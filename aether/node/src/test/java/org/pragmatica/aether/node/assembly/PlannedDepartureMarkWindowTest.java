// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.api.NodeDepartureNotifier;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;

/// #3858 round 2, invariant 1: a planned-departure mark ends only when the life it was created for is over,
/// ordered against that life's DEAD edge; it is created only while that edge has not consumed it; it is dropped early only on
/// evidence that no DEAD edge of that life is pending or in flight; after the DEAD edge nothing re-creates it.
///
/// The real membership FSM, alert manager, departure notifier (wired as `MembershipAssembly:651-652` wires it) and the predicates
/// `DrainAssembly` composes. The FSM changes the state to Dead and runs the DEAD hooks after it (`MemberTracking.dispatch`, under
/// `transitionGuard`); a hook on `onConfirmedDeparture` stands for another thread acting inside that window, and a predicate wrapper
/// stands for a thread acting between the observer's read and its write. Each thread's own code is the production code; only the
/// interleaving is fixed, in one thread, in the order the report's path names.
///
/// Each failing input is two tests: an ENABLED tripwire asserting today's behaviour (it goes red when the behaviour changes, and its
/// message says what to enable), and the real assertion, `@Disabled` for lane B. A test without the `@Disabled` twin is a control.
///
/// Sites of the #3858 round 2 enumeration that no test in this class names, and why none can break the invariant [reading]:
/// - C1 size cap (R-c): at the cap a new mark is refused and nothing is dropped, so it can only cost a spurious CRITICAL, the
///   deliberate bias at `AlertManager#onNodeFailed`; it is no ordering against a DEAD edge. Marks leaked by R1 are what could fill it.
/// - R-b (`containsKey` before the stamp in `markFromBroadcast`): it creates nothing; a consumer that ran between the put and the
///   check leaves no stamp, and the re-creation after a consumer is R1, which the R1 tests reach.
/// - R-d (`trackedDrainNodes`): a read-only count with no main caller; the R1 tests use it as the instrument.
/// - D4 (field copies of the DEAD listeners, MembershipFsm:1405-1419, :1703-1705): plumbing; D2 and D3 are reached through the
///   notifier by every test here.
/// - F-a and F-b (the ping handler and the leader's record tick): both end in `observeDrainSet` with the predicates `DrainAssembly`
///   composes, which these tests call directly. [unverified: no test drives the real feeders or their threads]
/// - C2 inside a window: the transition listener is staged in the same dispatch as the DEAD hooks of its own node, under that node's
///   `transitionGuard`, so it cannot interleave with them; `c2Control_*` covers the only order left.
class PlannedDepartureMarkWindowTest {
    private static final NodeId SELF = new NodeId("observer-node");
    private static final NodeId NODE = new NodeId("drained");
    private static final long T0 = 1_000_000L;
    private static final long AFTER_THE_OMISSION_GRACE = T0 + AlertManager.DRAIN_OMISSION_GRACE_MS;
    private static final String LANE_B = "#3858 round 2 lane B";

    private record World(MembershipFsm fsm, AlertManager alerts, ClusterEventAggregator aggregator) {
        Predicate<NodeId> drainEnded() {
            return DrainAssembly.nodeReadyAgain(() -> Map.of()).or(DrainAssembly.nodeUntracked(fsm));
        }

        Predicate<NodeId> departed() {
            return DrainAssembly.nodeDead(fsm);
        }

        void tick(Set<NodeId> drainSet, long nowMs) {
            alerts.observeDrainSet(drainSet, SELF, nowMs, drainEnded(), departed());
        }

        int notifications() {
            return Mockito.mockingDetails(aggregator).getInvocations().size();
        }
    }

    private static World world(boolean everReachable) {
        var fsm = MembershipFsm.membershipFsm();
        var alerts = alertManager();
        var aggregator = Mockito.mock(ClusterEventAggregator.class);
        var notifier = NodeDepartureNotifier.nodeDepartureNotifier(aggregator, alerts, SELF, OperatorWarningSink.handingOffTo(_ -> {}));

        fsm.onReachableDeath(notifier::onConfirmedDeparture);
        fsm.onNeverReachableDeath(notifier::onNeverJoined);
        fsm.onTransition(record -> alerts.noteMembershipTransition(record.nodeId(), record.cause()));
        if (everReachable) {
            fsm.onSwimHealthy(NODE, 1L);
        } else {
            fsm.onPeerConnected(NODE);
        }

        return new World(fsm, alerts, aggregator);
    }

