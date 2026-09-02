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

    /// #345 I4 — the timer payload that rides inside `state` for the three timer ops. Pinned as hard as
    /// the envelope, for the same reason: a token that round-tripped wrong would silently cancel or fire
    /// the WRONG timer, and a fire instant that round-tripped wrong would fire at the wrong time — neither
    /// announces itself as a parse failure.
    @Nested
    class Timers {
        private static final String TOKEN = "01J8XG7Q0000000000000000";

        @Test
        void encode_thenDecode_preservesTimerSchedule() {
            var command = new byte[] {4, 5, 6};
            var decoded = decodeOrFail(EntityLogRecord.timerSchedule(KEY, TOKEN, 1_700_000_000_123L, command).encode());

            assertThat(decoded.op()).isEqualTo(EntityLogRecord.Op.TIMER_SCHEDULE);
            assertThat(decoded.key()).isEqualTo(KEY);
            assertPayload(decoded, TOKEN, 1_700_000_000_123L, command);
        }

        @Test
        void encode_thenDecode_preservesTimerCancel() {
            var decoded = decodeOrFail(EntityLogRecord.timerCancel(KEY, TOKEN).encode());

            assertThat(decoded.op()).isEqualTo(EntityLogRecord.Op.TIMER_CANCEL);
            assertPayload(decoded, TOKEN, 0L, new byte[0]);
        }

        /// A fire carries the POST-FIRE STATE where a schedule carries the command — one record, so the
        /// token leaving the pending set and the state landing cannot be separated by a crash.
        @Test
        void encode_thenDecode_preservesTimerFire() {
            var state = new byte[] {7, 8};
            var decoded = decodeOrFail(EntityLogRecord.timerFire(KEY, TOKEN, state).encode());

            assertThat(decoded.op()).isEqualTo(EntityLogRecord.Op.TIMER_FIRE);
            assertPayload(decoded, TOKEN, 0L, state);
        }

        /// The instant is a full 64-bit field. Truncating it to an int would move far-future timers into
        /// the past and fire them all on the next tick.
        @Test
        void encode_thenDecode_preservesFireInstantsBeyondIntRange() {
            assertPayload(decodeOrFail(EntityLogRecord.timerSchedule(KEY, TOKEN, Long.MAX_VALUE, new byte[] {1}).encode()),
                          TOKEN,
                          Long.MAX_VALUE,
                          new byte[] {1});
        }

        /// Tokens are length-prefixed in BYTES, exactly as keys are. A multi-byte token counted in
        /// characters would take the wrong slice and corrupt both the token and the command after it.
        @Test
        void encode_thenDecode_preservesMultiByteToken() {
            var token = "токен-δ-🚀";

            assertThat(token.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(token.length());
            assertPayload(decodeOrFail(EntityLogRecord.timerSchedule(KEY, token, 9L, new byte[] {3}).encode()),
                          token,
                          9L,
                          new byte[] {3});
        }

        @Test
        void encode_thenDecode_preservesEmptyCommand() {
            assertPayload(decodeOrFail(EntityLogRecord.timerSchedule(KEY, TOKEN, 9L, new byte[0]).encode()),
                          TOKEN,
                          9L,
                          new byte[0]);
        }

        /// The ordinals ARE the wire form. A reordering of the enum would re-interpret every record already
        /// on disk as a different operation, so they are pinned rather than left to declaration order.
        @Test
        void ordinals_areStable_soExistingLogsKeepTheirMeaning() {
            assertThat(EntityLogRecord.Op.values()).containsExactly(EntityLogRecord.Op.UPSERT,
                                                                    EntityLogRecord.Op.DELETE,
                                                                    EntityLogRecord.Op.TIMER_SCHEDULE,
                                                                    EntityLogRecord.Op.TIMER_CANCEL,
                                                                    EntityLogRecord.Op.TIMER_FIRE);
        }

        @Test
        void timerPayload_fails_forTruncatedPayload() {
            assertMalformedPayload(new EntityLogRecord(EntityLogRecord.Op.TIMER_SCHEDULE, KEY, new byte[] {1, 0, 0, 0}));
        }

        @Test
        void timerPayload_fails_forEmptyPayload() {
            assertMalformedPayload(new EntityLogRecord(EntityLogRecord.Op.TIMER_CANCEL, KEY, new byte[0]));
        }

        /// A token length that runs past the payload is the corruption most likely to be waved through by a
        /// parser that trusts its input — it would read whatever followed in the buffer as the token.
        @Test
        void timerPayload_fails_forTokenLengthBeyondPayload() {
            var record = EntityLogRecord.timerSchedule(KEY, TOKEN, 1L, new byte[] {1});
            var payload = record.state();

            payload[9] = 0x7F;
            payload[10] = 0x00;

            assertMalformedPayload(new EntityLogRecord(record.op(), record.key(), payload));
        }

        @Test
        void timerPayload_fails_forNegativeTokenLength() {
            var record = EntityLogRecord.timerCancel(KEY, TOKEN);
            var payload = record.state();

            payload[9] = (byte) 0xFF;
            payload[10] = (byte) 0xFF;
            payload[11] = (byte) 0xFF;
            payload[12] = (byte) 0xFF;

            assertMalformedPayload(new EntityLogRecord(record.op(), record.key(), payload));
        }

        /// A newer node's payload must be refused loudly for the same reason a newer envelope is: parsing
        /// it under this build's layout would fold a garbage timer into state that then gets checkpointed.
        @Test
        void timerPayload_failsUnsupportedVersion_forNewerPayloadFraming() {
            var record = EntityLogRecord.timerFire(KEY, TOKEN, new byte[] {1});
            var payload = record.state();

            payload[0] = (byte) (EntityLogRecord.TimerPayload.PAYLOAD_VERSION + 1);

            new EntityLogRecord(record.op(), record.key(), payload)
                .timerPayload()
                .onSuccess(decoded -> fail("a newer payload version must be refused, got " + decoded))
                .onFailure(cause -> assertThat(cause.stream()).hasAtLeastOneElementOfType(EntityLogError.UnsupportedVersion.class));
        }

        /// The envelope is unchanged by timers: a timer record still decodes to its op and key under
        /// framing version 1, so a build that predates I4 still reads the log — it simply meets an op
        /// ordinal it does not know and refuses THAT, rather than misparsing the record.
        ///
        /// Against the LITERAL 1, not against `EntityLogRecord.VERSION`. The claim is about the byte an
        /// older build will find on the wire, and comparing the encoder's output to the constant the
        /// encoder writes holds for every value that constant could take — including the bump this test
        /// exists to catch.
        @Test
        void encode_keepsEnvelopeVersionOne_forTimerRecords() {
            assertThat(EntityLogRecord.timerSchedule(KEY, TOKEN, 1L, new byte[] {1}).encode()[0])
                .isEqualTo((byte) 1);
        }

        private static void assertPayload(EntityLogRecord record, String token, long fireAt, byte[] body) {
            var payload = record.timerPayload().fold(cause -> fail(cause.message()), decoded -> decoded);

            assertThat(payload.token()).isEqualTo(token);
            assertThat(payload.fireAtEpochMillis()).isEqualTo(fireAt);
            assertThat(payload.body()).isEqualTo(body);
        }

        private static void assertMalformedPayload(EntityLogRecord record) {
            record.timerPayload()
                  .onSuccess(payload -> fail("malformed timer payload must be refused, got " + payload))
                  .onFailure(EntityLogRecordTest::assertMalformedRecord);
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
