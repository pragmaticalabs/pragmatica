// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.config.cluster;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.config.cluster.ClusterBootstrapConfig.clusterBootstrapConfig;
import static org.pragmatica.aether.config.cluster.ClusterBootstrapConfigDiff.diff;
import static org.pragmatica.aether.config.cluster.ClusterBootstrapConfigDiff.formatPlan;
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

class ClusterBootstrapConfigDiffTest {

    private static final String RUNTIME_REF = "default";

    private static SourceProfile forgeSource(String name, int coreCount) {
        return sourceProfile(
            sourceNameOrDefault(name), SourceType.FORGE, none(), none(), none(), none(),
            none(), none(), none(), LoadBalancerMode.NONE, List.of(), none(),
            Map.of(),
            Map.of(NodeRole.CORE, roleSubTable(NodeRole.CORE, some(coreCount), none(), none(), RUNTIME_REF)),
            List.of()
        );
    }

    private static SourceProfile sourceWithRole(String name, NodeRole role, int count, String runtimeRef) {
        return sourceProfile(
            sourceNameOrDefault(name), SourceType.FORGE, none(), none(), none(), none(),
            none(), none(), none(), LoadBalancerMode.NONE, List.of(), none(),
            Map.of(),
            Map.of(role, roleSubTable(role, some(count), none(), none(), runtimeRef)),
            List.of()
        );
    }

    private static SourceProfile sourceWithRoles(String name, Map<NodeRole, RoleSubTable> roles) {
        return sourceProfile(
            sourceNameOrDefault(name), SourceType.FORGE, none(), none(), none(), none(),
            none(), none(), none(), LoadBalancerMode.NONE, List.of(), none(),
            Map.of(), roles, List.of()
        );
    }

    private static ClusterBootstrapConfig configWithSources(Map<String, SourceProfile> sources) {
        return clusterBootstrapConfig(
            "1", clusterIdentity("test-cluster", "1.0").unwrap(),
            defaultCoreTopology(), sources,
            Map.of(RUNTIME_REF, runtimeProfile(RUNTIME_REF, RuntimeType.JVM, none(), none())),
            infrastructureConfig(NetworkingType.MANUAL),
            defaultOperationsConfig()
        );
    }

    private static ClusterBootstrapConfig forgeConfig(int coreCount) {
        return configWithSources(Map.of("forge", forgeSource("forge", coreCount)));
    }

    @Nested
    class IdenticalConfigs {
        @Test
        void diff_identicalConfigs_emptyPlan() {
            var config = forgeConfig(3);
            var plan = diff(config, config);

            assertThat(plan.isEmpty()).isTrue();
            assertThat(plan.additions()).isEmpty();
            assertThat(plan.modifications()).isEmpty();
            assertThat(plan.removals()).isEmpty();
            assertThat(plan.immutable()).isEmpty();
        }
    }

    @Nested
    class SourceAdditionRemoval {
        @Test
        void diff_addSource_producesAddition() {
            var stored = forgeConfig(3);
            var desired = configWithSources(Map.of(
                "forge", forgeSource("forge", 3),
                "cloud-a", sourceWithRole("cloud-a", NodeRole.WORKER, 5, RUNTIME_REF)
            ));
            var plan = diff(stored, desired);

            assertThat(plan.additions()).hasSize(2);
            assertThat(plan.additions().getFirst()).isInstanceOf(DiffAction.AddSource.class);
            assertThat(plan.additions().get(1)).isInstanceOf(DiffAction.AddRole.class);
            assertThat(plan.removals()).isEmpty();
        }

        @Test
        void diff_removeSource_producesRemoval() {
            var stored = configWithSources(Map.of(
                "forge", forgeSource("forge", 3),
                "cloud-a", sourceWithRole("cloud-a", NodeRole.WORKER, 5, RUNTIME_REF)
            ));
            var desired = forgeConfig(3);
            var plan = diff(stored, desired);

            assertThat(plan.removals()).hasSize(1);
            assertThat(plan.removals().getFirst()).isInstanceOf(DiffAction.RemoveSource.class);

            var remove = (DiffAction.RemoveSource) plan.removals().getFirst();
            assertThat(remove.sourceName().value()).isEqualTo("cloud-a");
        }
    }

    @Nested
    class ScaleChanges {
        @Test
        void diff_scaleUp_producesScaleUp() {
            var stored = forgeConfig(3);
            var desired = forgeConfig(5);
            var plan = diff(stored, desired);

            assertThat(plan.additions()).hasSize(1);
            assertThat(plan.additions().getFirst()).isInstanceOf(DiffAction.ScaleUp.class);

            var scaleUp = (DiffAction.ScaleUp) plan.additions().getFirst();
            assertThat(scaleUp.from()).isEqualTo(3);
            assertThat(scaleUp.to()).isEqualTo(5);
        }

