// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.cluster.metrics;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.NodeIdCodec;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.UnknownTypeTagException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.serialization.FrameworkCodecs.frameworkCodecs;

/// #964: an enum ordinal a node cannot name must reach the handler as `UNKNOWN` with the rest of the
/// message intact, and a type tag it cannot resolve must fail with something an operator can act on.
///
/// Everything here goes through REAL BYTES and the REAL GENERATED CODECS. `PeerHealthObservationCodec`
/// and `HealthHintWireCodec` are emitted by the annotation processor from `@Codec`; nothing in this
/// file stubs a serializer, and the assertions are made against what came back out of a `ByteBuf`.
/// That matters because the two ways this defect has hidden before are a stubbed serializer that never
/// encodes anything, and a fixture that hand-feeds the value whose absence IS the defect.
///
/// The unknown ordinal is `HealthHintWire.values().length` — one past the last constant this node has,
/// computed rather than written as a literal. That is precisely the byte a node with one more constant
/// emits, and the expression stays correct as constants are added, so the test cannot silently
/// degrade into asserting something already known.
class UnknownEnumOrdinalWireTest {
    private static final NodeId PEER = NodeId.nodeId("peer-a").unwrap();
    private static final long TERM = 7L;
    private static final long COUNTER = 11L;
    private static final long PRODUCED_AT = 1_700_000_000_000L;

    private static SliceCodec codec() {
        return SliceCodec.sliceCodec(SliceCodec.sliceCodec(frameworkCodecs(), ConsensusCodecs.CODECS),
                                     MetricsCodecs.CODECS);
    }

    /// Frames a `PeerHealthObservation` exactly as `PeerHealthObservationCodec.writeBody` does, except
    /// that the hint's ordinal is supplied by the caller. This is the ONLY way to obtain the bytes a
    /// newer node emits without checking a newer node into the tree, and it writes a real ordinal to a
    /// real buffer rather than simulating one.
    private static ByteBuf frameWithHintOrdinal(int hintOrdinal) {
        var buf = Unpooled.buffer();

        SliceCodec.writeCompact(buf, PeerHealthObservationCodec.TAG);
        SliceCodec.writeCompact(buf, NodeIdCodec.TAG);
        NodeIdCodec.writeBody(codec(), buf, PEER);
        SliceCodec.writeCompact(buf, HealthHintWireCodec.TAG);
        SliceCodec.writeCompact(buf, hintOrdinal);
        buf.writeByte(SliceCodec.TAG_LONG);
        buf.writeLong(TERM);
        buf.writeByte(SliceCodec.TAG_LONG);
        buf.writeLong(COUNTER);
        buf.writeByte(SliceCodec.TAG_LONG);
        buf.writeLong(PRODUCED_AT);

        return buf;
    }

    /// POSITIVE CONTROL for every assertion below. If `frameWithHintOrdinal` laid the frame out wrongly
    /// the unknown-ordinal test would still "pass" for the wrong reason, so this proves the same
    /// hand-written frame decodes to the expected constant when the ordinal IS known — and therefore
    /// that the only variable between the two tests is the ordinal.
    @Test
    void handWrittenFrame_withKnownOrdinal_decodesToThatConstant() {
        PeerHealthObservation decoded = codec().read(frameWithHintOrdinal(HealthHintWire.SUSPECTED.ordinal()));

        assertThat(decoded.hint()).isEqualTo(HealthHintWire.SUSPECTED);
        assertThat(decoded.peerId()).isEqualTo(PEER);
        assertThat(decoded.observedEpochTerm()).isEqualTo(TERM);
    }

    /// The load-bearing assertion. Before #964 this threw `ArrayIndexOutOfBoundsException` out of
    /// `values()[readCompact(buf)]`, `QuicLaneDataHandler` caught it, and the message was gone.
    @Test
    void unknownOrdinal_decodesToUnknown_ratherThanThrowing() {
        var beyondThisNode = HealthHintWire.values().length;

        PeerHealthObservation decoded = codec().read(frameWithHintOrdinal(beyondThisNode));

        assertThat(decoded.hint()).isEqualTo(HealthHintWire.UNKNOWN);
    }

