// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.config.cluster;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.config.cluster.ClusterBootstrapConfig.clusterBootstrapConfig;
import static org.pragmatica.aether.config.cluster.ClusterBootstrapConfigValidator.validate;
import static org.pragmatica.aether.config.cluster.ClusterBootstrapConfigValidator.warnings;
import static org.pragmatica.aether.config.cluster.ClusterIdentity.clusterIdentity;
import static org.pragmatica.aether.config.cluster.CoreTopology.coreTopology;
import static org.pragmatica.aether.config.cluster.CoreTopology.defaultCoreTopology;
import static org.pragmatica.aether.config.cluster.FirewallRule.firewallRule;
import static org.pragmatica.aether.config.cluster.InfrastructureConfig.infrastructureConfig;
import static org.pragmatica.aether.config.cluster.OperationsConfig.defaultOperationsConfig;
import static org.pragmatica.aether.config.cluster.OperationsConfig.operationsConfig;
import static org.pragmatica.aether.config.cluster.PortMapping.portMapping;
import static org.pragmatica.aether.config.cluster.RoleSubTable.roleSubTable;
import static org.pragmatica.aether.config.cluster.RuntimeProfile.runtimeProfile;
import static org.pragmatica.aether.config.cluster.SourceProfile.sourceProfile;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


class ClusterBootstrapConfigValidatorTest {

    private static ClusterBootstrapConfig validForgeConfig() {
        var coreRole = roleSubTable(NodeRole.CORE, some(3), none(), none(), "ember");
        var source = sourceProfile(sourceNameOrDefault("local"), SourceType.FORGE, none(), none(), none(), none(),
                                   none(), none(), none(), LoadBalancerMode.ELECTED, List.of(),
                                   none(), Map.of(), Map.of(NodeRole.CORE, coreRole), List.of());

        return clusterBootstrapConfig("1.0.0", clusterIdentity("dev-local", "1.0.0").unwrap(),
                                      defaultCoreTopology(), Map.of("local", source), Map.of(),
                                      infrastructureConfig(NetworkingType.MANUAL), defaultOperationsConfig());
    }

    private static ClusterBootstrapConfig validCloudConfig() {
        var runtime = runtimeProfile("prod", RuntimeType.CONTAINER, some("aether:latest"), none());
        var coreRole = roleSubTable(NodeRole.CORE, some(3), none(), some("cx41"), "prod");
        var workerRole = roleSubTable(NodeRole.WORKER, some(2), none(), some("cx31"), "prod");
        var source = sourceProfile(sourceNameOrDefault("hetzner-eu"), SourceType.CLOUD, some(CloudProviderName.HETZNER),
                                   some("key"), some("eu-central"), none(), none(), none(), none(),
                                   LoadBalancerMode.EXTERNAL, List.of("10.0.0.1"), none(), Map.of(),
                                   Map.of(NodeRole.CORE, coreRole, NodeRole.WORKER, workerRole), List.of());

        return clusterBootstrapConfig("1.0.0", clusterIdentity("production", "1.0.0").unwrap(),
                                      defaultCoreTopology(), Map.of("hetzner-eu", source),
                                      Map.of("prod", runtime),
                                      infrastructureConfig(NetworkingType.MANUAL), defaultOperationsConfig());
    }

    private static ClusterBootstrapConfig cloudConfigWithFirewall(CloudProviderName provider) {
        var runtime = runtimeProfile("prod", RuntimeType.CONTAINER, some("aether:latest"), none());
        var coreRole = roleSubTable(NodeRole.CORE, some(3), none(), some("cx41"), "prod");
        var rules = List.of(firewallRule(8070, "tcp", "0.0.0.0/0", none()));
        var source = sourceProfile(sourceNameOrDefault("cloud-src"), SourceType.CLOUD, some(provider),
                                   some("key"), some("eu-central"), none(), none(), none(), none(),
                                   LoadBalancerMode.EXTERNAL, List.of("10.0.0.1"), none(), Map.of(),
                                   Map.of(NodeRole.CORE, coreRole), rules);

        return clusterBootstrapConfig("1.0.0", clusterIdentity("production", "1.0.0").unwrap(),
                                      defaultCoreTopology(), Map.of("cloud-src", source),
                                      Map.of("prod", runtime),
                                      infrastructureConfig(NetworkingType.MANUAL), defaultOperationsConfig());
    }

