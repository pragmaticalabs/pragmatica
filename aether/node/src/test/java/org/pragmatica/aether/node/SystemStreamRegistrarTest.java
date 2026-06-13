// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Result.unitResult;

/// Fix #1 — proves the system-stream registration is LEVEL-TRIGGERED and self-healing:
///   - a transient `createStream` commit failure is RETRIED until it commits;
///   - the loop STOPS once both legs report success / `STREAM_ALREADY_EXISTS`;
///   - the loop STOPS on leadership loss (a deposed leader must not keep attempting);
///   - a TERMINAL (config) cause latches the leg DONE without retrying (no consensus thrash).
class SystemStreamRegistrarTest {
    private static LeaderNotification.LeaderChange gained() {
        return LeaderNotification.leaderChange(Option.option(org.pragmatica.consensus.NodeId.nodeId("leader").unwrap()), true);
    }

    private static LeaderNotification.LeaderChange lost() {
        return LeaderNotification.leaderChange(Option.empty(), false);
    }

    @Nested
    class RetryUntilCommitted {

        @Test
        void onLeaderChange_retriesCreateStream_untilCommitThenStops() {
            var createStreamResult = new AtomicReference<Result<?>>(StreamError.General.STREAM_CONFIG_COMMIT_FAILED.result());
            var createStreamCalls = new AtomicInteger();
            var scheduler = new CapturingScheduler();
            var registrar = SystemStreamRegistrar.systemStreamRegistrar(() -> {
                                                                            createStreamCalls.incrementAndGet();
                                                                            return createStreamResult.get();
                                                                        },
                                                                        () -> StreamError.General.STREAM_ALREADY_EXISTS.result(),
                                                                        scheduler);

            // Leader-gain runs the first pass immediately: createStream fails (transient) → a retry is
            // scheduled; bootstrap leg latches DONE (ALREADY_EXISTS).
            registrar.onLeaderChange(gained());
            assertThat(createStreamCalls.get()).isEqualTo(1);
            assertThat(registrar.isComplete()).isFalse();
            assertThat(scheduler.hasPending()).isTrue();

            // Fire the retry — still failing — another retry scheduled.
            scheduler.fireNext();
            assertThat(createStreamCalls.get()).isEqualTo(2);
            assertThat(scheduler.hasPending()).isTrue();

            // The commit path recovers; the next retry commits → loop stops, no further retry pending.
            createStreamResult.set(unitResult());
            scheduler.fireNext();
            assertThat(createStreamCalls.get()).isEqualTo(3);
            assertThat(registrar.isComplete()).isTrue();
            assertThat(scheduler.hasPending()).isFalse();

            // A subsequent fired pass (if any straggler) must not re-attempt a DONE leg.
            scheduler.fireAll();
            assertThat(createStreamCalls.get()).isEqualTo(3);
        }

        @Test
        void onLeaderChange_alreadyExists_treatedAsCommitted_stopsImmediately() {
            var scheduler = new CapturingScheduler();
            var createStreamCalls = new AtomicInteger();
            var registrar = SystemStreamRegistrar.systemStreamRegistrar(() -> {
                                                                            createStreamCalls.incrementAndGet();
                                                                            return StreamError.General.STREAM_ALREADY_EXISTS.result();
                                                                        },
                                                                        () -> unitResult(),
                                                                        scheduler);

            registrar.onLeaderChange(gained());

            assertThat(createStreamCalls.get()).isEqualTo(1);
            assertThat(registrar.isComplete())
                .as("STREAM_ALREADY_EXISTS means the config is committed — stop, do not retry")
                .isTrue();
            assertThat(scheduler.hasPending()).isFalse();
        }
    }

    @Nested
    class StopConditions {

        @Test
        void onLeaderChange_leadershipLost_cancelsPendingRetry_andStopsAttempting() {
            var createStreamCalls = new AtomicInteger();
            var scheduler = new CapturingScheduler();
            var registrar = SystemStreamRegistrar.systemStreamRegistrar(() -> {
                                                                            createStreamCalls.incrementAndGet();
                                                                            return StreamError.General.STREAM_CONFIG_COMMIT_FAILED.result();
                                                                        },
                                                                        () -> unitResult(),
                                                                        scheduler);

            registrar.onLeaderChange(gained());
            assertThat(createStreamCalls.get()).isEqualTo(1);
            assertThat(scheduler.hasPending()).isTrue();

            // Lose leadership: the pending retry is cancelled and no further attempts happen.
            registrar.onLeaderChange(lost());
            assertThat(registrar.isLeader()).isFalse();

            // Even if a stale captured retry fires after demotion, the leader-gate short-circuits it.
            scheduler.fireAll();
            assertThat(createStreamCalls.get())
                .as("a deposed leader must not keep attempting registration")
                .isEqualTo(1);
        }

        @Test
        void onLeaderChange_terminalConfigError_latchesDone_withoutRetrying() {
            var createStreamCalls = new AtomicInteger();
            var scheduler = new CapturingScheduler();
            var registrar = SystemStreamRegistrar.systemStreamRegistrar(() -> {
                                                                            createStreamCalls.incrementAndGet();
                                                                            return StreamError.General.STREAM_MEMORY_EXCEEDED.result();
                                                                        },
                                                                        () -> unitResult(),
                                                                        scheduler);

            registrar.onLeaderChange(gained());

            assertThat(createStreamCalls.get()).isEqualTo(1);
            assertThat(registrar.isComplete())
                .as("a terminal config error latches the leg DONE — never retried")
                .isTrue();
            assertThat(scheduler.hasPending()).isFalse();

            scheduler.fireAll();
            assertThat(createStreamCalls.get()).isEqualTo(1);
        }

