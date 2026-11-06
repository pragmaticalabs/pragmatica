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
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.node.assembly.DhtAssembly;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2553: the readiness input of the DHT replication settlement IS the engine's passive-client flag, the one a worker's
/// replica readiness publishes. The unit tests of the settlement pin what it does with the input; this one pins that
/// production wiring (`DhtAssembly.dhtSettlement`) hands it the real flag, reached on an ASSEMBLED worker node (not started).
///
/// Mutation: pass `() -> true` or `() -> false` instead of `clusterNode::isPassiveClientReady` in `DhtAssembly`.
@Timeout(60)
class DhtSettlementReadinessWiringTest {
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
    void settlementReadiness_followsTheEnginesPassiveClientFlag() throws ReflectiveOperationException {
        node = AetherNode.aetherNode(workerConfig(new NodeId("worker-settlement-" + UUID.randomUUID())), () -> {}).unwrap();
        var cluster = (RabiaNode<KVCommand<AetherKey>>) component(node, "clusterNode");
        var settlement = DhtAssembly.dhtSettlement(new DhtAssembly.DhtSettlementInputs((AetherNodeConfig) component(node, "config"),
                                                                                       (MessageRouter.DelegateRouter) component(node, "router"),
                                                                                       (KVStore<AetherKey, AetherValue>) component(node, "kvStore"),
                                                                                       cluster,
                                                                                       node.dhtNode().unwrap(),
                                                                                       new AtomicReference<>(),
                                                                                       (org.pragmatica.cluster.node.SwitchableClusterNode<KVCommand<AetherKey>>) component(node, "switchableCluster")))
                                   .dhtSettlement();
        var inputs = (DhtReplicationSettlement.Inputs) field(settlement, "inputs");

        assertThat(cluster.isPassiveClientReady()).as("precondition: nothing has set the engine flag").isFalse();
        assertThat(inputs.replicaReady().getAsBoolean()).as("not ready while the engine flag is clear").isFalse();

        cluster.setPassiveClientReady(true);
        assertThat(inputs.replicaReady().getAsBoolean()).as("ready once the replica readiness publishes the flag").isTrue();
        cluster.setPassiveClientReady(false);
        assertThat(inputs.replicaReady().getAsBoolean()).as("and it follows the flag back").isFalse();
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
