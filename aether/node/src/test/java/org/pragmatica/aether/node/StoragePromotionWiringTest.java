// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.PromotionManager;
import org.pragmatica.storage.TierLevel;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// Promotion through the production path: `StorageFactory.createAll` builds the setups exactly as `AetherNode`
/// does, `activateNodeMaintenance` is what the node's ready signal calls, and `StorageMaintenanceDriver.tick()` is
/// the periodic entry. Nothing here builds its own `PromotionManager` -- a manager built by the test could not tell
/// whether the factory ever builds one.
class StoragePromotionWiringTest {
    private static final String ARTIFACTS = "artifacts";
    private static final String CONTENT = "content";
    private static final int BLOCK_BYTES = 1024;
    private static final int MEMORY_BLOCKS = 8;
    private static final long MEMORY_MAX_BYTES = (long) BLOCK_BYTES * MEMORY_BLOCKS;
    /// Strictly more than `PromotionConfig`'s default threshold of 50 accesses.
    private static final int HOT_READS = 51;

    @TempDir
    Path artifactsDir;

    @TempDir
    Path contentDir;

    @Test
    void createAll_promotionKey_decidesTheRealManagerOrTheInertOne() {
        var setups = createAll(true, false);

        assertThat(setups.get(ARTIFACTS).promotionManager()).as("`promotion` defaults to on: the factory builds a real manager")
                                                             .isNotSameAs(PromotionManager.NOOP);
        assertThat(setups.get(CONTENT).promotionManager()).as("`[storage.content] promotion = false` is the kill switch")
                                                           .isSameAs(PromotionManager.NOOP);
    }

    @Test
    void createAll_synthesizedInstances_followTheDefaultsPromotionSwitch() {
        var defaults = config(StorageConfig.storageConfig(), artifactsDir, MEMORY_MAX_BYTES, false);

        var setups = StorageFactory.createAll(Map.of(), "test-node", Option.none(), Option.none(), defaults)
                                   .onFailure(cause -> fail("createAll must succeed: " + cause.message()))
                                   .unwrap();

        assertThat(setups.get(ARTIFACTS).promotionManager()).as("the synthesized `artifacts` carries the defaults' switch")
                                                             .isSameAs(PromotionManager.NOOP);
        assertThat(setups.get(CONTENT).promotionManager()).as("so does the synthesized `content`")
                                                           .isSameAs(PromotionManager.NOOP);
    }

    @Test
    void compositePromotionManager_sumsCyclesAndMergesStatsAcrossInstances() {
        var first = mock(PromotionManager.class);
        var second = mock(PromotionManager.class);

        when(first.promote()).thenReturn(2);
        when(second.promote()).thenReturn(3);
        when(first.stats()).thenReturn(new PromotionManager.PromotionStats(2, 200, 1, 4, 10));
        when(second.stats()).thenReturn(new PromotionManager.PromotionStats(3, 300, 5, 6, 30));
        when(first.isActive()).thenReturn(false);
        when(second.isActive()).thenReturn(true);
        when(first.activate()).thenReturn(Result.success(Unit.unit()));
        when(second.activate()).thenReturn(Result.success(Unit.unit()));

        var composite = StorageFactory.compositePromotionManager(Map.of("one", setupWith(first), "two", setupWith(second)));

        assertThat(composite.promote()).isEqualTo(5);
        assertThat(composite.stats()).isEqualTo(new PromotionManager.PromotionStats(5, 500, 6, 10, 30));
        assertThat(composite.isActive()).as("active when at least one instance promotes").isTrue();

        composite.activate();

        verify(first).activate();
        verify(second).activate();
    }

    @Test
    void defaultStreamStorage_promotionManager_isInert() {
        var setup = StorageFactory.defaultStreamStorage(Option.none(), contentDir, "test-node").unwrap();

        assertThat(setup.promotionManager()).as("streams never promotes: append-only, with its own segment prefetch")
                                            .isSameAs(PromotionManager.NOOP);
    }