        @Test
        void diff_scaleDown_producesScaleDown() {
            var stored = forgeConfig(5);
            var desired = forgeConfig(3);
            var plan = diff(stored, desired);

            assertThat(plan.removals()).hasSize(1);
            assertThat(plan.removals().getFirst()).isInstanceOf(DiffAction.ScaleDown.class);

            var scaleDown = (DiffAction.ScaleDown) plan.removals().getFirst();
            assertThat(scaleDown.from()).isEqualTo(5);
            assertThat(scaleDown.to()).isEqualTo(3);
        }
    }

    @Nested
    class RoleChanges {
        @Test
        void diff_addRole_producesAddition() {
            var stored = forgeConfig(3);
            var desired = configWithSources(Map.of("forge", sourceWithRoles("forge", Map.of(
                NodeRole.CORE, roleSubTable(NodeRole.CORE, some(3), none(), none(), RUNTIME_REF),
                NodeRole.WORKER, roleSubTable(NodeRole.WORKER, some(2), none(), none(), RUNTIME_REF)
            ))));
            var plan = diff(stored, desired);

            assertThat(plan.additions()).hasSize(1);
            assertThat(plan.additions().getFirst()).isInstanceOf(DiffAction.AddRole.class);

            var addRole = (DiffAction.AddRole) plan.additions().getFirst();
            assertThat(addRole.role()).isEqualTo(NodeRole.WORKER);
            assertThat(addRole.count()).isEqualTo(2);
        }

        @Test
        void diff_removeRole_producesRemoval() {
            var stored = configWithSources(Map.of("forge", sourceWithRoles("forge", Map.of(
                NodeRole.CORE, roleSubTable(NodeRole.CORE, some(3), none(), none(), RUNTIME_REF),
                NodeRole.WORKER, roleSubTable(NodeRole.WORKER, some(2), none(), none(), RUNTIME_REF)
            ))));
            var desired = forgeConfig(3);
            var plan = diff(stored, desired);

            assertThat(plan.removals()).hasSize(1);
            assertThat(plan.removals().getFirst()).isInstanceOf(DiffAction.RemoveRole.class);

            var removeRole = (DiffAction.RemoveRole) plan.removals().getFirst();
            assertThat(removeRole.role()).isEqualTo(NodeRole.WORKER);
            assertThat(removeRole.count()).isEqualTo(2);
        }
    }

    @Nested
    class ModificationChanges {
        @Test
        void diff_runtimeChange_producesModification() {
            var stored = configWithSources(Map.of("forge", sourceWithRole("forge", NodeRole.CORE, 3, "runtime-a")));
            var desired = configWithSources(Map.of("forge", sourceWithRole("forge", NodeRole.CORE, 3, "runtime-b")));
            var plan = diff(stored, desired);

            assertThat(plan.modifications()).hasSize(1);
            assertThat(plan.modifications().getFirst()).isInstanceOf(DiffAction.RuntimeChange.class);

            var change = (DiffAction.RuntimeChange) plan.modifications().getFirst();
            assertThat(change.fromRuntime()).isEqualTo("runtime-a");
            assertThat(change.toRuntime()).isEqualTo("runtime-b");
        }

        @Test
        void diff_sourceFieldChange_producesModification() {
            var stored = configWithSources(Map.of("forge", sourceProfile(
                sourceNameOrDefault("forge"), SourceType.FORGE, none(), some("cred-a"), none(), none(),
                none(), none(), none(), LoadBalancerMode.NONE, List.of(), none(),
                Map.of(),
                Map.of(NodeRole.CORE, roleSubTable(NodeRole.CORE, some(3), none(), none(), RUNTIME_REF)),
                List.of()
            )));
            var desired = configWithSources(Map.of("forge", sourceProfile(
                sourceNameOrDefault("forge"), SourceType.FORGE, none(), some("cred-b"), none(), none(),
                none(), none(), none(), LoadBalancerMode.NONE, List.of(), none(),
                Map.of(),
                Map.of(NodeRole.CORE, roleSubTable(NodeRole.CORE, some(3), none(), none(), RUNTIME_REF)),
                List.of()
            )));
            var plan = diff(stored, desired);

            assertThat(plan.modifications()).hasSize(1);
            assertThat(plan.modifications().getFirst()).isInstanceOf(DiffAction.SourceFieldChange.class);

            var change = (DiffAction.SourceFieldChange) plan.modifications().getFirst();
            assertThat(change.sourceName().value()).isEqualTo("forge");
            assertThat(change.field()).isEqualTo("credentials");
        }

