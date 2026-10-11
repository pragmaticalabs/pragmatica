// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// #1027: docker removal (scale-down, role or source removal) used to address `<source>-<role>-<index>` labels that no container is ever named, so it
/// removed nothing it meant to. It now discovers the cluster/source/role containers by label and removes the NEWEST `count` by node id.
class WaveExecutorDockerRemovalTest {
    private static final ClusterName DOCK = ClusterName.clusterName("dock").unwrap();

    private static InstanceInfo container(String nodeId, String cluster, String source, String role) {
        return new InstanceInfo(InstanceId.instanceId(nodeId).unwrap(),
                                InstanceStatus.RUNNING,
                                List.of(),
                                InstanceType.ON_DEMAND,
                                Map.of("aether.cluster", cluster, "aether.source", source, "aether.role", role),
                                Option.some(nodeId),
                                Option.none());
    }

    private static final class FakeDocker implements ComputeProvider {
        final List<InstanceInfo> containers;
        final List<String> terminated = new ArrayList<>();

        FakeDocker(List<InstanceInfo> containers) {
            this.containers = containers;
        }

        @Override public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            return Causes.cause("unused").promise();
        }

        @Override public Promise<Unit> terminate(InstanceId instanceId) {
            terminated.add(instanceId.value());

            return Promise.success(Unit.unit());
        }

        @Override public Promise<List<InstanceInfo>> listInstances() {
            return Promise.success(containers);
        }

