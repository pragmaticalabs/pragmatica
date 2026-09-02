// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterIdentity;
import org.pragmatica.aether.config.cluster.CoreTopology;
import org.pragmatica.aether.config.cluster.InfrastructureConfig;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NetworkingType;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.OperationsConfig;
import org.pragmatica.aether.config.cluster.PortMapping;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.RuntimeProfile;
import org.pragmatica.aether.config.cluster.RuntimeType;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.config.cluster.TlsDeploymentConfig;
import org.pragmatica.aether.config.cluster.TimeoutsConfig;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

class DockerComposeGeneratorTest {

    private static ClusterBootstrapConfig fiveNodeConfig() {
        var ports = PortMapping.portMapping(6000, 5150, 8070, 6100);
        var roleSubTable = RoleSubTable.roleSubTable(
            NodeRole.CORE, Option.some(5), Option.none(), Option.some("bare-metal"), "default");
        var source = SourceProfile.sourceProfile(
            sourceNameOrDefault("docker-source"), SourceType.DOCKER, Option.none(), Option.none(), Option.none(),
            Option.none(), Option.none(), Option.none(), Option.none(),
            LoadBalancerMode.NONE, List.of(), Option.none(), Map.of(),
            Map.of(NodeRole.CORE, roleSubTable), List.of());
        var runtime = RuntimeProfile.runtimeProfile(
            "default", RuntimeType.CONTAINER,
            Option.some("ghcr.io/pragmaticalabs/aether-node:1.0.0-alpha"), Option.none());
        return ClusterBootstrapConfig.clusterBootstrapConfig(
            "1",
            ClusterIdentity.clusterIdentity("test-cluster", "1.0.0-alpha").unwrap(),
            CoreTopology.defaultCoreTopology(),
            Map.of("docker-source", source),
            Map.of("default", runtime),
            InfrastructureConfig.infrastructureConfig(NetworkingType.MANUAL),
            OperationsConfig.operationsConfig(
                org.pragmatica.aether.config.cluster.AutoHealSpec.defaultAutoHealSpec(),
                TlsDeploymentConfig.defaultTlsConfig(),
                TimeoutsConfig.defaultTimeoutsConfig(),
                ports));
    }

    @Nested
    class FiveNodeCluster {
        @Test
        void generate_fiveNodes_correctServiceCount() {
            var compose = DockerComposeGenerator.generate(fiveNodeConfig(), "test-api-key");
            assertTrue(compose.contains("node-1:"), "Should contain node-1 service");
            assertTrue(compose.contains("node-2:"), "Should contain node-2 service");
            assertTrue(compose.contains("node-3:"), "Should contain node-3 service");
            assertTrue(compose.contains("node-4:"), "Should contain node-4 service");
            assertTrue(compose.contains("node-5:"), "Should contain node-5 service");
        }

        @Test
        void generate_fiveNodes_correctImage() {
            var compose = DockerComposeGenerator.generate(fiveNodeConfig(), "test-api-key");
            assertTrue(compose.contains("image: ghcr.io/pragmaticalabs/aether-node:1.0.0-alpha"));
        }

        @Test
        void generate_fiveNodes_correctPortOffsets() {
            var compose = DockerComposeGenerator.generate(fiveNodeConfig(), "test-api-key");
            // Node 1: base ports
            assertTrue(compose.contains("\"5150:5150\""), "Node 1 management port");
            assertTrue(compose.contains("\"8070:8070\""), "Node 1 app-http port");
            assertTrue(compose.contains("\"6000:6000/udp\""), "Node 1 cluster port");
            assertTrue(compose.contains("\"6100:6100/udp\""), "Node 1 swim port");
            // Node 2: base + 1
            assertTrue(compose.contains("\"5151:5150\""), "Node 2 management port");
            assertTrue(compose.contains("\"8071:8070\""), "Node 2 app-http port");
            assertTrue(compose.contains("\"6001:6000/udp\""), "Node 2 cluster port");
            assertTrue(compose.contains("\"6101:6100/udp\""), "Node 2 swim port");
            // Node 5: base + 4
            assertTrue(compose.contains("\"5154:5150\""), "Node 5 management port");
            assertTrue(compose.contains("\"8074:8070\""), "Node 5 app-http port");
        }

        @Test
        void generate_fiveNodes_correctPeers() {
            var compose = DockerComposeGenerator.generate(fiveNodeConfig(), "test-api-key");
            assertTrue(compose.contains("node-1:aether-node-1:6000"), "Peer entry for node-1");
            assertTrue(compose.contains("node-5:aether-node-5:6000"), "Peer entry for node-5");
        }

        @Test
        void generate_fiveNodes_hasHealthcheck() {
            var compose = DockerComposeGenerator.generate(fiveNodeConfig(), "test-api-key");
            assertTrue(compose.contains("healthcheck:"), "Should have healthcheck");
            assertTrue(compose.contains("http://localhost:5150/health/live"), "Healthcheck URL");
            assertTrue(compose.contains("interval: 5s"), "Healthcheck interval");
            assertTrue(compose.contains("start_period: 30s"), "Healthcheck start period");
        }

        @Test
        void generate_fiveNodes_hasNetwork() {
            var compose = DockerComposeGenerator.generate(fiveNodeConfig(), "test-api-key");
            assertTrue(compose.contains("aether-network"), "Should reference aether-network");
            assertTrue(compose.contains("driver: bridge"), "Should use bridge driver");
        }
    }
}
