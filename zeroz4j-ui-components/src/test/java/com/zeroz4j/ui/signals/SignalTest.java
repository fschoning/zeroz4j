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
package com.zeroz4j.ui.signals;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SignalTest {

    @Test
    public void testValueSignal() {
        ValueSignal<String> signal = new ValueSignal<>("Initial");
        assertEquals("Initial", signal.get());

        signal.set("Updated");
        assertEquals("Updated", signal.get());

        signal.update(s -> s + "!");
        assertEquals("Updated!", signal.get());
    }

    @Test
    public void testComputed() {
        ValueSignal<Integer> a = new ValueSignal<>(10);
        ValueSignal<Integer> b = new ValueSignal<>(20);

        Computed<Integer> sum = new Computed<>(() -> a.get() + b.get());

        assertEquals(30, sum.get());

        a.set(15);
        assertEquals(35, sum.get());

        b.set(25);
        assertEquals(40, sum.get());
    }

    @Test
    public void testEffect() {
        ValueSignal<Integer> counter = new ValueSignal<>(0);
        AtomicInteger runCount = new AtomicInteger(0);
        AtomicInteger lastValue = new AtomicInteger(-1);

        Effect.Disposable disposable = Effect.create(() -> {
            runCount.incrementAndGet();
            lastValue.set(counter.get());
        });

        // Effect runs immediately once
        assertEquals(1, runCount.get());
        assertEquals(0, lastValue.get());

        // Update value -> effect re-runs
        counter.set(1);
        assertEquals(2, runCount.get());
        assertEquals(1, lastValue.get());

        // Unchanged value -> no re-run
        counter.set(1);
        assertEquals(2, runCount.get());

        // Dispose effect
        disposable.dispose();
        counter.set(2);
        
        // No re-run after dispose
        assertEquals(2, runCount.get());
        assertEquals(1, lastValue.get());
    }

    @Test
    public void testNestedComputedAndEffect() {
        ValueSignal<String> first = new ValueSignal<>("Hello");
        ValueSignal<String> last = new ValueSignal<>("World");

        Computed<String> full = new Computed<>(() -> first.get() + " " + last.get());
        Computed<Integer> length = new Computed<>(() -> full.get().length());

        AtomicInteger effectRuns = new AtomicInteger(0);

        Effect.Disposable disposable = Effect.create(() -> {
            effectRuns.incrementAndGet();
            length.get(); // Depend on length
        });

        assertEquals(1, effectRuns.get());
        assertEquals(11, length.get());

        first.set("Hi"); // "Hi World" -> length 8
        assertEquals(2, effectRuns.get());
        assertEquals(8, length.get());

        disposable.dispose();
    }
}