        @Test
        void diff_versionChange_producesClusterLevel() {
            var stored = forgeConfig(3);
            var desired = clusterBootstrapConfig(
                "1", clusterIdentity("test-cluster", "2.0").unwrap(),
                defaultCoreTopology(),
                Map.of("forge", forgeSource("forge", 3)),
                Map.of(RUNTIME_REF, runtimeProfile(RUNTIME_REF, RuntimeType.JVM, none(), none())),
                infrastructureConfig(NetworkingType.MANUAL),
                defaultOperationsConfig()
            );
            var plan = diff(stored, desired);

            assertThat(plan.modifications()).hasSize(1);
            assertThat(plan.modifications().getFirst()).isInstanceOf(DiffAction.ClusterLevelChange.class);

            var change = (DiffAction.ClusterLevelChange) plan.modifications().getFirst();
            assertThat(change.field()).isEqualTo("version");
            assertThat(change.from()).isEqualTo("1.0");
            assertThat(change.to()).isEqualTo("2.0");
        }
    }

    @Nested
    class ImmutableChanges {
        @Test
        void diff_clusterNameChange_producesImmutable() {
            var stored = forgeConfig(3);
            var desired = clusterBootstrapConfig(
                "1", clusterIdentity("other-cluster", "1.0").unwrap(),
                defaultCoreTopology(),
                Map.of("forge", forgeSource("forge", 3)),
                Map.of(RUNTIME_REF, runtimeProfile(RUNTIME_REF, RuntimeType.JVM, none(), none())),
                infrastructureConfig(NetworkingType.MANUAL),
                defaultOperationsConfig()
            );
            var plan = diff(stored, desired);

            assertThat(plan.hasImmutableChanges()).isTrue();
            assertThat(plan.immutable()).hasSize(1);
            assertThat(plan.immutable().getFirst()).isInstanceOf(DiffAction.ImmutableFieldChange.class);

            var change = (DiffAction.ImmutableFieldChange) plan.immutable().getFirst();
            assertThat(change.field()).isEqualTo("cluster.name");
        }
    }

    @Nested
    class WaveOrdering {
        @Test
        void diff_waveOrdering_additionsBeforeModificationsBeforeRemovals() {
            var stored = configWithSources(Map.of(
                "forge", sourceWithRole("forge", NodeRole.CORE, 3, "runtime-a"),
                "old-source", sourceWithRole("old-source", NodeRole.WORKER, 2, RUNTIME_REF)
            ));
            var desired = configWithSources(Map.of(
                "forge", sourceWithRole("forge", NodeRole.CORE, 5, "runtime-b"),
                "new-source", sourceWithRole("new-source", NodeRole.WORKER, 4, RUNTIME_REF)
            ));
            var plan = diff(stored, desired);

            assertThat(plan.additions()).isNotEmpty();
            assertThat(plan.modifications()).isNotEmpty();
            assertThat(plan.removals()).isNotEmpty();

            var allActions = plan.allActions();
            var firstAdditionIdx = indexOfFirst(allActions, DiffAction.AddSource.class);
            var firstModificationIdx = indexOfFirst(allActions, DiffAction.RuntimeChange.class);
            var firstRemovalIdx = indexOfFirst(allActions, DiffAction.RemoveSource.class);

            assertThat(firstAdditionIdx).isLessThan(firstModificationIdx);
            assertThat(firstModificationIdx).isLessThan(firstRemovalIdx);
        }

        private static int indexOfFirst(List<DiffAction> actions, Class<? extends DiffAction> type) {
            for (int i = 0; i < actions.size(); i++) {
                if (type.isInstance(actions.get(i))) {
                    return i;
                }
            }
            return -1;
        }
    }

    @Nested
    class FormatTests {
        @Test
        void formatPlan_nonEmpty_producesReadableOutput() {
            var stored = forgeConfig(3);
            var desired = forgeConfig(5);
            var plan = diff(stored, desired);

            var output = formatPlan(plan, stored, desired);

            assertThat(output).contains("Cluster: test-cluster");
            assertThat(output).contains("Current: 3 cores");
            assertThat(output).contains("Desired: 5 cores");
            assertThat(output).contains("~ forge.core  count: 3 -> 5");
        }
    }
}
