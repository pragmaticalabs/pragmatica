package org.pragmatica.aether.config.cluster;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterConfigValidatorTest {

    private static ClusterManagementConfig validConfig() {
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
                Option.none()
            ),
            ClusterSpec.clusterSpec(
                "production",
                "0.21.1",
                CoreSpec.coreSpec(5, 3, 9),
                WorkerSpec.workerSpec(0),
                DistributionConfig.distributionConfig(DistributionStrategy.BALANCED, List.of("zone-a")),
                AutoHealSpec.autoHealSpec(true, "60s", "15s"),
                UpgradeSpec.upgradeSpec(UpgradeStrategy.ROLLING)
            )
        );
    }

    @Nested
    class HappyPath {
        @Test
        void validate_validConfig_success() {
            ClusterConfigValidator.validate(validConfig())
                                  .onFailureRun(Assertions::fail)
                                  .onSuccess(config -> assertThat(config.cluster().name()).isEqualTo("production"));
        }
    }

    @Nested
    class Val01InvalidDeploymentType {
        // DeploymentType is an enum, so invalid types fail at parse time.
        // Validation still checks instance types for non-embedded deployments.
    }

    @Nested
    class Val02InvalidClusterName {
        @Test
        void validate_emptyName_failure() {
            var config = withClusterName("");
            assertValidationContains(config, "Invalid cluster name");
        }

        @Test
        void validate_nameWithUpperCase_failure() {
            var config = withClusterName("Production");
            assertValidationContains(config, "Invalid cluster name");
        }

        @Test
        void validate_nameStartsWithDash_failure() {
            var config = withClusterName("-invalid");
            assertValidationContains(config, "Invalid cluster name");
        }

        @Test
        void validate_nameTooLong_failure() {
            var config = withClusterName("a".repeat(64));
            assertValidationContains(config, "Invalid cluster name");
        }
    }

    @Nested
    class Val03InvalidCoreCount {
        @Test
        void validate_coreCountLessThan3_failure() {
            var config = withCoreSpec(1, 1, 1);
            assertValidationContains(config, "Invalid core count");
        }

        @Test
        void validate_evenCoreCount_failure() {
            var config = withCoreSpec(4, 3, 5);
            assertValidationContains(config, "Invalid core count");
        }
    }

    @Nested
    class Val04InvalidCoreMin {
        @Test
        void validate_coreMinLessThan3_failure() {
            var config = withCoreSpec(5, 1, 9);
            assertValidationContains(config, "Invalid core min");
        }

        @Test
        void validate_coreMinGreaterThanCount_failure() {
            var config = withCoreSpec(5, 7, 9);
            assertValidationContains(config, "Invalid core min");
        }

        @Test
        void validate_evenCoreMin_failure() {
            var config = withCoreSpec(5, 4, 9);
            assertValidationContains(config, "Invalid core min");
        }
    }

    @Nested
    class Val05InvalidCoreMax {
        @Test
        void validate_coreMaxLessThanCount_failure() {
            var config = withCoreSpec(5, 3, 3);
            assertValidationContains(config, "Invalid core max");
        }

        @Test
        void validate_evenCoreMax_failure() {
            var config = withCoreSpec(5, 3, 6);
            assertValidationContains(config, "Invalid core max");
        }
    }

    @Nested
    class Val06InvalidVersion {
        @Test
        void validate_nonSemver_failure() {
            var config = withVersion("latest");
            assertValidationContains(config, "Invalid version");
        }

        @Test
        void validate_incompleteSemver_failure() {
            var config = withVersion("1.0");
            assertValidationContains(config, "Invalid version");
        }
    }

    @Nested
    class Val07MissingInstanceType {
        @Test
        void validate_missingCoreInstance_failure() {
            var config = withInstances(Map.of("worker", "cx11"));
            assertValidationContains(config, "Missing instance type");
        }
    }

    @Nested
    class Val09MissingContainerImage {
        @Test
        void validate_containerWithoutImage_failure() {
            var config = withRuntime(RuntimeType.CONTAINER, Option.none());
            assertValidationContains(config, "Missing container image");
        }

        @Test
        void validate_jvmWithoutImage_success() {
            var config = withRuntime(RuntimeType.JVM, Option.none());
            ClusterConfigValidator.validate(config)
                                  .onFailure(cause -> assertThat(cause.message()).doesNotContain("Missing container image"));
        }
    }

    @Nested
    class Val10InvalidPort {
        @Test
        void validate_portZero_failure() {
            var config = withPorts(0, 5150, 8070, 6100);
            assertValidationContains(config, "Invalid port");
        }

        @Test
        void validate_portTooHigh_failure() {
            var config = withPorts(6000, 70000, 8070, 6100);
            assertValidationContains(config, "Invalid port");
        }
    }

    @Nested
    class Val11UnmappedZone {
        @Test
        void validate_zoneNotInDeployment_failure() {
            var config = withDistributionZones(List.of("zone-a", "zone-missing"));
            assertValidationContains(config, "Unmapped zone");
        }
    }

    @Nested
    class Val13InvalidRetryInterval {
        @Test
        void validate_retryIntervalTooShort_failure() {
            var config = withRetryInterval("2s");
            assertValidationContains(config, "Invalid retry interval");
        }

        @Test
        void validate_invalidFormat_failure() {
            var config = withRetryInterval("abc");
            assertValidationContains(config, "Invalid retry interval");
        }
    }

    @Nested
    class Val14SecretReferences {
        @Test
        void validate_resolvedSecretValue_success() {
            // Secret references are now resolved at string level before parsing.
            // Validator should accept any resolved (plain) value.
            var base = validConfig();
            var config = ClusterManagementConfig.clusterManagementConfig(
                DeploymentSpec.deploymentSpec(
                    base.deployment().type(),
                    base.deployment().instances(),
                    base.deployment().runtime(),
                    base.deployment().zones(),
                    base.deployment().ports(),
                    Option.some(TlsDeploymentConfig.tlsDeploymentConfig(true,
                                                                        Option.some("my-resolved-secret-value"),
                                                                        "720h")),
                    base.deployment().nodes()
                ),
                base.cluster()
            );
            ClusterConfigValidator.validate(config)
                                  .onFailure(cause -> assertThat(cause.message()).doesNotContain("secret"));
        }
    }

    @Nested
    class ValOnPremisesNodes {
        @Test
        void validate_onPremisesWithoutNodes_failure() {
            var config = withDeploymentType(DeploymentType.ON_PREMISES);
            assertValidationContains(config, "requires deployment.nodes.core");
        }

        @Test
        void validate_onPremisesNodeCountMismatch_failure() {
            var config = onPremisesConfig(List.of("10.0.1.1", "10.0.1.2"), 3);
            assertValidationContains(config, "has 2 entries but cluster.core.count is 3");
        }

        @Test
        void validate_onPremisesNodeCountMatches_noNodeError() {
            var config = onPremisesConfig(List.of("10.0.1.1", "10.0.1.2", "10.0.1.3"), 3);
            // Should not contain node-related errors (may fail for other reasons like missing instance type)
            ClusterConfigValidator.validate(config)
                                  .onFailure(cause -> {
                                      assertThat(cause.message()).doesNotContain("requires deployment.nodes.core");
                                      assertThat(cause.message()).doesNotContain("entries but cluster.core.count");
                                  });
        }
    }

    @Nested
    class ValOnPremisesSsh {
        @Test
        void validate_onPremisesWithoutSsh_failure() {
            var config = withDeploymentType(DeploymentType.ON_PREMISES);
            assertValidationContains(config, "requires deployment.ssh");
        }

        @Test
        void validate_onPremisesWithSsh_noSshError() {
            var config = onPremisesConfigWithSsh();
            ClusterConfigValidator.validate(config)
                                  .onFailure(cause -> {
                                      assertThat(cause.message()).doesNotContain("requires deployment.ssh");
                                      assertThat(cause.message()).doesNotContain("key_path must be specified");
                                  });
        }
    }

    // --- Helper methods for building test configs ---

    private static ClusterManagementConfig withClusterName(String name) {
        var base = validConfig();
        return ClusterManagementConfig.clusterManagementConfig(
            base.deployment(),
            ClusterSpec.clusterSpec(name, base.cluster().version(), base.cluster().core(),
                                    base.cluster().workers(), base.cluster().distribution(),
                                    base.cluster().autoHeal(), base.cluster().upgrade())
        );
    }

    private static ClusterManagementConfig withCoreSpec(int count, int min, int max) {
        var base = validConfig();
        return ClusterManagementConfig.clusterManagementConfig(
            base.deployment(),
            ClusterSpec.clusterSpec(base.cluster().name(), base.cluster().version(),
                                    CoreSpec.coreSpec(count, min, max),
                                    base.cluster().workers(), base.cluster().distribution(),
                                    base.cluster().autoHeal(), base.cluster().upgrade())
        );
    }

    private static ClusterManagementConfig withVersion(String version) {
        var base = validConfig();
        return ClusterManagementConfig.clusterManagementConfig(
            base.deployment(),
            ClusterSpec.clusterSpec(base.cluster().name(), version, base.cluster().core(),
                                    base.cluster().workers(), base.cluster().distribution(),
                                    base.cluster().autoHeal(), base.cluster().upgrade())
        );
    }

    private static ClusterManagementConfig withInstances(Map<String, String> instances) {
        var base = validConfig();
        return ClusterManagementConfig.clusterManagementConfig(
            DeploymentSpec.deploymentSpec(base.deployment().type(), instances,
                                          base.deployment().runtime(), base.deployment().zones(),
                                          base.deployment().ports(), base.deployment().tls(),
                                          base.deployment().nodes()),
            base.cluster()
        );
    }

    private static ClusterManagementConfig withRuntime(RuntimeType type, Option<String> image) {
        var base = validConfig();
        return ClusterManagementConfig.clusterManagementConfig(
            DeploymentSpec.deploymentSpec(base.deployment().type(), base.deployment().instances(),
                                          RuntimeConfig.runtimeConfig(type, image, Option.none()),
                                          base.deployment().zones(), base.deployment().ports(),
                                          base.deployment().tls(), base.deployment().nodes()),
            base.cluster()
        );
    }

    private static ClusterManagementConfig withPorts(int cluster, int management, int appHttp, int swim) {
        var base = validConfig();
        return ClusterManagementConfig.clusterManagementConfig(
            DeploymentSpec.deploymentSpec(base.deployment().type(), base.deployment().instances(),
                                          base.deployment().runtime(), base.deployment().zones(),
                                          PortMapping.portMapping(cluster, management, appHttp, swim),
                                          base.deployment().tls(), base.deployment().nodes()),
            base.cluster()
        );
    }

    private static ClusterManagementConfig withDistributionZones(List<String> zones) {
        var base = validConfig();
        return ClusterManagementConfig.clusterManagementConfig(
            base.deployment(),
            ClusterSpec.clusterSpec(base.cluster().name(), base.cluster().version(),
                                    base.cluster().core(), base.cluster().workers(),
                                    DistributionConfig.distributionConfig(DistributionStrategy.BALANCED, zones),
                                    base.cluster().autoHeal(), base.cluster().upgrade())
        );
    }

    private static ClusterManagementConfig withRetryInterval(String interval) {
        var base = validConfig();
        return ClusterManagementConfig.clusterManagementConfig(
            base.deployment(),
            ClusterSpec.clusterSpec(base.cluster().name(), base.cluster().version(),
                                    base.cluster().core(), base.cluster().workers(),
                                    base.cluster().distribution(),
                                    AutoHealSpec.autoHealSpec(true, interval, "15s"),
                                    base.cluster().upgrade())
        );
    }

    private static ClusterManagementConfig withDeploymentType(DeploymentType type) {
        var base = validConfig();
        return ClusterManagementConfig.clusterManagementConfig(
            DeploymentSpec.deploymentSpec(type, base.deployment().instances(),
                                          base.deployment().runtime(), base.deployment().zones(),
                                          base.deployment().ports(), base.deployment().tls(),
                                          base.deployment().nodes()),
            base.cluster()
        );
    }

    private static ClusterManagementConfig onPremisesConfig(List<String> nodeIps, int coreCount) {
        return ClusterManagementConfig.clusterManagementConfig(
            DeploymentSpec.deploymentSpec(
                DeploymentType.ON_PREMISES,
                Map.of("core", "bare-metal"),
                RuntimeConfig.runtimeConfig(RuntimeType.JVM, Option.none(), Option.none()),
                Map.of(),
                PortMapping.portMapping(6000, 5150, 8070, 6100),
                Option.none(),
                Option.some(Map.of("core", String.join(", ", nodeIps))),
                Option.some(SshConfig.sshConfig("aether", "~/.ssh/id_ed25519", 22))
            ),
            ClusterSpec.clusterSpec(
                "onprem-test",
                "0.21.1",
                CoreSpec.coreSpec(coreCount, 3, coreCount > 3 ? coreCount : 3),
                WorkerSpec.workerSpec(0),
                DistributionConfig.distributionConfig(DistributionStrategy.BALANCED, List.of()),
                AutoHealSpec.autoHealSpec(true, "60s", "15s"),
                UpgradeSpec.upgradeSpec(UpgradeStrategy.ROLLING)
            )
        );
    }

    private static ClusterManagementConfig onPremisesConfigWithSsh() {
        return ClusterManagementConfig.clusterManagementConfig(
            DeploymentSpec.deploymentSpec(
                DeploymentType.ON_PREMISES,
                Map.of("core", "bare-metal"),
                RuntimeConfig.runtimeConfig(RuntimeType.JVM, Option.none(), Option.none()),
                Map.of(),
                PortMapping.portMapping(6000, 5150, 8070, 6100),
                Option.none(),
                Option.some(Map.of("core", "10.0.1.1, 10.0.1.2, 10.0.1.3, 10.0.1.4, 10.0.1.5")),
                Option.some(SshConfig.sshConfig("aether", "~/.ssh/id_ed25519", 22))
            ),
            ClusterSpec.clusterSpec(
                "onprem-test",
                "0.21.1",
                CoreSpec.coreSpec(5, 3, 9),
                WorkerSpec.workerSpec(0),
                DistributionConfig.distributionConfig(DistributionStrategy.BALANCED, List.of()),
                AutoHealSpec.autoHealSpec(true, "60s", "15s"),
                UpgradeSpec.upgradeSpec(UpgradeStrategy.ROLLING)
            )
        );
    }

    private static void assertValidationContains(ClusterManagementConfig config, String expectedSubstring) {
        ClusterConfigValidator.validate(config)
                              .onSuccess(_ -> Assertions.fail("Expected validation failure"))
                              .onFailure(cause -> assertThat(cause.message()).contains(expectedSubstring));
    }
}
