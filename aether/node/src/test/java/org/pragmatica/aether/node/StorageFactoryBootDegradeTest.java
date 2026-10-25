// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.StorageCondition;
import org.pragmatica.storage.StorageCondition.Kind;
import org.pragmatica.storage.StorageError;
import org.pragmatica.storage.TierLevel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// A0b boot and degrade: which disk-tier failures refuse the boot, which degrade (and say so), the mount claim, and
/// the memory-tier budget. Each test names the mutation that reddens it.
///
/// Hermetic storage is NOT changed: `HermeticStorage` still roots the paths under a regular file, and its configs are
/// not marked operator-set (the mark is the loader's reading of a written `disk_path`), so they take the degrade arm.
class StorageFactoryBootDegradeTest {
    private static final long MIB = 1024L * 1024L;
    private static final long ONE_GIB = 1024L * MIB;

    @TempDir
    Path tempDir;

    /// Mutation: `diskTierUnavailable` ignores `diskPathOperatorSet` (always degrades) -- the boot succeeds.
    @Test
    void createAll_operatorSetDiskPathThatCannotOpen_refusesTheBootNamingThePath() {
        var unopenable = HermeticStorage.storageConfigAt(HermeticStorage.uncreatableRootIn(tempDir), false).withOperatorDiskPath();

        StorageFactory.createAll(Map.of("artifacts", unopenable), "node-1", Option.none(), Option.none(), HermeticStorage.synthesisDefaultsIn(tempDir))
                      .onSuccess(_ -> fail("an operator-set disk_path that cannot open must refuse the boot"))
                      .onFailure(cause -> assertThat(cause.message()).contains("'artifacts'")
                                                                     .contains("disk_path")
                                                                     .contains(unopenable.diskPath()));
    }

    /// Mutation: `handleDiskTierUnavailable` drops `.degraded(...)` -- the instance degrades silently.
    @Test
    void createAll_defaultPlacedDiskPathThatCannotOpen_degrades_andReportsEachInstance() {
        var setups = StorageFactory.createAll(Map.of(), "node-1", Option.none(), Option.none(), HermeticStorage.synthesisDefaultsIn(tempDir))
                                   .onFailure(cause -> fail("the synthesized defaults must degrade, not refuse: " + cause.message()))
                                   .unwrap();

        for (var name : new String[]{"artifacts", "content"}) {
            var setup = setups.get(name);

            assertThat(setup.instance().tierInfo()).as(name + " runs without a disk tier")
                                                  .noneMatch(tier -> tier.level() == TierLevel.LOCAL_DISK);
            assertThat(setup.conditions()).as(name).extracting(StorageCondition::kind).containsExactly(Kind.DISK_TIER_UNAVAILABLE);
            assertThat(setup.conditions().getFirst().detail()).contains("'" + name + "'").contains("restart");
        }
    }

    /// Mutation: an operator-set path that opens fine is reported as degraded, or the flag refuses a healthy path.
    @Test
    void createAll_operatorSetDiskPathThatOpens_bootsWithoutConditions() {
        var healthy = HermeticStorage.storageConfigAt(tempDir.resolve("healthy"), false).withOperatorDiskPath();

        var setups = StorageFactory.createAll(Map.of("artifacts", healthy), "node-1", Option.none(), Option.none(), HermeticStorage.synthesisDefaultsIn(tempDir))
                                   .onFailure(cause -> fail(cause.message()))
                                   .unwrap();

        assertThat(setups.get("artifacts").conditions()).isEmpty();
        assertThat(setups.get("artifacts").instance().tierInfo()).anyMatch(tier -> tier.level() == TierLevel.LOCAL_DISK);
        setups.values().forEach(setup -> setup.instance().shutdown());
    }

