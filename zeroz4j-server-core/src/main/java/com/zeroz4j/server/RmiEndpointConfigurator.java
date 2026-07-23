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

import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import java.security.Principal;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Server endpoint configurator that intercepts HTTP -> WebSocket upgrade handshakes and propagates security principals and roles.
 *
 * <p>Reads the authenticated HTTP principal and role set during the WebSocket upgrade handshake and copies them
 * into {@code ServerEndpointConfig.getUserProperties()} under {@link #PRINCIPAL_KEY} and {@link #ROLES_KEY}.</p>
 *
 * <p><b>AI Agent Execution Notes:</b></p>
 * <ul>
 *   <li><b>Handshake Interception:</b> Executed during HTTP GET upgrade to WebSocket before {@code @OnOpen}.</li>
 *   <li><b>Role Population:</b> Evaluates {@code HandshakeRequest.isUserInRole(role)} for each role registered in {@code knownRoles}.</li>
 * </ul>
 */
public class RmiEndpointConfigurator extends ServerEndpointConfig.Configurator {

    /** Key used to store the Principal in user properties map ("zeroz.principal"). */
    public static final String PRINCIPAL_KEY = "zeroz.principal";
    /** Key used to store the role set in user properties map ("zeroz.roles"). */
    public static final String ROLES_KEY = "zeroz.roles";

    /** Roles to check during handshake - populated by WasmRmiServerEngine at startup. */
    static final Set<String> knownRoles = new LinkedHashSet<>();

    /**
     * Modifies the WebSocket handshake request to store the caller's security principal and roles in user properties.
     *
     * @param config   the server endpoint configuration instance
     * @param request  the handshake HTTP request
     * @param response the handshake HTTP response
     *
     * <p><b>Under the hood:</b> Extracts {@code request.getUserPrincipal()}. Checks {@code request.isUserInRole(role)} against
     * {@code knownRoles}. Puts principal and userRoles set into {@code config.getUserProperties()}.</p>
     */
    @Override
    public void modifyHandshake(ServerEndpointConfig config,
                                HandshakeRequest request,
                                HandshakeResponse response) {
        super.modifyHandshake(config, request, response);
        Principal principal = request.getUserPrincipal();
        Set<String> userRoles = new LinkedHashSet<>();

        // Dev-mode fallback (Helidon has no servlet login): credentials arrive as
        // user/password query parameters on the WebSocket handshake and are validated
        // against DevAuth's demo users. Invalid credentials leave the session anonymous.
        if (principal == null && DevAuth.isDevMode()) {
            java.util.Map<String, java.util.List<String>> params = request.getParameterMap();
            String user = firstParam(params, "user");
            String password = firstParam(params, "password");
            Set<String> devRoles = DevAuth.authenticate(user, password);
            if (devRoles != null) {
                principal = () -> user;
                userRoles.addAll(devRoles);
            }
        }

        // Anonymous connections have no principal; user-properties maps may reject null
        // values (Tomcat/TomEE use a ConcurrentHashMap and NPE on put(key, null)).
        if (principal != null) {
            config.getUserProperties().put(PRINCIPAL_KEY, principal);
        }

        if (principal != null && userRoles.isEmpty()) {
            for (String role : knownRoles) {
                if (request.isUserInRole(role)) {
                    userRoles.add(role);
                }
            }
        }
        config.getUserProperties().put(ROLES_KEY, userRoles);
    }

    private static String firstParam(java.util.Map<String, java.util.List<String>> params, String name) {
        java.util.List<String> values = params != null ? params.get(name) : null;
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }
}
