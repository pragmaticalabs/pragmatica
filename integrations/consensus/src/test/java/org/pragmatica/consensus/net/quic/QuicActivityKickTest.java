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

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.StreamType;

import io.netty.channel.ChannelFuture;
import io.netty.channel.DefaultEventLoop;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/// #1727 (M2) — the activity kick of a [QuicPeerConnection], in isolation (mock channel, real event loop).
/// The interval and window are shrunk (20 ms / 300 ms) so the timing assertions are cheap; the
/// production values are exercised by [#steadyWriteLoad_costsAboutTenKicksPerSecond].
class QuicActivityKickTest {
    private static final byte[] FRAME = {7, 7, 7};
    private static final long INTERVAL_MS = 20;
    private static final long WINDOW_MS = 300;

    private final DefaultEventLoop loop = new DefaultEventLoop();

    @AfterEach
    void tearDown() {
        loop.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    private QuicChannel channel() throws Exception {
        var channel = mock(QuicChannel.class);
        var closed = mock(ChannelFuture.class);

        lenient().when(channel.eventLoop()).thenReturn(loop);
        lenient().when(channel.isActive()).thenReturn(true);
        lenient().when(channel.close()).thenReturn(closed);
        lenient().when(closed.sync()).thenReturn(closed);

        return channel;
    }

    private static QuicStreamChannel controlStream() {
        var stream = mock(QuicStreamChannel.class);

        lenient().when(stream.isActive()).thenReturn(true);
        lenient().when(stream.isWritable()).thenReturn(true);
        lenient().when(stream.streamId()).thenReturn(0L);

        return stream;
    }

    private static QuicPeerConnection withKick(QuicChannel channel, QuicStreamChannel control, long intervalMs, long windowMs) {
        var connection = QuicPeerConnection.quicPeerConnection(new NodeId("kick-peer"), channel);
        BooleanSupplier notSuppressed = () -> false;

        connection.registerStream(StreamType.CONTROL, control);
        connection.activityKick(FRAME, notSuppressed, intervalMs, windowMs);

        return connection;
    }

    private static void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    /// An idle connection sends nothing: the kick is driven only by data writes.
    @Test
    void idleConnection_sendsZeroKicks() throws Exception {
        var control = controlStream();
        var connection = withKick(channel(), control, INTERVAL_MS, WINDOW_MS);

        sleep(3 * WINDOW_MS);

        assertThat(connection.activityKicksSent()).isZero();
        verify(control, never()).writeAndFlush(any());
    }

    /// After one data write the kick runs for the window and then stops by itself. The control half of
    /// the pair is [#continuousWrites_keepTheKickRunningPastTheWindow].
    @Test
    void afterOneDataWrite_kicksStopOnceTheIdleWindowPasses() throws Exception {
        var connection = withKick(channel(), controlStream(), INTERVAL_MS, WINDOW_MS);

        connection.noteLaneWrite();
        sleep(WINDOW_MS / 2);
        assertThat(connection.activityKicksSent()).as("kicking inside the window").isPositive();

        sleep(2 * WINDOW_MS);
        var settled = connection.activityKicksSent();

        sleep(WINDOW_MS);

        assertThat(connection.activityKicksSent()).as("no kick after the window closed").isEqualTo(settled);
        assertThat(settled).as("bounded by window / interval").isLessThanOrEqualTo(WINDOW_MS / INTERVAL_MS + 3);
    }

    /// The control for the stop test: a connection that keeps writing keeps being kicked past one window.
    @Test
    void continuousWrites_keepTheKickRunningPastTheWindow() throws Exception {
        var connection = withKick(channel(), controlStream(), INTERVAL_MS, WINDOW_MS);
        var end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(3 * WINDOW_MS);
        var atOneWindow = -1L;
        var start = System.nanoTime();

        while (System.nanoTime() < end) {
            connection.noteLaneWrite();
            if (atOneWindow < 0 && System.nanoTime() - start > TimeUnit.MILLISECONDS.toNanos(WINDOW_MS)) {
                atOneWindow = connection.activityKicksSent();
            }
            sleep(5);
        }

        assertThat(connection.activityKicksSent()).isGreaterThan(atOneWindow + 5);
    }

    /// A connection that is no peer's active connection (superseded, draining) still kicks: the kick is
    /// owned by the connection and needs nothing from the transport's keepalive.
    @Test
    void drainingConnection_notAttachedToAnyPeer_stillKicks() throws Exception {
        var control = controlStream();
        var connection = withKick(channel(), control, INTERVAL_MS, WINDOW_MS);

        connection.noteLaneWrite();
        sleep(WINDOW_MS / 2);

        assertThat(connection.activityKicksSent()).isPositive();
        verify(control, org.mockito.Mockito.atLeastOnce()).writeAndFlush(any());
    }

    /// Closing the connection ends the kick, even inside the window.
    @Test
    void close_stopsTheKick() throws Exception {
        var connection = withKick(channel(), controlStream(), INTERVAL_MS, 10 * WINDOW_MS);

        connection.noteLaneWrite();
        sleep(WINDOW_MS / 2);
        connection.close();
        sleep(INTERVAL_MS * 3);
        var atClose = connection.activityKicksSent();

        sleep(WINDOW_MS);

        assertThat(atClose).isPositive();
        assertThat(connection.activityKicksSent()).isEqualTo(atClose);
    }

    /// Cost check at the PRODUCTION interval and window: a writer hammering the connection at ~1000
    /// writes/s must cost about 10 kick frames/s, not one per write.
    @Test
    void steadyWriteLoad_costsAboutTenKicksPerSecond() throws Exception {
        var connection = withKick(channel(),
                                  controlStream(),
                                  QuicActivityKick.DEFAULT_INTERVAL_MS,
                                  QuicActivityKick.DEFAULT_WINDOW_MS);
        var seconds = 3;
        var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        var writes = 0L;

        while (System.nanoTime() < end) {
            connection.noteLaneWrite();
            writes++;
            sleep(1);
        }

        var perSecond = connection.activityKicksSent() / (double) seconds;

        System.out.println("KICK-COST writes=" + writes + " kicks=" + connection.activityKicksSent() + " kicks/s=" + perSecond);
        assertThat(perSecond).isBetween(7.0, 10.5);
    }
}