    @Test
    void activateNodeMaintenance_startsDemotionAndPromotionOfArtifactsAndContent_notOfStreams() {
        var setups = withStreams(createAll(true, true));

        assertThat(maintenanceActive(setups.get(ARTIFACTS))).as("dormant until the node reports ready").isFalse();

        StorageFactory.activateNodeMaintenance(setups)
                      .onFailure(cause -> fail("activation must succeed: " + cause.message()));

        assertThat(setups.get(ARTIFACTS).demotionManager().isActive()).isTrue();
        assertThat(setups.get(ARTIFACTS).promotionManager().isActive()).isTrue();
        assertThat(setups.get(CONTENT).demotionManager().isActive()).isTrue();
        assertThat(setups.get(CONTENT).promotionManager().isActive()).isTrue();
        assertThat(setups.get(StorageFactory.STREAMS_NAME).demotionManager().isActive())
                .as("streams demotion stays leader-pinned: per-node activation must not reach it")
                .isFalse();

        StorageFactory.deactivateNodeMaintenance(setups)
                      .onFailure(cause -> fail("deactivation must succeed: " + cause.message()));

        assertThat(maintenanceActive(setups.get(ARTIFACTS))).isFalse();
        assertThat(maintenanceActive(setups.get(CONTENT))).isFalse();
    }

    @Test
    void signalReady_activatesMaintenanceBeforeSignalling_whenTheNodeIsNotDraining() {
        var setups = createAll(true, true);
        var activeWhenSignalled = new java.util.concurrent.atomic.AtomicBoolean();

        AetherNode.signalReadyActivatingStorage(() -> activeWhenSignalled.set(setups.get(CONTENT).promotionManager().isActive()),
                                                setups,
                                                () -> false);

        assertThat(activeWhenSignalled.get()).as("the node reports ready with maintenance already running").isTrue();
        assertThat(maintenanceActive(setups.get(ARTIFACTS))).isTrue();
    }

    /// The ready signal fires on every entry of the node-deployment machine to Active, which can be after the node began
    /// draining (a quorum-loss drain, then quorum returns). A draining node must stay off, and still signal.
    @Test
    void signalReady_afterTheNodeBeganDraining_keepsMaintenanceOff_butStillSignals() {
        var setups = createAll(true, true);
        var signalled = new java.util.concurrent.atomic.AtomicInteger();

        AetherNode.signalReadyActivatingStorage(signalled::incrementAndGet, setups, () -> true);

        assertThat(signalled.get()).isEqualTo(1);
        assertThat(maintenanceActive(setups.get(ARTIFACTS))).isFalse();
        assertThat(maintenanceActive(setups.get(CONTENT))).isFalse();
    }

    @Test
    void tick_promotesAHotBlockOutOfASlowTierIntoMemory_onTheRealFactoryPath() {
        var setups = createAll(true, true);
        var content = setups.get(CONTENT);
        var driver = driverOver(setups);

        StorageFactory.activateNodeMaintenance(setups);

        var ids = fillMemoryTier(content);

        driver.tick();

        var resident = content.metadataStore().listBlocksByTier(TierLevel.MEMORY);

        assertThat(resident.size()).as("baseline: the first tick demoted memory below its low watermark").isLessThan(MEMORY_BLOCKS);

        var cold = ids.stream().filter(id -> !isResidentInMemory(content, id)).findFirst().orElseThrow();

        deleteResidentBlocks(content, ids, 3);
        PromotionBootFixture.readRepeatedly(content, cold, HOT_READS);

        driver.tick();

        assertThat(isResidentInMemory(content, cold)).as("a block read %d times, held only by the disk tier, is copied "
                                                         + "into memory by the node's maintenance tick", HOT_READS)
                                                     .isTrue();
        assertThat(content.promotionManager().stats().blocksPromoted()).isEqualTo(1);
        assertThat(content.promotionManager().stats().failed()).as("a successful copy is not a failed one").isZero();
        assertThat(content.instance().tierInfo().stream()
                          .filter(info -> info.level() == TierLevel.MEMORY)
                          .findFirst().orElseThrow().usedBytes())
                .as("the copy is physically in the memory tier, not only recorded").isGreaterThanOrEqualTo(BLOCK_BYTES);
    }

    @Test
    void tick_promotesNothing_beforeTheNodeActivatesMaintenance() {
        var setups = createAll(true, true);
        var content = setups.get(CONTENT);
        var driver = driverOver(setups);
        var ids = fillMemoryTier(content);

        content.demotionManager().activate();
        driver.tick();

        var cold = ids.stream().filter(id -> !isResidentInMemory(content, id)).findFirst().orElseThrow();

        deleteResidentBlocks(content, ids, 3);
        PromotionBootFixture.readRepeatedly(content, cold, HOT_READS);
        driver.tick();

        assertThat(isResidentInMemory(content, cold)).as("promotion is dormant until activated").isFalse();
        assertThat(content.promotionManager().stats().cycles()).isZero();
    }