    @SuppressWarnings("unchecked")
    private static AlertManager alertManager() {
        return AlertManager.alertManager(Mockito.mock(RabiaNode.class), (KVStore<AetherKey, AetherValue>) Mockito.mock(KVStore.class));
    }

    private static String fixed(String finding, String realTest) {
        return "#3858 round 2 " + finding + " is fixed: delete this tripwire and enable " + realTest;
    }

    // ---- W1 (sites S1, D1, A1, A2, R-a): a directory scope change inside the DEAD window drops the mark ----

    private World w1Run() {
        var w = world(true);
        var windowRuns = new AtomicInteger();

        w.tick(Set.of(NODE), T0);
        assertThat(w.alerts().hasAnnouncedDeparture(NODE)).as("precondition: the drain record created the mark").isTrue();
        w.fsm().onConfirmedDeparture(departed -> {
            windowRuns.incrementAndGet();
            assertThat(w.fsm().memberStates()).as("instrument: inside the window").containsEntry(departed, "Dead");
            w.fsm().setTrackingEligibility(id -> !id.equals(departed));
            assertThat(w.fsm().memberStates()).as("instrument: the scope change dropped the node").doesNotContainKey(departed);
            w.tick(Set.of(), AFTER_THE_OMISSION_GRACE);
        });
        w.fsm().onSwimDeparted(NODE, 1L);
        assertThat(windowRuns.get()).as("instrument: the window hook ran once").isEqualTo(1);
        assertThat(w.notifications()).as("instrument: the notifier ran after the window").isPositive();

        return w;
    }

    /// Control, F4's behaviour: the same window and the age-out tick, no scope change. Green before and after the fix.
    @Test
    void w1Control_ageOutTickInsideTheWindow_keepsThePlannedDepartureMark() {
        var w = world(true);

        w.tick(Set.of(NODE), T0);
        w.fsm().onConfirmedDeparture(_ -> w.tick(Set.of(), AFTER_THE_OMISSION_GRACE));
        w.fsm().onSwimDeparted(NODE, 1L);

        assertThat(w.notifications()).as("instrument: the notifier ran").isPositive();
        assertThat(w.alerts().getActiveNodeHealthAlerts()).as("control: a planned departure raised CRITICAL").isEmpty();
    }

    /// Tripwire for W1: today the scope change plus the age-out tick drop the mark and the planned departure raises CRITICAL.
    @Test
    void w1Tripwire_scopeRetireInsideTheDeadWindow_todayRaisesCritical() {
        var w = w1Run();

        assertThat(w.alerts().getActiveNodeHealthAlerts()).as(fixed("W1", "w1_scopeRetireInsideTheDeadWindow_keepsThePlannedDepartureMark")).hasSize(1);
    }

    /// Red when: the age-out drops the mark before the notifier reads it, and the planned departure raises CRITICAL.
    @Disabled(LANE_B)
    @Test
    void w1_scopeRetireInsideTheDeadWindow_keepsThePlannedDepartureMark() {
        var w = w1Run();

        assertThat(w.alerts().getActiveNodeHealthAlerts()).as("W1: a planned departure raised CRITICAL").isEmpty();
    }

    // ---- W2 (sites C3, D1, R-a, `departed` = nodeDead): the first drain record inside the DEAD window is refused ----

    private World w2Run() {
        var w = world(true);

        w.fsm().onConfirmedDeparture(departed -> w.tick(Set.of(departed), T0));
        w.fsm().onSwimDeparted(NODE, 1L);
        assertThat(w.notifications()).as("instrument: the notifier ran").isPositive();

        return w;
    }

