// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterIdentity;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.CoreTopology;
import org.pragmatica.aether.config.cluster.InfrastructureConfig;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NetworkingType;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.OperationsConfig;
import org.pragmatica.aether.config.cluster.PortMapping;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.AutoHealSpec;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.config.cluster.TimeoutsConfig;
import org.pragmatica.aether.config.cluster.TlsDeploymentConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// RFC-0017 stage 4 — the deploy phase's discovery-mode branch: the gate that selects it and the
/// C4 formation observation (polling the provider API for `aether-formed=true` labels) that
/// replaces the management-port health poll, which a firewalled management port failed on
/// HEALTHY nodes (found live on Hetzner, 2026-08-05).
class BootstrapPhaseDeployFormationLabelsTest {

    private static SourceProfile source(String name, SourceType type, int coreCount) {
        var roles = coreCount > 0
                    ? Map.of(NodeRole.CORE,
                             RoleSubTable.roleSubTable(NodeRole.CORE, Option.some(coreCount), Option.empty(), Option.empty(), "default"))
                    : Map.<NodeRole, RoleSubTable> of();

        return SourceProfile.sourceProfile(sourceNameOrDefault(name),
                                           type,
                                           type == SourceType.CLOUD
                                           ? Option.some(CloudProviderName.HETZNER)
                                           : Option.empty(),
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
                                           roles,
                                           List.of());
    }

    private static ClusterBootstrapConfig config(Map<String, SourceProfile> sources) {
        var ops = OperationsConfig.operationsConfig(AutoHealSpec.defaultAutoHealSpec(),
                                                    TlsDeploymentConfig.defaultTlsConfig(),
                                                    TimeoutsConfig.timeoutsConfig("3s", "10s", "10s"),
                                                    PortMapping.defaultPortMapping());

        return ClusterBootstrapConfig.clusterBootstrapConfig("1.0.0",
                                                             ClusterIdentity.clusterIdentity("prod", "1.0.0").unwrap(),
                                                             CoreTopology.defaultCoreTopology(),
                                                             sources,
                                                             Map.of(),
                                                             InfrastructureConfig.infrastructureConfig(NetworkingType.MANUAL),
                                                             ops);
    }

    /// The wizard's only output — one cloud source carrying the cores — is exactly the shape that
    /// self-assembles. Anything else keeps the legacy SSH push, because `discoverPeers` sees only
    /// its own provider account and cores spread across sources cannot find each other by label.
    @Test
    void discoveryAssembly_engagesOnlyForASingleCloudCoreSource() {
        assertThat(BootstrapPhaseDeploy.discoveryAssembly(config(Map.of("eu", source("eu", SourceType.CLOUD, 3)))))
                .isTrue();
        assertThat(BootstrapPhaseDeploy.discoveryAssembly(config(Map.of("eu",
                                                                        source("eu", SourceType.CLOUD, 3),
                                                                        "workers",
                                                                        source("workers", SourceType.CLOUD, 0)))))
                .as("a second source WITHOUT cores does not disturb the gate")
                .isTrue();
        assertThat(BootstrapPhaseDeploy.discoveryAssembly(config(Map.of("eu",
                                                                        source("eu", SourceType.CLOUD, 3),
                                                                        "us",
                                                                        source("us", SourceType.CLOUD, 2)))))
                .as("cores in TWO sources cannot self-assemble by label — legacy path")
                .isFalse();
        assertThat(BootstrapPhaseDeploy.discoveryAssembly(config(Map.of("dc", source("dc", SourceType.SSH, 3)))))
                .as("SSH sources have no provider API to discover through")
                .isFalse();
    }

    @Test
    void pollFormedLabels_succeeds_whenExpectedCoresReportFormation() {
        var calls = new AtomicInteger();
        // 1 formed on the first poll, all 3 on the second — formation is gradual.
        var result = BootstrapPhaseDeploy.pollFormedLabels(filter -> Result.success(calls.incrementAndGet() == 1
                                                                                    ? 1
                                                                                    : 3),
                                                           3,
                                                           clusterName("prod").unwrap(),
                                                           sourceNameOrDefault("eu"),
                                                           5_000,
                                                           10);

        assertThat(result.isSuccess()).isTrue();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void pollFormedLabels_queriesTheFormedLabelScopedToTheCluster() {
        var seen = new ArrayList<Map<String, String>>();

        var _ = BootstrapPhaseDeploy.pollFormedLabels(filter -> {
            seen.add(filter);

            return Result.success(3);
        }, 3, clusterName("prod").unwrap(), sourceNameOrDefault("eu"), 5_000, 10);

        assertThat(seen.getFirst())
                .containsEntry("aether-cluster", "prod")
                .containsEntry("aether-formed", "true");
    }

    /// Silence is not success: at the deadline the failure names the counts and where to look.
    @Test
    void pollFormedLabels_timesOut_namingTheShortfall() {
        var result = BootstrapPhaseDeploy.pollFormedLabels(filter -> Result.success(1), 3, clusterName("prod").unwrap(), sourceNameOrDefault("eu"), 60, 10);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("1 of 3", "aether-formed", "cloud-init"));
    }

    /// A transient provider-API failure must not abort the wait — the poll keeps the last good
    /// count and retries until the deadline.
    @Test
    void pollFormedLabels_survivesTransientPollFailures() {
        var calls = new AtomicInteger();
        var result = BootstrapPhaseDeploy.pollFormedLabels(filter -> calls.incrementAndGet() < 3
                                                                     ? Causes.cause("api hiccup").result()
                                                                     : Result.success(3),
                                                           3,
                                                           clusterName("prod").unwrap(),
                                                           sourceNameOrDefault("eu"),
                                                           5_000,
                                                           10);

        assertThat(result.isSuccess()).isTrue();
    }
}
