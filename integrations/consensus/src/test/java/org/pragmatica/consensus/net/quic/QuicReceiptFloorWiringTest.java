package org.pragmatica.consensus.net.quic;

import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import io.netty.handler.codec.quic.QuicChannel;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// The receipt floor `QuicClusterNetwork` wires into every `PeerState` is three keepalive intervals (minimum 3s), so a
/// healthy idle link is never judged silent by the cross-direction supersede rule. With a 10s ping interval the floor is
/// 30s: an incumbent heard 5s before a fresh handshake is still a working link. Mutations that redden it: the factor
/// set to 0 (floor falls to the 3s minimum), or `getOrCreatePeer` not passing the floor at all.
class QuicReceiptFloorWiringTest {
    private static final NodeId PEER = new NodeId("rf-b");
    private static final NodeId SELF = new NodeId("rf-a");
    private static final TimeSpan PING_INTERVAL = TimeSpan.timeSpan(10).seconds();

    @Test
    void networkWiresThreeKeepaliveIntervalsAsTheReceiptFloor() {
        var network = network();
        var state = network.peerStateForTests(PEER);
        var t0 = System.nanoTime();
        state.beginConnecting(t0);
        var incumbent = connection(SELF);
        state.attach(incumbent, t0);
        state.markInbound(t0 + TimeUnit.SECONDS.toNanos(5));

        var result = state.attach(connection(PEER), t0 + TimeUnit.SECONDS.toNanos(10));

        assertThat(result.result()).isEqualTo(PeerState.AttachResult.DUPLICATE);
        assertThat(state.activeConnection().or((QuicPeerConnection) null)).isSameAs(incumbent);
    }

    private static QuicPeerConnection connection(NodeId initiator) {
        var chan = mock(QuicChannel.class);
        when(chan.isActive()).thenReturn(true);
        return QuicPeerConnection.quicPeerConnection(PEER, initiator, chan);
    }

    private static QuicClusterNetwork network() {
        var codec = LaneProbe.codec();
        var self = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 19995).fold(_ -> fail("bad address"), a -> a));
        var serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server")).fold(_ -> fail("server ssl"), ssl -> ssl);
        var clientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client")).fold(_ -> fail("client ssl"), ssl -> ssl);

        return new QuicClusterNetwork(stubTopology(self), codec, codec, MessageRouter.mutable(), serverSsl, clientSsl);
    }

    private static TopologyObserver stubTopology(NodeInfo self) {
        return new TopologyObserver() {
            @Override public Unit setConsensusMembership(Predicate<NodeId> membership) {return Unit.unit();}
            @Override public NodeInfo self() {return self;}
            @Override public Option<NodeInfo> get(NodeId id) {return id.equals(self.id()) ? Option.some(self) : Option.empty();}
            @Override public int clusterSize() {return 2;}
            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}
            @Override public Promise<Unit> start() {return Promise.unitPromise();}
            @Override public Promise<Unit> stop() {return Promise.unitPromise();}
            @Override public TimeSpan pingInterval() {return PING_INTERVAL;}
            @Override public TimeSpan helloTimeout() {return TimeSpan.timeSpan(5).seconds();}
            @Override public Option<TlsConfig> tls() {return Option.empty();}
            @Override public Option<NodeState> getState(NodeId id) {return Option.empty();}
            @Override public List<NodeId> topology() {return List.of(self.id());}
            @Override public void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList) {}
            @Override public void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes) {}
            @Override public void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes) {}
            @Override public void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message) {}
        };
    }
}
