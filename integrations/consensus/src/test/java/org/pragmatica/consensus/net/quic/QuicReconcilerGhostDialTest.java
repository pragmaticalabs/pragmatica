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
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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

/// #1749 premise test, reconciler side: a dead id G whose address now answers as another node B. The reconciler
/// dials G from the FSM desired set; every dial ends in `IdentityMismatch`.
///
/// Two properties, each with a control that proves the dial counter can see a dial:
///
///  - G leaves the desired set: dials stop (the control is the dials before the removal, and one more after G is
///    put back into the set).
///  - G is in the desired set but no longer reported alive by the raw-SWIM liveness gate: the dial spacing grows
///    to the 60 s cap, against one dial per 5 s tick while the gate reports it alive (the control).
///
/// The clock is the network's injected wall clock; a dial is observed as the peer's INIT/EVICTED to CONNECTING
/// transition. A tick that dials is awaited to its end (the dial's mismatch callback and the return of the peer to
/// a non-CONNECTING phase) before the next tick, so an in-flight dial never masks the next tick's decision.
@Timeout(120)
class QuicReconcilerGhostDialTest {
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(30).seconds();
    private static final TimeSpan PING_INTERVAL = TimeSpan.timeSpan(30).seconds();
    private static final long TICK_MS = 5_000L;
    private static final long CAP_MS = 60_000L;
    private static final long DIAL_START_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    /// The lower id initiates, so G must sort above A.
    private static final NodeId SELF = new NodeId("gd-a");
    private static final NodeId ANSWERER = new NodeId("gd-b");
    private static final NodeId GHOST = new NodeId("gd-c");

    private final List<QuicClusterNetwork> networks = new ArrayList<>();
    private final CopyOnWriteArrayList<PeerTransitionRecord> journal = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<List<NodeId>> refutations = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Long> dialTimes = new CopyOnWriteArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicReference<List<NodeInfo>> desired = new AtomicReference<>(List.of());
    private QuicClusterNetwork aNet;
    private NodeInfo ghostInfo;

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
    }

    private void arm() {
        aNet = network(SELF, journal);
        var bNet = network(ANSWERER, new CopyOnWriteArrayList<>());

        aNet.setPeerStateListener(refutationRecorder());
        aNet.overrideWallClockForTests(clock::get);
        aNet.setSwimLivenessGate(_ -> alive.get());
        aNet.setDesiredConnections(() -> (Collection<NodeInfo>) desired.get());
        start(aNet);
        start(bNet);
        ghostInfo = nodeInfo(GHOST, bNet.boundPort().unwrap());
        desired.set(List.of(ghostInfo));
    }

    @Test
    void ghostLeavingTheDesiredSet_stopsTheDials() {
        arm();

        tick(6);
        assertThat(dialTimes).as("control: while G is desired and reported alive, every tick dials").hasSize(6);
        assertThat(refutations).as("every dial ended in IdentityMismatch (dialed = G, claimant = B)")
                               .hasSize(6)
                               .containsOnly(List.of(GHOST, ANSWERER));

        desired.set(List.of());
        tick(6);
        assertThat(dialTimes).as("G left the desired set: no dial, although the liveness gate still reports it alive")
                             .hasSize(6);

        desired.set(List.of(ghostInfo));
        tick(1);
        assertThat(dialTimes).as("control: put back into the set, G is dialed again, so the counter still sees dials")
                             .hasSize(7);
    }

    @Test
    void ghostNoLongerReportedAlive_dialSpacingGrowsToTheCap() {
        arm();

        tick(4);
        assertThat(dialTimes).as("control: reported alive, one dial per tick").hasSize(4);

        alive.set(false);
        var window = 48;

        tick(window);
        var windowDials = dialTimes.subList(4, dialTimes.size());

        assertThat(windowDials).as("not alive: %d ticks over %d s dial a handful of times, not once per tick",
                                   window,
                                   window * TICK_MS / 1_000)
                               .hasSizeBetween(5, 9);
        assertThat(windowDials.get(windowDials.size() - 1) - windowDials.get(windowDials.size() - 2))
            .as("the last spacing is the capped one (60 s, jittered to at least 48 s)")
            .isGreaterThanOrEqualTo(CAP_MS * 8 / 10);
    }

    private void tick(int ticks) {
        for (int i = 0; i < ticks; i++) {
            tickOnce();
        }
    }

    private void tickOnce() {
        var dialsBefore = connectingCount();

        clock.addAndGet(TICK_MS);
        aNet.reconcileMissingPeersTick();
        if (awaitWithin(() -> connectingCount() > dialsBefore, DIAL_START_WINDOW_NANOS)) {
            dialTimes.add(clock.get());
            awaitTrue(() -> refutations.size() == connectingCount() && !connecting(),
                      "the dial ended in an identity mismatch and the peer left CONNECTING");
        }
    }

    private int connectingCount() {
        return (int) journal.stream()
                            .filter(record -> record.peerId().equals(GHOST))
                            .filter(record -> record.to() == PeerState.Phase.CONNECTING)
                            .count();
    }

    private boolean connecting() {
        return aNet.peerPhaseForTests(GHOST)
                   .filter(phase -> phase == PeerState.Phase.CONNECTING)
                   .isPresent();
    }

    private QuicPeerStateListener refutationRecorder() {
        return new QuicPeerStateListener() {
            @Override public void onPeerJoined(NodeId nodeId) {}
            @Override public void onPeerReconnected(NodeId nodeId) {}
            @Override public void onPeerLeft(NodeId nodeId) {}
            @Override public void onPeerIdentityRefuted(NodeId dialed, NodeId claimant) {refutations.add(List.of(dialed, claimant));}
        };
    }

    private static NodeInfo nodeInfo(NodeId id, int port) {
        return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("127.0.0.1", port).fold(_ -> fail("bad address"), a -> a));
    }

    private static void start(QuicClusterNetwork network) {
        network.startOnPort(0).await(AWAIT).onFailure(cause -> fail("start failed: " + cause.message()));
    }

    private QuicClusterNetwork network(NodeId id, List<PeerTransitionRecord> transitions) {
        var codec = LaneProbe.codec();
        var self = nodeInfo(id, 19994);
        var serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server"))
                                       .fold(_ -> fail("server ssl"), ssl -> ssl);
        var clientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client"))
                                       .fold(_ -> fail("client ssl"), ssl -> ssl);
        var network = new QuicClusterNetwork(stubTopology(self), codec, codec, MessageRouter.mutable(), serverSsl, clientSsl);

        network.setPeerTransitionListener(transitions::add);
        networks.add(network);

        return network;
    }

    private static boolean awaitWithin(BooleanSupplier condition, long nanos) {
        var deadline = System.nanoTime() + nanos;

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }

        return condition.getAsBoolean();
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        if (!awaitWithin(condition, AWAIT.nanos())) {
            fail("Timed out waiting for: " + what);
        }
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
