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
package com.zeroz4j.ui.component;

import com.zeroz4j.ui.signals.Signal;

public interface HasValue<T> {

    T getValue();
    void setValue(T value);
    
    void bindValue(Signal<T> signal);
    
    // Support for Vaadin-style explicit value change listeners
    default void addValueChangeListener(ValueChangeListener<T> listener) {
        // default implementation does nothing or could hook into signals
        // Concrete components like TextField, Select, etc. should override this to listen to DOM events
    }
    
    @FunctionalInterface
    interface ValueChangeListener<T> {
        void valueChanged(ValueChangeEvent<T> event);
    }
    
    class ValueChangeEvent<T> {
        private final HasValue<T> source;
        private final T oldValue;
        private final T value;
        private final boolean isFromClient;

        public ValueChangeEvent(HasValue<T> source, T oldValue, T value, boolean isFromClient) {
            this.source = source;
            this.oldValue = oldValue;
            this.value = value;
            this.isFromClient = isFromClient;
        }

        public HasValue<T> getHasValue() { return source; }
        public T getOldValue() { return oldValue; }
        public T getValue() { return value; }
        public boolean isFromClient() { return isFromClient; }
    }
}
