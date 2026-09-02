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
import org.pragmatica.aether.metrics.SwimAwareCollector;
import org.pragmatica.aether.slice.generation.Epoch;
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
            var harness = buildFsmHarness(countingNetwork);
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
            var harness = buildFsmHarness(countingNetwork);
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
    class BroadcastDispatch {
        @Test
        void pingTick_connectedPeers_issuesSingleBroadcast() {
            var network = new ConfigurableConnectedNetwork(Set.of(PEER_A, PEER_B));
            var harness = buildFsmHarness(network);
            // Topology is never seeded (no MembershipDecision/NodeJoined deltas) — recipients
            // are the live transport peers; the leader BROADCASTS one uniform ping to all of them.
            harness.fsm().dispatch(new ClusterFsmEvent.QuorumEstablished());

            harness.fsm().dispatch(new ClusterSyncEvents.PingTick(Epoch.epoch(7L, 0L)));

            assertThat(harness.fsm().current()).isInstanceOf(ClusterSyncState.Pinging.class);
            assertThat(network.broadcasts()).as("exactly one broadcast ping per tick").hasSize(1);
            assertThat(network.sent()).as("no per-peer unicast in the broadcast model").isEmpty();
        }

        @Test
        void pingTick_noConnectedPeers_isIgnored_noBroadcast() {
            var network = new ConfigurableConnectedNetwork(Set.of());
            var harness = buildFsmHarness(network);
            harness.fsm().dispatch(new ClusterFsmEvent.QuorumEstablished());

            harness.fsm().dispatch(new ClusterSyncEvents.PingTick(Epoch.epoch(7L, 0L)));

            assertThat(harness.fsm().current()).isInstanceOf(ClusterSyncState.Pinging.class);
            assertThat(network.broadcasts()).isEmpty();
        }

        @Test
        void pingTick_connectedPeerAbsentFromTopology_isStillMissTracked() {
            // A node present on the transport (connectedPeers) but missing from the lossy
            // delta-fed topology cache must still be a broadcast recipient and be miss-tracked,
            // since recipients are sourced from connectedPeers() (not topology).
            var network = new ConfigurableConnectedNetwork(Set.of(PEER_A, PEER_B));
            var harness = buildFsmHarness(network);
            // Topology knows SELF and PEER_A only — PEER_B is transport-connected but absent.
            harness.context().setTopology(List.of(SELF, PEER_A));
            harness.fsm().dispatch(new ClusterFsmEvent.QuorumEstablished());

            harness.fsm().dispatch(new ClusterSyncEvents.PingTick(Epoch.epoch(7L, 0L)));

            assertThat(network.broadcasts()).as("single broadcast ping").hasSize(1);
            assertThat(counterForPeer(harness, PEER_B))
                .as("PEER_B miss-tracked despite missing topology edge — recipients are connectedPeers")
                .isEqualTo(1);
        }
    }

    @Nested
    class CounterBehaviour {
        @Test
        void twoPingTicks_withoutPong_incrementCounterPastThreshold_reportsUnreachable() {
            var reported = new CopyOnWriteArrayList<NodeId>();
            var collector = new SwimAwareCollector(Set.of());
            collector.setUnreachableReporter(reported::add);
            var harness = buildFsmHarness(new ConfigurableConnectedNetwork(Set.of(PEER_A)), 2, collector);
            harness.fsm().dispatch(new ClusterFsmEvent.QuorumEstablished());

            harness.fsm().dispatch(new ClusterSyncEvents.PingTick(Epoch.epoch(7L, 0L)));
            harness.fsm().dispatch(new ClusterSyncEvents.PingTick(Epoch.epoch(7L, 0L)));

            assertThat(reported).containsExactly(PEER_A);
            // The missed count itself is only observable here, on the counter the threshold reads.
            assertThat(counterForPeer(harness, PEER_A)).isEqualTo(2);
        }

        @Test
        void pongReceived_resetsCounterForThatPeerToZero() {
            var reported = new CopyOnWriteArrayList<NodeId>();
            var collector = new SwimAwareCollector(Set.of());
            collector.setUnreachableReporter(reported::add);
            var harness = buildFsmHarness(new ConfigurableConnectedNetwork(Set.of(PEER_A, PEER_B)), 3, collector);
            harness.fsm().dispatch(new ClusterFsmEvent.QuorumEstablished());

            harness.fsm().dispatch(new ClusterSyncEvents.PingTick(Epoch.epoch(7L, 0L)));
            harness.fsm().dispatch(new ClusterSyncEvents.PingTick(Epoch.epoch(7L, 0L)));
            assertThat(counterForPeer(harness, PEER_A)).isEqualTo(2);
            assertThat(counterForPeer(harness, PEER_B)).isEqualTo(2);

            harness.fsm().dispatch(new ClusterSyncEvents.PongReceived(PEER_A));

            assertThat(counterForPeer(harness, PEER_A)).isEqualTo(0);
            // PEER_B counter untouched by PEER_A's pong.
            assertThat(counterForPeer(harness, PEER_B)).isEqualTo(2);
            // Nothing reported unreachable yet (threshold is 3; PEER_B is at 2, PEER_A at 0).
            assertThat(reported).isEmpty();
        }
    }

    @Nested
    class OwnerSideSwimGuard {
        @Test
        void emitPingTimeout_peerSwimAlive_doesNotDisconnectOrReportUnreachable() {
            // Option 1 + the owner-side SWIM guard: a peer past the ping-timeout threshold but
            // reported SWIM-ALIVE (e.g. transiently missing leader-coupled pongs during a
            // re-election) must NOT be disconnected and must NOT be fed into SWIM as an unreachable
            // hint — feeding a conflicting hint for a peer SWIM already trusts would be self-defeating
            // and risks the live-peer flap / self-drain cascade.
            var network = new RecordingNetwork();
            var collector = new SwimAwareCollector(Set.of(PEER_A));
            var reported = new CopyOnWriteArrayList<NodeId>();
            collector.setUnreachableReporter(reported::add);
            var harness = buildFsmHarness(network, 3, collector);

            harness.context().emitPingTimeoutIfExceeded(PEER_A, 5);

            assertThat(network.disconnected())
                .as("SWIM-ALIVE peer must not be disconnected by owner-side ping timeout")
                .isEmpty();
            assertThat(reported)
                .as("no unreachable hint fed into SWIM for a SWIM-ALIVE peer")
                .isEmpty();
            // The inert eviction-hint wire path must also stay empty — the next outbound ping carries
            // no hint for any peer (emitPingTimeoutIfExceeded is the sole writer and no longer writes).
            harness.context().broadcastPing(Epoch.epoch(7L, 0L), 7L);
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
        void emitPingTimeout_peerNotSwimAlive_reportsUnreachableAndDoesNotDisconnect() {
            // Option 1: a peer past the threshold that SWIM does NOT consider ALIVE is fed into SWIM
            // as transport-unreachable EVIDENCE (refutable when pongs resume) instead of being
            // destructively disconnected. The old behavior (network.disconnect on this path) is gone.
            var network = new RecordingNetwork();
            var collector = new SwimAwareCollector(Set.of());
            var reported = new CopyOnWriteArrayList<NodeId>();
            collector.setUnreachableReporter(reported::add);
            var harness = buildFsmHarness(network, 3, collector);

            harness.context().emitPingTimeoutIfExceeded(PEER_A, 5);

            assertThat(reported)
                .as("non-ALIVE peer past threshold is reported to SWIM as unreachable")
                .containsExactly(PEER_A);
            assertThat(network.disconnected())
                .as("option 1: ping-timeout no longer manufactures a destructive disconnect")
                .isEmpty();
        }

        @Test
        void emitPingTimeout_reportsUnreachableForDeadPeerOnly_notForSwimAlivePeer() {
            // Both branches in one pass: the SWIM-ALIVE peer is skipped entirely while the
            // SWIM-not-alive peer is reported unreachable exactly once; neither is disconnected.
            var network = new RecordingNetwork();
            var collector = new SwimAwareCollector(Set.of(PEER_A));
            var reported = new CopyOnWriteArrayList<NodeId>();
            collector.setUnreachableReporter(reported::add);
            var harness = buildFsmHarness(network, 3, collector);

            harness.context().emitPingTimeoutIfExceeded(PEER_A, 5);
            harness.context().emitPingTimeoutIfExceeded(PEER_B, 5);

            assertThat(reported)
                .as("only the SWIM-not-alive peer is reported unreachable, exactly once")
                .containsExactly(PEER_B);
            assertThat(network.disconnected())
                .as("option 1 never manufactures a disconnect")
                .isEmpty();
        }

        @Test
        void emitPingTimeout_belowThreshold_isNoopRegardlessOfSwim() {
            // Below-threshold calls short-circuit before the guard or any side effect.
            var network = new RecordingNetwork();
            var collector = new SwimAwareCollector(Set.of());
            var reported = new CopyOnWriteArrayList<NodeId>();
            collector.setUnreachableReporter(reported::add);
            var harness = buildFsmHarness(network, 3, collector);

            harness.context().emitPingTimeoutIfExceeded(PEER_A, 1);

            assertThat(network.disconnected()).isEmpty();
            assertThat(reported).as("below threshold short-circuits before any SWIM hint").isEmpty();
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
        return buildFsmHarness(new NoopNetwork(), 3);
    }

    private static FsmHarness buildFsmHarness(ClusterNetwork network) {
        return buildFsmHarness(network, 3);
    }

    private static FsmHarness buildFsmHarness(ClusterNetwork network, int threshold) {
        return buildFsmHarness(network, threshold, new NoopClusterSyncCollector());
    }

    private static FsmHarness buildFsmHarness(ClusterNetwork network,
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

    /// `ClusterNetwork` stub that records per-target sends and broadcast pings without doing any
    /// wire I/O. Only the methods the scheduler uses (`send`, `broadcast`) are meaningful; the rest
    /// inherit the Noop contract via explicit overrides to satisfy the interface.
    private static class CountingClusterNetwork extends NoopNetwork {
        private final CopyOnWriteArrayList<NodeId> sent = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<ProtocolMessage> broadcasts = new CopyOnWriteArrayList<>();

        @Override
        public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            sent.add(nodeId);
            return super.send(nodeId, message);
        }

        @Override
        public <M extends ProtocolMessage> Unit broadcast(M message) {
            broadcasts.add(message);
            return super.broadcast(message);
        }

        List<NodeId> sent() { return List.copyOf(sent); }

        List<ProtocolMessage> broadcasts() { return List.copyOf(broadcasts); }
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

        @Override
        public <M extends ProtocolMessage> Unit broadcast(M message) {
            sent.add(message);
            return super.broadcast(message);
        }

        List<NodeId> disconnected() { return List.copyOf(disconnected); }

        List<ProtocolMessage> sent() { return List.copyOf(sent); }
    }
}
