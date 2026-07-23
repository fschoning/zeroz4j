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
package com.zeroz4j.signals;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory and registry for shared signals.
 *
 * <p>Zeroz4j has exactly one signal abstraction. A signal created with
 * {@code new ValueSignal<>(x)} is local to wherever it lives — a client view or a server
 * service. A signal created with {@link #shared(String, Object)} is the same type, bound
 * to a wire identity; declare it once as a constant in the shared API module and reference
 * it from both tiers:</p>
 * <pre>{@code
 * // shared module
 * public final class JobSignals {
 *     public static final ValueSignal<JobStatus> STATUS =
 *             Signals.shared("job.status", JobStatus.idle());
 * }
 *
 * // server:  JobSignals.STATUS.set(next);        — broadcast, retention: framework's job
 * // client:  Effect.create(() -> bar.setValue(JobSignals.STATUS.get().getPercent()));
 * }</pre>
 *
 * <p>Because the shared module compiles into both tiers, each tier gets its own instance
 * of the constant bound to the same name; the installed {@link SignalTransport} gives it
 * its role. The server instance broadcasts on {@code set()} and retains the latest value;
 * the client instance mirrors it, receiving the retained value on subscribe. With no
 * transport installed (plain unit tests), a shared signal behaves as a local signal.</p>
 *
 * <p>Shared signals are server-authoritative in this release: a client-side {@code set()}
 * fails with {@link IllegalStateException}. Payloads must be wire-serializable
 * ({@code @BinaryModel} classes or types supported by {@code BinarySerializer}).</p>
 *
 * <p><b>AI Agent Execution Notes:</b></p>
 * <ul>
 *   <li><b>One Declaration:</b> The shared constant IS the signal — there is no separate
 *       topic object and no per-tier subscribe/publish call.</li>
 *   <li><b>Idempotent by Name:</b> Calling {@link #shared(String, Object)} twice with the
 *       same name returns the same instance.</li>
 * </ul>
 */
public final class Signals {

    private static final Map<String, SharedValueSignal<?>> registry = new ConcurrentHashMap<>();
    private static volatile SignalTransport transport;

    private Signals() {}

    /**
     * Declares (or returns the existing) shared signal bound to the given wire name.
     *
     * @param <T>          value type; must be wire-serializable
     * @param name         unique wire name, e.g. {@code "job.status"}
     * @param initialValue value both tiers hold until the first synchronization
     * @return the shared signal — use it exactly like any other {@link ValueSignal}
     * @throws IllegalArgumentException if {@code name} is null or blank
     */
    @SuppressWarnings("unchecked")
    public static synchronized <T> ValueSignal<T> shared(String name, T initialValue) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("shared signal name must not be null or blank");
        }
        SharedValueSignal<?> existing = registry.get(name);
        if (existing != null) {
            return (ValueSignal<T>) existing;
        }
        SharedValueSignal<T> signal = new SharedValueSignal<>(name, initialValue);
        registry.put(name, signal);
        SignalTransport current = transport;
        if (current != null) {
            current.onSharedSignalCreated(signal);
        }
        return signal;
    }

    /**
     * Installs the tier-specific transport. Called by the framework runtime (server engine
     * or Wasm client bootstrap), not by applications. Signals declared before installation
     * are replayed to the new transport.
     *
     * @param newTransport the transport, or null to detach
     */
    public static synchronized void installTransport(SignalTransport newTransport) {
        transport = newTransport;
        if (newTransport != null) {
            for (SharedValueSignal<?> signal : registry.values()) {
                newTransport.onSharedSignalCreated(signal);
            }
        }
    }

    /**
     * Looks up a shared signal by wire name.
     *
     * @param name wire name
     * @return the shared signal, or null if not declared in this runtime yet
     */
    public static SharedValueSignal<?> lookup(String name) {
        return registry.get(name);
    }

    static SignalTransport transport() {
        return transport;
    }

    /**
     * Clears the registry and transport. Test support only.
     */
    public static synchronized void resetForTesting() {
        registry.clear();
        transport = null;
    }
}
