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

import java.net.SocketAddress;
import java.util.ArrayList;
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
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Ghost dial (cloud run 1, s29 M1a): a dial aimed at a peer whose address has since been recycled to
/// ANOTHER node must not attach to, or supersede a healthy link of, whichever node now answers there.
///
/// Shape: B (the higher id) dialed A, so the pair is CONNECTED on B's link. Then A dials "C" at B's
/// address, as A does for a dead peer whose IP Hetzner re-issued to B. The dialer rejects the identity
/// mismatch, but the ACCEPTOR used to have attached A by then, and an inbound link initiated by the lower
/// id displaces an incumbent initiated by the higher one: the healthy A-B link was torn down on every ghost
/// dial. Wanted: B refuses the Hello (it names C, not B) before registering, so B's connection to A is the
/// very same object afterwards and B attached nothing new.
///
/// No traffic flows between A and B (ping interval 30s), so the incumbent is receipt-silent and the
/// cross-direction age/receipt floor of `PeerState` does not mask the acceptor check under test.
@Timeout(90)
class QuicMisdirectedDialTest {
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(30).seconds();
    private static final TimeSpan PING_INTERVAL = TimeSpan.timeSpan(30).seconds();
    /// After the ghost dial has FAILED at the dialer, the acceptor's (wrong) attach — which precedes the
    /// dialer's verdict on the wire — has long since happened; this only absorbs scheduling noise.
    private static final long SETTLE_MS = 1_000L;
    private static final List<String> SUPERSEDING_CAUSES = List.of(PeerState.CAUSE_ATTACH_SUPERSEDE,
                                                                   PeerState.CAUSE_ATTACH_STALE_REPLACE);

    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
    }

    @Test
    void dialAimedAtAnotherPeer_neverSupersedesTheHealthyLinkAtTheAnsweringNode() {
        var a = new NodeId("md-a");
        var b = new NodeId("md-b");
        var ghost = new NodeId("md-c");
        var aJournal = new CopyOnWriteArrayList<PeerTransitionRecord>();
        var bJournal = new CopyOnWriteArrayList<PeerTransitionRecord>();
        var aNet = network(a, aJournal);
        var bNet = network(b, bJournal);

        start(aNet);
        start(bNet);
        bNet.dialForTests(nodeInfo(a, aNet.boundPort().unwrap()), true);
        awaitTrue(() -> connected(aNet, b) && connected(bNet, a), "A and B CONNECTED on B's link");

        var bLinkToA = bNet.activeConnectionForTests(a).unwrap();
        var aLinkToB = aNet.activeConnectionForTests(b).unwrap();
        var bAttachedBefore = bNet.quicMetrics().handshakeTotalCount();
        var aFailuresBefore = aNet.quicMetrics().handshakeFailureCount();

        // A dials the recycled address: it names `ghost`, B answers.
        aNet.dialForTests(nodeInfo(ghost, bNet.boundPort().unwrap()), true);
        awaitTrue(() -> aNet.quicMetrics().handshakeFailureCount() > aFailuresBefore,
                  "arming: the ghost dial failed at the dialer");
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(SETTLE_MS));

        assertThat(bNet.activeConnectionForTests(a).unwrap())
            .as("B's connection to A is the same object: the ghost dial displaced nothing")
            .isSameAs(bLinkToA);
        assertThat(aNet.activeConnectionForTests(b).unwrap())
            .as("... and A's connection to B")
            .isSameAs(aLinkToB);
        assertThat(causes(bJournal)).as("no supersede at the answering node")
                                    .doesNotContainAnyElementsOf(SUPERSEDING_CAUSES);
        assertThat(bNet.quicMetrics().handshakeTotalCount())
            .as("the answering node attached nothing on the misdirected dial")
            .isEqualTo(bAttachedBefore);
        assertThat(connected(bNet, a)).as("B still has A CONNECTED").isTrue();
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

    private static void start(QuicClusterNetwork network) {
        network.startOnPort(0).await(AWAIT).onFailure(cause -> fail("start failed: " + cause.message()));
    }

    private QuicClusterNetwork network(NodeId id, List<PeerTransitionRecord> journal) {
        var codec = LaneProbe.codec();
        var self = NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("127.0.0.1", 19994).fold(_ -> fail("bad address"), a -> a));
        var router = MessageRouter.mutable();
        var serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server"))
                                       .fold(_ -> fail("server ssl"), ssl -> ssl);
        var clientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client"))
                                       .fold(_ -> fail("client ssl"), ssl -> ssl);
        var network = new QuicClusterNetwork(stubTopology(self), codec, codec, router, serverSsl, clientSsl);

        network.setPeerTransitionListener(journal::add);
        networks.add(network);

        return network;
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
