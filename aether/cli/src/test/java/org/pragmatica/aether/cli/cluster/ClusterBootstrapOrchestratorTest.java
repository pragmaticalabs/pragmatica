package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NetworkingType;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.RuntimeType;
import org.pragmatica.aether.config.cluster.SourceType;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.config.cluster.ClusterBootstrapConfig.clusterBootstrapConfig;
import static org.pragmatica.aether.config.cluster.ClusterIdentity.clusterIdentity;
import static org.pragmatica.aether.config.cluster.CoreTopology.defaultCoreTopology;
import static org.pragmatica.aether.config.cluster.InfrastructureConfig.infrastructureConfig;
import static org.pragmatica.aether.config.cluster.OperationsConfig.defaultOperationsConfig;
import static org.pragmatica.aether.config.cluster.RoleSubTable.roleSubTable;
import static org.pragmatica.aether.config.cluster.RuntimeProfile.runtimeProfile;
import static org.pragmatica.aether.config.cluster.SourceProfile.sourceProfile;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


class ClusterBootstrapOrchestratorTest {

    private static final String RUNTIME_REF = "ember";

    @Nested
    class BootstrapFlow {
        @Test
        void bootstrap_validForgeConfig_returnsResult() {
            var config = forgeConfig("test-forge", 3);

            var result = ClusterBootstrapOrchestrator.bootstrap(config);

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(ClusterBootstrapOrchestratorTest::verifyForgeResult);
        }
    }

    @Nested
    class ApiKeyGeneration {
        @Test
        void generateApiKey_produces32ByteBase64() {
            var apiKey = ClusterBootstrapOrchestrator.generateApiKey();
            var decoded = Base64.getUrlDecoder().decode(apiKey);

            assertEquals(32, decoded.length);
            assertFalse(apiKey.contains("="));
            assertTrue(apiKey.length() > 0);
        }
    }

    private static void verifyForgeResult(ClusterBootstrapOrchestrator.BootstrapResult r) {
        assertEquals("test-forge", r.clusterName());
        assertFalse(r.apiKey().isEmpty());
        assertEquals("AETHER_TEST_FORGE_API_KEY", r.apiKeyEnvName());
    }

    private static ClusterBootstrapConfig forgeConfig(String clusterName, int coreCount) {
        var forgeSource = sourceProfile(
            "forge", SourceType.FORGE, none(), none(), none(), none(),
            none(), none(), none(), LoadBalancerMode.NONE, List.of(), none(),
            Map.of(),
            Map.of(NodeRole.CORE, roleSubTable(NodeRole.CORE, some(coreCount), none(), none(), RUNTIME_REF)),
            List.of()
        );

        return clusterBootstrapConfig(
            "1", clusterIdentity(clusterName, "1.0"),
            defaultCoreTopology(),
            Map.of("forge", forgeSource),
            Map.of(RUNTIME_REF, runtimeProfile(RUNTIME_REF, RuntimeType.EMBER, none(), none())),
            infrastructureConfig(NetworkingType.MANUAL),
            defaultOperationsConfig()
        );
    }
}
