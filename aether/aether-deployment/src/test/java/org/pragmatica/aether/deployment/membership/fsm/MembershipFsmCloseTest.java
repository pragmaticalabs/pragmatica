// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.FsmObserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.testing.PollUntil.pollUntil;

/// #3557 — [`MembershipFsm#close`] cancels every per-member timer (the join grace, the eviction backstop, the DEPARTING
/// timeout and the rejoin re-offer) and turns a task the scheduler already handed to a thread into a no-op.
///
/// Two defences, each pinned by its own group of tests, so that dropping either goes red in its own group:
/// - the cancel: `*_cancelsThe*` capture the held future ([`MembershipFsm#heldTimerFutures`]) before the close and
///   assert it `isCancelled()` after it; dropping a cancel call in `MemberTracking#retire`, or only the
///   `future.cancel(false)` inside it while the handle is still cleared, leaves that future uncancelled. The count
///   ([`MembershipFsm#pendingTimerCount`]) alone cannot see the second: it reads the handle, which the cancel clears;
/// - the closed flag each task reads first: `*_dequeuedTaskIsANoOp` close, then run the tasks the way a thread that
///   had already dequeued them would ([`MembershipFsm#runDequeuedTimerTasks`]), because `cancel(false)` cannot stop
///   such a task; dropping the flag lets the task act on the closed FSM.
///
/// The arm-time `retired` checks are a second line behind the guard of the route that reaches each arm: the dispatch
/// guard for the DEPARTING timeout, the transition guard for the eviction backstop, the reaper's own flag for the
/// join-grace re-arm, and the transition and dispatch guards together for the join-grace re-arm of a fenced rejoin.
/// `*AfterClose_arms*` drive each route after the close and read the count, so dropping a route guard
/// together with its arm-time check goes red there.
///
/// `joinGraceDeferralLoop_stopsAtClose` replays the leak itself: a member that is OBSERVED with a live transport link is
/// deferred and re-armed every window. The reaper's eligibility predicate is the first thing every firing reads, one
/// statement before the DEFERRED log line, so its call count is the firing count.
class MembershipFsmCloseTest {
    private static final NodeId MEMBER = new NodeId("c3557-1");
    private static final TimeSpan SHORT_WINDOW = TimeSpan.timeSpan(40).millis();
    private static final TimeSpan SHORT_DEPARTURE = TimeSpan.timeSpan(150).millis();
    private static final TimeSpan LONG_WINDOW = TimeSpan.timeSpan(30).seconds();
    private static final long NO_HINT_DECAY = Long.MAX_VALUE;
    private static final Duration TWO_WINDOWS_AND_MORE = Duration.ofMillis(300);
    private static final Duration DEAD_DEADLINE = Duration.ofSeconds(10);

    private final Set<NodeId> reporting = ConcurrentHashMap.newKeySet();

    // --- join grace ---

    @Test
    void joinGraceDeferralLoop_stopsAtClose() {
        var firings = new AtomicInteger();
        var fsm = fsm(LONG_WINDOW, LONG_WINDOW, SHORT_WINDOW);

        fsm.setJoinGraceReapEligibility(_ -> firings.incrementAndGet() > 0);
        fsm.onPeerConnected(MEMBER);
        await().atMost(5, TimeUnit.SECONDS)
               .until(() -> firings.get() >= 3);

        fsm.close();

        var atClose = firings.get();

        assertThat(fsm.pendingTimerCount()).isZero();
        await().during(TWO_WINDOWS_AND_MORE)
               .atMost(5, TimeUnit.SECONDS)
               .until(() -> firings.get() == atClose);
        assertThat(fsm.memberStates()).containsEntry(MEMBER, "Observed");
    }

    @Test
    void close_cancelsTheJoinGrace() {
        var fsm = fsm(LONG_WINDOW, LONG_WINDOW, LONG_WINDOW);

        fsm.onPeerConnected(MEMBER);

        assertThat(fsm.pendingTimerCount()).as("the join grace is armed on first observation").isEqualTo(1);

        closeCancelsTheOneHeldTimer(fsm);
    }

    @Test
    void joinGrace_dequeuedTaskIsANoOp() {
        var fsm = fsm(LONG_WINDOW, LONG_WINDOW, LONG_WINDOW);

        fsm.onPeerConnected(MEMBER);
        fsm.onPeerDisconnected(MEMBER);
        assertThat(fsm.memberStates()).containsEntry(MEMBER, "Observed");
        fsm.close();
        fsm.runDequeuedTimerTasks();

        assertThat(fsm.memberStates()).as("a ghost with no transport link would be reaped to Dead by a live reaper")
                                      .containsEntry(MEMBER, "Observed");
    }

