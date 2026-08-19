// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapError;
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
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

class BootstrapPhaseDeployHealthPollTest {

    private static SourceProfile cloudSource() {
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
                                           List.of());
    }

    /// A second core-bearing source keeps `discoveryAssembly` FALSE, so these tests keep pinning
    /// the LEGACY SSH-push + management-port health-poll path (RFC-0017 stage 4 routes the
    /// single-cloud-core-source shape — the wizard's only output — through label-based formation
    /// observation instead; that path is pinned by `BootstrapPhaseDeployFormationLabelsTest`).
    private static SourceProfile sshCoreSource() {
        return SourceProfile.sourceProfile(sourceNameOrDefault("dc-1"),
                                           SourceType.SSH,
                                           Option.empty(),
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
                                                                            Option.some(2),
                                                                            Option.empty(),
                                                                            Option.empty(),
                                                                            "default")),
                                           List.of());
    }

    private static ClusterBootstrapConfig configWithShortTimeout() {
        // Use a very short healthCheck timeout so the failing branch completes in test time.
        var timeouts = TimeoutsConfig.timeoutsConfig("3s", "10s", "10s");
        var ports = PortMapping.defaultPortMapping();
        var ops = OperationsConfig.operationsConfig(AutoHealSpec.defaultAutoHealSpec(),
                                                    TlsDeploymentConfig.defaultTlsConfig(),
                                                    timeouts,
                                                    ports);
        return ClusterBootstrapConfig.clusterBootstrapConfig("1.0.0",
                                                             ClusterIdentity.clusterIdentity("prod", "1.0.0").unwrap(),
                                                             CoreTopology.defaultCoreTopology(),
                                                             Map.of("eu-1", cloudSource(), "dc-1", sshCoreSource()),
                                                             Map.of(),
                                                             InfrastructureConfig.infrastructureConfig(NetworkingType.MANUAL),
                                                             ops);
    }

    private static BootstrapContext contextWithThreeCloudNodes() {
        var config = configWithShortTimeout();
        var nodes = List.of(
            ProvisionedNode.provisionedNode("eu-1-core-0", "100", "203.0.113.10"),
            ProvisionedNode.provisionedNode("eu-1-core-1", "101", "203.0.113.11"),
            ProvisionedNode.provisionedNode("eu-1-core-2", "102", "203.0.113.12"));
        var addresses = List.of(
            NodeAddress.nodeAddress("eu-1-core-0", "203.0.113.10", Option.empty()),
            NodeAddress.nodeAddress("eu-1-core-1", "203.0.113.11", Option.empty()),
            NodeAddress.nodeAddress("eu-1-core-2", "203.0.113.12", Option.empty()));
        var state = BootstrapState.initialState("prod", "h", "now").withClusterSecret("s");
        return BootstrapContext.bootstrapContext(config, state, nodes, addresses)
                               .withClusterSecret("s");
    }

    @Test
    void deployCloudSource_succeeds_whenAllNodesHealthy() {
        var ctx = contextWithThreeCloudNodes();
        Fn1<Result<String>, String> alwaysHealthy = url -> Result.success("OK");

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx, ctx.config().sources().get("eu-1"), sourceNameOrDefault("eu-1"), alwaysHealthy);

        assertTrue(result.isSuccess(), () -> "All-healthy poll should succeed; got: " + result);
    }

    @Test
    void deployCloudSource_fails_namingTheUnreachableIp_whenOneNodeNeverResponds() {
        var ctx = contextWithThreeCloudNodes();
        var unreachable = "203.0.113.11";
        Fn1<Result<String>, String> stub = url -> url.contains(unreachable)
                                                  ? new TestError("connection refused").result()
                                                  : Result.success("OK");

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx, ctx.config().sources().get("eu-1"), sourceNameOrDefault("eu-1"), stub);

        assertTrue(result.isFailure(),
                   "Phase must fail when at least one node fails to come up before timeout");
        var msg = extractFailureMessage(result);
        assertTrue(msg.contains(unreachable),
                   () -> "Failure message must name the unreachable IP. Got: " + msg);
        // The gate polls the management API; it never inspects cloud-init. Blaming cloud-init sent a
        // live 2026-08-05 investigation to the wrong place — the nodes were healthy and the port was
        // firewalled. The message must name the port and the likeliest cause.
        assertTrue(msg.contains("management") && msg.contains("API"),
                   () -> "Failure message must name what was actually polled. Got: " + msg);
        assertTrue(msg.contains("firewall"),
                   () -> "Failure message must offer the likeliest cause (blocked ingress). Got: " + msg);
    }

    @Test
    void deployCloudSource_doesNotMisreportHealthyNodes_whenSomeFail() {
        var ctx = contextWithThreeCloudNodes();
        var unreachable = "203.0.113.12";
        Fn1<Result<String>, String> stub = url -> url.contains(unreachable)
                                                  ? new TestError("connection refused").result()
                                                  : Result.success("OK");

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx, ctx.config().sources().get("eu-1"), sourceNameOrDefault("eu-1"), stub);

        var msg = extractFailureMessage(result);
        assertFalse(msg.contains("203.0.113.10"), "Healthy node 10 must not appear in unreachable list");
        assertFalse(msg.contains("203.0.113.11"), "Healthy node 11 must not appear in unreachable list");
        assertTrue(msg.contains(unreachable), "Only the actually-failing node must be listed");
    }

    private static String extractFailureMessage(Result<?> result) {
        return result.fold(c -> c.message(), v -> "<unexpected success: " + v + ">");
    }

    @Test
    void deployCloudSource_pollsCorrectHealthEndpoint() {
        var ctx = contextWithThreeCloudNodes();
        var seen = ConcurrentHashMap.<String>newKeySet();
        Fn1<Result<String>, String> stub = url -> {
            seen.add(url);
            return Result.success("OK");
        };

        var _ = BootstrapPhaseDeploy.deployCloudSource(ctx, ctx.config().sources().get("eu-1"), sourceNameOrDefault("eu-1"), stub);

        // Each node's /health/live URL must have been called at least once
        assertTrue(seen.stream().anyMatch(u -> u.contains("203.0.113.10") && u.endsWith("/health/live")),
                   "Must poll /health/live on node 0");
        assertTrue(seen.stream().anyMatch(u -> u.contains("203.0.113.11") && u.endsWith("/health/live")),
                   "Must poll /health/live on node 1");
        assertTrue(seen.stream().anyMatch(u -> u.contains("203.0.113.12") && u.endsWith("/health/live")),
                   "Must poll /health/live on node 2");
    }

    @Test
    void deployCloudSource_succeedsOnFirstAttempt_whenAllNodesAlreadyHealthy() {
        // Sanity: under healthy conditions the loop must terminate immediately
        // (no retry overhead) and not poll a second time.
        var ctx = contextWithThreeCloudNodes();
        var pollCounts = new ConcurrentHashMap<String, AtomicInteger>();
        Fn1<Result<String>, String> stub = url -> {
            pollCounts.computeIfAbsent(url, k -> new AtomicInteger()).incrementAndGet();
            return Result.success("OK");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx, ctx.config().sources().get("eu-1"), sourceNameOrDefault("eu-1"), stub);

        assertTrue(result.isSuccess(), () -> "All-healthy poll must succeed: " + result);
        for (var counter : pollCounts.values()) {
            assertEquals(1, counter.get(),
                         "When all nodes are healthy the loop must NOT spin a second iteration");
        }
    }

    @Test
    void deployCloudSource_skipsPolling_whenNoNodesProvisioned() {
        var emptyCtx = BootstrapContext.bootstrapContext(configWithShortTimeout(),
                                                         BootstrapState.initialState("prod", "h", "now"),
                                                         List.of(),
                                                         List.of()).withClusterSecret("s");
        var probe = new AtomicInteger();
        Fn1<Result<String>, String> stub = url -> {
            probe.incrementAndGet();
            return Result.success("OK");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(emptyCtx, emptyCtx.config().sources().get("eu-1"), sourceNameOrDefault("eu-1"), stub);

        assertTrue(result.isSuccess(), "Empty node list must be a no-op success, not a timeout");
        assertEquals(0, probe.get(),
                     "Health-check stub must NOT be invoked when there's nothing to poll");
    }

    /// Local error type — we don't want to depend on a specific cause variant from
    /// production code in this test, just need *some* Result<String>.failure() value.
    private record TestError(String detail) implements org.pragmatica.lang.Cause {
        @Override public String message() { return detail; }
    }
}
