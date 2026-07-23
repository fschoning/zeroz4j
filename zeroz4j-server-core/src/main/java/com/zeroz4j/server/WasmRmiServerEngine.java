/*
 * Copyright 2026 Franz Schöning
 * Project: https://www.zeroz4j.com
 * Author: Franz Schöning - Principal Enterprise Architect (https://www.franzschoning.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zeroz4j.server;

import com.zeroz4j.api.BinarySerializer;
import com.zeroz4j.api.GrowableBuffer;
import com.zeroz4j.api.ObjectMapper;
import com.zeroz4j.api.SyncFrameTypes;
import com.zeroz4j.api.EventTopic;
import com.zeroz4j.api.RmiService;
import com.zeroz4j.api.RolesAllowed;
import com.zeroz4j.api.Secured;
import jakarta.annotation.PostConstruct;
import java.util.logging.Logger;
import java.util.logging.Level;
import jakarta.inject.Inject;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.RejectedExecutionException;

/**
 * Server-side Jakarta EE WebSocket endpoint for zeroz4j.
 * Listens on '/wasm-rmi' and uses Project Loom's Virtual Threads to dispatch
 * binary RPC method invocations to CDI beans.
 *
 * <p><b>AI Agent Execution Notes:</b></p>
 * <ul>
 *   <li><b>CDI Discovery & Scanning:</b> At startup ({@link #scanServiceRegistry()}), scans the CDI bean manager for beans implementing {@link RmiService}
 *       interfaces, builds service/method reflection registries, and populates security whitelists ({@code @Secured}, {@code @RolesAllowed}).</li>
 *   <li><b>Virtual Thread Concurrency:</b> Per-session {@link ExecutorService} (Project Loom virtual threads) handles inbound frames without blocking I/O threads.</li>
 *   <li><b>Frame Dispatch:</b> Operates on incoming binary frames in {@link #processIncomingBinaryPayload(ByteBuffer, Session)}. Reads correlation ID, interface name, method name, unmarshals arguments using {@link BinarySerializer}, enforces security, populates {@link RmiRequestContext}, invokes method via reflection, and writes return value (0x01 SUCCESS) or exception (0x0F ERROR).</li>
 * </ul>
 */
@ServerEndpoint(value = "/wasm-rmi", configurator = RmiEndpointConfigurator.class)
@ApplicationScoped
public class WasmRmiServerEngine implements EventPublisher {

    private static final Logger LOG = Logger.getLogger(WasmRmiServerEngine.class.getName());

    @Inject
    SyncEngine syncEngine;

    @Inject
    ObjectMapper mapper;

    @Inject
    LiveMutexManager liveMutexManager;

    /**
     * STATIC on purpose: Tomcat's WebSocket container instantiates a @ServerEndpoint PER
     * CONNECTION (the configurator does not override getEndpointInstance), while pushers
     * obtain the CDI @ApplicationScoped instance - a per-instance set left broadcastPush
     * talking to an always-empty list. Shared statically, every instance sees every session.
     */
    private static final Set<Session> activeSessions = ConcurrentHashMap.newKeySet();
    /** Interface FQCN -> CDI bean instance */
    private final Map<String, Object> serviceRegistry = new ConcurrentHashMap<>();
    /** Interface FQCN -> { methodName -> Method } */
    private final Map<String, Map<String, Method>> methodRegistry = new ConcurrentHashMap<>();
    /** Interface FQCN -> whether the interface has @Secured */
    private final Map<String, Boolean> securedInterfaces = new ConcurrentHashMap<>();
    /** Interface FQCN -> interface-level @RolesAllowed roles (empty if not set) */
    private final Map<String, Set<String>> interfaceRoles = new ConcurrentHashMap<>();
    /** "interfaceFQCN#methodName" -> method-level @RolesAllowed roles */
    private final Map<String, Set<String>> methodRoles = new ConcurrentHashMap<>();
    /** "interfaceFQCN#methodName" -> whether method has @Secured */
    private final Map<String, Boolean> securedMethods = new ConcurrentHashMap<>();

    /** Structured Concurrency: Map each WebSocket session to its own Virtual Thread Executor */
    private final Map<String, ExecutorService> sessionExecutors = new ConcurrentHashMap<>();

