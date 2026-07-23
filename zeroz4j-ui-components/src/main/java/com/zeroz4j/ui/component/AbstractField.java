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

import com.zeroz4j.signals.Signal;
import com.zeroz4j.signals.ValueSignal;
import com.zeroz4j.signals.Effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public abstract class AbstractField<C extends Component, T> extends Component implements HasValue<T>, HasStyle, HasEnabled, HasSize, Focusable {

    private T value;
    private final T emptyValue;
    private final List<ValueChangeListener<T>> listeners = new ArrayList<>();
    private ValueSignal<T> modelSignal;
    private boolean signalUpdating = false;
    
    public AbstractField(String tagName, T emptyValue) {
        super(tagName);
        this.emptyValue = emptyValue;
        this.value = emptyValue;
        addClassName("form-control");
    }

    @Override
    public Component getComponent() {
        return this;
    }
    
    public T getEmptyValue() {
        return emptyValue;
    }
    
    @Override
    public T getValue() {
        return value;
    }
    
    @Override
    public void setValue(T value) {
        setModelValue(value, false);
    }

    @Override
    public void addValueChangeListener(ValueChangeListener<T> listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    @Override
    public void bindValue(Signal<T> signal) {
        if (signal instanceof ValueSignal) {
            this.modelSignal = (ValueSignal<T>) signal;
            Effect.create(() -> {
                signalUpdating = true;
                try {
                    setValue(this.modelSignal.get());
                } finally {
                    signalUpdating = false;
                }
            });
        } else {
            // Read-only binding
            Effect.create(() -> {
                signalUpdating = true;
                try {
                    setValue(signal.get());
                } finally {
                    signalUpdating = false;
                }
            });
        }
    }
    
    protected void setModelValue(T value, boolean isFromClient) {
        T oldValue = this.value;
        this.value = value;
        if (!Objects.equals(oldValue, value)) {
            if (!isFromClient) {
                setPresentationValue(value);
            }
            
            ValueChangeEvent<T> event = new ValueChangeEvent<>(this, oldValue, value, isFromClient);
            for (ValueChangeListener<T> listener : listeners) {
                listener.valueChanged(event);
            }
            
            if (this.modelSignal != null && isFromClient && !signalUpdating) {
                this.modelSignal.set(value);
            }
        }
    }
    
    protected abstract void setPresentationValue(T newPresentationValue);
}
