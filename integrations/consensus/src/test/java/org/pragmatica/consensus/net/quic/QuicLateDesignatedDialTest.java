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

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
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
import org.pragmatica.messaging.StreamType;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1578 — the la-5 shape from #1554's skewed cold start. The designated dialer (the LOWER id) dials a peer
/// that is not listening yet; the peer starts and dials back, so the pair is CONNECTED on the peer's link;
/// then the designated dial, retransmitted until the peer listens, would complete LATE (H10: "a late completion
/// still attaches") and supersede the live lane at both ends — a RECONNECT, and a second full handshake per
/// pair (#1554 measured 28 handshakes where 20 were needed).
///
/// Wanted: a late dial to a peer already CONNECTED never supersedes the live lane, and every lane still carries
/// traffic both ways afterwards. Mechanism (#1578 B2): the attach abandons our own dial while its QUIC handshake
/// is incomplete, so it never completes. Armed: exactly one dial was abandoned at the lower id — its own dial
/// was still pending when the peer's link attached — and each end attached exactly one connection.
@Timeout(90)
class QuicLateDesignatedDialTest {
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(30).seconds();
    private static final TimeSpan PING_INTERVAL = TimeSpan.timeSpan(30).seconds();
    /// How long the peer stays down after the designated dial starts; #1554's la-5 booted 3 s late.
    private static final long PEER_START_DELAY_MS = 2_000L;
    /// Long enough for a late completion to land, were the abandoned dial still alive.
    private static final long SETTLE_MS = 2_000L;
    private static final List<String> SUPERSEDING_CAUSES = List.of(PeerState.CAUSE_ATTACH_SUPERSEDE,
                                                                   PeerState.CAUSE_ATTACH_STALE_REPLACE);

    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
    }

    @Test
    void designatedDialCompletingAfterThePeerDialedBack_neverSupersedesTheLiveLane() {
        var low = new NodeId("ld-a");
        var high = new NodeId("ld-b");
        var highPort = freeUdpPort();
        var lowJournal = new CopyOnWriteArrayList<PeerTransitionRecord>();
        var highJournal = new CopyOnWriteArrayList<PeerTransitionRecord>();
        var toLow = new CopyOnWriteArrayList<LaneProbe>();
        var toHigh = new CopyOnWriteArrayList<LaneProbe>();
        var lowNet = network(low, toLow, lowJournal);
        var highNet = network(high, toHigh, highJournal);

        start(lowNet, 0);
        lowNet.dialForTests(nodeInfo(high, highPort), false);
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(PEER_START_DELAY_MS));
        start(highNet, highPort);
        highNet.dialForTests(nodeInfo(low, lowNet.boundPort().unwrap()), true);

        awaitTrue(() -> connected(lowNet, high) && connected(highNet, low), "both ends CONNECTED");
        awaitTrue(() -> lowNet.quicMetrics().dialAbandonedCount() == 1,
                  "arming: the lower id's own dial was still pending when the peer's link attached, and was abandoned");
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(SETTLE_MS));

        Arrays.stream(StreamType.values())
              .forEach(lane -> sendProbes(lane, lowNet, low, highNet, high));
        awaitTrue(() -> missing(toHigh, "low-to-high-").isEmpty(), "low -> high on every lane: missing " + missing(toHigh, "low-to-high-"));
        awaitTrue(() -> missing(toLow, "high-to-low-").isEmpty(), "high -> low on every lane: missing " + missing(toLow, "high-to-low-"));

        assertThat(causes(lowJournal)).as("the late dial never supersedes the live lane at the lower id")
                                      .doesNotContainAnyElementsOf(SUPERSEDING_CAUSES);
        assertThat(causes(highJournal)).as("... nor at the peer")
                                       .doesNotContainAnyElementsOf(SUPERSEDING_CAUSES);
        assertThat(lowNet.quicMetrics().handshakeTotalCount()).as("one connection attached at the lower id").isEqualTo(1);
        assertThat(highNet.quicMetrics().handshakeTotalCount()).as("... and at the peer").isEqualTo(1);
    }

    private static void sendProbes(StreamType lane, QuicClusterNetwork lowNet, NodeId low, QuicClusterNetwork highNet, NodeId high) {
        lowNet.send(high, LaneProbe.laneProbe(low, lane, "low-to-high-" + lane));
        highNet.send(low, LaneProbe.laneProbe(high, lane, "high-to-low-" + lane));
    }

    private static List<StreamType> missing(List<LaneProbe> received, String prefix) {
        return Arrays.stream(StreamType.values())
                     .filter(Predicate.not(lane -> received.stream()
                                                           .anyMatch(probe -> probe.marker().equals(prefix + lane))))
                     .toList();
    }

    private static List<String> causes(List<PeerTransitionRecord> journal) {
        return journal.stream()
                      .map(PeerTransitionRecord::cause)
                      .toList();
    }

    private static boolean connected(QuicClusterNetwork network, NodeId peer) {
        return network.peerPhaseForTests(peer)
                      .filter(phase -> phase == PeerState.Phase.CONNECTED)
                      .isPresent();
    }

    private static NodeInfo nodeInfo(NodeId id, int port) {
        return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("127.0.0.1", port).fold(_ -> fail("bad address"), a -> a));
    }

    private static void start(QuicClusterNetwork network, int port) {
        network.startOnPort(port).await(AWAIT).onFailure(cause -> fail("start failed: " + cause.message()));
    }

    private QuicClusterNetwork network(NodeId id, List<LaneProbe> sink, List<PeerTransitionRecord> journal) {
        var codec = LaneProbe.codec();
        var self = NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("127.0.0.1", 19993).fold(_ -> fail("bad address"), a -> a));
        var router = MessageRouter.mutable();
        var serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server"))
                                       .fold(_ -> fail("server ssl"), ssl -> ssl);
        var clientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client"))
                                       .fold(_ -> fail("client ssl"), ssl -> ssl);

        router.addRoute(LaneProbe.class, sink::add);
        var network = new QuicClusterNetwork(stubTopology(self), codec, codec, router, serverSsl, clientSsl);

        network.setPeerTransitionListener(journal::add);
        networks.add(network);

        return network;
    }

    private static int freeUdpPort() {
        try (var socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            return socket.getLocalPort();
        } catch (IOException e) {
            return fail("no free UDP port: " + e.getMessage());
        }
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        var deadline = System.nanoTime() + AWAIT.nanos();

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        fail("Timed out waiting for: " + what);
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
