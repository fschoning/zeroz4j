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
package com.zeroz4j.ui.binding;

import com.zeroz4j.ui.component.Component;
import com.zeroz4j.ui.component.HasValue;

import java.util.ArrayList;
import java.util.List;
import com.zeroz4j.ui.component.HasStyle;

/**
 * Type-safe data binding framework binding domain model objects (beans) to zeroz4j UI components implementing {@link HasValue}.
 *
 * <p>Supports validation, custom converters, required-field checks, automatic write-through on change, and visual error indicators.</p>
 *
 * <p><b>AI Agent Execution Notes:</b></p>
 * <ul>
 *   <li><b>Data Flow:</b> {@link #readBean(Object)} copies properties from domain bean into UI fields. {@link #writeBean(Object)} validates fields and writes UI values back into domain bean.</li>
 *   <li><b>Live Model Updates:</b> Value change listeners registered in {@code bind()} auto-write back to the active bean if validation passes.</li>
 *   <li><b>Styling Integration:</b> Components implementing {@link HasStyle} receive the CSS class {@code "input-error"} when validation fails.</li>
 * </ul>
 *
 * @param <BEAN> the domain model class type being bound
 */
public class Binder<BEAN> {
    
    private BEAN bean;
    private final List<Binding<BEAN, ?>> bindings = new ArrayList<>();

    /**
     * Default constructor creating an unbound {@link Binder} instance.
     */
    public Binder() {
    }

    /**
     * Configures a binding builder for a target UI component implementing {@link HasValue}.
     *
     * @param <FIELDVALUE> the value type of the UI field
     * @param field        the UI component instance
     * @return a new {@link BindingBuilder} for configuring validators and property bindings
     */
    public <FIELDVALUE> BindingBuilder<BEAN, FIELDVALUE> forField(HasValue<FIELDVALUE> field) {
        return new BindingBuilderImpl<>(field);
    }

    /**
     * Binds a target domain bean to this binder and reads its property values into all bound UI fields.
     *
     * @param bean the domain model bean instance to bind
     *
     * <p><b>Under the hood:</b> Sets internal {@code bean} reference and executes {@link #readBean(Object)}.</p>
     */
    public void setBean(BEAN bean) {
        this.bean = bean;
        readBean(bean);
    }

    /**
     * Retrieves the currently bound domain model bean instance.
     *
     * @return bound bean instance, or {@code null} if unbound
     */
    public BEAN getBean() {
        return bean;
    }

    /**
     * Reads values from the specified domain bean into the registered UI fields without changing the active bean binding.
     *
     * @param bean domain bean instance to read from
     *
     * <p><b>Under the hood:</b> Iterates through {@code bindings} list and calls {@code binding.read(bean)}.</p>
     */
    public void readBean(BEAN bean) {
        for (Binding<BEAN, ?> binding : bindings) {
            binding.read(bean);
        }
    }

    /**
     * Validates all bound UI fields and writes their values to the target domain bean if all validations pass.
     *
     * @param bean domain bean instance to receive field values
     * @throws ValidationException if one or more field validations fail
     *
     * <p><b>Under the hood:</b> Evaluates {@code binding.validate()} for all bindings. Collects errors into a list.
     * Throws {@link ValidationException} if non-empty; otherwise executes {@code binding.write(bean)} for all bindings.</p>
     */
    public void writeBean(BEAN bean) throws ValidationException {
        List<ValidationResult> errors = new ArrayList<>();
        for (Binding<BEAN, ?> binding : bindings) {
            ValidationResult result = binding.validate();
            if (result.isError()) {
                errors.add(result);
            }
        }
        
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        
        for (Binding<BEAN, ?> binding : bindings) {
            binding.write(bean);
        }
    }

    /**
     * Writes UI field values to the target domain bean if valid, returning a boolean status instead of throwing an exception.
     *
     * @param bean domain bean instance to receive field values
     * @return true if write succeeded; false if validation failed
     */
    public boolean writeBeanIfValid(BEAN bean) {
        try {
            writeBean(bean);
            return true;
        } catch (ValidationException e) {
            return false;
        }
    }

    /**
     * Validates all bound UI fields and returns a {@link BinderValidationStatus} report.
     *
     * @return validation status report containing any validation errors
     */
    public BinderValidationStatus<BEAN> validate() {
        List<ValidationResult> errors = new ArrayList<>();
        for (Binding<BEAN, ?> binding : bindings) {
            ValidationResult result = binding.validate();
            if (result.isError()) {
                errors.add(result);
            }
        }
        return new BinderValidationStatus<>(errors);
    }

