// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.node.StorageMaintenanceProgress.Outcome;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.DegradeReporter;
import org.pragmatica.storage.DemotionManager;
import org.pragmatica.storage.PromotionManager;
import org.pragmatica.storage.StorageGarbageCollector;
import org.pragmatica.utility.warning.OperatorWarningCode;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// #250: the periodic tick that makes storage demotion, promotion and garbage collection actually run.
///
/// Before this driver existed, `AetherNode` wired storage through `DelegatedStorageAdapter.noOp()` —
/// the leader-pinned activation/deactivation plumbing (`toggleStorageOnLeaderChange`) was correct, but
/// nothing ever called `.demote()` or `.collectGarbage()`, so tiered storage never actually shrank in
/// production. This class is the missing caller.
///
/// It calls every operation unconditionally on every tick, regardless of leadership state. That is safe
/// because `DefaultDemotionManager.demote()`, the promotion manager's `promote()` and
/// `DefaultStorageGarbageCollector.collectGarbage()` self-gate on an internal `active` flag
/// (`if (!active) return 0;`) — so a tick before the flag is set is simply a no-op read of that flag, not a
/// correctness hazard. Who sets it differs: `streams` demotion follows the leader through
/// `DelegatedStorageAdapter`, `streams` GC is active from build on every node, and demotion, promotion and GC of
/// `artifacts` and `content` are activated on every node when it reports ready (`StorageFactory#activateNodeMaintenance`).
///
/// Order within a tick: demote, then promote, then GC. Demotion frees the room that promotion then uses.
/// Promotion blocks on a read of the source tier per block (a DHT read for the slowest), so it does not run on the
/// scheduler thread: the tick starts it on its own thread and returns. At most one cycle runs at a time; a tick that
/// finds the previous cycle still running skips promotion and counts nothing, so a slow DHT cannot pile cycles up.
///
/// #800: a pass that does not complete is a signal too. [#checkOverdue], on its own timer, raises
/// `STORAGE_MAINTENANCE_PASS_OVERDUE` when no tick completed within [#OVERDUE_AFTER_INTERVALS] maintenance intervals, or one
/// promotion cycle ran that long; the next completed tick or cycle raises the recovery. [#degradation] is the same reading
/// plus a failed-tick streak, for the `storage-maintenance` component of `/api/health`; [#registerMeters] exposes the streak,
/// the age of the last completed tick and the last outcome as gauges. All of it reads the injected clock.
///
/// GC never touches the shared DHT tier: that invariant is enforced inside
/// `StorageInstance.deleteFromPrivateTiers` (integrations/storage), over whatever tier list the
/// collector is handed, independent of anything this driver does.
public final class StorageMaintenanceDriver {
    private static final Logger log = LoggerFactory.getLogger(StorageMaintenanceDriver.class);
    /// The parts of one maintenance tick, as the operator-warning subject names them (`storage-maintenance/<part>`).
    static final String PART_DEMOTION = "demotion";
    static final String PART_PROMOTION = "promotion";
    static final String PART_GARBAGE_COLLECTION = "garbage-collection";
    static final String PART_RETRY_PENDING = "retry-pending";
    /// The whole tick, as the overdue warning names it: no tick completed, whichever pass holds it.
    static final String PART_TICK = "tick";
    /// How many maintenance intervals a pass may take before it is overdue.
    static final int OVERDUE_AFTER_INTERVALS = 3;
    private static final long NO_CYCLE = -1L;
    /// The instance of the driver's operator-warning reporter.
    public static final String REPORT_SUBJECT = "storage-maintenance";

    private final DemotionManager demotionManager;
    private final PromotionManager promotionManager;
    private final PromotionStallWatch stallWatch;
    private final StorageGarbageCollector garbageCollector;
    private final Executor promotionExecutor;
    private final DegradeReporter degrade;
    private final Supplier<Unit> retryPending;
    private final LongSupplier clock;
    private final long overdueAfterMs;
    private final long createdMs;
    private final AtomicBoolean promoting = new AtomicBoolean(false);
    /// When the running promotion cycle started, [#NO_CYCLE] while none runs.
    private final AtomicLong promotionStartedMs = new AtomicLong(NO_CYCLE);

    /// The result of the latest promotion cycle that ended and no tick has counted yet.
    private final AtomicReference<Option<Result<Integer>>> promotionResult = new AtomicReference<>(Option.none());

