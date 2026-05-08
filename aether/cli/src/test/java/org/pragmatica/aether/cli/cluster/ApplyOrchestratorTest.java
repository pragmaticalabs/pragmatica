// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NetworkingType;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.RuntimeType;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

class ApplyOrchestratorTest {

    private static final String RUNTIME_REF = "default";

    private static SourceProfile forgeSource(String name, int coreCount) {
        return sourceProfile(
            name, SourceType.FORGE, none(), none(), none(), none(),
            none(), none(), none(), LoadBalancerMode.NONE, List.of(), none(),
            Map.of(),
            Map.of(NodeRole.CORE, roleSubTable(NodeRole.CORE, some(coreCount), none(), none(), RUNTIME_REF)),
            List.of()
        );
    }

    private static SourceProfile sourceWithRole(String name, NodeRole role, int count) {
        return sourceProfile(
            name, SourceType.FORGE, none(), none(), none(), none(),
            none(), none(), none(), LoadBalancerMode.NONE, List.of(), none(),
            Map.of(),
            Map.of(role, roleSubTable(role, some(count), none(), none(), RUNTIME_REF)),
            List.of()
        );
    }

    private static ClusterBootstrapConfig configWithSources(Map<String, SourceProfile> sources) {
        return clusterBootstrapConfig(
            "1", clusterIdentity("test-cluster", "1.0.0").unwrap(),
            defaultCoreTopology(), sources,
            Map.of(RUNTIME_REF, runtimeProfile(RUNTIME_REF, RuntimeType.JVM, none(), none())),
            infrastructureConfig(NetworkingType.MANUAL),
            defaultOperationsConfig()
        );
    }

    private static ClusterBootstrapConfig forgeConfig(int coreCount) {
        return configWithSources(Map.of("forge", forgeSource("forge", coreCount)));
    }

    private static ClusterBootstrapConfig configWithName(String name, int coreCount) {
        return clusterBootstrapConfig(
            "1", clusterIdentity(name, "1.0.0").unwrap(),
            defaultCoreTopology(),
            Map.of("forge", forgeSource("forge", coreCount)),
            Map.of(RUNTIME_REF, runtimeProfile(RUNTIME_REF, RuntimeType.JVM, none(), none())),
            infrastructureConfig(NetworkingType.MANUAL),
            defaultOperationsConfig()
        );
    }

    @Nested
    class DryRunTests {
        @Test
        void dryRun_identicalConfigs_returnsFormattedOutput() {
            var config = forgeConfig(3);

            ApplyOrchestrator.dryRun(config, config)
                .onFailure(_ -> fail("Expected success"))
                .onSuccess(output -> assertTrue(output.contains("Cluster: test-cluster")));
        }

        @Test
        void dryRun_scaleUp_returnsFormattedPlan() {
            var stored = forgeConfig(3);
            var desired = forgeConfig(5);

            ApplyOrchestrator.dryRun(desired, stored)
                .onFailure(_ -> fail("Expected success"))
                .onSuccess(ApplyOrchestratorTest::assertScaleUpPlanFormatted);
        }

        @Test
        void dryRun_immutableChange_returnsError() {
            var stored = configWithName("cluster-a", 3);
            var desired = configWithName("cluster-b", 3);

            ApplyOrchestrator.dryRun(desired, stored)
                .onSuccess(_ -> fail("Expected failure"))
                .onFailure(cause -> assertInstanceOf(ClusterConfigError.ImmutableFieldChange.class, cause));
        }
    }

    @Nested
    class ApplyTests {
        @Test
        void apply_identicalConfigs_returnsZeroCounts() {
            var config = forgeConfig(3);

            ApplyOrchestrator.apply(config, config)
                .onFailure(_ -> fail("Expected success"))
                .onSuccess(ApplyOrchestratorTest::assertZeroCounts);
        }

        @Test
        void apply_scaleUp_returnsCorrectNodeCounts() {
            var stored = forgeConfig(3);
            var desired = forgeConfig(5);

            ApplyOrchestrator.apply(desired, stored)
                .onFailure(_ -> fail("Expected success"))
                .onSuccess(ApplyOrchestratorTest::assertScaleUpCounts);
        }

        @Test
        void apply_immutableChange_returnsError() {
            var stored = configWithName("cluster-a", 3);
            var desired = configWithName("cluster-b", 3);

            ApplyOrchestrator.apply(desired, stored)
                .onSuccess(_ -> fail("Expected failure"))
                .onFailure(cause -> assertInstanceOf(ClusterConfigError.ImmutableFieldChange.class, cause));
        }

        @Test
        void apply_addSource_countsNodesAdded() {
            var stored = forgeConfig(3);
            var desired = configWithSources(Map.of(
                "forge", forgeSource("forge", 3),
                "cloud-a", sourceWithRole("cloud-a", NodeRole.WORKER, 5)
            ));

            ApplyOrchestrator.apply(desired, stored)
                .onFailure(_ -> fail("Expected success"))
                .onSuccess(result -> assertEquals(5, result.nodesAdded()));
        }

        @Test
        void apply_removeSource_removalsTrackedAsActions() {
            var stored = configWithSources(Map.of(
                "forge", forgeSource("forge", 3),
                "cloud-a", sourceWithRole("cloud-a", NodeRole.WORKER, 5)
            ));
            var desired = forgeConfig(3);

            ApplyOrchestrator.apply(desired, stored)
                .onFailure(_ -> fail("Expected success"))
                .onSuccess(result -> assertTrue(result.executedPlan().removals().size() > 0));
        }
    }

    private static void assertScaleUpPlanFormatted(String output) {
        assertTrue(output.contains("Current: 3 cores"));
        assertTrue(output.contains("Desired: 5 cores"));
        assertTrue(output.contains("~ forge.core  count: 3 -> 5"));
    }

    private static void assertZeroCounts(ApplyResult result) {
        assertEquals(0, result.nodesAdded());
        assertEquals(0, result.nodesRemoved());
        assertEquals(0, result.nodesModified());
        assertTrue(result.executedPlan().isEmpty());
    }

    private static void assertScaleUpCounts(ApplyResult result) {
        assertEquals(2, result.nodesAdded());
        assertEquals(0, result.nodesRemoved());
        assertEquals(0, result.nodesModified());
    }
}
