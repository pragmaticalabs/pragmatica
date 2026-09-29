// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #856 — every `[storage.<name>]` section that omits `disk_path`/`snapshot_path` resolves the same
/// `/data/aether/...` default, so an explicit instance without paths beside the synthesized `artifacts` shared
/// its directories: each `LocalDiskTier` walked the other's bytes into its own capacity, and both wrote
/// metadata snapshots to one directory. `StorageFactory.createAll` now refuses such a boot before building any
/// instance. Mutation that reddens the refusal tests: make `StoragePathsOverlap.find` return none.
class StorageFactoryPathOverlapTest {
    private static final String NODE_ID = "overlap-test-node";

    @TempDir
    Path tempDir;

    /// The ticket's case through the production defaults: an explicit instance with the loader's default
    /// paths beside the synthesized `artifacts`, which resolves the same defaults. Refused by path
    /// comparison alone, so `/data` is never touched.
    @Test
    void explicitInstanceWithDefaultPaths_besideSynthesizedArtifacts_isRefused() {
        var result = StorageFactory.createAll(Map.of("vault", StorageConfig.storageConfig()), NODE_ID, Option.none(), Option.none());

        assertThat(result.isFailure()).as("#856: two instances on one base path must not boot: %s", result).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(StorageFactory.StoragePathsOverlap.class)
                                                   .satisfies(overlap -> assertThat(overlap.message()).contains("'artifacts'", "'vault'")));
    }

    @Test
    void twoExplicitInstancesOnOneDiskPath_areRefused() {
        var shared = tempDir.resolve("shared");
        var configs = Map.of("vault", at(shared, tempDir.resolve("vault-snapshots")),
                             "archive", at(shared, tempDir.resolve("archive-snapshots")));

        var result = createAll(configs);

        assertThat(result.isFailure()).as("%s", result).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(StorageFactory.StoragePathsOverlap.class));
    }

    /// Nesting is the same double count: the outer instance's walk includes the inner one's blocks.
    @Test
    void oneInstanceInsideAnother_isRefused() {
        var outer = tempDir.resolve("outer");
        var configs = Map.of("vault", at(outer, tempDir.resolve("vault-snapshots")),
                             "archive", at(outer.resolve("archive"), tempDir.resolve("archive-snapshots")));

        assertThat(createAll(configs).isFailure()).isTrue();
    }

    /// Two instances writing metadata snapshots into one directory, with distinct disk paths.
    @Test
    void twoInstancesSharingASnapshotPath_areRefused() {
        var snapshots = tempDir.resolve("snapshots");
        var configs = Map.of("vault", at(tempDir.resolve("vault"), snapshots),
                             "archive", at(tempDir.resolve("archive"), snapshots));

        assertThat(createAll(configs).isFailure()).isTrue();
    }

    /// CONTROL — distinct directories per instance, one of them keeping its snapshots under its own disk
    /// directory (nesting WITHIN an instance is allowed), plus the synthesized `artifacts`/`content`, boot.
    @Test
    void distinctPathsPerInstance_boot() {
        var vault = tempDir.resolve("vault");
        var configs = Map.of("vault", at(vault, vault.resolve("snapshots")),
                             "archive", at(tempDir.resolve("archive"), tempDir.resolve("archive-snapshots")));

        var result = createAll(configs);

        assertThat(result.isSuccess()).as("%s", result).isTrue();
        assertThat(result.unwrap()).containsKeys("vault", "archive", "artifacts", "content");
    }

    private org.pragmatica.lang.Result<Map<String, StorageFactory.StorageSetup>> createAll(Map<String, StorageConfig> configs) {
        return StorageFactory.createAll(configs,
                                        NODE_ID,
                                        Option.none(),
                                        Option.none(),
                                        HermeticStorage.synthesisDefaultsIn(tempDir));
    }

    private static StorageConfig at(Path disk, Path snapshots) {
        var defaults = StorageConfig.storageConfig();

        return StorageConfig.storageConfig(defaults.memoryMaxBytes(),
                                           defaults.diskMaxBytes(),
                                           disk.toString(),
                                           snapshots.toString(),
                                           defaults.snapshotMutationThreshold(),
                                           defaults.snapshotMaxInterval(),
                                           defaults.snapshotRetentionCount(),
                                           defaults.walPath(),
                                           false);
    }
}
