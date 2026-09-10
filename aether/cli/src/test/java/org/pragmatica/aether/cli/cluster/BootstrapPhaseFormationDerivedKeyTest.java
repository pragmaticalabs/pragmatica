// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.cluster.AutoHealSpec;
import org.pragmatica.aether.config.cluster.CloudProviderName;
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
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.config.cluster.TimeoutsConfig;
import org.pragmatica.aether.config.cluster.TlsDeploymentConfig;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.security.ClusterSecretDerivation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// #980 — the CLI half of the bootstrap admin key.
///
/// The defect this pins against: `aether cluster init` never writes an API key, so
/// `extractConfiguredApiKey` returned EMPTY, the phase-7 quorum poll went out unauthenticated, and a
/// genuinely healthy cluster answered `401 X-API-Key header required` — after which the tooling tore
/// it down. The key must now be DERIVED from the cluster secret this run minted in phase VALIDATE,
/// which is the same value the node registers in KV at first leadership.
class BootstrapPhaseFormationDerivedKeyTest {
    private static final String CLUSTER_SECRET = "formation-test-cluster-secret";

    /// THE regression. Every `aether cluster init` cluster is in this state: a cluster secret and no
    /// configured key anywhere.
    @Test
    void resolveManagementKey_noConfiguredKey_derivesFromTheClusterSecret() {
        var key = BootstrapPhaseFormation.resolveManagementKey(context(Option.empty(), CLUSTER_SECRET));

        assertThat(key.isPresent()).as("the quorum poll must carry a credential, not go out bare").isTrue();
        assertThat(key.unwrap()).as("and it must be the value the node derives and registers")
                  .isEqualTo(ClusterSecretDerivation.bootstrapAdminKey(CLUSTER_SECRET).unwrap());
    }

    /// An operator-configured ADMIN key still wins: it is accepted from node BOOT, whereas the derived
    /// key only becomes valid once the leader has committed its hash. Pinned so the fallback is not
    /// quietly promoted to the primary path.
    @Test
    void resolveManagementKey_configuredAdminKey_winsOverTheDerivedOne() {
        var key = BootstrapPhaseFormation.resolveManagementKey(context(Option.some(adminKeyToml()), CLUSTER_SECRET));

        assertThat(key.unwrap()).isEqualTo("operator-admin-key");
        assertThat(key.unwrap()).isNotEqualTo(ClusterSecretDerivation.bootstrapAdminKey(CLUSTER_SECRET).unwrap());
    }

    /// Neither a configured key nor a secret: the poll runs unauthenticated exactly as before #980,
    /// so a cluster with security off is unaffected. `BootstrapContext` defaults the secret to `""`,
    /// and deriving an ADMIN credential from the empty string would hand every such cluster the same
    /// publicly-computable key.
    @Test
    void resolveManagementKey_noKeyAndNoSecret_staysEmpty() {
        assertThat(BootstrapPhaseFormation.resolveManagementKey(context(Option.empty(), "")).isEmpty())
            .as("an empty cluster secret must not be derived from").isTrue();
        assertThat(BootstrapPhaseFormation.resolveManagementKey(context(Option.empty(), "   ")).isEmpty())
            .as("a blank cluster secret must not be derived from either").isTrue();
    }

    @Test
    void resolveManagementKey_differentClusterSecrets_yieldDifferentKeys() {
        var first = BootstrapPhaseFormation.resolveManagementKey(context(Option.empty(), CLUSTER_SECRET));
        var second = BootstrapPhaseFormation.resolveManagementKey(context(Option.empty(), CLUSTER_SECRET + "-other"));

        assertThat(first.unwrap()).isNotEqualTo(second.unwrap());
    }

    private static TomlDocument adminKeyToml() {
        return new TomlDocument(Map.of("app-http.api-keys.operator-admin-key",
                                       Map.of("authorization_role", "ADMIN")));
    }

    private static BootstrapContext context(Option<TomlDocument> nodeConfig, String clusterSecret) {
        var state = BootstrapState.initialState(clusterName("prod").unwrap(), "h", "now")
                                  .withClusterSecret(clusterSecret);

        return BootstrapContext.bootstrapContext(config(nodeConfig), state, List.of(), List.of())
                               .withClusterSecret(clusterSecret);
    }

    private static ClusterBootstrapConfig config(Option<TomlDocument> nodeConfig) {
        var ops = OperationsConfig.operationsConfig(AutoHealSpec.defaultAutoHealSpec(),
                                                    TlsDeploymentConfig.defaultTlsConfig(),
                                                    TimeoutsConfig.timeoutsConfig("3s", "10s", "10s"),
                                                    PortMapping.defaultPortMapping());

        return ClusterBootstrapConfig.clusterBootstrapConfig("1.0.0",
                                                             ClusterIdentity.clusterIdentity("prod", "1.0.0").unwrap(),
                                                             CoreTopology.defaultCoreTopology(),
                                                             Map.of("eu-1", cloudSource(nodeConfig)),
                                                             Map.of(),
                                                             InfrastructureConfig.infrastructureConfig(NetworkingType.MANUAL),
                                                             ops);
    }

    private static SourceProfile cloudSource(Option<TomlDocument> nodeConfig) {
        return SourceProfile.sourceProfile(sourceNameOrDefault("eu-1"),
                                           SourceType.CLOUD,
                                           Option.some(CloudProviderName.HETZNER),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           LoadBalancerMode.NONE,
                                           List.of(),
                                           Option.empty(),
                                           Map.of(),
                                           Map.of(NodeRole.CORE,
                                                  RoleSubTable.roleSubTable(NodeRole.CORE,
                                                                            Option.some(3),
                                                                            Option.empty(),
                                                                            Option.empty(),
                                                                            "default")),
                                           List.of(),
                                           nodeConfig);
    }
}
