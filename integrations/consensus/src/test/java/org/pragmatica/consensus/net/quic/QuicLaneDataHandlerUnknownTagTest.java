/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
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
package org.pragmatica.consensus.net.quic;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.SliceCodec;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.serialization.FrameworkCodecs.frameworkCodecs;

/// #964 at the BOUNDARY, which is where the defect actually lived.
///
/// The codec has always thrown on an unknown tag. What made the failure silent is this handler:
/// `catch (Exception e)` around `deserializer.decode(bytes)`, one generic
/// "Failed to deserialize message from peer {} on lane {}" line, and no counter. An operator saw a
/// deserialization error and had no route from there to "this cluster is running mixed codec
/// versions", and every retry from the sender failed identically, forever.
///
/// Dropping the message stays correct — an old node is not expected to handle a new message type —
/// so what is asserted is that the drop is now ATTRIBUTABLE and COUNTED, and that it is
/// distinguishable from a corrupt frame on a tag this node does know.
class QuicLaneDataHandlerUnknownTagTest {
    private static final NodeId PEER = NodeId.nodeId("peer-a").unwrap();

    private record Harness(EmbeddedChannel channel, QuicTransportMetrics metrics, List<Object> delivered) {}

    private static Harness harness() {
        var codec = SliceCodec.sliceCodec(frameworkCodecs(), ConsensusCodecs.CODECS);
        var metrics = QuicTransportMetrics.quicTransportMetrics();
        var delivered = new ArrayList<>();
        var handler = new QuicLaneDataHandler(PEER,
                                              StreamType.CONSENSUS,
                                              codec,
                                              metrics,
                                              (_, message) -> delivered.add(message),
                                              LoggerFactory.getLogger(QuicLaneDataHandlerUnknownTagTest.class));

        return new Harness(new EmbeddedChannel(handler), metrics, delivered);
    }

    private static ByteBuf frameWithTag(int tag) {
        var buf = Unpooled.buffer();

        SliceCodec.writeCompact(buf, tag);
        buf.writeLong(0xDEADBEEFL);

        return buf;
    }

    @Test
    void unknownTag_isCounted() {
        var harness = harness();

        harness.channel().writeInbound(frameWithTag(SliceCodec.USER_TAG_BASE + 7_777));

        assertThat(harness.metrics().unknownTypeTagDropCount()).isEqualTo(1);
    }

    @Test
    void unknownTag_deliversNothingToTheReceiver() {
        var harness = harness();

        harness.channel().writeInbound(frameWithTag(SliceCodec.USER_TAG_BASE + 7_777));

        assertThat(harness.delivered()).isEmpty();
    }

    /// The channel must survive: an unknown message type from a peer mid-upgrade is not a reason to
    /// tear down the lane carrying every other message from that peer.
    @Test
    void unknownTag_leavesTheChannelOpen() {
        var harness = harness();

        harness.channel().writeInbound(frameWithTag(SliceCodec.USER_TAG_BASE + 7_777));

        assertThat(harness.channel().isActive()).isTrue();
    }

    /// The discriminating half. A malformed body on a tag this node DOES know is a corrupt frame or a
    /// codec bug — a completely different operator action — so it must not move the version-skew
    /// counter. Without this, the counter could be incremented by any decode failure and "non-zero
    /// means mixed codec versions" would be false.
    @Test
    void knownTagWithCorruptBody_doesNotTouchTheUnknownTagCounter() {
        var harness = harness();
        var truncated = Unpooled.buffer();

        SliceCodec.writeCompact(truncated, org.pragmatica.consensus.NodeIdCodec.TAG);

        harness.channel().writeInbound(truncated);

        assertThat(harness.metrics().unknownTypeTagDropCount()).isZero();
        assertThat(harness.delivered()).isEmpty();
    }

    /// The positive control for the harness itself: a well-formed message DOES reach the receiver.
    /// Without it, every assertion above is satisfied by a handler that drops everything.
    @Test
    void wellFormedMessage_reachesTheReceiver() {
        var harness = harness();
        var codec = SliceCodec.sliceCodec(frameworkCodecs(), ConsensusCodecs.CODECS);
        var buf = Unpooled.buffer();

        codec.write(buf, PEER);

        harness.channel().writeInbound(buf);

        assertThat(harness.delivered()).containsExactly(PEER);
        assertThat(harness.metrics().unknownTypeTagDropCount()).isZero();
    }

    /// Every repeated occurrence is counted, not just the first. The log line is throttled elsewhere in
    /// this fix, and a counter that stopped at one would leave an operator unable to see the volume —
    /// which for a permanent, retrying failure is the number that matters.
    @Test
    void repeatedUnknownTags_areAllCounted() {
        var harness = harness();

        harness.channel().writeInbound(frameWithTag(SliceCodec.USER_TAG_BASE + 7_777));
        harness.channel().writeInbound(frameWithTag(SliceCodec.USER_TAG_BASE + 7_777));
        harness.channel().writeInbound(frameWithTag(SliceCodec.USER_TAG_BASE + 8_888));

        assertThat(harness.metrics().unknownTypeTagDropCount()).isEqualTo(3);
    }
}
