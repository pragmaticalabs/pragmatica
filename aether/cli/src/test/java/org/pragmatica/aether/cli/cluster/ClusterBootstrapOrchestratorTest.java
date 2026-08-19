// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

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

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigValidator;

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
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


class ClusterBootstrapOrchestratorTest {

    private static final String RUNTIME_REF = "ember";

    @Nested
    class BootstrapFlow {
        @Test
        void bootstrap_validForgeConfig_passesValidation() {
            var config = forgeConfig("test-forge", 3);

            ClusterBootstrapConfigValidator.validate(config)
                .onFailure(cause -> fail("Expected validation success but got: " + cause.message()))
                .onSuccess(validated -> {
                    assertEquals("test-forge", validated.cluster().name());
                    assertFalse(validated.sources().isEmpty());
                });
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

    private static ClusterBootstrapConfig forgeConfig(String clusterName, int coreCount) {
        var forgeSource = sourceProfile(
            sourceNameOrDefault("forge"), SourceType.FORGE, none(), none(), none(), none(),
            none(), none(), none(), LoadBalancerMode.NONE, List.of(), none(),
            Map.of(),
            Map.of(NodeRole.CORE, roleSubTable(NodeRole.CORE, some(coreCount), none(), none(), RUNTIME_REF)),
            List.of()
        );

        return clusterBootstrapConfig(
            "1", clusterIdentity(clusterName, "1.0.0").unwrap(),
            defaultCoreTopology(),
            Map.of("forge", forgeSource),
            Map.of(RUNTIME_REF, runtimeProfile(RUNTIME_REF, RuntimeType.EMBER, none(), none())),
            infrastructureConfig(NetworkingType.MANUAL),
            defaultOperationsConfig()
        );
    }
}