    // --- eviction backstop ---

    @Test
    void close_cancelsTheEvictionBackstop() {
        var fsm = coConfirmedDeadSuspect();

        assertThat(fsm.pendingTimerCount()).as("the backstop is armed").isEqualTo(1);

        closeCancelsTheOneHeldTimer(fsm);
    }

    @Test
    void evictionBackstop_dequeuedTaskIsANoOp() {
        var fsm = coConfirmedDeadSuspect();

        fsm.close();
        fsm.runDequeuedTimerTasks();

        assertThat(fsm.memberStates()).as("a live backstop would march the co-confirmed suspect to Dead")
                                      .containsEntry(MEMBER, "Suspect");
    }

    // --- DEPARTING timeout ---

    @Test
    void close_cancelsTheDepartureTimeout() {
        var fsm = departing(LONG_WINDOW);

        assertThat(fsm.pendingTimerCount()).as("the DEPARTING timeout is armed").isEqualTo(1);

        closeCancelsTheOneHeldTimer(fsm);
    }

    @Test
    void departureTimeout_dequeuedTaskIsANoOp() {
        var fsm = departing(LONG_WINDOW);

        fsm.close();
        fsm.runDequeuedTimerTasks();

        assertThat(fsm.memberStates()).as("a live timeout would terminalize the DEPARTING member to Dead")
                                      .containsEntry(MEMBER, "Departing");
    }

    // --- rejoin re-offer ---

    @Test
    void close_cancelsTheRejoinReoffer() {
        var fsm = deadWithRefusedRejoin();

        assertThat(fsm.pendingTimerCount()).as("the re-offer is scheduled").isEqualTo(1);

        closeCancelsTheOneHeldTimer(fsm);
    }

    @Test
    void rejoinReoffer_dequeuedTaskIsANoOp() {
        var fsm = deadWithRefusedRejoin();

        reporting.remove(MEMBER);
        fsm.close();
        fsm.runDequeuedTimerTasks();

        assertThat(fsm.memberStates()).as("a live re-offer would reopen the DEAD identity")
                                      .containsEntry(MEMBER, "Dead");
        assertThat(fsm.pendingTimerCount()).as("and nothing is re-armed").isZero();
    }

    // --- after close ---

    @Test
    void trackingCreatedAfterClose_armsNothing() {
        var fsm = fsm(LONG_WINDOW, LONG_WINDOW, LONG_WINDOW);

        fsm.close();
        fsm.onPeerConnected(MEMBER);

        assertThat(fsm.pendingTimerCount()).isZero();
    }

    @Test
    void departureAfterClose_armsNoDepartureTimeout() {
        var fsm = fsm(LONG_WINDOW, LONG_WINDOW, LONG_WINDOW);

        fsm.onSwimHealthy(MEMBER, 1L);
        fsm.onSwimSuspect(MEMBER, 1L);
        assertThat(fsm.memberStates()).containsEntry(MEMBER, "Suspect");
        fsm.close();
        fsm.onDownHysteresisMet(MEMBER);

        assertThat(fsm.pendingTimerCount()).as("the dispatch guard, then the arm-time check, keep the timeout unarmed")
                                           .isZero();
    }

    @Test
    void livenessGoneAfterClose_armsNoEvictionBackstop() {
        var fsm = fsm(LONG_WINDOW, LONG_WINDOW, LONG_WINDOW);

        fsm.onSwimHealthy(MEMBER, 1L);
        fsm.onSwimFaulty(MEMBER, 1L);
        assertThat(fsm.pendingTimerCount()).as("SWIM-FAULTY alone is not co-confirmation").isZero();
        fsm.close();
        fsm.onLivenessGone(MEMBER);

        assertThat(fsm.pendingTimerCount()).as("the transition guard, then the arm-time check, keep the backstop unarmed")
                                           .isZero();
    }

    @Test
    void deferredJoinGraceAfterClose_armsNoReaper() {
        var fsm = fsm(LONG_WINDOW, LONG_WINDOW, LONG_WINDOW);

        fsm.onPeerConnected(MEMBER);
        fsm.close();
        fsm.runDequeuedTimerTasks();

        assertThat(fsm.pendingTimerCount()).as("a reaper deferred on a live link re-arms; the flag, then the arm-time check, stop it")
                                           .isZero();
    }

