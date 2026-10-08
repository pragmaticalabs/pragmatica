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

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.SliceCodec;
import org.slf4j.LoggerFactory;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.Attribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// #1727 (M1) — the lane-end marker, in isolation. The real-connection counterpart, with the FIN
/// actually lost, is [QuicLaneEndMarkerFinLossTest].
class QuicLaneEndMarkerTest {
    private static final NodeId PEER = NodeId.nodeId("peer-a").unwrap();
    private static final StreamType LANE = StreamType.FORWARD;

    private record Harness(QuicLaneDataHandler handler,
                           ChannelHandlerContext ctx,
                           QuicStreamChannel stream,
                           QuicPeerConnection connection,
                           List<Object> delivered) {}

    @SuppressWarnings("unchecked")
    private static Harness harness() {
        var codec = LaneProbe.codec();
        var delivered = new ArrayList<>();
        var handler = new QuicLaneDataHandler(PEER,
                                              LANE,
                                              codec,
                                              QuicTransportMetrics.quicTransportMetrics(),
                                              (_, message) -> delivered.add(message),
                                              LoggerFactory.getLogger(QuicLaneEndMarkerTest.class));
        var connection = mock(QuicPeerConnection.class);
        var attribute = (Attribute<QuicPeerConnection>) mock(Attribute.class);
        var parent = mock(QuicChannel.class);
        var stream = mock(QuicStreamChannel.class);
        var ctx = mock(ChannelHandlerContext.class);

        when(attribute.get()).thenReturn(connection);
        when(parent.attr(PeerOpenedLaneRouter.PEER_CONNECTION)).thenReturn(attribute);
        when(stream.parent()).thenReturn(parent);
        when(ctx.channel()).thenReturn(stream);

        return new Harness(handler, ctx, stream, connection, delivered);
    }

    /// Pin (b): the lane is released on the zero-length frame alone — no FIN event, no channelInactive.
    @Test
    void zeroLengthFrame_releasesTheLane_evenWhenNoFinEverArrives() {
        var harness = harness();

        harness.handler().channelRead0(harness.ctx(), Unpooled.EMPTY_BUFFER);

        verify(harness.connection(), times(1)).streamEnded(LANE, harness.stream());
        assertThat(harness.delivered()).as("the marker is not a message").isEmpty();
    }

    /// The control for the pin above: an ordinary frame must NOT release the lane, or the test above
    /// would pass against a handler that releases on everything.
    @Test
    void ordinaryFrame_doesNotReleaseTheLane() {
        var harness = harness();
        var frame = Unpooled.wrappedBuffer(LaneProbe.codec().encode(LaneProbe.laneProbe(PEER, LANE, "payload")));

        harness.handler().channelRead0(harness.ctx(), frame);

        verify(harness.connection(), never()).streamEnded(any(), any());
        assertThat(harness.delivered()).hasSize(1);
    }

    /// The marker is the FIN's carrier: it must carry the FIN, and its payload must be exactly one
    /// zero 4-byte length prefix (written raw — a stream frame bypasses the length prepender).
    @Test
    void laneEndFrame_isAZeroLengthPrefix_carryingTheFin() {
        var frame = QuicPeerConnection.laneEndFrame();

        assertThat(frame.hasFin()).isTrue();
        assertThat(frame.content().readableBytes()).isEqualTo(4);
        assertThat(frame.content().readInt()).isZero();
    }

    /// Item 3 — the error path ends the lane with the marker before closing, so the peer releases its lane
    /// even if a bare FIN would be lost.
    @Test
    void exceptionCaught_endsTheLaneWithTheMarker_thenCloses() {
        var harness = harness();
        var written = mock(io.netty.channel.ChannelFuture.class);
        var listener = new java.util.concurrent.atomic.AtomicReference<io.netty.util.concurrent.GenericFutureListener>();

        when(harness.stream().isActive()).thenReturn(true);
        when(harness.ctx().writeAndFlush(any())).thenReturn(written);
        when(written.addListener(any())).thenAnswer(invocation -> {
            listener.set(invocation.getArgument(0));
            return written;
        });

        harness.handler().exceptionCaught(harness.ctx(), new IllegalStateException("boom"));

        verify(harness.ctx(), never()).close();
        verify(harness.ctx()).writeAndFlush(org.mockito.ArgumentMatchers.argThat(QuicLaneEndMarkerTest::isLaneEnd));
        try {
            listener.get().operationComplete(written);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        verify(harness.ctx()).close();
    }

    /// A dead stream has nothing to write to: it is only closed.
    @Test
    void exceptionCaught_onADeadStream_justCloses() {
        var harness = harness();

        when(harness.stream().isActive()).thenReturn(false);

        harness.handler().exceptionCaught(harness.ctx(), new IllegalStateException("boom"));

        verify(harness.ctx()).close();
        verify(harness.ctx(), never()).writeAndFlush(any());
    }

    /// Item 3 — a lane refused for want of a verified connection is ended with the marker too.
    @Test
    void refusedUnverifiedLane_endsWithTheMarker_beforeClosing() {
        var harness = harness();
        var written = mock(io.netty.channel.ChannelFuture.class);
        var parent = mock(QuicChannel.class);
        @SuppressWarnings("unchecked")
        var noConnection = (Attribute<QuicPeerConnection>) mock(Attribute.class);

        when(harness.stream().isActive()).thenReturn(true);
        when(harness.stream().parent()).thenReturn(parent);
        when(parent.attr(PeerOpenedLaneRouter.PEER_CONNECTION)).thenReturn(noConnection);
        when(harness.ctx().writeAndFlush(any())).thenReturn(written);
        when(written.addListener(any())).thenReturn(written);

        new PeerOpenedLaneRouter(LaneProbe.codec(), QuicTransportMetrics.quicTransportMetrics(), (_, message) -> {}, LoggerFactory.getLogger(QuicLaneEndMarkerTest.class))
            .attach(harness.ctx(), mock(io.netty.channel.ChannelHandler.class), LANE);

        verify(harness.ctx()).writeAndFlush(org.mockito.ArgumentMatchers.argThat(QuicLaneEndMarkerTest::isLaneEnd));
        verify(harness.ctx(), never()).close();
    }

    private static boolean isLaneEnd(Object message) {
        return message instanceof io.netty.handler.codec.quic.QuicStreamFrame frame
               && frame.hasFin()
               && frame.content().readableBytes() == 4
               && frame.content().getInt(frame.content().readerIndex()) == 0;
    }
}
