// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.scheduler.pg;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// A PostgreSQL-backed job scheduler (#2786): one-time jobs, a per-job lease with a fencing token, at-least-once firing.
/// Any number of instances may poll one database.
///
/// | Operation | Guarantee | Mechanism |
/// |---|---|---|
/// | [#schedule] | durable once the promise succeeds; due `delay` after the database clock's `now()` | one INSERT, committed |
/// | claim (each poll) | at most one instance holds an unexpired claim on a job at any database instant; every claim of a job carries a greater token than every earlier claim | `FOR UPDATE SKIP LOCKED` claim statement, token raised under the row lock, expiry judged by `now()` (see [PgJobStore]) |
/// | firing | AT-LEAST-ONCE, not exactly-once | lease expiry + re-claim |
/// | completion | applied only by the current claimer; a superseded claimer's completion is refused | `DELETE ... WHERE fence_token = ?` |
/// | failed firing | due again after `retryDelay`, retried without limit (the handler sees `attempt`) | fenced UPDATE back to SCHEDULED |
/// | [#cancel] | prevents every firing not yet claimed; a firing already running finishes, and its completion is refused | DELETE |
/// | [#close] | idempotent; no new poll after it; firings that outlive `closeGrace` have their claims released | lock-guarded stop + fenced release |
///
/// **At-least-once, stated plainly.** A crash or pause of the instance after the handler's side effect and before the
/// completion commits fires the job again once the lease expires. A handler that runs longer than the lease can run
/// concurrently with that re-fire. Handlers must therefore be idempotent; [FiredJob#fenceToken] lets them fence their
/// own writes (a later firing's token is always greater).
///
/// **Missed fires and catch-up.** Jobs are one-time; there are no recurring jobs. A job whose due time passed while no
/// instance polled, or that was scheduled with a negative delay, fires once at the next poll. Due jobs are claimed
/// oldest due time first, `batchSize` per poll; nothing is skipped or coalesced. The delay between the due time and the
/// firing is bounded by the poll interval plus the backlog ahead of the job, and is not otherwise guaranteed.
///
/// **Clock.** Every time decision is taken by the database's `now()`, so clock skew between instances changes nothing;
/// the local clock is read only to log the observed skew at [#start()].
///
/// Not provided: lease renewal (configure the lease longer than the longest handler run), recurring jobs, routing by
/// job name (one handler per instance; it dispatches on [FiredJob#name]).
public final class PgScheduler {
    private static final Logger log = LoggerFactory.getLogger(PgScheduler.class);
    private static final TimeSpan NO_DELAY = timeSpan(0).millis();

    private final PgJobStore store;
    private final SchedulerConfig config;
    private final JobHandler handler;
    private final Object lifecycle = new Object();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<JobId, FiredJob> held = new ConcurrentHashMap<>();
    private final Promise<Unit> closing = Promise.promise();
    private volatile Option<ScheduledFuture<?>> nextTick = Option.none();
    private volatile Promise<Unit> inFlight = Promise.unitPromise();

    private PgScheduler(PgJobStore store, SchedulerConfig config, JobHandler handler) {
        this.store = store;
        this.config = config;
        this.handler = handler;
    }

    public static PgScheduler pgScheduler(PgSqlConnector connector, SchedulerConfig config, JobHandler handler) {
        return pgScheduler(connector, config, handler, Clock.systemUTC());
    }

    /// @param clock read only to log the local-versus-database skew at [#start()]; never a decision input
    public static PgScheduler pgScheduler(PgSqlConnector connector,
                                          SchedulerConfig config,
                                          JobHandler handler,
                                          Clock clock) {
        return new PgScheduler(PgJobStore.pgJobStore(connector, clock), config, handler);
    }

    /// Creates the `scheduler_job` table and its index when absent; safe to run from several instances at once.
    public static Promise<Unit> installSchema(PgSqlConnector connector) {
        return PgJobStore.pgJobStore(connector,
                                     Clock.systemUTC())
                         .installSchema();
    }

    /// Schedules a one-time job due `delay` after the database's `now()`; a negative delay schedules it in the past
    /// (it fires at the next poll). Durable once the promise succeeds.
    public Promise<JobId> schedule(String name, Duration delay, byte[] payload) {
        return store.schedule(name, delay, payload);
    }

    /// Removes the job. Prevents every firing not yet claimed; a firing already running finishes and its completion is
    /// refused. Cancelling an absent job succeeds.
    public Promise<Unit> cancel(JobId id) {
        return store.cancel(id);
    }

    /// Starts polling. Idempotent while running; fails with [SchedulerError.General#CLOSED] after [#close()].
    public Promise<Unit> start() {
        return closed.get()
               ? SchedulerError.General.CLOSED.promise()
               : startOnce();
    }

    /// Stops polling, waits up to `closeGrace` for in-flight firings, then releases every claim still held (fenced by
    /// token) so another instance can claim those jobs at once instead of after the lease. Every call returns the same
    /// promise.
    public Promise<Unit> close() {
        return closed.compareAndSet(false, true)
               ? shutDown()
               : closing;
    }

