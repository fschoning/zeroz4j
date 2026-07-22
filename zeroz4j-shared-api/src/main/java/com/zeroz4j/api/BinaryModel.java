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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import com.zeroz4j.api.BinaryPackable;
import java.nio.ByteBuffer;

/**
 * Marks a Data Transfer Object (DTO) or Entity class for Ahead-of-Time (AOT) binary serialization compilation.
 *
 * <p>The {@code zeroz4j-apt} annotation processor scans for this annotation during
 * the compilation phase. It generates a type-safe, reflection-free serializer 
 * ({@code <ClassName>_Serializer}) and {@link BinarySerializerDelegate} to pack and unpack the object's fields 
 * directly into WebAssembly linear memory or backend byte streams using a contiguous {@link ByteBuffer}.</p>
 *
 * <p>Classes annotated with {@code @BinaryModel} must also implement 
 * {@link BinaryPackable} and provide a public no-argument constructor.</p>
 *
 * <p><b>AI Agent Execution Notes:</b></p>
 * <ul>
 *   <li><b>APT Processing:</b> Triggers code generation in `RmiAnnotationProcessor` during `javac` execution.</li>
 *   <li><b>Serialization Contract:</b> All non-transient instance fields are serialized in declaration order.</li>
 * </ul>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BinaryModel {
}
