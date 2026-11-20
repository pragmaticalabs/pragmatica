// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.FsmWarningKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.FsmWarningValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.testlogging.LogCapture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #3203: pins the `AetherNode` WIRING of the committed `FsmWarningKey` put into the cluster-event aggregator, the
/// `.onPut(FsmWarningKey.class, eventAggregator::onFsmWarningPut)` route entry. That route is the only publisher of an FSM family
/// warning (rule E), and the aggregator tests (`FsmWarningPutReplayGateTest`, `FsmHarness`) hand-wire it, so a deleted route
/// entry left every node test green (v3443c m3, v3443d m06).
///
/// A real self-forming single-node cluster (the shape of `ClusterEventsCommittedGateBootTest`): this node is the committed
/// leader, so the committed gate lets a fact through and counts it (`committedEmitsPassed`), whether or not the single node's
/// stream then lands it. A live put of a catalogued opener passes the gate once; the control, a put whose code is not
/// catalogued, passes nothing, which shows nothing else raises a committed fact in the window. The same row installed by
/// snapshot and replayed reaches the handler (its suppression is logged with the row's subject) and passes nothing.
class FsmWarningPutRouteWiringBootTest {
    @TempDir
    Path tempDir;

    private static final String AGGREGATOR_LOGGER = ClusterEventAggregator.class.getName();
    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final TimeSpan DELIVERY_BOUND = timeSpan(10).seconds();
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final String CATALOGUED_CODE = "retry-exhausted-unsettled";
    private static final String UNCATALOGUED_CODE = "not-a-catalogued-code";

    private final String subject = "route-boot-" + UUID.randomUUID();
    private AetherNode node;
    private LogCapture logs;

    @BeforeEach
    void setUp() {
        logs = LogCapture.capture(AGGREGATOR_LOGGER, Level.DEBUG, false);
    }

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }

        logs.close();
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void liveCommittedPut_reachesTheAggregator_throughTheNodesRouter_andPassesTheGateOnce() {
        boot();
        commitLeaderKey(node.self(), 500_000L);

        var beforeControl = passed();
        commitRow(FsmWarningKey.fsmWarningKey(UNCATALOGUED_CODE, subject));
        assertThat(passed()).as("control: an uncatalogued code passes nothing, and nothing else raises a committed fact")
                            .isEqualTo(beforeControl);

        var before = passed();
        commitRow(FsmWarningKey.fsmWarningKey(CATALOGUED_CODE, subject));
        assertThat(passed() - before).as("the live FsmWarningKey put is routed to onFsmWarningPut and passes the gate once")
                                     .isEqualTo(1L);
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void replayedCommittedPut_reachesTheAggregator_andPassesNothing() {
        boot();
        commitLeaderKey(node.self(), 500_000L);

        var before = passed();
        restoreSnapshotHoldingRow(FsmWarningKey.fsmWarningKey(CATALOGUED_CODE, subject));
        node.kvStore()
            .replayNotifications();
        awaitDelivery();

        assertThat(logs.messages()).as("control: the replayed put reaches onFsmWarningPut and is suppressed as a replay")
                                   .anyMatch(line -> line.contains("replay in progress") && line.contains(subject));
        assertThat(passed()).as("a replayed put is adopted, never published").isEqualTo(before);
    }

    private long passed() {
        return node.eventAggregator()
                   .committedEmitsPassed();
    }

    private static FsmWarningValue openRow() {
        return FsmWarningValue.fsmWarningValue(Map.of(),
                                               true,
                                               "open",
                                               1L,
                                               new NodeId("core-2"),
                                               new ProcessIncarnation(1_760_000_000_001L, 1L),
                                               1L);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void commitRow(FsmWarningKey key) {
        var kvStore = node.kvStore();
        var leader = kvStore.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                            .unwrap();

        kvStore.process(kvStore.createBatch((List) List.of(transaction(key, leader))));
        awaitDelivery();
    }

    private static KVCommand.LeaderTransaction<AetherKey, AetherValue> transaction(FsmWarningKey key, LeaderValue leader) {
        return new KVCommand.LeaderTransaction<>(key,
                                                 UUID.randomUUID()
                                                     .toString(),
                                                 leader,
                                                 List.of(),
                                                 List.of(new KVCommand.Mutation<>(key, Option.none(), Option.some(openRow()))));
    }

    /// State that reaches the store with no notification: a snapshot of a donor store holding this node as leader and the row.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void restoreSnapshotHoldingRow(FsmWarningKey key) {
        var donor = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), CODEC, CODEC);
        var leader = LeaderValue.leaderValue(node.self(), 2_000_000L);

        donor.process(donor.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, leader))));
        donor.process(donor.createBatch((List) List.of(transaction(key, leader))));
        node.kvStore()
            .restoreSnapshot(donor.makeSnapshot()
                                  .unwrap())
            .onFailure(cause -> fail("restore must succeed: " + cause.message()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void commitLeaderKey(NodeId leader, long viewSequence) {
        var kvStore = node.kvStore();

        kvStore.process(kvStore.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE,
                                                                                LeaderValue.leaderValue(leader, viewSequence)))));
        awaitDelivery();
    }

    /// Waits until every batch the store has applied so far has been delivered to its subscribers (the aggregator among them).
    private void awaitDelivery() {
        var kvStore = node.kvStore();

        kvStore.awaitDelivered(kvStore.enqueuedSeq())
               .await(DELIVERY_BOUND)
               .onFailure(cause -> fail("the committed batch must be delivered: " + cause.message()));
    }

    private void boot() {
        var self = NodeId.nodeId("fsm-warning-route-boot-" + UUID.randomUUID())
                         .unwrap();

        node = AetherNode.aetherNode(minimalConfig(self, tempDir), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed: " + cause.message()));
        awaitBootReplay();
    }

    /// #2550: the engine replays the store's notifications once it is serving, after the start promise resolves; every test
    /// starts once that boot replay is delivered, so a test write is never replayed back by it.
    private void awaitBootReplay() {
        await().atMost(15, SECONDS)
               .untilAsserted(() -> assertThat(node.kvStore()
                                                   .deliveryMetrics()
                                                   .queueHighWaterReplay()).as("the engine enqueues its boot replay")
                                                                           .isPositive());
        awaitDelivery();
    }

    private static AetherNodeConfig minimalConfig(NodeId self, Path storageRoot) {
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());

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
                               .build();
    }
}