    private Promise<Unit> startOnce() {
        return started.compareAndSet(false, true)
               ? beginPolling()
               : Promise.unitPromise();
    }

    private Promise<Unit> beginPolling() {
        return store.observedSkew()
                    .map(this::reportSkew)
                    .onSuccessRun(this::scheduleTick)
                    .onFailureRun(() -> started.set(false));
    }

    private Unit reportSkew(Duration skew) {
        if (skew.abs().toMillis() > config.leaseDuration().millis() / 2) {
            log.warn("Scheduler {}: local clock differs from the database clock by {}; scheduling is unaffected "
                    + "(it uses only the database clock), local timestamps in handlers are not",
                     config.instanceId().value(),
                     skew);
        }

        return Unit.unit();
    }

    @Contract
    private void scheduleTick() {
        synchronized (lifecycle) {
            if (!closed.get()) {
                nextTick = Option.some(SharedScheduler.schedule(this::tick, config.pollInterval()));
            }
        }
    }

    /// The next poll is scheduled only after this one settles, so polls of one instance never overlap. The closed check
    /// and the `inFlight` assignment share the lock with [#stopPolling()], so close always sees the batch that runs.
    @Contract
    private void tick() {
        synchronized (lifecycle) {
            if (!closed.get()) {
                inFlight = pollOnce().onResultRun(this::scheduleTick);
            }
        }
    }

    /// FER: a failed claim is logged and the next poll tries again. Nothing is lost: a claim whose commit happened but
    /// whose reply was lost holds a lease that expires, after which the job is claimed again (at-least-once).
    private Promise<Unit> pollOnce() {
        return store.claimDue(config.instanceId(),
                              config.leaseDuration(),
                              config.batchSize())
                    .flatMap(this::fireAll)
                    .onFailure(this::reportPollFailure);
    }

    /// FER: `allOf` keeps every firing's outcome and the batch never fails. A firing whose fenced write failed stays
    /// claimed until its lease expires and is then fired again (at-least-once), so dropping that failure here loses
    /// nothing.
    private Promise<Unit> fireAll(List<FiredJob> jobs) {
        return Promise.allOf(jobs.stream().map(this::fireOne).toList()).mapToUnit();
    }

    private Promise<Completion> fireOne(FiredJob job) {
        held.put(job.id(), job);

        return invoke(job).fold(result -> settle(job, result))
                     .onResultRun(() -> held.remove(job.id()));
    }

    private Promise<Unit> invoke(FiredJob job) {
        return Promise.lift(Causes::fromThrowable,
                            () -> handler.fire(job))
                      .flatMap(firing -> firing);
    }

    private Promise<Completion> settle(FiredJob job, Result<Unit> firing) {
        return firing.fold(cause -> retryLater(job, cause),
                           _ -> store.complete(job))
                     .onSuccess(outcome -> reportOutcome(job, outcome));
    }

    private Promise<Completion> retryLater(FiredJob job, Cause cause) {
        log.warn("Scheduler {}: job {} ({}) attempt {} failed, due again in {}: {}",
                 config.instanceId().value(),
                 job.id().value(),
                 job.name(),
                 job.attempt(),
                 config.retryDelay(),
                 cause.message());

        return store.reschedule(job, config.retryDelay());
    }

    @Contract
    private void reportOutcome(FiredJob job, Completion outcome) {
        if (outcome == Completion.REFUSED) {
            log.info("Scheduler {}: job {} token {} was superseded or cancelled; its write was refused",
                     config.instanceId().value(),
                     job.id().value(),
                     job.fenceToken());
        }
    }

    @Contract
    private void reportPollFailure(Cause cause) {
        log.warn("Scheduler {}: poll failed, retrying at the next poll: {}",
                 config.instanceId().value(),
                 cause.message());
    }

    private Promise<Unit> shutDown() {
        return drain(stopPolling()).onResult(closing::resolve);
    }

    private Promise<Unit> stopPolling() {
        synchronized (lifecycle) {
            nextTick.onPresent(tick -> tick.cancel(false));

            return inFlight;
        }
    }

    /// FER: the batch's outcome, including the grace timeout, is deliberately not inspected: a firing that settled has
    /// already written its own fenced outcome, and one still running is released below; its late completion is then
    /// refused or re-fires the job (at-least-once). The timeout acts on a derived promise, never on the batch itself.
    private Promise<Unit> drain(Promise<Unit> batch) {
        return batch.mapToUnit()
                    .timeout(config.closeGrace())
                    .fold(_ -> releaseHeld());
    }

    /// Design-out: a release that fails leaves the claim to expire with its lease, after which any instance claims the
    /// job; releasing only shortens that wait, so its failures are not reported.
    private Promise<Unit> releaseHeld() {
        return Promise.allOf(held.values().stream().map(job -> store.reschedule(job, NO_DELAY)).toList()).mapToUnit();
    }
}
