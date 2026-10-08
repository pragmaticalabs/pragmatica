// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// #2014: the leader's broadcast drain set must reach an observer that never saw `DrainRequested`, and it
/// marks the commanded nodes only. The mark is what lets the DEAD edge on a FOLLOWER read a planned
/// departure as one; the mark is held by the observer, so it survives a later leader change (the new
/// leader's pings carry an empty set and the observer is not invoked for it).
class AetherNodeDrainSetObserverTest {
    private static final NodeId SELF = NodeId.nodeId("observer").unwrap();
    private static final NodeId DRAINED = NodeId.nodeId("drained").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("other").unwrap();

    @SuppressWarnings("unchecked")
    private static AlertManager alerts() {
        return AlertManager.readOnly((KVStore<AetherKey, AetherValue>) Mockito.mock(KVStore.class));
    }

    @Test
    void drainSet_marksTheCommandedNodeOnAnObserverThatNeverSawDrainRequested() {
        var alerts = alerts();

        AetherNode.drainSetObserver(alerts, SELF).accept(Set.of(DRAINED));

        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isTrue();
        assertThat(alerts.hasAnnouncedDeparture(OTHER)).as("an uncommanded node stays unmarked: an unplanned kill must alert").isFalse();
    }

    @Test
    void drainSet_neverMarksSelf() {
        var alerts = alerts();

        AetherNode.drainSetObserver(alerts, SELF).accept(Set.of(SELF, DRAINED));

        assertThat(alerts.hasAnnouncedDeparture(SELF)).isFalse();
        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isTrue();
    }

    private static void blackholeAfter(AlertManager alerts, long nowMs, Set<NodeId> set) {
        alerts.observeDrainSet(set, SELF, nowMs);
    }

    /// The CTO's condition: drain started, then cancelled (the node stays, absent from the set), then the
    /// node dies unannounced: CRITICAL. Deleting the omission expiry in `observeDrainSet` turns this red.
    @Test
    void drainCancelled_nodeAbsentFromTheSetBeyondTheGrace_markClears_laterCrashAlerts() {
        var alerts = alerts();

        blackholeAfter(alerts, 0L, Set.of(DRAINED));
        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isTrue();
        blackholeAfter(alerts, 30_001L, Set.of());

        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).as("the cancelled drain's mark is gone").isFalse();
        alerts.onNodeFailed(DRAINED, SELF);
        assertThat(alerts.getActiveNodeHealthAlerts()).hasSize(1);
    }

    /// A leader change empties the drain set while the drainee is still draining: the mark must outlive
    /// the gap. Shrinking the grace to zero turns this red.
    @Test
    void leaderChange_emptySetWithinTheGrace_markSurvives() {
        var alerts = alerts();

        blackholeAfter(alerts, 0L, Set.of(DRAINED));
        blackholeAfter(alerts, 29_999L, Set.of());   // literal: 1 ms inside the 30 s grace, independent of the constant

        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isTrue();
    }

    /// A drain withdrawn by the FSM (DEPARTING back to MEMBER) clears the mark at once. Deleting the clear in
    /// `noteTransitionForAlerts` turns this red.
    @Test
    void drainWithdrawnByTheFsm_departingToMember_clearsTheMark() {
        var alerts = alerts();

        AetherNode.noteTransitionForAlerts(alerts, record("Member", "Departing", "DrainRequested"));
        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isTrue();
        AetherNode.noteTransitionForAlerts(alerts, record("Departing", "Member", "DrainUnacknowledged"));

        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isFalse();
    }

    /// The set cannot grow forever: a mark consumed by the DEAD edge and re-created by a lingering ping
    /// expires once the node leaves the set. Reverting the bookkeeping leaves the mark behind.
    @Test
    void departedNode_lingeringInPings_thenGone_leavesNoMark() {
        var alerts = alerts();

        blackholeAfter(alerts, 0L, Set.of(DRAINED));
        alerts.onNodeFailed(DRAINED, SELF);
        blackholeAfter(alerts, 1_000L, Set.of(DRAINED));
        blackholeAfter(alerts, 31_001L, Set.of());

        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isFalse();
    }

    private static org.pragmatica.aether.deployment.membership.fsm.MembershipTransitionRecord record(String from, String to, String cause) {
        return new org.pragmatica.aether.deployment.membership.fsm.MembershipTransitionRecord(DRAINED, from, to, cause, 1L, "", 0L);
    }
}
