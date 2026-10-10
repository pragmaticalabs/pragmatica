// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import org.pragmatica.aether.config.BackupConfig;
import org.pragmatica.aether.environment.CloudConfig;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1968: the provider that mints replacements is handed this node's EFFECTIVE `[backup]` (TOML or environment alike), so a Docker
/// leader whose backup is in its TOML still gives its replacements the same one.
class MainEffectiveBackupTest {
    private static final CloudConfig DOCKER = new CloudConfig("docker", Map.of(), Map.of("image_name", "x:1"), Map.of(), Map.of(), Map.of(), Map.of());

    @Test
    void effectiveBackup_isAddedToTheComputeMap_keepingTheTomlKeys() {
        var cloud = Main.withEffectiveBackup(DOCKER, BackupConfig.backupConfig(true, "/data/backups", "", BackupConfig.RestoreMode.AUTO));

        assertThat(cloud.compute()).containsEntry("image_name", "x:1")
                                   .containsEntry("AETHER_BACKUP_ENABLED", "true")
                                   .containsEntry("AETHER_BACKUP_PATH", "/data/backups");
    }

    @Test
    void noBackup_addsNothing() {
        assertThat(Main.withEffectiveBackup(DOCKER, BackupConfig.backupConfig()).compute()).isEqualTo(DOCKER.compute());
    }

    /// Wiring pin: the environment is created from the cloud config WITH the effective backup. Reading the source (comments stripped,
    /// whitespace removed) is the only way to see that, because `resolveEnvironment` needs a whole node config to run.
    @Test
    void resolveEnvironment_createsTheEnvironmentFromTheConfigWithTheEffectiveBackup() throws Exception {
        var root = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().getParent().resolve("src/main/java");
        var code = Files.readString(root.resolve("org/pragmatica/aether/Main.java")).lines()
                        .map(line -> line.replaceFirst("//.*$", ""))
                        .collect(Collectors.joining())
                        .replaceAll("\\s+", "");

        assertThat(code).contains("withEffectiveBackup(cloud,config.backup())");
    }
}
