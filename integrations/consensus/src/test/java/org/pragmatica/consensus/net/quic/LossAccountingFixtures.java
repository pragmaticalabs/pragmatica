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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamChannelConfig;
import io.netty.util.Attribute;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterFormationConfig;
import org.pragmatica.consensus.net.quic.TransportTripwireFixtures.TripwireTopology;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.slf4j.LoggerFactory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.clientSsl;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.serverSsl;

/// Fixtures of the #2583 P0 loss-accounting tests: an unstarted [QuicClusterNetwork] (no sockets) with a short consensus
/// retry budget, and mock lane streams. Nothing here changes behaviour.
final class LossAccountingFixtures {
    static final NodeId SELF = new NodeId("zzz-self");
    static final NodeId PEER = new NodeId("aaa-peer");
    static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();

    private LossAccountingFixtures() {}

    /// Three attempts, `interval` apart: a retry gives up after about two intervals.
    static QuicClusterNetwork network(TimeSpan interval) {
        return network(LaneProbe.codec(), interval);
    }

    /// `attempts` attempts, `interval` apart: a retry that is never released stays in flight for about `attempts * interval`.
    static QuicClusterNetwork network(int attempts, TimeSpan interval) {
        return network(LaneProbe.codec(), attempts, interval);
    }

    /// The held-frame budget of [#networkWithoutGiveUp], far past every test's `@Timeout`.
    static final TimeSpan NO_GIVE_UP_BUDGET = TimeSpan.timeSpan(1).hours();

    /// A network whose held frame is never given up inside a test (#3686): the budget (attempts x `interval`) is an hour.
    /// For every test whose subject is not the give-up; a test whose subject is the give-up names its own short budget.
    static QuicClusterNetwork networkWithoutGiveUp(TimeSpan interval) {
        return network((int) (NO_GIVE_UP_BUDGET.nanos() / interval.nanos()), interval);
    }

    /// The race of #3686 made certain: the calling thread stalls for `stall` (a scheduler stall; pass a multiple of the
    /// fixture's budget). A test whose assertion a give-up can undo fails here on a fixture that gives up, on any host.
    static void stallFor(TimeSpan stall) {
        var until = System.nanoTime() + stall.nanos();

        for (var left = until - System.nanoTime(); left > 0; left = until - System.nanoTime()) {
            LockSupport.parkNanos(left);
        }
    }

    static QuicClusterNetwork network(Serializer serializer, TimeSpan interval) {
        return network(serializer, 3, interval);
    }

    private static QuicClusterNetwork network(Serializer serializer, int attempts, TimeSpan interval) {
        var topology = new TripwireTopology(SELF, false, TimeSpan.timeSpan(30).seconds());
        var tuning = new QuicTransportTuning(attempts,
                                             interval,
                                             QuicTransportTuning.DEFAULT_WATERMARK_LOW_BYTES,
                                             QuicTransportTuning.DEFAULT_WATERMARK_HIGH_BYTES);

        return new QuicClusterNetwork(topology,
                                      serializer,
                                      LaneProbe.codec(),
                                      MessageRouter.mutable(),
                                      serverSsl("loss-server"),
                                      clientSsl("loss-client"),
                                      ClusterFormationConfig.defaults(),
                                      PeerConnectivityReporter.noop(),
                                      QuicClusterNetwork.ObservedEpochSupplier.zero(),
                                      tuning);
    }

    /// A [Serializer] that delegates to the probe codec and counts every [Serializer#encode] call.
    static Serializer countingSerializer(AtomicInteger encodes) {
        var delegate = LaneProbe.codec();

        return new Serializer() {
            @Override
            public <T> byte[] encode(T object) {
                encodes.incrementAndGet();

                return delegate.encode(object);
            }

            @Override
            public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {
                delegate.write(byteBuf, object);
            }
        };
    }

    static QuicStreamChannel stream(boolean writable) {
        var stream = mock(QuicStreamChannel.class);
        var future = mock(ChannelFuture.class);

        when(future.addListener(any())).thenReturn(future);
        when(stream.writeAndFlush(any())).thenReturn(future);
        when(stream.isActive()).thenReturn(true);
        when(stream.isWritable()).thenReturn(writable);
        when(stream.config()).thenReturn(mock(QuicStreamChannelConfig.class));

        return stream;
    }

    /// #3313: fires `stream`'s writability event through a data handler built the way every production construction
    /// builds one ([PeerOpenedLaneRouter#dataHandler]), with the settler `network` gives its server and client. The event
    /// is the only thing that runs the closer here: no write is made.
    static void fireWritabilityEvent(QuicClusterNetwork network, QuicStreamChannel stream) throws Exception {
        productionDataHandler(network).channelWritabilityChanged(contextOf(stream));
    }

    /// #3313 T-d: fires `stream`'s inactive event (a reset, or the stream closed while its connection lives) through a
    /// data handler built the way every production construction builds one, with the settler `network` gives its server
    /// and client. netty-quic fires no writability event when a stream closes, so this event is the only thing that runs
    /// the closer here: no write is made, no connection closes, no peer departs. The stream's connection carries no
    /// bound peer connection, so the handler's #1578 lane release finds nothing to release.
    static void fireStreamInactive(QuicClusterNetwork network, QuicStreamChannel stream) throws Exception {
        var connection = mock(QuicChannel.class);
        Attribute<QuicPeerConnection> unbound = mock();

        when(connection.attr(PeerOpenedLaneRouter.PEER_CONNECTION)).thenReturn(unbound);
        when(stream.parent()).thenReturn(connection);
        productionDataHandler(network).channelInactive(contextOf(stream));
    }

    private static QuicLaneDataHandler productionDataHandler(QuicClusterNetwork network) {
        var router = new PeerOpenedLaneRouter(mock(Deserializer.class),
                                              QuicTransportMetrics.quicTransportMetrics(),
                                              (_, _) -> {},
                                              LoggerFactory.getLogger(LossAccountingFixtures.class),
                                              network.backpressureSettler());

        return router.dataHandler(PEER, StreamType.CONSENSUS);
    }

    private static ChannelHandlerContext contextOf(QuicStreamChannel stream) {
        var ctx = mock(ChannelHandlerContext.class);

        when(ctx.channel()).thenReturn(stream);

        return ctx;
    }

    static QuicPeerConnection connection(NodeId peerId, QuicPeerConnection.LaneOpener opener) {
        var channel = mock(QuicChannel.class);

        when(channel.isActive()).thenReturn(true);

        var connection = QuicPeerConnection.quicPeerConnection(peerId, channel);

        connection.laneOpener(opener);

        return connection;
    }

    /// CONNECTED, stamped in the past so no protection window applies.
    static PeerState connectedState(NodeId peerId, QuicPeerConnection connection) {
        var past = System.nanoTime() - TimeSpan.timeSpan(60).seconds().nanos();
        var state = PeerState.peerState(peerId, past);

        state.beginConnecting(past);
        state.attach(connection, past);
        state.markInbound(System.nanoTime());

        return state;
    }
}