    private static ClusterBootstrapConfig cloudConfigWithManagement(String securityMode, String cidr) {
        var runtime = runtimeProfile("prod", RuntimeType.CONTAINER, some("aether:latest"), none());
        var coreRole = roleSubTable(NodeRole.CORE, some(3), none(), some("cx41"), "prod");
        var rules = List.of(firewallRule(22, "tcp", "10.0.0.0/8", none()),
                            firewallRule(8080, "tcp", cidr, none()));
        var overlay = new TomlDocument(Map.of("app-http", Map.of("security_mode", securityMode)));
        var source = sourceProfile(sourceNameOrDefault("cloud-src"), SourceType.CLOUD, some(CloudProviderName.HETZNER),
                                   some("key"), some("eu-central"), none(), none(), none(), none(),
                                   LoadBalancerMode.EXTERNAL, List.of("10.0.0.1"), none(), Map.of(),
                                   Map.of(NodeRole.CORE, coreRole), rules, some(overlay));

        return clusterBootstrapConfig("1.0.0", clusterIdentity("production", "1.0.0").unwrap(),
                                      defaultCoreTopology(), Map.of("cloud-src", source),
                                      Map.of("prod", runtime),
                                      infrastructureConfig(NetworkingType.MANUAL), defaultOperationsConfig());
    }

    private static ClusterBootstrapConfig cloudConfigWithPublicManagement(String securityMode) {
        return cloudConfigWithManagement(securityMode, "0.0.0.0/0");
    }

    private static ClusterBootstrapConfig cloudConfigWithScopedManagement(String securityMode) {
        return cloudConfigWithManagement(securityMode, "203.0.113.0/24");
    }

    private static ClusterBootstrapConfig cloudConfigWithSpot(CloudProviderName provider) {
        var runtime = runtimeProfile("prod", RuntimeType.CONTAINER, some("aether:latest"), none());
        var coreRole = roleSubTable(NodeRole.CORE, some(3), none(), some("cx41"), "prod");
        var spotRole = roleSubTable(NodeRole.SPOT, some(2), none(), some("cx31"), "prod");
        var source = sourceProfile(sourceNameOrDefault("cloud-src"), SourceType.CLOUD, some(provider),
                                   some("key"), some("eu-central"), none(), none(), none(), none(),
                                   LoadBalancerMode.EXTERNAL, List.of("10.0.0.1"), none(), Map.of(),
                                   Map.of(NodeRole.CORE, coreRole, NodeRole.SPOT, spotRole), List.of());

        return clusterBootstrapConfig("1.0.0", clusterIdentity("production", "1.0.0").unwrap(),
                                      defaultCoreTopology(), Map.of("cloud-src", source),
                                      Map.of("prod", runtime),
                                      infrastructureConfig(NetworkingType.MANUAL), defaultOperationsConfig());
    }

    @Nested
    class HappyPath {

        @Test
        void validate_validForgeConfig_succeeds() {
            validate(validForgeConfig())
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(config -> assertThat(config.cluster().name().value()).isEqualTo("dev-local"));
        }

        @Test
        void validate_validCloudConfig_succeeds() {
            validate(validCloudConfig())
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(config -> assertThat(config.cluster().name().value()).isEqualTo("production"));
        }
    }

    @Nested
    class ClusterLevel {

        @Test
        void clusterIdentity_invalidClusterName_returnsFailure() {
            // CL-01 is now enforced at construction (parse-don't-validate); the factory rejects
            // invalid names before they can reach the validator.
            clusterIdentity("INVALID_NAME!", "1.0.0")
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("INVALID_NAME!"));
        }

