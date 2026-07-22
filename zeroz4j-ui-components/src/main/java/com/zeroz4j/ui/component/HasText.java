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
import com.zeroz4j.ui.signals.Effect;

public interface HasText {

    Component getComponent();

    default void setText(String text) {
        getComponent().getElement().setTextContent(text);
    }
    
    default String getText() {
        return getComponent().getElement().getTextContent();
    }
    
    default void bindText(Signal<String> textSignal) {
        Effect.create(() -> setText(textSignal.get()));
    }
}