    /// Mutation: `diskTierUnavailable` lets `MountClaimed` fall through to the degrade arm for a default-placed path
    /// (the second node boots on memory plus the shared tier, past the claim).
    @Test
    void createAll_secondNodeOnAMountAnotherNodeHolds_refusesTheBoot_evenAtADefaultPath() {
        var shared = HermeticStorage.storageConfigAt(tempDir.resolve("shared"), false);
        var first = StorageFactory.createAll(Map.of("artifacts", shared), "node-a", Option.none(), Option.none(), HermeticStorage.synthesisDefaultsIn(tempDir))
                                  .onFailure(cause -> fail(cause.message()))
                                  .unwrap();

        StorageFactory.createAll(Map.of("artifacts", shared), "node-b", Option.none(), Option.none(), HermeticStorage.synthesisDefaultsIn(tempDir))
                      .onSuccess(_ -> fail("a second node on the same mount must refuse to boot"))
                      .onFailure(cause -> assertThat(cause.message()).contains("already claimed by node 'node-a'")
                                                                     .contains("'artifacts'"));
        first.values().forEach(setup -> setup.instance().shutdown());
    }

    /// Mutation: `StorageInstance.shutdown` stops releasing its tiers -- the mount stays claimed after the node stops.
    @Test
    void createAll_afterTheFirstNodeShutsDown_anotherNodeCanTakeTheMount() {
        var shared = HermeticStorage.storageConfigAt(tempDir.resolve("shared"), false);
        var first = StorageFactory.createAll(Map.of("artifacts", shared), "node-a", Option.none(), Option.none(), HermeticStorage.synthesisDefaultsIn(tempDir))
                                  .onFailure(cause -> fail(cause.message()))
                                  .unwrap();

        first.values().forEach(setup -> setup.instance().shutdown());

        assertThat(StorageFactory.createAll(Map.of("artifacts", shared), "node-b", Option.none(), Option.none(), HermeticStorage.synthesisDefaultsIn(tempDir))
                                 .isSuccess()).isTrue();
    }

    /// Mutation: `MemoryTierBudget.fit` returns the request unchanged -- 528 MiB of tiers under a 256 MiB heap.
    @Test
    void createAll_under256MiBHeap_memoryTiersStayWithinTheBudget_andReportTheClamp() {
        var setups = StorageFactory.createAll(Map.of(), "node-1", Option.none(), Option.none(), HermeticStorage.synthesisDefaultsIn(tempDir), 256 * MIB)
                                   .onFailure(cause -> fail(cause.message()))
                                   .unwrap();
        var budget = (long) (256 * MIB * MemoryTierBudget.HEAP_FRACTION);
        var total = setups.values().stream().mapToLong(StorageFactoryBootDegradeTest::memoryBytes).sum();

        assertThat(total).as("memory tiers of all instances, streams tier included").isLessThanOrEqualTo(budget);
        assertThat(setups.get("artifacts").conditions()).extracting(StorageCondition::kind).contains(Kind.MEMORY_TIER_CLAMPED);
        assertThat(setups.get("content").conditions()).extracting(StorageCondition::kind).contains(Kind.MEMORY_TIER_CLAMPED);
    }

    /// Mutation: the clamp applied unconditionally (e.g. `fit` always scales) -- a roomy heap loses its configured size.
    @Test
    void createAll_underARoomyHeap_keepsTheConfiguredMemoryTier_withNoClampCondition() {
        var setups = StorageFactory.createAll(Map.of(), "node-1", Option.none(), Option.none(), HermeticStorage.synthesisDefaultsIn(tempDir), 8 * ONE_GIB)
                                   .onFailure(cause -> fail(cause.message()))
                                   .unwrap();

        assertThat(memoryBytes(setups.get("artifacts"))).isEqualTo(StorageConfig.storageConfig().memoryMaxBytes());
        assertThat(setups.get("artifacts").conditions()).extracting(StorageCondition::kind).doesNotContain(Kind.MEMORY_TIER_CLAMPED);
    }

    private static long memoryBytes(StorageFactory.StorageSetup setup) {
        return setup.instance().tierInfo().stream()
                    .filter(tier -> tier.level() == TierLevel.MEMORY)
                    .mapToLong(tier -> tier.maxBytes())
                    .sum();
    }
}
