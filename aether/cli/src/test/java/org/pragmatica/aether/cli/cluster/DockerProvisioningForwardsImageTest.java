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

/// #1543 F2: a DOCKER source's containers boot the image their ROLE's runtime profile pins at the cluster version, on both CLI call sites
/// that provision them (bootstrap, scale wave). Driven from the dispatch points with a factory that records the [CloudConfig] it is given:
/// a call site that resolves the provider without the pin leaves `image_name` unset and the provider boots its one default image.
class DockerProvisioningForwardsImageTest {
    private static final String PINNED = """
            config_version = "1.0.0"

            [cluster]
            name = "dock"
            version = "1.1.0"

            [runtime.coreapp]
            type = "container"
            image = "registry/aether-node:{version}"

            [runtime.workerapp]
            type = "container"
            image = "registry/aether-worker:{version}"

            [source.local]
            type = "docker"

            [source.local.core]
            count = 1
            runtime = "coreapp"

            [source.local.worker]
            count = 1
            runtime = "workerapp"
            """;

    private static final String UNPINNED = """
            config_version = "1.0.0"

            [cluster]
            name = "dock"
            version = "1.1.0"

            [source.local]
            type = "docker"

            [source.local.core]
            count = 1
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

    @Test
    void bootstrapProvisioning_handsTheCoreProviderTheCoreRolesPinAtTheClusterVersion() {
        var config = ClusterBootstrapConfigParser.parse(PINNED).unwrap();

        bootstrap(config);

        assertThat(seen).as("CONTROL: the core group reached the provider factory").isNotEmpty();
        assertThat(seen.getFirst().compute()).containsEntry("image_name", "registry/aether-node:1.1.0");
    }

    /// The provider is resolved per role: a worker role pinning another image must not boot the core's. The core group fails in the
    /// recording factory and stops the loop, so the worker's provider is read through the scale-wave call site instead.
    @Test
    void scaleWaveProvisioning_handsTheProviderTheRolesOwnPin() {
        var config = ClusterBootstrapConfigParser.parse(PINNED).unwrap();

        WaveExecutor.provisionBySourceType(sourceNameOrDefault("local"), config.sources().get("local"), NodeRole.WORKER, 1, config);

        assertThat(seen).as("CONTROL: the call site reached the provider factory exactly once").hasSize(1);
        assertThat(seen.getFirst().compute()).containsEntry("image_name", "registry/aether-worker:1.1.0");
    }

    /// Resolved per role, not per source: a source with only a worker role hands the provider the WORKER's pin.
    @Test
    void bootstrapProvisioning_workerOnlySource_handsTheProviderTheWorkersPin() {
        var config = ClusterBootstrapConfigParser.parse(PINNED.replace("[source.local.core]\n            count = 1\n            runtime = \"coreapp\"\n", "")).unwrap();

        bootstrap(config);

        assertThat(seen).as("CONTROL: the worker group reached the provider factory").isNotEmpty();
        assertThat(seen.getFirst().compute()).containsEntry("image_name", "registry/aether-worker:1.1.0");
    }

    @Test
    void bootstrapProvisioning_roleWithoutAPin_handsTheProviderNoImage() {
        var config = ClusterBootstrapConfigParser.parse(UNPINNED).unwrap();

        bootstrap(config);

        assertThat(seen).as("CONTROL: the core group reached the provider factory").isNotEmpty();
        assertThat(seen.getFirst().compute()).doesNotContainKey("image_name");
    }

    private static void bootstrap(org.pragmatica.aether.config.cluster.ClusterBootstrapConfig config) {
        var clusterName = org.pragmatica.aether.environment.ClusterName.clusterName("dock").unwrap();
        var ctx = BootstrapContext.bootstrapContext(config, BootstrapState.initialState(clusterName, "h", "now"), List.of(), List.of());

        BootstrapPhaseProvision.provisionSource(ctx, sourceNameOrDefault("local"), config.sources().get("local"), 5150, clusterName);
    }
}