    private Map<String, StorageFactory.StorageSetup> createAll(boolean artifactsPromotion, boolean contentPromotion) {
        var defaults = StorageConfig.storageConfig();

        return StorageFactory.createAll(Map.of(ARTIFACTS,
                                               config(defaults, artifactsDir, defaults.memoryMaxBytes(), artifactsPromotion),
                                               CONTENT,
                                               config(defaults, contentDir, MEMORY_MAX_BYTES, contentPromotion)),
                                        "test-node",
                                        Option.none(),
                                        Option.none())
                             .onFailure(cause -> fail("createAll must succeed: " + cause.message()))
                             .unwrap();
    }

    private static StorageConfig config(StorageConfig defaults, Path root, long memoryMaxBytes, boolean promotion) {
        return StorageConfig.storageConfig(memoryMaxBytes,
                                           defaults.diskMaxBytes(),
                                           root.resolve("blocks").toString(),
                                           root.resolve("snapshots").toString(),
                                           defaults.snapshotMutationThreshold(),
                                           defaults.snapshotMaxInterval(),
                                           defaults.snapshotRetentionCount(),
                                           defaults.walPath(),
                                           false,
                                           promotion);
    }

    private static StorageFactory.StorageSetup setupWith(PromotionManager promotion) {
        return StorageFactory.StorageSetup.storageSetup("instance", null, null, null, null, null, null, Option.none(), promotion);
    }

    private Map<String, StorageFactory.StorageSetup> withStreams(Map<String, StorageFactory.StorageSetup> setups) {
        var all = new HashMap<>(setups);

        all.put(StorageFactory.STREAMS_NAME,
                StorageFactory.defaultStreamStorage(Option.none(), artifactsDir.resolve("streams"), "test-node").unwrap());

        return all;
    }

    /// The driver `AetherNode` builds, with the cycle run inline so the test sees its effect on return.
    private static StorageMaintenanceDriver driverOver(Map<String, StorageFactory.StorageSetup> setups) {
        return StorageMaintenanceDriver.storageMaintenanceDriver(StorageFactory.compositeDemotionManager(setups),
                                                                  StorageFactory.compositePromotionManager(setups),
                                                                  PromotionStallWatch.promotionStallWatch(setups,
                                                                                                          OperatorWarningSink.logOnly()),
                                                                  StorageFactory.compositeGarbageCollector(setups),
                                                                  Runnable::run);
    }

    private static boolean maintenanceActive(StorageFactory.StorageSetup setup) {
        return setup.demotionManager().isActive() || setup.promotionManager().isActive();
    }

    private static boolean isResidentInMemory(StorageFactory.StorageSetup setup, BlockId id) {
        return setup.metadataStore().getLifecycle(id).map(lc -> lc.presentIn().contains(TierLevel.MEMORY)).or(false);
    }

    /// Fills the memory tier to 100% of its budget, so the first tick crosses the 0.9 high watermark and demotes.
    private static List<BlockId> fillMemoryTier(StorageFactory.StorageSetup setup) {
        var ids = new ArrayList<BlockId>();

        for (var i = 0; i < MEMORY_BLOCKS; i++) {
            ids.add(setup.instance()
                         .put(distinctBlock(i))
                         .await()
                         .onFailure(cause -> fail("writing fill block failed: " + cause.message()))
                         .unwrap());
        }

        return ids;
    }

    /// Makes room in memory: after demotion stops, memory sits just under the 0.7 watermark, where promotion (which
    /// never fills a tier past it) has no room for one more block.
    private static void deleteResidentBlocks(StorageFactory.StorageSetup setup, List<BlockId> ids, int count) {
        ids.stream()
           .filter(id -> isResidentInMemory(setup, id))
           .limit(count)
           .forEach(id -> setup.instance().delete(id).await().onFailure(cause -> fail("delete failed: " + cause.message())));
    }

    private static byte[] distinctBlock(int index) {
        var block = new byte[BLOCK_BYTES];

        for (var i = 0; i < block.length; i++) {
            block[i] = (byte) ((index * 31 + i) % 251);
        }

        return block;
    }
}
