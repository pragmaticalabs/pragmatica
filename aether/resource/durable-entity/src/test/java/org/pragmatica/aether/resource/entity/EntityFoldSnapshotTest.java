// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #345 I3 — the checkpoint encoding, extended to pending timers in I4.
///
/// A checkpoint is read back by a DIFFERENT node than wrote it, and it is the base every later replay
/// builds on. A defect here does not fail a request — it produces folded state that is wrong on the
/// recovering node and stays wrong. So every field is round-tripped at its boundary values, and every
/// malformed input is asserted to FAIL rather than to parse into something plausible.
class EntityFoldSnapshotTest {
    private static final Map<String, Map<String, EntityFold.PendingTimer>> NO_TIMERS = Map.of();

    /// Four bytes appended after a v1 state section, laid out so a reader that wrongly looked for the v2
    /// timer section would read them as a timer-bearing key count of 3 and then fail trying to read three
    /// keys that are not there. A zero count would be indistinguishable from not reading at all.
    private static final byte[] TRAILING_GARBAGE = {0, 0, 0, 3};

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
            var decoded = decodeOrFail(EntityFoldSnapshot.encode(state, NO_TIMERS));

            assertThat(decoded.state()).hasSize(state.size());
            state.forEach((key, value) -> assertThat(decoded.state().get(key)).as("key %s", key).isEqualTo(value));
            assertThat(decoded.timers()).isEmpty();
        }
    }

    /// #345 I4. A checkpoint that carried state but not pending timers would lose every timer scheduled
    /// below its offset the moment retention reclaimed the log that proved it existed — silently, and only
    /// on the node that took the partition over.
    @Nested
    class Timers {
        @Test
        void encode_thenDecode_preservesOneTimer() {
            assertTimersRoundTrip(Map.of("order-1", Map.of("tok-1", new EntityFold.PendingTimer(1_700_000_000_123L,
                                                                                                new byte[] {1, 2, 3}))));
        }

        @Test
        void encode_thenDecode_preservesSeveralTimersOnSeveralKeys() {
            Map<String, Map<String, EntityFold.PendingTimer>> timers = new LinkedHashMap<>();

            timers.put("order-1", Map.of("tok-a", new EntityFold.PendingTimer(1L, new byte[] {1}),
                                         "tok-b", new EntityFold.PendingTimer(Long.MAX_VALUE, new byte[] {2, 2})));
            timers.put("order-2", Map.of("tok-c", new EntityFold.PendingTimer(0L, new byte[0])));

            assertTimersRoundTrip(timers);
        }

        /// The fire instant is a full 64-bit field. Truncating it to an int would silently move far-future
        /// timers into the past and fire them all on the next tick.
        @Test
        void encode_thenDecode_preservesFireInstantsBeyondIntRange() {
            assertTimersRoundTrip(Map.of("k", Map.of("tok", new EntityFold.PendingTimer(Long.MAX_VALUE, new byte[] {9}))));
        }

        @Test
        void encode_thenDecode_preservesMultiByteTokensAndKeys() {
            assertTimersRoundTrip(Map.of("заказ-δ", Map.of("токен-🚀", new EntityFold.PendingTimer(42L, new byte[] {7}))));
        }

        /// Both sections in one snapshot, since the timer section is read from wherever the state section
        /// left the buffer — an off-by-one between them would corrupt the timers and nothing else.
        @Test
        void encode_thenDecode_preservesStateAndTimersTogether() {
            var state = Map.of("k1", new byte[] {1}, "k2", new byte[] {2, 2});
            var timers = Map.of("k1", Map.of("tok", new EntityFold.PendingTimer(77L, new byte[] {3, 3, 3})));
            var decoded = decodeOrFail(EntityFoldSnapshot.encode(state, timers));

            assertThat(decoded.state().get("k1")).isEqualTo(new byte[] {1});
            assertThat(decoded.state().get("k2")).isEqualTo(new byte[] {2, 2});
            assertThat(decoded.timers().get("k1").get("tok").fireAtEpochMillis()).isEqualTo(77L);
            assertThat(decoded.timers().get("k1").get("tok").command()).isEqualTo(new byte[] {3, 3, 3});
        }

        private static void assertTimersRoundTrip(Map<String, Map<String, EntityFold.PendingTimer>> timers) {
            var decoded = decodeOrFail(EntityFoldSnapshot.encode(Map.of(), timers));

            assertThat(decoded.timers()).hasSize(timers.size());
            timers.forEach((key, pending) -> assertTimersOfKey(decoded.timers().get(key), key, pending));
        }

        private static void assertTimersOfKey(Map<String, EntityFold.PendingTimer> decoded,
                                              String key,
                                              Map<String, EntityFold.PendingTimer> expected) {
            assertThat(decoded).as("timers of key %s", key).hasSize(expected.size());
            expected.forEach((token, timer) -> assertTimer(decoded.get(token), key, token, timer));
        }

        /// Compared component by component rather than by record equality: [EntityFold.PendingTimer] carries
        /// a `byte[]`, so its generated `equals` is array IDENTITY — two records holding equal bytes are
        /// never equal, and a round-trip assertion written the obvious way fails against correct code.
        private static void assertTimer(EntityFold.PendingTimer decoded,
                                        String key,
                                        String token,
                                        EntityFold.PendingTimer expected) {
            assertThat(decoded).as("timer %s of key %s", token, key).isNotNull();
            assertThat(decoded.fireAtEpochMillis()).as("fire instant of %s/%s", key, token)
                                                   .isEqualTo(expected.fireAtEpochMillis());
            assertThat(decoded.command()).as("command of %s/%s", key, token).isEqualTo(expected.command());
        }
    }

    /// The bump rule this file states for itself: a reader that accepts every older version. A checkpoint
    /// written before I4 is not degraded data — the build that wrote it had no timers at all, so "no
    /// pending timers" is its exact meaning. Refusing it would strand every keyspace checkpointed before
    /// this increment, which is unrecoverable state rather than a compatibility inconvenience.
    @Nested
    class BackwardCompatibility {
        @Test
        void decode_acceptsVersionOne_andReportsNoTimers() {
            var decoded = decodeOrFail(versionOneSnapshot(Map.of("order-1", new byte[] {1, 2, 3})));

            assertThat(decoded.state()).hasSize(1);
            assertThat(decoded.state().get("order-1")).isEqualTo(new byte[] {1, 2, 3});
            assertThat(decoded.timers()).isEmpty();
        }

        @Test
        void decode_acceptsVersionOne_withNoEntries() {
            var decoded = decodeOrFail(versionOneSnapshot(Map.of()));

            assertThat(decoded.state()).isEmpty();
            assertThat(decoded.timers()).isEmpty();
        }

        /// A v1 body followed by TRAILING BYTES the v1 layout never defined. The compatibility arm must stop
        /// at the end of the state section: a reader that goes looking for the v2 timer-count would consume
        /// the four bytes below as one, and this test's whole name is that it does not. Trailing garbage is
        /// what makes that checkable — with nothing after the state section, an over-reading parser fails on
        /// an underflow, which is the easy half; consuming plausible garbage silently is the hard one.
        @Test
        void decode_versionOne_doesNotReadPastTheStateSection() {
            var body = versionOneSnapshot(Map.of("k", new byte[] {5}));
            var bytes = ByteBuffer.allocate(body.length + TRAILING_GARBAGE.length)
                                  .put(body)
                                  .put(TRAILING_GARBAGE)
                                  .array();

            assertThat(bytes[0]).isEqualTo((byte) 1);

            var decoded = decodeOrFail(bytes);

            assertThat(decoded.state()).describedAs("the state section reads exactly as it would with nothing after it")
                                       .hasSize(1);
            assertThat(decoded.state().get("k")).isEqualTo(new byte[] {5});
            assertThat(decoded.timers()).describedAs("a v1 checkpoint declares no timers, whatever follows its state section")
                                        .isEmpty();
        }

        /// Reading v1 is only half of compatibility. A keyspace with no timers must keep WRITING v1, so a
        /// rollback to a build that predates I4 can still read the checkpoints this build produced — a
        /// version bump is otherwise one-way for every keyspace, including the majority that never schedule
        /// a timer.
        ///
        /// Against the LITERAL 1: the claim is about the byte a pre-I4 reader will find, and comparing the
        /// encoder's output to the constant the encoder writes holds for whatever that constant becomes.
        @Test
        void encode_writesTheVersionOneLayout_whenThereAreNoTimers() {
            assertThat(EntityFoldSnapshot.encode(Map.of("order-1", new byte[] {1, 2, 3}), NO_TIMERS)[0])
                .isEqualTo((byte) 1);
        }

        /// The literal 2, for the reason the v1 assertion above takes the literal 1 — and additionally
        /// because "version two" is in this test's name: read against the constant, the name could go on
        /// claiming 2 while the encoder wrote anything at all.
        @Test
        void encode_writesTheVersionTwoLayout_whenATimerIsPending() {
            var timers = Map.of("k", Map.of("tok", new EntityFold.PendingTimer(1L, new byte[] {2})));

            assertThat(EntityFoldSnapshot.encode(Map.of(), timers)[0]).isEqualTo((byte) 2);
        }

        /// The strong form: byte-for-byte identical to what the pre-I4 encoder produced. Asserting only that
        /// the first byte is 1 would pass for bytes an older reader still cannot parse, which is the failure
        /// that matters — the whole claim is that an older build can read these.
        @Test
        void encode_withoutTimers_matchesThePreTimerLayoutByteForByte() {
            var state = Map.of("order-1", new byte[] {1, 2, 3});

            assertThat(EntityFoldSnapshot.encode(state, NO_TIMERS)).isEqualTo(versionOneSnapshot(state));
        }

        @Test
        void encode_withoutTimers_matchesThePreTimerLayout_forEmptyState() {
            assertThat(EntityFoldSnapshot.encode(Map.of(), NO_TIMERS)).isEqualTo(versionOneSnapshot(Map.of()));
        }

        /// A REAL v1 payload, laid out by hand exactly as the pre-I4 encoder wrote it: version byte, entry
        /// count, then length-prefixed key/state pairs and nothing after them. Re-using the current encoder
        /// and patching its version byte would not test this — it would leave a v2 timer section behind the
        /// v1 marker, which no v1 writer ever produced.
        ///
        /// The version byte is the LITERAL 1 for the same reason the rest of the layout is hand-laid: this
        /// is a record a build that no longer exists wrote, so it must not move when a constant in this
        /// build does. Stamped from `VERSION_WITHOUT_TIMERS`, every assertion comparing an encoder's output
        /// against this fixture would agree with itself through a version bump.
        private static byte[] versionOneSnapshot(Map<String, byte[]> state) {
            var size = 5;

            for (var entry : state.entrySet()) {
                size += 4 + entry.getKey().getBytes(StandardCharsets.UTF_8).length + 4 + entry.getValue().length;
            }

            var buffer = ByteBuffer.allocate(size);

            buffer.put((byte) 1);
            buffer.putInt(state.size());
            state.forEach((key, value) -> putVersionOneEntry(buffer, key, value));

            return buffer.array();
        }

        private static void putVersionOneEntry(ByteBuffer buffer, String key, byte[] value) {
            var keyBytes = key.getBytes(StandardCharsets.UTF_8);

            buffer.putInt(keyBytes.length);
            buffer.put(keyBytes);
            buffer.putInt(value.length);
            buffer.put(value);
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
            var bytes = EntityFoldSnapshot.encode(Map.of("k", new byte[] {1}), NO_TIMERS);

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
            var bytes = EntityFoldSnapshot.encode(Map.of("k", new byte[] {1}), NO_TIMERS);

            bytes[4] = 99;

            assertMalformed(bytes);
        }

        @Test
        void decode_fails_forTruncatedPayload() {
            var full = EntityFoldSnapshot.encode(Map.of("key", new byte[] {1, 2, 3, 4}), NO_TIMERS);
            var truncated = new byte[full.length - 3];

            System.arraycopy(full, 0, truncated, 0, truncated.length);

            assertMalformed(truncated);
        }

        /// A v2 snapshot whose timer section is cut off must fail rather than silently reporting the state
        /// it did manage to read with no pending timers — which is exactly the shape of the loss the timer
        /// section exists to prevent, wearing a success.
        @Test
        void decode_fails_forTruncatedTimerSection() {
            var full = EntityFoldSnapshot.encode(Map.of("k", new byte[] {1}),
                                                 Map.of("k", Map.of("tok", new EntityFold.PendingTimer(1L, new byte[] {2}))));
            var truncated = new byte[full.length - 5];

            System.arraycopy(full, 0, truncated, 0, truncated.length);

            assertMalformed(truncated);
        }

        @Test
        void decode_fails_forTimerKeyCountBeyondContent() {
            var bytes = EntityFoldSnapshot.encode(Map.of(),
                                                  Map.of("k", Map.of("tok", new EntityFold.PendingTimer(1L, new byte[] {2}))));

            bytes[8] = 99;

            assertMalformed(bytes);
        }

        /// A checkpoint written by a newer node must be refused loudly rather than parsed under this
        /// build's layout — misparsed state would be folded and then re-checkpointed as though real.
        @Test
        void decode_failsUnsupportedVersion_forNewerFormat() {
            var bytes = EntityFoldSnapshot.encode(Map.of("k", new byte[] {1}), NO_TIMERS);

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

    private static EntityFoldSnapshot.FoldedState decodeOrFail(byte[] bytes) {
        return EntityFoldSnapshot.decode(bytes).fold(cause -> fail(cause.message()), state -> state);
    }
}
