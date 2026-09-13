// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.pragmatica.aether.deployment.membership.fsm.MembershipDeltaEdge;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.metrics.ClusterSyncPongSignalFan;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.metrics.NodeReportedStateHolder;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.FsmObserver;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// The #1054 wiring pin, at the REAL seam. `AetherNode.drainAcknowledgement` is what the production
/// membership FSM consults when a drain-initiated DEPARTING timeout expires. The FSM's own tests feed
/// it a hand-written predicate, so they cannot notice a rewire of `AetherNode` — reading the READY set
/// instead of DRAINING, or dropping the source altogether. This class pins the extracted method, both
/// on its own and composed with a real [MembershipFsm], so either regression goes red HERE.
class DrainAcknowledgementSeamTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId TARGET = new NodeId("node-target");

    private static final long NO_HINT_DECAY = Long.MAX_VALUE;
    private static final TimeSpan BACKSTOP = TimeSpan.timeSpan(40).millis();
    private static final TimeSpan FIRING_TIMEOUT = TimeSpan.timeSpan(80).millis();
    private static final TimeSpan LONG_JOIN_GRACE = TimeSpan.timeSpan(30).seconds();

    /// Self is not consensus-active, so the local holder reports SYNCING and never matches DRAINING —
    /// every acknowledgement below comes from the readiness view, which is what is under test.
    private static final NodeReportedStateHolder SELF_SYNCING = NodeReportedStateHolder.nodeReportedStateHolder(() -> false);

    @Test
    void drainAcknowledgement_targetReportingDraining_isAcknowledged() {
        var fan = readinessView(Map.of(TARGET, NodeReportedState.DRAINING));

        assertThat(AetherNode.drainAcknowledgement(fan, SELF_SYNCING, SELF)
                             .test(TARGET)).as("a target reporting DRAINING on its pong has acted on the DRAIN")
                                           .isTrue();
    }

    /// A READY target is serving: it has not acted on any DRAIN. Armed against the easiest wrong
    /// rewire — selecting the READY set — which would answer `true` here.
    @Test
    void drainAcknowledgement_targetReportingReady_isNotAcknowledged() {
        var fan = readinessView(Map.of(TARGET, NodeReportedState.READY));

        assertThat(AetherNode.drainAcknowledgement(fan, SELF_SYNCING, SELF)
                             .test(TARGET)).isFalse();
    }

    @Test
    void drainAcknowledgement_targetAbsentFromView_isNotAcknowledged() {
        assertThat(AetherNode.drainAcknowledgement(readinessView(Map.of()), SELF_SYNCING, SELF)
                             .test(TARGET)).isFalse();
    }

    /// The composed pin: a real FSM wired through the seam. The same drain, the same expiry — the
    /// target's reported state alone decides between reaping and withdrawing.
    @Test
    void wiredFsm_drainedTargetReportingDraining_terminalizesWithSingleRemovedDelta() {
        var view = new AtomicReference<Map<NodeId, NodeReportedState>>(Map.of(TARGET, NodeReportedState.DRAINING));
        var fsm = wiredFsm(view);
        var deltas = new ArrayList<MembershipDeltaEdge>();
        fsm.onMembershipDelta(deltas::add);

        fsm.onSwimHealthy(TARGET, 1L);
        fsm.onDrainRequested(TARGET);

        await().atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(fsm.memberStates()).containsEntry(TARGET, "Dead"));
        assertThat(deltas).filteredOn(edge -> edge.kind() == MembershipDeltaEdge.Kind.REMOVED)
                          .hasSize(1);
    }

    @Test
    void wiredFsm_drainedTargetStillReportingReady_isWithdrawnToMember() {
        var view = new AtomicReference<Map<NodeId, NodeReportedState>>(Map.of(TARGET, NodeReportedState.READY));
        var fsm = wiredFsm(view);
        var deltas = new ArrayList<MembershipDeltaEdge>();
        fsm.onMembershipDelta(deltas::add);

        fsm.onSwimHealthy(TARGET, 1L);
        fsm.onDrainRequested(TARGET);

        await().pollDelay(FIRING_TIMEOUT.millis() * 3, TimeUnit.MILLISECONDS)
               .atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(fsm.memberStates()).containsEntry(TARGET, "Member"));
        assertThat(deltas).filteredOn(edge -> edge.kind() == MembershipDeltaEdge.Kind.REMOVED)
                          .isEmpty();
    }

    private static MembershipFsm wiredFsm(AtomicReference<Map<NodeId, NodeReportedState>> view) {
        var fsm = MembershipFsm.membershipFsm(FsmObserver.noop(),
                                              System::currentTimeMillis,
                                              NO_HINT_DECAY,
                                              BACKSTOP,
                                              FIRING_TIMEOUT,
                                              LONG_JOIN_GRACE);

        fsm.drainAcknowledgementSource(AetherNode.drainAcknowledgement(liveReadinessView(view), SELF_SYNCING, SELF));

        return fsm;
    }

    private static ClusterSyncPongSignalFan readinessView(Map<NodeId, NodeReportedState> snapshot) {
        return liveReadinessView(new AtomicReference<>(snapshot));
    }

    /// Readiness view that serves a fixed snapshot. Only `readinessSnapshot` is read by the seam; the
    /// pong-recording half of the real fan is `ClusterSyncPongSignalFanTest`'s subject, not this one's.
    private static ClusterSyncPongSignalFan liveReadinessView(AtomicReference<Map<NodeId, NodeReportedState>> view) {
        return new ClusterSyncPongSignalFan() {
            @Override
            public void fan(ClusterSyncPong pong) {}

            @Override
            public void evict(NodeId nodeId) {}

            @Override
            public void sweepStale(long maxAgeNanos) {}

            @Override
            public Map<NodeId, NodeReportedState> readinessSnapshot() {
                return view.get();
            }

            @Override
            public void onStuckSyncing(Consumer<NodeId> callback) {}

            @Override
            public void warmedUp(BooleanSupplier guard) {}
        };
    }
}