    /**
     * Scans CDI container for beans implementing {@code @RmiService}-annotated interfaces
     * and registers them in the service/method whitelist. Also collects security
     * annotations and populates the known roles for the handshake configurator.
     *
     * <p><b>Under the hood:</b> Executed automatically via {@code @PostConstruct}. Queries {@link BeanManager#getBeans(Class)}.
     * Populates {@code serviceRegistry}, {@code methodRegistry}, {@code securedInterfaces}, and {@code interfaceRoles}.</p>
     */
    @PostConstruct
    public void scanServiceRegistry() {
        try {
            BeanManager bm = CDI.current().getBeanManager();
            for (Bean<?> bean : bm.getBeans(Object.class)) {
                Class<?> beanClass = bean.getBeanClass();
                for (Class<?> iface : beanClass.getInterfaces()) {
                    if (iface.isAnnotationPresent(RmiService.class)) {
                        String ifaceName = iface.getName();
                        Object instance = CDI.current().select(iface).get();
                        serviceRegistry.put(ifaceName, instance);

                        // Interface-level security
                        boolean ifaceSecured = iface.isAnnotationPresent(Secured.class);
                        securedInterfaces.put(ifaceName, ifaceSecured);

                        RolesAllowed ifaceRolesAnn = iface.getAnnotation(RolesAllowed.class);
                        if (ifaceRolesAnn != null) {
                            Set<String> roles = new HashSet<>(Arrays.asList(ifaceRolesAnn.value()));
                            interfaceRoles.put(ifaceName, roles);
                            RmiEndpointConfigurator.knownRoles.addAll(roles);
                            securedInterfaces.put(ifaceName, true); // @RolesAllowed implies @Secured
                        } else {
                            interfaceRoles.put(ifaceName, Collections.emptySet());
                        }

                        Map<String, Method> methods = new ConcurrentHashMap<>();
                        for (Method m : iface.getDeclaredMethods()) {
                            methods.put(m.getName(), m);
                            String methodKey = ifaceName + "#" + m.getName();

                            // Method-level security
                            securedMethods.put(methodKey, m.isAnnotationPresent(Secured.class));
                            RolesAllowed methodRolesAnn = m.getAnnotation(RolesAllowed.class);
                            if (methodRolesAnn != null) {
                                Set<String> roles = new HashSet<>(Arrays.asList(methodRolesAnn.value()));
                                methodRoles.put(methodKey, roles);
                                RmiEndpointConfigurator.knownRoles.addAll(roles);
                                securedMethods.put(methodKey, true);
                            }
                        }
                        methodRegistry.put(ifaceName, methods);

                        LOG.info("[zeroz4j] Registered RMI service: " + ifaceName
                            + " -> " + beanClass.getName()
                            + (ifaceSecured ? " [SECURED]" : ""));
                    }
                }
            }
        } catch (Exception e) {
            LOG.warning("[zeroz4j] Warning: CDI service scan deferred: " + e.getMessage());
        }
    }

    /**
     * Shuts down all virtual thread executors gracefully upon bean destruction.
     *
     * <p><b>Under the hood:</b> Executed via {@code @PreDestroy}. Iterates through {@code sessionExecutors} and calls {@code shutdownNow()}.</p>
     */
    @PreDestroy
    public void shutdown() {
        for (ExecutorService exec : sessionExecutors.values()) {
            exec.shutdownNow();
        }
        sessionExecutors.clear();
    }

    /**
     * Handles WebSocket connection open lifecycle events.
     *
     * @param session the newly connected WebSocket session
     * @param config  the endpoint configuration containing handshake user properties
     *
     * <p><b>Under the hood:</b> Adds session to {@code activeSessions}, creates a virtual thread executor for the session in {@code sessionExecutors},
     * transmits an AUTH frame (0x03) with principal and roles to the client, and registers the session with {@code syncEngine}.</p>
     */
    @OnOpen
    @SuppressWarnings("unchecked")
    public void onOpen(Session session, EndpointConfig config) {
        activeSessions.add(session);
        sessionExecutors.put(session.getId(), Executors.newVirtualThreadPerTaskExecutor());

        // Propagate principal and roles from handshake
        Principal principal = (Principal) config.getUserProperties().get(RmiEndpointConfigurator.PRINCIPAL_KEY);
        Set<String> roles = (Set<String>) config.getUserProperties().get(RmiEndpointConfigurator.ROLES_KEY);
        if (roles == null) roles = Collections.emptySet();

        // Anonymous connections have no principal; Tomcat's user-properties map NPEs on null values.
        if (principal != null) {
            session.getUserProperties().put(RmiEndpointConfigurator.PRINCIPAL_KEY, principal);
        }
        session.getUserProperties().put(RmiEndpointConfigurator.ROLES_KEY, roles);

        // Send AUTH frame (0x03) to client
        String username = principal != null ? principal.getName() : "anonymous";
        sendAuthFrame(session, username, roles);

        LOG.info("[zeroz4j] Client connected: " + username + " roles=" + roles);

        // LiveSync: add session to SyncEngine
        syncEngine.addSession(session);
    }

