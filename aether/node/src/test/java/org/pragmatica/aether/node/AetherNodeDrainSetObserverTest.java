// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.statemachine.FsmObserver;
import org.pragmatica.aether.node.assembly.DrainAssembly;

import static org.assertj.core.api.Assertions.assertThat;

/// #2014: the planned-departure mark keys on the DRAIN RECORD (the leader's broadcast set, or the leader's own
/// registry), never on a membership edge. It reaches an observer that never saw `DrainRequested`, survives a SWIM
/// incarnation refutation and a leader change while the drain is running, and goes away when the drain is not.
class AetherNodeDrainSetObserverTest {
    private static final NodeId SELF = NodeId.nodeId("observer").unwrap();
    private static final NodeId DRAINED = NodeId.nodeId("drained").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("other").unwrap();
    private static final Predicate<NodeId> NOT_READY = _ -> false;
    private static final Predicate<NodeId> READY = _ -> true;
    private static final Predicate<NodeId> NOT_DEPARTED = _ -> false;

    @SuppressWarnings("unchecked")
    private static AlertManager alerts() {
        return AlertManager.readOnly((KVStore<AetherKey, AetherValue>) Mockito.mock(KVStore.class));
    }

    private static void record(AlertManager alerts, long nowMs, Set<NodeId> set, Predicate<NodeId> running) {
        alerts.observeDrainSet(set, SELF, nowMs, running, NOT_DEPARTED);
    }

    @Test
    void drainSet_marksTheCommandedNodeOnAnObserverThatNeverSawDrainRequested() {
        var alerts = alerts();

        DrainAssembly.drainSetObserver(alerts, SELF, NOT_READY, NOT_DEPARTED).accept(Set.of(DRAINED));

        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isTrue();
        assertThat(alerts.hasAnnouncedDeparture(OTHER)).as("an uncommanded node stays unmarked: an unplanned kill must alert").isFalse();
    }

