// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// #290 — cluster-formation bootstrap admin API key. When management security is on by default and an
/// operator provisioned no API keys, a fresh cluster would have no usable credential. On first
/// leadership this registrar generates ONE random ADMIN key, stores its hash cluster-wide in the KV
/// store (validated everywhere via [`org.pragmatica.aether.http.security.KvStoreApiKeyValidator`]), and
/// prints the plaintext ONCE, prominently, so the operator can capture it.
///
/// Structurally identical to [`SystemStreamRegistrar`]: leader-armed, idempotent, self-healing,
/// consensus-committed, and unit-testable against a stubbed scheduler. The single leg is injected as a
/// [`Supplier`] of [`Promise`] (the async consensus write) so the registrar can be tested without a
/// live cluster.
///
///   - **Idempotent.** The leg first checks the KV store for an existing active ADMIN key; if one
///     exists it returns [`Option#empty`] (nothing committed, nothing to print) and the registrar
///     latches DONE — so a re-elected leader, or a cluster started with an operator-provided key, never
///     creates a second bootstrap key.
///   - **Retry ONLY on transient failures.** A consensus commit timeout / not-yet-quorate failure
///     leaves the leg pending so the next bounded-backoff pass retries; once committed the KV check
///     makes subsequent passes no-ops.
///   - **Stops on leadership loss.** A deposed leader cancels the pending retry — only the leader may
///     write.
///   - **Print-once.** The plaintext is emitted exactly once (latched), even across retries.
public final class BootstrapAdminKeyRegistrar {
    private static final Logger LOG = LoggerFactory.getLogger(BootstrapAdminKeyRegistrar.class);
    static final TimeSpan INITIAL_BACKOFF = TimeSpan.timeSpan(500L).millis();
    static final TimeSpan MAX_BACKOFF = TimeSpan.timeSpan(30L).seconds();

    /// Leg result: `Some(plaintext)` when a NEW bootstrap key was committed (print it once);
    /// `None` when an admin key already existed (idempotent no-op). A failed `Promise` is retried
    /// (transient) — there are no terminal causes for this leg.
    private final Supplier<Promise<Option<String>>> bootstrapLeg;
    private final RetryScheduler scheduler;
    private final AtomicBoolean leader = new AtomicBoolean(false);
    private final AtomicBoolean done = new AtomicBoolean(false);
    private final AtomicBoolean printed = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> pendingRetry = new AtomicReference<>();
    private final AtomicReference<TimeSpan> nextBackoff = new AtomicReference<>(INITIAL_BACKOFF);

    /// Pluggable one-shot scheduler seam (production = [`SharedScheduler#schedule`]); a test injects a
    /// deterministic scheduler that captures the runnable instead of timing it.
    @FunctionalInterface
    public interface RetryScheduler {
        ScheduledFuture<?> schedule(Runnable runnable, TimeSpan delay);
    }

    private BootstrapAdminKeyRegistrar(Supplier<Promise<Option<String>>> bootstrapLeg, RetryScheduler scheduler) {
        this.bootstrapLeg = bootstrapLeg;
        this.scheduler = scheduler;
    }

    /// Production factory bound to the process-wide [`SharedScheduler`].
    public static BootstrapAdminKeyRegistrar bootstrapAdminKeyRegistrar(Supplier<Promise<Option<String>>> bootstrapLeg) {
        return new BootstrapAdminKeyRegistrar(bootstrapLeg, SharedScheduler::schedule);
    }

    /// Test factory accepting an explicit scheduler seam.
    static BootstrapAdminKeyRegistrar bootstrapAdminKeyRegistrar(Supplier<Promise<Option<String>>> bootstrapLeg,
                                                                 RetryScheduler scheduler) {
        return new BootstrapAdminKeyRegistrar(bootstrapLeg, scheduler);
    }

    /// `LeaderChange` route hook. On leader-gain arm the retry loop and run the first pass immediately;
    /// on leader-loss disarm — only the leader can commit.
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

    boolean isComplete() {
        return done.get();
    }

    boolean isLeader() {
        return leader.get();
    }

    @Contract
    private void runPass() {
        if (done.get() || !leader.get()) {
            return;
        }

        bootstrapLeg.get().onSuccess(this::latchSuccess).onFailure(this::onTransientFailure);
    }

    @Contract
    private void latchSuccess(Option<String> plaintext) {
        cancelPendingRetry();
        if (done.compareAndSet(false, true)) {
            plaintext.onPresent(this::printBootstrapKey).onEmpty(() -> LOG.info("Bootstrap admin key: an admin API key already exists; not generating one"));
        }
    }

    @Contract
    private void onTransientFailure(Cause cause) {
        LOG.debug("Bootstrap admin key: transient failure: {} — will retry", cause.message());
        scheduleRetry();
    }

    @Contract
    private void printBootstrapKey(String plaintext) {
        if (!printed.compareAndSet(false, true)) {
            return;
        }

        var banner = """

            ================================================================
            AETHER BOOTSTRAP ADMIN API KEY (shown once — store it securely)
            ----------------------------------------------------------------
            %s
            ----------------------------------------------------------------
            Pass it as the `X-API-Key` header (or `--api-key` to the CLI).
            Rotate or revoke it via /api/cluster/keys once provisioned.
            ================================================================
            """.formatted(plaintext);

        LOG.warn(banner);
        System.out.println(banner);
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

    private static TimeSpan nextBackoffAfter(TimeSpan current) {
        var doubled = current.nanos() * 2;

        if (doubled >= MAX_BACKOFF.nanos()) {
            return MAX_BACKOFF;
        }

        return TimeSpan.timeSpan(doubled).nanos();
    }
}
