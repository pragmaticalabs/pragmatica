// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaHeartbeat;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotRequest;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSubscribe;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.SubscribeMode;
import org.pragmatica.cluster.state.kvstore.IncarnationTag;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// What an ASSEMBLED, unstarted core wires for the replica feed (W-OBS W2): the two inbound feed routes and the cluster directory's content
/// sources. The routes are read from the routing table the node installed into the delegate router.
class ReplicaFeedBootWiringTest {
    private static final NodeId PEER = new NodeId("worker-1");

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path storageRoot;
    private AetherNode node;

    @AfterEach
    void close() {
        if (node != null) {
            node.stop().await(timeSpan(10).seconds());
        }
        ConfigService.clear();
        ResourceProvider.clear();
    }

    private AetherNodeConfig config(NodeId self) {
        return AetherNodeConfig.builder().self(self)
            .coreNodes(List.of(NodeInfo.nodeInfo(self, NodeAddress.nodeAddress("localhost", 6123).unwrap(), Map.of(NodeInfo.LABEL_ROLE, "core")),
                              NodeInfo.nodeInfo(new NodeId("core-seed"), NodeAddress.nodeAddress("localhost", 6124).unwrap(), Map.of(NodeInfo.LABEL_ROLE, "core"))))
            .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
            .sliceConfig(org.pragmatica.aether.config.SliceConfig.sliceConfig())
            .artifactRepo(org.pragmatica.dht.DHTConfig.FULL).coreMax(1)
            .appHttp(AppHttpConfig.appHttpConfig()).tls(Option.none())
            .quicTls(TlsConfig.selfSignedMutual()).certificateProvider(Option.none())
            .configProvider(Option.none())
            .environment(Option.none()).managementHttpProtocol(org.pragmatica.aether.config.HttpProtocol.H1).storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false)).build();
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

    /// Mutations: drop the `ReplicaFeedAssembly.replicaFeed(...)` call of `WorkerHierarchyAssembly`; drop either route; drop the
    /// `setDirectorySources(...)` call of `MembershipAssembly`.
    @Test
    @SuppressWarnings("unchecked")
    void anAssembledCore_routesTheFeedRequests_andWiresTheDirectorySources() throws ReflectiveOperationException {
        var router = MessageRouter.DelegateRouter.delegate();

        node = AetherNode.aetherNode(config(new NodeId("feed-boot-" + UUID.randomUUID())),
                                     router,
                                     NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()),
                                     () -> {}).unwrap();

        var routed = ((Map<Class<?>, ?>) component(field(router, "delegate"), "routingTable")).keySet();

        assertThat(routed).as("the subscribe and snapshot requests reach the feed server").contains(ReplicaSubscribe.class, ReplicaSnapshotRequest.class);
        assertThat(routed).as("control: a message only a subscriber receives has no route on a core").doesNotContain(ReplicaHeartbeat.class);

        var context = field(component(node, "metricsScheduler"), "context");

        assertThat(((AtomicReference<Option<?>>) field(context, "directoryProducer")).get().isPresent())
            .as("the leader's directory content sources are wired into the ClusterSync context")
            .isTrue();
    }

    /// The pong path's sender gate asks the node's governor lookup (C-4). The collector starts with a gate that accepts no governor; the
    /// assembly replaces it with its own lookup, so the wired predicate is a lambda of the assembly, not of the collector.
    /// Mutation: drop the `setDirectoryGovernor(...)` call of `MembershipAssembly`.
    @Test
    @SuppressWarnings("unchecked")
    void anAssembledCore_replacesTheCollectorsDefaultGovernorGate() throws ReflectiveOperationException {
        node = AetherNode.aetherNode(config(new NodeId("feed-boot-" + UUID.randomUUID())),
                                     MessageRouter.DelegateRouter.delegate(),
                                     NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()),
                                     () -> {}).unwrap();

        var gate = ((AtomicReference<?>) field(node.metricsCollector(), "ownGovernor")).get();

        assertThat(gate.getClass().getName()).as("the gate is the assembly's lookup").contains("MembershipAssembly");
    }
}
