// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator.DefaultConsumerGroupCoordinator;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2626 wiring pin: what `AetherNode` hands the consumer-group coordinator. The coordinator's ordering fence is only
/// as good as these two inputs, and a wrong one fails silently: an unrelated registry never shows the committed
/// assignments, and a constant epoch source stamps every rebalance below a real leader's term, so the applier refuses
/// it. Read from the ASSEMBLED, unstarted node, like `ReplicaFeedBootWiringTest`.
class ConsumerGroupCoordinatorWiringTest {
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
            .coreNodes(List.of(NodeInfo.nodeInfo(self, NodeAddress.nodeAddress("localhost", 6125).unwrap(), Map.of(NodeInfo.LABEL_ROLE, "core")),
                              NodeInfo.nodeInfo(new NodeId("core-seed"), NodeAddress.nodeAddress("localhost", 6126).unwrap(), Map.of(NodeInfo.LABEL_ROLE, "core"))))
            .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
            .sliceConfig(org.pragmatica.aether.config.SliceConfig.sliceConfig())
            .artifactRepo(org.pragmatica.dht.DHTConfig.FULL).coreMax(1)
            .appHttp(AppHttpConfig.appHttpConfig()).tls(Option.none())
            .quicTls(TlsConfig.selfSignedMutual()).certificateProvider(Option.none())
            .configProvider(Option.none())
            .environment(Option.none()).managementHttpProtocol(org.pragmatica.aether.config.HttpProtocol.H1).storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false)).build();
    }

    private AetherNode assemble(NodeId self) {
        return AetherNode.aetherNode(config(self),
                                     MessageRouter.DelegateRouter.delegate(),
                                     NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()),
                                     () -> {}).unwrap();
    }

    /// Mutations: pass `ConsumerGroupRegistry.consumerGroupRegistry()` (a fresh, unrelated registry) to the coordinator
    /// in `AetherNode.assembleNode`; pass `() -> Epoch.ZERO` instead of `epochSources.leaderEpoch()`.
    @Test
    @Timeout(value = 60, unit = SECONDS)
    @SuppressWarnings({"unchecked", "rawtypes"})
    void anAssembledCore_handsTheCoordinatorItsOwnRegistry_andTheLeaderEpochSource() {
        var self = new NodeId("cg-wiring-" + UUID.randomUUID());

        node = assemble(self);

        var coordinator = (DefaultConsumerGroupCoordinator) node.consumerGroupCoordinator();
        var store = node.kvStore();

        assertThat(coordinator.registry()).as("the registry the committed assignments are mirrored into")
                                          .isSameAs(node.consumerGroupRegistry());

        store.process(store.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, LeaderValue.leaderValue(self, 7L)))));
        KvDelivery.awaitDelivered(store);

        assertThat(coordinator.leaderEpoch().get().rabiaTerm()).as("the term follows the committed election that named this node")
                                                               .isEqualTo(7L);
    }
}
