// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;

import org.pragmatica.utility.warning.OperatorWarningSink;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #1543 E2 — pins what `AetherNode` hands the replacement machinery, through a real booted node: the replacement service is given
/// the node's GENESIS voter set (so an EXTERNAL core replacement under a former voter's identity is refused like CTM's own), and
/// `NodeReplacementWiring.connectReconcilers` ran, so the leader reconciler and the community placement reconciler hold the
/// pairings. Every unit pin drives these inputs by hand; deleting the call or passing an empty set left them all green.
class NodeReplacementWiringBootTest {
    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final NodeId FRESH = new NodeId("fresh-replacement");

    @TempDir
    Path tempDir;

    private AetherNode node;
    private NodeId self;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop().await(timeSpan(10).seconds()).onFailure(cause -> {});
        }

        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void theReplacementService_isGivenTheGenesisVoterSet() {
        node = bootedNode();

        assertThat(((NodeReplacementWiring.Wired) node.nodeReplacementService()).genesisVoters())
            .as("the configured genesis set: a single-node cluster's genesis voter is itself").isEqualTo(Set.of(self));
    }

    /// The ready view a WORKER replacement reads (`readyAll`) is the node's real stable ready set: a booted core is in its own view.
    @Test
    @Timeout(value = 120, unit = SECONDS)
    void theReplacementService_readsTheNodesRealReadyView() {
        node = bootedNode();

        await().atMost(30, SECONDS).alias("the node's own ready view contains the node")
               .untilAsserted(() -> assertThat(((NodeReplacementWiring.Wired) node.nodeReplacementService()).readyAll()).contains(self));
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void connectReconcilers_ran_theReconcilersHoldThePairings() {
        node = bootedNode();
        // A one-node cluster never elects a leader, so the pairing is put straight into the index the node's reconcilers read.
        ((NodeReplacementWiring.Wired) node.nodeReplacementService()).pairings()
                                                                     .put(new AetherKey.NodeReplacementKey(self),
                                                                          new AetherValue.NodeReplacementValue(FRESH, "core", AetherValue.NodeReplacementPhase.JOINING, 0L));
        var leaderReconciler = (LeaderReconciler) accessor(node, "leaderReconciler");
        var placement = ((ClusterTopologyManager) accessor(node, "clusterTopologyManagerInstance")).installedCommunityPlacement().unwrap();

        assertThat(leaderReconciler.surgeReplacementsView()).as("the leader reconciler counts the pairing's CORE replacement as capacity").containsExactly(FRESH);
        assertThat(placement.surgeView()).as("the placement reconciler is told the surge").containsExactly(FRESH);
        assertThat(placement.protectedView()).as("and which nodes a reduction must not remove").containsExactlyInAnyOrder(self, FRESH);
    }

    /// #2062 (M5): the node hands its operator-warning sink to the topology manager, so "the termination of a retired node's instance is not
    /// confirmed" is an operator event and not only a log line. Without the wiring the CTM keeps the log-only sink.
    @Test
    @Timeout(value = 120, unit = SECONDS)
    void theTopologyManager_isWiredToTheNodesOperatorWarningSink() {
        node = bootedNode();

        var sink = ((ClusterTopologyManager) accessor(node, "clusterTopologyManagerInstance")).operatorWarningSink();

        assertThat(sink).as("a hand-off sink to the event aggregator, not the log-only default").isNotSameAs(OperatorWarningSink.logOnly());
    }

    /// #2062: the marks of retired nodes whose termination is unconfirmed are read from the node's replicated store, so a new leader inherits
    /// them. Without the wiring the topology manager reads an empty map and a successor never sees an open mark.
    @Test
    @Timeout(value = 120, unit = SECONDS)
    @SuppressWarnings({"rawtypes", "unchecked"})
    void theTopologyManager_readsTheUnconfirmedMarksFromTheNodesStore() {
        node = bootedNode();
        var mark = new AetherValue.UnconfirmedTerminationValue("provider unreachable", true, false);
        var leader = new org.pragmatica.cluster.state.kvstore.LeaderValue(self, 1);
        var key = new AetherKey.UnconfirmedTerminationKey(FRESH);
        var store = (org.pragmatica.cluster.state.kvstore.KVStore) node.kvStore();

        store.process(store.createBatch(java.util.List.of(new org.pragmatica.cluster.state.kvstore.KVCommand.Put(org.pragmatica.cluster.state.kvstore.LeaderKey.INSTANCE, leader))));
        store.process(store.createBatch(java.util.List.of(new org.pragmatica.cluster.state.kvstore.KVCommand.LeaderTransaction(key,
                                                                                                                             java.util.UUID.randomUUID().toString(),
                                                                                                                             leader,
                                                                                                                             java.util.List.of(),
                                                                                                                             java.util.List.of(new org.pragmatica.cluster.state.kvstore.KVCommand.Mutation<>(key, Option.none(), Option.some(mark)))))));

        var marks = ((ClusterTopologyManager) accessor(node, "clusterTopologyManagerInstance")).unconfirmedMarks();

        assertThat(marks).as("read from the replicated store").containsEntry(FRESH, mark);
    }

    private static Object accessor(AetherNode booted, String name) {
        return Result.lift(() -> {
            var method = booted.getClass().getDeclaredMethod(name);

            method.setAccessible(true);

            return method.invoke(booted);
        }).unwrap();
    }

    /// The EXTERNAL-admission fleet limit is the node's configured `maxNodes`, not a constant: passing 0 or `Integer.MAX_VALUE` for it
    /// leaves every unit test green, because they hand the wiring their own limit.
    @Test
    @Timeout(value = 120, unit = SECONDS)
    void theReplacementService_isWiredToTheConfiguredFleetLimit() {
        self = NodeId.nodeId("replacement-wiring-limit-" + UUID.randomUUID()).unwrap();
        node = AetherNode.aetherNode(minimalConfig(tempDir, self, 7), () -> {})
                         .onFailure(cause -> fail("assembly must succeed: " + cause.message()))
                         .unwrap();

        assertThat(((NodeReplacementWiring.Wired) node.nodeReplacementService()).fleetLimit()).isEqualTo(7);
    }

    private AetherNode bootedNode() {
        self = NodeId.nodeId("replacement-wiring-boot-" + UUID.randomUUID()).unwrap();
        var booted = AetherNode.aetherNode(minimalConfig(tempDir, self, Integer.MAX_VALUE), () -> {})
                               .onFailure(cause -> fail("assembly must succeed: " + cause.message()))
                               .unwrap();

        booted.start().await(START_BOUND).onFailure(cause -> fail("start() must succeed: " + cause.message()));

        return booted;
    }

    private static AetherNodeConfig minimalConfig(Path storageRoot, NodeId self, int maxNodes) {
        var address = nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, address);

        return AetherNodeConfig.builder()
                               .self(self)
                               .coreNodes(List.of(selfInfo))
                               .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                               .sliceConfig(SliceConfig.sliceConfig())
                               .artifactRepo(DHTConfig.FULL)
                               .coreMax(1)
                               .appHttp(AppHttpConfig.appHttpConfig())
                               .tls(Option.none())
                               .quicTls(TlsConfig.selfSignedMutual())
                               .certificateProvider(Option.none())
                               .configProvider(Option.none())
                               .environment(Option.none())
                               .managementHttpProtocol(HttpProtocol.H1)
                               .storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false))
                               .build()
                               .withAutoHeal(AutoHealConfig.DEFAULT.withMaxNodes(maxNodes));
    }
}
