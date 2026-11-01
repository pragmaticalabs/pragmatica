// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2556 verifier: a worker's DHT replication settlement counts its own store as the authority on its report only while its replica is
/// ready (#2553). The guard is wired in `DhtAssembly.dhtSettlement` (`clusterNode::isPassiveClientReady`); the unit tests feed the settlement
/// their own supplier, so only an ASSEMBLED worker node (not started) reaches the wiring. The settlement is taken from the DHT node's
/// caught-up hook (`dhtNode.onReplicationCaughtUp(_ -> dhtSettlement.report())`), whose one captured value it is.
///
/// Mutation: `() -> true` or `() -> false` in place of `clusterNode::isPassiveClientReady` reddens this test.
class DhtSettlementReplicaReadinessWiringTest {
    @TempDir
    Path storageRoot;
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
    void aWorkersSettlement_followsTheEngineReplicaReadiness() throws ReflectiveOperationException {
        node = AetherNode.aetherNode(workerConfig(new NodeId("worker-settle-" + UUID.randomUUID())), () -> {}).unwrap();
        var cluster = (RabiaNode<KVCommand<AetherKey>>) component(node, "clusterNode");
        var inputs = settlementInputs(node);

        assertThat(inputs.replica()).as("precondition: a worker is not a DHT replica, so readiness decides").isFalse();
        assertThat(cluster.isPassiveClientReady()).as("precondition: nothing has set the engine flag").isFalse();
        assertThat(inputs.replicaReady().getAsBoolean()).as("a worker whose replica is not ready does not count its store").isFalse();

        cluster.setPassiveClientReady(true);
        assertThat(inputs.replicaReady().getAsBoolean()).as("it counts its store once the replica readiness publisher sets the flag").isTrue();

        cluster.setPassiveClientReady(false);
        assertThat(inputs.replicaReady().getAsBoolean()).as("and stops when it clears (a resnapshot, a refused feed)").isFalse();
    }

    @SuppressWarnings("unchecked")
    private static DhtReplicationSettlement.Inputs settlementInputs(AetherNode node) throws ReflectiveOperationException {
        var dhtNode = ((Option<DHTNode>) component(node, "dhtNode")).unwrap();
        var hook = ((AtomicReference<Consumer<Long>>) field(dhtNode, "caughtUpListener")).get();

        for (Field captured : hook.getClass().getDeclaredFields()) {
            captured.setAccessible(true);
            if (captured.get(hook) instanceof DhtReplicationSettlement settlement) {
                return (DhtReplicationSettlement.Inputs) component(settlement, "inputs");
            }
        }
        throw new AssertionError("the caught-up hook no longer captures the settlement; reach it another way: " + hook.getClass());
    }

    private AetherNodeConfig workerConfig(NodeId self) {
        return AetherNodeConfig.builder().self(self)
            .coreNodes(List.of(NodeInfo.nodeInfo(self, NodeAddress.nodeAddress("localhost", 6123).unwrap(), Map.of(NodeInfo.LABEL_ROLE, "worker")),
                              NodeInfo.nodeInfo(new NodeId("core-seed"), NodeAddress.nodeAddress("localhost", 6124).unwrap(), Map.of(NodeInfo.LABEL_ROLE, "core"))))
            .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
            .sliceConfig(SliceConfig.sliceConfig())
            .artifactRepo(DHTConfig.FULL).coreMax(1)
            .appHttp(AppHttpConfig.appHttpConfig()).tls(Option.none())
            .quicTls(TlsConfig.selfSignedMutual()).certificateProvider(Option.none())
            .configProvider(Option.none())
            .environment(Option.none()).managementHttpProtocol(HttpProtocol.H1).storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false)).build();
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
