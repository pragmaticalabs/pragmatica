// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.metrics.NodeReportedStateHolder;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// TW-O9 of the worker-replica-feed design (W-OBS), at the level that reaches the real wiring: an ASSEMBLED worker node, not started.
///
/// Today a worker reports `READY` only when its engine's passive-client latch is set AND the projection client says its
/// metadata projection is fresh (`AetherNode`: `runtimeReady(clusterNode) && workerProjectionFreshRef.get()` feeding the
/// node-reported-state holder). The two inputs are independent: the latch is set by `authorizePassiveClient()` (TW-O3), freshness by the
/// projection client. The design replaces the freshness input with `ReplicaReadiness.ready()` and flips this in W4 (the cutover), not W3.
///
/// The holder is not reachable through any accessor, so the test takes it from the supplier the node registers on its collector.
class ReplicaReadinessWiringTripwireTest {
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

    @Test
    @SuppressWarnings("unchecked")
    void workerWithTheLatchSetAndSubsystemsUp_isStillNotReady_whileItsProjectionIsNotFresh_today() throws ReflectiveOperationException {
        node = AetherNode.aetherNode(workerConfig(new NodeId("worker-readiness-" + UUID.randomUUID())), () -> {}).unwrap();
        var channel = (org.pragmatica.aether.worker.metadata.WorkerMetadataChannel) component(node, "workerMetadataChannel");
        var cluster = (RabiaNode<KVCommand<AetherKey>>) component(node, "clusterNode");
        var holder = registeredHolder(node);

        assertThat(channel.hasFreshProjection()).as("precondition: an assembled worker has received no projection").isFalse();
        assertThat(cluster.isPassiveClientReady()).as("precondition: the passive-client latch is not set").isFalse();

        holder.onSubsystemsReady();
        cluster.authorizePassiveClient();

        assertThat(AetherNode.runtimeReady(cluster)).as("the engine side of readiness is satisfied: the latch is set").isTrue();
        assertThat(node.isReady()).as("and the node's own runtime-ready accessor agrees").isTrue();
        assertThat(holder.current()).as("TW-O9: yet the node still reports SYNCING, because the projection is not fresh; readiness is the AND of the two").isEqualTo(NodeReportedState.SYNCING);
    }

    @Disabled("W4 (cutover): enable when readiness is computed by ReplicaReadiness.ready() and set through setPassiveClientReady; assert a worker is ready iff ready() holds")
    @Test
    void workerIsReady_iffReplicaReadinessHolds() {
        assertThat(ReplicaSeam.typeExists("ReplicaReadiness")).as("W3 adds ReplicaReadiness (tag equal, heartbeat not stale, lag bound, role, initial catch-up); W4 makes it the only input to isPassiveClientReady and to the node-reported state").isTrue();
    }

    @SuppressWarnings("unchecked")
    private static NodeReportedStateHolder registeredHolder(AetherNode node) throws ReflectiveOperationException {
        var collector = node.metricsCollector();
        var reference = (AtomicReference<Option<Supplier<NodeReportedState>>>) field(collector, "nodeReportedStateSupplier");
        var supplier = reference.get().unwrap();

        // The registered supplier is the method reference `nodeReportedStateHolder::current`: its one captured value is the holder.
        for (Field captured : supplier.getClass().getDeclaredFields()) {
            captured.setAccessible(true);
            if (captured.get(supplier) instanceof NodeReportedStateHolder holder) {
                return holder;
            }
        }
        throw new AssertionError("the registered supplier no longer captures the holder; reach it another way: " + supplier.getClass());
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
