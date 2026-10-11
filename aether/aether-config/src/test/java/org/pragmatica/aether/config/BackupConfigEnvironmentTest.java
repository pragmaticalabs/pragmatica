// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1968: the effective `[backup]` as the `AETHER_BACKUP_*` environment a provider hands to a node that has no TOML of its own. It must
/// round-trip through the loader's environment override ([ConfigLoader#backupConfigFrom]), so a replacement runs the SAME backup.
class BackupConfigEnvironmentTest {
    @Test
    void enabledBackup_rendersAllKeys_andRoundTripsThroughTheEnvironmentOverride() {
        var backup = BackupConfig.backupConfig(true, "/data/backups", "git@backups.example.com:ops/c.git", BackupConfig.RestoreMode.FRESH);
        var env = backup.asEnvironment();

        assertThat(env).containsOnly(Map.entry("AETHER_BACKUP_ENABLED", "true"),
                                     Map.entry("AETHER_BACKUP_PATH", "/data/backups"),
                                     Map.entry("AETHER_BACKUP_REMOTE", "git@backups.example.com:ops/c.git"),
                                     Map.entry("AETHER_BACKUP_RESTORE", "fresh"));
        assertThat(ConfigLoader.backupConfigFrom(org.pragmatica.config.toml.TomlParser.parse("").unwrap(), env::get).or((BackupConfig) null))
            .as("the replacement's loader reads back exactly this backup")
            .isEqualTo(backup);
    }

    @Test
    void remoteIsOmittedWhenBlank_andDisabledOrPathlessBackupRendersNothing() {
        assertThat(BackupConfig.backupConfig(true, "/p", "", BackupConfig.RestoreMode.AUTO).asEnvironment()).doesNotContainKey("AETHER_BACKUP_REMOTE");
        assertThat(BackupConfig.backupConfig().asEnvironment()).as("disabled").isEmpty();
        assertThat(BackupConfig.backupConfig(true, "", "", BackupConfig.RestoreMode.AUTO).asEnvironment()).as("no path: the node does not run it either").isEmpty();
    }
}