    /// Requirement B: the node still needs the rest of the message. A drop would lose the peer
    /// identity and all three timestamps along with the one field it could not read.
    @Test
    void unknownOrdinal_leavesEveryOtherFieldIntact() {
        PeerHealthObservation decoded = codec().read(frameWithHintOrdinal(HealthHintWire.values().length));

        assertThat(decoded.peerId()).isEqualTo(PEER);
        assertThat(decoded.observedEpochTerm()).isEqualTo(TERM);
        assertThat(decoded.observedEpochCounter()).isEqualTo(COUNTER);
        assertThat(decoded.producedAtMs()).isEqualTo(PRODUCED_AT);
    }

    /// A far-out ordinal (a peer several versions ahead, or a corrupt byte) behaves identically. The
    /// bounds check is a range test, not an off-by-one guard for the single next constant.
    @Test
    void ordinalFarBeyondTheKnownRange_alsoDecodesToUnknown() {
        PeerHealthObservation decoded = codec().read(frameWithHintOrdinal(9_999));

        assertThat(decoded.hint()).isEqualTo(HealthHintWire.UNKNOWN);
    }

    /// The counter is what an operator reads; the log line is throttled and the sentinel alone tells
    /// them nothing about volume. Asserted as a DELTA because the counter is JVM-global and other
    /// tests in this class also decode unknown ordinals.
    @Test
    void unknownOrdinal_isCounted() {
        var before = SliceCodec.unknownEnumOrdinalCount();

        codec().read(frameWithHintOrdinal(HealthHintWire.values().length));

        assertThat(SliceCodec.unknownEnumOrdinalCount()).isEqualTo(before + 1);
    }

    /// A known ordinal must NOT touch the counter — otherwise "non-zero means version skew" is false
    /// and the metric is worthless. This is the mutually-exclusive half of the assertion above.
    @Test
    void knownOrdinal_doesNotTouchTheCounter() {
        var before = SliceCodec.unknownEnumOrdinalCount();

        codec().read(frameWithHintOrdinal(HealthHintWire.FAULTY.ordinal()));

        assertThat(SliceCodec.unknownEnumOrdinalCount()).isEqualTo(before);
    }

    /// The OTHER half of #964: an unknown TYPE TAG. Dropping the message is correct per the ruling —
    /// an old node is not expected to handle a new message type — so what is asserted here is that the
    /// failure is IDENTIFIABLE. It carries its own type and the offending tag, which is what lets
    /// `QuicLaneDataHandler` count it and name it instead of logging a generic decode error.
    @Test
    void unknownTypeTag_failsWithAnIdentifiableCauseCarryingTheTag() {
        var buf = Unpooled.buffer();
        var absentTag = SliceCodec.USER_TAG_BASE + 4_242;

        SliceCodec.writeCompact(buf, absentTag);
        buf.writeLong(0L);

        assertThatThrownBy(() -> codec().read(buf))
            .isInstanceOf(UnknownTypeTagException.class)
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(UnknownTypeTagException.class))
            .satisfies(e -> assertThat(e.tag()).isEqualTo(absentTag));
    }

    /// A tag that IS registered must not raise the version-skew type, or the boundary would report a
    /// corrupt frame as a rolling upgrade and send the operator to the wrong place.
    @Test
    void knownTypeTagWithCorruptBody_doesNotReportAsVersionSkew() {
        var buf = Unpooled.buffer();

        SliceCodec.writeCompact(buf, PeerHealthObservationCodec.TAG);

        assertThatThrownBy(() -> codec().read(buf)).isNotInstanceOf(UnknownTypeTagException.class);
    }
}
