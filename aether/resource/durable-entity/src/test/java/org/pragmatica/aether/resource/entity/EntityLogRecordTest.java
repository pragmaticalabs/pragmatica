// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #345 I3 — the entity log's framing.
///
/// This is pinned harder than its size suggests. The log is the only durable copy of entity state, so a
/// framing defect does not surface as a failed operation — it surfaces as folded state that is quietly
/// wrong, on every node, forever after. Every field is therefore round-tripped at its boundary values,
/// and every malformed input is asserted to FAIL rather than to parse into something plausible.
class EntityLogRecordTest {
    private static final String KEY = "order-42";

    @Nested
    class RoundTrip {
        @Test
        void encode_thenDecode_preservesUpsert() {
            var state = new byte[] {1, 2, 3, -1, 0, 127};

            assertRoundTrip(EntityLogRecord.upsert(KEY, state), EntityLogRecord.Op.UPSERT, KEY, state);
        }

        @Test
        void encode_thenDecode_preservesDelete() {
            assertRoundTrip(EntityLogRecord.delete(KEY), EntityLogRecord.Op.DELETE, KEY, new byte[0]);
        }

        /// A tombstone and an upsert of genuinely empty state must not decode to the same thing — the
        /// reason Op is explicit rather than inferred from an empty payload.
        @Test
        void decode_distinguishesDelete_fromUpsertOfEmptyState() {
            var deleteDecoded = decodeOrFail(EntityLogRecord.delete(KEY).encode());
            var upsertDecoded = decodeOrFail(EntityLogRecord.upsert(KEY, new byte[0]).encode());

            assertThat(deleteDecoded.op()).isEqualTo(EntityLogRecord.Op.DELETE);
            assertThat(upsertDecoded.op()).isEqualTo(EntityLogRecord.Op.UPSERT);
            assertThat(deleteDecoded.state()).isEmpty();
            assertThat(upsertDecoded.state()).isEmpty();
        }

        /// Keys are length-prefixed in BYTES, not characters. A multi-byte key that was counted in
        /// characters would take the wrong slice and silently corrupt both the key and the state after
        /// it — which is exactly the failure this framing must not have.
        @Test
        void encode_thenDecode_preservesMultiByteKey() {
            var key = "заказ-δ-🚀";
            var state = new byte[] {9, 9};

            assertThat(key.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(key.length());
            assertRoundTrip(EntityLogRecord.upsert(key, state), EntityLogRecord.Op.UPSERT, key, state);
        }

        @Test
        void encode_thenDecode_preservesEmptyKey() {
            assertRoundTrip(EntityLogRecord.upsert("", new byte[] {7}), EntityLogRecord.Op.UPSERT, "", new byte[] {7});
        }

        @Test
        void encode_thenDecode_preservesLargeState() {
            var state = new byte[64 * 1024];

            for (var i = 0; i < state.length; i++) {
                state[i] = (byte) (i % 251);
            }

            assertRoundTrip(EntityLogRecord.upsert(KEY, state), EntityLogRecord.Op.UPSERT, KEY, state);
        }

        private static void assertRoundTrip(EntityLogRecord record,
                                            EntityLogRecord.Op expectedOp,
                                            String expectedKey,
                                            byte[] expectedState) {
            var decoded = decodeOrFail(record.encode());

            assertThat(decoded.op()).isEqualTo(expectedOp);
            assertThat(decoded.key()).isEqualTo(expectedKey);
            assertThat(decoded.state()).isEqualTo(expectedState);
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
            assertMalformed(new byte[] {EntityLogRecord.VERSION, 0, 0, 0});
        }

        /// A key length that runs past the record is the corruption most likely to be waved through by a
        /// parser that trusts its input — it would read whatever followed in the buffer.
        @Test
        void decode_fails_forKeyLengthBeyondRecord() {
            var bytes = EntityLogRecord.upsert(KEY, new byte[] {1}).encode();

            bytes[2] = 0x7F;
            bytes[3] = 0x00;

            assertMalformed(bytes);
        }

        @Test
        void decode_fails_forNegativeKeyLength() {
            var bytes = EntityLogRecord.upsert(KEY, new byte[] {1}).encode();

            bytes[2] = (byte) 0xFF;
            bytes[3] = (byte) 0xFF;
            bytes[4] = (byte) 0xFF;
            bytes[5] = (byte) 0xFF;

            assertMalformed(bytes);
        }

        @Test
        void decode_fails_forUnknownOperation() {
            var bytes = EntityLogRecord.upsert(KEY, new byte[] {1}).encode();

            bytes[1] = 99;

            assertMalformed(bytes);
        }

        /// A newer node's record must be refused loudly. Parsing it under this build's layout would fold
        /// garbage into state that then gets checkpointed as though it were real.
        @Test
        void decode_failsUnsupportedVersion_forNewerFraming() {
            var bytes = EntityLogRecord.upsert(KEY, new byte[] {1}).encode();

            bytes[0] = (byte) (EntityLogRecord.VERSION + 1);

            EntityLogRecord.decode(bytes)
                           .onSuccess(record -> fail("a newer framing version must be refused, got " + record))
                           .onFailure(cause -> assertThat(cause.stream()).hasAtLeastOneElementOfType(EntityLogError.UnsupportedVersion.class));
        }

        @Test
        void decode_namesTheVersions_inTheRefusal() {
            var bytes = EntityLogRecord.upsert(KEY, new byte[] {1}).encode();

            bytes[0] = 42;

            EntityLogRecord.decode(bytes)
                           .onSuccess(record -> fail("a newer framing version must be refused, got " + record))
                           .onFailure(cause -> assertThat(cause.message()).contains("42")
                                                                          .contains(String.valueOf(EntityLogRecord.VERSION)));
        }

        private static void assertMalformed(byte[] bytes) {
            EntityLogRecord.decode(bytes)
                           .onSuccess(record -> fail("malformed input must be refused, got " + record))
                           .onFailure(EntityLogRecordTest::assertMalformedRecord);
        }
    }

    private static void assertMalformedRecord(Cause cause) {
        assertThat(cause.stream()).hasAtLeastOneElementOfType(EntityLogError.MalformedRecord.class);
    }

    private static EntityLogRecord decodeOrFail(byte[] bytes) {
        return EntityLogRecord.decode(bytes)
                              .fold(cause -> fail(cause.message()), record -> record);
    }
}