    @Test
    void drainSet_neverMarksANodeTheDepartedPredicateHolds_andMarksTheOthers() {
        var alerts = alerts();

        DrainAssembly.drainSetObserver(alerts, SELF, NOT_READY, DRAINED::equals).accept(Set.of(DRAINED, OTHER));

        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).as("its life is over on this observer").isFalse();
        assertThat(alerts.hasAnnouncedDeparture(OTHER)).isTrue();
    }

    @Test
    void drainSet_neverMarksSelf() {
        var alerts = alerts();

        DrainAssembly.drainSetObserver(alerts, SELF, NOT_READY, NOT_DEPARTED).accept(Set.of(SELF, DRAINED));

        assertThat(alerts.hasAnnouncedDeparture(SELF)).isFalse();
        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isTrue();
    }

    /// Cancelled drain: absent from the record past the grace and the node reports READY again. The mark clears
    /// and a later real crash alerts. Deleting the age-out turns this red.
    @Test
    void drainCancelled_absentPastTheGraceAndReadyAgain_markClears_laterCrashAlerts() {
        var alerts = alerts();

        record(alerts, 0L, Set.of(DRAINED), READY);
        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isTrue();
        record(alerts, 30_001L, Set.of(), READY);

        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).as("the cancelled drain's mark is gone").isFalse();
        alerts.onNodeFailed(DRAINED, SELF);
        assertThat(alerts.getActiveNodeHealthAlerts()).hasSize(1);
    }

    /// Leader change: the new leader's pings omit the drainee, still within the grace. The mark survives.
    /// Shrinking the grace to zero turns this red.
    @Test
    void leaderChange_emptySetWithinTheGrace_markSurvives() {
        var alerts = alerts();

        record(alerts, 0L, Set.of(DRAINED), READY);
        record(alerts, 29_999L, Set.of(), READY);

        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isTrue();
    }

    /// Run 3, T3: the drainee halted, the leader died, and the followers' DEAD edge came ~50 s after the last ping,
    /// long past the grace. A halted drainee reports nothing (not READY), so its mark must survive until the DEAD
    /// edge. Expiring on the clock alone (dropping the ready-again test) turns this red.
    @Test
    void drainedNodeHalted_neverReportsReady_markSurvivesPastTheGraceUntilTheDeadEdge() {
        var alerts = alerts();

        record(alerts, 0L, Set.of(DRAINED), NOT_READY);
        record(alerts, 120_000L, Set.of(), NOT_READY);

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
        var tick = DrainAssembly.drainSetObserver(alerts, SELF, NOT_READY, NOT_DEPARTED);

        tick.accept(Set.of(DRAINED));
        alerts.onNodeFailed(DRAINED, SELF);
        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).as("consumed by the DEAD edge").isFalse();
        tick.accept(Set.of(DRAINED));

        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isTrue();
    }

    /// The set cannot grow forever for a node that comes back: a mark consumed by the DEAD edge and re-created by a
    /// lingering record expires once the node is out of the record and READY again.
    @Test
    void departedNode_lingeringInPings_thenGone_leavesNoMark() {
        var alerts = alerts();

        record(alerts, 0L, Set.of(DRAINED), READY);
        alerts.onNodeFailed(DRAINED, SELF);
        record(alerts, 1_000L, Set.of(DRAINED), READY);
        record(alerts, 31_001L, Set.of(), READY);

        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isFalse();
    }

    @Test
    void nodeReadyAgain_isTrueOnlyForAReportedReady_absenceAndDrainingAreNot() {
        var ready = DrainAssembly.nodeReadyAgain(() -> Map.of(DRAINED, NodeReportedState.READY));
        var draining = DrainAssembly.nodeReadyAgain(() -> Map.of(DRAINED, NodeReportedState.DRAINING));
        var absent = DrainAssembly.nodeReadyAgain(Map::of);

        assertThat(ready.test(DRAINED)).isTrue();
        assertThat(draining.test(DRAINED)).isFalse();
        assertThat(absent.test(DRAINED)).as("absence from the readiness view is not evidence the drain is over").isFalse();
    }

    /// v-2043 N1: a node that is NOT the leader must not feed its own registry (an operator drain never leaves it, so a former
    /// leader would keep re-marking a node that restarted under the new leader). Removing the leadership gate turns this red.
    @Test
    void ownRegistryTick_notLeader_feedsNothing() {
        var fed = new java.util.concurrent.atomic.AtomicInteger();

        DrainAssembly.leaderOnlyDrainRecordTick(() -> false, () -> Set.of(DRAINED), _ -> fed.incrementAndGet()).run();

        assertThat(fed.get()).as("a non-leader's registry is not a drain record").isZero();
    }

    /// And the leader DOES feed the registry's actual targets (not an empty set, not a stale one). Feeding an empty set, or
    /// dropping the feed, turns this red; with the gate pinned above, the two together pin the whole tick.
    @Test
    void ownRegistryTick_leader_feedsTheRegistryTargets() {
        var seen = new java.util.concurrent.atomic.AtomicReference<Set<NodeId>>(null);

        DrainAssembly.leaderOnlyDrainRecordTick(() -> true, () -> Set.of(DRAINED), seen::set).run();

        assertThat(seen.get()).containsExactly(DRAINED);
    }

    /// Composition: a former leader's registry still names DRAINED; the gate keeps its alert manager unmarked, so the node's
    /// real crash alerts on this observer (the N1 chain end to end at unit level).
    @Test
    void formerLeader_withStaleRegistry_staysUnmarked_soTheNextCrashAlerts() {
        var alerts = alerts();
        var tick = DrainAssembly.leaderOnlyDrainRecordTick(() -> false,
                                                        () -> Set.of(DRAINED),
                                                        DrainAssembly.drainSetObserver(alerts, SELF, NOT_READY, NOT_DEPARTED));

        tick.run();
        alerts.onNodeFailed(DRAINED, SELF);

        assertThat(alerts.getActiveNodeHealthAlerts()).hasSize(1);
    }

    /// Failure input (d): a node that never reports READY again reaches DEAD. Its tracking entry must go with the DEAD edge;
    /// `ageOut` alone waits for a READY that a halted node never sends.
    @Test
    void nodeThatNeverReportsReady_reachingDead_isNoLongerTracked() {
        var alerts = alerts();

        alerts.observeDrainSet(Set.of(DRAINED), SELF, 0L, NOT_READY, NOT_DEPARTED);
        assertThat(alerts.trackedDrainNodes()).as("instrument check: the drain record created tracking").isEqualTo(1);

        alerts.onNodeFailed(DRAINED, SELF);

        assertThat(alerts.trackedDrainNodes()).isZero();
    }

    /// Tracking counts a node that holds only the announced-departure mark (a local `DrainRequested` edge, no broadcast record
    /// yet): the mark is tracking state too, and its DEAD edge removes it.
    @Test
    void trackedDrainNodes_countsANodeHoldingOnlyTheAnnouncedMark() {
        var alerts = alerts();

        alerts.noteMembershipTransition(DRAINED, "DrainRequested");

        assertThat(alerts.trackedDrainNodes()).isEqualTo(1);
        alerts.onNodeFailed(DRAINED, SELF);
        assertThat(alerts.trackedDrainNodes()).isZero();
    }

    /// A node whose only tracking is the broadcast timestamp is still tracked: the count is the union of both maps, so a timestamp
    /// that outlives its mark (the interleaving of a ping's mark-and-stamp with a DEAD edge's removal) is visible in the count, not
    /// hidden by it. No sequential call reaches that state (the stamp is only put while the mark exists, and every removal takes both),
    /// so the test plants the stamp through the field: the one place a test can stand for the race.
    @Test
    void trackedDrainNodes_countsANodeHoldingOnlyTheBroadcastTimestamp() throws ReflectiveOperationException {
        var alerts = alerts();

        plantBroadcastTimestampOnly(alerts, DRAINED);

        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).as("instrument: no announced-departure mark is held").isFalse();
        assertThat(alerts.trackedDrainNodes()).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private static void plantBroadcastTimestampOnly(AlertManager alerts, NodeId node) throws ReflectiveOperationException {
        var field = AlertManager.class.getDeclaredField("broadcastDrainSeen");

        field.setAccessible(true);
        ((Map<String, Long>) field.get(alerts)).put(node.id(), 0L);
    }

    /// Failure input (d), the churn sequence: many drained nodes die without reporting; three drains stay in flight. The
    /// tracked count is the in-flight drains, never the distinct ids ever seen.
    @Test
    void churn_leavesTrackingAtMostTheDrainsInFlight() {
        var alerts = alerts();
        var inFlight = Set.of(nodeNamed(0), nodeNamed(1), nodeNamed(2));

        IntStream.range(0, 50).forEach(i -> alerts.observeDrainSet(Set.of(nodeNamed(i)), SELF, i, NOT_READY, NOT_DEPARTED));
        IntStream.range(3, 50).forEach(i -> alerts.onNodeFailed(nodeNamed(i), SELF));

        assertThat(alerts.trackedDrainNodes()).isLessThanOrEqualTo(inFlight.size());
        inFlight.forEach(node -> assertThat(alerts.hasAnnouncedDeparture(node)).as("a drain in flight keeps its mark").isTrue());
    }

    /// A ping that still names the node after this observer's own DEAD edge (the leader clears its registry on its own, later
    /// edge) must not re-create the mark or its tracking: the departure consumer already ran, so nothing would consume it. The
    /// observer's FSM holding the node DEAD is what says so (#2315 F4: DEAD is no longer drain-ended evidence for the age-out).
    @Test
    void pingNamingTheNodeAfterTheDeadEdge_createsNoMark_whenTheFsmHoldsItDead() {
        var alerts = alerts();
        var fsm = fsmHoldingDead(DRAINED);
        var departed = DrainAssembly.nodeDead(fsm);

        alerts.observeDrainSet(Set.of(DRAINED), SELF, 0L, NOT_READY, NOT_DEPARTED);
        alerts.onNodeFailed(DRAINED, SELF);
        alerts.observeDrainSet(Set.of(DRAINED), SELF, 1L, NOT_READY, departed);

        assertThat(alerts.trackedDrainNodes()).as("the late ping created nothing").isZero();
        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isFalse();
    }

    /// The other half of the same guard: a node the FSM holds DEAD whose mark is still there (the departure notification has
    /// not run) keeps it, a ping naming it or not.
    @Test
    void markOfADeadNodeWhoseDepartureIsPending_survivesPingsAndTheOmissionGrace() {
        var alerts = alerts();
        var fsm = fsmHoldingDead(DRAINED);
        var departed = DrainAssembly.nodeDead(fsm);
        var ended = DrainAssembly.nodeReadyAgain(() -> Map.of()).or(DrainAssembly.nodeUntracked(fsm));

        alerts.observeDrainSet(Set.of(DRAINED), SELF, 0L, ended, NOT_DEPARTED);
        alerts.observeDrainSet(Set.of(DRAINED), SELF, 1L, ended, departed);
        alerts.observeDrainSet(Set.of(), SELF, 1L + AlertManager.DRAIN_OMISSION_GRACE_MS, ended, departed);

        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).as("pending until the notifier consumes it").isTrue();
    }

    @Test
    void nodeUntracked_isTrueOnlyForANodeTheFsmDoesNotTrack() {
        var fsm = fsmHoldingDead(DRAINED);

        assertThat(DrainAssembly.nodeUntracked(fsm).test(OTHER)).isTrue();
        assertThat(DrainAssembly.nodeUntracked(fsm).test(DRAINED)).as("a DEAD entry is kept, so it is tracked").isFalse();
    }

    @Test
    void nodeDead_isFalseForAMemberAndForAnUnknownNode() {
        var fsm = fsmHoldingDead(DRAINED);

        assertThat(DrainAssembly.nodeDead(fsm).test(OTHER)).isFalse();
        assertThat(DrainAssembly.nodeDead(fsm).test(DRAINED)).isTrue();
    }

    private static NodeId nodeNamed(int i) {
        return NodeId.nodeId("churn-" + i).unwrap();
    }

    private static MembershipFsm fsmHoldingDead(NodeId node) {
        var fsm = MembershipFsm.membershipFsm(FsmObserver.noop(),
                                              System::currentTimeMillis,
                                              Long.MAX_VALUE,
                                              TimeSpan.timeSpan(40).millis(),
                                              TimeSpan.timeSpan(30).seconds(),
                                              TimeSpan.timeSpan(30).seconds());

        fsm.onMemberDescriptor(NodeInfo.nodeInfo(node, new NodeAddress(node.id(), 6000), Map.of(NodeInfo.LABEL_ROLE, "core")));
        fsm.onSwimHealthy(node, 1L);
        fsm.onSwimDeparted(node, 2L);
        return fsm;
    }
}
