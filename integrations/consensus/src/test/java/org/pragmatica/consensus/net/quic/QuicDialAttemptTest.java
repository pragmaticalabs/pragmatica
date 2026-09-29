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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
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

/// #1578 — what happens to the dialer's own attempts around a CONNECTED transition.
///
/// - B1: an attempt that outlives its per-attempt timeout is released (its socket closed), so after a long
///   outage only the newest attempt can connect: one connection per end, one lane set, nothing superseded.
/// - B2: when the peer goes CONNECTED over another link, our own attempt whose QUIC handshake is incomplete is
///   abandoned — in #1554's skewed cold start that is exactly one handshake per pair (20 for five cores).
/// - The safety line of B2: an attempt PAST its QUIC handshake is never abandoned, because its Hello may
///   already be with the peer, which may adopt it.
@Timeout(120)
class QuicDialAttemptTest {
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(30).seconds();
    private static final TimeSpan PING_INTERVAL = TimeSpan.timeSpan(30).seconds();
    /// Short Hello timeout for the long-outage case: connectTimeout = 3 × this, so an attempt times out in ~1 s.
    private static final TimeSpan SHORT_HELLO_TIMEOUT = TimeSpan.timeSpan(300).millis();
    private static final TimeSpan DEFAULT_HELLO_TIMEOUT = TimeSpan.timeSpan(5).seconds();
    private static final int OUTAGE_ATTEMPTS = 3;
    /// How long the late core stays down; below connectTimeout (15 s at the default Hello timeout), so each
    /// seed has ONE attempt pending — the normal la-5 shape. #1554's la-5 booted 3 s late.
    private static final long LATE_START_DELAY_MS = 2_000L;
    /// Long enough for a late completion to land, were a released or abandoned attempt still alive.
    private static final long SETTLE_MS = 3_000L;
    private static final int CORES = 5;
    private static final List<String> SUPERSEDING_CAUSES = List.of(PeerState.CAUSE_ATTACH_SUPERSEDE,
                                                                   PeerState.CAUSE_ATTACH_STALE_REPLACE);

    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
    }

    @Test
    void peerDownLongerThanTheConnectTimeout_onlyTheNewestAttemptConnects_oneLaneSetEachEnd() {
        var low = new NodeId("da-a");
        var high = new NodeId("da-b");
        var highPort = freeUdpPort();
        var lowNode = node(low, SHORT_HELLO_TIMEOUT, BootTokens.bootTokens(0L));
        var highNode = node(high, SHORT_HELLO_TIMEOUT, BootTokens.bootTokens(0L));

        start(lowNode.network(), 0);
        for (int attempt = 1; attempt <= OUTAGE_ATTEMPTS; attempt++) {
            lowNode.network().dialForTests(nodeInfo(high, highPort), false);
            awaitTrue(() -> phase(lowNode.network(), high) == PeerState.Phase.EVICTED,
                      "attempt " + attempt + " times out while the peer is down");
        }
        lowNode.network().dialForTests(nodeInfo(high, highPort), false);
        start(highNode.network(), highPort);

        awaitTrue(() -> connected(lowNode.network(), high) && connected(highNode.network(), low), "both ends CONNECTED");
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(SETTLE_MS));
        assertEveryLaneCarriesTrafficBothWays(lowNode, highNode);

        assertThat(lowNode.network().quicMetrics().handshakeTotalCount()).as("one connection attached at the dialer, however many attempts timed out")
                                                                          .isEqualTo(1);
        assertThat(highNode.network().quicMetrics().handshakeTotalCount()).as("... and at the peer").isEqualTo(1);
        assertThat(causes(highNode.journal())).as("no timed-out attempt completed late and replaced the live link")
                                              .doesNotContainAnyElementsOf(SUPERSEDING_CAUSES);
        assertThat(causes(lowNode.journal())).doesNotContainAnyElementsOf(SUPERSEDING_CAUSES);
    }

    @Test
    void attemptPastItsQuicHandshake_isNeverAbandoned_evenWhileItsHelloIsUnanswered() {
        var low = new NodeId("dh-a");
        var high = new NodeId("dh-b");
        var highPort = freeUdpPort();
        var helloHeld = new HeldAdmission(BootTokens.bootTokens(0L));
        var lowNode = node(low, DEFAULT_HELLO_TIMEOUT, BootTokens.bootTokens(0L));
        var highNode = node(high, DEFAULT_HELLO_TIMEOUT, helloHeld);

        start(lowNode.network(), 0);
        start(highNode.network(), highPort);
        lowNode.network().dialForTests(nodeInfo(high, highPort), false);
        // The peer holds its Hello answer: our QUIC handshake is done and our Hello has arrived, so the peer may
        // adopt this connection the moment it answers.
        awaitTrue(helloHeld::entered, "arming: the peer received our Hello (QUIC handshake complete) and holds its answer");
        lowNode.network().abandonPendingDialForTests(high);
        helloHeld.release();

        awaitTrue(() -> connected(lowNode.network(), high) && connected(highNode.network(), low),
                  "the dial past its handshake completes: both ends CONNECTED");
        assertEveryLaneCarriesTrafficBothWays(lowNode, highNode);
        assertThat(lowNode.network().quicMetrics().dialAbandonedCount()).as("an attempt past its QUIC handshake is never abandoned")
                                                                         .isZero();
    }

    @Test
    void lateHighestCore_normalShape_exactlyOneHandshakePerPair() {
        var ids = new ArrayList<NodeId>();
        var ports = new ArrayList<Integer>();
        var nodes = new ArrayList<TestNode>();

        for (int i = 1; i <= CORES; i++) {
            ids.add(new NodeId("dl-" + i));
            ports.add(freeUdpPort());
            nodes.add(node(ids.getLast(), DEFAULT_HELLO_TIMEOUT, BootTokens.bootTokens(0L)));
        }

        var late = CORES - 1;

        for (int i = 0; i < late; i++) {
            start(nodes.get(i).network(), ports.get(i));
        }
        // Each seed dials every higher id — the designated dialer — including the late core, which is not
        // listening yet, so those four attempts stay pending.
        for (int i = 0; i < late; i++) {
            for (int j = i + 1; j < CORES; j++) {
                nodes.get(i).network().dialForTests(nodeInfo(ids.get(j), ports.get(j)), false);
            }
        }
        awaitTrue(() -> seedsConnected(nodes, ids, late), "the seeds are connected to each other");
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(LATE_START_DELAY_MS));
        start(nodes.get(late).network(), ports.get(late));
        // The late core is the designated dialer for no pair; it initiates from its isolation branch (forced).
        for (int i = 0; i < late; i++) {
            nodes.get(late).network().dialForTests(nodeInfo(ids.get(i), ports.get(i)), true);
        }

        awaitTrue(() -> fullyConnected(nodes, ids), "every core CONNECTED to every other");
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(SETTLE_MS));

        assertThat(nodes.stream().mapToLong(node -> node.network().quicMetrics().handshakeTotalCount()).sum())
            .as("one connection per pair, counted at both ends: 5 × 4, no second handshake from a late designated dial")
            .isEqualTo((long) CORES * (CORES - 1));
        assertThat(nodes.stream().mapToLong(node -> node.network().quicMetrics().dialAbandonedCount()).sum())
            .as("arming: each seed's pending dial to the late core was abandoned when the late core's link attached")
            .isEqualTo(late);
        nodes.forEach(node -> assertThat(causes(node.journal())).as("nothing superseded at " + node.id())
                                                                .doesNotContainAnyElementsOf(SUPERSEDING_CAUSES));
    }

    private static boolean seedsConnected(List<TestNode> nodes, List<NodeId> ids, int seeds) {
        for (int i = 0; i < seeds; i++) {
            for (int j = 0; j < seeds; j++) {
                if (i != j && !connected(nodes.get(i).network(), ids.get(j))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean fullyConnected(List<TestNode> nodes, List<NodeId> ids) {
        return seedsConnected(nodes, ids, nodes.size());
    }

    private void assertEveryLaneCarriesTrafficBothWays(TestNode a, TestNode b) {
        Arrays.stream(StreamType.values())
              .forEach(lane -> {
                  a.network().send(b.id(), LaneProbe.laneProbe(a.id(), lane, "a-to-b-" + lane));
                  b.network().send(a.id(), LaneProbe.laneProbe(b.id(), lane, "b-to-a-" + lane));
              });
        awaitTrue(() -> missing(b.received(), "a-to-b-").isEmpty(), "a -> b on every lane: missing " + missing(b.received(), "a-to-b-"));
        awaitTrue(() -> missing(a.received(), "b-to-a-").isEmpty(), "b -> a on every lane: missing " + missing(a.received(), "b-to-a-"));
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

    private static PeerState.Phase phase(QuicClusterNetwork network, NodeId peer) {
        return network.peerPhaseForTests(peer)
                      .or(PeerState.Phase.INIT);
    }

    private static boolean connected(QuicClusterNetwork network, NodeId peer) {
        return phase(network, peer) == PeerState.Phase.CONNECTED;
    }

    private static NodeInfo nodeInfo(NodeId id, int port) {
        return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("127.0.0.1", port).fold(_ -> fail("bad address"), a -> a));
    }

    private static void start(QuicClusterNetwork network, int port) {
        network.startOnPort(port).await(AWAIT).onFailure(cause -> fail("start failed: " + cause.message()));
    }

    private record TestNode(NodeId id,
                            QuicClusterNetwork network,
                            List<LaneProbe> received,
                            List<PeerTransitionRecord> journal) {}

    private TestNode node(NodeId id, TimeSpan helloTimeout, BootTokens bootTokens) {
        var codec = LaneProbe.codec();
        var self = NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("127.0.0.1", 19993).fold(_ -> fail("bad address"), a -> a));
        var router = MessageRouter.mutable();
        var serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server"))
                                       .fold(_ -> fail("server ssl"), ssl -> ssl);
        var clientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client"))
                                       .fold(_ -> fail("client ssl"), ssl -> ssl);
        var received = new CopyOnWriteArrayList<LaneProbe>();
        var journal = new CopyOnWriteArrayList<PeerTransitionRecord>();

        router.addRoute(LaneProbe.class, received::add);
        var network = new QuicClusterNetwork(stubTopology(self, helloTimeout), codec, codec, router, serverSsl, clientSsl);

        network.setBootTokens(bootTokens);
        network.setPeerTransitionListener(journal::add);
        networks.add(network);

        return new TestNode(id, network, received, journal);
    }

    /// Boot tokens that hold the FIRST admission until released: the acceptor has received the dialer's Hello
    /// (so the dialer's QUIC handshake is complete) and has not answered yet.
    private static final class HeldAdmission implements BootTokens {
        private final BootTokens delegate;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final Map<NodeId, Boolean> held = new ConcurrentHashMap<>();

        HeldAdmission(BootTokens delegate) {
            this.delegate = delegate;
        }

        boolean entered() {
            return entered.getCount() == 0;
        }

        void release() {
            released.countDown();
        }

        @Override
        public Admission admit(NodeId peer, long token) {
            if (held.putIfAbsent(peer, Boolean.TRUE) == null) {
                entered.countDown();
                awaitRelease();
            }
            return delegate.admit(peer, token);
        }

        private void awaitRelease() {
            try {
                released.await(AWAIT.nanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override public long self() {return delegate.self();}
        @Override public boolean isRetired(NodeId peer) {return delegate.isRetired(peer);}
        @Override public long tokenOf(NodeId peer) {return delegate.tokenOf(peer);}
        @Override public long refusals() {return delegate.refusals();}
        @Override public Unit onRetired(Consumer<NodeId> listener) {return delegate.onRetired(listener);}
        @Override public Unit selfRefused(String reason) {return delegate.selfRefused(reason);}
        @Override public Unit onSelfRefused(Consumer<String> listener) {return delegate.onSelfRefused(listener);}
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

    private static TopologyObserver stubTopology(NodeInfo self, TimeSpan helloTimeout) {
        return new TopologyObserver() {
            @Override public Unit setConsensusMembership(Predicate<NodeId> membership) {return Unit.unit();}
            @Override public NodeInfo self() {return self;}
            @Override public Option<NodeInfo> get(NodeId id) {return id.equals(self.id()) ? Option.some(self) : Option.empty();}
            @Override public int clusterSize() {return CORES;}
            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}
            @Override public Promise<Unit> start() {return Promise.unitPromise();}
            @Override public Promise<Unit> stop() {return Promise.unitPromise();}
            @Override public TimeSpan pingInterval() {return PING_INTERVAL;}
            @Override public TimeSpan helloTimeout() {return helloTimeout;}
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
