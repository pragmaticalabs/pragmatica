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
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.replica.ReplicaFeedClient;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaEntries;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaHeartbeat;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaRefused;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotChunk;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotRequest;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSubscribe;
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

/// D9 / N-6, flipped by W4 (the cutover): `ReplicaCutover` and its factory are deleted, so every assembled worker holds the replica client and
/// routes the four messages it receives, the engine's passive-client latch (`authorizePassiveClient`) is gone, and the metadata projection no
/// longer drives readiness. The W3 dark assertions (production factory is `DARK`, no client, the latch is the live setter) are removed with the
/// type they asserted; the governor-first rule ("no worker subscribes to a core while an announced, serving governor exists") is
/// `ReplicaFeedClientRelayTest.aMemberWithAnAnnouncedServingGovernor_neverSubscribesToACore`.
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

    /// Mutation: construct no client (`ReplicaClientAssembly.replicaClient` returns empty for a worker), or drop a route.
    @Test
    @SuppressWarnings("unchecked")
    void aWorker_holdsTheClient_andRoutesTheFourMessagesItReceives_andTheTwoItServes() throws ReflectiveOperationException {
        var router = MessageRouter.DelegateRouter.delegate();

        node = boot(router);

        var client = (Option<ReplicaFeedClient<?, ?>>) component(node, "replicaClient");

        assertThat(client.isPresent()).isTrue();
        assertThat(routed(router)).contains(CLIENT_ROUTES.toArray(new Class<?>[0]));
        assertThat(routed(router)).as("a governor serves members on a worker too").contains(ReplicaSubscribe.class, ReplicaSnapshotRequest.class);
    }

    /// I-W6 / the latch is gone. Mutation: restore `authorizePassiveClient` on `RabiaNode`.
    @Test
    void theEngineLatch_isGone() {
        assertThat(java.util.Arrays.stream(RabiaNode.class.getMethods()).map(java.lang.reflect.Method::getName)).doesNotContain("authorizePassiveClient");
    }

    /// The projection client's fresh callback no longer sets the engine flag. Mutation: restore the latch call at the `workerMetadataChannel(...)`
    /// call site.
    @Test
    @SuppressWarnings("unchecked")
    void theProjectionClient_noLongerDrivesTheEngineReadiness() throws ReflectiveOperationException {
        node = boot(MessageRouter.DelegateRouter.delegate());

        var channel = (org.pragmatica.aether.worker.metadata.WorkerMetadataChannel) component(node, "workerMetadataChannel");
        var cluster = (RabiaNode<KVCommand<AetherKey>>) component(node, "clusterNode");
        var callback = (Runnable) field(channel.client(), "projectionReady");

        callback.run();

        assertThat(cluster.isPassiveClientReady()).isFalse();
    }

    /// The engine seams, through the real forwarding `RabiaNode` of an assembled worker: `setPassiveClientReady` sets and clears the flag and
    /// `completePassiveRestore` fires the post-restore listeners. Mutation: drop either forward in `RabiaNode` (return `Unit.unit()` without
    /// delegating to the engine).
    @Test
    @SuppressWarnings("unchecked")
    void rabiaNode_forwardsTheTwoEngineSeams() throws ReflectiveOperationException {
        node = boot(MessageRouter.DelegateRouter.delegate());

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

    private AetherNode boot(MessageRouter.DelegateRouter router) {
        return AetherNode.aetherNode(workerConfig(new NodeId("worker-gate-" + UUID.randomUUID())),
                                     router,
                                     NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()),
                                     () -> {},
                                     () -> {},
                                     () -> {},
                                     variable -> Option.none(),
                                     () -> Result.success(Unit.unit()))
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
