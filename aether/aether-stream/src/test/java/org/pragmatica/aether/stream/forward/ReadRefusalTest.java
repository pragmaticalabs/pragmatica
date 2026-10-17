// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.forward.ReadRefusal.Kind;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.segment.SegmentError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


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
                                                       new SegmentError.SealedRangeMissing("orders", 1, 23L, 31L));

    @Test
    void everyKindButUnknown_hasACarriedSample_andNothingElse() {
        var sampled = CARRIED.stream()
                             .map(cause -> ReadRefusal.readRefusal(cause).unwrap().kind())
                             .collect(Collectors.toSet());
        var declared = Arrays.stream(Kind.values()).filter(kind -> kind != Kind.UNKNOWN).collect(Collectors.toSet());

        assertThat(sampled).as("a kind with no sample would be untested on the wire").isEqualTo(declared);
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

    private static ReadForwardResponse roundTrip(ReadForwardResponse original) {
        var buffer = Unpooled.buffer();

        CODEC.write(buffer, original);

        return CODEC.read(buffer);
    }
}
