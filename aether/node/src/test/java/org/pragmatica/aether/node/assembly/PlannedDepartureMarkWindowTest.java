// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

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
/// Each failing input of the #3858 round 2 report is one test; a `*Control_*` test is the neighbouring input that was never broken.
///
/// Sites of the #3858 round 2 enumeration that no test in this class names, and why none can break the invariant [reading]:
/// - C1 size cap (R-c): at the cap a new mark is refused and nothing is dropped, so it can only cost a spurious CRITICAL, the
///   deliberate bias at `AlertManager#onNodeFailed`; it is no ordering against a DEAD edge. `DepartureMarksTest#atTheCap_*` pins it. Marks leaked by R1 are what could fill it.
/// - R-b (the stamp written after the mark in the old `markFromBroadcast`): gone. The mark and the stamp are written in one section
///   of `DepartureMarks`, ordered against the consumer's settle; `DepartureMarksTest` races the two.
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

    private record World(MembershipFsm fsm, AlertManager alerts, ClusterEventAggregator aggregator) {
        Predicate<NodeId> drainEnded() {
            return DrainAssembly.nodeReadyAgain(() -> Map.of()).or(DrainAssembly.nodeUntracked(fsm));
        }

        Predicate<NodeId> holdsDeadLife() {
            return DrainAssembly.nodeHoldsDeadLife(fsm);
        }

        void tick(Set<NodeId> drainSet, long nowMs) {
            alerts.observeDrainSet(drainSet, SELF, nowMs, drainEnded(), holdsDeadLife());
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

    /// Red when: the age-out drops the mark before the notifier reads it, and the planned departure raises CRITICAL.
    @Test
    void w1_scopeRetireInsideTheDeadWindow_keepsThePlannedDepartureMark() {
        var w = w1Run();

        assertThat(w.alerts().getActiveNodeHealthAlerts()).as("W1: a planned departure raised CRITICAL").isEmpty();
    }

    // ---- W2 (sites C3, D1, R-a, `departed` = nodeHoldsDeadLife): the first drain record inside the DEAD window is refused ----

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

    /// Red when: the predicate refuses the mark, so the pending notification reads an unplanned departure and raises CRITICAL.
    @Test
    void w2_firstSightingInsideTheDeadWindow_marksThePlannedDeparture() {
        var w = w2Run();

        assertThat(w.alerts().getActiveNodeHealthAlerts()).as("W2: a drain record seen before the notification was refused; CRITICAL").isEmpty();
    }

    // ---- R1 (sites C1, C3, A2/A3, D1-D3, A1): a mark re-created after its consumer ran outlives the node ----

    private World r1Run(World w, Runnable deadEdge) {
        w.tick(Set.of(NODE), T0);
        deadEdge.run();
        assertThat(w.fsm().memberStates()).as("instrument: the node is DEAD").containsEntry(NODE, "Dead");
        w.tick(Set.of(NODE), T0 + 1);
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

    /// Red when: the mark and stamp re-created after their consumer ran outlive the node (a tracked DEAD node is never drain-ended,
    /// and no later DEAD edge comes).
    @Test
    void r1_departedReadBeforeTheReachableDeadEdge_leaksNoMarkAndNoStamp() {
        var w = r1ReachableRun();

        assertThat(w.alerts().hasAnnouncedDeparture(NODE)).as("R1: a mark re-created after its consumer ran outlives the node").isFalse();
        assertThat(w.alerts().trackedDrainNodes()).as("R1: tracking entries of a DEAD node after the omission grace").isZero();
    }

    /// Red when: a mark and stamp re-created after the never-reachable edge's consumer ran outlive the node.
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
        w.fsm().onConfirmedDeparture(departed -> w.alerts().forgetDepartureMarks(departed, w.fsm()::noDeadEdgePending));
        w.fsm().onSwimDeparted(NODE, 1L);
        assertThat(w.notifications()).as("instrument: the notifier ran").isPositive();

        return w;
    }

    /// Red when: the handshake's drop ends the mark of a life whose DEAD edge is already in flight.
    @Test
    void a4_peerJoinedInsideTheDeadWindow_keepsThePlannedDepartureMark() {
        var w = a4Run();

        assertThat(w.alerts().getActiveNodeHealthAlerts()).as("A4: a planned departure raised CRITICAL").isEmpty();
    }

    /// Control, A4: a handshake from a node the FSM no longer tracks, with no edge in flight, ends the mark: nothing would consume it.
    @Test
    void a4Control_peerJoinedForAnUntrackedNode_endsTheMark() {
        var w = world(true);

        w.tick(Set.of(NODE), T0);
        w.fsm().setTrackingEligibility(id -> !id.equals(NODE));
        assertThat(w.fsm().noDeadEdgePending(NODE)).as("instrument: nothing is pending for an untracked node").isTrue();
        w.alerts().forgetDepartureMarks(NODE, w.fsm()::noDeadEdgePending);

        assertThat(w.alerts().hasAnnouncedDeparture(NODE)).as("control: the handshake ended the mark").isFalse();
    }

    /// A4 for a node the FSM still tracks and has not killed: the handshake proves nothing about its DEAD edge, so the mark stays and
    /// the edge that follows reads the planned departure.
    @Test
    void a4_peerJoinedForATrackedNode_keepsTheMarkForTheDeadEdgeToCome() {
        var w = world(true);

        w.tick(Set.of(NODE), T0);
        w.alerts().forgetDepartureMarks(NODE, w.fsm()::noDeadEdgePending);
        w.fsm().onSwimDeparted(NODE, 1L);

        assertThat(w.alerts().getActiveNodeHealthAlerts()).as("A4: a planned departure raised CRITICAL").isEmpty();
    }

    /// A4, the scope change inside the window: the roster no longer shows the node, but its dispatch has not finished running the
    /// hooks, so the FSM does not prove that no edge is pending and the handshake leaves the mark.
    @Test
    void a4_peerJoinedAfterAScopeRetireInsideTheDeadWindow_keepsThePlannedDepartureMark() {
        var w = world(true);

        w.tick(Set.of(NODE), T0);
        w.fsm().onConfirmedDeparture(departed -> {
            w.fsm().setTrackingEligibility(id -> !id.equals(departed));
            w.alerts().forgetDepartureMarks(departed, w.fsm()::noDeadEdgePending);
        });
        w.fsm().onSwimDeparted(NODE, 1L);
        assertThat(w.notifications()).as("instrument: the notifier ran").isPositive();

        assertThat(w.alerts().getActiveNodeHealthAlerts()).as("A4: a planned departure raised CRITICAL").isEmpty();
    }

    // ---- S1 (site `setTrackingEligibility`): the node leaves the roster only after its running dispatch finished ----

    /// A scope change on another thread waits for a dispatch of the node that is running its hooks (the tracking's guard), and the
    /// node stays on the roster while it waits: retire first, remove second. Removing first would show the node absent while its DEAD
    /// edge is still running.
    @Test
    void scopeRetire_waitsForTheRunningDispatch_beforeTheNodeLeavesTheRoster() throws InterruptedException {
        var w = world(true);
        var inHook = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        w.fsm().onConfirmedDeparture(_ -> holdHook(inHook, release));
        var dispatcher = new Thread(() -> w.fsm().onSwimDeparted(NODE, 1L));
        var scope = new Thread(() -> w.fsm().setTrackingEligibility(id -> !id.equals(NODE)));

        dispatcher.start();
        assertThat(inHook.await(10, TimeUnit.SECONDS)).as("instrument: the hook is running").isTrue();
        scope.start();
        awaitBlocked(scope);
        var rosterWhileWaiting = w.fsm().memberStates();
        release.countDown();
        dispatcher.join();
        scope.join();

        assertThat(rosterWhileWaiting).as("the node stays on the roster while its dispatch runs").containsKey(NODE);
        assertThat(w.fsm().memberStates()).as("the scope change took effect once the dispatch finished").doesNotContainKey(NODE);
    }

    private static void holdHook(CountDownLatch inHook, CountDownLatch release) {
        inHook.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitBlocked(Thread thread) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(thread.getState()).as("instrument: the scope change is waiting on the dispatch").isEqualTo(Thread.State.BLOCKED);
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
