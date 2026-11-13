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

import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterFormationConfig;
import org.pragmatica.consensus.net.quic.TransportTripwireFixtures.TripwireTopology;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Serializer;

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

    static QuicClusterNetwork network(Serializer serializer, TimeSpan interval) {
        var topology = new TripwireTopology(SELF, false, TimeSpan.timeSpan(30).seconds());
        var tuning = new QuicTransportTuning(3,
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
                                      ObservedEpochSupplier.zero(),
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

        return stream;
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