    private class BindingBuilderImpl<FIELDVALUE> implements BindingBuilder<BEAN, FIELDVALUE> {
        private final HasValue<FIELDVALUE> field;
        private final List<Validator<? super FIELDVALUE>> validators = new ArrayList<>();
        private boolean isRequired = false;
        private String requiredMessage = "This field is required";

        public BindingBuilderImpl(HasValue<FIELDVALUE> field) {
            this.field = field;
        }

        @Override
        public BindingBuilder<BEAN, FIELDVALUE> withValidator(Validator<? super FIELDVALUE> validator) {
            validators.add(validator);
            return this;
        }

        @Override
        public BindingBuilder<BEAN, FIELDVALUE> asRequired(String errorMessage) {
            this.isRequired = true;
            this.requiredMessage = errorMessage;
            return this;
        }

        @Override
        public Binding<BEAN, FIELDVALUE> bind(ValueProvider<BEAN, FIELDVALUE> getter, Setter<BEAN, FIELDVALUE> setter) {
            BindingImpl<FIELDVALUE> binding = new BindingImpl<>(field, getter, setter, validators, isRequired, requiredMessage);
            bindings.add(binding);
            
            field.addValueChangeListener(event -> {
                ValidationResult vr = binding.validate();
                if (Binder.this.bean != null && !vr.isError()) {
                    binding.write(Binder.this.bean);
                }
            });
            
            return binding;
        }
    }

    private class BindingImpl<FIELDVALUE> implements Binding<BEAN, FIELDVALUE> {
        private final HasValue<FIELDVALUE> field;
        private final ValueProvider<BEAN, FIELDVALUE> getter;
        private final Setter<BEAN, FIELDVALUE> setter;
        private final List<Validator<? super FIELDVALUE>> validators;
        private final boolean isRequired;
        private final String requiredMessage;

        public BindingImpl(HasValue<FIELDVALUE> field, ValueProvider<BEAN, FIELDVALUE> getter, Setter<BEAN, FIELDVALUE> setter, List<Validator<? super FIELDVALUE>> validators, boolean isRequired, String requiredMessage) {
            this.field = field;
            this.getter = getter;
            this.setter = setter;
            this.validators = validators;
            this.isRequired = isRequired;
            this.requiredMessage = requiredMessage;
        }

        @Override
        public HasValue<FIELDVALUE> getField() {
            return field;
        }

        @Override
        public ValidationResult validate() {
            FIELDVALUE value = field.getValue();
            
            // Check required
            if (isRequired && (value == null || (value instanceof String && ((String) value).trim().isEmpty()))) {
                ValidationResult res = ValidationResult.error(requiredMessage);
                showError(res);
                return res;
            }
            
            ValueContext ctx = new ValueContext(field instanceof Component ? (Component) field : null, field);
            for (Validator<? super FIELDVALUE> validator : validators) {
                ValidationResult result = validator.apply(value, ctx);
                if (result.isError()) {
                    showError(result);
                    return result;
                }
            }
            
            clearError();
            return ValidationResult.ok();
        }

        @Override
        public void read(BEAN bean) {
            if (bean == null) {
                field.setValue(null);
                clearError();
            } else {
                field.setValue(getter.apply(bean));
                clearError();
            }
        }

        @Override
        public void write(BEAN bean) {
            if (setter != null) {
                setter.accept(bean, field.getValue());
            }
        }
        
        private void showError(ValidationResult result) {
            if (field instanceof HasStyle) {
                HasStyle style = (HasStyle) field;
                style.addClassName("input-error");
                style.setStyle("--error-message", "'" + result.getErrorMessage().replace("'", "\\'") + "'");
            }
        }
        
        private void clearError() {
            if (field instanceof HasStyle) {
                HasStyle style = (HasStyle) field;
                style.removeClassName("input-error");
            }
        }
    }

    /**
     * Removes a specific field binding from this binder instance.
     *
     * @param binding the binding handle to remove
     */
    public void removeBinding(Binding<BEAN, ?> binding) {
        bindings.remove(binding);
    }

    /**
     * Removes all registered field bindings from this binder instance.
     */
    public void removeAllBindings() {
        bindings.clear();
    }
}
