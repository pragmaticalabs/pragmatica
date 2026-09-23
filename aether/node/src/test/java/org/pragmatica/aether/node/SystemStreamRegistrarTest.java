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
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
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
import static org.pragmatica.lang.Unit.unit;

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

            // Leader-gain SCHEDULES the first pass (#1419: never inline — see FIRST_PASS_DELAY) and
            // returns without touching a leg. Firing it: createStream fails (transient) → a retry is
            // scheduled; bootstrap leg latches DONE (ALREADY_EXISTS).
            registrar.onLeaderChange(gained());
            assertThat(createStreamCalls.get())
                .as("the first pass is scheduled, not run on the notification thread")
                .isZero();

            scheduler.fireNext();
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
            scheduler.fireNext();

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
            scheduler.fireNext();
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
            scheduler.fireNext();

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
            scheduler.fireNext();
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

            // Leader-gain schedules the FIRST pass at zero delay (#1419); each of the 8 fired passes
            // then schedules one retry, walking the curve past the clamp point (16s → 32s clamps to
            // MAX=30s). The zero-delay head is the fix's own signature: it must not consume a
            // backoff step.
            registrar.onLeaderChange(gained());
            for (int i = 0; i < 8; i++) {
                scheduler.fireNext();
            }

            var nanos = scheduler.delays().stream().map(TimeSpan::nanos).toList();

            assertThat(nanos).as("the first pass is armed at zero delay, then backoff doubles from INITIAL and saturates at MAX")
                             .containsExactly(SystemStreamRegistrar.FIRST_PASS_DELAY.nanos(),
                                              SystemStreamRegistrar.INITIAL_BACKOFF.nanos(),
                                              TimeSpan.timeSpan(1L).seconds().nanos(),
                                              TimeSpan.timeSpan(2L).seconds().nanos(),
                                              TimeSpan.timeSpan(4L).seconds().nanos(),
                                              TimeSpan.timeSpan(8L).seconds().nanos(),
                                              TimeSpan.timeSpan(16L).seconds().nanos(),
                                              SystemStreamRegistrar.MAX_BACKOFF.nanos(),
                                              SystemStreamRegistrar.MAX_BACKOFF.nanos());
            assertThat(SystemStreamRegistrar.FIRST_PASS_DELAY.nanos())
                .as("the first pass must start immediately — only the THREAD changes")
                .isZero();

            assertThat(SystemStreamRegistrar.INITIAL_BACKOFF.millis())
                .as("INITIAL_BACKOFF contract is 500ms")
                .isEqualTo(500L);
            assertThat(nanos.getLast())
                .as("clamped delay never exceeds MAX_BACKOFF")
                .isEqualTo(SystemStreamRegistrar.MAX_BACKOFF.nanos());
        }
    }

    /// #1419 — the registrar must not run a leg on the thread that delivered the `LeaderChange`.
    ///
    /// On the real path that thread is Rabia's single apply thread, inside `commitChanges`, and BOTH
    /// legs block it: `createStream` awaits the `StreamConfigKey` commit (`StreamPartitionManager`'s
    /// pre-existing 10 s await — latent on rc4 today) and `bootstrap` awaits the catalog commit
    /// (`KvBackedStreamRegistry`, added by #968). Each of those commits can only be applied by the
    /// very thread that is waiting for it, so an inline pass waits on something it is itself
    /// preventing — the await burns its whole bound and consensus is frozen for that long.
    ///
    /// Both tests reproduce that causal shape exactly: the leg awaits a promise that ONLY the caller
    /// can resolve, and only AFTER `onLeaderChange` has returned. Run inline the leg cannot commit at
    /// all (it times out, the registrar never latches DONE, and the call takes the whole bound); run
    /// off the notification thread it commits on the first pass. One test per leg, because the fix
    /// moves the pass rather than either leg, and leg 1's stall predates #968.
    @Nested
    class OffTheNotificationThread {
        private static final TimeSpan LEG_BOUND = TimeSpan.timeSpan(2L).seconds();

        @Test
        void onLeaderChange_bootstrapLegCommitOnlyPossibleAfterReturn_commitsAndNeverBlocksTheCaller() {
            var commit = Promise.<Unit> promise();
            var legEntries = new AtomicInteger();
            var scheduler = new CapturingScheduler();
            var registrar = SystemStreamRegistrar.systemStreamRegistrar(() -> unitResult(),
                                                                        () -> awaitCommit(commit, legEntries),
                                                                        scheduler);

            assertLegRunsOffTheCallersThread(registrar, scheduler, commit, legEntries);
        }

        @Test
        void onLeaderChange_createStreamLegCommitOnlyPossibleAfterReturn_commitsAndNeverBlocksTheCaller() {
            var commit = Promise.<Unit> promise();
            var legEntries = new AtomicInteger();
            var scheduler = new CapturingScheduler();
            var registrar = SystemStreamRegistrar.systemStreamRegistrar(() -> awaitCommit(commit, legEntries),
                                                                        () -> unitResult(),
                                                                        scheduler);

            assertLegRunsOffTheCallersThread(registrar, scheduler, commit, legEntries);
        }

        private static void assertLegRunsOffTheCallersThread(SystemStreamRegistrar registrar,
                                                             CapturingScheduler scheduler,
                                                             Promise<Unit> commit,
                                                             AtomicInteger legEntries) {
            var startNanos = System.nanoTime();

            registrar.onLeaderChange(gained());

            var elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

            assertThat(legEntries.get())
                .as("the leg must not be entered on the notification thread — on the real path that is "
                    + "Rabia's apply thread and the leg awaits a put only it can commit (#1419)")
                .isZero();
            assertThat(elapsedMillis)
                .as("onLeaderChange must return without waiting; inline it burns the leg's whole %s bound", LEG_BOUND)
                .isLessThan(LEG_BOUND.millis());

            // Exactly what the apply thread does once released: apply the commit the leg waits on.
            commit.succeed(unit());
            scheduler.fireNext();

            assertThat(legEntries.get()).isEqualTo(1);
            assertThat(registrar.isComplete())
                .as("the awaited commit lands, so the leg latches DONE on its FIRST pass")
                .isTrue();
            assertThat(scheduler.hasPending())
                .as("both legs DONE — nothing further scheduled")
                .isFalse();
        }

        /// A leg whose consensus commit is resolvable only by the thread that delivered the
        /// notification, after it returns. Bounded so the inline (defective) arrangement fails loudly
        /// instead of hanging the suite.
        private static Result<?> awaitCommit(Promise<Unit> commit, AtomicInteger legEntries) {
            legEntries.incrementAndGet();

            return commit.await(LEG_BOUND);
        }
    }

    /// Deterministic [`SystemStreamRegistrar.RetryScheduler`] seam: captures each scheduled runnable
    /// instead of timing it so the test can drive retry passes explicitly. A fired runnable is removed
    /// before invocation (mirroring the production `pendingRetry.set(null)` at the top of
    /// `onScheduledPass`). Every pass is captured here, including the first (#1419).
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
