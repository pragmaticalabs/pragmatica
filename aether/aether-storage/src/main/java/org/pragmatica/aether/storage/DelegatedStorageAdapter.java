// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.storage;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.DemotionManager;
import org.pragmatica.storage.StorageGarbageCollector;

import java.util.concurrent.atomic.AtomicBoolean;

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
            @Override public int demote() {
                return 0;
            }

            @Override public DemotionStats stats() {
                return new DemotionStats(0, 0, 0);
            }

            @Override public Result<Unit> activate() {
                return Result.success(unit());
            }

            @Override public Result<Unit> deactivate() {
                return Result.success(unit());
            }

            @Override public boolean isActive() {
                return false;
            }
        };
    }

    private static StorageGarbageCollector noOpGarbageCollector() {
        return new StorageGarbageCollector() {
            @Override public int collectGarbage() {
                return 0;
            }

            @Override public GCStats stats() {
                return new GCStats(0, 0);
            }

            @Override public Result<Unit> activate() {
                return Result.success(unit());
            }

            @Override public Result<Unit> deactivate() {
                return Result.success(unit());
            }

            @Override public boolean isActive() {
                return false;
            }
        };
    }

    public Promise<Unit> activate() {
        if (active.compareAndSet(false, true)) {
            demotionManager.activate();
            garbageCollector.activate();
            log.info("STORAGE delegation group activated");
        }
        return Promise.success(unit());
    }

    public Promise<Unit> deactivate() {
        if (active.compareAndSet(true, false)) {
            garbageCollector.deactivate();
            demotionManager.deactivate();
            log.info("STORAGE delegation group deactivated");
        }
        return Promise.success(unit());
    }

    public boolean isActive() {
        return active.get();
    }
}
