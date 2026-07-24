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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the 0.3.0 serializer extension: native {@link UUID} and {@link Instant} tags plus
 * TeaVM-safe {@link Enum} handling (registry-resolver {@code TAG_ENUM} path for enums inside
 * generic containers). Also asserts backward compatibility with pre-extension payloads.
 */
public class BinarySerializerTypesTest {

    public enum Priority { LOW, MEDIUM, HIGH }

    @BeforeAll
    public static void setup() {
        // Mirrors what the generated registrar does: register a reflection-free enum resolver.
        BinaryRegistry.registerEnum(Priority.class.getName(), Priority::valueOf);
    }

    @Test
    public void testUuidRoundTrip() {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        assertEquals(id, roundTrip(id));
        assertEquals(id, roundTripGrowable(id));
    }

    @Test
    public void testUuidEncodedAsTeaVmSafeStringForm() {
        // TeaVM does not emulate UUID.getMostSignificantBits()/new UUID(long,long), so the wire
        // encoding must be the canonical string form for the same code to link on the Wasm client.
        // A pure JVM round-trip passes with either encoding, so pin the wire contract explicitly:
        // this fails if a future change reverts to the two-longs binary form.
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        BinarySerializer.writeValue(buffer, id, new ObjectMapper());
        buffer.flip();
        assertEquals(BinarySerializer.TAG_UUID, buffer.get());
        assertEquals(id.toString(), BinarySerializer.readString(buffer));
    }

    @Test
    public void testInstantRoundTrip() {
        Instant now = Instant.ofEpochSecond(1_753_000_000L, 123_456_789);
        assertEquals(now, roundTrip(now));
        assertEquals(now, roundTripGrowable(now));

        // Epoch and a zero-nanos instant.
        assertEquals(Instant.EPOCH, roundTrip(Instant.EPOCH));
        Instant whole = Instant.ofEpochSecond(42L);
        assertEquals(whole, roundTrip(whole));
    }

    @Test
    public void testEnumRoundTrip() {
        for (Priority p : Priority.values()) {
            assertEquals(p, roundTrip(p));
            assertEquals(p, roundTripGrowable(p));
        }
    }

    @Test
    public void testListOfEnums() {
        List<Priority> list = new ArrayList<>();
        list.add(Priority.HIGH);
        list.add(Priority.LOW);
        list.add(Priority.MEDIUM);

        @SuppressWarnings("unchecked")
        List<Object> read = (List<Object>) roundTrip(list);
        assertEquals(list, read);
    }

    @Test
    public void testMapOfUuidToInstant() {
        Map<UUID, Instant> map = new LinkedHashMap<>();
        map.put(UUID.randomUUID(), Instant.ofEpochSecond(1_000, 5));
        map.put(UUID.randomUUID(), Instant.ofEpochSecond(2_000, 750_000_000));

        @SuppressWarnings("unchecked")
        Map<Object, Object> read = (Map<Object, Object>) roundTrip(map);
        assertEquals(map, read);
    }

    @Test
    public void testNullEnumRemainsNull() {
        Priority nothing = null;
        // A null value is written as TAG_NULL by writeValue, so it comes back null.
        assertNull(roundTrip(nothing));
    }

    @Test
    public void testUnregisteredEnumTagThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> BinaryRegistry.resolveEnum("com.example.NotRegistered", "X"));
    }

    @Test
    public void testBackwardCompatibleOldPayload() {
        // Build a payload using only pre-0.3.0 tags (int, String, list of primitives).
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        ObjectMapper mapper = new ObjectMapper();
        BinarySerializer.writeValue(buffer, 7, mapper);
        BinarySerializer.writeValue(buffer, "legacy", mapper);
        BinarySerializer.writeValue(buffer, List.of(1, 2, 3), mapper);
        buffer.flip();

        assertEquals(7, BinarySerializer.readValue(buffer, mapper));
        assertEquals("legacy", BinarySerializer.readValue(buffer, mapper));
        assertEquals(List.of(1, 2, 3), BinarySerializer.readValue(buffer, mapper));
    }

    @Test
    public void testTagValuesUnchangedAndNewTagsAssigned() {
        // Existing tags must not shift; new tags occupy 0x0F–0x11.
        assertEquals(0x0E, BinarySerializer.TAG_REF);
        assertEquals(0x0F, BinarySerializer.TAG_UUID);
        assertEquals(0x10, BinarySerializer.TAG_INSTANT);
        assertEquals(0x11, BinarySerializer.TAG_ENUM);
    }

    private static Object roundTrip(Object value) {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        ObjectMapper mapper = new ObjectMapper();
        BinarySerializer.writeValue(buffer, value, mapper);
        buffer.flip();
        return BinarySerializer.readValue(buffer, mapper);
    }

    private static Object roundTripGrowable(Object value) {
        GrowableBuffer buffer = new GrowableBuffer(8);
        ObjectMapper mapper = new ObjectMapper();
        BinarySerializer.writeValue(buffer, value, mapper);
        ByteBuffer readBuf = ByteBuffer.wrap(buffer.toByteArray());
        return BinarySerializer.readValue(readBuf, mapper);
    }
}
