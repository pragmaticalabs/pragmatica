// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Fix #1 — LEVEL-TRIGGERED, self-healing registration of the system streams the observability READ
/// path depends on (`system:cluster-events:1.0.0` partition + the namespace catalog entries that back
/// `/api/events|alerts|traces`).
///
/// **Why the previous fire-once-on-leader-gain approach failed.** The prior
/// `registerSystemStreamsOnLeaderChange` ran exactly once per `LeaderChange` and, on a
/// consensus-commit timeout, only LOGGED "will retry on next leader change". There is no further
/// `LeaderChange` on a stable cluster, so if the commit timed out in the ~10s after leader-gain
/// (consensus not yet quorate) the `StreamConfigKey` was never committed, the stream never entered the
/// replicated catalog, the owner-gate never armed, and nothing was ever published — observability read
/// `200 []` forever.
///
/// **The fix.** A bounded-backoff retry loop, kicked from leader-gain and pinned to leadership:
///   - **Two independent legs.** `createStream` (publishes the `StreamConfigKey`) and `bootstrap()`
///     (registers the namespace catalog entries that alerts/traces share). Each leg latches DONE on
///     its first success-or-terminal outcome and is never re-attempted; the loop keeps running only
///     for the leg(s) still pending.
///   - **Idempotent + ALREADY_EXISTS == DONE.** `createStream` returns `STREAM_ALREADY_EXISTS` once the
///     config is committed (see [`org.pragmatica.aether.stream.StreamPartitionManager#createStream`]);
///     that is treated as SUCCESS/stop, not a retry trigger.
///   - **Retry ONLY on transient failures.** A commit timeout / not-yet-quorate failure
///     (`STREAM_CONFIG_COMMIT_FAILED`, "Node is inactive", etc.) is retried; a genuine CONFIG error
///     (`STREAM_MEMORY_EXCEEDED`, `AHSE_REQUIRED_FOR_STRONG`) is terminal — the leg latches DONE
///     (logged) and never thrashes consensus over an un-fixable config.
///   - **Stops on leadership loss.** `onLeaderChange(loss)` (or any non-leader pass) cancels the
///     pending retry and disarms; only the leader can commit.
///   - **Bounded backoff.** Exponential from `INITIAL_BACKOFF` to `MAX_BACKOFF`; never busy-loops, at
///     most one outstanding scheduled retry (deduped via the future ref).
///
/// Wiring: a single instance is constructed in `AetherNode` and its [`#onLeaderChange`] is appended to
/// the `LeaderChange` routes (replacing the fire-once `registerSystemStreamsOnLeaderChange`). The two
/// legs are injected as [`Supplier`]s of [`Result`] so the registrar is unit-testable against a stubbed
/// scheduler without a live cluster.
public final class SystemStreamRegistrar {
    private static final Logger LOG = LoggerFactory.getLogger(SystemStreamRegistrar.class);
    static final TimeSpan INITIAL_BACKOFF = TimeSpan.timeSpan(500L).millis();
    static final TimeSpan MAX_BACKOFF = TimeSpan.timeSpan(30L).seconds();

    private final Supplier<Result<?>> createStreamLeg;
    private final Supplier<Result<?>> bootstrapLeg;
    private final RetryScheduler scheduler;
    private final AtomicBoolean leader = new AtomicBoolean(false);
    private final AtomicBoolean createStreamDone = new AtomicBoolean(false);
    private final AtomicBoolean bootstrapDone = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> pendingRetry = new AtomicReference<>();
    private final AtomicReference<TimeSpan> nextBackoff = new AtomicReference<>(INITIAL_BACKOFF);

    /// Pluggable one-shot scheduler seam (production = [`SharedScheduler#schedule`]); a test injects a
    /// deterministic scheduler that captures the runnable instead of timing it.
    @FunctionalInterface
    public interface RetryScheduler {
        ScheduledFuture<?> schedule(Runnable runnable, TimeSpan delay);
    }

    private SystemStreamRegistrar(Supplier<Result<?>> createStreamLeg,
                                  Supplier<Result<?>> bootstrapLeg,
                                  RetryScheduler scheduler) {
        this.createStreamLeg = createStreamLeg;
        this.bootstrapLeg = bootstrapLeg;
        this.scheduler = scheduler;
    }

    /// Production factory bound to the process-wide [`SharedScheduler`].
    public static SystemStreamRegistrar systemStreamRegistrar(Supplier<Result<?>> createStreamLeg,
                                                              Supplier<Result<?>> bootstrapLeg) {
        return new SystemStreamRegistrar(createStreamLeg, bootstrapLeg, SharedScheduler::schedule);
    }

    /// Test factory accepting an explicit scheduler seam.
    static SystemStreamRegistrar systemStreamRegistrar(Supplier<Result<?>> createStreamLeg,
                                                       Supplier<Result<?>> bootstrapLeg,
                                                       RetryScheduler scheduler) {
        return new SystemStreamRegistrar(createStreamLeg, bootstrapLeg, scheduler);
    }

    /// `LeaderChange` route hook. On leader-gain arm the retry loop and run the first pass immediately;
    /// on leader-loss disarm (cancel the pending retry) — only the leader can commit, so a deposed
    /// leader must stop attempting.
    @Contract
    public void onLeaderChange(LeaderNotification.LeaderChange change) {
        if (change.localNodeIsLeader()) {
            activate();
        } else {
            deactivate();
        }
    }

