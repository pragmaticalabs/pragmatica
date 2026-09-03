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
    /// mean the group is really running. The CAS still gates re-entry (only one caller proceeds past
    /// it at a time); on any manager failure it is rolled back to `false` before this method returns.
    public Promise<Unit> activate() {
        if (!active.compareAndSet(false, true)) {
            return Promise.success(unit());
        }

        Result.all(demotionManager.activate().onFailure(cause -> logActivationFailure("demotion manager", cause)),
                   garbageCollector.activate().onFailure(cause -> logActivationFailure("garbage collector", cause)))
              .map((_, _) -> unit())
              .onSuccessRun(() -> log.info("STORAGE delegation group activated"))
              .onFailureRun(() -> active.set(false));

        return Promise.success(unit());
    }

    public Promise<Unit> deactivate() {
        if (active.compareAndSet(true, false)) {
            garbageCollector.deactivate().onFailure(cause -> logDeactivationFailure("garbage collector", cause));
            demotionManager.deactivate().onFailure(cause -> logDeactivationFailure("demotion manager", cause));
            log.info("STORAGE delegation group deactivated");
        }

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
