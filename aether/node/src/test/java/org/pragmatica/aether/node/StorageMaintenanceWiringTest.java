// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.BlockLifecycle;
import org.pragmatica.storage.DemotionManager;
import org.pragmatica.storage.GarbageCollectorConfig;
import org.pragmatica.storage.StorageGarbageCollector;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Arrays.copyOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.option;
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

    /// Explicit `[storage.artifacts]` temp dir for the #783 test below -- redirects `content`'s
    /// synthesized sibling disk path (`StorageFactory.defaultContentConfig`) to somewhere writable;
    /// the hardcoded `StorageConfig.storageConfig()` default (`/data/aether/storage`) is not
    /// creatable in a test sandbox.
    @TempDir
    Path artifactsDir;

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

    /// #250 review, item 3: the four tests above all pass `Option.none()` for the DHT client, so
    /// none of them prove a real `DhtStorageTier` ever reaches the tier list `StorageInstance`,
    /// `DemotionManager`, and `StorageGarbageCollector` are handed by `assembleStreamSetup` --
    /// they exercise the demotion/GC wiring but never the shared-tier branch of it.
    ///
    /// This test builds the production setup WITH a DHT tier present (fake client), writes
    /// through it, forces the resulting block orphaned and past the (hardcoded, 1-hour)
    /// GC grace period, runs a maintenance pass (`demote()` + `collectGarbage()`), and asserts
    /// the content still comes back. `deleteFromPrivateTiers` always removes the memory/disk
    /// copies once a block is orphan-eligible -- regardless of the shared-tier guard -- so a
    /// successful read afterward is only possible because the block is still sitting in the DHT
    /// tier, which is only true if that tier actually reached the collector's tier list AND the
    /// guard held. Either failure (tier dropped from the list, or guard removed) collapses this
    /// to `Option.none()`.
    @Test
    void defaultStreamStorage_maintenancePass_neverDeletesFromOrDemotesOutOfSharedDhtTier() {
        var dhtClient = new InMemoryDHTClient();
        var setup = StorageFactory.defaultStreamStorage(Option.some(dhtClient), streamDataDir, "test-node");
        var content = "shared-tier-content".getBytes(StandardCharsets.UTF_8);

        var blockId = setup.instance().put(content).await().unwrap();

        assertThat(dhtClient.isEmpty())
            .as("write-through with a DHT tier present writes the durable copy to the DHT tier "
               + "first (it is always the last/durable tier when present) -- an empty backing "
               + "store here means the DHT tier never received the wiring's tier list at all")
            .isFalse();

        forceOrphanedPastGracePeriod(setup, blockId);

        setup.demotionManager().activate();
        setup.garbageCollector().activate();

        setup.demotionManager().demote();
        setup.garbageCollector().collectGarbage();

        setup.instance()
             .get(blockId)
             .await()
             .onFailure(cause -> fail("get should not fail: " + cause.message()))
             .onSuccess(opt -> assertThat(opt.isPresent())
                 .as("private (memory/disk) copies are gone once a block is orphan-eligible; "
                    + "content must still be readable from the shared DHT tier -- empty means "
                    + "the maintenance pass deleted from, or demoted out of, the shared tier")
                 .isTrue());
    }

    /// #783 C4 ("maintenance is real"): before this fix, `content`'s `StorageInstance` was built
    /// by `StorageFactory.defaultContentStorage` entirely outside `storageSetups` -- it never
    /// registered a `StorageSetup`, so `AetherNode`'s `compositeDemotionManager` /
    /// `compositeGarbageCollector` (built by fanning out over `storageSetups`, see
    /// `StorageFactory.compositeDemotionManager`/`compositeGarbageCollector`) never reached it, and
    /// `StorageMaintenanceDriver.tick()` never demoted or GC'd a single content block no matter how
    /// long a node ran. `createAll` now synthesizes a "content" entry (`StorageFactory.CONTENT_NAME`)
    /// through the exact same path as every other instance whenever `[storage.content]` was not
    /// explicit, so it lands in `storageSetups` and is covered like `artifacts`/`streams`.
    ///
    /// This builds the REAL `createAll` result (an explicit writable `[storage.artifacts]` section
    /// redirects content's synthesized sibling path off the hardcoded `/data/aether/storage`
    /// default -- see the `artifactsDir` field javadoc), the REAL composite demotion/GC managers
    /// over the FULL `setups` map (not just content, to prove the real fan-out reaches it), and the
    /// REAL `StorageMaintenanceDriver` -- then ticks once and asserts the shared DHT copy of a
    /// content block, forced orphaned and past the GC grace cutoff, survives the pass (mirroring
    /// `defaultStreamStorage_maintenancePass_neverDeletesFromOrDemotesOutOfSharedDhtTier` above: a
    /// successful read after the pass is only possible if the private/disk copy was actually
    /// removed by a real GC/demotion pass that reached the DHT-tier guard).
    ///
    /// Red-before: reverting only the `configs.containsKey(CONTENT_NAME)` synthesis hunk in
    /// `StorageFactory.createAll` leaves "content" absent from `storageSetups` --
    /// `assertThat(setups).containsKey(CONTENT)` below fails immediately, before the driver is even
    /// built.
    @Test
    void createAll_realMaintenanceDriverTick_reachesSynthesizedContentInstance() {
        var dhtClient = new InMemoryDHTClient();
        var defaults = StorageConfig.storageConfig();
        var artifactsConfig = new StorageConfig(defaults.memoryMaxBytes(),
                                                defaults.diskMaxBytes(),
                                                artifactsDir.toString(),
                                                artifactsDir.resolve("snapshots").toString(),
                                                defaults.snapshotMutationThreshold(),
                                                defaults.snapshotMaxInterval(),
                                                defaults.snapshotRetentionCount(),
                                                defaults.walPath(),
                                                false);

        var setups = StorageFactory.createAll(Map.of("artifacts", artifactsConfig),
                                               "test-node",
                                               Option.some(dhtClient),
                                               Option.none())
                                    .onFailure(cause -> fail("createAll must succeed: " + cause.message()))
                                    .unwrap();

        assertThat(setups).containsKey("content");

        var contentSetup = setups.get("content");
        var content = "content-maintenance-probe".getBytes(StandardCharsets.UTF_8);
        var blockId = contentSetup.instance().put(content).await().unwrap();

        assertThat(dhtClient.isEmpty())
            .as("content's synthesized config carries a DHT tier (Option.some(dhtClient) was "
               + "passed to createAll) -- an empty backing store means that tier never reached "
               + "content's tier list at all")
            .isFalse();

        forceOrphanedPastGracePeriod(contentSetup, blockId);

        var compositeDemotionManager = StorageFactory.compositeDemotionManager(setups);
        var compositeGarbageCollector = StorageFactory.compositeGarbageCollector(setups);
        var driver = StorageMaintenanceDriver.storageMaintenanceDriver(compositeDemotionManager,
                                                                        compositeGarbageCollector);

        compositeDemotionManager.activate();
        compositeGarbageCollector.activate();

        driver.tick();

        contentSetup.instance()
                    .get(blockId)
                    .await()
                    .onFailure(cause -> fail("get should not fail: " + cause.message()))
                    .onSuccess(opt -> assertThat(opt.isPresent())
                        .as("private (memory/disk) copies are gone once a block is "
                           + "orphan-eligible; content must still be readable from the shared "
                           + "DHT tier -- empty means the real driver's tick either never reached "
                           + "content's real demotion/GC managers, or deleted from the shared tier")
                        .isTrue());
    }

    /// Drives the block's lifecycle directly to orphaned (refCount 0) and past the GC grace
    /// cutoff, bypassing the hardcoded 1-hour `GarbageCollectorConfig` default that
    /// `defaultStreamStorage` does not expose for injection -- the alternative would be a test
    /// that actually sleeps an hour.
    private static void forceOrphanedPastGracePeriod(StorageFactory.StorageSetup setup, BlockId blockId) {
        var expiredCutoff = System.currentTimeMillis()
                           - GarbageCollectorConfig.garbageCollectorConfig().gracePeriodMs()
                           - 60_000;

        setup.metadataStore()
             .computeLifecycle(blockId,
                               lc -> BlockLifecycle.blockLifecycle(lc.blockId(), lc.presentIn(), 0,
                                                                   expiredCutoff, lc.createdAt(), lc.accessCount()));
    }

    /// In-memory `DHTClient` stub backed by a `ConcurrentHashMap`. Mirrors
    /// `DhtStorageTierTest.InMemoryDHTClient` (aether-storage module) -- duplicated locally
    /// because that class is package-private in a different module's test tree and unreachable
    /// from here.
    private static final class InMemoryDHTClient implements DHTClient {
        private final ConcurrentHashMap<String, byte[]> store = new ConcurrentHashMap<>();

        @Override
        public Promise<Option<byte[]>> get(byte[] key) {
            return Promise.success(option(store.get(keyString(key))).map(v -> copyOf(v, v.length)));
        }

        @Override
        public Promise<Unit> put(byte[] key, byte[] value) {
            store.put(keyString(key), copyOf(value, value.length));
            return Promise.success(unit());
        }

        @Override
        public Promise<Boolean> remove(byte[] key) {
            return Promise.success(store.remove(keyString(key)) != null);
        }

        @Override
        public Promise<Boolean> exists(byte[] key) {
            return Promise.success(store.containsKey(keyString(key)));
        }

        @Override
        public Partition partitionFor(byte[] key) {
            return null;
        }

        boolean isEmpty() {
            return store.isEmpty();
        }

        private static String keyString(byte[] key) {
            return new String(key, StandardCharsets.UTF_8);
        }
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
