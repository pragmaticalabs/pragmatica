// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.stream.RemovalCause;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamError.CursorExpired.Reason;
import org.pragmatica.aether.stream.forward.ReadRefusal.Answer;
import org.pragmatica.aether.stream.forward.ReadRefusal.Behaviour;
import org.pragmatica.aether.stream.forward.ReadRefusal.Kind;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.segment.SegmentError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.storage.StorageError;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.aether.stream.forward.StreamForwardClient.streamForwardClient;


/// #1967: the typed read refusal a forwarded read carries. Every value in a sample cause is distinct from its neighbours, so a slot
/// the codec drops, reorders or crosses with another fails by value; the cross-node behaviour is `ForwardedReadRefusalTest`.
class ReadRefusalTest {
    private static final SliceCodec CODEC = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), ForwardCodecsStream.CODECS);
    private static final NodeId SENDER = new NodeId("serving-node");
    private static final Epoch PRESENTED = Epoch.epoch(7L, 8L, 9L);
    private static final Epoch CURRENT = Epoch.epoch(7L, 9L, 1L);

    /// One sample per carried kind (two for the held partition, whose flag changes the text), each with distinct values in every slot
    /// it uses.
    private static final List<Cause> CARRIED = List.of(new StreamError.StreamNotFound("orders"),
                                                       new StreamError.PartitionOutOfRange("orders", 3, 2),
                                                       StreamError.General.PARTITION_NOT_LOCAL,
                                                       StreamError.General.BUFFER_CLOSED,
                                                       new StreamError.CursorExpired(11L, 29L),
                                                       new StreamError.CursorExpired(11L, 29L, StreamError.CursorExpired.Reason.LOST),
                                                       new StreamError.OwnerNotActivated("orders", 4),
                                                       new StreamError.ReplicaNotVerified("orders", 5, 17L),
                                                       new StreamError.StaleEpochRead("orders", 6, PRESENTED, CURRENT),
                                                       new StreamError.NotCurrentOwner("orders", 2, new NodeId("expected"), new NodeId("actual")),
                                                       new StreamError.OwnerCatchupPending("orders", 1),
                                                       new StreamError.LinearizableRoundTimeout("orders", 7),
                                                       new StreamError.PartitionHeldNotMaterialized("orders", 3, 41L, false),
                                                       new StreamError.PartitionHeldNotMaterialized("orders", 3, 41L, true),
                                                       new StreamError.RingIndexCorrupted("orders", 2, "Index 9 out of bounds for length 4"),
                                                       new SegmentError.SealInFlight("orders", 1, 23L),
                                                       new SegmentError.SealedRangeMissing("orders", 1, 23L, 31L),
                                                       new StreamError.StreamNotMaterialized("orders"));

    @Test
    void everyKindButUnknown_hasACarriedSample_andNothingElse() {
        var sampled = CARRIED.stream()
                             .map(cause -> ReadRefusal.readRefusal(cause).unwrap().kind())
                             .collect(Collectors.toSet());
        var declared = Arrays.stream(Kind.values()).filter(kind -> kind != Kind.UNKNOWN).collect(Collectors.toSet());

        assertThat(sampled).as("a kind with no sample would be untested on the wire").isEqualTo(declared);
    }

    /// #2981 step 2 (design §0 row 3): read routing treats a lapsed owner lease as not-activated; no new wire kind. Mutation: the
    /// `OwnerLeaseLapsed` arm mapped to another kind or not carried -> RED.
    @Test
    void aLapsedOwnerLease_travelsAsOwnerNotActivated() {
        var refusal = ReadRefusal.readRefusal(new StreamError.OwnerLeaseLapsed("orders", 4)).unwrap();

        assertThat(refusal.kind()).isEqualTo(Kind.OWNER_NOT_ACTIVATED);
        assertThat(refusal.toCause("")).isEqualTo(new StreamError.OwnerNotActivated("orders", 4));
    }

    @Test
    void everyCarriedCause_survivesTheCodecAsItself() {
        for (var cause : CARRIED) {
            var refusal = ReadRefusal.readRefusal(cause).unwrap();
            var sent = ReadForwardResponse.refusalResponse(SENDER, "corr", refusal, cause.message());
            var received = roundTrip(sent);

            assertThat(received.refusal()).as("the refusal of %s", cause).isEqualTo(Option.some(refusal));
            assertThat(received.refusal().unwrap().toCause(received.errorMessage())).as("the cause rebuilt from %s", refusal).isEqualTo(cause);
            assertThat(received.success()).isFalse();
        }
    }

    @Test
    void theEnumConstants_rebuildAsTheSameInstance_becauseCallersCompareThemByIdentity() {
        for (var constant : List.of(StreamError.General.PARTITION_NOT_LOCAL, StreamError.General.BUFFER_CLOSED)) {
            assertThat(ReadRefusal.readRefusal(constant).unwrap().toCause("text")).isSameAs(constant);
        }
    }

    @Test
    void aCarriedCause_keepsItsTextForTheCallerThatDoesNotKnowTheCode() {
        var cause = new StreamError.CursorExpired(11L, 29L);
        var received = roundTrip(ReadForwardResponse.refusalResponse(SENDER, "corr", ReadRefusal.readRefusal(cause).unwrap(), cause.message()));

        assertThat(received.errorMessage()).isEqualTo(cause.message());
    }

    /// A refusal no read raises, an engine failure, a consensus failure and a plain cause are not carried: they travel as text.
    @Test
    void aCauseNoReadIsKnownToRaise_isNotCarried() {
        var notCarried = List.of(StreamError.General.STREAM_CONFIG_COMMIT_FAILED,
                                 StreamError.General.BUFFER_EMPTY,
                                 new StreamError.StaleEpochAppend("orders", 0, PRESENTED, CURRENT),
                                 new StreamError.WalHeadLost("orders", 0, Path.of("orders-0.wal"), 4L, 9L),
                                 new StreamError.EpochDiverged(PRESENTED, 3L, 3L),
                                 SegmentError.General.values()[0],
                                 Causes.cause("something else"));

        for (var cause : notCarried) {
            assertThat(ReadRefusal.readRefusal(cause)).as("%s", cause).isEqualTo(Option.none());
        }
    }

    /// A newer node may send a code this one does not know (#964): it degrades to the owner's text, as every failure did before.
    @Test
    void anUnknownKind_rebuildsAsTheOwnersText() {
        var unknown = ReadRefusal.readRefusal(StreamError.General.PARTITION_NOT_LOCAL).unwrap();
        var fromANewerNode = new ReadRefusal(Kind.UNKNOWN,
                                             unknown.stream(),
                                             unknown.partition(),
                                             unknown.first(),
                                             unknown.second(),
                                             unknown.flag(),
                                             unknown.detail(),
                                             unknown.peerFirst(),
                                             unknown.peerSecond(),
                                             unknown.epochFirst(),
                                             unknown.epochSecond());

        assertThat(fromANewerNode.toCause("the newer node's text")).isEqualTo(new StreamForwardError.ReadForwardFailed("the newer node's text"));
    }

    /// The wire form pins its slots by position: a reorder crosses two longs or two epochs and every other test stays green by
    /// accident of equal values, so the record's own equality is pinned across the codec with every slot distinct.
    @Test
    void theRefusalRecord_roundTripsWithEverySlotDistinct() {
        var refusal = new ReadRefusal(Kind.NOT_CURRENT_OWNER, "s", 5, 101L, 202L, true, "d", "p1", "p2", PRESENTED, CURRENT);
        var received = roundTrip(ReadForwardResponse.refusalResponse(SENDER, "corr", refusal, "text"));

        assertThat(received.refusal()).isEqualTo(Option.some(refusal));
        assertThat(Set.of(received.refusal().unwrap().first(), received.refusal().unwrap().second())).containsExactlyInAnyOrder(101L, 202L);
    }

    /// T-P4 (stream-mem M-1b): `CursorExpired.reason` rides the refusal's `flag` slot, which `CURSOR_EXPIRED` did not use. Mutant:
    /// `toCause` ignores `flag` for `CURSOR_EXPIRED` -> the LOST arm goes RED.
    @Nested
    class CursorExpiredReason {
        @Test
        void bothReasons_surviveTheCodecAsThemselves() {
            for (var reason : StreamError.CursorExpired.Reason.values()) {
                var cause = new StreamError.CursorExpired(11L, 29L, reason);
                var received = roundTrip(ReadForwardResponse.refusalResponse(SENDER, "corr", ReadRefusal.readRefusal(cause).unwrap(), cause.message()));

                assertThat(received.refusal().unwrap().toCause(received.errorMessage())).as("%s", reason).isEqualTo(cause);
            }
        }

        @Test
        void theTwoArgumentForm_isExpired_andTravelsWithAClearFlag() {
            var legacy = new StreamError.CursorExpired(11L, 29L);
            var refusal = ReadRefusal.readRefusal(legacy).unwrap();

            assertThat(legacy.reason()).isEqualTo(StreamError.CursorExpired.Reason.EXPIRED);
            assertThat(refusal.flag()).as("bytes the two-argument form always encoded").isFalse();
            assertThat(refusal.toCause("")).isEqualTo(new StreamError.CursorExpired(11L, 29L, StreamError.CursorExpired.Reason.EXPIRED));
        }

        @Test
        void lostRidesTheFlagSlot_andNoOtherKindTouchesIt() {
            var lost = ReadRefusal.readRefusal(new StreamError.CursorExpired(1L, 2L, StreamError.CursorExpired.Reason.LOST)).unwrap();

            assertThat(lost.flag()).isTrue();
            for (var cause : CARRIED) {
                var refusal = ReadRefusal.readRefusal(cause).unwrap();
                var expected = cause instanceof StreamError.PartitionHeldNotMaterialized held
                               ? held.budgetExhausted()
                               : cause instanceof StreamError.CursorExpired expired && expired.reason() == StreamError.CursorExpired.Reason.LOST;

                assertThat(refusal.flag()).as("the flag of %s", cause).isEqualTo(expected);
            }
        }
    }

    /// stream-mem M-1b-delta: the removal cause a refusal names. [RemovalCause]'s ordinal is its wire code, `ReadRefusal`'s
    /// compact constructor is the ONE table by kind (`CURSOR_EXPIRED` keeps a removal's cause and turns `NOT_APPLICABLE` into
    /// `UNRECORDED`, every other kind carries `NOT_APPLICABLE`), and a code this node does not know decodes to `UNKNOWN`. Each
    /// test names the mutant that turns it red.
    @Nested
    class TheNamedCause {
        /// The literal table: one row per member, in wire order (name, code, the reason the cause fixes). Mutant: two constants
        /// swapped in `RemovalCause`, or a `reasonOf` arm moved -> RED.
        private static final List<CauseRow> TABLE = List.of(CauseRow.causeRow("RETENTION", 0, Option.some(Reason.EXPIRED)),
                                                            CauseRow.causeRow("AGE", 1, Option.some(Reason.EXPIRED)),
                                                            CauseRow.causeRow("COUNT", 2, Option.some(Reason.EXPIRED)),
                                                            CauseRow.causeRow("BYTES", 3, Option.some(Reason.EXPIRED)),
                                                            CauseRow.causeRow("RING_FROZEN", 4, Option.some(Reason.LOST)),
                                                            CauseRow.causeRow("OVER_SHARE", 5, Option.some(Reason.LOST)),
                                                            CauseRow.causeRow("DROPPED_RUN", 6, Option.some(Reason.LOST)),
                                                            CauseRow.causeRow("BLOCK_PUT_FAILED", 7, Option.some(Reason.LOST)),
                                                            CauseRow.causeRow("BLOCK_ABSENT", 8, Option.some(Reason.LOST)),
                                                            CauseRow.causeRow("UNLISTED_TAIL", 9, Option.some(Reason.LOST)),
                                                            CauseRow.causeRow("LIFE_LOST", 10, Option.some(Reason.LOST)),
                                                            CauseRow.causeRow("UNRECORDED", 11, Option.none()),
                                                            CauseRow.causeRow("NOT_APPLICABLE", 12, Option.none()),
                                                            CauseRow.causeRow("UNKNOWN", 13, Option.none()));

        /// A code no node of this generation knows: `RemovalCause` has 14 members, so 99 and -1 are both out of range.
        private static final List<Integer> UNKNOWN_CODES = List.of(99, -1);

        private record CauseRow(String name, int code, Option<Reason> reason) {
            static CauseRow causeRow(String name, int code, Option<Reason> reason) {
                return new CauseRow(name, code, reason);
            }
        }

        @Test
        void theCauseTable_isLiteral_aRowPerMember() {
            assertThat(TABLE).as("a row per member").hasSize(RemovalCause.values().length);
            for (var row : TABLE) {
                var member = RemovalCause.valueOf(row.name());

                assertThat(member.ordinal()).as("the code of %s", row.name()).isEqualTo(row.code());
                assertThat(StreamError.CursorExpired.reasonOf(member)).as("the reason %s fixes", row.name()).isEqualTo(row.reason());
            }
        }

        /// Mutant: two constants swapped, or `CHAIN_CUT` re-added anywhere -> RED.
        @Test
        void theEnumOrder_endsWithTheThreeThatNameNoRemoval_andHasNoChainCut() {
            assertThat(Arrays.stream(RemovalCause.values()).map(RemovalCause::name).toList()).containsExactly("RETENTION",
                                                                                                              "AGE",
                                                                                                              "COUNT",
                                                                                                              "BYTES",
                                                                                                              "RING_FROZEN",
                                                                                                              "OVER_SHARE",
                                                                                                              "DROPPED_RUN",
                                                                                                              "BLOCK_PUT_FAILED",
                                                                                                              "BLOCK_ABSENT",
                                                                                                              "UNLISTED_TAIL",
                                                                                                              "LIFE_LOST",
                                                                                                              "UNRECORDED",
                                                                                                              "NOT_APPLICABLE",
                                                                                                              "UNKNOWN");
        }

        /// The code table as one line, the form the design states it in. Mutant: two constants swapped -> RED.
        @Test
        void theCodeTable_isTheEnumLine() {
            var line = Arrays.stream(RemovalCause.values())
                             .map(cause -> cause.name() + "=" + cause.ordinal())
                             .collect(Collectors.joining(","));

            assertThat(line).isEqualTo("RETENTION=0,AGE=1,COUNT=2,BYTES=3,RING_FROZEN=4,OVER_SHARE=5,DROPPED_RUN=6,BLOCK_PUT_FAILED=7,"
                                       + "BLOCK_ABSENT=8,UNLISTED_TAIL=9,LIFE_LOST=10,UNRECORDED=11,NOT_APPLICABLE=12,UNKNOWN=13");
        }

        /// Codes 0-10 fix a reason (0-3 `EXPIRED`, 4-10 `LOST`); the three that name no removal fix none. Mutant: a `reasonOf` arm
        /// moved between groups -> RED.
        @Test
        void reasonOf_fixesTheReasonForCodesZeroToTen_andNoneForTheLastThree() {
            for (var cause : RemovalCause.values()) {
                var expected = cause.ordinal() <= 3
                               ? Option.some(Reason.EXPIRED)
                               : cause.ordinal() <= 10
                                 ? Option.some(Reason.LOST)
                                 : Option.<Reason> none();

                assertThat(StreamError.CursorExpired.reasonOf(cause)).as("%s", cause).isEqualTo(expected);
            }
        }

        /// Mutant: the compact constructor's `reasonOf` check dropped -> RED.
        @Test
        void aPairWhoseReasonDisagreesWithTheCause_isRefused() {
            assertThatThrownBy(() -> new StreamError.CursorExpired(1L, 2L, Reason.EXPIRED, RemovalCause.RING_FROZEN)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StreamError.CursorExpired(1L, 2L, Reason.LOST, RemovalCause.AGE)).isInstanceOf(IllegalArgumentException.class);
        }

        /// A refusal that is not a removal is never a `CursorExpired`. Mutant: the compact constructor's `NOT_APPLICABLE` check
        /// dropped -> RED (both reasons, since `NOT_APPLICABLE` fixes none).
        @Test
        void notApplicable_isRefusedUnderEitherReason() {
            assertThatThrownBy(() -> new StreamError.CursorExpired(1L, 2L, Reason.EXPIRED, RemovalCause.NOT_APPLICABLE)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StreamError.CursorExpired(1L, 2L, Reason.LOST, RemovalCause.NOT_APPLICABLE)).isInstanceOf(IllegalArgumentException.class);
        }

        /// The control for the two refusals above: every pair that agrees builds, so the refusals are specific.
        @Test
        void everyPairThatAgrees_builds() {
            for (var cause : namingARemoval()) {
                for (var reason : reasonsOf(cause)) {
                    assertThat(new StreamError.CursorExpired(1L, 2L, reason, cause).cause()).as("%s %s", reason, cause).isEqualTo(cause);
                }
            }
        }

        /// Row 1: every member but `NOT_APPLICABLE` round-trips under `CURSOR_EXPIRED`, its reason in `flag` and its cause in
        /// `cause`; every other component reads back as written. Mutant: the codec drops or crosses `cause`, or `toCause`
        /// rebuilds another cause -> RED.
        @Test
        void row1_everyCauseButNotApplicable_roundTripsUnderCursorExpired() {
            for (var cause : namingARemoval()) {
                for (var reason : reasonsOf(cause)) {
                    var expired = new StreamError.CursorExpired(11L, 29L, reason, cause);
                    var sent = ReadRefusal.readRefusal(expired).unwrap();
                    var received = roundTrip(ReadForwardResponse.refusalResponse(SENDER, "corr", sent, expired.message()));
                    var first = received.refusal().unwrap();

                    assertThat(first).as("%s %s", reason, cause).isEqualTo(sent);
                    assertThat(first.flag()).as("the reason of %s %s", reason, cause).isEqualTo(reason == Reason.LOST);
                    assertThat(first.cause()).as("the cause of %s %s", reason, cause).isEqualTo(cause);
                    assertThat(first.toCause(received.errorMessage())).as("rebuilt %s %s", reason, cause).isEqualTo(expired);
                }
            }
        }

        /// Row 3: `NOT_APPLICABLE` round-trips under every other kind, with every slot distinct. Mutant: the codec drops or
        /// crosses `cause` -> RED.
        @Test
        void row3_notApplicable_roundTripsUnderEveryOtherKind() {
            for (var kind : otherKinds()) {
                var sent = sample(kind, true, RemovalCause.NOT_APPLICABLE);
                var first = roundTrip(ReadForwardResponse.refusalResponse(SENDER, "corr", sent, "text")).refusal().unwrap();

                assertThat(first).as("%s", kind).isEqualTo(sent);
                assertThat(first.cause()).as("the cause of %s", kind).isEqualTo(RemovalCause.NOT_APPLICABLE);
            }
        }

        /// The decode table, one test per cell: the bytes carry the code, the compact constructor decides the cause. Row 2 is the
        /// `NOT_APPLICABLE -> UNRECORDED` cell, never a round trip. Mutant per row -> RED: a known cause decoded as another,
        /// `NOT_APPLICABLE` kept for `CURSOR_EXPIRED`, an unknown code not `UNKNOWN`, another kind keeping a written cause.
        @TestFactory
        Stream<DynamicTest> theDecodeTable_aCellPerKindAndCode() {
            var cursorCells = Arrays.stream(RemovalCause.values())
                                    .map(code -> decodeCell(Kind.CURSOR_EXPIRED, code.ordinal(), cursorCauseOf(code)));
            var unknownCell = Stream.of(decodeCell(Kind.CURSOR_EXPIRED, 99, RemovalCause.UNKNOWN));
            var otherCells = otherKinds().stream()
                                         .flatMap(kind -> Stream.of(RemovalCause.RETENTION.ordinal(), RemovalCause.NOT_APPLICABLE.ordinal(), 99)
                                                                .map(code -> decodeCell(kind, code, RemovalCause.NOT_APPLICABLE)));

            return Stream.of(cursorCells, unknownCell, otherCells).flatMap(cells -> cells);
        }

        /// A flag that says `EXPIRED` with a cause that fixes `LOST` decodes with the cause's reason and never throws: the cause
        /// wins over the flag. Mutant: `toCause` takes the reason from the flag alone -> RED (the constructor throws).
        @Test
        void anExpiredFlagWithRingFrozen_decodesAsLost_andDoesNotThrow() {
            var decoded = decoded(Kind.CURSOR_EXPIRED, false, RemovalCause.RING_FROZEN.ordinal());

            assertThat(decoded.flag()).isFalse();
            assertThat(decoded.toCause("text")).isEqualTo(new StreamError.CursorExpired(101L, 202L, Reason.LOST, RemovalCause.RING_FROZEN));
        }

        /// A newer node's code decodes to `UNKNOWN` and rebuilds a `CursorExpired` naming `UNKNOWN`, its reason from the flag.
        /// Mutant: `toCause` maps `UNKNOWN` to `RETENTION` or `NOT_APPLICABLE`, or throws on it -> RED.
        @Test
        void anUnknownCode_decodesToUnknown_andRebuildsNamingIt() {
            for (var code : UNKNOWN_CODES) {
                for (var reason : Reason.values()) {
                    var decoded = decoded(Kind.CURSOR_EXPIRED, reason == Reason.LOST, code);

                    assertThat(decoded.cause()).as("code %d", code).isEqualTo(RemovalCause.UNKNOWN);
                    assertThat(decoded.toCause("text")).as("code %d %s", code, reason)
                                                       .isEqualTo(new StreamError.CursorExpired(101L, 202L, reason, RemovalCause.UNKNOWN));
                }
            }
        }

        /// The shim forms name no cause and pass `UNRECORDED`; the pre-cause 11-argument form passes `NOT_APPLICABLE`, which
        /// `CURSOR_EXPIRED` turns into `UNRECORDED`. Mutant: a shim passing `NOT_APPLICABLE` or `RETENTION`, or a
        /// `CURSOR_EXPIRED` refusal keeping `NOT_APPLICABLE` -> RED.
        @Test
        void theShimForms_passUnrecorded() {
            var twoArgument = new StreamError.CursorExpired(1L, 2L);

            assertThat(twoArgument.reason()).isEqualTo(Reason.EXPIRED);
            assertThat(twoArgument.cause()).isEqualTo(RemovalCause.UNRECORDED);
            for (var reason : Reason.values()) {
                var withReason = new StreamError.CursorExpired(1L, 2L, reason);

                assertThat(withReason.reason()).as("%s", reason).isEqualTo(reason);
                assertThat(withReason.cause()).as("%s", reason).isEqualTo(RemovalCause.UNRECORDED);
            }
            for (var kind : Kind.values()) {
                var expected = kind == Kind.CURSOR_EXPIRED
                               ? RemovalCause.UNRECORDED
                               : RemovalCause.NOT_APPLICABLE;

                assertThat(elevenArgument(kind).cause()).as("the 11-argument form of %s", kind).isEqualTo(expected);
            }
            assertThat(sample(Kind.CURSOR_EXPIRED, false, RemovalCause.NOT_APPLICABLE).cause()).isEqualTo(RemovalCause.UNRECORDED);
        }

        /// `UNKNOWN` is reserved for decoding: no constructor writes it. The cause form refuses it (it fixes no reason). Mutant: a
        /// shim or the 11-argument form passing `UNKNOWN` -> RED.
        @Test
        void noConstructor_yieldsUnknown() {
            assertThat(new StreamError.CursorExpired(1L, 2L).cause()).isNotEqualTo(RemovalCause.UNKNOWN);
            for (var reason : Reason.values()) {
                assertThat(new StreamError.CursorExpired(1L, 2L, reason).cause()).as("%s", reason).isNotEqualTo(RemovalCause.UNKNOWN);
            }
            for (var row : TABLE.subList(0, 11)) {
                var cause = RemovalCause.valueOf(row.name());
                var named = new StreamError.CursorExpired(1L, 2L, cause);

                assertThat(named.cause()).as("%s", cause).isEqualTo(cause);
                assertThat(Option.some(named.reason())).as("the reason %s fixes", cause).isEqualTo(row.reason());
            }
            assertThatThrownBy(() -> new StreamError.CursorExpired(1L, 2L, RemovalCause.UNKNOWN)).isInstanceOf(IllegalArgumentException.class);
            for (var kind : Kind.values()) {
                assertThat(elevenArgument(kind).cause()).as("the 11-argument form of %s", kind).isNotEqualTo(RemovalCause.UNKNOWN);
            }
        }

        /// Only `CURSOR_EXPIRED` carries a cause: every other kind carries `NOT_APPLICABLE` whatever was written. Mutant: the
        /// compact constructor writes `UNKNOWN` or keeps `RETENTION` for another kind -> RED.
        @Test
        void everyOtherKind_carriesNotApplicable_neverUnknown() {
            for (var kind : otherKinds()) {
                for (var written : RemovalCause.values()) {
                    assertThat(sample(kind, false, written).cause()).as("%s written %s", kind, written).isEqualTo(RemovalCause.NOT_APPLICABLE);
                }
            }
        }

        private static DynamicTest decodeCell(Kind kind, int code, RemovalCause expected) {
            return DynamicTest.dynamicTest(kind + " code " + code + " -> " + expected,
                                           () -> assertThat(decoded(kind, false, code)).isEqualTo(sample(kind, false, expected)));
        }

        /// What a `CURSOR_EXPIRED` decode of a known code gives: the code's own cause, except `NOT_APPLICABLE` (row 2).
        private static RemovalCause cursorCauseOf(RemovalCause code) {
            return code == RemovalCause.NOT_APPLICABLE
                   ? RemovalCause.UNRECORDED
                   : code;
        }

        private static ReadRefusal elevenArgument(Kind kind) {
            return new ReadRefusal(kind, "s", 5, 101L, 202L, false, "d", "p1", "p2", PRESENTED, CURRENT);
        }

        private static List<Kind> otherKinds() {
            return Arrays.stream(Kind.values()).filter(kind -> kind != Kind.CURSOR_EXPIRED).toList();
        }

        private static List<RemovalCause> namingARemoval() {
            return Arrays.stream(RemovalCause.values()).filter(cause -> cause != RemovalCause.NOT_APPLICABLE).toList();
        }

        /// The reasons a cause goes with: the one it fixes, or both when it fixes none.
        private static List<Reason> reasonsOf(RemovalCause cause) {
            return StreamError.CursorExpired.reasonOf(cause).map(reason -> List.of(reason)).or(() -> List.of(Reason.values()));
        }
    }

    /// T-C5 (stream-mem M-1c): a refused cursor carries the SET of removal causes behind the range, on the type
    /// (`CursorExpired.causes`) and on the wire (`ReadRefusal.causes`, the bit `1 << ordinal` per cause).
    @Nested
    class TheCauseSet {
        private static final Set<RemovalCause> FULL_REMOVAL_SET = EnumSet.range(RemovalCause.RETENTION, RemovalCause.LIFE_LOST);
        private static final int UNKNOWN_BIT = 1 << RemovalCause.UNKNOWN.ordinal();
        private static final int NOT_APPLICABLE_BIT = 1 << RemovalCause.NOT_APPLICABLE.ordinal();

        /// A local refusal and its forwarded copy agree on (reason, cause, causes): each single cause, a two-cause set and the
        /// full 11-cause removal set go through `readRefusal`, the codec and `toCause` and come back EQUAL. Mutant: drop
        /// `causes` from the encode in `ofStreamError` -> RED.
        @Test
        void aCursorExpired_roundTripsItsCauseSet_asItself() {
            for (var expired : cursorsWithSets()) {
                var sent = ReadRefusal.readRefusal(expired).unwrap();
                var received = roundTrip(ReadForwardResponse.refusalResponse(SENDER, "corr", sent, expired.message()));
                var first = received.refusal().unwrap();

                assertThat(sent.toCause(expired.message())).as("locally rebuilt %s", expired).isEqualTo(expired);
                assertThat(first).as("%s", expired).isEqualTo(sent);
                assertThat(first.causes()).as("the mask of %s", expired).isEqualTo(StreamError.CursorExpired.maskOf(expired.causes()));
                assertThat(first.toCause(received.errorMessage())).as("forwarded %s", expired).isEqualTo(expired);
            }
        }

        /// The shim default: a form naming a cause gives `{cause}`, `UNRECORDED` gives the empty set, on the type and on the
        /// pre-`causes` `ReadRefusal` forms. Mutant: the empty set for every shim form -> RED.
        @Test
        void theShimForms_deriveTheSetFromTheCauseTheyName() {
            assertThat(new StreamError.CursorExpired(1L, 2L, Reason.EXPIRED, RemovalCause.AGE).causes()).containsExactly(RemovalCause.AGE);
            assertThat(new StreamError.CursorExpired(1L, 2L, RemovalCause.LIFE_LOST).causes()).containsExactly(RemovalCause.LIFE_LOST);
            assertThat(new StreamError.CursorExpired(1L, 2L, Reason.LOST, RemovalCause.UNRECORDED).causes()).isEmpty();
            assertThat(new StreamError.CursorExpired(1L, 2L, Reason.LOST).causes()).isEmpty();
            assertThat(new StreamError.CursorExpired(1L, 2L).causes()).isEmpty();
            assertThat(withTwelveArguments(Kind.CURSOR_EXPIRED, RemovalCause.AGE).causes()).isEqualTo(1 << RemovalCause.AGE.ordinal());
            assertThat(withTwelveArguments(Kind.CURSOR_EXPIRED, RemovalCause.UNRECORDED).causes()).isZero();
            assertThat(withTwelveArguments(Kind.CURSOR_EXPIRED, RemovalCause.NOT_APPLICABLE).causes()).isZero();
            assertThat(new ReadRefusal(Kind.CURSOR_EXPIRED, "s", 5, 101L, 202L, false, "d", "p1", "p2", PRESENTED, CURRENT).causes()).isZero();
        }

        /// A `CursorExpired` refuses `NOT_APPLICABLE` in its set (as it refuses it as the cause), and its set is immutable.
        /// Mutant: the compact constructor drops the refusal or stores the caller's set -> RED.
        @Test
        void theSet_refusesNotApplicable_andIsImmutable() {
            var mutable = EnumSet.of(RemovalCause.AGE);
            var expired = new StreamError.CursorExpired(1L, 2L, Reason.EXPIRED, RemovalCause.AGE, mutable);

            mutable.add(RemovalCause.COUNT);
            assertThat(expired.causes()).containsExactly(RemovalCause.AGE);
            assertThatThrownBy(() -> expired.causes().add(RemovalCause.COUNT)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> new StreamError.CursorExpired(1L, 2L, Reason.EXPIRED, RemovalCause.AGE, Set.of(RemovalCause.NOT_APPLICABLE)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /// The mask pair: one bit per ordinal. Mutant: a bit position off by one, or `maskOf` not the inverse -> RED.
        @Test
        void theMaskPair_isOneBitPerOrdinal_andInverseOnEveryConstructibleSet() {
            for (var cause : RemovalCause.values()) {
                assertThat(StreamError.CursorExpired.maskOf(Set.of(cause))).as("%s", cause).isEqualTo(1 << cause.ordinal());
            }
            assertThat(StreamError.CursorExpired.maskOf(Set.of())).isZero();
            for (var set : List.of(Set.<RemovalCause>of(), FULL_REMOVAL_SET, EnumSet.of(RemovalCause.RETENTION, RemovalCause.UNKNOWN))) {
                assertThat(StreamError.CursorExpired.causesOf(StreamError.CursorExpired.maskOf(set))).as("%s", set).isEqualTo(set);
            }
        }

        /// Every kind but `CURSOR_EXPIRED` carries `causes = 0`, whatever was written. Mutant: drop the kind test in the compact
        /// constructor -> RED.
        @Test
        void everyOtherKind_carriesNoCauses() {
            for (var kind : Kind.values()) {
                if (kind != Kind.CURSOR_EXPIRED) {
                    assertThat(thirteenArguments(kind, RemovalCause.NOT_APPLICABLE, -1).causes()).as("%s", kind).isZero();
                    assertThat(thirteenArguments(kind, RemovalCause.NOT_APPLICABLE, 1).causes()).as("%s", kind).isZero();
                }
            }
        }

        /// A bit at or above `UNKNOWN`'s ordinal is a newer node's code and reads back as the `UNKNOWN` bit; the
        /// `NOT_APPLICABLE` bit is dropped. Mutant: keep the raw mask -> RED.
        @Test
        void aCursorMask_foldsHighBitsToUnknown_andDropsNotApplicable() {
            var bit14 = 1 << 14;
            var age = 1 << RemovalCause.AGE.ordinal();

            assertThat(thirteenArguments(Kind.CURSOR_EXPIRED, RemovalCause.AGE, age | bit14).causes()).isEqualTo(age | UNKNOWN_BIT);
            assertThat(thirteenArguments(Kind.CURSOR_EXPIRED, RemovalCause.AGE, bit14).causes()).isEqualTo(UNKNOWN_BIT);
            assertThat(thirteenArguments(Kind.CURSOR_EXPIRED, RemovalCause.AGE, Integer.MIN_VALUE).causes()).isEqualTo(UNKNOWN_BIT);
            assertThat(thirteenArguments(Kind.CURSOR_EXPIRED, RemovalCause.AGE, UNKNOWN_BIT).causes()).isEqualTo(UNKNOWN_BIT);
            assertThat(thirteenArguments(Kind.CURSOR_EXPIRED, RemovalCause.AGE, age | NOT_APPLICABLE_BIT).causes()).isEqualTo(age);
            assertThat(thirteenArguments(Kind.CURSOR_EXPIRED, RemovalCause.AGE, NOT_APPLICABLE_BIT).causes()).isZero();
        }

        /// The same table on a DECODED value: the bytes carry the mask, the compact constructor decides, and the rebuilt
        /// `CursorExpired` names the folded set. Mutant: the decode keeps the raw mask or `toCause` rebuilds from the
        /// shim default -> RED.
        @Test
        void aDecodedMask_isNormalised_andRebuildsAsTheSet() {
            var decoded = decoded(Kind.CURSOR_EXPIRED, false, RemovalCause.AGE.ordinal(), (1 << RemovalCause.AGE.ordinal()) | (1 << 14) | NOT_APPLICABLE_BIT);

            assertThat(decoded.causes()).isEqualTo((1 << RemovalCause.AGE.ordinal()) | UNKNOWN_BIT);
            assertThat(decoded.toCause("text")).isEqualTo(new StreamError.CursorExpired(101L,
                                                                                         202L,
                                                                                         Reason.EXPIRED,
                                                                                         RemovalCause.AGE,
                                                                                         EnumSet.of(RemovalCause.AGE, RemovalCause.UNKNOWN)));
            assertThat(decoded(Kind.STREAM_NOT_FOUND, false, RemovalCause.AGE.ordinal(), -1).causes()).isZero();
        }

        private static List<StreamError.CursorExpired> cursorsWithSets() {
            var singles = Arrays.stream(RemovalCause.values())
                                .filter(cause -> cause != RemovalCause.NOT_APPLICABLE)
                                .map(cause -> new StreamError.CursorExpired(11L, 29L, reasonOf(cause), cause, Set.of(cause)));
            var others = Stream.of(new StreamError.CursorExpired(11L, 29L, Reason.EXPIRED, RemovalCause.RETENTION, EnumSet.of(RemovalCause.AGE, RemovalCause.COUNT)),
                                   new StreamError.CursorExpired(11L, 29L, Reason.LOST, RemovalCause.LIFE_LOST, FULL_REMOVAL_SET),
                                   new StreamError.CursorExpired(11L, 29L, Reason.LOST, RemovalCause.UNRECORDED, FULL_REMOVAL_SET),
                                   new StreamError.CursorExpired(11L, 29L, Reason.LOST, RemovalCause.UNRECORDED, Set.of()));

            return Stream.concat(singles, others).toList();
        }

        private static Reason reasonOf(RemovalCause cause) {
            return StreamError.CursorExpired.reasonOf(cause).fold(() -> Reason.EXPIRED, reason -> reason);
        }

        private static ReadRefusal withTwelveArguments(Kind kind, RemovalCause cause) {
            return new ReadRefusal(kind, "s", 5, 101L, 202L, false, "d", "p1", "p2", PRESENTED, CURRENT, cause);
        }

        private static ReadRefusal thirteenArguments(Kind kind, RemovalCause cause, int causes) {
            return new ReadRefusal(kind, "s", 5, 101L, 202L, false, "d", "p1", "p2", PRESENTED, CURRENT, cause, causes);
        }
    }

    /// T-P8 unit arms (stream-mem M-1b), the aether-stream half: totality, the round trip, the content pins from sources that
    /// are not the row, and the transport family against the family's own marks. The status arms are `StreamErrorStatusTest`.
    @Nested
    class TheOneAnswerSwitch {
        private final List<StreamForwardError> transport = transportSamples();

        @Test
        void everyKind_hasARow_andEveryRowIsReached() {
            var reached = EnumSet.noneOf(Answer.class);

            for (var kind : Kind.values()) {
                assertThat(kind.answer()).as("the row of %s", kind).isNotNull();
                reached.add(kind.answer());
            }
            reached.add(ReadRefusal.answerOf(new StreamError.EpochDiverged(PRESENTED, 3L, 3L)));
            transport.forEach(cause -> reached.add(ReadRefusal.answerOf(cause)));

            assertThat(reached).as("a row no cause reaches is dead").containsExactlyInAnyOrder(Answer.values());
        }

        @Test
        void theNewKind_isAppendedBeforeUnknown() {
            assertThat(Kind.STREAM_NOT_MATERIALIZED.ordinal()).isEqualTo(Kind.UNKNOWN.ordinal() - 1);
            assertThat(Kind.UNKNOWN.ordinal()).isEqualTo(16);
            assertThat(Kind.STREAM_NOT_MATERIALIZED.answer()).isEqualTo(Answer.NN_SURFACE);
            assertThat(ReadRefusal.answerOf(new StreamError.StreamNotMaterialized("orders"))).isEqualTo(Answer.NN_SURFACE);
        }

        @Test
        void theRoundTrip_everyKindComesBackAsItself_andAnswersItsRow() {
            for (var kind : Kind.values()) {
                var cause = blank(kind).toCause("");

                if (kind != Kind.UNKNOWN) {
                    assertThat(ReadRefusal.readRefusal(cause).unwrap().kind()).as("the kind of %s", kind).isEqualTo(kind);
                }
                assertThat(ReadRefusal.answerOf(cause)).as("the row of the cause rebuilt from %s", kind).isEqualTo(kind.answer());
            }
        }

        /// Pin (5a), literal: a row-derived expectation cannot see a wrong row. Mutants: `UNKNOWN` to `NN_SURFACE`, or
        /// `OWNER_NOT_ACTIVATED` to `NN_RETRY`.
        @Test
        void theRowsThatAreNotSurface_areExactlyThree_andHoldExactlyTwoKinds() {
            var notSurface = Arrays.stream(Answer.values()).filter(row -> row.behaviour() != Behaviour.SURFACE).collect(Collectors.toSet());
            var kindsInThem = Arrays.stream(Kind.values()).filter(kind -> notSurface.contains(kind.answer())).collect(Collectors.toSet());

            assertThat(notSurface).containsExactlyInAnyOrder(Answer.NN_DEGRADE, Answer.NN_RETRY, Answer.FAULT_DEGRADE);
            assertThat(kindsInThem).containsExactlyInAnyOrder(Kind.UNKNOWN, Kind.PARTITION_NOT_LOCAL);
        }

        /// #3481 fix round item 4: every row's class and behaviour as literals, so a row moved to another class or behaviour
        /// goes red (the 9 `answerClass()` survivors, and `NN_RETRY` RETRY to DEGRADE, which the count above cannot tell apart).
        @Test
        void everyRow_hasItsLiteralClassAndBehaviour() {
            var expected = java.util.Map.of(Answer.NN_SURFACE, List.of(ReadRefusal.AnswerClass.NN, Behaviour.SURFACE),
                                            Answer.NN_DEGRADE, List.of(ReadRefusal.AnswerClass.NN, Behaviour.DEGRADE),
                                            Answer.NN_RETRY, List.of(ReadRefusal.AnswerClass.NN, Behaviour.RETRY),
                                            Answer.RQ_INVALID, List.of(ReadRefusal.AnswerClass.RQ, Behaviour.SURFACE),
                                            Answer.RQ_NOT_FOUND, List.of(ReadRefusal.AnswerClass.RQ, Behaviour.SURFACE),
                                            Answer.CE_L, List.of(ReadRefusal.AnswerClass.CE_OR_L, Behaviour.SURFACE),
                                            Answer.L_EPOCH, List.of(ReadRefusal.AnswerClass.L, Behaviour.SURFACE),
                                            Answer.FAULT, List.of(ReadRefusal.AnswerClass.FAULT, Behaviour.SURFACE),
                                            Answer.FAULT_DEGRADE, List.of(ReadRefusal.AnswerClass.FAULT, Behaviour.DEGRADE));

            assertThat(expected).hasSize(Answer.values().length);

            for (var row : Answer.values()) {
                assertThat(row.answerClass()).as("answerClass of %s", row).isEqualTo(expected.get(row).get(0));
                assertThat(row.behaviour()).as("behaviour of %s", row).isEqualTo(expected.get(row).get(1));
            }
        }

        @Test
        void aKindlessCause_takesUnknownsRow_onEveryFamily() {
            var kindless = List.of(StreamError.General.BUFFER_EMPTY,
                                   new StreamError.WalReplayMismatch("orders", 0, Path.of("orders-0.wal"), 1L, 2L),
                                   new SegmentError.WalRangeMissing("orders", 0, 1L, 2L, 0),
                                   new StorageError.ReadError("disk"),
                                   Causes.cause("something else"));

            for (var cause : kindless) {
                assertThat(ReadRefusal.answerOf(cause)).as("%s", cause).isEqualTo(Kind.UNKNOWN.answer()).isEqualTo(Answer.NN_DEGRADE);
            }
        }

        @Test
        void anEpochDiverged_isTheLostLifeRow() {
            assertThat(ReadRefusal.answerOf(new StreamError.EpochDiverged(PRESENTED, 3L, 3L))).isEqualTo(Answer.L_EPOCH);
        }

        /// Pin (5c): the transport member's row follows the family's own marks, `Cause.Terminal` or a `General` constant whose
        /// `isTransient()` is false. Mutant: `ForwardNotSent` placed in `FAULT_DEGRADE`.
        @Test
        void aTransportMembersRow_followsTheFamilysMarks() {
            for (var cause : transport) {
                var final_ = cause.isTerminal() || cause instanceof StreamForwardError.General && !cause.isTransient();

                assertThat(ReadRefusal.answerOf(cause)).as("%s", cause).isEqualTo(final_ ? Answer.FAULT_DEGRADE : Answer.NN_DEGRADE);
            }
        }

        @Test
        void theTransportSamples_coverEveryMemberOfTheFamily() {
            var sampled = transport.stream().map(Object::getClass).collect(Collectors.toSet());
            var declared = Arrays.stream(StreamForwardError.class.getPermittedSubclasses()).collect(Collectors.toSet());

            assertThat(sampled).as("a member added to the family with no sample would be unplaced here").isEqualTo(declared);
            assertThat(transport).containsAll(Arrays.asList(StreamForwardError.General.values()));
        }

        /// Pin (5d): literal rows of five causes no other source covers.
        @Test
        void fiveCauses_haveLiteralRows() {
            var outcome = new WriteOutcome.EncodeFailed(SENDER, "ReadForward");

            assertThat(ReadRefusal.answerOf(new SegmentError.SealInFlight("orders", 1, 23L))).isEqualTo(Answer.NN_SURFACE);
            assertThat(ReadRefusal.answerOf(new SegmentError.SealedRangeMissing("orders", 1, 23L, 31L))).isEqualTo(Answer.NN_SURFACE);
            assertThat(ReadRefusal.answerOf(new StreamForwardError.ReadForwardFailed("text"))).isEqualTo(Answer.NN_DEGRADE);
            assertThat(ReadRefusal.answerOf(StreamForwardError.ForwardNotEncoded.FACTORY.apply(outcome))).isEqualTo(Answer.FAULT_DEGRADE);
            assertThat(ReadRefusal.answerOf(StreamForwardError.General.READ_RESPONSE_OVERSIZED)).isEqualTo(Answer.FAULT_DEGRADE);
            assertThat(Answer.FAULT_DEGRADE.answerClass()).isNotEqualTo(Answer.NN_DEGRADE.answerClass());
        }

        /// Arm (6): the segment family is decided member by member; only the two a read raises are carried.
        @Test
        void everySegmentError_isDecided_andOnlyTwoAreCarried() {
            var samples = List.<SegmentError> of(new SegmentError.SealInFlight("orders", 1, 23L),
                                                 new SegmentError.SealedRangeMissing("orders", 1, 23L, 31L),
                                                 SegmentError.General.SEGMENT_REF_NOT_FOUND,
                                                 new SegmentError.WalRangeMissing("orders", 0, 1L, 2L, 0),
                                                 new SegmentError.CorruptRecord("seg", 1, 2, "corrupt"));
            var sampled = samples.stream().map(Object::getClass).collect(Collectors.toSet());
            var carried = samples.stream().filter(cause -> ReadRefusal.readRefusal(cause).isPresent()).map(Object::getClass).collect(Collectors.toSet());

            assertThat(sampled).isEqualTo(Arrays.stream(SegmentError.class.getPermittedSubclasses()).collect(Collectors.toSet()));
            assertThat(carried).containsExactlyInAnyOrder(SegmentError.SealInFlight.class, SegmentError.SealedRangeMissing.class);
        }

        private static ReadRefusal blank(Kind kind) {
            return new ReadRefusal(kind, "orders", 1, 0L, 0L, false, "", "p1", "p2", Epoch.ZERO, Epoch.ZERO);
        }

        private static List<StreamForwardError> transportSamples() {
            var peer = new NodeId("peer");

            return List.of(StreamForwardError.General.FORWARD_TIMEOUT,
                           StreamForwardError.General.GOVERNOR_UNAVAILABLE,
                           StreamForwardError.General.READ_FORWARD_TIMEOUT,
                           StreamForwardError.General.READ_RESPONSE_OVERSIZED,
                           StreamForwardError.General.STREAM_FORWARD_UNAVAILABLE,
                           new StreamForwardError.RemotePublishFailed("d"),
                           new StreamForwardError.RemotePublishRetryable("d"),
                           StreamForwardError.ForwardNotSent.FACTORY.apply(new WriteOutcome.NoPeerState(peer)),
                           StreamForwardError.ForwardNotEncoded.FACTORY.apply(new WriteOutcome.EncodeFailed(peer, "ReadForward")),
                           StreamForwardError.PublishNotSent.FACTORY.apply(new WriteOutcome.NoPeerState(peer)),
                           StreamForwardError.PublishNotEncoded.FACTORY.apply(new WriteOutcome.EncodeFailed(peer, "PublishForward")),
                           new StreamForwardError.ReadForwardFailed("d"));
        }
    }

    /// T-P12 (a), stream-mem M-1b: one kindless cause gets the SAME row where it is raised and once it has crossed a forward.
    /// X1 a third family, X2 kindless and `Cause.Terminal`, X3 a kindless `StreamError` whose pin status is 500. A cause with no
    /// kind leaves the serving node as the text of a failure response (the handler's `failureAnswer` branch when
    /// `readRefusal` is none, asserted here) and the REAL client rebuilds it. Mutants: choose `FAULT_DEGRADE` by `isTerminal()`
    /// for any family (X2's rows differ); make `answerOf`'s last step return `FAULT` (all three).
    @Nested
    class OneCauseOneRow {
        private static final List<Cause> KINDLESS = List.of(new StorageError.ReadError("disk"),
                                                            new SegmentError.WalRangeMissing("orders", 0, 1L, 2L, 0),
                                                            new StreamError.WalReplayMismatch("orders", 0, Path.of("orders-0.wal"), 1L, 2L));

        @Test
        void aKindlessCause_hasTheSameRowOnTheServingNodeAndOnTheAskingNode() {
            for (var raised : KINDLESS) {
                assertThat(ReadRefusal.readRefusal(raised)).as("%s has no kind", raised).isEqualTo(Option.none());

                var rebuilt = rebuiltByTheClient(raised);

                assertThat(rebuilt).as("rebuilt from %s", raised).isInstanceOf(StreamForwardError.ReadForwardFailed.class);
                assertThat(ReadRefusal.answerOf(rebuilt)).as("%s", raised).isEqualTo(ReadRefusal.answerOf(raised)).isEqualTo(Answer.NN_DEGRADE);
            }
        }

        private static Cause rebuiltByTheClient(Cause raised) {
            var clientRef = new AtomicReference<StreamForwardClient>();
            var transport = AcceptingStreamForwardTransport.accepting((_, message) -> clientRef.get()
                                                                                              .onReadForwardResponse(failureOverTheWire(((ReadForward) message).correlationId(),
                                                                                                                                         raised)));
            var client = streamForwardClient(new NodeId("asking"), transport, TimeSpan.timeSpan(5).seconds());

            clientRef.set(client);

            return client.readRemote(SENDER, "orders", 0, 0L, 10).await().fold(cause -> cause, _ -> Causes.cause("read succeeded"));
        }

        private static ReadForwardResponse failureOverTheWire(String correlationId, Cause raised) {
            return roundTrip(ReadForwardResponse.failureResponse(SENDER, correlationId, raised.message()));
        }
    }

    /// The sample's bytes with the trailing cause code replaced by `code`, and the `causes` mask after it by what a sender
    /// of the shim forms writes for that code (`{cause}`, none for `UNRECORDED` and `NOT_APPLICABLE`): `causes` is the
    /// record's last component (an `int`: its tag byte and four bytes) and `cause` the one before it, so the tail is asserted
    /// (a codec that moves either fails here rather than patching another slot).
    private static ReadRefusal decoded(Kind kind, boolean flag, int code) {
        return decoded(kind, flag, code, shimMask(code));
    }

    private static ReadRefusal decoded(Kind kind, boolean flag, int code, int mask) {
        var written = sample(kind, flag, RemovalCause.NOT_APPLICABLE);
        var buffer = Unpooled.buffer();

        CODEC.write(buffer, written);
        assertThat(buffer.getInt(buffer.writerIndex() - 4)).as("the causes mask is the last four bytes")
                                                           .isEqualTo(written.causes());
        assertThat(buffer.getByte(buffer.writerIndex() - 5)).as("the mask carries the int tag")
                                                            .isEqualTo((byte) SliceCodec.TAG_INT);
        assertThat(buffer.getByte(buffer.writerIndex() - 6)).as("the cause code is the byte before the mask")
                                                            .isEqualTo((byte) written.cause().ordinal());
        buffer.writerIndex(buffer.writerIndex() - 6);
        SliceCodec.writeCompact(buffer, code);
        buffer.writeByte(SliceCodec.TAG_INT);
        buffer.writeInt(mask);

        return CODEC.read(buffer);
    }

    /// The mask the shim forms derive for a cause code; a code no node of this generation knows names `UNKNOWN`.
    private static int shimMask(int code) {
        var cause = code >= 0 && code < RemovalCause.values().length
                    ? RemovalCause.values()[code]
                    : RemovalCause.UNKNOWN;

        return StreamError.CursorExpired.maskOf(StreamError.CursorExpired.causesNamedBy(cause));
    }

    /// Every slot distinct from its neighbours, so a decode that crosses two of them fails by value.
    private static ReadRefusal sample(Kind kind, boolean flag, RemovalCause cause) {
        return new ReadRefusal(kind, "s", 5, 101L, 202L, flag, "d", "p1", "p2", PRESENTED, CURRENT, cause);
    }

    private static ReadForwardResponse roundTrip(ReadForwardResponse original) {
        var buffer = Unpooled.buffer();

        CODEC.write(buffer, original);

        return CODEC.read(buffer);
    }
}
