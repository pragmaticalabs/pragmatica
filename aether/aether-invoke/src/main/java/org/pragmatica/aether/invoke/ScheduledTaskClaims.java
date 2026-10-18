// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.pragmatica.aether.invoke.ScheduledTaskRegistry.ScheduledTask;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskClaimKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskClaimValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The durable half of a SINGLE-mode task's in-flight guard (#1155, direction B of the #273 ruling).
///
/// The in-process guard (`Context.inFlight`) holds on one node. A leader elected while the previous leader's fire is still
/// running starts with an empty set and used to fire again on its next tick, so one schedule ran twice. Here a fire is
/// claimed in cluster state before it runs ([#acquire]) and the claim is given up when it settles ([Ticket#release]); a leader
/// that finds a held claim does not fire.
///
/// **The fence.** [ScheduledTaskClaimValue] is `VersionFenced`: the applier takes a claim write only as the immediate
/// successor of the committed row. Two leaders that read the same row race to write the same successor, one commits and the
/// other is refused; a claim overridden as stale is followed by the new holder's successor, so the old holder's release,
/// built on the version it claimed, is refused too. The release is what gates the fire's state write: an outcome is recorded
/// only if the release landed, so a fenced-out run records nothing and exactly one run per claim is recorded. The refused
/// write is silent (nothing is emitted, the applier's result is not attributable under batching), so every write is
/// confirmed by reading the row back.
///
/// **What this does not do.** It does not stop the old run: the invocation is not cancellable, so a run fenced out at its
/// release has already executed, and its side effect is a duplicate of the new holder's. It bounds the RECORDED runs, and
/// the window in which a second run can START to the stale bound below. A claim whose holder is gone is taken over after
/// that bound, so a leader lost mid-fire costs its task that long.
///
/// **Stale bound** = `max(interval, completionBound + max(1 s, completionBound / 10))`. A healthy holder gives up its own
/// wait at the completion bound and releases; the margin covers the release round trip, so a claim older than the bound is a
/// holder that is gone or hung. A marked guess: the ruling asked for N x p99 run time, and no per-task run-time
/// distribution exists to take a p99 of, so the one upper bound the runtime enforces stands in for it.
/// Claim time is the claimant's wall clock read against the evaluator's, so skew moves the bound by the skew.
///
/// **Cost.** SINGLE-mode only. One row per task (removed with the task, never per fire), and two extra writes per fire (the
/// claim and its release) next to the state row's one; ALL-mode is unchanged.
final class ScheduledTaskClaims {
    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskClaims.class);
    private static final long MIN_MARGIN_MILLIS = 1_000L;

    /// One fire's hold on its task's claim.
    interface Ticket {
        /// A fire that holds no durable claim (ALL-mode): nothing to give up, nothing that can have been overridden.
        Ticket NONE = () -> Promise.success(true);
        /// Gives the claim up. Resolves `true` when the release landed, so this fire is still the one the cluster recorded
        /// as running; `false` when it was refused (the claim was overridden meanwhile) or could not be written.
        Promise<Boolean> release();
    }

    private final ScheduledClaimStore store;
    private final NodeId self;
    private final LeaderManager leaderManager;
    private final TimeSpan completionBound;
    private final LongSupplier clock;
    /// The tokens of the claims THIS process took and has not seen released. A held claim carrying one of them, found when
    /// no fire of this node is in flight, is an orphan of a release that failed, not another node's run.
    private final Set<String> issued = ConcurrentHashMap.newKeySet();

    ScheduledTaskClaims(ScheduledClaimStore store,
                        NodeId self,
                        LeaderManager leaderManager,
                        TimeSpan completionBound,
                        LongSupplier clock) {
        this.store = store;
        this.self = self;
        this.leaderManager = leaderManager;
        this.completionBound = completionBound;
        this.clock = clock;
    }

    /// Claims the next fire of `task`: a ticket when this node may run it, none when another holder's claim stands.
    Promise<Option<Ticket>> acquire(ScheduledTask task) {
        return task.executionMode() == ExecutionMode.SINGLE
               ? claim(claimKey(task), staleAfterMillis(task))
               : Promise.success(Option.some(Ticket.NONE));
    }

    /// The task is gone: its claim row goes with it.
    Promise<Unit> forget(ScheduledTaskKey task) {
        return store.write(new KVCommand.Remove<>(ScheduledTaskClaimKey.scheduledTaskClaimKey(task)))
                    .onFailure(cause -> log.warn("Could not remove the claim of removed task {}: {}",
                                                 task,
                                                 cause.message()));
    }

    private static ScheduledTaskClaimKey claimKey(ScheduledTask task) {
        return ScheduledTaskClaimKey.scheduledTaskClaimKey(task.configSection(), task.artifact(), task.methodName());
    }

    private long staleAfterMillis(ScheduledTask task) {
        var bound = completionBound.millis();

        return Math.max(intervalMillis(task), bound + Math.max(MIN_MARGIN_MILLIS, bound / 10));
    }

    private static long intervalMillis(ScheduledTask task) {
        return task.isInterval()
               ? ScheduledTaskManager.IntervalParser.parse(task.interval())
                                                    .map(TimeSpan::millis)
                                                    .or(0L)
               : 0L;
    }

    private Promise<Option<Ticket>> claim(ScheduledTaskClaimKey key, long staleAfterMillis) {
        var prior = store.read(key);

        return prior.filter(row -> standsAgainstUs(row, staleAfterMillis))
                    .isPresent()
               ? held(key, prior)
               : write(key, prior);
    }

    /// A held claim stands unless it has gone stale or is an orphan of this process's own failed release.
    private boolean standsAgainstUs(ScheduledTaskClaimValue row, long staleAfterMillis) {
        return row.held()
               && !row.isStale(clock.getAsLong(), staleAfterMillis)
               && !issued.contains(row.token());
    }

    private Promise<Option<Ticket>> held(ScheduledTaskClaimKey key, Option<ScheduledTaskClaimValue> prior) {
        prior.onPresent(row -> log.debug("Scheduled task {} not fired: claim held by {} (term {}) since {}",
                                         key,
                                         row.holder(),
                                         row.term(),
                                         row.claimedAt()));

        return Promise.success(Option.none());
    }

    private Promise<Option<Ticket>> write(ScheduledTaskClaimKey key, Option<ScheduledTaskClaimValue> prior) {
        var claim = ScheduledTaskClaimValue.claimed(prior,
                                                    self,
                                                    leaderManager.currentLeaderEpoch().or(0L),
                                                    UUID.randomUUID().toString(),
                                                    clock.getAsLong());

        issued.add(claim.token());

        return store.write(new KVCommand.Put<>(key, claim))
                    .map(_ -> confirmClaim(key, claim))
                    // FER (degrade forward = skip the tick): a claim that cannot be written leaves this node unable to show it is
                    // the only runner, so it does not fire. Guarantee earned: no fire without a committed claim. Mechanism: the next
                    // tick reads the row again and retries; cost: the task does not run while claim writes fail.
                    .recover(cause -> claimNotWritten(key, claim, cause));
    }

    private Option<Ticket> confirmClaim(ScheduledTaskClaimKey key, ScheduledTaskClaimValue claim) {
        return store.read(key)
                    .filter(row -> row.heldBy(claim.token()))
                    .map(row -> ticketFor(key, claim))
                    .onEmpty(() -> issued.remove(claim.token()));
    }

    private Option<Ticket> claimNotWritten(ScheduledTaskClaimKey key, ScheduledTaskClaimValue claim, Cause cause) {
        issued.remove(claim.token());
        log.warn("Scheduled task {} not fired: its claim could not be written: {}", key, cause.message());

        return Option.none();
    }

    private Ticket ticketFor(ScheduledTaskClaimKey key, ScheduledTaskClaimValue claim) {
        return () -> release(key, claim);
    }

    private Promise<Boolean> release(ScheduledTaskClaimKey key, ScheduledTaskClaimValue claim) {
        return store.write(new KVCommand.Put<>(key,
                                               claim.released()))
                    .map(_ -> releaseLanded(key, claim))
                    // FER (degrade forward = record nothing): a release that cannot be written leaves the claim held, so this
                    // fire's outcome is not recorded. Guarantee earned: a fire is recorded only if its release landed. Mechanism:
                    // the claim is an orphan of this process, which its next tick takes over (see `issued`), or goes stale for
                    // another leader.
                    .recover(cause -> releaseNotWritten(key, cause));
    }

    private boolean releaseLanded(ScheduledTaskClaimKey key, ScheduledTaskClaimValue claim) {
        var landed = store.read(key).filter(row -> row.releasedBy(claim.token())).isPresent();

        if (landed) {
            issued.remove(claim.token());
        } else {
            log.warn("Scheduled task {}: the claim this fire took at {} was overridden while it ran, its release was refused; its"
                    + " outcome is not recorded",
                     key,
                     claim.claimedAt());
        }

        return landed;
    }

    private boolean releaseNotWritten(ScheduledTaskClaimKey key, Cause cause) {
        log.warn("Scheduled task {}: the claim release could not be written, this fire's outcome is not recorded: {}",
                 key,
                 cause.message());

        return false;
    }
}
