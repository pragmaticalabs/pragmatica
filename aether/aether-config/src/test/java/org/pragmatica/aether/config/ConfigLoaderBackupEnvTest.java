// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import java.util.Map;

import org.pragmatica.config.toml.TomlParser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /// A blank variable (a compose file's `AETHER_BACKUP_REMOTE: ""`) is unset, so it can neither clear a TOML value nor disable it.
    @Test
    void aBlankVariableIsUnset_itNeitherClearsNorDisablesTheToml() {
        var backup = load("[backup]\nenabled = true\npath = \"/toml/path\"\nremote = \"toml-remote\"\nrestore = \"fresh\"\n",
                          Map.of("AETHER_BACKUP_ENABLED", "  ", "AETHER_BACKUP_PATH", "", "AETHER_BACKUP_REMOTE", " ", "AETHER_BACKUP_RESTORE", ""));

        assertThat(backup).isEqualTo(BackupConfig.backupConfig(true, "/toml/path", "toml-remote", BackupConfig.RestoreMode.FRESH));
    }

    /// Env silently beating a deliberate TOML change is logged once per overriding key, naming the key and both sources and never a value.
    @Test
    void anEnvironmentValueThatDiffersFromTheToml_isReportedByKeyAndSource_neverByValue() {
        var notices = new java.util.ArrayList<String>();
        var doc = TomlParser.parse("[backup]\nenabled = true\npath = \"/toml/path\"\nremote = \"https://user:secret@host/r.git\"\n").unwrap();

        ConfigLoader.backupConfigFrom(doc,
                                      Map.of("AETHER_BACKUP_PATH", "/env/path",
                                             "AETHER_BACKUP_REMOTE", "https://user:secret@host/r.git",
                                             "AETHER_BACKUP_RESTORE", "fresh")::get,
                                      notices::add);

        assertThat(notices).as("path differs; remote is equal; restore has no TOML value to override").hasSize(1);
        assertThat(notices.getFirst()).contains("[backup] path", "AETHER_BACKUP_PATH", "node TOML").doesNotContain("/env/path", "/toml/path", "secret");
    }

    @Test
    void noOverrideNotice_whenTheEnvironmentAgreesOrOnlyFillsAGap() {
        var notices = new java.util.ArrayList<String>();
        var doc = TomlParser.parse("[backup]\nenabled = true\npath = \"/p\"\n").unwrap();

        ConfigLoader.backupConfigFrom(doc, Map.of("AETHER_BACKUP_PATH", "/p", "AETHER_BACKUP_REMOTE", "r")::get, notices::add);

        assertThat(notices).isEmpty();
    }

    /// The winning source of each key is logged once at boot (R3), never a value.
    @Test
    void theWinningSourceOfEachKeyIsReportedOnce_neverTheValue() {
        var summaries = new java.util.ArrayList<String>();
        var doc = TomlParser.parse("[backup]\nenabled = true\npath = \"/toml/path\"\nremote = \"secret-remote\"\n").unwrap();

        ConfigLoader.backupConfigFrom(doc, Map.of("AETHER_BACKUP_PATH", "/env/path")::get, _ -> {}, summaries::add);

        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst()).contains("path=environment", "remote=TOML", "restore=default", "enabled=TOML")
                                        .doesNotContain("/env/path", "/toml/path", "secret-remote");
    }

    /// The path rule applies to the EFFECTIVE path, so a bad value arriving only through the environment (a compose file's
    /// AETHER_BACKUP_PATH, a provisioner's) is refused exactly as a TOML one is (R1b).
    @Test
    void anEnvironmentOnlyPath_isRefusedForADockerNode_whenRelativeOrOutsideData() {
        var doc = TomlParser.parse("").unwrap();

        for (var bad : new String[] {"relative/backups", "/var/aether/backups", "/database/x"}) {
            var env = Map.of("AETHER_BACKUP_ENABLED", "true", "AETHER_BACKUP_PATH", bad);

            assertThatThrownBy(() -> ConfigLoader.effectiveBackup(doc, env::get, Environment.DOCKER, true))
                .as("docker node, env path " + bad)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[backup] path").hasMessageContaining(bad.strip());
        }
    }

    @Test
    void anEnvironmentOnlyPath_underData_isAcceptedForADockerNode() {
        var doc = TomlParser.parse("").unwrap();

        assertThat(ConfigLoader.effectiveBackup(doc, Map.of("AETHER_BACKUP_ENABLED", "true", "AETHER_BACKUP_PATH", "/data/backups")::get, Environment.DOCKER, true)
                               .isPresent()).isTrue();
    }

    @Test
    void theEnvironmentPathRule_isPerEnvironment_kubernetesNeedsAbsolute_localKeepsItsRelativeDefault() {
        var doc = TomlParser.parse("").unwrap();

        assertThatThrownBy(() -> ConfigLoader.effectiveBackup(doc, Map.of("AETHER_BACKUP_ENABLED", "true", "AETHER_BACKUP_PATH", "rel")::get, Environment.KUBERNETES, true))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(ConfigLoader.effectiveBackup(doc, Map.of("AETHER_BACKUP_ENABLED", "true", "AETHER_BACKUP_PATH", "/var/aether/backups")::get, Environment.KUBERNETES, true).isPresent()).isTrue();
        assertThat(ConfigLoader.effectiveBackup(doc, Map.of("AETHER_BACKUP_ENABLED", "true", "AETHER_BACKUP_PATH", "./aether-backups")::get, Environment.LOCAL, true).isPresent()).isTrue();
    }

    /// An invalid path fails the WHOLE load (not just a helper): the loader wraps the refusal as an invalid-config error.
    @Test
    void aTomlPathOutsideData_failsTheLoad_forADockerEnvironment() {
        var result = ConfigLoader.loadFromString("[cluster]\nenvironment = \"docker\"\n[backup]\nenabled = true\npath = \"/srv/backups\"\n");

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("under /data"));
    }

    /// v-2001 blocker: the rule keys on an environment that was SAID. A TOML with no `[cluster] environment` defaults to docker and must
    /// not be treated as a Docker node; the composed cloud node TOML never carries the key.
    @Test
    void aDefaultedEnvironment_isNotRestricted() {
        var doc = TomlParser.parse("[backup]\nenabled = true\npath = \"/var/aether/backups\"\n").unwrap();

        assertThat(ConfigLoader.environmentIsExplicit(doc, Map.of())).isFalse();
        assertThat(ConfigLoader.effectiveBackup(doc, Map.<String, String>of()::get, Environment.DOCKER, false).isPresent()).isTrue();
        assertThat(ConfigLoader.loadFromString("[backup]\nenabled = true\npath = \"/var/aether/backups\"\n").isSuccess()).isTrue();
        assertThat(ConfigLoader.environmentIsExplicit(TomlParser.parse("[cluster]\nenvironment = \"docker\"\n").unwrap(), Map.of())).isTrue();
        assertThat(ConfigLoader.environmentIsExplicit(doc, Map.of("environment", "docker"))).as("a CLI override says it too").isTrue();
    }

    @Test
    void aDockerNode_refusesDotDotEscapesAndSiblingsOfData() {
        var doc = TomlParser.parse("").unwrap();

        for (var bad : new String[] {"/data/../etc", "/database/x", "/data/../../etc/backups"}) {
            assertThatThrownBy(() -> ConfigLoader.effectiveBackup(doc, Map.of("AETHER_BACKUP_ENABLED", "true", "AETHER_BACKUP_PATH", bad)::get, Environment.DOCKER, true))
                .as("docker node, path " + bad).isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(ConfigLoader.effectiveBackup(doc, Map.of("AETHER_BACKUP_ENABLED", "true", "AETHER_BACKUP_PATH", "/data/a/../b")::get, Environment.DOCKER, true).isPresent())
            .as("a path that normalises to a place under /data is fine").isTrue();
    }
}
