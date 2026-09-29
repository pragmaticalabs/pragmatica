// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.storage;

import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.DemotionManager;
import org.pragmatica.storage.StorageGarbageCollector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


public final class DelegatedStorageAdapter {
    private static final Logger log = LoggerFactory.getLogger(DelegatedStorageAdapter.class);

    private final DemotionManager demotionManager;
    private final StorageGarbageCollector garbageCollector;
    private final AtomicBoolean active = new AtomicBoolean(false);

    private DelegatedStorageAdapter(DemotionManager demotionManager, StorageGarbageCollector garbageCollector) {
        this.demotionManager = demotionManager;
        this.garbageCollector = garbageCollector;
    }

    public static DelegatedStorageAdapter delegatedStorageAdapter(DemotionManager demotionManager,
                                                                  StorageGarbageCollector garbageCollector) {
        return new DelegatedStorageAdapter(demotionManager, garbageCollector);
    }

    public static DelegatedStorageAdapter noOp() {
        return new DelegatedStorageAdapter(noOpDemotionManager(), noOpGarbageCollector());
    }

    private static DemotionManager noOpDemotionManager() {
        return new DemotionManager() {
            @Override
            public int demote() {
                return 0;
            }

            @Override
            public DemotionStats stats() {
                return new DemotionStats(0, 0, 0);
            }

            @Override
            public Result<Unit> activate() {
                return Result.success(unit());
            }

            @Override
            public Result<Unit> deactivate() {
                return Result.success(unit());
            }

            @Override
            public boolean isActive() {
                return false;
            }
        };
    }

    private static StorageGarbageCollector noOpGarbageCollector() {
        return new StorageGarbageCollector() {
            @Override
            public int collectGarbage() {
                return 0;
            }

            @Override
            public GCStats stats() {
                return new GCStats(0, 0);
            }

            @Override
            public Result<Unit> activate() {
                return Result.success(unit());
            }

            @Override
            public Result<Unit> deactivate() {
                return Result.success(unit());
            }

            @Override
            public boolean isActive() {
                return false;
            }
        };
    }

    /// #250 review: `active` must reflect whether both managers actually started, not merely that
    /// activation was attempted -- a caller reading `isActive()` after this returns needs "true" to
    /// mean the group is really running, so the flag is set only once both activations succeeded.
    ///
    /// #804: a PARTIAL activation (one manager started, the other failed) stops the one that started
    /// before returning. Otherwise it kept running while the adapter reported inactive, and no later
    /// `deactivate()` could reach it, because that guards on the adapter being active. Both methods are
    /// synchronized so an activation and a deactivation never interleave their manager calls.
    public synchronized Promise<Unit> activate() {
        if (active.get()) {
            return Promise.success(unit());
        }

        var demotion = demotionManager.activate()
                                      .onFailure(cause -> logActivationFailure("demotion manager", cause));
        var collector = garbageCollector.activate()
                                        .onFailure(cause -> logActivationFailure("garbage collector", cause));

        Result.all(demotion, collector)
              .map((_, _) -> unit())
              .onSuccessRun(() -> active.set(true))
              .onSuccessRun(() -> log.info("STORAGE delegation group activated"))
              .onFailureRun(() -> stopPartiallyActivated(demotion, collector));

        return Promise.success(unit());
    }

    private void stopPartiallyActivated(Result<Unit> demotion, Result<Unit> collector) {
        demotion.onSuccessRun(() -> demotionManager.deactivate()
                                                   .onFailure(cause -> logDeactivationFailure("demotion manager",
                                                                                              cause)));
        collector.onSuccessRun(() -> garbageCollector.deactivate()
                                                     .onFailure(cause -> logDeactivationFailure("garbage collector",
                                                                                                cause)));
    }

    /// #804 — mirror of `activate()`: the adapter reports inactive only once BOTH managers deactivated.
    /// A failed deactivation keeps it active (a maintenance pass may still be scheduled or running) and
    /// is logged at WARN with the cause; a later `deactivate()` retries.
    public synchronized Promise<Unit> deactivate() {
        if (!active.get()) {
            return Promise.success(unit());
        }

        Result.all(garbageCollector.deactivate()
                                   .onFailure(cause -> logDeactivationFailure("garbage collector", cause)),
                   demotionManager.deactivate()
                                  .onFailure(cause -> logDeactivationFailure("demotion manager", cause)))
              .map((_, _) -> unit())
              .onSuccessRun(() -> active.set(false))
              .onSuccessRun(() -> log.info("STORAGE delegation group deactivated"));

        return Promise.success(unit());
    }

    public boolean isActive() {
        return active.get();
    }

    /// #250 review: `activate()`/`deactivate()` on the delegated managers now do real work (they were
    /// no-ops before #250) and can fail -- discarding the `Result` would hide a manager that never
    /// actually started or stopped while the adapter reports itself active/inactive regardless.
    private static void logActivationFailure(String component, Cause cause) {
        log.warn("STORAGE {} activation failed: {}", component, cause.message());
    }

    private static void logDeactivationFailure(String component, Cause cause) {
        log.warn("STORAGE {} deactivation failed: {}", component, cause.message());
    }
}