    /// Control: the first sighting before the death. Green before and after the fix.
    @Test
    void w2Control_firstSightingBeforeTheDeath_marksThePlannedDeparture() {
        var w = world(true);

        w.tick(Set.of(NODE), T0);
        w.fsm().onSwimDeparted(NODE, 1L);

        assertThat(w.notifications()).as("instrument: the notifier ran").isPositive();
        assertThat(w.alerts().getActiveNodeHealthAlerts()).as("control: a planned departure raised CRITICAL").isEmpty();
    }

    /// Tripwire for W2: today `departed` (state Dead) refuses the mark and the pending notification raises CRITICAL.
    @Test
    void w2Tripwire_firstSightingInsideTheDeadWindow_todayRaisesCritical() {
        var w = w2Run();

        assertThat(w.alerts().getActiveNodeHealthAlerts()).as(fixed("W2", "w2_firstSightingInsideTheDeadWindow_marksThePlannedDeparture")).hasSize(1);
    }

    /// Red when: `departed` refuses the mark, so the pending notification reads an unplanned departure and raises CRITICAL.
    @Disabled(LANE_B)
    @Test
    void w2_firstSightingInsideTheDeadWindow_marksThePlannedDeparture() {
        var w = w2Run();

        assertThat(w.alerts().getActiveNodeHealthAlerts()).as("W2: a drain record seen before the notification was refused; CRITICAL").isEmpty();
    }

    // ---- R1 (sites C1, C3, A2/A3, D1-D3, A1): a mark re-created after its consumer ran outlives the node ----

    private World r1Run(World w, Runnable deadEdge) {
        var raced = new AtomicBoolean();
        var dead = w.departed();
        Predicate<NodeId> racingDeparted = id -> {
            var answer = dead.test(id);

            if (id.equals(NODE) && raced.compareAndSet(false, true)) {
                deadEdge.run();
            }
            return answer;
        };

        w.tick(Set.of(NODE), T0);
        w.alerts().observeDrainSet(Set.of(NODE), SELF, T0 + 1, w.drainEnded(), racingDeparted);
        assertThat(raced.get()).as("instrument: the DEAD edge ran inside the tick").isTrue();
        assertThat(w.fsm().memberStates()).as("instrument: the node is DEAD").containsEntry(NODE, "Dead");
        w.tick(Set.of(NODE), T0 + 2);
        w.tick(Set.of(), T0 + 2 + AlertManager.DRAIN_OMISSION_GRACE_MS);

        return w;
    }

    private World r1ReachableRun() {
        var w = world(true);
        var run = r1Run(w, () -> w.fsm().onSwimDeparted(NODE, 1L));

        Mockito.verify(w.aggregator()).onAnnouncedDeparture(NODE);
        assertThat(w.alerts().getActiveNodeHealthAlerts()).as("instrument: the departure was planned").isEmpty();

        return run;
    }

    private World r1NeverReachableRun() {
        var w = world(false);

        return r1Run(w, () -> w.fsm().onJoinGraceExpired(NODE));
    }

    /// Tripwire for R1 through the reachable DEAD edge (consumer A2): today the mark and the stamp stay after the omission grace.
    @Test
    void r1Tripwire_departedReadBeforeTheReachableDeadEdge_todayLeaksTheMarkAndTheStamp() {
        var w = r1ReachableRun();
        var fix = fixed("R1", "r1_departedReadBeforeTheReachableDeadEdge_leaksNoMarkAndNoStamp");

        assertThat(w.alerts().hasAnnouncedDeparture(NODE)).as(fix).isTrue();
        assertThat(w.alerts().trackedDrainNodes()).as(fix).isEqualTo(1);
    }

    /// Red when: the mark and stamp re-created after their consumer ran outlive the node (a tracked DEAD node is never drain-ended,
    /// and no later DEAD edge comes).
    @Disabled(LANE_B)
    @Test
    void r1_departedReadBeforeTheReachableDeadEdge_leaksNoMarkAndNoStamp() {
        var w = r1ReachableRun();

        assertThat(w.alerts().hasAnnouncedDeparture(NODE)).as("R1: a mark re-created after its consumer ran outlives the node").isFalse();
        assertThat(w.alerts().trackedDrainNodes()).as("R1: tracking entries of a DEAD node after the omission grace").isZero();
    }