    @Test
    void close_isIdempotent() {
        var fsm = fsm(LONG_WINDOW, LONG_WINDOW, LONG_WINDOW);

        fsm.onPeerConnected(MEMBER);
        fsm.close();
        fsm.close();

        assertThat(fsm.pendingTimerCount()).isZero();
    }

    @Test
    void rejoinAfterClose_armsNoJoinGrace() {
        var fsm = departing(SHORT_DEPARTURE);

        pollUntil(() -> fsm.memberStates()
                           .get(MEMBER),
                  "Dead"::equals,
                  DEAD_DEADLINE).assertMet("the DEPARTING timeout terminalizes the member to Dead");
        fsm.close();
        fsm.onSwimHealthy(MEMBER, 2L);

        assertThat(fsm.memberStates()).as("a live rejoin would move the DEAD identity to Observed")
                                      .containsEntry(MEMBER, "Dead");
        assertThat(fsm.pendingTimerCount()).as("the transition guard, the dispatch guard, then the arm-time check, keep the re-armed reaper unarmed")
                                           .isZero();
    }

    // --- the count itself ---

    @Test
    void firedTimer_isNotCountedAsArmed() {
        var fsm = fsm(LONG_WINDOW, LONG_WINDOW, SHORT_WINDOW);

        fsm.setJoinGraceReapEligibility(_ -> false);
        fsm.onPeerConnected(MEMBER);

        var held = fsm.heldTimerFutures();

        assertThat(held).as("the reaper is held").hasSize(1);
        pollUntil(() -> held.getFirst()
                            .isDone(),
                  Boolean::booleanValue,
                  DEAD_DEADLINE).assertMet("the reaper fires");

        assertThat(fsm.heldTimerFutures()).as("an ineligible firing leaves its handle held").hasSize(1);
        assertThat(fsm.pendingTimerCount()).as("a fired timer is not armed").isZero();
    }

    // --- arrangements ---

    /// Captures the one held timer future, closes, and asserts the future itself is cancelled (not only its handle
    /// cleared) and that nothing is counted as armed afterwards.
    private static void closeCancelsTheOneHeldTimer(MembershipFsm fsm) {
        var held = fsm.heldTimerFutures();

        assertThat(held).as("exactly one timer future is held").hasSize(1);

        fsm.close();

        assertThat(held).as("close cancels the future, not only the handle that holds it")
                        .allMatch(Future::isCancelled);
        assertThat(fsm.pendingTimerCount()).isZero();
    }

    private MembershipFsm coConfirmedDeadSuspect() {
        var fsm = fsm(LONG_WINDOW, LONG_WINDOW, LONG_WINDOW);

        fsm.onSwimHealthy(MEMBER, 1L);
        fsm.onSwimFaulty(MEMBER, 1L);
        fsm.onLivenessGone(MEMBER);
        assertThat(fsm.memberStates()).containsEntry(MEMBER, "Suspect");

        return fsm;
    }

    private MembershipFsm departing(TimeSpan departureTimeout) {
        var fsm = fsm(LONG_WINDOW, departureTimeout, LONG_WINDOW);

        fsm.onSwimHealthy(MEMBER, 1L);
        fsm.onSwimSuspect(MEMBER, 1L);
        fsm.onDownHysteresisMet(MEMBER);
        assertThat(fsm.memberStates()).containsEntry(MEMBER, "Departing");

        return fsm;
    }

    /// The #3028 S3 shape: DEAD, then a newer incarnation refused because the member reports quorum loss, which
    /// schedules the re-offer.
    private MembershipFsm deadWithRefusedRejoin() {
        var fsm = departing(SHORT_DEPARTURE);

        reporting.add(MEMBER);
        fsm.onSwimHealthy(MEMBER, 2L);
        pollUntil(() -> fsm.memberStates()
                           .get(MEMBER),
                  "Dead"::equals,
                  DEAD_DEADLINE).assertMet("the DEPARTING timeout terminalizes the refusing member to Dead");
        fsm.onSwimHealthy(MEMBER, 3L);

        return fsm;
    }

    private MembershipFsm fsm(TimeSpan evictionBackstop, TimeSpan departureTimeout, TimeSpan joinGrace) {
        var fsm = MembershipFsm.membershipFsm(FsmObserver.noop(),
                                              System::currentTimeMillis,
                                              NO_HINT_DECAY,
                                              evictionBackstop,
                                              departureTimeout,
                                              joinGrace);

        fsm.setQuorumLossReport(reporting::contains);

        return fsm;
    }
}
