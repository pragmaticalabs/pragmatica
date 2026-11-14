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
/// | firing | AT-LEAST-ONCE, not exactly-once; a handler that has not resolved within `leaseDuration` of its claim is abandoned locally and the job goes back to SCHEDULED (due after `retryDelay`), so a hung handler never blocks the job or the instance | lease expiry + re-claim, per-firing timeout at the lease, fenced reschedule |
/// | latency | a due job is claimed within one `pollInterval` plus one claim round trip of a poll that has a free slot (at most `batchSize` firings in flight per instance; when all slots are busy, until one settles, at most `leaseDuration` + one fenced write) | polling is decoupled from firing: the next poll is armed when the claim settles; claim size = `batchSize` minus firings in flight |
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
/// firing is bounded by the poll interval plus the backlog ahead of the job when a slot is free, and by the lease plus a
/// fenced write when every slot is held by a slow handler; the bound assumes the database answers (a claim that never
/// returns stalls that instance's polling) and is not a real-time guarantee.
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
    private final Map<JobId, Promise<Completion>> running = new ConcurrentHashMap<>();
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

    /// The next poll is scheduled when the CLAIM of this one settles, not when its firings do, so polls of one instance
    /// never overlap but a running handler never delays the next poll. The closed check and the `inFlight` assignment
    /// share the lock with [#stopPolling()], so close always sees the claim that runs. Package-private so a test can
    /// drive the tick that was already dequeued when close ran, which the cancel in [#stopPolling()] cannot stop.
    @Contract
    void tick() {
        synchronized (lifecycle) {
            if (!closed.get()) {
                inFlight = pollOnce().onResultRun(this::scheduleTick);
            }
        }
    }

    /// FER: a failed claim is logged and the next poll tries again. Nothing is lost: a claim whose commit happened but
    /// whose reply was lost holds a lease that expires, after which the job is claimed again (at-least-once). At most
    /// `batchSize` firings are in flight per instance: a poll claims only the free slots and claims nothing when none is
    /// free.
    private Promise<Unit> pollOnce() {
        return claimUpTo(config.batchSize() - held.size());
    }

    private Promise<Unit> claimUpTo(int free) {
        return free > 0
               ? store.claimDue(config.instanceId(),
                                config.leaseDuration(),
                                free)
                      .map(this::launchAll)
                      .onFailure(this::reportPollFailure)
               : Promise.unitPromise();
    }

    private Unit launchAll(List<FiredJob> jobs) {
        jobs.forEach(this::fireOne);

        return Unit.unit();
    }

    /// FER: the firing's outcome is not awaited by the poll. A firing whose fenced write failed stays claimed until its
    /// lease expires and is then fired again (at-least-once), so dropping that failure loses nothing; [#drain] waits for
    /// every outcome in `running`. `outcome` is registered before the handler starts so a handler that settles at once
    /// still finds its entry to remove.
    @Contract
    private void fireOne(FiredJob job) {
        Promise<Completion> outcome = Promise.promise();

        held.put(job.id(), job);
        running.put(job.id(), outcome.onResultRun(() -> forget(job)));
        run(job).onResult(outcome::resolve);
    }

    @Contract
    private void forget(FiredJob job) {
        held.remove(job.id());
        running.remove(job.id());
    }

    private Promise<Completion> run(FiredJob job) {
        return invoke(job).fold(result -> settle(job, result));
    }

    /// A handler that has not resolved when its lease has run out is abandoned: the timeout fails the derived promise
    /// returned by `invoke` (never the handler's own promise), so a late resolution is ignored and cannot complete the job
    /// against a newer token; the firing is settled as failed and goes back to SCHEDULED, fenced by its token.
    private Promise<Unit> invoke(FiredJob job) {
        return Promise.lift(Causes::fromThrowable,
                            () -> handler.fire(job))
                      .flatMap(firing -> firing)
                      .timeout(config.leaseDuration());
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

    /// FER: the outcome of the last claim and of the firings, including the grace timeout, is deliberately not
    /// inspected: a firing that settled has already written its own fenced outcome, and one still running is released
    /// below; its late completion is then refused or re-fires the job (at-least-once). The timeout acts on a derived
    /// promise, never on a firing itself.
    private Promise<Unit> drain(Promise<Unit> lastClaim) {
        return lastClaim.fold(_ -> allFirings())
                        .timeout(config.closeGrace())
                        .fold(_ -> releaseHeld());
    }

    private Promise<Unit> allFirings() {
        return Promise.allOf(List.copyOf(running.values())).mapToUnit();
    }

    /// Design-out: a release that fails leaves the claim to expire with its lease, after which any instance claims the
    /// job; releasing only shortens that wait, so its failures are not reported.
    private Promise<Unit> releaseHeld() {
        return Promise.allOf(held.values().stream().map(job -> store.reschedule(job, NO_DELAY)).toList()).mapToUnit();
    }
}
