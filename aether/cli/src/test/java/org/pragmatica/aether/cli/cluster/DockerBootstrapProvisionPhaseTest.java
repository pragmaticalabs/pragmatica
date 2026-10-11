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

    @Test
    void provisionedNodes_areIdentifiedByTheIdTheProviderWasGiven_zoneAndProvisionedByAreStamped() {
        var result = runPhase(config("""

                [source.local]
                type = "docker"
                zone = "z1"

                [source.local.core]
                count = 3

                [source.local.worker]
                count = 2
                """));

        assertThat(result.isSuccess()).isTrue();
        List<String> ids = result.<List<String>> fold(cause -> List.of(), ctx -> ctx.nodes().stream().map(node -> node.nodeId()).toList());
        var contextIds = requests.stream().map(request -> request.context().nodeId().or("<none>")).toList();

        assertThat(contextIds).as("every node, workers included, was handed a planned id").doesNotContain("<none>").doesNotHaveDuplicates();
        assertThat(ids).as("#1027: the id recorded for a node is the id the provider was given - one identity, not a second label scheme")
                       .containsExactlyElementsOf(contextIds);
        for (var request : requests) {
            assertThat(request.zone()).isEqualTo("z1");
            assertThat(request.context().provisionedBy()).isEqualTo(ProvisionContext.PROVISIONED_BY_BOOTSTRAP);
        }
    }
}
