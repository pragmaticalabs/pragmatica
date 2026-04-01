package org.pragmatica.aether.config.cluster;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.ClusterConfigDiff.ConfigChange;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterConfigDiffTest {

    private static ClusterManagementConfig baseConfig() {
        return ClusterManagementConfig.clusterManagementConfig(
                DeploymentSpec.deploymentSpec(
                        DeploymentType.HETZNER,
                        Map.of("core", "cx21"),
                        RuntimeConfig.runtimeConfig(RuntimeType.CONTAINER,
                                                    Option.some("ghcr.io/test:0.21.1"),
                                                    Option.none()),
                        Map.of("zone-a", "fsn1-dc14"),
                        PortMapping.portMapping(6000, 5150, 8070, 6100),
                        Option.some(TlsDeploymentConfig.tlsDeploymentConfig(true,
                                                                            Option.some("${secrets:cluster-secret}"),
                                                                            "720h")),
                        Option.none()),
                ClusterSpec.clusterSpec(
                        "production",
                        "0.21.1",
                        CoreSpec.coreSpec(5, 3, 9),
                        WorkerSpec.workerSpec(0),
                        DistributionConfig.distributionConfig(DistributionStrategy.BALANCED, List.of("zone-a")),
                        AutoHealSpec.autoHealSpec(true, "60s", "15s"),
                        UpgradeSpec.upgradeSpec(UpgradeStrategy.ROLLING)));
    }

    private static ClusterManagementConfig withCoreCount(ClusterManagementConfig config, int count) {
        var cluster = config.cluster();
        var newCore = CoreSpec.coreSpec(count, cluster.core().min(), cluster.core().max());
        var newCluster = ClusterSpec.clusterSpec(cluster.name(), cluster.version(), newCore,
                                                 cluster.workers(), cluster.distribution(),
                                                 cluster.autoHeal(), cluster.upgrade());
        return ClusterManagementConfig.clusterManagementConfig(config.deployment(), newCluster);
    }

    private static ClusterManagementConfig withVersion(ClusterManagementConfig config, String version) {
        var cluster = config.cluster();
        var newCluster = ClusterSpec.clusterSpec(cluster.name(), version, cluster.core(),
                                                 cluster.workers(), cluster.distribution(),
                                                 cluster.autoHeal(), cluster.upgrade());
        return ClusterManagementConfig.clusterManagementConfig(config.deployment(), newCluster);
    }

    private static ClusterManagementConfig withClusterName(ClusterManagementConfig config, String name) {
        var cluster = config.cluster();
        var newCluster = ClusterSpec.clusterSpec(name, cluster.version(), cluster.core(),
                                                 cluster.workers(), cluster.distribution(),
                                                 cluster.autoHeal(), cluster.upgrade());
        return ClusterManagementConfig.clusterManagementConfig(config.deployment(), newCluster);
    }

    private static ClusterManagementConfig withDeploymentType(ClusterManagementConfig config, DeploymentType type) {
        var deployment = config.deployment();
        var newDeployment = DeploymentSpec.deploymentSpec(type, deployment.instances(), deployment.runtime(),
                                                         deployment.zones(), deployment.ports(),
                                                         deployment.tls(), deployment.nodes());
        return ClusterManagementConfig.clusterManagementConfig(newDeployment, config.cluster());
    }

    private static ClusterManagementConfig withAutoHeal(ClusterManagementConfig config, AutoHealSpec autoHeal) {
        var cluster = config.cluster();
        var newCluster = ClusterSpec.clusterSpec(cluster.name(), cluster.version(), cluster.core(),
                                                 cluster.workers(), cluster.distribution(),
                                                 autoHeal, cluster.upgrade());
        return ClusterManagementConfig.clusterManagementConfig(config.deployment(), newCluster);
    }

    private static ClusterManagementConfig withCoreMin(ClusterManagementConfig config, int min) {
        var cluster = config.cluster();
        var newCore = CoreSpec.coreSpec(cluster.core().count(), min, cluster.core().max());
        var newCluster = ClusterSpec.clusterSpec(cluster.name(), cluster.version(), newCore,
                                                 cluster.workers(), cluster.distribution(),
                                                 cluster.autoHeal(), cluster.upgrade());
        return ClusterManagementConfig.clusterManagementConfig(config.deployment(), newCluster);
    }

    private static ClusterManagementConfig withCoreMax(ClusterManagementConfig config, int max) {
        var cluster = config.cluster();
        var newCore = CoreSpec.coreSpec(cluster.core().count(), cluster.core().min(), max);
        var newCluster = ClusterSpec.clusterSpec(cluster.name(), cluster.version(), newCore,
                                                 cluster.workers(), cluster.distribution(),
                                                 cluster.autoHeal(), cluster.upgrade());
        return ClusterManagementConfig.clusterManagementConfig(config.deployment(), newCluster);
    }

    @Nested
    class EqualConfigs {
        @Test
        void diff_identicalConfigs_emptyDiff() {
            var config = baseConfig();
            var diff = ClusterConfigDiff.diff(config, config);
            assertThat(diff.isEmpty()).isTrue();
            assertThat(diff.changes()).isEmpty();
            assertThat(diff.hasImmutableChanges()).isFalse();
        }
    }

    @Nested
    class MutableChanges {
        @Test
        void diff_changedCoreCount_producesScaleCoreChange() {
            var stored = baseConfig();
            var desired = withCoreCount(stored, 7);
            var diff = ClusterConfigDiff.diff(stored, desired);

            assertThat(diff.isEmpty()).isFalse();
            assertThat(diff.hasImmutableChanges()).isFalse();
            assertThat(diff.actionableChanges()).hasSize(1);

            var change = diff.actionableChanges().getFirst();
            assertThat(change).isInstanceOf(ConfigChange.ScaleCore.class);

            var scale = (ConfigChange.ScaleCore) change;
            assertThat(scale.from()).isEqualTo(5);
            assertThat(scale.to()).isEqualTo(7);
        }

        @Test
        void diff_changedVersion_producesUpdateVersionChange() {
            var stored = baseConfig();
            var desired = withVersion(stored, "0.22.0");
            var diff = ClusterConfigDiff.diff(stored, desired);

            assertThat(diff.actionableChanges()).hasSize(1);
            assertThat(diff.actionableChanges().getFirst()).isInstanceOf(ConfigChange.UpdateVersion.class);
        }

        @Test
        void diff_changedAutoHeal_producesUpdateAutoHealChange() {
            var stored = baseConfig();
            var desired = withAutoHeal(stored, AutoHealSpec.autoHealSpec(true, "30s", "15s"));
            var diff = ClusterConfigDiff.diff(stored, desired);

            assertThat(diff.actionableChanges()).hasSize(1);
            assertThat(diff.actionableChanges().getFirst()).isInstanceOf(ConfigChange.UpdateAutoHeal.class);
        }

        @Test
        void diff_changedCoreMin_producesUpdateCoreMinChange() {
            var stored = baseConfig();
            var desired = withCoreMin(stored, 5);
            var diff = ClusterConfigDiff.diff(stored, desired);

            assertThat(diff.actionableChanges()).hasSize(1);
            var change = (ConfigChange.UpdateCoreMin) diff.actionableChanges().getFirst();
            assertThat(change.from()).isEqualTo(3);
            assertThat(change.to()).isEqualTo(5);
        }

        @Test
        void diff_changedCoreMax_producesUpdateCoreMaxChange() {
            var stored = baseConfig();
            var desired = withCoreMax(stored, 11);
            var diff = ClusterConfigDiff.diff(stored, desired);

            assertThat(diff.actionableChanges()).hasSize(1);
            var change = (ConfigChange.UpdateCoreMax) diff.actionableChanges().getFirst();
            assertThat(change.from()).isEqualTo(9);
            assertThat(change.to()).isEqualTo(11);
        }
    }

    @Nested
    class ImmutableChanges {
        @Test
        void diff_changedDeploymentType_producesImmutableChange() {
            var stored = baseConfig();
            var desired = withDeploymentType(stored, DeploymentType.AWS);
            var diff = ClusterConfigDiff.diff(stored, desired);

            assertThat(diff.hasImmutableChanges()).isTrue();
            assertThat(diff.immutableChanges()).hasSize(1);
            var change = (ConfigChange.ImmutableChange) diff.immutableChanges().getFirst();
            assertThat(change.field()).isEqualTo("deployment.type");
        }

        @Test
        void diff_changedClusterName_producesImmutableChange() {
            var stored = baseConfig();
            var desired = withClusterName(stored, "staging");
            var diff = ClusterConfigDiff.diff(stored, desired);

            assertThat(diff.hasImmutableChanges()).isTrue();
            var change = (ConfigChange.ImmutableChange) diff.immutableChanges().getFirst();
            assertThat(change.field()).isEqualTo("cluster.name");
        }

        @Test
        void diff_immutableChange_validateAndExtractActions_returnsFailure() {
            var stored = baseConfig();
            var desired = withDeploymentType(stored, DeploymentType.AWS);
            var diff = ClusterConfigDiff.diff(stored, desired);

            diff.validateAndExtractActions()
                .onSuccess(_ -> Assertions.fail("Expected failure for immutable change"));
        }
    }

    @Nested
    class MultipleChanges {
        @Test
        void diff_multipleChanges_allDetected() {
            var stored = baseConfig();
            var desired = withVersion(withCoreCount(withAutoHeal(stored,
                                                                 AutoHealSpec.autoHealSpec(false, "30s", "10s")),
                                                    7),
                                      "0.22.0");
            var diff = ClusterConfigDiff.diff(stored, desired);

            assertThat(diff.isEmpty()).isFalse();
            assertThat(diff.hasImmutableChanges()).isFalse();
            assertThat(diff.actionableChanges()).hasSize(3);

            var changeTypes = diff.actionableChanges()
                                  .stream()
                                  .map(Object::getClass)
                                  .map(Class::getSimpleName)
                                  .toList();
            assertThat(changeTypes).contains("ScaleCore", "UpdateAutoHeal", "UpdateVersion");
        }

        @Test
        void diff_mutableAndImmutable_bothDetected() {
            var stored = baseConfig();
            var desired = withDeploymentType(withCoreCount(stored, 7), DeploymentType.AWS);
            var diff = ClusterConfigDiff.diff(stored, desired);

            assertThat(diff.hasImmutableChanges()).isTrue();
            assertThat(diff.immutableChanges()).hasSize(1);
            assertThat(diff.actionableChanges()).hasSize(1);
        }
    }

    @Nested
    class ValidationIntegration {
        @Test
        void validateAndExtractActions_noImmutableChanges_returnsActionable() {
            var stored = baseConfig();
            var desired = withCoreCount(stored, 7);
            var diff = ClusterConfigDiff.diff(stored, desired);

            diff.validateAndExtractActions()
                .onFailureRun(Assertions::fail)
                .onSuccess(actions -> {
                    assertThat(actions).hasSize(1);
                    assertThat(actions.getFirst()).isInstanceOf(ConfigChange.ScaleCore.class);
                });
        }

        @Test
        void validateAndExtractActions_emptyDiff_returnsEmptyList() {
            var config = baseConfig();
            var diff = ClusterConfigDiff.diff(config, config);

            diff.validateAndExtractActions()
                .onFailureRun(Assertions::fail)
                .onSuccess(actions -> assertThat(actions).isEmpty());
        }
    }
}
