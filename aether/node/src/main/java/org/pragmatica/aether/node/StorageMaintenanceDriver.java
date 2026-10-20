// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.DemotionManager;
import org.pragmatica.storage.PromotionManager;
import org.pragmatica.storage.StorageGarbageCollector;

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
/// correctness hazard. Who sets it differs: GC and `streams` demotion follow the leader through
/// `DelegatedStorageAdapter`, while demotion and promotion of `artifacts` and `content` are activated on every node
/// when it reports ready (`StorageFactory#activateNodeMaintenance`).
///
/// Order within a tick: demote, then promote, then GC. Demotion frees the room that promotion then uses.
/// Promotion blocks on a read of the source tier per block (a DHT read for the slowest), so it does not run on the
/// scheduler thread: the tick starts it on its own thread and returns. At most one cycle runs at a time; a tick that
/// finds the previous cycle still running skips promotion and counts nothing, so a slow DHT cannot pile cycles up.
///
/// GC never touches the shared DHT tier: that invariant is enforced inside
/// `StorageInstance.deleteFromPrivateTiers` (integrations/storage), over whatever tier list the
/// collector is handed, independent of anything this driver does.
public final class StorageMaintenanceDriver {
    private static final Logger log = LoggerFactory.getLogger(StorageMaintenanceDriver.class);

    private final DemotionManager demotionManager;
    private final PromotionManager promotionManager;
    private final PromotionStallWatch stallWatch;
    private final StorageGarbageCollector garbageCollector;
    private final Executor promotionExecutor;
    private final AtomicBoolean promoting = new AtomicBoolean(false);

    private StorageMaintenanceDriver(DemotionManager demotionManager,
                                     PromotionManager promotionManager,
                                     PromotionStallWatch stallWatch,
                                     StorageGarbageCollector garbageCollector,
                                     Executor promotionExecutor) {
        this.demotionManager = demotionManager;
        this.promotionManager = promotionManager;
        this.stallWatch = stallWatch;
        this.garbageCollector = garbageCollector;
        this.promotionExecutor = promotionExecutor;
    }

    static StorageMaintenanceDriver storageMaintenanceDriver(DemotionManager demotionManager,
                                                             PromotionManager promotionManager,
                                                             PromotionStallWatch stallWatch,
                                                             StorageGarbageCollector garbageCollector) {
        return storageMaintenanceDriver(demotionManager,
                                        promotionManager,
                                        stallWatch,
                                        garbageCollector,
                                        StorageMaintenanceDriver::startOnDaemonThread);
    }

    /// `promotionExecutor` runs one promotion cycle; the production one is a fresh daemon thread per cycle (a cycle
    /// starts every few minutes at most, so a pool would only hold an idle thread per node).
    static StorageMaintenanceDriver storageMaintenanceDriver(DemotionManager demotionManager,
                                                             PromotionManager promotionManager,
                                                             PromotionStallWatch stallWatch,
                                                             StorageGarbageCollector garbageCollector,
                                                             Executor promotionExecutor) {
        return new StorageMaintenanceDriver(demotionManager,
                                            promotionManager,
                                            stallWatch,
                                            garbageCollector,
                                            promotionExecutor);
    }

    @Contract
    private static void startOnDaemonThread(Runnable cycle) {
        Thread.ofPlatform().daemon().name("storage-promotion").start(cycle);
    }

    /// Runs one demotion pass, starts one promotion cycle unless the previous one is still running, then runs one GC
    /// pass. Logs only when a pass actually moved something, so an inactive or idle cluster does not spam the log every
    /// tick. Fire-and-forget scheduler entry point (`storageMaintenanceDriver::tick` on a fixed-rate timer),
    /// same shape as `EntityCheckpointDriver.tick()` — a `void` tick is the established convention for
    /// this integration point, not a return-type omission.
    @Contract
    public void tick() {
        var demoted = demotionManager.demote();

        startPromotionUnlessRunning();
        var collected = garbageCollector.collectGarbage();

        if (demoted > 0 || collected > 0) {
            log.info("Storage maintenance tick: {} block(s) demoted, {} block(s) garbage-collected", demoted, collected);
        }
    }

    @Contract
    private void startPromotionUnlessRunning() {
        if (promoting.compareAndSet(false, true)) {
            promotionExecutor.execute(this::runPromotionCycle);
        }
    }

    /// One promotion cycle, then the stall check on what it did. FER (degrade forward): a failed cycle is logged and
    /// dropped, and the next tick starts a fresh one; nothing waits on a cycle having run, and the guard is released
    /// either way, or one bad cycle would stop promotion on this node for good. The guarantee is only that promotion
    /// retries every tick; a cycle that keeps failing is surfaced by the stall watch, not by this log line.
    @Contract
    private void runPromotionCycle() {
        Result.lift(Causes::fromThrowable, this::promoteAndObserve)
              .onFailure(cause -> log.warn("Storage promotion cycle failed: {}",
                                           cause.message()))
              .onSuccess(StorageMaintenanceDriver::logPromoted);
        promoting.set(false);
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
