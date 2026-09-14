/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// #667 added `responder` to `SyncResponse`. The wire-assignment gates (`SystemCodecPinningTest`,
/// `WireAssignmentTripwireTest`) pin tags and enum ordinals, not record SHAPE (#1147), so shape is
/// pinned here — but by exactly one of the two tests below, and not the one the #667 ruling named.
///
/// **`ordinalBeyondThisNode_decodesToUnknown_withTheRestOfTheResponseIntact` is the shape pin.** It
/// hand-frames the bytes, so it fails the moment the record's component list changes. The round-trip
/// test cannot: it writes and reads with the SAME regenerated codec, which agrees with itself for any
/// shape. Measured — adding a fourth component to `SyncResponse` leaves the round-trip GREEN and
/// reddens the hand-framed test with `IndexOutOfBounds readerIndex(35) + length(1) exceeds
/// writerIndex(35)`.
///
/// Do not trim the hand-framed test as redundant with the round-trip. Trimming it the other way round
/// would leave a test that LOOKS like a shape pin and pins nothing.
class SyncResponseResponderStateCodecTest {
    private static final SliceCodec CODEC = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), allCodecs());
    private static final NodeId SENDER = NodeId.nodeId("node-7").unwrap();
    private static final byte[] SNAPSHOT = "snapshot".getBytes(StandardCharsets.UTF_8);

    private static List<SliceCodec.TypeCodec<?>> allCodecs() {
        var all = new ArrayList<SliceCodec.TypeCodec<?>>();

        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(RabiaCodecs.CODECS);

        return all;
    }

    /// Pins the VALUE round-trip: each responder state survives encode/decode through the real
    /// generated codec. It does NOT pin the record's shape — same codec on both sides — see the class
    /// doc.
    @Test
    void responderState_roundTrips_throughTheGeneratedCodec() {
        for (var responder : List.of(ResponderState.LIVE, ResponderState.COLD)) {
            var original = new SyncResponse<TestCommand>(SENDER, SavedState.savedState(SNAPSHOT, Phase.phase(42), List.of()), responder);
            var buf = Unpooled.buffer();

            CODEC.write(buf, original);

            SyncResponse<?> decoded = CODEC.read(buf);

            assertThat(decoded.responder()).as("responder %s must survive the wire", responder).isEqualTo(responder);
            assertThat(decoded.sender()).isEqualTo(SENDER);
            assertThat(decoded.state().lastCommittedPhase()).isEqualTo(Phase.phase(42));
        }
    }

    /// Frames a SyncResponse exactly as the generated `writeBody` does, with the responder ordinal
    /// supplied by the caller — the bytes a node with an extra constant would emit.
    ///
    /// Doubles as the record's SHAPE pin: the framing here is written out by hand, so any added,
    /// removed or reordered component of `SyncResponse` desynchronizes it from the generated reader
    /// and this test fails. Nothing else in the tree does that (#1147).
    @Test
    void ordinalBeyondThisNode_decodesToUnknown_withTheRestOfTheResponseIntact() {
        var buf = Unpooled.buffer();

        SliceCodec.writeCompact(buf, RabiaProtocolMessage_Synchronous_SyncResponseCodec.TAG);
        SliceCodec.writeCompact(buf, org.pragmatica.consensus.NodeIdCodec.TAG);
        org.pragmatica.consensus.NodeIdCodec.writeBody(CODEC, buf, SENDER);
        CODEC.write(buf, SavedState.<TestCommand>savedState(SNAPSHOT, Phase.phase(42), List.of()));
        SliceCodec.writeCompact(buf, ResponderStateCodec.TAG);
        SliceCodec.writeCompact(buf, ResponderState.values().length);

        SyncResponse<?> decoded = CODEC.read(buf);

        assertThat(decoded.responder()).isEqualTo(ResponderState.UNKNOWN);
        assertThat(decoded.sender()).isEqualTo(SENDER);
        assertThat(decoded.state().lastCommittedPhase()).isEqualTo(Phase.phase(42));
    }
}
