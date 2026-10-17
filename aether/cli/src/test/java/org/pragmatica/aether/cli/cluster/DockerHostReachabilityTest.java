// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2089: the operator's CLI is outside the docker network, where a container name does not resolve and a bridge IP is not reachable on
/// Docker Desktop. A docker node is reached through its published management port, which differs per node, so no probe may assume the one
/// configured management port. Real HTTP servers on distinct ephemeral ports stand in for the nodes: a probe that uses the configured port
/// (or the same port for every node) reaches none of them.
class DockerHostReachabilityTest {
    private static final int UNUSED_CONFIGURED_PORT = 1;
    private final List<HttpServer> servers = new ArrayList<>();

    @BeforeEach
    void noRealDocker() {
        DockerHostPorts.override = containerId -> Result.success(40_000 + Math.floorMod(containerId.hashCode(), 20_000));
    }

    @AfterEach
    void stop() {
        servers.forEach(server -> server.stop(0));
        DockerHostPorts.override = null;
    }

    private HttpServer node(AtomicInteger hits, String healthBody) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            var body = (exchange.getRequestURI().getPath().equals("/api/v1/health") ? healthBody : "{}").getBytes(StandardCharsets.UTF_8);

            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        servers.add(server);

        return server;
    }

    private static NodeAddress addressOf(String id, HttpServer server) {
        return NodeAddress.nodeAddress(id, "127.0.0.1", Option.none(), Option.some(server.getAddress().getPort()));
    }

    @Test
    void waitForHealth_pollsEachNodeOnItsOwnPublishedPort() throws IOException {
        var hitsA = new AtomicInteger();
        var hitsB = new AtomicInteger();
        var addresses = List.of(addressOf("a", node(hitsA, "{}")), addressOf("b", node(hitsB, "{}")));

        var result = BootstrapPhaseFormation.waitForHealth(addresses, UNUSED_CONFIGURED_PORT, 3_000L, "http");

        assertThat(result.isSuccess()).isTrue();
        assertThat(hitsA.get()).as("node a was probed on its own port").isPositive();
        assertThat(hitsB.get()).as("node b was probed on its own port").isPositive();
    }

    @Test
    void waitForQuorum_readsTheClusterViewFromTheFirstNodesOwnPort() throws IOException {
        var hits = new AtomicInteger();
        var first = node(hits, "{\"quorum\":true,\"nodeCount\":3}");
        var addresses = List.of(addressOf("a", first), addressOf("b", node(new AtomicInteger(), "{}")));

        var result = BootstrapPhaseFormation.waitForQuorum(addresses, UNUSED_CONFIGURED_PORT, 3_000L, 3, "http", Option.none());

        assertThat(result.isSuccess()).isTrue();
        assertThat(hits.get()).isPositive();
    }

    @Test
    void nodesWithoutAnOwnPort_stillUseTheConfiguredManagementPort() throws IOException {
        var hits = new AtomicInteger();
        var server = node(hits, "{}");
        var plain = NodeAddress.nodeAddress("c", "127.0.0.1", Option.none());

        assertThat(plain.managementHostPort(server.getAddress().getPort())).isEqualTo("127.0.0.1:" + server.getAddress().getPort());
        assertThat(BootstrapPhaseFormation.waitForHealth(List.of(plain), server.getAddress().getPort(), 3_000L, "http").isSuccess()).isTrue();
        assertThat(hits.get()).isPositive();
    }

    private static BootstrapContext contextWith(NodeAddress address) {
        var config = ClusterBootstrapConfigParser.parse("""
                                                        config_version = "1.0.0"

                                                        [cluster]
                                                        name = "dock"
                                                        version = "1.0.0"

                                                        [source.d]
                                                        type = "docker"

                                                        [source.d.core]
                                                        count = 3

                                                        [operations.tls]
                                                        auto_generate = false
                                                        """).unwrap();

        return BootstrapContext.bootstrapContext(config,
                                                 BootstrapState.initialState(ClusterName.clusterName("dock").unwrap(), "h", "now"),
                                                 List.of(),
                                                 List.of(address));
    }

    @Test
    void storedAndRegisteredEndpoints_carryTheNodesOwnPort() {
        var ctx = contextWith(NodeAddress.nodeAddress("a", "127.0.0.1", Option.none(), Option.some(51234)));

        assertThat(BootstrapPhaseFormation.buildManagementEndpoint(ctx)).isEqualTo("http://127.0.0.1:51234");
        assertThat(BootstrapPhasePost.managementEndpoint(ctx)).isEqualTo("http://127.0.0.1:51234");
    }

    @Test
    void collect_persistsThePort_andARehydratedRunReadsItBack() {
        var node = ProvisionedNode.provisionedNode("docker-core-0", "cid", "127.0.0.1", Option.some(51234));
        var ctx = contextWith(NodeAddress.nodeAddress("x", "127.0.0.1", Option.none())).withNodes(List.of(node));
        var collected = BootstrapPhaseCollect.execute(ctx).unwrap();

        assertThat(collected.addresses().getFirst().managementPort()).isEqualTo(Option.some(51234));
        assertThat(collected.state().collectedAddresses()).containsExactly("127.0.0.1:51234");
        assertThat(NodeAddress.fromPersisted("docker-core-0", "127.0.0.1:51234").managementPort()).isEqualTo(Option.some(51234));
        assertThat(NodeAddress.fromPersisted("n", "10.0.0.7").managementPort()).as("a bare host has no own port").isEqualTo(Option.none());
        assertThat(NodeAddress.fromPersisted("n", "10.0.0.7").publicIp()).isEqualTo("10.0.0.7");
    }

    /// A resumed run rebuilds its nodes and addresses from the state file, so a docker node's port must come back with them.
    @Test
    void rehydrate_aResumedRunKeepsEachDockerNodesOwnPort() {
        var state = BootstrapState.initialState(ClusterName.clusterName("dock").unwrap(), "h", "now")
                                  .withPhaseStatus(BootstrapPhase.PROVISION, BootstrapState.PhaseStatus.COMPLETED)
                                  .withPhaseStatus(BootstrapPhase.COLLECT_ADDRESSES, BootstrapState.PhaseStatus.COMPLETED)
                                  .withProvisionedNodeIds(List.of("d-core-0", "d-core-1"))
                                  .withCollectedAddresses(List.of("127.0.0.1:51234", "127.0.0.1:51235"));
        var ctx = contextWith(NodeAddress.nodeAddress("x", "127.0.0.1", Option.none())).withState(state);

        var rehydrated = ClusterBootstrapOrchestrator.rehydrateNodes(ctx);

        assertThat(rehydrated.addresses()).extracting(address -> address.managementPort().or(-1)).containsExactly(51234, 51235);
        assertThat(rehydrated.nodes()).extracting(node -> node.managementPort().or(-1)).containsExactly(51234, 51235);
        assertThat(rehydrated.addresses()).extracting(NodeAddress::publicIp).containsOnly("127.0.0.1");
    }

    @Test
    void provisionedDockerNodes_areAddressedByTheirOwnPublishedPort() {
        var compute = new FakeDockerProvider();
        var config = ClusterBootstrapConfigParser.parse("""
                                                        config_version = "1.0.0"

                                                        [cluster]
                                                        name = "dock"
                                                        version = "1.0.0"

                                                        [source.d]
                                                        type = "docker"

                                                        [source.d.core]
                                                        count = 3
                                                        """).unwrap();
        var nodes = BootstrapPhaseProvision.provisionWithCompute(compute,
                                                                 org.pragmatica.aether.environment.SourceName.sourceNameOrDefault("d"),
                                                                 config.sources().get("d"),
                                                                 ClusterName.clusterName("dock").unwrap())
                                            .unwrap();

        assertThat(nodes).hasSize(3);
        assertThat(nodes).allMatch(node -> node.publicIp().equals("127.0.0.1"));
        assertThat(nodes.stream().map(node -> node.managementPort().or(-1)).distinct().count()).as("one port per node").isEqualTo(3);
        for (var node : nodes) {
            assertThat(node.managementPort()).isEqualTo(Option.some(40_000 + Math.floorMod(node.serverId().hashCode(), 20_000)));
        }
    }

    @Test
    void dockerPortOutput_isParsed_firstLineWins_garbageFails() {
        assertThat(DockerHostPorts.parse("0.0.0.0:55001\n[::]:55002\n").or(-1)).isEqualTo(55001);
        assertThat(DockerHostPorts.parse("[::]:55003").or(-1)).isEqualTo(55003);
        assertThat(DockerHostPorts.parse("").isFailure()).isTrue();
        assertThat(DockerHostPorts.parse("no mapping").isFailure()).isTrue();
    }

    @Test
    void dockerProvider_isToldToPublishItsPorts() {
        var seen = new ArrayList<org.pragmatica.aether.environment.CloudConfig>();

        ProviderResolver.dockerFactoryOverride = new org.pragmatica.aether.environment.EnvironmentIntegrationFactory() {
            @Override
            public String providerName() {
                return "docker";
            }

            @Override
            public Result<org.pragmatica.aether.environment.EnvironmentIntegration> create(org.pragmatica.aether.environment.CloudConfig config) {
                seen.add(config);
                return org.pragmatica.lang.utils.Causes.cause("recorded").result();
            }
        };
        try {
            ProviderResolver.resolveDockerCompute(ClusterBootstrapConfigParser.parse("""
                                                                                     config_version = "1.0.0"

                                                                                     [cluster]
                                                                                     name = "dock"
                                                                                     version = "1.0.0"

                                                                                     [source.d]
                                                                                     type = "docker"

                                                                                     [source.d.core]
                                                                                     count = 3
                                                                                     """).unwrap().sources().get("d"));
        } finally {
            ProviderResolver.dockerFactoryOverride = null;
        }

        assertThat(seen.getFirst().compute()).containsEntry(ProviderResolver.DOCKER_EXPOSE_HOST_PORTS_KEY, "true");
    }

    /// Answers every create with a running container whose id is its name.
    private static final class FakeDockerProvider implements org.pragmatica.aether.environment.ComputeProvider {
        @Override
        public org.pragmatica.aether.environment.ProviderDefaults providerDefaults() {
            return org.pragmatica.aether.environment.ProviderDefaults.providerDefaults("docker", "", "", "", Option.empty(), false);
        }

        @Override
        public org.pragmatica.lang.Promise<org.pragmatica.aether.environment.InstanceInfo> createFrom(org.pragmatica.aether.environment.ProvisionRequest request) {
            var name = request.context().nodeId().or("minted");

            return org.pragmatica.aether.environment.InstanceInfo.instanceInfo(org.pragmatica.aether.environment.InstanceId.instanceId(name).unwrap(),
                                                                               org.pragmatica.aether.environment.InstanceStatus.RUNNING,
                                                                               List.of(name),
                                                                               org.pragmatica.aether.environment.InstanceType.ON_DEMAND)
                                                                .async();
        }

        @Override
        public org.pragmatica.lang.Promise<org.pragmatica.lang.Unit> terminate(org.pragmatica.aether.environment.InstanceId instanceId) {
            return org.pragmatica.lang.Promise.success(org.pragmatica.lang.Unit.unit());
        }

        @Override
        public org.pragmatica.lang.Promise<List<org.pragmatica.aether.environment.InstanceInfo>> listInstances() {
            return org.pragmatica.lang.Promise.success(List.of());
        }

        @Override
        public org.pragmatica.lang.Promise<org.pragmatica.aether.environment.InstanceInfo> instanceStatus(org.pragmatica.aether.environment.InstanceId instanceId) {
            return org.pragmatica.lang.Promise.success(null);
        }
    }
}
