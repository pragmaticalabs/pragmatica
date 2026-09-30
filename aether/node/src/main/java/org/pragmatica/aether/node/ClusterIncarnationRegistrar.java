// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.utility.ULID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// #1529 part 1 — mints the cluster's lineage and incarnation ([ClusterIncarnation]) at genesis.
///
/// Same shape as [`BootstrapAdminKeyRegistrar`]: leader-armed, idempotent, self-healing and
/// consensus-committed, retrying on bounded backoff because the first pass after a leader gain often
/// meets a not-yet-quorate commit.
///
///   - **Idempotent.** A committed incarnation — minted earlier, or brought back by a restore — makes the
///     leg a no-op, so a re-elected leader never mints a second lineage.
///   - **Confirmed, not assumed.** The value is version-fenced, and a fenced write that loses a race is
///     dropped silently; the leg therefore re-reads the committed value after applying and succeeds only
///     once one is present, whoever wrote it.
///   - **Stops on leadership loss.** Only the leader mints.
public final class ClusterIncarnationRegistrar {
    private static final Logger LOG = LoggerFactory.getLogger(ClusterIncarnationRegistrar.class);
    static final TimeSpan INITIAL_BACKOFF = TimeSpan.timeSpan(500L).millis();
    static final TimeSpan MAX_BACKOFF = TimeSpan.timeSpan(30L).seconds();
    private static final Cause NOT_YET_COMMITTED = Causes.cause("Cluster incarnation genesis not yet committed");

    private final Supplier<Promise<Unit>> genesisLeg;
    private final RetryScheduler scheduler;
    private final AtomicBoolean leader = new AtomicBoolean(false);
    private final AtomicBoolean done = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> pendingRetry = new AtomicReference<>();
    private final AtomicReference<TimeSpan> nextBackoff = new AtomicReference<>(INITIAL_BACKOFF);

    /// Pluggable one-shot scheduler seam (production = [`SharedScheduler#schedule`]); a test injects a
    /// deterministic scheduler that captures the runnable instead of timing it.
    @FunctionalInterface
    public interface RetryScheduler {
        ScheduledFuture<?> schedule(Runnable runnable, TimeSpan delay);
    }

    private ClusterIncarnationRegistrar(Supplier<Promise<Unit>> genesisLeg, RetryScheduler scheduler) {
        this.genesisLeg = genesisLeg;
        this.scheduler = scheduler;
    }

    /// Production factory bound to the process-wide [`SharedScheduler`].
    public static ClusterIncarnationRegistrar clusterIncarnationRegistrar(Supplier<Promise<Unit>> genesisLeg) {
        return new ClusterIncarnationRegistrar(genesisLeg, SharedScheduler::schedule);
    }

    /// Test factory accepting an explicit scheduler seam.
    static ClusterIncarnationRegistrar clusterIncarnationRegistrar(Supplier<Promise<Unit>> genesisLeg,
                                                                   RetryScheduler scheduler) {
        return new ClusterIncarnationRegistrar(genesisLeg, scheduler);
    }

    /// The genesis leg against a live node: mint when absent, then confirm by re-reading the committed
    /// value.
    public static Supplier<Promise<Unit>> genesisLeg(Supplier<KVStore<AetherKey, AetherValue>> kvStore,
                                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        return genesisLeg(kvStore,
                          applier,
                          ClusterIncarnationRegistrar::freshUlid,
                          ClusterIncarnationRegistrar::freshUlid);
    }

    static Supplier<Promise<Unit>> genesisLeg(Supplier<KVStore<AetherKey, AetherValue>> kvStore,
                                              Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                              Supplier<String> freshLineageId,
                                              Supplier<String> freshIncarnationId) {
        return () -> ClusterIncarnation.genesisCommand(kvStore.get(),
                                                       freshLineageId,
                                                       freshIncarnationId)
                                       .fold(Promise::unitPromise,
                                             command -> mintAndConfirm(kvStore, applier, command));
    }

    private static Promise<Unit> mintAndConfirm(Supplier<KVStore<AetherKey, AetherValue>> kvStore,
                                                Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                KVCommand<AetherKey> command) {
        return applier.apply(List.of(command))
                      .flatMap(_ -> confirmCommitted(kvStore.get()));
    }

    private static Promise<Unit> confirmCommitted(KVStore<AetherKey, AetherValue> kvStore) {
        return ClusterIncarnation.committed(kvStore)
                                 .map(_ -> Promise.unitPromise())
                                 .or(NOT_YET_COMMITTED::promise);
    }

    private static String freshUlid() {
        return ULID.ulid().encoded();
    }

    /// `LeaderChange` route hook. On leader-gain arm the retry loop and run the first pass immediately;
    /// on leader-loss disarm — only the leader can mint.
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

    @Contract
    private void runPass() {
        if (done.get() || !leader.get()) {
            return;
        }

        genesisLeg.get().onSuccess(_ -> latchSuccess()).onFailure(this::onTransientFailure);
    }

    @Contract
    private void latchSuccess() {
        cancelPendingRetry();
        if (done.compareAndSet(false, true)) {
            LOG.info("Cluster incarnation: committed");
        }
    }

    @Contract
    private void onTransientFailure(Cause cause) {
        LOG.debug("Cluster incarnation: transient failure: {} — will retry", cause.message());
        scheduleRetry();
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

    // JBCT-RET-08: AtomicReference clear — null is the JDK sentinel, not Option-wrappable
    @SuppressWarnings("JBCT-RET-08")
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
