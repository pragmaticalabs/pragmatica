// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// #2014: the planned-departure mark keys on the DRAIN RECORD (the leader's broadcast set, or the leader's own
/// registry), never on a membership edge. It reaches an observer that never saw `DrainRequested`, survives a SWIM
/// incarnation refutation and a leader change while the drain is running, and goes away when the drain is not.
class AetherNodeDrainSetObserverTest {
    private static final NodeId SELF = NodeId.nodeId("observer").unwrap();
    private static final NodeId DRAINED = NodeId.nodeId("drained").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("other").unwrap();
    private static final Predicate<NodeId> NOT_RUNNING = _ -> false;
    private static final Predicate<NodeId> RUNNING = _ -> true;

    @SuppressWarnings("unchecked")
    private static AlertManager alerts() {
        return AlertManager.readOnly((KVStore<AetherKey, AetherValue>) Mockito.mock(KVStore.class));
    }

    private static void record(AlertManager alerts, long nowMs, Set<NodeId> set, Predicate<NodeId> running) {
        alerts.observeDrainSet(set, SELF, nowMs, running);
    }

    @Test
    void drainSet_marksTheCommandedNodeOnAnObserverThatNeverSawDrainRequested() {
        var alerts = alerts();

        AetherNode.drainSetObserver(alerts, SELF, NOT_RUNNING).accept(Set.of(DRAINED));

        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isTrue();
        assertThat(alerts.hasAnnouncedDeparture(OTHER)).as("an uncommanded node stays unmarked: an unplanned kill must alert").isFalse();
    }

    @Test
    void drainSet_neverMarksSelf() {
        var alerts = alerts();

        AetherNode.drainSetObserver(alerts, SELF, NOT_RUNNING).accept(Set.of(SELF, DRAINED));

        assertThat(alerts.hasAnnouncedDeparture(SELF)).isFalse();
        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isTrue();
    }

    /// Cancelled drain: absent from the record past the grace, the drainee not draining, FSM not DEPARTING. The
    /// mark clears and a later real crash alerts. Deleting the age-out turns this red.
    @Test
    void drainCancelled_absentPastTheGraceAndNotRunning_markClears_laterCrashAlerts() {
        var alerts = alerts();

        record(alerts, 0L, Set.of(DRAINED), NOT_RUNNING);
        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isTrue();
        record(alerts, 30_001L, Set.of(), NOT_RUNNING);

        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).as("the cancelled drain's mark is gone").isFalse();
        alerts.onNodeFailed(DRAINED, SELF);
        assertThat(alerts.getActiveNodeHealthAlerts()).hasSize(1);
    }

    /// Leader change: the new leader's pings omit the drainee, still within the grace. The mark survives.
    /// Shrinking the grace to zero turns this red.
    @Test
    void leaderChange_emptySetWithinTheGrace_markSurvives() {
        var alerts = alerts();

        record(alerts, 0L, Set.of(DRAINED), NOT_RUNNING);
        record(alerts, 29_999L, Set.of(), NOT_RUNNING);

        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isTrue();
    }

    /// Run 2, T3: the drainee's DEAD edge reached two followers ~52 s after the drain started, long past the
    /// grace and with the leader's record already empty. While the drain is RUNNING (here: the observer's FSM
    /// holds the node DEPARTING) the mark must not expire. Replacing the in-progress probe with `false` turns
    /// this red.
    @Test
    void drainRunning_emptySetPastTheGrace_markSurvivesUntilTheDeadEdge() {
        var alerts = alerts();

        record(alerts, 0L, Set.of(DRAINED), RUNNING);
        record(alerts, 120_000L, Set.of(), RUNNING);

        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isTrue();
    }

    /// Run 2, T1: a SWIM incarnation refutation moved the leader's FSM DEPARTING to MEMBER while the drain was
    /// still commanded, and the old clear-on-edge dropped the leader's mark, so the leader raised CRITICAL. The
    /// mark must not key on that edge. Re-adding a clear on DEPARTING-to-MEMBER in the transition feed turns
    /// this red.
    @Test
    void incarnationRefutation_departingToMember_doesNotClearTheMark() {
        var alerts = alerts();

        alerts.noteMembershipTransition(DRAINED, "DrainRequested");
        alerts.noteMembershipTransition(DRAINED, "SwimHealthy");

        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isTrue();
    }

    /// The leader re-derives its mark from its own registry each tick, so a mark consumed or lost is restored
    /// while the drain is still commanded.
    @Test
    void leaderTick_restoresAConsumedMarkWhileTheDrainIsStillCommanded() {
        var alerts = alerts();
        var tick = AetherNode.drainSetObserver(alerts, SELF, NOT_RUNNING);

        tick.accept(Set.of(DRAINED));
        alerts.onNodeFailed(DRAINED, SELF);
        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).as("consumed by the DEAD edge").isFalse();
        tick.accept(Set.of(DRAINED));

        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isTrue();
    }

    /// The set cannot grow forever: a mark consumed by the DEAD edge and re-created by a lingering record
    /// expires once the node leaves the record and no drain runs.
    @Test
    void departedNode_lingeringInPings_thenGone_leavesNoMark() {
        var alerts = alerts();

        record(alerts, 0L, Set.of(DRAINED), NOT_RUNNING);
        alerts.onNodeFailed(DRAINED, SELF);
        record(alerts, 1_000L, Set.of(DRAINED), NOT_RUNNING);
        record(alerts, 31_001L, Set.of(), NOT_RUNNING);

        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isFalse();
    }

    @Test
    void drainInProgress_readsReportedDrainingOrAnFsmDeparting_notAnythingElse() {
        var draining = AetherNode.drainInProgress(() -> Map.of(DRAINED, NodeReportedState.DRAINING), Map::of);
        var departing = AetherNode.drainInProgress(Map::of, () -> Map.of(DRAINED, "Departing"));
        var neither = AetherNode.drainInProgress(() -> Map.of(DRAINED, NodeReportedState.READY), () -> Map.of(DRAINED, "Member"));

        assertThat(draining.test(DRAINED)).isTrue();
        assertThat(departing.test(DRAINED)).isTrue();
        assertThat(neither.test(DRAINED)).isFalse();
    }
}
