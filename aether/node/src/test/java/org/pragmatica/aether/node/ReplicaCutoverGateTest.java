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
import org.pragmatica.aether.node.assembly.WorkerHierarchyAssembly;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.replica.ReplicaFeedClient;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaEntries;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaHeartbeat;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaRefused;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotChunk;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// D9 / N-6: W3 lands DARK. The production factory returns `DARK`; an assembled DARK worker holds no replica client and routes none of the feed's
/// messages, so it never subscribes, and its readiness is still driven only by the engine latch (`authorizePassiveClient`). A node built `LIVE`
/// through the same factory seam (test code only) holds the client and routes the four messages it receives. W4 deletes the type and the
/// factory and flips this test (dark assertions `@Disabled`, cutover assertions enabled).
class ReplicaCutoverGateTest {
    private static final List<Class<?>> CLIENT_ROUTES = List.of(ReplicaEntries.class,
                                                                ReplicaHeartbeat.class,
                                                                ReplicaRefused.class,
                                                                ReplicaSnapshotChunk.class);

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

    /// Mutation: make `ReplicaCutover.production()` return `LIVE`.
    @Test
    void production_isDark() {
        assertThat(ReplicaCutover.production()).isEqualTo(ReplicaCutover.DARK);
        assertThat(ReplicaCutover.production().isLive()).isFalse();
    }

    /// Mutation: construct the client regardless of the cutover (drop the `cutover.isLive()` test in `ReplicaClientAssembly.replicaClient`); or
    /// route the four client messages unconditionally.
    @Test
    @SuppressWarnings("unchecked")
    void aDarkWorker_holdsNoReplicaClient_routesNoFeedMessage_andKeepsTheLatch() throws ReflectiveOperationException {
        var router = MessageRouter.DelegateRouter.delegate();

        node = boot(router, ReplicaCutover.production());

        assertThat(component(node, "replicaClient")).as("no client is constructed, so no ReplicaSubscribe can ever be sent").isEqualTo(Option.none());
        assertThat(routed(router)).as("none of the messages a subscriber receives has a route").doesNotContainAnyElementsOf(CLIENT_ROUTES);

        var cluster = (RabiaNode<KVCommand<AetherKey>>) component(node, "clusterNode");

        assertThat(cluster.isPassiveClientReady()).as("before the latch").isFalse();
        cluster.authorizePassiveClient();
        assertThat(cluster.isPassiveClientReady()).as("DARK: the latch is still the live setter").isTrue();
    }

    /// Mutation: ignore the cutover in `ReplicaClientAssembly.replicaClient` (never construct), or drop a route.
    @Test
    @SuppressWarnings("unchecked")
    void aLiveWorker_holdsTheClient_andRoutesTheFourMessagesItReceives() throws ReflectiveOperationException {
        var router = MessageRouter.DelegateRouter.delegate();

        node = boot(router, ReplicaCutover.LIVE);

        var client = (Option<ReplicaFeedClient<?, ?>>) component(node, "replicaClient");

        assertThat(client.isPresent()).isTrue();
        assertThat(routed(router)).contains(CLIENT_ROUTES.toArray(new Class<?>[0]));
    }

    /// The two DARK-only behaviors, as values: DARK keeps the latch and the projection tick, LIVE has neither.
    /// Mutation: ignore the cutover in `WorkerHierarchyAssembly.passiveLatch` or `projectionTick`.
    @Test
    @SuppressWarnings("unchecked")
    void passiveLatchAndProjectionTick_runOnlyWhenDark() {
        RabiaNode<KVCommand<AetherKey>> cluster = org.mockito.Mockito.mock(RabiaNode.class);
        var channel = org.mockito.Mockito.mock(org.pragmatica.aether.worker.metadata.WorkerMetadataChannel.class);

        WorkerHierarchyAssembly.passiveLatch(ReplicaCutover.LIVE, cluster).run();
        WorkerHierarchyAssembly.projectionTick(ReplicaCutover.LIVE, channel).run();
        org.mockito.Mockito.verifyNoInteractions(cluster, channel);

        WorkerHierarchyAssembly.passiveLatch(ReplicaCutover.DARK, cluster).run();
        WorkerHierarchyAssembly.projectionTick(ReplicaCutover.DARK, channel).run();
        org.mockito.Mockito.verify(cluster).authorizePassiveClient();
        org.mockito.Mockito.verify(channel).tick();
    }

    /// The engine seams, through the real forwarding `RabiaNode` of an assembled worker: `setPassiveClientReady` sets and clears the flag and
    /// `completePassiveRestore` fires the post-restore listeners. Mutation: drop either forward in `RabiaNode` (return `Unit.unit()` without
    /// delegating to the engine).
    @Test
    @SuppressWarnings("unchecked")
    void rabiaNode_forwardsTheTwoEngineSeams() throws ReflectiveOperationException {
        node = boot(MessageRouter.DelegateRouter.delegate(), ReplicaCutover.production());

        var cluster = (RabiaNode<KVCommand<AetherKey>>) component(node, "clusterNode");
        var fired = new java.util.concurrent.atomic.AtomicInteger();

        cluster.onStateRestored(fired::incrementAndGet);
        cluster.setPassiveClientReady(true);
        assertThat(cluster.isPassiveClientReady()).isTrue();
        cluster.setPassiveClientReady(false);
        assertThat(cluster.isPassiveClientReady()).as("the seam clears, unlike the latch").isFalse();
        cluster.completePassiveRestore();
        assertThat(fired.get()).as("the post-restore listeners fire once per install").isEqualTo(1);
    }

    private AetherNode boot(MessageRouter.DelegateRouter router, ReplicaCutover cutover) {
        return AetherNode.aetherNode(workerConfig(new NodeId("worker-gate-" + UUID.randomUUID())),
                                     router,
                                     NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()),
                                     () -> {},
                                     () -> {},
                                     () -> {},
                                     variable -> Option.none(),
                                     () -> Result.success(Unit.unit()),
                                     cutover)
                       .unwrap();
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<Class<?>> routed(MessageRouter.DelegateRouter router) throws ReflectiveOperationException {
        return ((Map<Class<?>, ?>) component(field(router, "delegate"), "routingTable")).keySet();
    }

    private AetherNodeConfig workerConfig(NodeId self) {
        return AetherNodeConfig.builder().self(self)
            .coreNodes(List.of(NodeInfo.nodeInfo(self, NodeAddress.nodeAddress("localhost", 6123).unwrap(), Map.of(NodeInfo.LABEL_ROLE, "worker")),
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
}
