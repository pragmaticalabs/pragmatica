// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #345 I3 — the checkpoint encoding.
///
/// A checkpoint is read back by a DIFFERENT node than wrote it, and it is the base every later replay
/// builds on. A defect here does not fail a request — it produces folded state that is wrong on the
/// recovering node and stays wrong. So every field is round-tripped at its boundary values, and every
/// malformed input is asserted to FAIL rather than to parse into something plausible.
class EntityFoldSnapshotTest {

    @Nested
    class RoundTrip {
        @Test
        void encode_thenDecode_preservesEmptyState() {
            assertRoundTrip(new LinkedHashMap<>());
        }

        @Test
        void encode_thenDecode_preservesSingleEntry() {
            assertRoundTrip(Map.of("order-1", new byte[] {1, 2, 3}));
        }

        @Test
        void encode_thenDecode_preservesManyEntries() {
            Map<String, byte[]> state = new LinkedHashMap<>();

            for (var i = 0; i < 500; i++) {
                state.put("key-" + i, ("value-" + i).getBytes(StandardCharsets.UTF_8));
            }

            assertRoundTrip(state);
        }

        /// Keys are length-prefixed in BYTES. A key counted in characters would take the wrong slice and
        /// corrupt every entry after it — the failure this encoding must not have.
        @Test
        void encode_thenDecode_preservesMultiByteKeys() {
            assertRoundTrip(Map.of("заказ-δ-🚀", new byte[] {7}));
        }

        /// A key whose state is legitimately zero-length must survive as a PRESENT key with empty state,
        /// not vanish. A snapshot lists live keys, and "live with empty state" is one of them.
        @Test
        void encode_thenDecode_preservesEmptyValue() {
            assertRoundTrip(Map.of("empty", new byte[0]));
        }

        @Test
        void encode_thenDecode_preservesLargeValue() {
            var value = new byte[128 * 1024];

            for (var i = 0; i < value.length; i++) {
                value[i] = (byte) (i % 251);
            }

            assertRoundTrip(Map.of("big", value));
        }

        @Test
        void encode_thenDecode_preservesEmptyKey() {
            assertRoundTrip(Map.of("", new byte[] {9}));
        }

        private static void assertRoundTrip(Map<String, byte[]> state) {
            var decoded = decodeOrFail(EntityFoldSnapshot.encode(state));

            assertThat(decoded).hasSize(state.size());
            state.forEach((key, value) -> assertThat(decoded.get(key)).as("key %s", key).isEqualTo(value));
        }
    }

    @Nested
    class Malformed {
        @Test
        void decode_fails_forEmptyInput() {
            assertMalformed(new byte[0]);
        }

        @Test
        void decode_fails_forTruncatedHeader() {
            assertMalformed(new byte[] {EntityFoldSnapshot.VERSION, 0, 0});
        }

        @Test
        void decode_fails_forNegativeEntryCount() {
            var bytes = EntityFoldSnapshot.encode(Map.of("k", new byte[] {1}));

            bytes[1] = (byte) 0xFF;
            bytes[2] = (byte) 0xFF;
            bytes[3] = (byte) 0xFF;
            bytes[4] = (byte) 0xFF;

            assertMalformed(bytes);
        }

        /// The corruption most likely to be waved through by a parser that trusts its input: a declared
        /// count larger than the entries actually present. It must fail, not return a short map.
        @Test
        void decode_fails_forEntryCountBeyondContent() {
            var bytes = EntityFoldSnapshot.encode(Map.of("k", new byte[] {1}));

            bytes[4] = 99;

            assertMalformed(bytes);
        }

        @Test
        void decode_fails_forTruncatedPayload() {
            var full = EntityFoldSnapshot.encode(Map.of("key", new byte[] {1, 2, 3, 4}));
            var truncated = new byte[full.length - 3];

            System.arraycopy(full, 0, truncated, 0, truncated.length);

            assertMalformed(truncated);
        }

        /// A checkpoint written by a newer node must be refused loudly rather than parsed under this
        /// build's layout — misparsed state would be folded and then re-checkpointed as though real.
        @Test
        void decode_failsUnsupportedVersion_forNewerFormat() {
            var bytes = EntityFoldSnapshot.encode(Map.of("k", new byte[] {1}));

            bytes[0] = (byte) (EntityFoldSnapshot.VERSION + 1);

            EntityFoldSnapshot.decode(bytes)
                              .onSuccess(state -> fail("a newer snapshot version must be refused, got " + state))
                              .onFailure(cause -> assertThat(cause.stream()).hasAtLeastOneElementOfType(EntityLogError.UnsupportedVersion.class));
        }

        private static void assertMalformed(byte[] bytes) {
            EntityFoldSnapshot.decode(bytes)
                              .onSuccess(state -> fail("malformed checkpoint must be refused, got " + state))
                              .onFailure(EntityFoldSnapshotTest::assertMalformedRecord);
        }
    }

    private static void assertMalformedRecord(Cause cause) {
        assertThat(cause.stream()).hasAtLeastOneElementOfType(EntityLogError.MalformedRecord.class);
    }

    private static Map<String, byte[]> decodeOrFail(byte[] bytes) {
        return EntityFoldSnapshot.decode(bytes).fold(cause -> fail(cause.message()), state -> state);
    }
}
