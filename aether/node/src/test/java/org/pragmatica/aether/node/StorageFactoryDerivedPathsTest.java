// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.SharedTierConfig;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #249: only a [StorageConfig#sharedOnly] instance is placed on derived paths. A configuration built any other way
/// is placed as written, a blank `diskPath` included: before the explicit flag, a blank `diskPath` alone read as
/// "shared-only" and the whole configuration was replaced by the defaults. No instance is built, so nothing is
/// written to the working directory a blank path resolves against.
class StorageFactoryDerivedPathsTest {
    @TempDir
    Path tempDir;

    private StorageConfig defaults() {
        return StorageConfig.storageConfig(8L * 1024 * 1024,
                                           64L * 1024 * 1024,
                                           tempDir.resolve("storage").toString(),
                                           tempDir.resolve("metadata-snapshots").toString(),
                                           1000,
                                           "60s",
                                           5);
    }

    /// Mutation that reddens it: key `StorageConfig#derivesPaths` on a blank `diskPath` again.
    @Test
    void blankDiskPath_builtProgrammatically_isUsedAsWritten_notReplacedByDefaults() {
        var vault = StorageConfig.storageConfig(1234, 5678, "", tempDir.resolve("vault-snapshots").toString(), 7, "9s", 3);
        var artifacts = StorageConfig.storageConfig(4321, 8765, "", tempDir.resolve("artifacts-snapshots").toString(), 7, "9s", 3);

        var placed = StorageFactory.withDerivedPaths(Map.of("vault", vault, "artifacts", artifacts), defaults(), false);

        assertThat(placed.get("vault")).isEqualTo(vault);
        assertThat(placed.get("artifacts")).isEqualTo(artifacts);
    }

    /// Mutation that reddens it: drop the flag `StorageConfig#sharedOnly` sets.
    @Test
    void sharedOnlyInstance_isPlacedOnTheDefaults_keepingItsSharedTier() {
        var shared = SharedTierConfig.dht();

        var placed = StorageFactory.withDerivedPaths(Map.of("vault", StorageConfig.sharedOnly(shared)), defaults(), false)
                                   .get("vault");

        assertThat(placed.diskPath()).isEqualTo(defaults().diskPath());
        assertThat(placed.snapshotPath()).isEqualTo(defaults().snapshotPath());
        assertThat(placed.shared()).isEqualTo(shared);
    }

    /// The node builds only from the derived map: a shared-only configuration handed to `createAll` underived is
    /// refused before any tier is built, instead of being placed on its blank paths. Mutation that reddens it: drop
    /// the `UnderivedStoragePaths` check in `StorageFactory#pendingSetups`.
    @Test
    void createAll_underivedSharedOnlyInstance_isRefused_namingIt() {
        StorageFactory.createAll(Map.of("vault", StorageConfig.sharedOnly(SharedTierConfig.dht())),
                                 "derived-paths-node",
                                 Option.none(),
                                 Option.none(),
                                 defaults())
                      .onSuccess(setups -> fail("an underived shared-only instance must not be built: " + setups.keySet()))
                      .onFailure(cause -> assertThat(cause).isInstanceOf(StorageFactory.UnderivedStoragePaths.class)
                                                           .satisfies(refusal -> assertThat(refusal.message()).contains("'vault'")));
    }
}
