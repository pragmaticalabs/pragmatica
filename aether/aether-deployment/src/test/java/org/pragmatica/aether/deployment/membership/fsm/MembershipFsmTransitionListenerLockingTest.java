// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

    private static final int FLIP_THREADS = 4;
    private static final int FLIPS_PER_THREAD = 200;

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
    /// the listener returns — and the listener is waiting for the walker, so it never does.
    @Test
    void transitionListener_runsWithNoMemberTrackingMonitorHeld() {
        var fsm = MembershipFsm.membershipFsm();

        fsm.onSwimHealthy(A, 1L);
        fsm.onSwimHealthy(B, 1L);

        var listenerRan = new AtomicBoolean(false);
        var walkCompleted = new AtomicBoolean(false);

        fsm.onTransition(_ -> {
            listenerRan.set(true);
            var walker = daemon("probe-929-walker", () -> {
                fsm.strictCoreMemberCount();
                walkCompleted.set(true);
            });

            walker.start();
            joinQuietly(walker, PROBE_BOUND_MS);
        });

        fsm.onSwimSuspect(A, 2L);

        assertThat(listenerRan).as("instrument check: the transition listener must have run at all")
                               .isTrue();
        assertThat(walkCompleted).as("a second thread must complete a full member walk (which acquires "
                                     + "EVERY MemberTracking monitor) WHILE the transition listener is "
                                     + "running — i.e. no per-member monitor is held across "
                                     + "transitionSink.accept (#929)")
                                 .isTrue();
    }

    /// The property the transition guard buys back. Publishing after the monitor is released is only
    /// correct if ONE member's transitions stay totally ordered: the journal and every other consumer
    /// see a chain, never a pair swapped by two threads racing to publish. Each record must therefore
    /// start in the state its predecessor ended in.
    @Test
    void oneMembersTransitions_stayTotallyOrdered_underConcurrentDispatch() {
        var fsm = MembershipFsm.membershipFsm();

        fsm.onSwimHealthy(A, 1L);

        var records = Collections.<MembershipTransitionRecord> synchronizedList(new ArrayList<>());

        fsm.onTransition(records::add);

        var incarnation = new AtomicLong(2L);
        var flippers = IntStream.range(0, FLIP_THREADS)
                                .mapToObj(index -> daemon("probe-929-flip-" + index,
                                                          () -> flip(fsm, incarnation)))
                                .toList();

        flippers.forEach(Thread::start);
        flippers.forEach(thread -> joinQuietly(thread, PROBE_BOUND_MS));

        assertThat(records).as("instrument check: the flippers must have produced transitions to order")
                           .isNotEmpty();

        var published = List.copyOf(records);

        for (var index = 1; index < published.size(); index++) {
            assertThat(published.get(index).fromState())
                    .as("published transition %d of one member must start where transition %d ended — "
                        + "the per-member transition guard serialises state change AND fan-out, so the "
                        + "published sequence chains (#929)", index, index - 1)
                    .isEqualTo(published.get(index - 1).toState());
        }
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
