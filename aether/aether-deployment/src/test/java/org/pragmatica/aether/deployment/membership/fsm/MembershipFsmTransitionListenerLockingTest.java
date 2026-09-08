// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.statemachine.FsmObserver;
import org.pragmatica.statemachine.FsmTags;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// #929 — the transition listener must NOT run while a [`MembershipFsm$MemberTracking`] monitor is
/// held, and moving it out must not cost the ordering the monitor used to provide.
///
/// ## The defect these pin
///
/// `MemberTracking.dispatch` was `synchronized` and published to `transitionSink` while holding the
/// dispatching member's monitor. `AetherNode.onFsmTransition` → `propagateMemberCount` →
/// `MembershipFsm.strictCoreObservedMemberCount` walks the member map calling the `synchronized`
/// `isStrictCoreMember` on EVERY member — so the listener held one member's monitor and requested
/// all the others', in `ConcurrentHashMap` iteration order. One node's SWIM loop (`onSwimSuspect`)
/// and QUIC loop (`onLivenessGone`) dispatching on two different members each held one monitor and
/// wanted the other's. That order is a hash order, so lock ordering cannot repair it.
///
/// ## Why the probe forces the interleaving instead of hoping for it
///
/// The race needs a specific window: both threads must be inside their listener, past the state
/// change, before either finishes the member walk. A probe that just "runs the code twice"
/// concurrently passes against the broken code too, and would prove nothing. The barrier below
/// holds each thread at exactly that point until the other arrives, making the AB/BA cycle
/// deterministic — the pre-fix code deadlocks every run, the fixed code never does.
///
/// [`#oneMembersTransitions_stayTotallyOrdered_underConcurrentDispatch`] is the other half: moving
/// the fan-out out of the monitor is only correct if per-member ordering survives, which is what
/// the transition guard buys and what a bare "publish after release" would lose.
class MembershipFsmTransitionListenerLockingTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId A = new NodeId("node-a");
    private static final NodeId B = new NodeId("node-b");

    /// Ceiling on the whole probe. The fixed code finishes in milliseconds; the broken code never
    /// finishes at all, so this only decides how long a RED run takes.
    private static final long PROBE_BOUND_MS = 5_000L;
    /// Ceiling on the rendezvous itself. Distinguishes "the window was opened and nothing wedged"
    /// from "the probe never actually got both threads into position" — the second is scored as a
    /// broken instrument, not as a pass.
    private static final long RENDEZVOUS_BOUND_MS = 5_000L;

    /// Bound for the "did NOT finish" half of the leaf probe. Short on purpose: under the invariant
    /// the walker is BLOCKED, not slow, so waiting longer proves nothing — and the same test then
    /// proves the walker was merely blocked by joining it after the monitor is released.
    private static final long LEAF_PROBE_BOUND_MS = 500L;

    private static final int FLIP_THREADS = 4;
    private static final int FLIPS_PER_THREAD = 200;
    /// Deliberate widening of the publish window, so an unserialised fan-out loses the race often
    /// rather than almost never. 20 us x 1600 dispatches is ~30 ms of added test time under the
    /// guard, and near-certain detection without it.
    private static final long OBSERVATION_WINDOW_NANOS = 20_000L;

    /// The deadlock probe. Two threads enter `MembershipFsm` through the two production ingress
    /// points that collided in the field — SWIM `onSwimSuspect` and QUIC `onLivenessGone` — on two
    /// DIFFERENT members of ONE FSM. Each listener performs the same member walk
    /// `AetherNode.propagateMemberCount` performs, after both are in position.
    @Test
    void concurrentDispatchOnTwoMembers_doesNotDeadlock() {
        var fsm = MembershipFsm.membershipFsm();

        fsm.onSwimHealthy(A, 1L);
        fsm.onSwimHealthy(B, 1L);

        var rendezvous = new CyclicBarrier(2);
        var arrivals = new AtomicInteger();
        var rendezvousBroken = new AtomicBoolean(false);

        // Installed AFTER the promotions, so only the two probe transitions reach the barrier.
        fsm.onTransition(_ -> {
            arrivals.incrementAndGet();
            rendezvous(rendezvous, rendezvousBroken);
            // Shape-for-shape AetherNode.propagateMemberCount: acquires EVERY member's monitor.
            fsm.strictCoreObservedMemberCount(SELF);
        });

        var swimLoop = daemon("probe-929-swim", () -> fsm.onSwimSuspect(A, 2L));
        var quicLoop = daemon("probe-929-quic", () -> fsm.onLivenessGone(B));

        swimLoop.start();
        quicLoop.start();
        joinQuietly(swimLoop, PROBE_BOUND_MS);
        joinQuietly(quicLoop, PROBE_BOUND_MS);

        assertThat(arrivals).as("instrument check: both dispatches must have reached the listener, or "
                                + "the AB/BA window was never opened and this probe proves nothing")
                            .hasValue(2);
        assertThat(rendezvousBroken).as("instrument check: the rendezvous must have completed, so both "
                                        + "threads were inside their listener at the same moment")
                                    .isFalse();
        assertThat(ManagementFactory.getThreadMXBean().findDeadlockedThreads())
                .as("no thread may deadlock: MembershipFsm must not hold a per-member monitor across "
                    + "the transition listener, which walks every other member's monitor (#929)")
                .isNull();
        assertThat(swimLoop.isAlive()).as("the SWIM-side dispatch must have completed").isFalse();
        assertThat(quicLoop.isAlive()).as("the QUIC-side dispatch must have completed").isFalse();
    }

    /// Direct pin of the invariant: while the transition listener runs, ANOTHER thread must be able
    /// to acquire every `MemberTracking` monitor. `strictCoreMemberCount` is the cheapest full walk
    /// of them. Under the pre-#929 code the walker blocks on the dispatching member's monitor until
    /// the listener returns.
    ///
    /// The verdict is SNAPSHOTTED INSIDE the listener, and that is load-bearing. Asserting on the
    /// walker's flag after `onSwimSuspect` returns is satisfied by the wrong event: the walker is
    /// merely BLOCKED, and the instant the listener returns the monitor is released, the walk
    /// finishes, and a later assertion reads `true` for a walk that did not overlap the listener at
    /// all. A first cut of this pin did exactly that and stayed GREEN against the reintroduced
    /// defect while the deadlock probe went red.
    @Test
    void transitionListener_runsWithNoMemberTrackingMonitorHeld() {
        var fsm = MembershipFsm.membershipFsm();

        fsm.onSwimHealthy(A, 1L);
        fsm.onSwimHealthy(B, 1L);

        var listenerRan = new AtomicBoolean(false);
        var walkFinishedWhileListenerRan = new AtomicBoolean(false);

        fsm.onTransition(_ -> {
            listenerRan.set(true);
            var walkDone = new CountDownLatch(1);
            var walker = daemon("probe-929-walker", () -> {
                fsm.strictCoreMemberCount();
                walkDone.countDown();
            });

            walker.start();
            walkFinishedWhileListenerRan.set(awaitQuietly(walkDone, PROBE_BOUND_MS));
        });

        fsm.onSwimSuspect(A, 2L);

        assertThat(listenerRan).as("instrument check: the transition listener must have run at all")
                               .isTrue();
        assertThat(walkFinishedWhileListenerRan)
                .as("a second thread must complete a full member walk (which acquires EVERY "
                    + "MemberTracking monitor) WHILE the transition listener is still on the stack — "
                    + "i.e. no per-member monitor is held across transitionSink.accept (#929)")
                .isTrue();
    }

    /// The two properties the transition guard buys back, and the reason a bare "publish after the
    /// monitor is released" is NOT an acceptable fix.
    ///
    /// 1. ONE member's transitions stay totally ordered: consumers see a chain, never a pair swapped
    ///    by two threads racing to publish. Each record starts in the state its predecessor ended in.
    /// 2. A listener observes the member in exactly the state its record names — the atomicity the
    ///    old `synchronized dispatch` provided, and the one thing moving the fan-out could have cost.
    ///
    /// Property 2 is the sensitive one and is why this test does not rely on ordering alone: the
    /// window for an ordering inversion is a handful of instructions wide, so a guardless
    /// implementation can survive thousands of iterations by luck. Parking briefly inside the
    /// listener widens that window deliberately — an ordering assertion that could not realistically
    /// fail is not a pin. (Measured: with the guard removed and no park, all iterations passed.)
    @Test
    void oneMembersTransitions_stayOrderedAndMatchObservedState_underConcurrentDispatch() {
        var fsm = MembershipFsm.membershipFsm();

        fsm.onSwimHealthy(A, 1L);

        var records = Collections.<MembershipTransitionRecord> synchronizedList(new ArrayList<>());
        var staleObservations = new AtomicInteger();

        fsm.onTransition(record -> {
            LockSupport.parkNanos(OBSERVATION_WINDOW_NANOS);
            if (!record.toState().equals(fsm.memberStates().get(A))) {
                staleObservations.incrementAndGet();
            }
            records.add(record);
        });

        var incarnation = new AtomicLong(2L);
        var flippers = IntStream.range(0, FLIP_THREADS)
                                .mapToObj(index -> daemon("probe-929-flip-" + index,
                                                          () -> flip(fsm, incarnation)))
                                .toList();

        flippers.forEach(Thread::start);
        flippers.forEach(thread -> joinQuietly(thread, PROBE_BOUND_MS));

        assertThat(records).as("instrument check: the flippers must have produced transitions to order")
                           .isNotEmpty();
        assertThat(staleObservations)
                .as("while a listener runs, the member it names must not have moved on — no other "
                    + "thread may transition that member until the fan-out returns (#929)")
                .hasValue(0);

        var published = List.copyOf(records);

        for (var index = 1; index < published.size(); index++) {
            assertThat(published.get(index).fromState())
                    .as("published transition %d of one member must start where transition %d ended — "
                        + "the per-member transition guard serialises state change AND fan-out, so the "
                        + "published sequence chains (#929)", index, index - 1)
                    .isEqualTo(published.get(index - 1).toState());
        }
    }

    /// Verification round 1, BLOCKING finding. FIVE public ingresses reach `MemberTracking.dispatch`
    /// WITHOUT passing through `inTransition` — `onSwimUnknown`, `onPeerConnected`,
    /// `onJoinGraceExpired`, `onDrainRequested`, `onDownHysteresisMet`
    /// (`MembershipFsm.java:544/550/580/590/599`). For those five, the guard taken INSIDE `dispatch`
    /// is the ONLY serialisation that exists.
    ///
    /// [`#oneMembersTransitions_stayOrderedAndMatchObservedState_underConcurrentDispatch`] drives
    /// member A exclusively through `onSwimSuspect`/`onSwimHealthy`, which are both
    /// `inTransition`-wrapped and therefore still guarded when the guard is removed from `dispatch`
    /// alone. That is why removing it left the suite green — and that green was recorded as "the
    /// mutation landed somewhere that did not matter". It landed on the only lock protecting five
    /// public entry points; the label certified as harmless the single acquisition those paths rely
    /// on, which is a documented reason never to look again.
    ///
    /// This drives ONE guarded ingress (`onSwimHealthy`) against ONE direct-dispatch ingress
    /// (`onDrainRequested`) on the SAME member, so the guard inside `dispatch` is load-bearing and
    /// its removal is observable. MEMBER -> DEPARTING on the drain, DEPARTING -> MEMBER on a
    /// strictly-newer healthy incarnation, so both ingresses produce real transitions.
    @Test
    void aDirectDispatchIngress_isSerialisedAgainstAGuardedOne_onTheSameMember() {
        var fsm = MembershipFsm.membershipFsm();

        fsm.onSwimHealthy(A, 1L);

        var transitions = new AtomicInteger();
        var staleObservations = new AtomicInteger();

        fsm.onTransition(record -> {
            transitions.incrementAndGet();
            LockSupport.parkNanos(OBSERVATION_WINDOW_NANOS);
            if (!record.toState().equals(fsm.memberStates().get(A))) {
                staleObservations.incrementAndGet();
            }
        });

        var incarnation = new AtomicLong(2L);
        var guarded = daemon("probe-929-guarded-ingress", () -> {
            for (var i = 0; i < FLIPS_PER_THREAD; i++) {
                fsm.onSwimHealthy(A, incarnation.incrementAndGet());
            }
        });
        var direct = daemon("probe-929-direct-ingress", () -> {
            for (var i = 0; i < FLIPS_PER_THREAD; i++) {
                fsm.onDrainRequested(A);
            }
        });

        guarded.start();
        direct.start();
        joinQuietly(guarded, PROBE_BOUND_MS);
        joinQuietly(direct, PROBE_BOUND_MS);

        assertThat(transitions).as("instrument check: the two ingresses must have produced transitions "
                                   + "to observe, or this probe watches nothing")
                               .hasValueGreaterThan(0);
        assertThat(staleObservations)
                .as("a transition published by the DIRECT-dispatch ingress `onDrainRequested` must "
                    + "also find its member unmoved: `dispatch`'s own guard is the only serialisation "
                    + "those five public ingresses have (#929 verification round 1, BLOCKING)")
                .hasValue(0);
    }

    /// The PRECONDITION this whole fix rests on, made observable — and it is not the property the
    /// code comment originally claimed.
    ///
    /// "Lock order is always guard then monitor" is true and does not earn the safety: the fan-out
    /// still holds one member's guard while acquiring every OTHER member's monitor, which is exactly
    /// what `AetherNode.propagateMemberCount` does. What makes that acyclic is that **the per-member
    /// monitor is a LEAF — nothing acquired under it acquires anything else.**
    ///
    /// That property is breakable from OUTSIDE this class. [`MembershipFsm#membershipFsm`] overloads
    /// accept an explicit [`FsmObserver`], and `Fsm` invokes it inside the state transition, i.e.
    /// inside `applyEvent`, i.e. under the monitor. Production happens to wire `FsmObserver.noop()`,
    /// so today the invariant holds by accident of wiring rather than by construction. This test makes
    /// the constraint observable: **an observer runs under the per-member monitor, therefore an
    /// observer that takes any lock reintroduces the inversion this fix removes.**
    ///
    /// Read the green result as "the constraint is still real", not as "all is well". If someone moves
    /// observer invocation out from under the monitor, this test SHOULD go red and be rewritten.
    @Test
    void anObserverPassedToThePublicFactory_runsUnderThePerMemberMonitor_soItMustNotTakeLocks() {
        var fsmRef = new AtomicReference<MembershipFsm>();
        var observerRan = new AtomicBoolean(false);
        var walkFinishedWhileObserving = new AtomicBoolean(false);
        var walkDoneRef = new AtomicReference<CountDownLatch>();

        FsmObserver<MembershipState, MembershipEvent> observer = new FsmObserver<>() {
            @Override
            public void onTransition(FsmTags tags, MembershipState from, MembershipState to) {
                observerRan.set(true);
                var started = new CountDownLatch(1);
                var done = new CountDownLatch(1);

                walkDoneRef.set(done);
                var walker = daemon("probe-929-leaf-walker", () -> {
                    started.countDown();
                    fsmRef.get().strictCoreMemberCount();
                    done.countDown();
                });

                walker.start();
                awaitQuietly(started, PROBE_BOUND_MS);
                walkFinishedWhileObserving.set(awaitQuietly(done, LEAF_PROBE_BOUND_MS));
            }

            @Override
            public void onCasLost(FsmTags tags, MembershipState expected, MembershipState actual) {}

            @Override
            public void onEventIgnored(FsmTags tags, MembershipState state, MembershipEvent event) {}
        };

        var fsm = MembershipFsm.membershipFsm(observer, System::currentTimeMillis, Long.MAX_VALUE);

        fsmRef.set(fsm);
        fsm.onSwimHealthy(A, 1L);

        assertThat(observerRan).as("instrument check: the observer must have been invoked at all, or "
                                   + "this test observes nothing")
                               .isTrue();
        assertThat(walkFinishedWhileObserving)
                .as("an FsmObserver supplied through the public factory runs UNDER the per-member "
                    + "monitor, so a concurrent member walk cannot complete while it is on the stack. "
                    + "This is the leaf precondition the #929 fix depends on: an observer that takes "
                    + "any lock reintroduces the inversion")
                .isFalse();
        assertThat(awaitQuietly(walkDoneRef.get(), PROBE_BOUND_MS))
                .as("control: the walker must finish once the monitor is released — otherwise the "
                    + "assertion above is satisfied by a thread that never ran rather than by one that "
                    + "was blocked")
                .isTrue();
    }

    private static void flip(MembershipFsm fsm, AtomicLong incarnation) {
        for (var i = 0; i < FLIPS_PER_THREAD; i++) {
            fsm.onSwimSuspect(A, incarnation.incrementAndGet());
            fsm.onSwimHealthy(A, incarnation.incrementAndGet());
        }
    }

    private static Thread daemon(String name, Runnable body) {
        var thread = new Thread(body, name);

        thread.setDaemon(true);

        return thread;
    }

    private static boolean awaitQuietly(CountDownLatch latch, long boundMs) {
        try {
            return latch.await(boundMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return false;
        }
    }

    private static void joinQuietly(Thread thread, long boundMs) {
        try {
            thread.join(boundMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void rendezvous(CyclicBarrier barrier, AtomicBoolean broken) {
        try {
            barrier.await(RENDEZVOUS_BOUND_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            broken.set(true);
        } catch (BrokenBarrierException | TimeoutException e) {
            broken.set(true);
        }
    }
}
