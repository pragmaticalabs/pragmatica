// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.lang.Contract;
import org.pragmatica.storage.DemotionManager;
import org.pragmatica.storage.StorageGarbageCollector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// #250: the periodic tick that makes storage demotion and garbage collection actually run.
///
/// Before this driver existed, `AetherNode` wired storage through `DelegatedStorageAdapter.noOp()` —
/// the leader-pinned activation/deactivation plumbing (`toggleStorageOnLeaderChange`) was correct, but
/// nothing ever called `.demote()` or `.collectGarbage()`, so tiered storage never actually shrank in
/// production. This class is the missing caller.
///
/// It calls both operations unconditionally on every tick, regardless of leadership state. That is safe
/// because both `DefaultDemotionManager.demote()` and `DefaultStorageGarbageCollector.collectGarbage()`
/// self-gate on an internal `active` flag (`if (!active) return 0;`) that the leader-pinned adapter
/// toggles — so a tick on a non-leader (or before the adapter has activated) is simply a no-op read of
/// that flag, not a correctness hazard.
///
/// GC never touches the shared DHT tier: that invariant is enforced inside
/// `StorageInstance.deleteFromPrivateTiers` (integrations/storage), over whatever tier list the
/// collector is handed, independent of anything this driver does.
public final class StorageMaintenanceDriver {
    private static final Logger log = LoggerFactory.getLogger(StorageMaintenanceDriver.class);

    private final DemotionManager demotionManager;
    private final StorageGarbageCollector garbageCollector;

    private StorageMaintenanceDriver(DemotionManager demotionManager, StorageGarbageCollector garbageCollector) {
        this.demotionManager = demotionManager;
        this.garbageCollector = garbageCollector;
    }

    public static StorageMaintenanceDriver storageMaintenanceDriver(DemotionManager demotionManager,
                                                                    StorageGarbageCollector garbageCollector) {
        return new StorageMaintenanceDriver(demotionManager, garbageCollector);
    }

    /// Runs one demotion pass followed by one GC pass. Logs only when either pass actually moved
    /// something, so an inactive or idle cluster does not spam the log every tick.
    /// Fire-and-forget scheduler entry point (`storageMaintenanceDriver::tick` on a fixed-rate timer),
    /// same shape as `EntityCheckpointDriver.tick()` — a `void` tick is the established convention for
    /// this integration point, not a return-type omission.
    @Contract
    public void tick() {
        var demoted = demotionManager.demote();
        var collected = garbageCollector.collectGarbage();

        if (demoted > 0 || collected > 0) {
            log.info("Storage maintenance tick: {} block(s) demoted, {} block(s) garbage-collected", demoted, collected);
        }
    }
}
