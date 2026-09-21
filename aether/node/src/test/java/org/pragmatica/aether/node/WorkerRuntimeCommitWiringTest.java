// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentContext;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.node.SwitchableClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class WorkerRuntimeCommitWiringTest {
    private AetherNode node;

    @AfterEach void close() {
        if (node != null) { node.stop().await(timeSpan(10).seconds()); }
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void assembledWorkerRuntimeWritersFollowTheForwardingDelegate() throws ReflectiveOperationException {
        var self = new NodeId("worker-commit-wiring-" + UUID.randomUUID());
        var config = AetherNodeConfig.builder().self(self)
            .coreNodes(List.of(NodeInfo.nodeInfo(self, NodeAddress.nodeAddress("localhost", 6123).unwrap(), Map.of(NodeInfo.LABEL_ROLE, "worker")),
                              NodeInfo.nodeInfo(new NodeId("core-seed"), NodeAddress.nodeAddress("localhost", 6124).unwrap(), Map.of(NodeInfo.LABEL_ROLE, "core"))))
            .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
            .sliceConfig(org.pragmatica.aether.config.SliceConfig.sliceConfig())
            .artifactRepo(org.pragmatica.dht.DHTConfig.FULL).coreMax(1)
            .appHttp(AppHttpConfig.appHttpConfig()).tls(Option.none())
            .quicTls(TlsConfig.selfSignedMutual()).certificateProvider(Option.none())
            .configProvider(Option.none()).environment(Option.none()).build();
        node = AetherNode.aetherNode(config, () -> {}).unwrap();
        var switchable = (SwitchableClusterNode<KVCommand<AetherKey>>) component(node, "switchableCluster");
        assertThat(switchable.current()).as("first metadata LOAD must already use forwarding before activation")
            .isInstanceOf(org.pragmatica.cluster.node.ForwardingClusterNode.class);
        var context = (NodeDeploymentContext) field(component(node, "nodeDeploymentManager"), "ctx");
        var publisherCluster = (ClusterNode<KVCommand<AetherKey>>) field(context.httpRoutePublisher().unwrap(), "cluster");
        var schedulerContext = component(component(node, "scheduledTaskManager"), "ctx");
        var schedulerWriter = (Consumer<KVCommand<AetherKey>>) field(schedulerContext, "stateWriter");
        var committed = new ArrayList<List<KVCommand<AetherKey>>>();
        var direct = switchable.current();
        switchable.switchTo(new ClusterNode<>() {
            public NodeId self() { return direct.self(); }
            public TopologyManager topologyManager() { return direct.topologyManager(); }
            public Promise<Unit> start() { return Promise.unitPromise(); }
            public Promise<Unit> stop() { return Promise.unitPromise(); }
            public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
                committed.add(commands);
                return Promise.success(List.of());
            }
        });
        var runtimeCursors = (Option<?>) field(component(node, "streamConsumerRuntime"), "cursorStore");
        var projectionCursors = (org.pragmatica.aether.node.projection.ProjectionAwareCursorStore) runtimeCursors.unwrap();
        var clusterCursors = (org.pragmatica.aether.node.stream.ClusterCursorStore) projectionCursors.delegate();
        var command = new KVCommand.Noop<AetherKey>(AetherKey.ClusterConfigKey.CURRENT);
        assertThat(context.cluster().apply(List.of(command)).await().isSuccess()).isTrue();
        assertThat(publisherCluster.apply(List.of(command)).await().isSuccess()).isTrue();
        schedulerWriter.accept(command);
        assertThat(clusterCursors.commandWriter().apply(List.of(command)).await().isSuccess()).isTrue();
        assertThat(committed).containsExactly(List.of(command), List.of(command), List.of(command), List.of(command));
    }

    @Test
    void stagedCoreReadinessDoesNotAdmitWorkloadBeforeVoterInstallation() {
        var installed = new NodeId("installed-core");
        var staged = new NodeId("staged-core");
        var counted = java.util.Set.of(installed, staged);
        assertThat(AetherNode.installedCorePlacementMembers(counted, java.util.Set.of(installed)))
            .containsExactly(installed);
        assertThat(AetherNode.installedCorePlacementMembers(counted, counted))
            .containsExactlyInAnyOrder(installed, staged);
        var unavailable = org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.MEMBERSHIP_NOT_WIRED;
        assertThat(AetherNode.installedCorePlacementMembers(unavailable, counted)).isSameAs(unavailable);
    }

    private static Object component(Object instance, String name) throws ReflectiveOperationException {
        var method = instance.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(instance);
    }

    private static Object field(Object instance, String name) throws ReflectiveOperationException {
        var field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }
}
