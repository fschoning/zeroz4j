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
import jakarta.enterprise.context.ApplicationScoped;
import java.util.logging.Logger;
import java.util.logging.Level;
import jakarta.inject.Inject;
import jakarta.websocket.Session;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application-scoped CDI singleton managing real-time LiveSync broadcasts for tracked domain objects.
 *
 * <p><b>AI Agent Execution Notes:</b></p>
 * <ul>
 *   <li><b>LiveSync Propagation:</b> When backend code modifies an object annotated with {@code @LiveSync},
 *       calling {@link #notifyChanged(Object)} serializes the object state and pushes a 0x10 SUBSCRIBE/snapshot update
 *       frame across active client WebSocket sessions.</li>
 *   <li><b>Scope Filtering:</b> Supports {@code GLOBAL} (all connected clients), {@code SESSION} (specific session ID),
 *       or {@code USER} (specific authenticated username).</li>
 * </ul>
 */
@ApplicationScoped
public class SyncEngine {

    /**
     * Scope enumeration controlling the broadcast radius of LiveSync updates.
     */
    public enum SyncScope {
        /** Broadcast to all connected client sessions. */
        GLOBAL,
        /** Broadcast only to a specific WebSocket session ID. */
        SESSION,
        /** Broadcast only to sessions authenticated under a specific username. */
        USER
    }

    private static final Logger LOG = Logger.getLogger(SyncEngine.class.getName());

    @Inject
    ObjectMapper mapper;

    /** All active WebSocket sessions. */
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * Registers an active WebSocket session with the LiveSync engine.
     *
     * @param wsSession the opening WebSocket session
     *
     * <p><b>Under the hood:</b> Inserts {@code (wsSession.getId(), wsSession)} into {@code sessions} concurrent map.</p>
     */
    public void addSession(Session wsSession) {
        sessions.put(wsSession.getId(), wsSession);
        LOG.info("[zeroz4j-Sync] Session added: " + wsSession.getId());
    }

    /**
     * Unregisters a WebSocket session from the LiveSync engine upon disconnect.
     *
     * @param sessionId the closing session ID
     *
     * <p><b>Under the hood:</b> Removes {@code sessionId} key from {@code sessions} map.</p>
     */
    public void removeSession(String sessionId) {
        if (sessions.remove(sessionId) != null) {
            LOG.info("[zeroz4j-Sync] Session removed: " + sessionId);
        }
    }

    /**
     * Broadcasts a LiveSync update for a modified object globally to all connected clients.
     *
     * @param obj the modified domain model instance
     *
     * <p><b>Under the hood:</b> Delegates to {@link #notifyChanged(Object, SyncScope, String)} passing {@code SyncScope.GLOBAL} and {@code null}.</p>
     */
    public void notifyChanged(Object obj) {
        notifyChanged(obj, SyncScope.GLOBAL, null);
    }

    /**
     * Broadcasts a LiveSync update for a modified object to clients matching the target scope and filter identifier.
     *
     * @param obj    the modified domain model instance
     * @param scope  the broadcast radius scope (GLOBAL, SESSION, or USER)
     * @param target the target session ID or username (ignored if scope is GLOBAL)
     *
     * <p><b>Under the hood:</b> Looks up object ID in {@link ObjectMapper#getId(Object)}. Iterates through {@code sessions}.
     * Applies scope filtering. Construct binary SUBSCRIBE frame (0x10) containing serialized object payload, and transmits via {@link WsWrites#send}.</p>
     */
    public void notifyChanged(Object obj, SyncScope scope, String target) {
        String id = mapper.getId(obj);
        if (id == null) {
            return; // Not tracked by the ObjectMapper
        }

        for (Session session : sessions.values()) {
            // Apply scope filtering
            if (scope == SyncScope.SESSION && !session.getId().equals(target)) {
                continue;
            }
            if (scope == SyncScope.USER) {
                java.security.Principal p = (java.security.Principal) session.getUserProperties().get(RmiEndpointConfigurator.PRINCIPAL_KEY);
                if (p == null || !p.getName().equals(target)) {
                    continue;
                }
            }

            try {
                GrowableBuffer buffer = new GrowableBuffer();
                buffer.putInt(0); // reqId (0 for broadcast)
                buffer.put(SyncFrameTypes.SUBSCRIBE); // MSG_SYNC_UPDATE
                BinarySerializer.writeValue(buffer, obj, mapper);
                sendFrame(session, buffer.toByteArray());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "[zeroz4j-Sync] Sync notification error: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Thread-safe frame sending via the WebSocket async remote.
     */
    private void sendFrame(Session wsSession, byte[] frameData) {
        if (wsSession == null || !wsSession.isOpen()) {
            return;
        }
        WsWrites.send(wsSession, frameData);
    }
}
