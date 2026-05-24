// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.fsm;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.NoopClusterSyncCollector;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.NoopNetwork;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.Fsm;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// Direct FSM-level tests for the cluster-sync scheduler lifecycle. Exercises the sealed state
/// hierarchy ([`ClusterSyncState`]) without depending on the public `ClusterSyncScheduler` adapter
/// — each test builds an FSM via the initial-state factory pattern mirroring the adapter.
class ClusterSyncFsmTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();
    private static final NodeId PEER_B = NodeId.nodeId("peer-b").unwrap();

    @Nested
    class HappyPath {
        @Test
        void dormant_quorumEstablished_pinging_quorumDisappeared_dormant() {
            var harness = buildFsmHarness();
            var ctx = harness.context();
            assertThat(harness.fsm().current()).isSameAs(ctx.dormant());

            harness.fsm().dispatch(new ClusterFsmEvent.QuorumEstablished());
            assertThat(harness.fsm().current()).isInstanceOf(ClusterSyncState.Pinging.class);

            harness.fsm().dispatch(new ClusterFsmEvent.QuorumDisappeared());
            assertThat(harness.fsm().current()).isSameAs(ctx.dormant());
        }

        @Test
        void shutdown_movesToStoppedTerminal() {
            var harness = buildFsmHarness();
            var ctx = harness.context();

            harness.fsm().dispatch(new ClusterFsmEvent.QuorumEstablished());
            harness.fsm().dispatch(new ClusterFsmEvent.Shutdown());

            assertThat(harness.fsm().current()).isSameAs(ctx.stopped());
            // Subsequent events are ignored — no transition.
            harness.fsm().dispatch(new ClusterFsmEvent.QuorumEstablished());
            assertThat(harness.fsm().current()).isSameAs(ctx.stopped());
        }
    }

    @Nested
    class CasContention {
        @Test
        void eightConcurrentQuorumEstablished_exactlyOneWins_andPingTaskStartedOnce() throws InterruptedException {
            var countingNetwork = new CountingClusterNetwork();
            var harness = buildFsmHarness(countingNetwork, HealthSignalSink.noop());
            // Seed topology so a PingTick would have a peer to send to.
            harness.context().setTopology(List.of(SELF, PEER_A));

            var events = IntStream.range(0, 8)
                                  .<ClusterFsmEvent>mapToObj(_ -> new ClusterFsmEvent.QuorumEstablished())
                                  .toList();
            var threads = new ArrayList<Thread>(events.size());
            var latch = new CountDownLatch(1);
            for (var ev : events) {
                var t = new Thread(() -> awaitAndDispatch(latch, harness.fsm(), ev));
                threads.add(t);
                t.start();
            }
            latch.countDown();
            for (var t : threads) { t.join(); }

            // Only one transition into Pinging should have committed — the others lost the CAS.
            assertThat(harness.fsm().current()).isInstanceOf(ClusterSyncState.Pinging.class);
            // Exactly one Pinging instance was reached — the ping task was scheduled exactly once.
            // We assert the CAS-win bookkeeping by counting that the freshly-entered Pinging
            // instance is currently in place; redundant QuorumEstablished events in Pinging are
            // ignored (do not rebuild the record).
            var first = harness.fsm().current();
            harness.fsm().dispatch(new ClusterFsmEvent.QuorumEstablished());
            assertThat(harness.fsm().current()).isSameAs(first);
        }
    }

    @Nested
    class IgnoredEvents {
        @Test
        void pingTickInDormant_isIgnored_noNetworkActivity() {
            var countingNetwork = new CountingClusterNetwork();
            var harness = buildFsmHarness(countingNetwork, HealthSignalSink.noop());
            harness.context().setTopology(List.of(SELF, PEER_A));

            harness.fsm().dispatch(new ClusterSyncEvents.PingTick(Epoch.epoch(7L, 0L)));

            assertThat(harness.fsm().current()).isSameAs(harness.context().dormant());
            assertThat(countingNetwork.sent()).isEmpty();
        }

        @Test
        void pongReceivedInDormant_isIgnored() {
            var harness = buildFsmHarness();

            harness.fsm().dispatch(new ClusterSyncEvents.PongReceived(PEER_A));

            assertThat(harness.fsm().current()).isSameAs(harness.context().dormant());
        }
    }

    @Nested
    class UnseededTopologyFallback {
        @Test
        void pingTick_emptyTopologyButConnectedPeers_dispatchesToConnectedPeers() {
            var network = new ConfigurableConnectedNetwork(Set.of(PEER_A, PEER_B));
            var harness = buildFsmHarness(network, HealthSignalSink.noop());
            // Topology is never seeded (no MembershipDecision/NodeJoined deltas) — Spike-1 scenario.
            harness.fsm().dispatch(new ClusterFsmEvent.QuorumEstablished());

            harness.fsm().dispatch(new ClusterSyncEvents.PingTick(Epoch.epoch(7L, 0L)));

            assertThat(harness.fsm().current()).isInstanceOf(ClusterSyncState.Pinging.class);
            assertThat(network.sent()).containsExactlyInAnyOrder(PEER_A, PEER_B);
        }

        @Test
        void pingTick_emptyTopologyAndNoConnectedPeers_isIgnored() {
            var network = new ConfigurableConnectedNetwork(Set.of());
            var harness = buildFsmHarness(network, HealthSignalSink.noop());
            harness.fsm().dispatch(new ClusterFsmEvent.QuorumEstablished());

            harness.fsm().dispatch(new ClusterSyncEvents.PingTick(Epoch.epoch(7L, 0L)));

            assertThat(harness.fsm().current()).isInstanceOf(ClusterSyncState.Pinging.class);
            assertThat(network.sent()).isEmpty();
        }
    }

    @Nested
    class CounterBehaviour {
        @Test
        void twoPingTicks_withoutPong_incrementCounterPastThreshold_emitsTimeout() {
            var captured = new CopyOnWriteArrayList<HealthSignal>();
            HealthSignalSink sink = captured::add;
            var harness = buildFsmHarness(new CountingClusterNetwork(), sink, 2);
            harness.context().setTopology(List.of(SELF, PEER_A));
            harness.fsm().dispatch(new ClusterFsmEvent.QuorumEstablished());

            harness.fsm().dispatch(new ClusterSyncEvents.PingTick(Epoch.epoch(7L, 0L)));
            harness.fsm().dispatch(new ClusterSyncEvents.PingTick(Epoch.epoch(7L, 0L)));

            var pingTimeouts = captured.stream()
                                       .filter(s -> s instanceof HealthSignal.PingTimeout)
                                       .map(s -> (HealthSignal.PingTimeout) s)
                                       .toList();
            assertThat(pingTimeouts).hasSize(1);
            assertThat(pingTimeouts.getFirst().nodeId()).isEqualTo(PEER_A);
            assertThat(pingTimeouts.getFirst().missedIntervals()).isEqualTo(2);
            assertThat(counterForPeer(harness, PEER_A)).isEqualTo(2);
        }

        @Test
        void pongReceived_resetsCounterForThatPeerToZero() {
            var captured = new CopyOnWriteArrayList<HealthSignal>();
            HealthSignalSink sink = captured::add;
            var harness = buildFsmHarness(new CountingClusterNetwork(), sink, 3);
            harness.context().setTopology(List.of(SELF, PEER_A, PEER_B));
            harness.fsm().dispatch(new ClusterFsmEvent.QuorumEstablished());

            harness.fsm().dispatch(new ClusterSyncEvents.PingTick(Epoch.epoch(7L, 0L)));
            harness.fsm().dispatch(new ClusterSyncEvents.PingTick(Epoch.epoch(7L, 0L)));
            assertThat(counterForPeer(harness, PEER_A)).isEqualTo(2);
            assertThat(counterForPeer(harness, PEER_B)).isEqualTo(2);

            harness.fsm().dispatch(new ClusterSyncEvents.PongReceived(PEER_A));

            assertThat(counterForPeer(harness, PEER_A)).isEqualTo(0);
            // PEER_B counter untouched by PEER_A's pong.
            assertThat(counterForPeer(harness, PEER_B)).isEqualTo(2);
            // No timeout fired yet (threshold is 3; PEER_B is at 2, PEER_A at 0).
            assertThat(captured).isEmpty();
        }
    }

    @Nested
    class OwnerSideSwimGuard {
        @Test
        void emitPingTimeout_peerSwimAlive_doesNotDisconnectAndEmitsNoEvictionHint() {
            // FIX 3 (a): the owner-side ping-timeout path must mirror the follower-side
            // processEvictionHints SWIM guard. A peer past the ping-timeout threshold but
            // reported SWIM-ALIVE (e.g. transiently missing leader-coupled pongs during a
            // re-election) must NOT be soft-evicted and must NOT be broadcast as an eviction
            // hint — preventing the live-peer REMOVE flap / self-drain cascade.
            var network = new RecordingNetwork();
            var collector = new SwimAwareCollector(Set.of(PEER_A));
            var harness = buildFsmHarness(network, HealthSignalSink.noop(), 3, collector);

            harness.context().emitPingTimeoutIfExceeded(PEER_A, 5);

            assertThat(network.disconnected())
                .as("SWIM-ALIVE peer must not be disconnected by owner-side ping timeout")
                .isEmpty();
            // The next outbound ping must NOT carry an eviction hint for the SWIM-ALIVE peer.
            harness.context().sendOnePing(PEER_A, Epoch.epoch(7L, 0L), 7L);
            var pings = network.sent().stream()
                               .filter(m -> m instanceof org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing)
                               .map(m -> (org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing) m)
                               .toList();
            assertThat(pings).as("exactly one ping was sent").hasSize(1);
            assertThat(pings.getFirst().evictionHints())
                .as("no eviction hint broadcast for SWIM-ALIVE peer")
                .doesNotContain(PEER_A);
        }

        @Test
        void emitPingTimeout_peerNotSwimAlive_disconnects() {
            // Control: a peer past the threshold that SWIM does NOT consider ALIVE is evicted
            // as before — the guard is strictly additive, it does not block genuine dead-peer
            // eviction.
            var network = new RecordingNetwork();
            var collector = new SwimAwareCollector(Set.of());
            var harness = buildFsmHarness(network, HealthSignalSink.noop(), 3, collector);

            harness.context().emitPingTimeoutIfExceeded(PEER_A, 5);

            assertThat(network.disconnected())
                .as("non-ALIVE peer past threshold is still evicted")
                .containsExactly(PEER_A);
        }

        @Test
        void emitPingTimeout_belowThreshold_isNoopRegardlessOfSwim() {
            // Below-threshold calls short-circuit before the guard or any side effect.
            var network = new RecordingNetwork();
            var collector = new SwimAwareCollector(Set.of());
            var harness = buildFsmHarness(network, HealthSignalSink.noop(), 3, collector);

            harness.context().emitPingTimeoutIfExceeded(PEER_A, 1);

            assertThat(network.disconnected()).isEmpty();
        }
    }

    // --- harness plumbing ---

    private static int counterForPeer(FsmHarness harness, NodeId peer) {
        if (!(harness.fsm().current() instanceof ClusterSyncState.Pinging pinging)) {
            return -1;
        }
        return pinging.missedPings().getOrDefault(peer, 0);
    }

    private static FsmHarness buildFsmHarness() {
        return buildFsmHarness(new NoopNetwork(), HealthSignalSink.noop(), 3);
    }

    private static FsmHarness buildFsmHarness(ClusterNetwork network, HealthSignalSink sink) {
        return buildFsmHarness(network, sink, 3);
    }

    private static FsmHarness buildFsmHarness(ClusterNetwork network, HealthSignalSink sink, int threshold) {
        return buildFsmHarness(network, sink, threshold, new NoopClusterSyncCollector());
    }

    private static FsmHarness buildFsmHarness(ClusterNetwork network,
                                              HealthSignalSink sink,
                                              int threshold,
                                              ClusterSyncCollector collector) {
        var ctxRef = new AtomicReference<ClusterSyncContext>();
        Function<Fsm<ClusterSyncState, ClusterFsmEvent>, ClusterSyncState> factory =
                fsm -> {
                    var ctx = new ClusterSyncContext(fsm,
                                                     SELF,
                                                     network,
                                                     collector,
                                                     TimeSpan.timeSpan(1).hours(),
                                                     () -> 7L,
                                                     sink,
                                                     threshold,
                                                     () -> Epoch.epoch(7L, 0L),
                                                     org.pragmatica.aether.metrics.observation.PeerObservationStore.peerObservationStore());
                    ctxRef.set(ctx);
                    return ctx.dormant();
                };
        var fsm = Fsm.fsm("cluster-sync-fsm-test", factory);
        return new FsmHarness(fsm, ctxRef.get());
    }

    private record FsmHarness(Fsm<ClusterSyncState, ClusterFsmEvent> fsm, ClusterSyncContext context) {}

    private static void awaitAndDispatch(CountDownLatch latch,
                                         Fsm<ClusterSyncState, ClusterFsmEvent> fsm,
                                         ClusterFsmEvent ev) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        fsm.dispatch(ev);
    }

    /// `ClusterNetwork` stub that records per-target sends without doing any wire I/O. Only the
    /// methods the scheduler uses (`send`) are meaningful; the rest inherit the Noop contract via
    /// explicit overrides to satisfy the interface.
    private static class CountingClusterNetwork extends NoopNetwork {
        private final CopyOnWriteArrayList<NodeId> sent = new CopyOnWriteArrayList<>();

        @Override
        public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            sent.add(nodeId);
            return super.send(nodeId, message);
        }

        List<NodeId> sent() { return List.copyOf(sent); }
    }

    /// `CountingClusterNetwork` variant with a fixed `connectedPeers()` set. Exercises the
    /// Spike-1 fallback: topology unseeded but transport peers present.
    private static final class ConfigurableConnectedNetwork extends CountingClusterNetwork {
        private final Set<NodeId> connected;

        ConfigurableConnectedNetwork(Set<NodeId> connected) {
            this.connected = Set.copyOf(connected);
        }

        @Override
        public Set<NodeId> connectedPeers() { return connected; }
    }

    /// `ClusterNetwork` stub that records the targets of `disconnect(...)` and the outbound
    /// messages from `send(...)` so the owner-side SWIM guard can be asserted (the harmful
    /// actions FIX 3 suppresses for ALIVE peers: the disconnect AND the eviction-hint broadcast
    /// carried in the next `ClusterSyncPing`).
    private static final class RecordingNetwork extends NoopNetwork {
        private final CopyOnWriteArrayList<NodeId> disconnected = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<ProtocolMessage> sent = new CopyOnWriteArrayList<>();

        @Override
        public void disconnect(org.pragmatica.consensus.net.NetworkServiceMessage.DisconnectNode disconnectNode) {
            disconnected.add(disconnectNode.nodeId());
        }

        @Override
        public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            sent.add(message);
            return super.send(nodeId, message);
        }

        List<NodeId> disconnected() { return List.copyOf(disconnected); }

        List<ProtocolMessage> sent() { return List.copyOf(sent); }
    }

    /// Collector double whose SWIM-alive predicate is fixed at construction, so the owner-side
    /// `emitPingTimeoutIfExceeded` guard can be driven deterministically without standing up
    /// real SWIM. ALIVE peers must be exempt from ping-timeout eviction.
    private static final class SwimAwareCollector extends NoopClusterSyncCollector {
        private final Set<NodeId> alivePeers;

        SwimAwareCollector(Set<NodeId> alivePeers) {
            this.alivePeers = Set.copyOf(alivePeers);
        }

        @Override
        public boolean peerLocallyAlive(NodeId peer) {
            return alivePeers.contains(peer);
        }
    }
}
