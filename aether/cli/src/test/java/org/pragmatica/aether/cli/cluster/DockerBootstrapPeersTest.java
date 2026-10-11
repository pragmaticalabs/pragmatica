// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// #2089: a Docker node boots from its `PEERS` list and aborts with "Self node ... must be in coreNodes" when its own id is not in
/// it. Bootstrap supplied no peers for Docker, so every node fell back to the image's baked list. The core ids are now minted before
/// provisioning and every node is created with the same full `id:host:port` list.
class DockerBootstrapPeersTest {
    private static final String THREE_CORES_ONE_WORKER = """
            config_version = "1.0.0"

            [cluster]
            name = "dock"
            version = "1.0.0"

            [source.local]
            type = "docker"

            [source.local.core]
            count = 3

            [source.local.worker]
            count = 1
            """;

    private final List<ProvisionRequest> requests = new ArrayList<>();
    private final ClusterName cluster = ClusterName.clusterName("dock").unwrap();

    private final ComputeProvider recording = new ComputeProvider() {
        @Override
        public org.pragmatica.aether.environment.ProviderDefaults providerDefaults() {
            return org.pragmatica.aether.environment.ProviderDefaults.providerDefaults("docker", "", "", "", org.pragmatica.lang.Option.empty(), false);
        }

        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            requests.add(request);

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

    @org.junit.jupiter.api.BeforeEach
    void publishedPorts() {
        DockerHostPorts.override = containerId -> org.pragmatica.lang.Result.success(40_000 + Math.floorMod(containerId.hashCode(), 20_000));
    }

    @org.junit.jupiter.api.AfterEach
    void unpublishPorts() {
        DockerHostPorts.override = null;
    }

    private SourceProfile source() {
        return ClusterBootstrapConfigParser.parse(THREE_CORES_ONE_WORKER).unwrap().sources().get("local");
    }

    @Test
    void provisionWithCompute_everyCoreGetsTheFullPeerList_includingItself() {
        var result = BootstrapPhaseProvision.provisionWithCompute(recording, sourceNameOrDefault("local"), source(), cluster);

        assertThat(result.<String> fold(cause -> cause.message(), nodes -> "ok")).isEqualTo("ok");
        var cores = requests.stream().filter(r -> "core".equals(r.context().role())).toList();

        assertThat(cores).as("three cores were created").hasSize(3);

        var coreIds = cores.stream().map(r -> r.context().nodeId().or("")).toList();

        assertThat(coreIds).as("every core carries a pre-minted id").allMatch(id -> id.startsWith("aether-dock-node-"));
        assertThat(coreIds).doesNotHaveDuplicates();

        var expectedEntries = coreIds.stream().map(id -> id + ":" + id + ":" + DockerCores.CLUSTER_PORT).toList();

        for (var core : cores) {
            var peers = core.context().peers().or("");

            assertThat(Arrays.asList(peers.split(","))).as("the full list, own entry included, for " + core.context().nodeId().or(""))
                                                          .containsExactlyInAnyOrderElementsOf(expectedEntries);
            assertThat(peers).as("one list for all: identical text on every core").isEqualTo(cores.getFirst().context().peers().or(""));
        }
    }

    @Test
    void provisionWithCompute_aWorkerGetsTheCorePeerList_notItsOwnEntry() {
        BootstrapPhaseProvision.provisionWithCompute(recording, sourceNameOrDefault("local"), source(), cluster);

        var coreRequest = requests.stream().filter(r -> "core".equals(r.context().role())).findFirst().orElseThrow();
        var worker = requests.stream().filter(r -> "worker".equals(r.context().role())).findFirst().orElseThrow();

        assertThat(worker.context().peers().or("")).as("a non-empty core list, not two empty ones").isNotEmpty();
        assertThat(worker.context().peers().or("")).isEqualTo(coreRequest.context().peers().or(""));
        assertThat(worker.context().nodeId().or("")).as("#1027: a worker is handed a planned id too, so the id recorded for it is the identity it boots with")
                                                    .startsWith("aether-").contains("-node-");
    }

    /// The port in every node's PEERS and the port the provider makes the node listen on are one value: bootstrap tells the provider.
    @Test
    void dockerClusterPort_isHandedToTheProvider_notLeftToItsDefault() {
        var seen = new java.util.ArrayList<org.pragmatica.aether.environment.CloudConfig>();

        ProviderResolver.dockerFactoryOverride = new org.pragmatica.aether.environment.EnvironmentIntegrationFactory() {
            @Override
            public String providerName() {
                return "docker";
            }

            @Override
            public org.pragmatica.lang.Result<org.pragmatica.aether.environment.EnvironmentIntegration> create(org.pragmatica.aether.environment.CloudConfig config) {
                seen.add(config);
                return org.pragmatica.lang.utils.Causes.cause("recorded").result();
            }
        };
        try {
            ProviderResolver.resolveDockerCompute(source());
        } finally {
            ProviderResolver.dockerFactoryOverride = null;
        }

        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst().compute()).containsEntry(ProviderResolver.DOCKER_CLUSTER_PORT_KEY, String.valueOf(DockerCores.CLUSTER_PORT));
        assertThat(DockerCores.CLUSTER_PORT).isEqualTo(org.pragmatica.aether.environment.docker.DockerConfig.dockerConfig().unwrap().clusterPort());
    }
}
