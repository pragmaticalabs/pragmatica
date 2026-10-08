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

    /// A mock writable, active stream whose executor captures the scheduled backstop.
    private record ErrorPath(Harness harness, io.netty.channel.ChannelFuture written,
                             java.util.concurrent.atomic.AtomicReference<io.netty.util.concurrent.GenericFutureListener> listener,
                             java.util.concurrent.atomic.AtomicReference<Runnable> backstop) {}

    @SuppressWarnings("unchecked")
    private static ErrorPath errorPath(boolean active, boolean writable) {
        var harness = harness();
        var written = mock(io.netty.channel.ChannelFuture.class);
        var listener = new java.util.concurrent.atomic.AtomicReference<io.netty.util.concurrent.GenericFutureListener>();
        var backstop = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        var executor = mock(io.netty.util.concurrent.EventExecutor.class);

        when(harness.stream().isActive()).thenReturn(active);
        when(harness.stream().isWritable()).thenReturn(writable);
        when(harness.ctx().writeAndFlush(any())).thenReturn(written);
        when(harness.ctx().executor()).thenReturn(executor);
        when(written.addListener(any())).thenAnswer(invocation -> {
            listener.set(invocation.getArgument(0));
            return written;
        });
        when(executor.schedule(any(Runnable.class), org.mockito.ArgumentMatchers.anyLong(), any())).thenAnswer(invocation -> {
            backstop.set(invocation.getArgument(0));
            return null;
        });

        return new ErrorPath(harness, written, listener, backstop);
    }

    /// Item 3 — the error path ends the lane with the marker before closing, so the peer releases its lane
    /// even if a bare FIN would be lost. The close follows the marker write.
    @Test
    void exceptionCaught_endsTheLaneWithTheMarker_thenCloses() throws Exception {
        var path = errorPath(true, true);
        var ctx = path.harness().ctx();

        path.harness().handler().exceptionCaught(ctx, new IllegalStateException("boom"));

        verify(ctx, never()).close();
        verify(ctx).writeAndFlush(org.mockito.ArgumentMatchers.argThat(QuicLaneEndMarkerTest::isLaneEnd));
        path.listener().get().operationComplete(path.written());
        verify(ctx).close();
    }

    /// An error path must not hang: if the marker write never completes (flow-control blocked behind
    /// queued writes), the bounded backstop closes the stream anyway, and only once.
    @Test
    void exceptionCaught_whenTheMarkerWriteNeverCompletes_theBackstopStillCloses() throws Exception {
        var path = errorPath(true, true);
        var ctx = path.harness().ctx();

        path.harness().handler().exceptionCaught(ctx, new IllegalStateException("boom"));

        verify(ctx, never()).close();
        assertThat(path.backstop().get()).as("a backstop close is scheduled").isNotNull();
        path.backstop().get().run();
        verify(ctx, times(1)).close();
        path.listener().get().operationComplete(path.written());
        verify(ctx, times(1)).close();
    }

    /// A stream that is not writable (the marker would queue behind blocked writes) is closed at once,
    /// with nothing written.
    @Test
    void exceptionCaught_onANonWritableStream_closesAtOnce_writingNothing() {
        var path = errorPath(true, false);
        var ctx = path.harness().ctx();

        path.harness().handler().exceptionCaught(ctx, new IllegalStateException("boom"));

        verify(ctx).close();
        verify(ctx, never()).writeAndFlush(any());
    }

    /// A dead stream has nothing to write to: it is only closed.
    @Test
    void exceptionCaught_onADeadStream_justCloses() {
        var path = errorPath(false, true);
        var ctx = path.harness().ctx();

        path.harness().handler().exceptionCaught(ctx, new IllegalStateException("boom"));

        verify(ctx).close();
        verify(ctx, never()).writeAndFlush(any());
    }

    /// Item 3 — a lane refused for want of a verified connection is ended with the marker and then
    /// CLOSED: the close follows the write, and the backstop closes it if the write never completes.
    @Test
    void refusedUnverifiedLane_endsWithTheMarker_andIsEventuallyClosed() throws Exception {
        var path = errorPath(true, true);
        var ctx = path.harness().ctx();
        var parent = mock(QuicChannel.class);
        @SuppressWarnings("unchecked")
        var noConnection = (Attribute<QuicPeerConnection>) mock(Attribute.class);

        when(path.harness().stream().parent()).thenReturn(parent);
        when(parent.attr(PeerOpenedLaneRouter.PEER_CONNECTION)).thenReturn(noConnection);

        new PeerOpenedLaneRouter(LaneProbe.codec(), QuicTransportMetrics.quicTransportMetrics(), (_, message) -> {}, LoggerFactory.getLogger(QuicLaneEndMarkerTest.class))
            .attach(ctx, mock(io.netty.channel.ChannelHandler.class), LANE);

        verify(ctx).writeAndFlush(org.mockito.ArgumentMatchers.argThat(QuicLaneEndMarkerTest::isLaneEnd));
        verify(ctx, never()).close();
        path.backstop().get().run();
        verify(ctx, times(1)).close();
    }

    private static boolean isLaneEnd(Object message) {
        return message instanceof io.netty.handler.codec.quic.QuicStreamFrame frame
               && frame.hasFin()
               && frame.content().readableBytes() == 4
               && frame.content().getInt(frame.content().readerIndex()) == 0;
    }
}
