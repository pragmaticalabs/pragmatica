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

import io.netty.channel.DefaultChannelPromise;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterFormationConfig;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// Verifies the two follower<->follower zombie-link eviction paths added to
/// [`QuicClusterNetwork`]:
///
///   - FIX A: the periodic liveness sweep at the top of the missing-peer reconciler tick demotes
///     a CONNECTED [PeerState] whose underlying connection reports `!isActive()` to EVICTED, so
///     the same tick re-dials it.
///   - FIX C: the event-driven close listener evicts a peer the instant its QUIC channel closes,
///     but only when the closed connection is STILL the bound one (adopt-newer supersede must not
///     evict the live replacement).
@Timeout(10)
class QuicClusterNetworkLivenessSweepTest {
    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(5).seconds();
    private static final TimeSpan PING_INTERVAL = TimeSpan.timeSpan(1).seconds();
    private static final TimeSpan HELLO_TIMEOUT = TimeSpan.timeSpan(5).seconds();

    private SliceCodec codec;
    private QuicSslContext serverSsl;
    private QuicSslContext clientSsl;
    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        codec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), combinedCodecs());
        serverSsl = QuicTlsProvider.serverContext(TlsConfig.selfSignedServer())
                                    .fold(_ -> fail("Server SSL failed"), ssl -> ssl);
        clientSsl = QuicTlsProvider.clientContext(TlsConfig.insecureClient())
                                    .fold(_ -> fail("Client SSL failed"), ssl -> ssl);
    }

    @AfterEach
    void tearDown() {
        for (var network : networks) {
            network.stop().await(AWAIT_TIMEOUT);
        }
        networks.clear();
    }

    // --- FIX A: liveness sweep in reconciler tick ---

    @Test
    void reconcileMissingPeersTick_connectedButInactiveLink_isEvicted() {
        // A follower<->follower zombie: phase==CONNECTED but the underlying channel is dead.
        // Nothing writes to it periodically, so the write-path evictor never fires. The
        // reconciler's liveness sweep must demote it so it drops out of connectedPeers().
        var network = createNetwork(NodeId.randomNodeId());
        var peerId = new NodeId("zombie-peer");
        var chan = inactiveChannel();
        network.seedPeerForTests(peerId, connectedPeerState(peerId, chan));

        assertThat(network.connectedPeers())
            .as("precondition: the dead-but-CONNECTED peer is initially counted as connected")
            .contains(peerId);

        network.reconcileMissingPeersTick();

        assertThat(network.connectedPeers())
            .as("liveness sweep must evict the CONNECTED-but-inactive zombie so it is no longer connected")
            .doesNotContain(peerId);
    }

    @Test
    void reconcileMissingPeersTick_connectedAndActiveLink_isNotEvicted() {
        // A healthy CONNECTED peer with a live channel must survive the sweep untouched.
        var network = createNetwork(NodeId.randomNodeId());
        var peerId = new NodeId("healthy-peer");
        network.seedPeerForTests(peerId, connectedPeerState(peerId, activeChannel()));

        network.reconcileMissingPeersTick();

        assertThat(network.connectedPeers())
            .as("a live CONNECTED link must not be evicted by the liveness sweep")
            .contains(peerId);
    }

    // --- FIX B: last-inbound TTL eviction for CONNECTED zombies (isActive()-lies-true) ---

    @Test
    void reconcileMissingPeersTick_connectedActiveButStaleInbound_isEvicted() {
        // Flavor-2 zombie: the channel reports isActive()==true (QUIC idle timeout disabled, the
        // partition-orphaned link never learned its peer died), but NO inbound frame has arrived on
        // any lane for far longer than the liveness TTL (pingInterval * 8 = 8s). The TTL sweep must
        // evict it for re-dial.
        var network = createNetwork(NodeId.randomNodeId());
        var peerId = new NodeId("silent-zombie");
        network.seedPeerForTests(peerId, staleInboundPeerState(peerId, activeChannel()));

        assertThat(network.connectedPeers())
            .as("precondition: the silent-but-CONNECTED+active peer is initially counted as connected")
            .contains(peerId);

        network.reconcileMissingPeersTick();

        assertThat(network.connectedPeers())
            .as("the inbound-TTL sweep must evict a CONNECTED+active link gone silent past the TTL")
            .doesNotContain(peerId);
    }

    @Test
    void reconcileMissingPeersTick_connectedActiveAndRecentInbound_isNotEvicted() {
        // A CONNECTED+active link that received an inbound frame within the TTL is healthy and must
        // survive the sweep untouched — a quiet-but-probed SWIM link is never falsely evicted.
        var network = createNetwork(NodeId.randomNodeId());
        var peerId = new NodeId("healthy-recent-inbound");
        var state = connectedPeerState(peerId, activeChannel());
        state.markInbound(System.nanoTime());
        network.seedPeerForTests(peerId, state);

        network.reconcileMissingPeersTick();

        assertThat(network.connectedPeers())
            .as("a CONNECTED+active link with recent inbound must not be evicted by the TTL sweep")
            .contains(peerId);
    }

    @Test
    void onMessageReceived_whileBlackholed_doesNotRefreshInbound_soSweepStillTrips() {
        // The chaos substrate (blackhole) keeps the QUIC channel CONNECTED+active but silently drops
        // ALL inbound frames before they reach the liveness funnel. A blackholed inbound therefore
        // must NOT refresh the liveness clock, so a stale-inbound CONNECTED+active peer is still
        // evicted by the TTL sweep even though frames are notionally arriving off the wire.
        var network = createNetwork(NodeId.randomNodeId());
        var peerId = new NodeId("blackholed-zombie");
        network.seedPeerForTests(peerId, staleInboundPeerState(peerId, activeChannel()));
        network.blackhole(true);

        // Simulate inbound traffic while blackholed — these must be dropped before markInbound.
        network.onMessageReceivedForTests(peerId, "ignored-frame-1");
        network.onMessageReceivedForTests(peerId, "ignored-frame-2");
        network.blackhole(false);

        network.reconcileMissingPeersTick();

        assertThat(network.connectedPeers())
            .as("blackholed inbound must not count as liveness — the TTL sweep still trips on the zombie")
            .doesNotContain(peerId);
    }

    // --- FIX C: event-driven close listener ---

    @Test
    void onChannelClosed_boundConnection_evictsPeer() {
        // Genuine channel close on the still-bound connection: the listener must evict the peer
        // immediately so the reconciler re-dials, independent of any outbound write.
        var network = createNetwork(NodeId.randomNodeId());
        var peerId = new NodeId("closing-peer");
        var chan = activeChannel();
        var closeFuture = new DefaultChannelPromise(chan, ImmediateEventExecutor.INSTANCE);
        when(chan.closeFuture()).thenReturn(closeFuture);

        var connection = QuicPeerConnection.quicPeerConnection(peerId, chan);
        network.seedPeerForTests(peerId, connectedPeerStateWith(peerId, connection));
        network.registerCloseListenerForTests(peerId, connection);

        assertThat(network.connectedPeers())
            .as("precondition: bound peer is connected before its channel closes")
            .contains(peerId);

        closeFuture.setSuccess();

        assertThat(network.connectedPeers())
            .as("event-driven close of the bound connection must evict the peer")
            .doesNotContain(peerId);
    }

    @Test
    void onChannelClosed_supersededConnection_doesNotEvictLiveReplacement() {
        // adopt-newer: a fresh handshake superseded the old connection (binding a DIFFERENT
        // QuicPeerConnection object). When the OLD connection's channel later closes, the
        // listener must NOT evict — the live replacement is still bound (current != closed).
        var network = createNetwork(NodeId.randomNodeId());
        var peerId = new NodeId("superseded-peer");

        var oldChan = activeChannel();
        var oldCloseFuture = new DefaultChannelPromise(oldChan, ImmediateEventExecutor.INSTANCE);
        when(oldChan.closeFuture()).thenReturn(oldCloseFuture);
        var oldConnection = QuicPeerConnection.quicPeerConnection(peerId, oldChan);

        // The peer is currently bound to a DIFFERENT (live) connection — the replacement.
        var liveConnection = QuicPeerConnection.quicPeerConnection(peerId, activeChannel());
        network.seedPeerForTests(peerId, connectedPeerStateWith(peerId, liveConnection));

        // The old (superseded) connection still has its close listener registered.
        network.registerCloseListenerForTests(peerId, oldConnection);

        oldCloseFuture.setSuccess();

        assertThat(network.connectedPeers())
            .as("a superseded connection's close must NOT evict the live replacement")
            .contains(peerId);
    }

    // --- helpers ---

    private static QuicChannel activeChannel() {
        var chan = mock(QuicChannel.class);
        lenient().when(chan.isActive()).thenReturn(true);
        return chan;
    }

    private static QuicChannel inactiveChannel() {
        var chan = mock(QuicChannel.class);
        lenient().when(chan.isActive()).thenReturn(false);
        return chan;
    }

    /// CONNECTED PeerState backed by the supplied channel. Stamped in the past so it sits
    /// outside any protection window. The channel's `isActive()` controls the sweep verdict.
    private static PeerState connectedPeerState(NodeId peerId, QuicChannel chan) {
        return connectedPeerStateWith(peerId, QuicPeerConnection.quicPeerConnection(peerId, chan));
    }

    private static PeerState connectedPeerStateWith(NodeId peerId, QuicPeerConnection connection) {
        var past = System.nanoTime() - Duration.ofMinutes(1).toNanos();
        var state = PeerState.peerState(peerId, past);
        state.beginConnecting(past);
        state.attach(connection, past);
        // Refresh the inbound liveness clock to "now" so a live link is not falsely tripped by the
        // inbound-TTL sweep; the inactive-channel zombie tests rely on isActive()==false instead.
        state.markInbound(System.nanoTime());
        return state;
    }

    /// CONNECTED PeerState backed by the supplied (active) channel whose inbound liveness clock is
    /// stamped 1 minute in the past — far beyond the 8s liveness TTL — so the inbound-TTL sweep trips.
    private static PeerState staleInboundPeerState(NodeId peerId, QuicChannel chan) {
        var past = System.nanoTime() - Duration.ofMinutes(1).toNanos();
        var state = PeerState.peerState(peerId, past);
        state.beginConnecting(past);
        state.attach(QuicPeerConnection.quicPeerConnection(peerId, chan), past);
        // Deliberately do NOT call markInbound — leave lastInboundAtNanos at the past attach stamp.
        return state;
    }

    private QuicClusterNetwork createNetwork(NodeId nodeId) {
        var address = NodeAddress.nodeAddress("127.0.0.1", 19999).fold(_ -> fail("bad address"), a -> a);
        var selfInfo = NodeInfo.nodeInfo(nodeId, address);
        var topology = stubTopologyManager(selfInfo);
        var network = new QuicClusterNetwork(topology, codec, codec, MessageRouter.mutable(), serverSsl, clientSsl,
                                              ClusterFormationConfig.defaults(), QuicDisconnectListener.noop());
        networks.add(network);
        network.startOnPort(0).await(AWAIT_TIMEOUT).onFailure(cause -> fail("start failed: " + cause.message()));
        return network;
    }

    private TopologyObserver stubTopologyManager(NodeInfo self) {
        return new TopologyObserver() {
            @Override public NodeInfo self() {return self;}
            @Override public Option<NodeInfo> get(NodeId id) {
                return id.equals(self.id()) ? Option.some(self) : Option.empty();
            }
            @Override public int clusterSize() {return 1;}
            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}
            @Override public Promise<Unit> start() {return Promise.unitPromise();}
            @Override public Promise<Unit> stop() {return Promise.unitPromise();}
            @Override public TimeSpan pingInterval() {return PING_INTERVAL;}
            @Override public TimeSpan helloTimeout() {return HELLO_TIMEOUT;}
            @Override public Option<TlsConfig> tls() {return Option.empty();}
            @Override public Option<NodeState> getState(NodeId id) {return Option.empty();}
            @Override public List<NodeId> topology() {return List.of(self.id());}
            @Override public void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList) {}
            @Override public void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes) {}
            @Override public void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes) {}
            @Override public void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message) {}
        };
    }

    private static List<SliceCodec.TypeCodec<?>> combinedCodecs() {
        var all = new ArrayList<SliceCodec.TypeCodec<?>>();
        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);
        return all;
    }
}