    private final AtomicReference<StorageMaintenanceProgress> progress = new AtomicReference<>(StorageMaintenanceProgress.storageMaintenanceProgress());

    private StorageMaintenanceDriver(DemotionManager demotionManager,
                                     PromotionManager promotionManager,
                                     PromotionStallWatch stallWatch,
                                     StorageGarbageCollector garbageCollector,
                                     Executor promotionExecutor,
                                     DegradeReporter degrade,
                                     Supplier<Unit> retryPending,
                                     LongSupplier clock,
                                     TimeSpan interval) {
        this.demotionManager = demotionManager;
        this.promotionManager = promotionManager;
        this.stallWatch = stallWatch;
        this.garbageCollector = garbageCollector;
        this.promotionExecutor = promotionExecutor;
        this.degrade = degrade;
        this.retryPending = retryPending;
        this.clock = clock;
        this.overdueAfterMs = OVERDUE_AFTER_INTERVALS * interval.millis();
        this.createdMs = clock.getAsLong();
    }

    public static StorageMaintenanceDriver storageMaintenanceDriver(DemotionManager demotionManager,
                                                                    PromotionManager promotionManager,
                                                                    PromotionStallWatch stallWatch,
                                                                    StorageGarbageCollector garbageCollector,
                                                                    DegradeReporter degrade,
                                                                    Supplier<Unit> retryPending,
                                                                    TimeSpan interval) {
        return storageMaintenanceDriver(demotionManager,
                                        promotionManager,
                                        stallWatch,
                                        garbageCollector,
                                        StorageMaintenanceDriver::startOnDaemonThread,
                                        degrade,
                                        retryPending,
                                        System::currentTimeMillis,
                                        interval);
    }

    /// `promotionExecutor` runs one promotion cycle; the production one is a fresh daemon thread per cycle (a cycle
    /// starts every few minutes at most, so a pool would only hold an idle thread per node). `clock` is wall-clock
    /// milliseconds; `interval` is the maintenance tick interval, which the overdue reading is measured in.
    static StorageMaintenanceDriver storageMaintenanceDriver(DemotionManager demotionManager,
                                                             PromotionManager promotionManager,
                                                             PromotionStallWatch stallWatch,
                                                             StorageGarbageCollector garbageCollector,
                                                             Executor promotionExecutor,
                                                             DegradeReporter degrade,
                                                             Supplier<Unit> retryPending,
                                                             LongSupplier clock,
                                                             TimeSpan interval) {
        return new StorageMaintenanceDriver(demotionManager,
                                            promotionManager,
                                            stallWatch,
                                            garbageCollector,
                                            promotionExecutor,
                                            degrade,
                                            retryPending,
                                            clock,
                                            interval);
    }

    @Contract
    private static void startOnDaemonThread(Runnable cycle) {
        Thread.ofPlatform().daemon().name("storage-promotion").start(cycle);
    }

    /// Runs one demotion pass, starts one promotion cycle unless the previous one is still running, then runs one GC
    /// pass and the retry of a pending activation. Logs only when a pass actually moved something, so an inactive or idle cluster does not spam the log every
    /// tick. Fire-and-forget scheduler entry point (`storageMaintenanceDriver::tick` on a fixed-rate timer),
    /// same shape as `EntityCheckpointDriver.tick()` — a `void` tick is the established convention for
    /// this integration point, not a return-type omission.
    @Contract
    public void tick() {
        var demotion = runPass(PART_DEMOTION, demotionManager::demote);

        startPromotionUnlessRunning();
        var collection = runPass(PART_GARBAGE_COLLECTION, garbageCollector::collectGarbage);

        var retry = runPass(PART_RETRY_PENDING, this::retryPendingPass);
        var promotion = promotionResult.getAndSet(Option.none());

        progress.updateAndGet(before -> before.after(clock.getAsLong(), demotion, promotion, collection, retry));
        raisePassCompleted(PART_TICK);
        logMoved(demotion.or(0), collection.or(0));
    }

    /// What the latest tick did and how many ticks in a row failed (#800); the Management API serves it.
    public StorageMaintenanceProgress progress() {
        return progress.get();
    }

