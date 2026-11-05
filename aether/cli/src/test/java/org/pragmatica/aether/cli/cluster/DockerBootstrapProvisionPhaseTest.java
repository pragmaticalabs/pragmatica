// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigValidator;
import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProviderDefaults;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2089 at the whole PROVISION phase (the probe shape v-2091 used on #2091): every core of the cluster, across ALL docker sources,
/// is created with ONE identical list of all cores, so two docker sources can never form two clusters; plus the lines the narrower
/// peer test leaves unpinned (failure early-return, per-node labels, zone, provisioned-by).
class DockerBootstrapProvisionPhaseTest {
    private final List<ProvisionRequest> requests = new ArrayList<>();
    private int failAt = -1;

    private final ComputeProvider recording = new ComputeProvider() {
        @Override
        public ProviderDefaults providerDefaults() {
            return ProviderDefaults.providerDefaults("docker", "", "", "", Option.empty(), false);
        }

        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            requests.add(request);
            if (requests.size() == failAt) {
                return Promise.failure(Causes.cause("injected failure"));
            }

            var name = request.context().nodeId().or("minted-by-provider-" + requests.size());

            return InstanceInfo.instanceInfo(InstanceId.instanceId(name).unwrap(), InstanceStatus.RUNNING, List.of(name), InstanceType.ON_DEMAND)
                               .async();
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            return Promise.success(List.of());
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return Promise.success(null);
        }
    };

    @BeforeEach
    void install() {
        DockerNodeLedger.override = _ -> DockerNodeLedger.none();
        DockerHostPorts.override = containerId -> Result.success(40_000 + Math.floorMod(containerId.hashCode(), 20_000));
        ProviderResolver.dockerFactoryOverride = new EnvironmentIntegrationFactory() {
            @Override
            public String providerName() {
                return "docker";
            }

            @Override
            public Result<EnvironmentIntegration> create(CloudConfig config) {
                return Result.success(EnvironmentIntegration.withCompute(recording));
            }
        };
    }

    @AfterEach
    void remove() {
        DockerNodeLedger.override = null;
        DockerHostPorts.override = null;
        ProviderResolver.dockerFactoryOverride = null;
    }

    private static ClusterBootstrapConfig config(String sources) {
        var toml = """
                config_version = "1.0.0"

                [cluster]
                name = "dock"
                version = "1.0.0"
                """ + sources;

        return ClusterBootstrapConfigParser.parse(toml).flatMap(ClusterBootstrapConfigValidator::validate).unwrap();
    }

    private Result<BootstrapContext> runPhase(ClusterBootstrapConfig config) {
        var ctx = BootstrapContext.bootstrapContext(config,
                                                    BootstrapState.initialState(ClusterName.clusterName("dock").unwrap(), "h", "now"),
                                                    List.of(),
                                                    List.of());

        return BootstrapPhaseProvision.execute(ctx);
    }

    private List<ProvisionRequest> role(String role) {
        return requests.stream().filter(r -> role.equals(r.context().role())).toList();
    }

    private static List<String> entries(ProvisionRequest r) {
        var peers = r.context().peers().or("");

        return peers.isEmpty() ? List.of() : Arrays.asList(peers.split(","));
    }

    @Test
    void twoDockerSourcesWithCores_everyCoreGetsOneIdenticalListOfAllCores() {
        var result = runPhase(config("""

                [source.a]
                type = "docker"

                [source.a.core]
                count = 3

                [source.b]
                type = "docker"

                [source.b.core]
                count = 2
                """));

        assertThat(result.isSuccess()).isTrue();
        var cores = role("core");

        assertThat(cores).hasSize(5);

        var allIds = cores.stream().map(r -> r.context().nodeId().or("")).toList();
        var expected = allIds.stream().map(id -> id + ":" + id + ":" + DockerCores.CLUSTER_PORT).toList();

        assertThat(allIds).doesNotHaveDuplicates();
        for (var core : cores) {
            assertThat(entries(core)).as("peers of " + core.context().nodeId().or("")).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(core.context().peers().or("")).isEqualTo(cores.getFirst().context().peers().or(""));
        }
    }

    @Test
    void dockerWorkerOnlySource_besideADockerCoreSource_getsTheCorePeers() {
        var result = runPhase(config("""

                [source.a]
                type = "docker"

                [source.a.core]
                count = 3

                [source.w]
                type = "docker"

                [source.w.worker]
                count = 1
                """));

        assertThat(result.isSuccess()).isTrue();
        assertThat(entries(role("worker").getFirst())).as("the worker lists the three cores of the other source").hasSize(3);
    }

    @Test
    void partialFailure_stopsAtTheFailedCore_andTheResultIsTheFailure() {
        failAt = 2;
        var result = runPhase(config("""

                [source.local]
                type = "docker"

                [source.local.core]
                count = 3

                [source.local.worker]
                count = 1
                """));

        assertThat(result.isFailure()).isTrue();
        assertThat(requests).as("sequential: nothing after the failing core is created").hasSize(2);
        assertThat(entries(requests.getFirst())).as("the survivor still carries the full list").hasSize(3);
    }

    private static final String LOCAL_3_CORES_2_WORKERS = """

            [source.local]
            type = "docker"
            zone = "z1"

            [source.local.core]
            count = 3

            [source.local.worker]
            count = 2
            """;

    /// The ledger must record every provisioned node whatever the id the provider was given: `isDockerCluster` (destroy's published-port gate, #2089)
    /// reads it, and a docker bootstrap whose ledger came out empty brought back "Connection refused for every drain".
    @Test
    void dockerBootstrap_ledgerHoldsOneDockerVmPerNode_andDestroyRecognisesTheCluster() {
        var result = runPhase(config(LOCAL_3_CORES_2_WORKERS));

        assertThat(result.isSuccess()).isTrue();
        var state = result.<BootstrapState> fold(cause -> BootstrapState.initialState(ClusterName.clusterName("dock").unwrap(), "h", "now"), ctx -> ctx.state());
        var vms = state.createdResources()
                       .stream()
                       .filter(CreatedResource.ProvisionedVm.class::isInstance)
                       .map(CreatedResource.ProvisionedVm.class::cast)
                       .toList();

        assertThat(vms).as("one ProvisionedVm per node").hasSize(5);
        assertThat(vms).allMatch(vm -> "docker".equals(vm.provider()) && "local".equals(vm.sourceName()));
        assertThat(vms.stream().filter(vm -> "core".equals(vm.role())).count()).isEqualTo(3);
        assertThat(vms.stream().filter(vm -> "worker".equals(vm.role())).count()).isEqualTo(2);
        assertThat(ClusterDestroyCommand.isDockerCluster(Option.some(state))).as("destroy recognises the docker cluster").isTrue();
    }

    /// The id recorded for a node is the CLI's attribution key (`<source>-<role>-<index>`): deploy, post and the ledger parse it. The container's own
    /// identity travels in the provision context, planned by the CLI for every node (workers included) so the provider mints nothing (#1027).
    @Test
    void provisionedNodes_areKeyedByTheLabel_andTheContextCarriesDistinctPlannedContainerIds() {
        var result = runPhase(config(LOCAL_3_CORES_2_WORKERS));

        assertThat(result.isSuccess()).isTrue();
        List<String> keys = result.<List<String>> fold(cause -> List.of(), ctx -> ctx.nodes().stream().map(node -> node.nodeId()).toList());
        var contextIds = requests.stream().map(request -> request.context().nodeId().or("<none>")).toList();

        assertThat(keys).containsExactly("local-core-0", "local-core-1", "local-core-2", "local-worker-0", "local-worker-1");
        assertThat(keys).allMatch(key -> BootstrapPhaseProvision.belongsTo(key, org.pragmatica.aether.environment.SourceName.sourceNameOrDefault("local")));
        assertThat(contextIds).as("every node, workers included, was handed a planned container id").doesNotContain("<none>").doesNotHaveDuplicates();
        assertThat(contextIds).allMatch(id -> id.startsWith("aether-dock-node-"));
        for (var request : requests) {
            assertThat(request.zone()).isEqualTo("z1");
            assertThat(request.context().provisionedBy()).isEqualTo(ProvisionContext.PROVISIONED_BY_BOOTSTRAP);
        }
    }
}
