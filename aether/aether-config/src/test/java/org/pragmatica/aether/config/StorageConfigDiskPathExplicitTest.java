// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// A0b: `diskPathExplicit` is what separates a tier an operator placed (a boot that cannot open it is refused) from one the
/// node placed (it degrades loudly). Only the loader sets it, and only for a section that writes `disk_path`.
class StorageConfigDiskPathExplicitTest {
    private static StorageConfig loaded(String toml, String instance) {
        return ConfigLoader.loadFromString(toml)
                           .fold(cause -> fail(cause.message()), config -> config)
                           .storage()
                           .get(instance);
    }

    @Test
    void sectionWritingDiskPath_isExplicit() {
        assertThat(loaded("[storage.artifacts]\ndisk_path = \"/srv/blocks\"\n", "artifacts").diskPathExplicit()).isTrue();
        assertThat(loaded("[storage.vault]\ndisk_path = \"/srv/vault\"\nsnapshot_path = \"/srv/snap\"\n", "vault").diskPathExplicit()).isTrue();
    }

    @Test
    void sectionWithoutDiskPath_isNotExplicit_evenWhenItWritesOtherKeys() {
        assertThat(loaded("[storage.artifacts]\nmemory_max_bytes = 1048576\n", "artifacts").diskPathExplicit()).isFalse();
        assertThat(loaded("[storage.vault]\nsnapshot_path = \"/srv/snap\"\n", "vault").diskPathExplicit()).isFalse();
        assertThat(loaded("[storage.content.shared]\nkind = \"dht\"\n", "content").diskPathExplicit()).isFalse();
    }

    @Test
    void programmaticConfiguration_isNotExplicit() {
        assertThat(StorageConfig.storageConfig().diskPathExplicit()).isFalse();
    }

    @Test
    void explicitness_survivesTheSharedTierAttachedAfterTheLoad() {
        var toml = "[storage.artifacts]\ndisk_path = \"/srv/blocks\"\n[storage.artifacts.shared]\nkind = \"dht\"\n";

        assertThat(loaded(toml, "artifacts").diskPathExplicit()).isTrue();
    }

    @Test
    void withMemoryMaxBytes_keepsEverythingElse() {
        var explicit = loaded("[storage.artifacts]\ndisk_path = \"/srv/blocks\"\n", "artifacts");

        var cut = explicit.withMemoryMaxBytes(1234);

        assertThat(cut.memoryMaxBytes()).isEqualTo(1234);
        assertThat(cut.withMemoryMaxBytes(explicit.memoryMaxBytes())).isEqualTo(explicit);
    }
}
