/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package org.pragmatica.consensus.net.quic;

import java.util.ArrayList;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.SliceCodec;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.serialization.FrameworkCodecs.frameworkCodecs;

/// A3 characterization (the audit's `[lane-close unverified]` claim): what a receiving lane does with a frame over
/// [QuicClusterServer#MAX_FRAME_LENGTH]. The sender's `LengthFieldPrepender` has no limit, so such a frame IS
/// written; the receiver's `LengthFieldBasedFrameDecoder` (fail-fast) rejects it from its length header alone, and
/// [QuicLaneDataHandler#exceptionCaught] ends and closes the lane stream, so every other message in flight on that
/// peer's lane is lost with it. This is the receiver behaviour the HTTP forwarder's pre-send frame-limit check
/// exists to avoid; the test pins it with the same two handlers the stream pipelines install, in order.
class OversizedLaneFrameTest {
    private static final NodeId PEER = NodeId.nodeId("peer-a").unwrap();

    private static EmbeddedChannel lane(ArrayList<Object> delivered) {
        var codec = SliceCodec.sliceCodec(frameworkCodecs(), ConsensusCodecs.CODECS);
        var handler = new QuicLaneDataHandler(PEER,
                                              StreamType.FORWARD,
                                              codec,
                                              QuicTransportMetrics.quicTransportMetrics(),
                                              (_, message) -> delivered.add(message),
                                              LoggerFactory.getLogger(OversizedLaneFrameTest.class));

        return new EmbeddedChannel(new LengthFieldBasedFrameDecoder(QuicClusterServer.MAX_FRAME_LENGTH, 0, 4, 0, 4),
                                   handler);
    }

    private static ByteBuf lengthHeader(int length) {
        return Unpooled.buffer(4).writeInt(length);
    }

    @Test
    void frameOverTheLimit_closesTheLane() {
        var delivered = new ArrayList<>();
        var lane = lane(delivered);

        lane.writeInbound(lengthHeader(QuicClusterServer.MAX_FRAME_LENGTH + 1));
        lane.runPendingTasks();

        assertThat(lane.isActive()).as("an oversized frame closes the whole lane stream").isFalse();
        assertThat(delivered).isEmpty();
        lane.finishAndReleaseAll();
    }

    /// Positive control: the same lane stays open for a frame within the limit, so the close above is the limit's.
    @Test
    void frameWithinTheLimit_leavesTheLaneOpen() {
        var delivered = new ArrayList<>();
        var lane = lane(delivered);
        var codec = SliceCodec.sliceCodec(frameworkCodecs(), ConsensusCodecs.CODECS);
        var payload = Unpooled.buffer();

        codec.write(payload, PEER);
        var frame = Unpooled.buffer().writeInt(payload.readableBytes()).writeBytes(payload);
        payload.release();

        lane.writeInbound(frame);

        assertThat(lane.isActive()).isTrue();
        assertThat(delivered).containsExactly(PEER);
        lane.finishAndReleaseAll();
    }
}
