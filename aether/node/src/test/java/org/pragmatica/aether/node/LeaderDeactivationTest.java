// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.testlogging.LogCapture;
import org.pragmatica.utility.react.ReactExecutor;
import org.pragmatica.utility.react.ReactFailureSink;
import org.pragmatica.utility.react.ReactGate;

import static org.assertj.core.api.Assertions.assertThat;

/// #2688: the wait `AetherNode.stop()` puts between routing PASSIVE and quiescing the router, on a real serial react executor. The
/// deactivation is keyed to its delivery, so a `LeaderChange` that a concurrent leader-FSM dispatch enqueues AFTER the wait was taken
/// is still awaited (a FIFO marker alone resolves before it), and the wait is bounded.
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class LeaderDeactivationTest {
    private static final TimeSpan LONG_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();
    private static final LeaderNotification.LeaderChange SELF = LeaderNotification.leaderChange(Option.some(NodeId.nodeId("self")
                                                                                                                  .unwrap()),
                                                                                                true);
    private static final LeaderNotification.LeaderChange NONE = LeaderNotification.leaderChange(Option.none(), false);

    private final ReactExecutor executor = ReactExecutor.reactExecutor("LeaderManager",
                                                                       ReactGate.reactGate(() -> true, () -> false),
                                                                       ReactFailureSink.loggingOnly());
    private final LeaderDeactivation deactivation = LeaderDeactivation.leaderDeactivation();

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void delivered_whenNeverLeader_resolvesOnceTheEarlierReactionsRan() throws InterruptedException {
        var release = hold();
        var delivered = deactivation.delivered(executor, LONG_BOUND);

        assertThat(delivered.isResolved()).as("held behind the blocked reaction").isFalse();
        release.countDown();
        assertThat(delivered.await(AWAIT).isSuccess()).isTrue();
    }

    /// The residual race of #2688: the leader FSM's change is enqueued after the wait was taken (the concurrent dispatch won the CAS and
    /// was still before its entry action). A FIFO marker alone has already resolved by then.
    @Test
    void delivered_whileLeader_waitsForADeactivationEnqueuedAfterTheWaitWasTaken() {
        deactivation.onLeaderChange(SELF);
        var delivered = deactivation.delivered(executor, LONG_BOUND);

        assertThat(executor.drained().await(AWAIT).isSuccess()).as("every reaction queued before the wait has run").isTrue();
        assertThat(delivered.isResolved()).as("no deactivation delivered yet").isFalse();

        executor.undo("LeaderChange(other)", () -> deactivation.onLeaderChange(NONE));

        assertThat(delivered.await(AWAIT).isSuccess()).as("the late deactivation is awaited").isTrue();
    }

    /// The remaining residual of #2688: a LeaderChange(self) is queued, undelivered, behind a held reaction when the wait is taken (so the
    /// node is not yet recorded as leader), and its deactivation is enqueued after the wait's marker. The wait must still cover it.
    @Test
    void delivered_whenLeadershipIsQueuedUndelivered_waitsForTheDeactivationEnqueuedAfterTheMarker() throws InterruptedException {
        var release = hold();

        executor.undo("LeaderChange(self)", () -> deactivation.onLeaderChange(SELF));
        var delivered = deactivation.delivered(executor, LONG_BOUND);

        release.countDown();
        assertThat(executor.drained().await(AWAIT).isSuccess()).as("the queued leadership and the earlier reactions ran").isTrue();
        assertThat(delivered.isResolved()).as("leadership delivered, deactivation not yet").isFalse();

        executor.undo("LeaderChange(other)", () -> deactivation.onLeaderChange(NONE));

        assertThat(delivered.await(AWAIT).isSuccess()).as("the deactivation enqueued after the marker is awaited").isTrue();
    }

    /// The deactivation is recorded on the route the toggles share, and toggles registered after the record still run in that route.
    @Test
    void delivered_waitsForTheRestOfTheRouteThatDeliveredTheDeactivation() throws InterruptedException {
        var recorded = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        deactivation.onLeaderChange(SELF);
        var delivered = deactivation.delivered(executor, LONG_BOUND);

        executor.undo("LeaderChange(other)", () -> deliverThenHold(recorded, release));
        assertThat(recorded.await(10, TimeUnit.SECONDS)).as("the deactivation was recorded").isTrue();
        assertThat(delivered.await(TimeSpan.timeSpan(300).millis()).isSuccess()).as("the route that recorded it is still running")
                                                                                  .isFalse();

        release.countDown();
        assertThat(delivered.await(AWAIT).isSuccess()).isTrue();
    }

    /// A second leadership delivery keeps the pending deactivation, so a wait taken before it is not orphaned.
    @Test
    void delivered_takenBeforeARepeatedLeadershipDelivery_resolvesWithTheDeactivation() {
        deactivation.onLeaderChange(SELF);
        var delivered = deactivation.delivered(executor, LONG_BOUND);

        deactivation.onLeaderChange(SELF);
        deactivation.onLeaderChange(NONE);

        assertThat(delivered.await(AWAIT).isSuccess()).isTrue();
    }

    @Test
    void delivered_afterLeadershipIsRegained_waitsForTheNextDeactivation() {
        deactivation.onLeaderChange(SELF);
        deactivation.onLeaderChange(NONE);
        deactivation.onLeaderChange(SELF);
        var delivered = deactivation.delivered(executor, LONG_BOUND);

        assertThat(delivered.await(TimeSpan.timeSpan(300).millis()).isSuccess()).as("leader again: nothing delivered yet").isFalse();

        deactivation.onLeaderChange(NONE);

        assertThat(delivered.await(AWAIT).isSuccess()).isTrue();
    }

    /// F2: a wedged `LeaderManager` reaction cannot hold the stop. Past the bound the wait warns and resolves.
    @Test
    void delivered_pastTheBound_warnsAndResolves() throws InterruptedException {
        try (var capture = LogCapture.capture(LeaderDeactivation.class, Level.WARN, false)) {
            var release = hold();

            deactivation.onLeaderChange(SELF);
            var started = System.nanoTime();
            var delivered = deactivation.delivered(executor, TimeSpan.timeSpan(200).millis());
            var outcome = delivered.await(AWAIT);
            var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            release.countDown();
            assertThat(outcome.isSuccess()).as("resolved, not failed, at the bound").isTrue();
            assertThat(elapsedMs).as("within the bound plus scheduling slack").isLessThan(5_000L);
            assertThat(capture.messages(Level.WARN)).as("the undelivered deactivation is reported")
                                                    .anySatisfy(message -> assertThat(message).contains("Leader deactivation not delivered on LeaderManager"));
        }
    }

    /// The bound the node's stop uses, pinned by value (A10: an inflated bound would hold a stop for minutes behind a wedged reaction).
    @Test
    void theStopsBound_isTwoSeconds() {
        assertThat(AetherNode.LEADER_DEACTIVATION_BOUND).isEqualTo(TimeSpan.timeSpan(2).seconds());
    }

    /// A10, on a manual clock (no waiting): with the executor held, the wait armed with the node's bound is still pending one millisecond before
    /// the bound and resolved, with the warning, at it.
    @Test
    void delivered_withTheNodesBound_resolvesAtTheBoundOnAHeldExecutor() throws InterruptedException {
        try (var capture = LogCapture.capture(LeaderDeactivation.class, Level.WARN, false)) {
            var clock = new ManualDeadline();
            var onClock = LeaderDeactivation.leaderDeactivation(clock);
            var release = hold();

            onClock.onLeaderChange(SELF);
            var delivered = onClock.delivered(executor, AetherNode.LEADER_DEACTIVATION_BOUND);

            assertThat(clock.armedWith).as("the wait is armed with the node's bound").isEqualTo(AetherNode.LEADER_DEACTIVATION_BOUND);
            clock.advanceTo(AetherNode.LEADER_DEACTIVATION_BOUND.nanos() - 1_000_000L);
            assertThat(delivered.isResolved()).as("held, and one millisecond short of the bound").isFalse();

            clock.advanceTo(AetherNode.LEADER_DEACTIVATION_BOUND.nanos());
            release.countDown();
            assertThat(delivered.isResolved()).as("resolved at the bound").isTrue();
            assertThat(delivered.await(AWAIT).isSuccess()).as("resolved, not failed").isTrue();
            assertThat(capture.messages(Level.WARN)).anySatisfy(message -> assertThat(message).contains("Leader deactivation not delivered on LeaderManager within"));
        }
    }

    /// A clock that only moves when the test says so: fails the bounded promise once the time advanced reaches the bound it was armed with.
    private static final class ManualDeadline implements Fn2<Promise<Unit>, Promise<Unit>, TimeSpan> {
        private Promise<Unit> armedPromise;
        TimeSpan armedWith;

        @Override
        public Promise<Unit> apply(Promise<Unit> promise, TimeSpan bound) {
            armedPromise = promise;
            armedWith = bound;
            return promise;
        }

        void advanceTo(long nanos) {
            if (armedWith != null && nanos >= armedWith.nanos()) {
                armedPromise.fail(Causes.cause("manual deadline reached"));
            }
        }
    }

    private void deliverThenHold(CountDownLatch recorded, CountDownLatch release) {
        deactivation.onLeaderChange(NONE);
        recorded.countDown();
        awaitQuietly(release);
    }

    private CountDownLatch hold() throws InterruptedException {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        executor.undo("hold", () -> startThenHold(started, release));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        return release;
    }

    private static void startThenHold(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        awaitQuietly(release);
    }

    private static Unit awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return Unit.unit();
    }
}