    /**
     * Handles WebSocket connection closure lifecycle events.
     *
     * @param session the closing WebSocket session
     *
     * <p><b>Under the hood:</b> Removes session from {@code activeSessions}, shuts down virtual thread executor,
     * releases all live mutex locks owned by the session via {@link LiveMutexManager#releaseAll}, and unregisters session from {@code syncEngine}.</p>
     */
    @OnClose
    public void onClose(Session session) {
        activeSessions.remove(session);

        // Structured Concurrency: Terminate all tasks for this session
        ExecutorService sessionExecutor = sessionExecutors.remove(session.getId());
        if (sessionExecutor != null) {
            sessionExecutor.shutdownNow();
        }

        // Release any distributed locks held by this session
        if (liveMutexManager != null) {
            liveMutexManager.releaseAll("session:" + session.getId());
        }

        // LiveSync: clean up the SyncSession
        syncEngine.removeSession(session.getId());
    }

    private void sendAuthFrame(Session session, String username, Set<String> roles) {
        try {
            GrowableBuffer buffer = new GrowableBuffer();
            buffer.putInt(0); // no correlation ID
            buffer.put(SyncFrameTypes.AUTH); // AUTH frame type
            buffer.put((byte) 1); // protocol version
            BinarySerializer.writeString(buffer, username);
            buffer.putInt(roles.size());
            for (String role : roles) {
                BinarySerializer.writeString(buffer, role);
            }
            WsWrites.send(session, buffer.toByteArray());
        } catch (Exception e) {
            LOG.warning("[zeroz4j] Failed to send AUTH frame: " + e.getMessage());
        }
    }

    /**
     * Broadcasts a server-initiated push notification to all active client sessions.
     *
     * @param topic   the notification topic string
     * @param payload the message payload object
     *
     * <p><b>Under the hood:</b> Iterates through {@code activeSessions} and calls {@link #sendPush(Session, String, Object)} for each.</p>
     */
    public void broadcastPush(String topic, Object payload) {
        for (Session session : activeSessions) {
            sendPush(session, topic, payload);
        }
    }

    /**
     * Broadcasts a typed event to all active client sessions.
     *
     * @param <T>     payload type bound by the topic
     * @param topic   shared {@link EventTopic} descriptor
     * @param payload the payload to broadcast; may be null for {@code EventTopic<Void>} events
     *
     * <p><b>Under the hood:</b> Delegates to {@link #broadcastPush(String, Object)} using {@link EventTopic#name()}.</p>
     */
    @Override
    public <T> void publish(EventTopic<T> topic, T payload) {
        broadcastPush(topic.name(), payload);
    }

    /**
     * Sends a server-initiated push notification to a specific client session.
     *
     * @param session the target client WebSocket session
     * @param topic   the notification topic string
     * @param payload the message payload object
     *
     * <p><b>Under the hood:</b> Checks {@code session.isOpen()}. Serializes push frame (0x02 + topic string + serialized payload)
     * using {@link BinarySerializer#writeValue(GrowableBuffer, Object, ObjectMapper)} and transmits via {@link WsWrites#send}.</p>
     */
    public void sendPush(Session session, String topic, Object payload) {
        if (!session.isOpen()) {
            activeSessions.remove(session);
            return;
        }
        try {
            GrowableBuffer buffer = new GrowableBuffer();
            buffer.putInt(0);
            buffer.put(SyncFrameTypes.RPC_PUSH);
            BinarySerializer.writeString(buffer, topic);
            BinarySerializer.writeValue(buffer, payload, mapper);
            WsWrites.send(session, buffer.toByteArray());
        } catch (Exception e) {
            LOG.warning("[zeroz4j] Push error for session " + session.getId() + ": " + e.getMessage());
        }
    }

