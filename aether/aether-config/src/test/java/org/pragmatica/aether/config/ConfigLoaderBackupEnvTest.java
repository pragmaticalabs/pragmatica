// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import java.util.Map;

import org.pragmatica.config.toml.TomlParser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1968: `[backup]` is read from the node TOML, and each key is overridable by its `AETHER_BACKUP_*` variable. A node minted
/// without a TOML of its own (a Docker replacement) therefore gets the backup configuration from its provisioner's environment.
class ConfigLoaderBackupEnvTest {
    private static BackupConfig load(String toml, Map<String, String> env) {
        var doc = TomlParser.parse(toml).unwrap();

        return ConfigLoader.backupConfigFrom(doc, env::get).or((BackupConfig) null);
    }

    @Test
    void envAlone_enablesBackup_withNoTomlSection() {
        var backup = load("", Map.of("AETHER_BACKUP_ENABLED", "true",
                                     "AETHER_BACKUP_PATH", "/data/backups",
                                     "AETHER_BACKUP_REMOTE", "git@backups.example.com:ops/c.git",
                                     "AETHER_BACKUP_RESTORE", "fresh"));

        assertThat(backup).isEqualTo(BackupConfig.backupConfig(true, "/data/backups", "git@backups.example.com:ops/c.git", BackupConfig.RestoreMode.FRESH));
    }

    @Test
    void tomlAlone_isUnchanged() {
        var backup = load("[backup]\nenabled = true\npath = \"/var/aether/backups\"\n", Map.of());

        assertThat(backup).isEqualTo(BackupConfig.backupConfig(true, "/var/aether/backups", "", BackupConfig.RestoreMode.AUTO));
    }

    @Test
    void envOverridesTomlPerKey_andKeepsTheKeysItDoesNotSet() {
        var backup = load("[backup]\nenabled = true\npath = \"/toml/path\"\nremote = \"toml-remote\"\n",
                          Map.of("AETHER_BACKUP_PATH", "/env/path"));

        assertThat(backup.path()).isEqualTo("/env/path");
        assertThat(backup.remote()).as("a key the environment does not set keeps its TOML value").isEqualTo("toml-remote");
    }

    @Test
    void envCanDisable_andNothingSetMeansDisabled() {
        assertThat(load("[backup]\nenabled = true\npath = \"/p\"\n", Map.of("AETHER_BACKUP_ENABLED", "false"))).isNull();
        assertThat(load("", Map.of())).as("control: no section and no environment").isNull();
        assertThat(load("", Map.of("AETHER_BACKUP_PATH", "/p"))).as("a path alone does not enable it").isNull();
    }
}