        @Test
        void onLeaderChange_bootstrapTransientFailure_retriedIndependently() {
            // createStream commits immediately; only the bootstrap leg is transiently failing. The
            // retry loop must keep running for bootstrap alone and never re-attempt the committed
            // createStream leg.
            var createStreamCalls = new AtomicInteger();
            var bootstrapResult = new AtomicReference<Result<?>>(Causes.cause("Node is inactive").result());
            var bootstrapCalls = new AtomicInteger();
            var scheduler = new CapturingScheduler();
            var registrar = SystemStreamRegistrar.systemStreamRegistrar(() -> {
                                                                            createStreamCalls.incrementAndGet();
                                                                            return unitResult();
                                                                        },
                                                                        () -> {
                                                                            bootstrapCalls.incrementAndGet();
                                                                            return bootstrapResult.get();
                                                                        },
                                                                        scheduler);

            registrar.onLeaderChange(gained());
            assertThat(createStreamCalls.get()).isEqualTo(1);
            assertThat(bootstrapCalls.get()).isEqualTo(1);
            assertThat(registrar.isComplete()).isFalse();
            assertThat(scheduler.hasPending()).isTrue();

            bootstrapResult.set(unitResult());
            scheduler.fireNext();

            assertThat(createStreamCalls.get())
                .as("the committed createStream leg must not be re-attempted")
                .isEqualTo(1);
            assertThat(bootstrapCalls.get()).isEqualTo(2);
            assertThat(registrar.isComplete()).isTrue();
            assertThat(scheduler.hasPending()).isFalse();
        }
    }

    @Nested
    class BackoffSchedule {

        @Test
        void onLeaderChange_retriesWhileTransient_usesExponentialBackoffClampedAtMax() {
            // createStream stays transiently-failing forever; bootstrap commits immediately so the loop
            // keeps running solely to retry createStream — one scheduled delay captured per pass.
            var scheduler = new CapturingScheduler();
            var registrar = SystemStreamRegistrar.systemStreamRegistrar(StreamError.General.STREAM_CONFIG_COMMIT_FAILED::result,
                                                                        () -> unitResult(),
                                                                        scheduler);

            // First pass (leader-gain) schedules retry #1; then fire 7 more passes to walk the curve
            // past the clamp point (16s → 32s clamps to MAX=30s).
            registrar.onLeaderChange(gained());
            for (int i = 0; i < 7; i++) {
                scheduler.fireNext();
            }

            var nanos = scheduler.delays().stream().map(TimeSpan::nanos).toList();

            assertThat(nanos).as("backoff doubles from INITIAL then saturates at MAX")
                             .containsExactly(SystemStreamRegistrar.INITIAL_BACKOFF.nanos(),
                                              TimeSpan.timeSpan(1L).seconds().nanos(),
                                              TimeSpan.timeSpan(2L).seconds().nanos(),
                                              TimeSpan.timeSpan(4L).seconds().nanos(),
                                              TimeSpan.timeSpan(8L).seconds().nanos(),
                                              TimeSpan.timeSpan(16L).seconds().nanos(),
                                              SystemStreamRegistrar.MAX_BACKOFF.nanos(),
                                              SystemStreamRegistrar.MAX_BACKOFF.nanos());

            assertThat(SystemStreamRegistrar.INITIAL_BACKOFF.millis())
                .as("INITIAL_BACKOFF contract is 500ms")
                .isEqualTo(500L);
            assertThat(nanos.getLast())
                .as("clamped delay never exceeds MAX_BACKOFF")
                .isEqualTo(SystemStreamRegistrar.MAX_BACKOFF.nanos());
        }
    }

    /// Deterministic [`SystemStreamRegistrar.RetryScheduler`] seam: captures each scheduled runnable
    /// instead of timing it so the test can drive retry passes explicitly. A fired runnable is removed
    /// before invocation (mirroring the production `pendingRetry.set(null)` at the top of `onRetryFire`).
    private static final class CapturingScheduler implements SystemStreamRegistrar.RetryScheduler {
        private final List<Runnable> pending = new ArrayList<>();
        private final List<TimeSpan> delays = new ArrayList<>();

        @Override public ScheduledFuture<?> schedule(Runnable runnable, TimeSpan delay) {
            pending.add(runnable);
            delays.add(delay);
            return new NoopFuture();
        }

        boolean hasPending() {return !pending.isEmpty();}

        List<TimeSpan> delays() {return delays;}

        void fireNext() {
            if (pending.isEmpty()) {return;}
            pending.removeFirst().run();
        }

        void fireAll() {
            while (!pending.isEmpty()) {
                pending.removeFirst().run();
            }
        }
    }

    private static final class NoopFuture implements ScheduledFuture<Object> {
        @Override public long getDelay(TimeUnit unit) {return 0;}
        @Override public int compareTo(Delayed o) {return 0;}
        @Override public boolean cancel(boolean mayInterruptIfRunning) {return true;}
        @Override public boolean isCancelled() {return false;}
        @Override public boolean isDone() {return false;}
        @Override public Object get() {return null;}
        @Override public Object get(long timeout, TimeUnit unit) {return null;}
    }
}