        @Test
        void validate_invalidVersion_returnsError() {
            validate(configWithVersion("not-semver"))
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("CL-02"));
        }

        // #585: CL-02 rejected valid semver pre-release/build-metadata versions, including the
        // project's own "1.0.0-rc3"/"1.0.0-rc4" strings — an operator pasting the real Aether
        // version into `[cluster] version` got refused at bootstrap VALIDATE.
        @Test
        void validate_semverPreReleaseVersion_succeeds() {
            validate(configWithVersion("1.0.0-rc3"))
                .onFailure(cause -> Assertions.fail(cause.message()));
            validate(configWithVersion("1.0.0-rc4"))
                .onFailure(cause -> Assertions.fail(cause.message()));
        }

        @Test
        void validate_semverPreReleaseWithBuildMetadata_succeeds() {
            validate(configWithVersion("1.0.0-rc4+meta"))
                .onFailure(cause -> Assertions.fail(cause.message()));
        }

        @Test
        void validate_semverDottedPreReleaseIdentifiers_succeeds() {
            validate(configWithVersion("1.0.0-alpha.1"))
                .onFailure(cause -> Assertions.fail(cause.message()));
        }

        @Test
        void validate_semverTrailingDashNoIdentifier_returnsError() {
            validate(configWithVersion("1.0.0-"))
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("CL-02"));
        }

        @Test
        void validate_semverLeadingZeroInPreReleaseNumericIdentifier_returnsError() {
            validate(configWithVersion("1.0.0-01"))
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("CL-02"));
        }

        @Test
        void validate_semverInvalidCharacterInPreRelease_returnsError() {
            validate(configWithVersion("1.0.0-rc_4"))
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("CL-02"));
        }

        private static ClusterBootstrapConfig configWithVersion(String version) {
            return clusterBootstrapConfig("1.0.0", clusterIdentity("test", version).unwrap(),
                                          defaultCoreTopology(), validForgeConfig().sources(),
                                          Map.of(), infrastructureConfig(NetworkingType.MANUAL),
                                          defaultOperationsConfig());
        }

        @Test
        void validate_evenCoreCount_returnsError() {
            var coreRole = roleSubTable(NodeRole.CORE, some(4), none(), none(), "ember");
            var source = sourceProfile(sourceNameOrDefault("local"), SourceType.FORGE, none(), none(), none(), none(),
                                       none(), none(), none(), LoadBalancerMode.ELECTED, List.of(),
                                       none(), Map.of(), Map.of(NodeRole.CORE, coreRole), List.of());
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("test", "1.0.0").unwrap(),
                                                defaultCoreTopology(), Map.of("local", source), Map.of(),
                                                infrastructureConfig(NetworkingType.MANUAL),
                                                defaultOperationsConfig());
            validate(config)
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("CL-04").contains("odd"));
        }

        @Test
        void validate_coreCountTooSmall_returnsError() {
            var coreRole = roleSubTable(NodeRole.CORE, some(1), none(), none(), "ember");
            var source = sourceProfile(sourceNameOrDefault("local"), SourceType.FORGE, none(), none(), none(), none(),
                                       none(), none(), none(), LoadBalancerMode.ELECTED, List.of(),
                                       none(), Map.of(), Map.of(NodeRole.CORE, coreRole), List.of());
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("test", "1.0.0").unwrap(),
                                                defaultCoreTopology(), Map.of("local", source), Map.of(),
                                                infrastructureConfig(NetworkingType.MANUAL),
                                                defaultOperationsConfig());
            validate(config)
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("CL-04").contains(">= 3"));
        }

        @Test
        void validate_noCoreSubTable_returnsError() {
            var workerRole = roleSubTable(NodeRole.WORKER, some(3), none(), none(), "ember");
            var source = sourceProfile(sourceNameOrDefault("local"), SourceType.FORGE, none(), none(), none(), none(),
                                       none(), none(), none(), LoadBalancerMode.ELECTED, List.of(),
                                       none(), Map.of(), Map.of(NodeRole.WORKER, workerRole), List.of());
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("test", "1.0.0").unwrap(),
                                                defaultCoreTopology(), Map.of("local", source), Map.of(),
                                                infrastructureConfig(NetworkingType.MANUAL),
                                                defaultOperationsConfig());
            validate(config)
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("CL-07"));
        }

        @Test
        void validate_portsNotDistinct_returnsError() {
            var ports = portMapping(8080, 8080, 8070, 8190);
            var ops = operationsConfig(defaultOperationsConfig().autoHeal(), defaultOperationsConfig().tls(),
                                       defaultOperationsConfig().timeouts(), ports);
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("test", "1.0.0").unwrap(),
                                                defaultCoreTopology(), validForgeConfig().sources(),
                                                Map.of(), infrastructureConfig(NetworkingType.MANUAL), ops);
            validate(config)
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("CL-11").contains("conflicts"));
        }

        @Test
        void validate_portOutOfRange_returnsError() {
            var ports = portMapping(0, 8080, 8070, 8190);
            var ops = operationsConfig(defaultOperationsConfig().autoHeal(), defaultOperationsConfig().tls(),
                                       defaultOperationsConfig().timeouts(), ports);
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("test", "1.0.0").unwrap(),
                                                defaultCoreTopology(), validForgeConfig().sources(),
                                                Map.of(), infrastructureConfig(NetworkingType.MANUAL), ops);
            validate(config)
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("CL-11").contains("out of range"));
        }

        @Test
        void validate_autoHealDisabled_returnsPf25() {
            // Positive control for enabled=true already exists: HappyPath.validate_validForgeConfig_succeeds
            // uses defaultOperationsConfig(), which defaults autoHeal to enabled=true and must not trip PF-25.
            var autoHeal = AutoHealSpec.autoHealSpec(false, "60s", "15s");
            var ops = operationsConfig(autoHeal, defaultOperationsConfig().tls(),
                                       defaultOperationsConfig().timeouts(), defaultOperationsConfig().ports());
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("test", "1.0.0").unwrap(),
                                                defaultCoreTopology(), validForgeConfig().sources(),
                                                Map.of(), infrastructureConfig(NetworkingType.MANUAL), ops);
            validate(config)
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("PF-25").contains("auto-heal disable"));
        }
    }

    @Nested
    class CoreTopologyChecks {

        @Test
        void validate_maxUnavailableTooHigh_returnsError() {
            var topology = coreTopology(none(), none(), 3);
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("test", "1.0.0").unwrap(),
                                                topology, validForgeConfig().sources(), Map.of(),
                                                infrastructureConfig(NetworkingType.MANUAL),
                                                defaultOperationsConfig());
            validate(config)
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("REQ-3.3.7"));
        }
    }

    @Nested
    class PerSource {

        @Test
        void validate_spotOnSshSource_returnsError() {
            var coreRole = roleSubTable(NodeRole.CORE, none(), some(List.of("h1", "h2", "h3")), none(), "ember");
            var spotRole = roleSubTable(NodeRole.SPOT, none(), some(List.of("h4")), none(), "ember");
            var source = sourceProfile(sourceNameOrDefault("ssh-src"), SourceType.SSH, none(), none(), none(), none(),
                                       some("root"), some("/key"), some(22), LoadBalancerMode.NONE, List.of(),
                                       none(), Map.of(),
                                       Map.of(NodeRole.CORE, coreRole, NodeRole.SPOT, spotRole), List.of());
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("test", "1.0.0").unwrap(),
                                                defaultCoreTopology(), Map.of("ssh-src", source), Map.of(),
                                                infrastructureConfig(NetworkingType.MANUAL),
                                                defaultOperationsConfig());
            validate(config)
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("PF-15"));
        }

        @Test
        void validate_spotOnForgeSource_returnsError() {
            var coreRole = roleSubTable(NodeRole.CORE, some(3), none(), none(), "ember");
            var spotRole = roleSubTable(NodeRole.SPOT, some(1), none(), none(), "ember");
            var source = sourceProfile(sourceNameOrDefault("local"), SourceType.FORGE, none(), none(), none(), none(),
                                       none(), none(), none(), LoadBalancerMode.ELECTED, List.of(),
                                       none(), Map.of(),
                                       Map.of(NodeRole.CORE, coreRole, NodeRole.SPOT, spotRole), List.of());
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("test", "1.0.0").unwrap(),
                                                defaultCoreTopology(), Map.of("local", source), Map.of(),
                                                infrastructureConfig(NetworkingType.MANUAL),
                                                defaultOperationsConfig());
            validate(config)
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("PF-15"));
        }

        @Test
        void validate_spotOnAwsSource_succeeds() {
            // W10: AWS has a real spot arm (createFrom attaches EC2 InstanceMarketOptions), so a
            // [source.aws.spot] sub-table is the one provider allowed to carry spot today.
            validate(cloudConfigWithSpot(CloudProviderName.AWS))
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(config -> assertThat(config.cluster().name().value()).isEqualTo("production"));
        }

        @Test
        void validate_spotOnHetznerSource_returnsPf16() {
            validate(cloudConfigWithSpot(CloudProviderName.HETZNER))
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("PF-16")
                                                              .contains("hetzner")
                                                              .contains("does not support spot"));
        }

        @Test
        void validate_spotOnGcpSource_returnsPf16() {
            validate(cloudConfigWithSpot(CloudProviderName.GCP))
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("PF-16")
                                                              .contains("gcp")
                                                              .contains("provisioningModel=SPOT"));
        }

        @Test
        void validate_spotOnAzureSource_returnsPf16() {
            validate(cloudConfigWithSpot(CloudProviderName.AZURE))
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("PF-16")
                                                              .contains("azure")
                                                              .contains("priority=Spot"));
        }

        /// #574 — `allow_ingress` on a provider with no ingress arm used to parse, validate and diff
        /// cleanly while never being applied. On GCP/Azure that fails CLOSED (their default rules deny
        /// inbound), so rejecting is about honesty rather than exposure; Hetzner, where the same gap
        /// fails OPEN, is why the rejection exists at all.
        ///
        /// **AWS is no longer in that set** (#463): its `openIngress` landed, so `allow_ingress` on an
        /// AWS source is now honoured — security groups are created, tagged `(aether-cluster,
        /// aether-source)`, attached at instance-create and reclaimed by `cluster destroy`. Rejecting it
        /// would refuse a configuration the runtime now implements.
        @Test
        void validate_allowIngressOnAwsSource_isAccepted_sinceAwsManagesIngress() {
            validate(cloudConfigWithFirewall(CloudProviderName.AWS))
                .onFailure(cause -> Assertions.fail("AWS ingress is implemented; PF-23 must not reject it: "
                                                    + cause.message()));
        }

        @Test
        void validate_allowIngressOnGcpSource_returnsPf19() {
            validate(cloudConfigWithFirewall(CloudProviderName.GCP))
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("PF-23").contains("gcp"));
        }

        @Test
        void validate_allowIngressOnAzureSource_returnsPf19() {
            validate(cloudConfigWithFirewall(CloudProviderName.AZURE))
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("PF-23").contains("azure"));
        }

        /// An SSH host's firewall is the operator's; Aether has no API to manage it, so the block
        /// would be silently inert. Refuse rather than pretend.
        @Test
        void validate_allowIngressOnSshSource_returnsPf23() {
            var coreRole = roleSubTable(NodeRole.CORE, none(), some(List.of("h1", "h2", "h3")), none(), "ember");
            var rules = List.of(firewallRule(8070, "tcp", "0.0.0.0/0", none()));
            var source = sourceProfile(sourceNameOrDefault("ssh-src"), SourceType.SSH, none(), none(), none(), none(),
                                       some("root"), some("/key"), some(22), LoadBalancerMode.NONE,
                                       List.of(), none(), Map.of(),
                                       Map.of(NodeRole.CORE, coreRole), rules);
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("production", "1.0.0").unwrap(),
                                                defaultCoreTopology(), Map.of("ssh-src", source), Map.of(),
                                                infrastructureConfig(NetworkingType.MANUAL), defaultOperationsConfig());

            validate(config)
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("PF-23")
                                                              .contains("no cloud ingress API"));
        }

        /// Live 2026-08-05: three nodes provisioned fine, then DEPLOY_RUNTIME died with
        /// "SSH preflight failed: 3 host(s) unreachable after 300s" — because the firewall was
        /// working and port 22 was not declared. Warn before the operator burns 5 minutes and 3 VMs.
        @Test
        void warnings_firewallWithoutSshPort_warnsAboutDeployLockout() {
            assertThat(warnings(cloudConfigWithFirewall(CloudProviderName.HETZNER)))
                .anySatisfy(warning -> assertThat(warning).contains("port 22")
                                                          .contains("DEPLOY_RUNTIME"));
        }

        /// The readiness gate polls the management API on the node's PUBLIC address, so a firewall
        /// that omits it fails bootstrap on nodes that booted perfectly (live 2026-08-05).
        @Test
        void warnings_firewallWithoutManagementPort_warnsAboutReadinessGate() {
            assertThat(warnings(cloudConfigWithFirewall(CloudProviderName.HETZNER)))
                .anySatisfy(warning -> assertThat(warning).contains("8080")
                                                          .contains("management API"));
        }

        @Test
        void warnings_firewallWithBootstrapPorts_doesNotWarn() {
            var runtime = runtimeProfile("prod", RuntimeType.CONTAINER, some("aether:latest"), none());
            var coreRole = roleSubTable(NodeRole.CORE, some(3), none(), some("cx41"), "prod");
            var rules = List.of(firewallRule(22, "tcp", "10.0.0.0/8", none()),
                                firewallRule(8080, "tcp", "10.0.0.0/8", none()),
                                firewallRule(8070, "tcp", "0.0.0.0/0", none()));
            var source = sourceProfile(sourceNameOrDefault("cloud-src"), SourceType.CLOUD, some(CloudProviderName.HETZNER),
                                       some("key"), some("eu-central"), none(), none(), none(), none(),
                                       LoadBalancerMode.EXTERNAL, List.of("10.0.0.1"), none(), Map.of(),
                                       Map.of(NodeRole.CORE, coreRole), rules);
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("production", "1.0.0").unwrap(),
                                                defaultCoreTopology(), Map.of("cloud-src", source),
                                                Map.of("prod", runtime),
                                                infrastructureConfig(NetworkingType.MANUAL), defaultOperationsConfig());

            assertThat(warnings(config)).noneSatisfy(warning -> assertThat(warning).contains("port 22"))
                                        .noneSatisfy(warning -> assertThat(warning).contains("port 8080"));
        }

        /// PF-24. Either half alone is a defensible operator choice; the pair is unauthenticated
        /// remote control of the cluster. Reachable by following the documented cloud example, which
        /// sets security_mode="none" to get past bootstrap's own config write.
        @Test
        void validate_publicManagementPortWithSecurityDisabled_returnsPf24() {
            validate(cloudConfigWithPublicManagement("none"))
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("PF-24")
                                                              .contains("unauthenticated management API"));
        }

        @Test
        void validate_publicManagementPortWithAuthEnabled_succeeds() {
            validate(cloudConfigWithPublicManagement("api_key"))
                .onFailure(cause -> assertThat(cause.message()).doesNotContain("PF-24"));
        }

        @Test
        void validate_scopedManagementPortWithSecurityDisabled_succeeds() {
            validate(cloudConfigWithScopedManagement("none"))
                .onFailure(cause -> assertThat(cause.message()).doesNotContain("PF-24"));
        }

        @Test
        void validate_allowIngressOnHetznerSource_succeeds() {
            validate(cloudConfigWithFirewall(CloudProviderName.HETZNER))
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(config -> assertThat(config.cluster().name().value()).isEqualTo("production"));
        }

        @Test
        void validate_electedLbOnSsh_returnsError() {            var coreRole = roleSubTable(NodeRole.CORE, none(), some(List.of("h1", "h2", "h3")), none(), "ember");
            var source = sourceProfile(sourceNameOrDefault("ssh-src"), SourceType.SSH, none(), none(), none(), none(),
                                       some("root"), some("/key"), some(22), LoadBalancerMode.ELECTED,
                                       List.of(), none(), Map.of(),
                                       Map.of(NodeRole.CORE, coreRole), List.of());
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("test", "1.0.0").unwrap(),
                                                defaultCoreTopology(), Map.of("ssh-src", source), Map.of(),
                                                infrastructureConfig(NetworkingType.MANUAL),
                                                defaultOperationsConfig());
            validate(config)
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("PF-17"));
        }

        @Test
        void validate_sshWithCountInsteadOfHosts_returnsError() {
            var coreRole = roleSubTable(NodeRole.CORE, some(3), none(), none(), "ember");
            var source = sourceProfile(sourceNameOrDefault("ssh-src"), SourceType.SSH, none(), none(), none(), none(),
                                       some("root"), some("/key"), some(22), LoadBalancerMode.NONE,
                                       List.of(), none(), Map.of(),
                                       Map.of(NodeRole.CORE, coreRole), List.of());
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("test", "1.0.0").unwrap(),
                                                defaultCoreTopology(), Map.of("ssh-src", source), Map.of(),
                                                infrastructureConfig(NetworkingType.MANUAL),
                                                defaultOperationsConfig());
            validate(config)
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("PF-10"));
        }

        @Test
        void validate_cloudWithHostsInsteadOfCount_returnsError() {
            var runtime = runtimeProfile("prod", RuntimeType.CONTAINER, some("aether:latest"), none());
            var coreRole = roleSubTable(NodeRole.CORE, none(), some(List.of("h1", "h2", "h3")),
                                        some("cx41"), "prod");
            var source = sourceProfile(sourceNameOrDefault("cloud-src"), SourceType.CLOUD, some(CloudProviderName.HETZNER),
                                       some("key"), some("eu"), none(), none(), none(), none(),
                                       LoadBalancerMode.EXTERNAL, List.of(), none(), Map.of(),
                                       Map.of(NodeRole.CORE, coreRole), List.of());
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("test", "1.0.0").unwrap(),
                                                defaultCoreTopology(), Map.of("cloud-src", source),
                                                Map.of("prod", runtime),
                                                infrastructureConfig(NetworkingType.MANUAL),
                                                defaultOperationsConfig());
            validate(config)
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("PF-11"));
        }

        @Test
        void validate_invalidFirewallPort_returnsError() {
            var rule = firewallRule(0, "tcp", "10.0.0.0/8", none());
            var coreRole = roleSubTable(NodeRole.CORE, some(3), none(), none(), "ember");
            var source = sourceProfile(sourceNameOrDefault("local"), SourceType.FORGE, none(), none(), none(), none(),
                                       none(), none(), none(), LoadBalancerMode.ELECTED, List.of(),
                                       none(), Map.of(), Map.of(NodeRole.CORE, coreRole), List.of(rule));
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("test", "1.0.0").unwrap(),
                                                defaultCoreTopology(), Map.of("local", source), Map.of(),
                                                infrastructureConfig(NetworkingType.MANUAL),
                                                defaultOperationsConfig());
            validate(config)
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("PF-18").contains("invalid port"));
        }

        @Test
        void validate_invalidFirewallProtocol_returnsError() {
            var rule = firewallRule(443, "icmp", "10.0.0.0/8", none());
            var coreRole = roleSubTable(NodeRole.CORE, some(3), none(), none(), "ember");
            var source = sourceProfile(sourceNameOrDefault("local"), SourceType.FORGE, none(), none(), none(), none(),
                                       none(), none(), none(), LoadBalancerMode.ELECTED, List.of(),
                                       none(), Map.of(), Map.of(NodeRole.CORE, coreRole), List.of(rule));
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("test", "1.0.0").unwrap(),
                                                defaultCoreTopology(), Map.of("local", source), Map.of(),
                                                infrastructureConfig(NetworkingType.MANUAL),
                                                defaultOperationsConfig());
            validate(config)
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("PF-18").contains("invalid protocol"));
        }

        @Test
        void validate_forgeWithNonEmberRuntime_returnsError() {
            var runtime = runtimeProfile("jvm-rt", RuntimeType.JVM, none(), none());
            var coreRole = roleSubTable(NodeRole.CORE, some(3), none(), none(), "jvm-rt");
            var source = sourceProfile(sourceNameOrDefault("local"), SourceType.FORGE, none(), none(), none(), none(),
                                       none(), none(), none(), LoadBalancerMode.ELECTED, List.of(),
                                       none(), Map.of(), Map.of(NodeRole.CORE, coreRole), List.of());
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("test", "1.0.0").unwrap(),
                                                defaultCoreTopology(), Map.of("local", source),
                                                Map.of("jvm-rt", runtime),
                                                infrastructureConfig(NetworkingType.MANUAL),
                                                defaultOperationsConfig());
            validate(config)
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("PF-19"));
        }

        @Test
        void validate_dockerWithNonDockerRuntime_returnsError() {
            var runtime = runtimeProfile("jvm-rt", RuntimeType.JVM, none(), none());
            var coreRole = roleSubTable(NodeRole.CORE, some(3), none(), none(), "jvm-rt");
            var source = sourceProfile(sourceNameOrDefault("local"), SourceType.DOCKER, none(), none(), none(), none(),
                                       none(), none(), none(), LoadBalancerMode.NONE, List.of(),
                                       none(), Map.of(), Map.of(NodeRole.CORE, coreRole), List.of());
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("test", "1.0.0").unwrap(),
                                                defaultCoreTopology(), Map.of("local", source),
                                                Map.of("jvm-rt", runtime),
                                                infrastructureConfig(NetworkingType.MANUAL),
                                                defaultOperationsConfig());
            validate(config)
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("PF-20"));
        }
    }

    @Nested
    class Warnings {

        @Test
        void warnings_singleSourceCoreMajority_returnsWarning() {
            var result = warnings(validForgeConfig());

            assertThat(result).anyMatch(w -> w.contains("CL-13"));
        }
    }
}