    /// The maintenance watchdog (#800), on its own timer so that a blocked tick cannot hold it: raises the overdue warning
    /// for the tick and for a promotion cycle that has not completed within [#OVERDUE_AFTER_INTERVALS] intervals, once per
    /// part. The completed tick or cycle closes it. Fire-and-forget scheduler entry point, as [#tick].
    @Contract
    public void checkOverdue() {
        var now = clock.getAsLong();

        overdueTick(now).onPresent(reason -> raiseOverdue(PART_TICK, reason));
        overduePromotion(now).onPresent(reason -> raiseOverdue(PART_PROMOTION, reason));
    }

    /// Why storage maintenance on this node is degraded, none while it is not (#800): ticks in a row with a failed pass, no
    /// tick completed within [#OVERDUE_AFTER_INTERVALS] intervals, or a promotion cycle running that long.
    public Option<String> degradation() {
        var now = clock.getAsLong();

        return failedStreak().orElse(() -> overdueTick(now))
                           .orElse(() -> overduePromotion(now));
    }

    /// Seconds since the latest tick completed, or since the driver was built before the first one.
    public long secondsSinceLastPass() {
        return (clock.getAsLong() - lastCompletedMs()) / 1000;
    }

    /// Registers the maintenance gauges (#800) in `registry`: `aether_storage_maintenance_consecutive_failed_ticks`,
    /// `aether_storage_maintenance_seconds_since_last_pass`, and `aether_storage_maintenance_last_outcome`, which is 1 for the
    /// `outcome` tag of the latest tick and 0 for the others.
    public Unit registerMeters(MeterRegistry registry) {
        Gauge.builder("aether_storage_maintenance_consecutive_failed_ticks",
                      () -> progress().consecutiveFailedTicks())
             .description("Storage maintenance ticks in a row with a failed pass")
             .register(registry);
        Gauge.builder("aether_storage_maintenance_seconds_since_last_pass", this::secondsSinceLastPass)
             .description("Seconds since the latest storage maintenance tick completed")
             .register(registry);
        for (var outcome : Outcome.values()) {
            registerOutcomeGauge(registry, outcome);
        }

        return Unit.unit();
    }

    private void registerOutcomeGauge(MeterRegistry registry, Outcome outcome) {
        Gauge.builder("aether_storage_maintenance_last_outcome",
                      () -> isLastOutcome(outcome))
             .description("1 for the outcome of the latest storage maintenance tick, 0 for the others")
             .tag("outcome",
                  outcome.name())
             .register(registry);
    }

    private int isLastOutcome(Outcome outcome) {
        return progress().outcome() == outcome
               ? 1
               : 0;
    }

    private Option<String> failedStreak() {
        var current = progress.get();

        return Option.some(current.consecutiveFailedTicks())
                     .filter(streak -> streak > 0)
                     .map(streak -> streak
                                   + " maintenance tick(s) in a row had a failed pass, latest: " + current.lastFailure()
                                                                                                          .or("unknown"));
    }

    private Option<String> overdueTick(long now) {
        return Option.some(now - lastCompletedMs())
                     .filter(elapsed -> elapsed > overdueAfterMs)
                     .map(elapsed -> "no maintenance tick completed for " + elapsed / 1000 + " s");
    }

    private Option<String> overduePromotion(long now) {
        return Option.some(promotionStartedMs.get())
                     .filter(started -> started != NO_CYCLE)
                     .map(started -> now - started)
                     .filter(elapsed -> elapsed > overdueAfterMs)
                     .map(elapsed -> "the promotion cycle has been running for " + elapsed / 1000 + " s");
    }

    private long lastCompletedMs() {
        return Math.max(createdMs,
                        progress.get().lastRunMs());
    }

    private void raiseOverdue(String part, String reason) {
        degrade.degradedFor(OperatorWarningCode.STORAGE_MAINTENANCE_PASS_OVERDUE,
                            part,
                            "Storage maintenance {} is overdue: {}",
                            part,
                            reason);
    }

    private void raisePassCompleted(String part) {
        degrade.recoveredFor(OperatorWarningCode.STORAGE_MAINTENANCE_PASS_OVERDUE,
                             part,
                             "Storage maintenance {} completed again",
                             part);
    }

