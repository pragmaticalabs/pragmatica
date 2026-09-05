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
import org.pragmatica.storage.TierLevel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    /// Explicit `[storage.content]` temp dir for the two acceptance-item-3 tests below.
    @TempDir
    Path contentDir;

    private static final String ARTIFACTS = "artifacts";
    private static final String CONTENT = "content";
    /// Sized so exactly [#CONTENT_MEMORY_BLOCKS] blocks of [#CONTENT_BLOCK_BYTES] fill the memory
    /// tier to 100% of its budget -- above `DefaultDemotionManager`'s 0.9 high watermark, so a single
    /// pass demotes down toward the 0.7 low watermark. `MemoryTier.put` REFUSES any write that would
    /// exceed `maxBytes` (returning `StorageError.TierFull`, which `writeToAllTiers` swallows for a
    /// cache tier), so the budget must be an exact multiple of the block size or the last write would
    /// silently skip the memory tier instead of filling it.
    private static final int CONTENT_BLOCK_BYTES = 1024;
    private static final int CONTENT_MEMORY_BLOCKS = 8;
    private static final long CONTENT_MEMORY_MAX_BYTES = (long) CONTENT_BLOCK_BYTES * CONTENT_MEMORY_BLOCKS;

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

        // #858: `DhtStorageTier.get()` is gated on a per-instance `readGate` that ONLY
        // `StorageFactory.verifyDhtMarker` resolves -- the post-formation step `AetherNode.start()`
        // runs before the node reports ready. The maintenance pass has just removed the private
        // (memory/disk) copies, so the read below is served by the DHT tier and nothing else;
        // without this call it sits out the tier's 30s admission bound and fails with
        // `StorageError.TierNotAdmitted` rather than exercising the shared-tier guard. Verifying
        // first is exactly what production does, in the same order.
        contentSetup.dhtMarkerCheck()
                    .onPresent(check -> StorageFactory.verifyDhtMarker(dhtClient, check)
                                                      .await()
                                                      .onFailure(cause -> fail("verifying content's DHT marker "
                                                                               + "failed: " + cause.message())));

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

    /// #783 acceptance item 3, DEMOTION half: the ticket demands proof the content tier's memory
    /// cache ACTUALLY SHRINKS after normal use -- explicitly "not just that the objects are
    /// constructed". The registration test above does NOT establish that: its post-tick read is
    /// satisfied by a pass that demoted and collected nothing, because an untouched block is still
    /// readable. Only the registration assertion (`containsKey`) there can go red. This test measures
    /// memory-tier occupancy ACROSS the tick, so a pass that does nothing fails it.
    ///
    /// `[storage.content]` is EXPLICIT here rather than synthesized, for one reason: a memory budget
    /// small enough that `DefaultDemotionManager`'s 0.9 high watermark is crossed by a few KB of
    /// writes. The synthesized default hardcodes `StorageConfig.storageConfig()`'s 256 MB
    /// (`StorageFactory.defaultContentConfig`), so demoting it would need ~230 MB of test writes. The
    /// CONSTRUCTION path is identical either way (`createAll` -> `createOne` -> `assembleSetup`, one
    /// `StorageSetup` with real managers registered in the returned map); only the `StorageConfig`'s
    /// origin differs, and the synthesized branch's own derivation is pinned separately by
    /// `StorageFactoryEncryptionTest#createAll_synthesizedContent_usesSiblingDiskPath_distinctFromArtifacts`
    /// plus the two synthesized-content encryption tests.
    ///
    /// Every instrument is content's OWN, never the composite's: the composite fans out over
    /// `artifacts` AND `content`, so a composite-level counter could be driven entirely by `artifacts`
    /// and still look green with content untouched. `metadataStore().listBlocksByTier(MEMORY)` is the
    /// authoritative record of memory-tier residency and is updated only by
    /// `DefaultDemotionManager.completeBlockDemotion`, which deletes the block from the memory tier
    /// before rewriting that record.
    @Test
    void createAll_realMaintenanceDriverTick_actuallyShrinksContentMemoryTier() {
        var setups = createAllWithExplicitContent(Option.none());
        var contentSetup = setups.get(CONTENT);

        fillMemoryTier(contentSetup);

        var memoryBefore = contentSetup.metadataStore().listBlocksByTier(TierLevel.MEMORY).size();

        assertThat(memoryBefore).as("baseline: the memory budget is sized to hold exactly %d blocks, so all "
                                    + "of them must be resident before the pass -- a smaller number means the "
                                    + "fill never reached the watermark and the rest of this test would prove "
                                    + "nothing", CONTENT_MEMORY_BLOCKS)
                                .isEqualTo(CONTENT_MEMORY_BLOCKS);

        tickRealDriver(setups);

        var memoryAfter = contentSetup.metadataStore().listBlocksByTier(TierLevel.MEMORY).size();

        assertThat(memoryAfter).as("the content tier's memory cache must ACTUALLY shrink -- still %d means the "
                                   + "real driver's tick reached no real DemotionManager for content (the #783 "
                                   + "gap), or reached one that demoted nothing", memoryBefore)
                               .isLessThan(memoryBefore);

        assertThat(contentSetup.demotionManager().stats().bytesMoved())
                .as("content's OWN demotion manager must report bytes actually moved out of the memory tier")
                .isGreaterThan(0L);

        assertThat(contentSetup.metadataStore().listBlocksByTier(TierLevel.LOCAL_DISK).size())
                .as("demotion MOVES a block down a tier, it does not drop it -- every block written must still "
                   + "be accounted for on disk afterwards")
                .isEqualTo(CONTENT_MEMORY_BLOCKS);
    }

    /// #783 acceptance item 3, GARBAGE-COLLECTION half: proof an orphaned content block is ACTUALLY
    /// collected, with three instruments independent of each other and of the collector's own counter
    /// -- the block's disk file is gone from the filesystem, its lifecycle record is gone from the
    /// metadata store, and the block is no longer readable through the instance. Before #783 nothing
    /// ever ran GC against `content` at all, so every one of these stayed put forever.
    ///
    /// No DHT client here, deliberately: with tiers `[memory, disk]` EVERY tier holding the block is
    /// private, so `deleteFromPrivateTiers` is expected to remove all copies and the post-pass read
    /// must miss. That makes "actually collected" a positive, unambiguous assertion. The
    /// complementary property -- that GC never deletes from a SHARED DHT tier (#250's guard) -- is
    /// what `createAll_realMaintenanceDriverTick_reachesSynthesizedContentInstance` above pins.
    ///
    /// Note the ticket's phrase "orphaned DHT blocks are actually collected" does NOT describe the
    /// shipped behaviour and is not asserted here: `DefaultStorageGarbageCollector.deleteBlock` calls
    /// `deleteFromPrivateTiers`, never `delete`, precisely so this node's local refcount cannot delete
    /// a block another node may still reference. Collection is a PRIVATE-tier operation by design.
    @Test
    void createAll_realMaintenanceDriverTick_actuallyCollectsOrphanedContentBlock() {
        var setups = createAllWithExplicitContent(Option.none());
        var contentSetup = setups.get(CONTENT);
        var blockId = contentSetup.instance()
                                  .put("content-gc-probe".getBytes(StandardCharsets.UTF_8))
                                  .await()
                                  .onFailure(cause -> fail("writing the probe block failed: " + cause.message()))
                                  .unwrap();
        var diskBlockPath = rawBlockPath(contentBlocksDir(), blockId);

        assertThat(Files.exists(diskBlockPath)).as("baseline: the block must be on disk before the pass")
                                               .isTrue();
        assertThat(contentSetup.metadataStore().containsBlock(blockId))
                .as("baseline: the block must have a lifecycle record before the pass")
                .isTrue();

        forceOrphanedPastGracePeriod(contentSetup, blockId);

        tickRealDriver(setups);

        assertThat(contentSetup.garbageCollector().stats().blocksCollected())
                .as("content's OWN collector (never the composite -- artifacts could satisfy that) must report "
                   + "a real collection")
                .isGreaterThan(0);

        assertThat(Files.exists(diskBlockPath))
                .as("filesystem-level truth, independent of every counter above: the orphaned block's disk file "
                   + "must actually be gone")
                .isFalse();

        assertThat(contentSetup.metadataStore().containsBlock(blockId))
                .as("deleteFromPrivateTiers drops the lifecycle record once the block is collected")
                .isFalse();

        contentSetup.instance()
                    .get(blockId)
                    .await()
                    .onFailure(cause -> fail("get should not fail after collection: " + cause.message()))
                    .onSuccess(opt -> assertThat(opt.isPresent())
                            .as("with no DHT tier configured every tier holding the block is private, so a "
                               + "collected block must be unreadable")
                            .isFalse());
    }

    /// Real `createAll` with BOTH `artifacts` and `content` explicit -- `artifacts` because the bare
    /// `StorageConfig.storageConfig()` default (`/data/aether/storage`) is not creatable in a test
    /// sandbox, `content` because these tests need a memory budget small enough to cross the demotion
    /// watermark (see [#CONTENT_MEMORY_MAX_BYTES]).
    private Map<String, StorageFactory.StorageSetup> createAllWithExplicitContent(Option<DHTClient> dhtClient) {
        var defaults = StorageConfig.storageConfig();

        return StorageFactory.createAll(Map.of(ARTIFACTS,
                                               storageConfigAt(defaults, artifactsDir, artifactsDir.resolve("snapshots"),
                                                               defaults.memoryMaxBytes()),
                                               CONTENT,
                                               storageConfigAt(defaults, contentBlocksDir(), contentDir.resolve("snapshots"),
                                                               CONTENT_MEMORY_MAX_BYTES)),
                                        "test-node",
                                        dhtClient,
                                        Option.none())
                             .onFailure(cause -> fail("createAll must succeed: " + cause.message()))
                             .unwrap();
    }

    private static StorageConfig storageConfigAt(StorageConfig defaults, Path diskPath, Path snapshotPath, long memoryMaxBytes) {
        return new StorageConfig(memoryMaxBytes,
                                 defaults.diskMaxBytes(),
                                 diskPath.toString(),
                                 snapshotPath.toString(),
                                 defaults.snapshotMutationThreshold(),
                                 defaults.snapshotMaxInterval(),
                                 defaults.snapshotRetentionCount(),
                                 defaults.walPath(),
                                 false);
    }

    private Path contentBlocksDir() {
        return contentDir.resolve("blocks");
    }

    /// Fills content's memory tier to 100% of its budget through NORMAL USE -- write, then read --
    /// which is precisely the state #783's acceptance item 3 asks about.
    ///
    /// The read is REQUIRED, not decoration, and the reason is a sharp edge in `StorageInstance`:
    /// `writeToAllTiers` writes the durable (disk) tier, then promotes into the memory cache tier via
    /// `recordTierPresence` -> `MetadataStore.computeLifecycle`, and only THEN calls `trackNewBlock`
    /// to create the lifecycle record. `computeLifecycle` is `computeIfPresent`, so that promotion
    /// record lands on a lifecycle that does not exist yet and is silently dropped -- a
    /// written-but-never-read block is PHYSICALLY in the memory tier while the metadata store still
    /// says it lives on disk alone. `DefaultDemotionManager.selectCandidates` picks candidates from
    /// `listBlocksByTier(MEMORY)`, so such a block is invisible to demotion even with the tier over
    /// its high watermark. A subsequent read re-records presence for the tier it is served from
    /// (`completeVerification` -> `recordTierPresence`), by which time the lifecycle exists, so
    /// write-then-read leaves the memory tier both full AND visible to the demoter.
    ///
    /// That ordering quirk lives in `integrations/storage`, outside this ticket's scope; it is
    /// reported, not fixed here.
    private static void fillMemoryTier(StorageFactory.StorageSetup setup) {
        for (var i = 0; i < CONTENT_MEMORY_BLOCKS; i++) {
            var blockId = setup.instance()
                               .put(distinctBlock(i))
                               .await()
                               .onFailure(cause -> fail("writing fill block failed: " + cause.message()))
                               .unwrap();

            setup.instance()
                 .get(blockId)
                 .await()
                 .onFailure(cause -> fail("reading fill block back failed: " + cause.message()));
        }
    }

    /// Distinct payloads of a FIXED size -- blocks are content-addressed, so identical payloads would
    /// collapse onto one `BlockId` and the tier would never fill.
    private static byte[] distinctBlock(int index) {
        var block = new byte[CONTENT_BLOCK_BYTES];

        for (var i = 0; i < block.length; i++) {
            block[i] = (byte) ((index * 31 + i) % 251);
        }

        return block;
    }

    /// The production maintenance path: the REAL composite managers over the FULL setups map (so the
    /// fan-out actually has to reach `content`) driven by the REAL `StorageMaintenanceDriver`.
    private static void tickRealDriver(Map<String, StorageFactory.StorageSetup> setups) {
        var demotionManager = StorageFactory.compositeDemotionManager(setups);
        var garbageCollector = StorageFactory.compositeGarbageCollector(setups);
        var driver = StorageMaintenanceDriver.storageMaintenanceDriver(demotionManager, garbageCollector);

        demotionManager.activate();
        garbageCollector.activate();

        driver.tick();
    }

    /// Mirrors `LocalDiskTier`'s own private `blockPath` sharding (`{base}/{hex[0:2]}/{hex[2:4]}/{hex}`)
    /// so a test can check the filesystem directly instead of trusting a tier accessor.
    private static Path rawBlockPath(Path base, BlockId id) {
        var hex = id.hexString();

        return base.resolve(hex.substring(0, 2))
                   .resolve(hex.substring(2, 4))
                   .resolve(hex);
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
