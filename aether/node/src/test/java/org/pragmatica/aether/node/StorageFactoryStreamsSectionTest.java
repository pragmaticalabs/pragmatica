// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// A `[storage.streams]` section is how an operator sets the stream WAL's `wal_path` (#634-3). `createAll`, called
/// by `AetherNode` exactly as below, built that section as an instance AND built `streams` from its own arm, and
/// collecting the two setups threw `IllegalStateException: Duplicate key streams` (found while fixing #856, probed
/// at `61d5ac3a2`). Mutation that reddens it: pass `configs` unfiltered to `pendingSetups` in that overload.
class StorageFactoryStreamsSectionTest {
    private static final String NODE_ID = "streams-section-node";

    @TempDir
    Path tempDir;

    @Test
    void streamsSectionCarryingOnlyWalPath_bootsWithOneStreamsInstance() {
        var configs = new HashMap<>(HermeticStorage.nodeStorageIn(tempDir, false));
        var defaults = StorageConfig.storageConfig();

        // Loader defaults for every key but wal_path -- what `[storage.streams] wal_path = "..."` alone parses to.
        configs.put("streams", StorageConfig.storageConfig(defaults.memoryMaxBytes(),
                                                           defaults.diskMaxBytes(),
                                                           defaults.diskPath(),
                                                           defaults.snapshotPath(),
                                                           defaults.snapshotMutationThreshold(),
                                                           defaults.snapshotMaxInterval(),
                                                           defaults.snapshotRetentionCount(),
                                                           tempDir.resolve("wal").toString()));

        var result = StorageFactory.createAll(Map.copyOf(configs),
                                              NODE_ID,
                                              Option.none(),
                                              Option.none(),
                                              new StorageFactory.StreamSetupRequest(Option.none(),
                                                                                    tempDir.resolve("stream-data"),
                                                                                    NODE_ID,
                                                                                    Option.none()));

        assertThat(result.isSuccess()).as("a [storage.streams] section must not fail the boot: %s", result).isTrue();
        assertThat(result.unwrap()).containsOnlyKeys("artifacts", "content", "streams");
    }
}
