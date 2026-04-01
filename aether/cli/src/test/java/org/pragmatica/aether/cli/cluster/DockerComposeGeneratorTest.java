package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.AutoHealSpec;
import org.pragmatica.aether.config.cluster.ClusterManagementConfig;
import org.pragmatica.aether.config.cluster.ClusterSpec;
import org.pragmatica.aether.config.cluster.CoreSpec;
import org.pragmatica.aether.config.cluster.DeploymentSpec;
import org.pragmatica.aether.config.cluster.DeploymentType;
import org.pragmatica.aether.config.cluster.DistributionConfig;
import org.pragmatica.aether.config.cluster.DistributionStrategy;
import org.pragmatica.aether.config.cluster.PortMapping;
import org.pragmatica.aether.config.cluster.RuntimeConfig;
import org.pragmatica.aether.config.cluster.RuntimeType;
import org.pragmatica.aether.config.cluster.UpgradeSpec;
import org.pragmatica.aether.config.cluster.UpgradeStrategy;
import org.pragmatica.aether.config.cluster.WorkerSpec;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerComposeGeneratorTest {

    private static ClusterManagementConfig fiveNodeConfig() {
        return ClusterManagementConfig.clusterManagementConfig(
            DeploymentSpec.deploymentSpec(
                DeploymentType.ON_PREMISES,
                Map.of("core", "bare-metal"),
                RuntimeConfig.runtimeConfig(RuntimeType.CONTAINER,
                                            Option.some("ghcr.io/pragmaticalabs/aether-node:0.25.0"),
                                            Option.none()),
                Map.of(),
                PortMapping.portMapping(6000, 5150, 8070, 6100),
                Option.none(),
                Option.none()
            ),
            ClusterSpec.clusterSpec(
                "test-cluster",
                "0.25.0",
                CoreSpec.coreSpec(5, 3, 9),
                WorkerSpec.workerSpec(0),
                DistributionConfig.distributionConfig(DistributionStrategy.BALANCED, List.of()),
                AutoHealSpec.autoHealSpec(true, "60s", "15s"),
                UpgradeSpec.upgradeSpec(UpgradeStrategy.ROLLING)
            )
        );
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
            assertTrue(compose.contains("image: ghcr.io/pragmaticalabs/aether-node:0.25.0"));
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
