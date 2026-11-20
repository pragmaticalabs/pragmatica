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

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.StreamError;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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

    private static ReadForwardResponse roundTrip(ReadForwardResponse original) {
        var buffer = Unpooled.buffer();

        CODEC.write(buffer, original);

        return CODEC.read(buffer);
    }
}
