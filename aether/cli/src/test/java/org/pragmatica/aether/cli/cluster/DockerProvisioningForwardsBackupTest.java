// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// #1968: the two CLI call sites that provision a DOCKER source's nodes (`BootstrapPhaseProvision.provisionSource` on bootstrap,
/// `WaveExecutor.provisionBySourceType` on a scale wave) must hand the Docker provider the source's `[backup]`. Each is driven from
/// its dispatch point with a factory that records the [CloudConfig] it is given, so a call site that resolves the provider
/// WITHOUT the source's backup (`resolveDockerComputeWithoutBackup`) turns these red.
class DockerProvisioningForwardsBackupTest {
    private static final String WITH_BACKUP = """
            config_version = "1.0.0"

            [cluster]
            name = "dock"
            version = "1.0.0"

            [source.local]
            type = "docker"

            [source.local.node_config.backup]
            enabled = true
            path = "/data/backups"
            remote = "/data/backup-remote/kv.git"
            """;

    private final List<CloudConfig> seen = new ArrayList<>();

    @BeforeEach
    void install() {
        ProviderResolver.dockerFactoryOverride = new EnvironmentIntegrationFactory() {
            @Override
            public String providerName() {
                return "docker";
            }

            @Override
            public Result<EnvironmentIntegration> create(CloudConfig config) {
                seen.add(config);
                return Causes.cause("recorded, not provisioning").result();
            }
        };
    }

    @AfterEach
    void remove() {
        ProviderResolver.dockerFactoryOverride = null;
    }

    private void assertForwarded() {
        assertThat(seen).as("CONTROL: the call site reached the provider factory exactly once").hasSize(1);
        assertThat(seen.getFirst().compute()).containsEntry("AETHER_BACKUP_ENABLED", "true")
                                              .containsEntry("AETHER_BACKUP_PATH", "/data/backups")
                                              .containsEntry("AETHER_BACKUP_REMOTE", "/data/backup-remote/kv.git");
    }

    @Test
    void bootstrapProvisioning_handsTheDockerProviderTheSourcesBackup() {
        var config = ClusterBootstrapConfigParser.parse(WITH_BACKUP).unwrap();
        var ctx = BootstrapContext.bootstrapContext(config,
                                                    BootstrapState.initialState(org.pragmatica.aether.environment.ClusterName.clusterName("dock").unwrap(), "h", "now"),
                                                    List.of(),
                                                    List.of());

        BootstrapPhaseProvision.provisionSource(ctx,
                                                sourceNameOrDefault("local"),
                                                config.sources().get("local"),
                                                5150,
                                                org.pragmatica.aether.environment.ClusterName.clusterName("dock").unwrap());

        assertForwarded();
    }

    @Test
    void scaleWaveProvisioning_handsTheDockerProviderTheSourcesBackup() {
        var config = ClusterBootstrapConfigParser.parse(WITH_BACKUP).unwrap();

        WaveExecutor.provisionBySourceType(sourceNameOrDefault("local"), config.sources().get("local"), NodeRole.CORE, 1, config);

        assertForwarded();
    }
}