        @Override public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return Causes.cause("unused").promise();
        }
    }

    private static FakeDocker docker() {
        return new FakeDocker(List.of(container("aether-dock-node-009", "dock", "other", "worker"),
                                      container("aether-else-node-008", "else", "local", "worker"),
                                      container("aether-dock-node-007", "dock", "local", "core"),
                                      container("aether-dock-node-01a", "dock", "local", "worker"),
                                      container("aether-dock-node-01b", "dock", "local", "worker"),
                                      container("aether-dock-node-01c", "dock", "local", "worker"),
                                      container("aether-dock-node-01d", "dock", "other", "worker"),
                                      container("aether-dock-node-01e", "dock", "local", "core"),
                                      container("aether-else-node-01f", "else", "local", "worker")));
    }

    @Test
    void scaleDown_removesTheNewestContainersOfThatSourceRoleAndCluster_andNothingElse() {
        var compute = docker();

        var result = WaveExecutor.destroyDockerNodes(compute, DOCK, sourceNameOrDefault("local"), NodeRole.WORKER, 2);

        assertThat(result.isSuccess()).isTrue();
        assertThat(compute.terminated).as("the two newest local workers of cluster dock, by name").containsExactlyInAnyOrder("aether-dock-node-01c", "aether-dock-node-01b");
    }

    @Test
    void removingMoreThanExist_isRefused_andRemovesNothing() {
        var compute = docker();

        var result = WaveExecutor.destroyDockerNodes(compute, DOCK, sourceNameOrDefault("local"), NodeRole.WORKER, 4);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("only 3 container(s)").contains("Nothing was removed"));
        assertThat(compute.terminated).isEmpty();
    }

    private static final String DOCKER_CLUSTER = """
            config_version = "1.0.0"

            [cluster]
            name = "dock"
            version = "1.0.0"

            [source.local]
            type = "docker"

            [source.local.core]
            count = 3

            [source.local.worker]
            count = 3
            """;

    /// The wiring: a docker `ScaleDown` reaches the label-discovery path through the real removal dispatch, with the provider the resolver hands back.
    @Test
    void aDockerScaleDownAction_removesTheRealContainers_throughTheRemovalDispatch() {
        var compute = docker();

        ProviderResolver.dockerFactoryOverride = new org.pragmatica.aether.environment.EnvironmentIntegrationFactory() {
            @Override
            public String providerName() {
                return "docker";
            }

            @Override
            public org.pragmatica.lang.Result<org.pragmatica.aether.environment.EnvironmentIntegration> create(org.pragmatica.aether.environment.CloudConfig config) {
                return org.pragmatica.lang.Result.success(new org.pragmatica.aether.environment.EnvironmentIntegration() {
                    @Override public Option<ComputeProvider> compute() { return Option.some(compute); }
                    @Override public Option<org.pragmatica.aether.environment.SecretsProvider> secrets() { return Option.none(); }
                    @Override public Option<org.pragmatica.aether.environment.LoadBalancerProvider> loadBalancer() { return Option.none(); }
                });
            }
        };
        try {
            var stored = org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser.parse(DOCKER_CLUSTER).unwrap();
            var action = new org.pragmatica.aether.config.cluster.DiffAction.ScaleDown(sourceNameOrDefault("local"), NodeRole.WORKER, 3, 1);

            var result = WaveExecutor.executeRemoval(action, stored, 8080);

            assertThat(result.isSuccess()).isTrue();
            assertThat(compute.terminated).containsExactlyInAnyOrder("aether-dock-node-01c", "aether-dock-node-01b");
        } finally {
            ProviderResolver.dockerFactoryOverride = null;
        }
    }

    /// Rolling restart of ONE docker node, through the real drain-and-destroy step: the `<source>-<role>-<index>` label names no container, so the
    /// OLDEST container of the cluster/source/role is drained at its published management port and then destroyed by its own name.
    @Test
    void aRollingRestartOfADockerNode_drainsAndDestroysTheOldestRealContainer_notTheLabel() {
        var compute = docker();
        var http = new ScriptedDrainHttp(ScriptedDrainHttp.drainAccepted("aether-dock-node-01a"),
                                         ScriptedDrainHttp.notFound("aether-dock-node-01a"),
                                         ScriptedDrainHttp.connectionRefused());
        var originalHttp = ClusterHttpClient.HTTP_OPS_REF.get();

        ProviderResolver.dockerFactoryOverride = factoryReturning(compute);
        DockerHostPorts.override = container -> org.pragmatica.lang.Result.success(38911);
        ClusterHttpClient.HTTP_OPS_REF.set(http);
        try {
            var source = org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser.parse(DOCKER_CLUSTER).unwrap().sources().get("local");

            var result = WaveExecutor.drainAndDestroyNode(DOCK, "local-worker-0", sourceNameOrDefault("local"), NodeRole.WORKER, source, 8080);

            assertThat(result.isSuccess()).isTrue();
            assertThat(compute.terminated).as("the oldest local worker of cluster dock, by its own name").containsExactly("aether-dock-node-01a");
            assertThat(http.requestUris()).as("drained at the published port, by the real node id")
                                          .anyMatch(uri -> uri.startsWith("POST http") && uri.contains("://127.0.0.1:38911/api/v1/nodes/drain/aether-dock-node-01a"));
            assertThat(http.requests()).noneMatch(request -> request.contains("local-worker-0"));
        } finally {
            ProviderResolver.dockerFactoryOverride = null;
            DockerHostPorts.override = null;
            ClusterHttpClient.HTTP_OPS_REF.set(originalHttp);
        }
    }

    private static org.pragmatica.aether.environment.EnvironmentIntegrationFactory factoryReturning(ComputeProvider compute) {
        return new org.pragmatica.aether.environment.EnvironmentIntegrationFactory() {
            @Override
            public String providerName() {
                return "docker";
            }

            @Override
            public org.pragmatica.lang.Result<org.pragmatica.aether.environment.EnvironmentIntegration> create(org.pragmatica.aether.environment.CloudConfig config) {
                return org.pragmatica.lang.Result.success(new org.pragmatica.aether.environment.EnvironmentIntegration() {
                    @Override public Option<ComputeProvider> compute() { return Option.some(compute); }
                    @Override public Option<org.pragmatica.aether.environment.SecretsProvider> secrets() { return Option.none(); }
                    @Override public Option<org.pragmatica.aether.environment.LoadBalancerProvider> loadBalancer() { return Option.none(); }
                });
            }
        };
    }
}
