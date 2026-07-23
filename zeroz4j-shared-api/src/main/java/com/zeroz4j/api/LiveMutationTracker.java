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
package com.zeroz4j.api;

/**
 * Static hook through which APT-generated live subclasses report field changes.
 *
 * <p>Generated {@code <Model>_Live} setter overrides call {@link #fieldChanged(Object)}
 * after assigning. The Wasm client installs a {@link Listener} that forwards mutations to
 * the server; with no listener installed (server tier, plain unit tests) the hook is a
 * no-op. Framework-internal except for {@link #touch(Object)}, the application-facing
 * escape hatch for in-place collection edits the setters cannot observe.</p>
 */
public final class LiveMutationTracker {

    /** Receives change notifications for live instances. */
    @FunctionalInterface
    public interface Listener {
        /**
         * Invoked after a tracked field assignment.
         *
         * @param liveObject the mutated live instance
         */
        void changed(Object liveObject);
    }

    private static volatile Listener listener;
    private static final ThreadLocal<Boolean> applyingRemote = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private LiveMutationTracker() {}

    /**
     * Installs the mutation listener. Called by the client runtime, not applications.
     *
     * @param newListener the listener, or null to detach
     */
    public static void install(Listener newListener) {
        listener = newListener;
    }

    /**
     * Reports a field change on a live instance. Called by generated setter overrides.
     *
     * @param liveObject the mutated instance
     */
    public static void fieldChanged(Object liveObject) {
        if (applyingRemote.get()) {
            return; // change originated from an inbound sync — do not echo it back
        }
        Listener current = listener;
        if (current != null) {
            current.changed(liveObject);
        }
    }

    /**
     * Marks a live instance as changed without going through a setter — the escape hatch
     * for in-place collection mutations (e.g. after {@code obj.getTags().add(...)}).
     *
     * @param liveObject the mutated instance
     */
    public static void touch(Object liveObject) {
        fieldChanged(liveObject);
    }

    /**
     * Suppresses change reporting on the current thread while an inbound remote state is
     * applied through the setters. Framework-internal; always pair with
     * {@link #endRemoteApply()} in a finally block.
     */
    public static void beginRemoteApply() {
        applyingRemote.set(Boolean.TRUE);
    }

    /** Ends the suppression started by {@link #beginRemoteApply()}. */
    public static void endRemoteApply() {
        applyingRemote.set(Boolean.FALSE);
    }
}