    /// Tripwire for R1 through the never-reachable DEAD edge (consumer A3, `onNeverJoined` -> `forgetDepartureMarks`): the same
    /// check-then-act, the other consumer.
    @Test
    void r1NeverReachableTripwire_departedReadBeforeTheNeverReachableDeadEdge_todayLeaksTheMarkAndTheStamp() {
        var w = r1NeverReachableRun();
        var fix = fixed("R1 (never-reachable consumer)", "r1NeverReachable_departedReadBeforeTheNeverReachableDeadEdge_leaksNoMarkAndNoStamp");

        assertThat(w.alerts().hasAnnouncedDeparture(NODE)).as(fix).isTrue();
        assertThat(w.alerts().trackedDrainNodes()).as(fix).isEqualTo(1);
    }

    /// Red when: a mark and stamp re-created after the never-reachable edge's consumer ran outlive the node.
    @Disabled(LANE_B)
    @Test
    void r1NeverReachable_departedReadBeforeTheNeverReachableDeadEdge_leaksNoMarkAndNoStamp() {
        var w = r1NeverReachableRun();

        assertThat(w.alerts().hasAnnouncedDeparture(NODE)).as("R1: a mark re-created after the never-reachable consumer ran outlives the node").isFalse();
        assertThat(w.alerts().trackedDrainNodes()).as("R1: tracking entries of a DEAD node after the omission grace").isZero();
    }

    // ---- A4 (site `forgetDepartureMarks` from the PeerJoined route, AetherNode): a drop inside the DEAD window ----

    private World a4Run() {
        var w = world(true);

        w.tick(Set.of(NODE), T0);
        w.fsm().onConfirmedDeparture(departed -> w.alerts().forgetDepartureMarks(departed));
        w.fsm().onSwimDeparted(NODE, 1L);
        assertThat(w.notifications()).as("instrument: the notifier ran").isPositive();

        return w;
    }

    /// Tripwire for A4: today a PeerJoined handshake that lands inside the window drops the mark, and the planned departure raises
    /// CRITICAL. The doc at `AlertManager#forgetDepartureMarks` calls the early end deliberate (the bias toward a spurious CRITICAL);
    /// [marked guess] whether the invariant admits it for an in-flight DEAD edge is lane B's ruling, and this test names the case.
    @Test
    void a4Tripwire_peerJoinedInsideTheDeadWindow_todayRaisesCritical() {
        var w = a4Run();

        assertThat(w.alerts().getActiveNodeHealthAlerts()).as(fixed("A4", "a4_peerJoinedInsideTheDeadWindow_keepsThePlannedDepartureMark")).hasSize(1);
    }

    /// Red when: the handshake's drop ends the mark of a life whose DEAD edge is already in flight.
    @Disabled(LANE_B)
    @Test
    void a4_peerJoinedInsideTheDeadWindow_keepsThePlannedDepartureMark() {
        var w = a4Run();

        assertThat(w.alerts().getActiveNodeHealthAlerts()).as("A4: a planned departure raised CRITICAL").isEmpty();
    }

    // ---- C2 (site `noteMembershipTransition` from the FSM transition listener, MembershipAssembly:298) ----

    /// Control, C2: the transition listener runs inside the dispatch of the node it names, under that node's `transitionGuard`, so it
    /// cannot interleave with that node's DEAD hooks; the only order left is a `DrainRequested` after the edge, and it creates no mark.
    /// Green before and after the fix: a DEAD node takes no `DrainRequested` transition.
    @Test
    void c2Control_drainRequestedAfterTheDeadEdge_createsNoMark() {
        var w = world(true);

        w.fsm().onSwimDeparted(NODE, 1L);
        w.fsm().onDrainRequested(NODE);

        assertThat(w.fsm().memberStates()).as("instrument: the node is DEAD").containsEntry(NODE, "Dead");
        assertThat(w.alerts().hasAnnouncedDeparture(NODE)).as("C2: a mark created after the consumer ran").isFalse();
        assertThat(w.alerts().trackedDrainNodes()).as("C2: tracking after the DEAD edge").isZero();
    }
}
