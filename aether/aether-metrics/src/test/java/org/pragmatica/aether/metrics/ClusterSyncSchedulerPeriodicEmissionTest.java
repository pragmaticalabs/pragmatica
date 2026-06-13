// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.cluster.metrics.CommunityReport;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.metrics.PeerObservationBuffer;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.io.TimeSpan;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies that `ClusterSyncScheduler` schedules and drives periodic
/// `emitPeriodicConnectivity` invocations on the underlying collector per Step 1
/// of the topology-observation refactor:
///  - `emitPeriodicConnectivityNow` synchronously routes through to the collector
///    with the current topology / connected peers / self (used by deterministic
///    tests in lieu of a `TestScheduler`).
///  - the scheduled task fires automatically at the `PeriodicObservationConfig.period()`
///    cadence once the FSM transitions to `Pinging` (validated via short-period
///    timer + invocation count poll).
///  - the periodic task is leader-AGNOSTIC at emission (followers emit too — leader
///    gating happens at FSM consumption, Step 3).
class ClusterSyncSchedulerPeriodicEmissionTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();
    private static final NodeId PEER_B = NodeId.nodeId("peer-b").unwrap();
    private static final NodeId PEER_C = NodeId.nodeId("peer-c").unwrap();

    @Test
    void emitPeriodicConnectivityNow_invokesCollectorWithCurrentTopologyAndConnectedPeers() {
        var connectedPeers = Set.of(PEER_A, PEER_B);
        var network = new RecordingNetwork(connectedPeers);
        var collector = new RecordingCollector();
        var scheduler = newScheduler(network, collector, longPeriodicConfig());
        scheduler.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A,
                                                                      List.of(SELF, PEER_A, PEER_B, PEER_C)));

        scheduler.emitPeriodicConnectivityNow();

        var invocations = collector.invocations();
        assertThat(invocations).hasSize(1);
        var invocation = invocations.getFirst();
        assertThat(invocation.self()).isEqualTo(SELF);
        assertThat(invocation.topology()).containsExactlyInAnyOrder(SELF, PEER_A, PEER_B, PEER_C);
        assertThat(invocation.connected()).containsExactlyInAnyOrder(PEER_A, PEER_B);
    }

    @Test
    void emitPeriodicConnectivityNow_twoInvocationsRecordTwice() {
        var network = new RecordingNetwork(Set.of(PEER_A));
        var collector = new RecordingCollector();
        var scheduler = newScheduler(network, collector, longPeriodicConfig());
        scheduler.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A)));

        scheduler.emitPeriodicConnectivityNow();
        scheduler.emitPeriodicConnectivityNow();

        assertThat(collector.invocations()).hasSize(2);
    }

    @Test
    void emitPeriodicConnectivityNow_emptyTopology_skipsInvocation() {
        var network = new RecordingNetwork(Set.of());
        var collector = new RecordingCollector();
        var scheduler = newScheduler(network, collector, longPeriodicConfig());

        scheduler.emitPeriodicConnectivityNow();

        assertThat(collector.invocations()).isEmpty();
    }

    @Test
    void onMembershipDecision_nodeJoining_includesJoiningPeerInTopologyEmission() {
        // RC1 resilience regression guard. A joining peer is NOT in `coreMemberIds` yet,
        // so `MembershipDecision.NodeJoining.topology()` reflects the ready-member view only.
        // ClusterSyncScheduler MUST augment the metrics topology with the joining nodeId,
        // otherwise the periodic emission never reports DISCONNECTED for a crashed
        // pre-promotion replacement and the aggregator-quorum gate refuses to confirm
        // UNREACHABLE — leaving a stale member in the presence-derived view.
        var connectedPeers = Set.of(PEER_A);
        var network = new RecordingNetwork(connectedPeers);
        var collector = new RecordingCollector();
        var scheduler = newScheduler(network, collector, longPeriodicConfig());

        scheduler.onMembershipDecision(MembershipDecision.nodeJoining(PEER_B, List.of(SELF, PEER_A)));
        scheduler.emitPeriodicConnectivityNow();

        var invocations = collector.invocations();
        assertThat(invocations).hasSize(1);
        var invocation = invocations.getFirst();
        assertThat(invocation.topology()).containsExactlyInAnyOrder(SELF, PEER_A, PEER_B);
        assertThat(invocation.connected()).containsExactlyInAnyOrder(PEER_A);
    }

    @Test
    void onMembershipDecision_nodeJoining_alreadyInTopology_noDuplicate() {
        // Defensive: if a future projection includes the joining peer in `topology` itself,
        // augmentation must be a no-op (no duplicate in the emission set).
        var network = new RecordingNetwork(Set.of(PEER_A, PEER_B));
        var collector = new RecordingCollector();
        var scheduler = newScheduler(network, collector, longPeriodicConfig());

        scheduler.onMembershipDecision(MembershipDecision.nodeJoining(PEER_B, List.of(SELF, PEER_A, PEER_B)));
        scheduler.emitPeriodicConnectivityNow();

        var invocations = collector.invocations();
        assertThat(invocations).hasSize(1);
        assertThat(invocations.getFirst().topology()).containsExactlyInAnyOrder(SELF, PEER_A, PEER_B);
    }

    @Test
    void scheduledPeriodicTask_firesAtConfiguredCadenceOncePinging() {
        // Validates the scheduler wires SharedScheduler.scheduleAtFixedRate at
        // `periodicConfig.period()` cadence. Uses a tight 100ms period so the test
        // resolves quickly without blocking. Expectation: ≥2 invocations within
        // ~2s of FSM entering Pinging.
        var network = new RecordingNetwork(Set.of(PEER_A));
        var collector = new RecordingCollector();
        var tightConfig = new PeriodicObservationConfig(TimeSpan.timeSpan(100).millis(),
                                                        TimeSpan.timeSpan(300).millis(),
                                                        () -> 64);
        var scheduler = newScheduler(network, collector, tightConfig);
        scheduler.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A)));
        scheduler.onQuorumStateChange(ClusterStateNotification.active());

        pollUntil(() -> collector.invocations().size() >= 2, 2_000L);

        assertThat(collector.invocations()).hasSizeGreaterThanOrEqualTo(2);
        scheduler.stop();
    }

    @Test
    void scheduledPeriodicTask_cancelledOnPingingExit() {
        // Validates `Pinging.onExit` cancels the periodic task — moving the FSM
        // back to Dormant must stop further periodic invocations.
        var network = new RecordingNetwork(Set.of(PEER_A));
        var collector = new RecordingCollector();
        var tightConfig = new PeriodicObservationConfig(TimeSpan.timeSpan(100).millis(),
                                                        TimeSpan.timeSpan(300).millis(),
                                                        () -> 64);
        var scheduler = newScheduler(network, collector, tightConfig);
        scheduler.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A)));
        scheduler.onQuorumStateChange(ClusterStateNotification.active());

        pollUntil(() -> !collector.invocations().isEmpty(), 2_000L);

        scheduler.onQuorumStateChange(ClusterStateNotification.passive());
        var countAfterStop = collector.invocations().size();
        sleepQuietly(500L);
        var countAfterWait = collector.invocations().size();
        // After cancellation the at-most-1 in-flight task may complete, but no
        // further ticks should accrue (cadence is 100ms; 500ms wait would yield
        // 4-5 additional ticks if the task were still scheduled).
        assertThat(countAfterWait).isLessThanOrEqualTo(countAfterStop + 1);
    }

    private static ClusterSyncScheduler newScheduler(ClusterNetwork network,
                                                     ClusterSyncCollector collector,
                                                     PeriodicObservationConfig periodicConfig) {
        return ClusterSyncScheduler.clusterSyncScheduler(SELF,
                                                          network,
                                                          collector,
                                                          TimeSpan.timeSpan(1).seconds(),
                                                          () -> 7L,
                                                          HealthSignalSink.noop(),
                                                          ClusterSyncScheduler.DEFAULT_PING_TIMEOUT_THRESHOLD,
                                                          () -> Epoch.epoch(7L, 0L),
                                                          PeerObservationStore.peerObservationStore(),
                                                          periodicConfig);
    }

    private static PeriodicObservationConfig longPeriodicConfig() {
        // Long-period config so the scheduled task never fires during the
        // synchronous `emitPeriodicConnectivityNow` tests above.
        return new PeriodicObservationConfig(TimeSpan.timeSpan(60).seconds(),
                                              TimeSpan.timeSpan(180).seconds(),
                                              () -> 64);
    }

    private static void pollUntil(java.util.function.BooleanSupplier condition, long timeoutMs) {
        var deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {return;}
            sleepQuietly(25L);
        }
    }

    private static void sleepQuietly(long millis) {
        try {Thread.sleep(millis);} catch (InterruptedException ex) {Thread.currentThread().interrupt();}
    }

    /// Test double for `ClusterSyncCollector` that records every
    /// `emitPeriodicConnectivity` invocation for inspection.
    private static final class RecordingCollector implements ClusterSyncCollector {
        record Invocation(Set<NodeId> topology, Set<NodeId> connected, NodeId self, long nowMs) {}

        private final CopyOnWriteArrayList<Invocation> invocations = new CopyOnWriteArrayList<>();

        List<Invocation> invocations() {
            return List.copyOf(invocations);
        }

        @Override public void emitPeriodicConnectivity(Set<NodeId> topology, Set<NodeId> connected, NodeId self, long nowMs) {
            invocations.add(new Invocation(Set.copyOf(topology), Set.copyOf(connected), self, nowMs));
        }

        @Override public Map<String, Double> collectLocal() {return Map.of();}
        @Override public void recordCall(MethodName method, long durationMs) {}
        @Override public void recordCustom(String name, double value) {}
        @Override public void setInvocationMetricsProvider(InvocationMetricsCollector provider) {}
        @Override public Map<NodeId, Map<String, Double>> allMetrics() {return Map.of();}
        @Override public Map<String, Double> metricsFor(NodeId nodeId) {return Map.of();}
        @Override public Map<NodeId, List<MetricsSnapshot>> historicalMetrics() {return Map.of();}
        @Override public void removeNode(NodeId nodeId) {}
        @Override public void onMembershipDecision(MembershipDecision decision) {}
        @Override public void onClusterSyncPing(ClusterSyncPing ping) {}
        @Override public void onClusterSyncPong(ClusterSyncPong pong) {}
        @Override public long observedRabiaTerm() {return 0L;}
        @Override public Epoch observedEpoch() {return Epoch.ZERO;}
        @Override public List<CommunityReport> collectCommunityReports() {return List.of();}
        @Override public void setCommunityReportSupplier(Supplier<List<CommunityReport>> supplier) {}
        @Override public void addPongListener(Consumer<ClusterSyncPong> listener) {}
        @Override public void setPongSignalFan(ClusterSyncPongSignalFan fan) {}
        @Override public void setPeerObservationBuffer(PeerObservationBuffer buffer) {}
    }

    /// Network test double that returns a fixed connectedPeers snapshot — extends
    /// `NoopNetwork` to inherit no-op send/start/stop behavior.
    private static final class RecordingNetwork extends NoopNetwork {
        private final Set<NodeId> connected;

        RecordingNetwork(Set<NodeId> connected) {
            this.connected = Set.copyOf(connected);
        }

        @Override public Set<NodeId> connectedPeers() {
            return connected;
        }
    }
}