    /// The retry of a pending activation or deactivation as a pass of the tick (#3087), so that a throw is the tick's failed pass,
    /// reported as itself, instead of escaping the tick and leaving it without a progress record.
    private int retryPendingPass() {
        retryPending.get();

        return 0;
    }

    /// One pass of the tick. A throw is the pass's failure (BER-free: nothing is retried here, the next tick runs the pass
    /// again), raised once per part as an operator warning and closed by the next pass that completes, so that a failing
    /// demotion no longer keeps garbage collection from running.
    private Result<Integer> runPass(String part, Supplier<Integer> pass) {
        return Result.lift(Causes::fromThrowable, pass::get)
                     .onFailure(cause -> raisePassFailed(part,
                                                         cause.message()))
                     .onSuccessRun(() -> raisePassRecovered(part));
    }

    private void raisePassFailed(String part, String cause) {
        degrade.degradedFor(OperatorWarningCode.STORAGE_MAINTENANCE_PASS_FAILED,
                            part,
                            "Storage maintenance {} pass failed: {}; it is tried again on the next tick",
                            part,
                            cause);
    }

    private void raisePassRecovered(String part) {
        degrade.recoveredFor(OperatorWarningCode.STORAGE_MAINTENANCE_PASS_FAILED,
                             part,
                             "Storage maintenance {} pass completed again",
                             part);
    }

    @Contract
    private static void logMoved(int demoted, int collected) {
        if (demoted > 0 || collected > 0) {
            log.info("Storage maintenance tick: {} block(s) demoted, {} block(s) garbage-collected", demoted, collected);
        }
    }

    /// FER (degrade forward): if the cycle cannot even be started (a thread that will not start, a refusing executor), the
    /// guard is released and the failure logged, and the next tick tries again; promotion is an optimization, so one lost
    /// cycle costs nothing but that cycle. Without the release the guard would stay set and promotion on this node would
    /// stop for good, silently. `Throwable`, not `Exception`: a thread that cannot be created is an `OutOfMemoryError`.
    @Contract
    @SuppressWarnings("JBCT-EX-01")
    private void startPromotionUnlessRunning() {
        if (promoting.compareAndSet(false, true)) {
            try {
                promotionStartedMs.set(clock.getAsLong());
                promotionExecutor.execute(this::runPromotionCycle);
            } catch (Throwable t) {
                promotionStartedMs.set(NO_CYCLE);
                promoting.set(false);
                promotionResult.set(Option.some(Causes.fromThrowable(t).<Integer> result()));
                raisePassFailed(PART_PROMOTION, t.toString());
                log.warn("Storage promotion cycle could not be started: {} -- the next tick tries again", t.toString());
            }
        }
    }

    /// One promotion cycle, then the stall check on what it did. FER (degrade forward): a failed cycle is logged and
    /// dropped, and the next tick starts a fresh one; nothing waits on a cycle having run. The guard is released in a
    /// `finally`, so it is released however the cycle ends, including by an `Error` that `Result.lift` rethrows
    /// (a `VirtualMachineError`); otherwise one such cycle would stop promotion on this node for good. The guarantee is only
    /// that promotion retries every tick; a cycle that keeps failing is surfaced by the stall watch, not by this log line.
    /// The cycle's result is left for the next tick's progress record (#800), and its end closes an overdue warning.
    @Contract
    @SuppressWarnings("JBCT-EX-01")
    private void runPromotionCycle() {
        try {
            var result = Result.lift(Causes::fromThrowable, this::promoteAndObserve)
                               .onFailure(cause -> log.warn("Storage promotion cycle failed: {}",
                                                            cause.message()))
                               .onFailure(cause -> raisePassFailed(PART_PROMOTION,
                                                                   cause.message()))
                               .onSuccessRun(() -> raisePassRecovered(PART_PROMOTION))
                               .onSuccess(StorageMaintenanceDriver::logPromoted);

            promotionResult.set(Option.some(result));
            raisePassCompleted(PART_PROMOTION);
        } finally {
            promotionStartedMs.set(NO_CYCLE);
            promoting.set(false);
        }
    }

    private int promoteAndObserve() {
        var promoted = promotionManager.promote();

        stallWatch.observe();

        return promoted;
    }

    @Contract
    private static void logPromoted(int promoted) {
        if (promoted > 0) {
            log.info("Storage promotion cycle: {} block(s) promoted", promoted);
        }
    }
}