    @Contract
    void activate() {
        if (!leader.compareAndSet(false, true)) {
            return;
        }
        // A fresh leader term re-arms the backoff; the DONE latches are intentionally NOT reset —
        // once the config is committed it stays committed across re-elections (idempotent), so a
        // re-elected leader only re-attempts a leg that never completed.
        nextBackoff.set(INITIAL_BACKOFF);
        runPass();
    }

    @Contract
    void deactivate() {
        if (!leader.compareAndSet(true, false)) {
            return;
        }

        cancelPendingRetry();
    }

    /// Observability — both legs committed/terminal (no further work).
    boolean isComplete() {
        return createStreamDone.get() && bootstrapDone.get();
    }

    boolean isLeader() {
        return leader.get();
    }

    /// Single registration pass: attempt each not-yet-DONE leg; if work remains and we are still
    /// leader, schedule the next bounded-backoff retry (deduped to one outstanding future). Stops
    /// scheduling once both legs are DONE or leadership is lost.
    @Contract
    private void runPass() {
        if (!leader.get()) {
            return;
        }

        attemptLeg("system:cluster-events createStream", createStreamLeg, createStreamDone);
        if (!leader.get()) {
            return;
        }

        attemptLeg("system-stream bootstrap", bootstrapLeg, bootstrapDone);
        if (isComplete()) {
            LOG.info("SystemStreamRegistrar: all system streams registered");
            cancelPendingRetry();

            return;
        }

        scheduleRetry();
    }

    /// Attempt one leg if it is not already DONE. Latches DONE on success or a TERMINAL (config) cause;
    /// leaves it pending on a TRANSIENT (commit-timeout / not-quorate) cause so the next pass retries.
    @Contract
    private void attemptLeg(String name, Supplier<Result<?>> leg, AtomicBoolean done) {
        // Re-check leadership immediately before the consensus write: deactivate() may land on the
        // dispatch thread between runPass's leader.get() and here. The residual check-then-write window
        // is irreducible without a lock, but at most yields one idempotent (STREAM_ALREADY_EXISTS) Put,
        // which is acceptable.
        if (done.get() || !leader.get()) {
            return;
        }

        leg.get().onSuccess(_ -> latchSuccess(name, done)).onFailure(cause -> classifyFailure(name, cause, done));
    }

    @Contract
    private void latchSuccess(String name, AtomicBoolean done) {
        if (done.compareAndSet(false, true)) {
            LOG.info("SystemStreamRegistrar: {} committed", name);
        }
    }

    @Contract
    private void classifyFailure(String name, Cause cause, AtomicBoolean done) {
        if (isTerminal(cause)) {
            LOG.warn("SystemStreamRegistrar: {} failed with a terminal (non-retryable) cause: {} — not retrying",
                     name,
                     cause.message());
            done.set(true);

            return;
        }

        LOG.debug("SystemStreamRegistrar: {} transient failure: {} — will retry", name, cause.message());
    }

    /// A TERMINAL cause is one that retrying cannot resolve, so the leg latches DONE and stops:
    ///   - `STREAM_ALREADY_EXISTS` — the config is COMMITTED (the success condition for this loop).
    ///     `createStream` reports it as a failure-typed [`Result`], so it arrives here rather than via
    ///     `onSuccess`; treating it as terminal-DONE is what stops the loop once committed.
    ///   - `STREAM_MEMORY_EXCEEDED` / `AHSE_REQUIRED_FOR_STRONG` — genuine configuration errors.
    /// Everything else — consensus commit timeout, "Node is inactive", not-yet-quorate — is TRANSIENT
    /// and retried. Matched by stable enum identity, not message text.
    private static boolean isTerminal(Cause cause) {
        return cause == StreamError.General.STREAM_ALREADY_EXISTS || cause == StreamError.General.STREAM_MEMORY_EXCEEDED || cause == StreamError.General.AHSE_REQUIRED_FOR_STRONG;
    }

    @Contract
    private void scheduleRetry() {
        if (pendingRetry.get() != null) {
            return;
        }

        var delay = nextBackoff.get();
        var future = scheduler.schedule(this::onRetryFire, delay);

        if (!pendingRetry.compareAndSet(null, future)) {
            future.cancel(false);

            return;
        }

        if (!leader.get()) {
            future.cancel(false);
            pendingRetry.compareAndSet(future, null);

            return;
        }

        nextBackoff.set(nextBackoffAfter(delay));
    }

    @Contract
    private void onRetryFire() {
        pendingRetry.set(null);
        runPass();
    }

    @Contract
    private void cancelPendingRetry() {
        var prev = pendingRetry.getAndSet(null);

        if (prev != null) {
            prev.cancel(false);
        }
    }

    /// Exponential backoff, doubling each retry, clamped at [`#MAX_BACKOFF`].
    private static TimeSpan nextBackoffAfter(TimeSpan current) {
        var doubled = current.nanos() * 2;

        if (doubled >= MAX_BACKOFF.nanos()) {
            return MAX_BACKOFF;
        }

        return TimeSpan.timeSpan(doubled).nanos();
    }
}
