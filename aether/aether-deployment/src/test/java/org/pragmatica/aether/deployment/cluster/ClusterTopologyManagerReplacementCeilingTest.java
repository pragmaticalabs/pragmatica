// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2811 — the in-flight ceiling of a core replacement is read from the source the replacement is dispatched to: the source
/// [ClusterTopologyManagerRecord#healSource] resolves for the departed node, the same one `provisionReplacement` uses for its
/// zones, instance type and provider. The ceiling used to be the FIRST cloud source declaring the role by name, whichever source
/// the replacing node belonged to. Every case names a source that first-by-name pick gets wrong (`aws` sorts before `hetzner`;
/// a Docker source is never a cloud source). The chain is the production one: committed configuration, source lifecycle manager
/// answering `committedSourceOf`, manager.
class ClusterTopologyManagerReplacementCeilingTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId FROM_AWS = nodeId("node-from-aws").unwrap();
    private static final NodeId FROM_HETZNER = nodeId("node-from-hetzner").unwrap();
    private static final NodeId FROM_DOCKER = nodeId("node-from-docker").unwrap();
    private static final NodeId UNPLACED = nodeId("node-unplaced").unwrap();
    private static final TimeSpan DEFAULT_TEN_MINUTES = timeSpan(10).minutes();

    private static final String HEADER = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"
            """;

    private static final String CLOUD_SOURCE = """

            [source.%s]
            type = "cloud"
            provider = "hetzner"
            credentials = "hcloud-token"
            region = "eu-central"
            %s

            [source.%<s.core]
            count = 1
            """;

    private static final String DOCKER_SOURCE = """

            [source.dock]
            type = "docker"

            [source.dock.core]
            count = 1
            """;

    private final AtomicReference<Function<NodeId, Result<SourceName>>> committedSource =
        new AtomicReference<>(node -> Causes.cause("no placement for " + node.id()).result());
    private final AtomicReference<Option<ClusterConfigValue>> configRef = new AtomicReference<>(Option.none());
    private ClusterTopologyManager manager;

    @BeforeEach
    void setUp() {
        var snapshotSource = new StubSnapshotSource();
        var self = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("10.0.0.1", 6000).unwrap());
        var config = new TopologyConfig(SELF, 3, timeSpan(60).seconds(), timeSpan(1).seconds(), List.of(self));
        var observer = TopologyObserver.topologyObserver(config, quietRouter(), snapshotSource).unwrap();
        var registry = SourceComputeRegistry.sourceComputeRegistry(configRef::get,
                                                                   _ -> Result.success(EnvironmentIntegration.withCompute(unusedProvider())));
        var lifecycle = NodeLifecycleManager.nodeLifecycleManager(registry,
                                                                  node -> committedSource.get().apply(node),
                                                                  Option.none(),
                                                                  Option.none());

        manager = ClusterTopologyManager.clusterTopologyManager(observer,
                                                                lifecycle,
                                                                AutoHealConfig.DEFAULT,
                                                                DeploymentMap.deploymentMap(),
                                                                snapshotSource,
                                                                configRef::get,
                                                                _ -> Promise.success(List.of()),
                                                                () -> AetherValue.ClusterPhase.NORMAL);
    }

    /// Failure (a): two cloud sources declare `core`; the departed node belongs to the second by name.
    @Test
    void twoCloudSources_nodeOfTheLaterSourceDeparts_getsThatSourcesCeiling_notTheFirstByName() {
        seedConfig(cloud("aws", "replacement_ceiling = \"6m\"") + cloud("hetzner", "replacement_ceiling = \"15m\""));
        placeNode(FROM_HETZNER, "hetzner");

        assertThat(ceilingOf(Option.some(FROM_HETZNER))).isEqualTo(timeSpan(15).minutes());
    }

    @Test
    void twoCloudSources_nodeOfTheFirstSourceDeparts_getsThatSourcesCeiling() {
        seedConfig(cloud("aws", "replacement_ceiling = \"6m\"") + cloud("hetzner", "replacement_ceiling = \"15m\""));
        placeNode(FROM_AWS, "aws");

        assertThat(ceilingOf(Option.some(FROM_AWS))).isEqualTo(timeSpan(6).minutes());
    }

    /// Failure (b): Docker and cloud both declare `core`; a Docker replacement must not inherit the cloud source's ceiling.
    @Test
    void dockerAndCloud_dockerNodeDeparts_getsTheTenMinuteDefault_notTheCloudCeiling() {
        seedConfig(DOCKER_SOURCE + cloud("aws", "replacement_ceiling = \"20m\""));
        placeNode(FROM_DOCKER, "dock");

        assertThat(ceilingOf(Option.some(FROM_DOCKER))).isEqualTo(DEFAULT_TEN_MINUTES);
    }

    @Test
    void dockerAndCloud_cloudNodeDeparts_getsTheCloudCeiling() {
        seedConfig(DOCKER_SOURCE + cloud("aws", "replacement_ceiling = \"20m\""));
        placeNode(FROM_AWS, "aws");

        assertThat(ceilingOf(Option.some(FROM_AWS))).isEqualTo(timeSpan(20).minutes());
    }

    /// The source cannot be resolved (several declare the role, the departed node carries none): the longest ceiling over the
    /// declaring sources, so no replacement of any of them is abandoned early.
    @Test
    void healSourceRefuses_cloudSources_theMaximumCeilingOverTheDeclaringSources() {
        seedConfig(cloud("aws", "replacement_ceiling = \"6m\"") + cloud("hetzner", "replacement_ceiling = \"15m\""));

        assertThat(ceilingOf(Option.some(UNPLACED))).isEqualTo(timeSpan(15).minutes());
        assertThat(ceilingOf(Option.none())).isEqualTo(timeSpan(15).minutes());
    }

    /// A Docker source counts in the maximum with its own ten-minute default, though it is no cloud source.
    @Test
    void healSourceRefuses_dockerAndCloud_theMaximumIncludesTheDockerDefault() {
        seedConfig(DOCKER_SOURCE + cloud("aws", "replacement_ceiling = \"7m\""));

        assertThat(ceilingOf(Option.some(UNPLACED))).isEqualTo(DEFAULT_TEN_MINUTES);
    }

    @Test
    void oneSourceDeclaresTheRole_noPlacement_isThatSourcesCeiling() {
        seedConfig(cloud("aws", "replacement_ceiling = \"7m\""));

        assertThat(ceilingOf(Option.none())).isEqualTo(timeSpan(7).minutes());
    }

    @Test
    void noPersistedConfig_isTheTenMinuteDefault() {
        assertThat(ceilingOf(Option.none())).isEqualTo(DEFAULT_TEN_MINUTES);
    }

    @Test
    void unparseablePersistedConfig_isTheTenMinuteDefault() {
        seedConfig("this is [not toml");

        assertThat(ceilingOf(Option.some(FROM_AWS))).isEqualTo(DEFAULT_TEN_MINUTES);
    }

    @Test
    void roleNoSourceDeclares_isTheTenMinuteDefault() {
        seedConfig(cloud("aws", "replacement_ceiling = \"7m\""));

        assertThat(manager.replacementCeiling(NodeRole.WORKER, Option.none())).isEqualTo(DEFAULT_TEN_MINUTES);
    }

    private TimeSpan ceilingOf(Option<NodeId> departed) {
        return manager.replacementCeiling(NodeRole.CORE, departed);
    }

    private static String cloud(String name, String ceiling) {
        return CLOUD_SOURCE.formatted(name, ceiling);
    }

    private void placeNode(NodeId node, String source) {
        committedSource.set(asked -> asked.equals(node)
                                     ? SourceName.sourceName(source)
                                     : Causes.cause("no placement for " + asked.id()).<SourceName> result());
    }

    private void seedConfig(String body) {
        var toml = body.startsWith("config_version")
                   ? body
                   : HEADER + body;

        configRef.set(Option.some(new ClusterConfigValue(Option.some(toml),
                                                         "prod-cluster",
                                                         "1.0.0",
                                                         List.of(),
                                                         3,
                                                         9,
                                                         "test",
                                                         1L,
                                                         System.currentTimeMillis())));
    }

    private static ComputeProvider unusedProvider() {
        return new ComputeProvider() {
            @Override
            public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
                return EnvironmentError.operationNotSupported("createFrom").promise();
            }

            @Override
            public Promise<Unit> terminate(InstanceId id) {
                return EnvironmentError.operationNotSupported("terminate").promise();
            }

            @Override
            public Promise<List<InstanceInfo>> listInstances() {
                return EnvironmentError.operationNotSupported("listInstances").promise();
            }

            @Override
            public Promise<InstanceInfo> instanceStatus(InstanceId id) {
                return EnvironmentError.operationNotSupported("instanceStatus").promise();
            }
        };
    }

    private static MessageRouter.MutableRouter quietRouter() {
        var router = MessageRouter.mutable();

        router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});

        return router;
    }

    private static final class StubSnapshotSource implements GenerationSnapshotSource {
        @Override
        public Option<MembershipView> currentMembershipView() {
            return Option.none();
        }

        @Override
        public long observedRabiaTerm() {
            return 0L;
        }
    }
}
