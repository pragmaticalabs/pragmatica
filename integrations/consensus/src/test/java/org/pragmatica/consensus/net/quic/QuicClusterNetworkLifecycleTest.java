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

import java.net.InetAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.fail;

/// Start/stop lifecycle of [QuicClusterNetwork].
///
/// #1366 — `stop()` nulls `client`, and the dial path dereferenced the field twice: at the resolve step and in
/// the resolve-success continuation, which a stop can overtake. After a stop both paths threw an NPE (surfaced
/// by #1311's escape guard). Each is now a clean no-op that leaves no CONNECTING peer behind.
///
/// #1461 — `stop()` before `start()` was a silent no-op (its CAS on `isRunning` failed and nothing was recorded),
/// so the later start armed a server, a reconciler and a keepalive that nothing could stop. A stop is now
/// recorded and a stopped network stays stopped: the late start refuses with `NETWORK_STOPPED`.
@Timeout(60)
class QuicClusterNetworkLifecycleTest {
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(20).seconds();
    private static final NodeId SELF = new NodeId("lifecycle-self");
    private static final NodeId PEER = new NodeId("lifecycle-peer");

    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(AWAIT));
    }

    /// #1366, resolve step. Mutation that reddens it: dereference the `client` field instead of the local.
    @Test
    void dialAfterStop_isANoOp_notAnNpe() {
        var network = started();

        network.stop().await(AWAIT).onFailure(cause -> fail("stop failed: " + cause.message()));

        assertThatCode(() -> network.dialForTests(peer(), true)).as("#1366: a dial after stop must not dereference null")
                                                               .doesNotThrowAnyException();
        assertThat(network.peerPhaseForTests(PEER)).as("nothing was dialed").isEqualTo(Option.empty());
    }

    /// #1366, resolve-success continuation: a resolve that completes after stop(). Mutation that reddens it:
    /// dereference the `client` field in `dialResolved`.
    @Test
    void resolveCompletingAfterStop_dialsNothing_andLeavesNoConnectingPeer() throws Exception {
        var network = started();

        network.stop().await(AWAIT).onFailure(cause -> fail("stop failed: " + cause.message()));

        assertThatCode(() -> network.dialResolvedForTests(peer(), InetAddress.getLoopbackAddress(), 1))
            .as("#1366: the continuation must not dereference null")
            .doesNotThrowAnyException();
        assertThat(network.peerPhaseForTests(PEER)).as("no peer is left CONNECTING by a dial that could not start").isEqualTo(Option.empty());
    }

    /// CONTROL — the same seams on a RUNNING network do dial: the peer goes CONNECTING. Without this, the empty
    /// phases above could mean the seams never reach the dial at all.
    @Test
    void resolveCompletingWhileRunning_beginsConnecting() {
        var network = started();

        network.dialResolvedForTests(peer(), InetAddress.getLoopbackAddress(), 1);

        assertThat(network.peerPhaseForTests(PEER)).as("control: a running network dials")
                                                   .isEqualTo(Option.some(PeerState.Phase.CONNECTING));
    }

    /// #1461. Mutation that reddens it: drop the `closed` check at the top of `startOnPort`.
    @Test
    void stopBeforeStart_theLateStartRefuses_andArmsNothing() {
        var network = network();
        var ready = new java.util.concurrent.atomic.AtomicBoolean();

        network.whenReady(() -> ready.set(true));
        network.stop().await(AWAIT).onFailure(cause -> fail("stop failed: " + cause.message()));

        var start = network.startOnPort(0).await(AWAIT);

        assertThat(start.isFailure()).as("#1461: a start after stop must refuse: %s", start).isTrue();
        start.onFailure(cause -> assertThat(cause).isEqualTo(QuicTransportError.General.NETWORK_STOPPED));
        assertThat(network.boundPort()).as("no server was bound").isEqualTo(Option.empty());
        assertThat(network.periodicTasksScheduledForTests()).as("no reconciler or keepalive was armed").isFalse();
        assertThat(ready.get()).as("the transport never reported ready").isFalse();
    }

    /// The same holds for a network that ran and was stopped: stopped stays stopped.
    @Test
    void stopAfterStart_thenStartAgain_refuses() {
        var network = started();

        network.stop().await(AWAIT).onFailure(cause -> fail("stop failed: " + cause.message()));

        assertThat(network.startOnPort(0).await(AWAIT).isFailure()).isTrue();
        assertThat(network.periodicTasksScheduledForTests()).isFalse();
    }

    /// CONTROL — a start with no prior stop binds and arms its periodic tasks, so the negatives above are real.
    @Test
    void startWithoutStop_bindsAndArmsItsPeriodicTasks() {
        var network = started();

        assertThat(network.boundPort().isPresent()).isTrue();
        assertThat(network.periodicTasksScheduledForTests()).isTrue();
    }

    private QuicClusterNetwork started() {
        var network = network();

        network.startOnPort(0).await(AWAIT).onFailure(cause -> fail("start failed: " + cause.message()));

        return network;
    }

    private QuicClusterNetwork network() {
        var codec = LaneProbe.codec();
        var self = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 19995).fold(_ -> fail("bad address"), a -> a));
        var serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("lifecycle-server"))
                                       .fold(_ -> fail("server ssl"), ssl -> ssl);
        var clientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("lifecycle-client"))
                                       .fold(_ -> fail("client ssl"), ssl -> ssl);
        var network = new QuicClusterNetwork(stubTopology(self), codec, codec, MessageRouter.mutable(), serverSsl, clientSsl);

        networks.add(network);

        return network;
    }

    private static NodeInfo peer() {
        return NodeInfo.nodeInfo(PEER, NodeAddress.nodeAddress("127.0.0.1", 1).fold(_ -> fail("bad address"), a -> a));
    }

    private static TopologyObserver stubTopology(NodeInfo self) {
        return new TopologyObserver() {
            @Override public Unit setConsensusMembership(Predicate<NodeId> membership) {return Unit.unit();}
            @Override public NodeInfo self() {return self;}
            @Override public Option<NodeInfo> get(NodeId id) {return id.equals(self.id()) ? Option.some(self) : Option.empty();}
            @Override public int clusterSize() {return 3;}
            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}
            @Override public Promise<Unit> start() {return Promise.unitPromise();}
            @Override public Promise<Unit> stop() {return Promise.unitPromise();}
            @Override public TimeSpan pingInterval() {return TimeSpan.timeSpan(30).seconds();}
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
