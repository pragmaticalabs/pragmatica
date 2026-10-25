// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// A0b: the loader marks an instance's disk path as the operator's own exactly when `disk_path` was written. The node
/// refuses to boot over an operator-set path that cannot open and degrades over a default or derived one.
class StorageDiskPathOperatorSetTest {
    private static java.util.Map<String, StorageConfig> load(String toml) {
        return ConfigLoader.loadFromString(toml).fold(cause -> fail(cause.message()), config -> config).storage();
    }

    /// Mutation: `markedIfDiskPathWritten` never marks.
    @Test
    void loader_writtenDiskPath_isMarkedOperatorSet() {
        assertThat(load("[storage.artifacts]\ndisk_path = \"/mnt/aether\"\n").get("artifacts").diskPathOperatorSet()).isTrue();
    }

    /// Mutation: `markedIfDiskPathWritten` always marks -- the stock container, whose sections omit `disk_path`, would
    /// then refuse to boot.
    @Test
    void loader_sectionWithoutDiskPath_isNotMarked_andKeepsTheDefaultPath() {
        var artifacts = load("[storage.artifacts]\nmemory_max_bytes = 1048576\n").get("artifacts");

        assertThat(artifacts.diskPathOperatorSet()).isFalse();
        assertThat(artifacts.diskPath()).isEqualTo(StorageConfig.storageConfig().diskPath());
    }

    /// Mutation: `withShared` / `derivingPaths` rebuild the record without carrying the mark.
    @Test
    void mark_survivesTheCopiesTheNodeMakes() {
        var marked = StorageConfig.storageConfig().withOperatorDiskPath();

        assertThat(marked.withShared(SharedTierConfig.dht()).diskPathOperatorSet()).isTrue();
        assertThat(marked.derivingPaths().diskPathOperatorSet()).isTrue();
        assertThat(marked.withMemoryMaxBytes(1L).diskPathOperatorSet()).isTrue();
    }

    /// Mutation: `withPaths` keeps the mark -- a path the node derives would read as the operator's.
    @Test
    void withPaths_clearsTheMark_becauseTheNodeNowChoseThePath() {
        assertThat(StorageConfig.storageConfig().withOperatorDiskPath().withPaths("/a", "/b").diskPathOperatorSet()).isFalse();
    }

    /// Mutation: the default configuration is born marked.
    @Test
    void defaults_andTheLegacyConstructors_areNotMarked() {
        assertThat(StorageConfig.storageConfig().diskPathOperatorSet()).isFalse();
        assertThat(StorageConfig.storageConfig(1L, 2L, "/d", "/s", 1, "60s", 1).diskPathOperatorSet()).isFalse();
    }
}
