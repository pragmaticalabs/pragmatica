// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.TierLevel;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Two nodes' storage, each its own `StorageFactory.createAll` result with its own tiers, metadata and managers (what two
/// `AetherNode`s hold): activating node A's maintenance promotes A's hot block and leaves B dormant; B promotes its own
/// hot block independently once B itself is activated; deactivating A does not touch B. Promotion is per node, not
/// leader-pinned and not shared.
class StoragePromotionTwoNodeTest {
    private static final String ARTIFACTS = "artifacts";
    private static final String CONTENT = "content";
    private static final int BLOCK_BYTES = 1024;
    private static final int MEMORY_BLOCKS = 8;
    private static final long MEMORY_MAX_BYTES = (long) BLOCK_BYTES * MEMORY_BLOCKS;
    private static final int HOT_READS = 51;

    @TempDir
    Path artifactsDir;
    @TempDir
    Path contentDir;
    @TempDir
    Path artifactsDirB;
    @TempDir
    Path contentDirB;

    @Test
    void twoNodes_eachPromotesItsOwnHotBlock_andNodeBIsNotPromotedByNodeA() {
        var a = createAll(artifactsDir, contentDir);
        var b = createAll(artifactsDirB, contentDirB);
        var driverA = driverOver(a);
        var driverB = driverOver(b);
        var contentA = a.get(CONTENT);
        var contentB = b.get(CONTENT);

        StorageFactory.activateNodeMaintenance(a);   // only A ready

        var idsA = fillMemoryTier(contentA);
        var idsB = fillMemoryTier(contentB);

        contentB.demotionManager().activate();   // demotion only; B's promotion stays dormant
        driverA.tick();
        driverB.tick();

        var coldA = idsA.stream().filter(id -> !isResidentInMemory(contentA, id)).findFirst().orElseThrow();
        var coldB = idsB.stream().filter(id -> !isResidentInMemory(contentB, id)).findFirst().orElseThrow();

        deleteResidentBlocks(contentA, idsA, 3);
        deleteResidentBlocks(contentB, idsB, 3);
        PromotionBootFixture.readRepeatedly(contentA, coldA, HOT_READS);
        PromotionBootFixture.readRepeatedly(contentB, coldB, HOT_READS);
        driverA.tick();
        driverB.tick();

        assertThat(isResidentInMemory(contentA, coldA)).as("A promoted").isTrue();
        assertThat(isResidentInMemory(contentB, coldB)).as("B dormant, not promoted by A's activation").isFalse();
        assertThat(contentB.promotionManager().stats().cycles()).isZero();

        StorageFactory.activateNodeMaintenance(b);   // B becomes ready later
        driverB.tick();

        assertThat(isResidentInMemory(contentB, coldB)).as("B promoted independently once ready").isTrue();
        assertThat(contentB.promotionManager().stats().blocksPromoted()).isEqualTo(1);
        assertThat(contentA.promotionManager().stats().blocksPromoted()).isEqualTo(1);
        assertThat(contentA.promotionManager()).isNotSameAs(contentB.promotionManager());

        // deactivate A only: B keeps promoting, A stops
        StorageFactory.deactivateNodeMaintenance(a);
        assertThat(contentA.promotionManager().isActive()).isFalse();
        assertThat(contentB.promotionManager().isActive()).isTrue();
    }

    private Map<String, StorageFactory.StorageSetup> createAll(Path artDir, Path conDir) {
        var defaults = StorageConfig.storageConfig();

        return StorageFactory.createAll(Map.of(ARTIFACTS,
                                               config(defaults, artDir, defaults.memoryMaxBytes(), true),
                                               CONTENT,
                                               config(defaults, conDir, MEMORY_MAX_BYTES, true)),
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

    /// The driver `AetherNode` builds, with the cycle run inline so the test sees its effect on return.
    private static StorageMaintenanceDriver driverOver(Map<String, StorageFactory.StorageSetup> setups) {
        return StorageMaintenanceDriver.storageMaintenanceDriver(StorageFactory.compositeDemotionManager(setups),
                                                                  StorageFactory.compositePromotionManager(setups),
                                                                  PromotionStallWatch.promotionStallWatch(setups,
                                                                                                          OperatorWarningSink.logOnly()),
                                                                  StorageFactory.compositeGarbageCollector(setups),
                                                                  Runnable::run);
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