    /**
     * Processes incoming binary RPC frames from WebSocket clients.
     * Enforces {@code @Secured} and {@code @RolesAllowed} security rules prior to invocation.
     *
     * @param payload binary payload buffer received from client
     * @param session active WebSocket session
     *
     * <p><b>Under the hood:</b> Submits processing task to session's virtual thread executor. Parses correlation ID,
     * interface name, method name, and arguments. Validates against service and method registries. Checks security principal
     * and roles. Sets thread-local {@link RmiRequestContext}. Invokes target method via reflection. Writes success frame (0x01)
     * or error frame (0x0F) back to client.</p>
     */
    @OnMessage
    @SuppressWarnings("unchecked")
    public void processIncomingBinaryPayload(ByteBuffer payload, Session session) {
        byte[] data = new byte[payload.remaining()];
        payload.get(data);

        ExecutorService sessionExecutor = sessionExecutors.get(session.getId());
        if (sessionExecutor == null) {
            return; // Session is already closing or closed
        }

        try {
            sessionExecutor.submit(() -> {

                ByteBuffer buffer = ByteBuffer.wrap(data);
                int messageId = buffer.getInt();

                try {
                    String interfaceName = BinarySerializer.readString(buffer);
                    String methodName = BinarySerializer.readString(buffer);
                    int argumentCount = buffer.getInt();

                    // Validate against service whitelist
                    Object beanInstance = serviceRegistry.get(interfaceName);
                    if (beanInstance == null) {
                        throw new SecurityException("Rejected RMI call to unregistered service: " + interfaceName);
                    }

                    Map<String, Method> methods = methodRegistry.get(interfaceName);
                    Method targetMethod = methods != null ? methods.get(methodName) : null;
                    if (targetMethod == null) {
                        throw new NoSuchMethodException("No method '" + methodName + "' on service: " + interfaceName);
                    }

                    // --- Security enforcement ---
                    String methodKey = interfaceName + "#" + methodName;
                    Principal principal = (Principal) session.getUserProperties().get(RmiEndpointConfigurator.PRINCIPAL_KEY);
                    Set<String> userRoles = (Set<String>) session.getUserProperties().get(RmiEndpointConfigurator.ROLES_KEY);
                    if (userRoles == null) userRoles = Collections.emptySet();

                    // Check @Secured (interface or method level)
                    boolean requiresAuth = Boolean.TRUE.equals(securedInterfaces.get(interfaceName))
                                        || Boolean.TRUE.equals(securedMethods.get(methodKey));
                    if (requiresAuth && principal == null) {
                        throw new SecurityException("Authentication required for: " + interfaceName + "#" + methodName);
                    }

                    // Check @RolesAllowed (method-level overrides interface-level)
                    Set<String> requiredRoles = methodRoles.getOrDefault(methodKey, Collections.emptySet());
                    if (requiredRoles.isEmpty()) {
                        requiredRoles = interfaceRoles.getOrDefault(interfaceName, Collections.emptySet());
                    }
                    if (!requiredRoles.isEmpty()) {
                        boolean hasRole = false;
                        for (String required : requiredRoles) {
                            if (userRoles.contains(required)) {
                                hasRole = true;
                                break;
                            }
                        }
                        if (!hasRole) {
                            throw new SecurityException("Access denied: requires role "
                                + requiredRoles + " but user has " + userRoles);
                        }
                    }
                    // --- End security enforcement ---

                    Object[] extractedArguments = new Object[argumentCount];
                    for (int i = 0; i < argumentCount; i++) {
                        extractedArguments[i] = BinarySerializer.readValue(buffer, mapper);
                    }

                    Object executionResult;
                    try {
                        RmiRequestContext.setContext(principal, userRoles, session.getId());
                        executionResult = targetMethod.invoke(beanInstance, extractedArguments);
                    } finally {
                        RmiRequestContext.clear();
                    }

                    GrowableBuffer responseBuffer = new GrowableBuffer();
                    responseBuffer.putInt(messageId);
                    responseBuffer.put(SyncFrameTypes.RPC_RESPONSE);
                    BinarySerializer.writeValue(responseBuffer, executionResult, mapper);
                    WsWrites.send(session, responseBuffer.toByteArray());

                } catch (Throwable ex) {
                    Throwable actual = ex;
                    if (ex instanceof InvocationTargetException) {
                        actual = ex.getCause();
                    }
                    LOG.log(Level.SEVERE, "[zeroz4j] RMI error: " + actual.getMessage(), actual);

                    try {
                        GrowableBuffer errorBuffer = new GrowableBuffer(512);
                        errorBuffer.putInt(messageId);
                        errorBuffer.put(SyncFrameTypes.RPC_ERROR);
                        BinarySerializer.writeString(errorBuffer,
                            actual.getMessage() != null ? actual.getMessage() : actual.toString());
                        WsWrites.send(session, errorBuffer.toByteArray());
                    } catch (Exception ioEx) {
                        LOG.warning("[zeroz4j] Failed to send error: " + ioEx.getMessage());
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.warning("[zeroz4j] Dropped incoming message because server is shutting down.");
        }
    }
}
