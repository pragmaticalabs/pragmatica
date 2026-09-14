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
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


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

        /// CL-08 second half (#296 review SF-1): node ids are `<source>-<role>-<index>`, so a source
        /// name that dash-prefixes another would let two sources claim one node. Refused at load.
        @Test
        void validate_sourceNameIsDashPrefixOfAnother_returnsCl08NamingBoth() {
            var coreRole = roleSubTable(NodeRole.CORE, some(3), none(), none(), "ember");
            var eu = sourceProfile(sourceNameOrDefault("eu"), SourceType.FORGE, none(), none(), none(), none(),
                                   none(), none(), none(), LoadBalancerMode.ELECTED, List.of(),
                                   none(), Map.of(), Map.of(NodeRole.CORE, coreRole), List.of());
            var eu1 = sourceProfile(sourceNameOrDefault("eu-1"), SourceType.FORGE, none(), none(), none(), none(),
                                    none(), none(), none(), LoadBalancerMode.ELECTED, List.of(),
                                    none(), Map.of(), Map.of(NodeRole.CORE, coreRole), List.of());
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("test", "1.0.0").unwrap(),
                                                defaultCoreTopology(), Map.of("eu", eu, "eu-1", eu1), Map.of(),
                                                infrastructureConfig(NetworkingType.MANUAL),
                                                defaultOperationsConfig());
            validate(config)
                .onSuccess(v -> Assertions.fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).contains("CL-08")
                                                              .contains("'eu' is a prefix of source 'eu-1'"));
        }

        @Test
        void validate_distinctNonPrefixSourceNames_pass() {
            var coreRole = roleSubTable(NodeRole.CORE, some(3), none(), none(), "ember");
            var eu = sourceProfile(sourceNameOrDefault("eu"), SourceType.FORGE, none(), none(), none(), none(),
                                   none(), none(), none(), LoadBalancerMode.ELECTED, List.of(),
                                   none(), Map.of(), Map.of(NodeRole.CORE, coreRole), List.of());
            var us = sourceProfile(sourceNameOrDefault("us"), SourceType.FORGE, none(), none(), none(), none(),
                                   none(), none(), none(), LoadBalancerMode.ELECTED, List.of(),
                                   none(), Map.of(), Map.of(NodeRole.CORE, coreRole), List.of());
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("test", "1.0.0").unwrap(),
                                                defaultCoreTopology(), Map.of("eu", eu, "us", us), Map.of(),
                                                infrastructureConfig(NetworkingType.MANUAL),
                                                defaultOperationsConfig());
            validate(config).onFailure(cause -> assertThat(cause.message()).doesNotContain("CL-08"));
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
            var autoHeal = AutoHealSpec.autoHealSpec(false);
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

        /// #1049 — the runtime reads `replacement_ceiling` only from a cloud source; on any other
        /// source type the value would parse and never be read, so it is refused (PF-26).
        @Test
        void validate_replacementCeilingOnForgeSource_returnsPf26() {
            var coreRole = roleSubTable(NodeRole.CORE, some(3), none(), none(), "ember");
            var source = sourceProfile(sourceNameOrDefault("local"), SourceType.FORGE, none(), none(), none(), none(),
                                       List.of(), none(), none(), none(), LoadBalancerMode.ELECTED, List.of(),
                                       none(), Map.of(), Map.of(NodeRole.CORE, coreRole), List.of(), none(),
                                       some(timeSpan(5).minutes()));
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("dev-local", "1.0.0").unwrap(),
                                                defaultCoreTopology(), Map.of("local", source), Map.of(),
                                                infrastructureConfig(NetworkingType.MANUAL),
                                                defaultOperationsConfig());

            validate(config)
                .onSuccess(v -> Assertions.fail("Expected PF-26 for a ceiling no runtime path reads"))
                .onFailure(cause -> assertThat(cause.message()).contains("PF-26")
                                                              .contains("replacement_ceiling"));
        }

        /// Control for the PF-26 case above: the same key on a cloud source is read, so it is accepted.
        @Test
        void validate_replacementCeilingOnCloudSource_accepted() {
            var runtime = runtimeProfile("prod", RuntimeType.CONTAINER, some("aether:latest"), none());
            var coreRole = roleSubTable(NodeRole.CORE, some(3), none(), some("cx41"), "prod");
            var source = sourceProfile(sourceNameOrDefault("hetzner-eu"), SourceType.CLOUD, some(CloudProviderName.HETZNER),
                                       some("key"), some("eu-central"), none(), List.of(), none(), none(), none(),
                                       LoadBalancerMode.EXTERNAL, List.of("10.0.0.1"), none(), Map.of(),
                                       Map.of(NodeRole.CORE, coreRole), List.of(), none(),
                                       some(timeSpan(5).minutes()));
            var config = clusterBootstrapConfig("1.0.0", clusterIdentity("production", "1.0.0").unwrap(),
                                                defaultCoreTopology(), Map.of("hetzner-eu", source),
                                                Map.of("prod", runtime),
                                                infrastructureConfig(NetworkingType.MANUAL), defaultOperationsConfig());

            validate(config)
                .onFailure(cause -> assertThat(cause.message()).doesNotContain("PF-26"));
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

        /// #1090 review SF-2: a JVM/EMBER runtime on an SSH source used to pass validation and be
        /// refused only at DEPLOY_RUNTIME — after every other source had already provisioned. The
        /// deploy phase can launch only a container over SSH, so PF-22 says so at config load.
        @Test
        void validate_sshWithJvmRuntime_returnsError() {
            validate(sshConfigWithRuntime(runtimeProfile("jvm-rt", RuntimeType.JVM, none(), none())))
                .onSuccess(v -> Assertions.fail("Expected PF-22: a JVM runtime cannot be launched over SSH"))
                .onFailure(cause -> assertThat(cause.message()).contains("PF-22").contains("jvm-rt"));
        }

        @Test
        void validate_sshWithEmberRuntime_returnsError() {
            validate(sshConfigWithRuntime(runtimeProfile("ember-rt", RuntimeType.EMBER, none(), none())))
                .onSuccess(v -> Assertions.fail("Expected PF-22: an EMBER runtime cannot be launched over SSH"))
                .onFailure(cause -> assertThat(cause.message()).contains("PF-22").contains("ember-rt"));
        }

        /// Control for the two above: the container runtime is what the SSH path launches.
        @Test
        void validate_sshWithContainerRuntime_isAccepted() {
            var runtime = runtimeProfile("ctr", RuntimeType.CONTAINER, some("ghcr.io/pragmaticalabs/aether-node:1.0.0"),
                                         none());
            validate(sshConfigWithRuntime(runtime))
                .onFailure(cause -> assertThat(cause.message()).doesNotContain("PF-22"));
        }

        private static ClusterBootstrapConfig sshConfigWithRuntime(RuntimeProfile runtime) {
            var coreRole = roleSubTable(NodeRole.CORE, none(), some(List.of("h1", "h2", "h3")), none(), runtime.name());
            var source = sourceProfile(sourceNameOrDefault("ssh-src"), SourceType.SSH, none(), none(), none(), none(),
                                       some("root"), some("/key"), some(22), LoadBalancerMode.NONE,
                                       List.of(), none(), Map.of(),
                                       Map.of(NodeRole.CORE, coreRole), List.of());
            return clusterBootstrapConfig("1.0.0", clusterIdentity("test", "1.0.0").unwrap(),
                                          defaultCoreTopology(), Map.of("ssh-src", source),
                                          Map.of(runtime.name(), runtime),
                                          infrastructureConfig(NetworkingType.MANUAL),
                                          defaultOperationsConfig());
        }

        /// #1090 review SF-3: PF-09 catches a host listed twice INSIDE one SSH source; a host listed
        /// by two SSH sources was deployed twice, the second `docker run` replacing the first. One
        /// host runs one node — refuse it at config load, naming both sources.
        @Test
        void validate_sameHostInTwoSshSources_returnsError() {
            validate(twoSshSources(List.of("10.0.0.1", "10.0.0.2", "10.0.0.3"), List.of("10.0.0.1")))
                .onSuccess(v -> Assertions.fail("Expected PF-27: host 10.0.0.1 is declared by both SSH sources"))
                .onFailure(cause -> assertThat(cause.message()).contains("PF-27")
                                                               .contains("10.0.0.1")
                                                               .contains("'dc'")
                                                               .contains("'lab'"));
        }

        @Test
        void validate_distinctHostsAcrossSshSources_isAccepted() {
            validate(twoSshSources(List.of("10.0.0.1", "10.0.0.2", "10.0.0.3"), List.of("10.0.1.1")))
                .onFailure(cause -> assertThat(cause.message()).doesNotContain("PF-27"));
        }

        private static ClusterBootstrapConfig twoSshSources(List<String> dcHosts, List<String> labHosts) {
            var runtime = runtimeProfile("ctr", RuntimeType.CONTAINER, some("ghcr.io/pragmaticalabs/aether-node:1.0.0"),
                                         none());
            return clusterBootstrapConfig("1.0.0", clusterIdentity("test", "1.0.0").unwrap(),
                                          defaultCoreTopology(),
                                          Map.of("dc", sshSource("dc", NodeRole.CORE, dcHosts),
                                                 "lab", sshSource("lab", NodeRole.WORKER, labHosts)),
                                          Map.of("ctr", runtime),
                                          infrastructureConfig(NetworkingType.MANUAL),
                                          defaultOperationsConfig());
        }

        private static SourceProfile sshSource(String name, NodeRole role, List<String> hosts) {
            return sourceProfile(sourceNameOrDefault(name), SourceType.SSH, none(), none(), none(), none(),
                                 some("root"), some("/key"), some(22), LoadBalancerMode.NONE,
                                 List.of(), none(), Map.of(),
                                 Map.of(role, roleSubTable(role, none(), some(hosts), none(), "ctr")), List.of());
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

    /// #1019 — the consensus maximum at the BOOTSTRAP validator.
    ///
    /// Round 1 bounded the consensus tier at `aether cluster init` alone. The round-1 review (S2)
    /// showed what that left: this validator ACCEPTED a hand-written config with a derived core count
    /// of 11, and `[cluster.core] max = 15` besides — so the operator route the ticket itself calls a
    /// working alternative ("writing the config directly … DOES provision five core nodes") had no
    /// ceiling at all. The cap is now a property of the config, not of one command that writes configs.
    ///
    /// Both ends are asserted per rule rather than by "validate fails": `validateDerivedCoreCount` and
    /// `validateCoreMax` are separate checks that this class would otherwise conflate, and a config
    /// with an over-cap derived count also trips REQ-3.3.3 if `max` is left at the default.
    @Nested
    class ConsensusTierMaximum {

        private static ClusterBootstrapConfig configWithCoreCount(int count) {
            var coreRole = roleSubTable(NodeRole.CORE, some(count), none(), none(), "ember");
            var source = sourceProfile(sourceNameOrDefault("local"), SourceType.FORGE, none(), none(), none(), none(),
                                       none(), none(), none(), LoadBalancerMode.ELECTED, List.of(),
                                       none(), Map.of(), Map.of(NodeRole.CORE, coreRole), List.of());

            return clusterBootstrapConfig("1.0.0", clusterIdentity("dev-local", "1.0.0").unwrap(),
                                          defaultCoreTopology(), Map.of("local", source), Map.of(),
                                          infrastructureConfig(NetworkingType.MANUAL), defaultOperationsConfig());
        }

        private static ClusterBootstrapConfig configWithCoreMax(int max) {
            var coreRole = roleSubTable(NodeRole.CORE, some(5), none(), none(), "ember");
            var source = sourceProfile(sourceNameOrDefault("local"), SourceType.FORGE, none(), none(), none(), none(),
                                       none(), none(), none(), LoadBalancerMode.ELECTED, List.of(),
                                       none(), Map.of(), Map.of(NodeRole.CORE, coreRole), List.of());

            return clusterBootstrapConfig("1.0.0", clusterIdentity("dev-local", "1.0.0").unwrap(),
                                          coreTopology(some(5), some(max), 1), Map.of("local", source), Map.of(),
                                          infrastructureConfig(NetworkingType.MANUAL), defaultOperationsConfig());
        }

        private static String messageOf(ClusterBootstrapConfig config) {
            return validate(config).fold(cause -> cause.message(), _ -> "");
        }

        @Test
        void validate_fails_whenDerivedCoreCountExceedsTheMaximum() {
            assertThat(validate(configWithCoreCount(11)).isFailure()).isTrue();
            assertThat(messageOf(configWithCoreCount(11))).contains("CL-04")
                                                          .contains("must be <= 9");
        }

        /// The boundary, both sides. Without this, a cap set one too low or one too high still passes
        /// the test above.
        @Test
        void validate_succeeds_atTheMaximumAndFailsJustAbove() {
            assertThat(validate(configWithCoreCount(9)).isSuccess()).isTrue();
            assertThat(validate(configWithCoreCount(11)).isFailure()).isTrue();
        }

        /// The STRUCTURAL floor stays at 3 here — an existing 3-node cluster must still re-bootstrap.
        /// This is deliberately NOT `CoreWorkerSplit`'s supported minimum of 5, and pinning it stops a
        /// later reader "harmonising" the two.
        @Test
        void validate_succeeds_atTheStructuralFloorOfThree() {
            assertThat(validate(configWithCoreCount(3)).isSuccess()).isTrue();
        }

        @Test
        void validate_fails_whenCoreMaxExceedsTheMaximum() {
            assertThat(validate(configWithCoreMax(15)).isFailure()).isTrue();
            assertThat(messageOf(configWithCoreMax(15))).contains("REQ-3.3.3")
                                                        .contains("must be <= 9");
        }

        @Test
        void validate_succeeds_whenCoreMaxIsAtTheMaximum() {
            assertThat(validate(configWithCoreMax(9)).isSuccess()).isTrue();
        }
    }
}
