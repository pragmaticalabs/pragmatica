// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.DemotionManager;
import org.pragmatica.storage.StorageGarbageCollector;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// #250 Constraint 4 pinning test: node-level storage maintenance wiring.
///
/// Before this fix, `AetherNode` constructed `DelegatedStorageAdapter.noOp()` — an adapter backed by
/// two anonymous classes whose `isActive()` is HARDCODED to always return `false`, regardless of how
/// many times `activate()` is called (see `DelegatedStorageAdapter.noOpDemotionManager()` /
/// `noOpGarbageCollector()`). Any test that boots a real `DemotionManager`/`StorageGarbageCollector`
/// through `StorageFactory` and observes `isActive()` flip to `true` after `activate()` is therefore
/// PROOF the wiring is not the no-op stand-in — the no-op is structurally incapable of passing it.
///
/// `defaultStreamStorage` is the exact production entry point `AetherNode` calls to build each storage
/// setup, so exercising it here — rather than a hand-rolled substitute — pins the real wiring path.
class StorageMaintenanceWiringTest {

    @TempDir
    Path streamDataDir;

    @TempDir
    Path streamDataDir2;

    /// The no-op's `isActive()` never leaves `false`. A real `DefaultDemotionManager` self-gates on an
    /// internal flag that `activate()` flips — so observing `true` here is only possible if
    /// `StorageFactory` wired a real manager, never `DelegatedStorageAdapter.noOp()`'s stand-in.
    @Test
    void defaultStreamStorage_demotionManager_isReal_notTheAlwaysFalseNoOp() {
        var setup = StorageFactory.defaultStreamStorage(Option.none(), streamDataDir, "test-node");

        assertThat(setup.demotionManager().isActive()).isFalse();

        setup.demotionManager().activate();

        assertThat(setup.demotionManager().isActive()).isTrue();
    }

    /// Same proof, for the garbage collector side of the pair.
    @Test
    void defaultStreamStorage_garbageCollector_isReal_notTheAlwaysFalseNoOp() {
        var setup = StorageFactory.defaultStreamStorage(Option.none(), streamDataDir, "test-node");

        assertThat(setup.garbageCollector().isActive()).isFalse();

        setup.garbageCollector().activate();

        assertThat(setup.garbageCollector().isActive()).isTrue();
    }

    /// The composite (what `AetherNode` actually hands to `DelegatedStorageAdapter` and
    /// `StorageMaintenanceDriver`) must fan `activate()` out to every underlying real setup, not just
    /// flip its own flag — pinning that leader-pinned activation reaches every storage instance.
    @Test
    void compositeDemotionManager_activate_fansOutToEveryUnderlyingSetup() {
        var setup1 = StorageFactory.defaultStreamStorage(Option.none(), streamDataDir, "node-1");
        var setup2 = StorageFactory.defaultStreamStorage(Option.none(), streamDataDir2, "node-2");
        var composite = StorageFactory.compositeDemotionManager(Map.of("a", setup1, "b", setup2));

        assertThat(composite.isActive()).isFalse();

        composite.activate();

        assertThat(composite.isActive()).isTrue();
        assertThat(setup1.demotionManager().isActive()).isTrue();
        assertThat(setup2.demotionManager().isActive()).isTrue();
    }

    /// The other Constraint 4 half: the driver must actually CALL `demote()`/`collectGarbage()` on
    /// every tick, not merely hold references to them.
    @Test
    void storageMaintenanceDriver_tick_invokesBothDemoteAndCollectGarbage() {
        var demoteCalls = new AtomicInteger();
        var gcCalls = new AtomicInteger();
        var driver = StorageMaintenanceDriver.storageMaintenanceDriver(countingDemotionManager(demoteCalls),
                                                                        countingGarbageCollector(gcCalls));

        driver.tick();

        assertThat(demoteCalls.get()).isEqualTo(1);
        assertThat(gcCalls.get()).isEqualTo(1);

        driver.tick();

        assertThat(demoteCalls.get()).isEqualTo(2);
        assertThat(gcCalls.get()).isEqualTo(2);
    }

    private static DemotionManager countingDemotionManager(AtomicInteger calls) {
        return new DemotionManager() {
            @Override
            public int demote() {
                calls.incrementAndGet();
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
                return true;
            }
        };
    }

    private static StorageGarbageCollector countingGarbageCollector(AtomicInteger calls) {
        return new StorageGarbageCollector() {
            @Override
            public int collectGarbage() {
                calls.incrementAndGet();
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
                return true;
            }
        };
    }
}
