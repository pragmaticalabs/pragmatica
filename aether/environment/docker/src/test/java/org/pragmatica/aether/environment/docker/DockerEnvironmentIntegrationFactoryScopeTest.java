// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import java.util.Map;

import org.pragmatica.aether.environment.CloudConfig;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1543 F2: the cluster a Docker provider is scoped to travels in the compute map (`cluster_name`), because the CLI that tears a
/// cluster down has no `AETHER_CLUSTER_NAME` of its own.
class DockerEnvironmentIntegrationFactoryScopeTest {
    private static CloudConfig computeOf(Map<String, String> compute) {
        return new CloudConfig("docker", Map.of(), compute, Map.of(), Map.of(), Map.of(), Map.of());
    }

    @Test
    void buildDockerConfig_clusterNameFromTheComputeMap_scopesTheProvider() {
        var config = DockerEnvironmentIntegrationFactory.buildDockerConfig(computeOf(Map.of("cluster_name", "prod"))).unwrap();

        assertThat(config.clusterName()).isEqualTo("prod");
    }

    @Test
    void buildDockerConfig_computeMapWinsOverTheHostEnvironment() {
        var fromHost = System.getenv("AETHER_CLUSTER_NAME");
        var config = DockerEnvironmentIntegrationFactory.buildDockerConfig(computeOf(Map.of("cluster_name", "explicit"))).unwrap();

        assertThat(config.clusterName()).as("host env was: " + fromHost).isEqualTo("explicit");
    }
}
