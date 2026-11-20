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
import org.pragmatica.aether.deployment.cluster.fsm2.Warnings;
import org.pragmatica.aether.deployment.cluster.fsm2.Warnings.Verdict;
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
///
/// #3503: this node is also the cluster FSM's leader, and its `Warnings.difference` closes a mirrored open row whose family hold test
/// refuses the subject. The close is a live committed put whose recovery notice passes the gate, at a time the test does not control. So
/// the rows these tests count use a family whose hold test admits every subject (`worker-absence-unjudgeable`), which the leader keeps
/// open and writes nothing that passes the gate; the orphan case is pinned on its own, by waiting for the close itself. The fixture's
/// family choice is pinned directly, by the hold test's verdict for each code (`fixtureCodes_...`), with no wait.
class FsmWarningPutRouteWiringBootTest {
    @TempDir
    Path tempDir;

    private static final String AGGREGATOR_LOGGER = ClusterEventAggregator.class.getName();
    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final TimeSpan DELIVERY_BOUND = timeSpan(10).seconds();
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    /// A catalogued opener whose family hold test admits every subject, so the leader keeps the row open and writes nothing that passes the gate.
    private static final String CATALOGUED_CODE = "worker-absence-unjudgeable";
    /// A catalogued opener whose hold test refuses a subject that is not an artifact: the leader closes the row (#3503).
    private static final String ORPHANED_CODE = "retry-exhausted-unsettled";
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

    /// #3503: a replayed row the leader cannot hold (its hold test refuses the subject) is adopted without a publish, then closed by the
    /// leader's own WARNINGS write. That close is a live commit, and its recovery notice is the one publish; waiting for the closed row
    /// makes the count deterministic. Before #3503 the replay test used this row and read the counter while the close could still land.
    @Test
    @Timeout(value = 120, unit = SECONDS)
    void replayedOrphanedPut_isClosedByTheLeader_andOnlyTheClosePasses() {
        boot();
        commitLeaderKey(node.self(), 500_000L);

        var key = FsmWarningKey.fsmWarningKey(ORPHANED_CODE, subject);
        var before = passed();
        restoreSnapshotHoldingRow(key);
        node.kvStore()
            .replayNotifications();
        awaitDelivery();
        awaitClosed(key);
        awaitDelivery();

        assertThat(logs.messages()).as("control: the replayed put reaches onFsmWarningPut and is suppressed as a replay")
                                   .anyMatch(line -> line.contains("replay in progress") && line.contains(subject));
        assertThat(passed() - before).as("the replay passes nothing; the leader's close of the orphaned row passes once")
                                     .isEqualTo(1L);
    }

    /// #3503 (m11): what keeps the leader from closing the replay test's row is the family of `CATALOGUED_CODE`, and the counts above pass
    /// on one run even with a family the leader closes. This pins the choice with no wait: the family's hold test, asked about the fixture
    /// subject (not an artifact, so no model is consulted), admits for `CATALOGUED_CODE` and refuses for `ORPHANED_CODE`.
    @Test
    void fixtureCodes_areAFamilyTheLeaderKeepsOpen_andOneItCloses() {
        assertThat(holdVerdict(CATALOGUED_CODE)).as("the leader's hold test admits the replay fixture's subject, so it never closes the row")
                                                .isInstanceOf(Verdict.Admitted.class);
        assertThat(holdVerdict(ORPHANED_CODE)).as("control: the orphan fixture's code is refused, so the leader closes its row")
                                              .isInstanceOf(Verdict.Refused.class);
    }

    private Verdict holdVerdict(String code) {
        return Warnings.TABLE.get(code)
                             .test()
                             .test(null, subject, Warnings.SINGLE, Warnings.SINGLE);
    }

    private void awaitClosed(FsmWarningKey key) {
        await().atMost(30, SECONDS)
               .untilAsserted(() -> assertThat(node.kvStore()
                                                   .getTyped(key, FsmWarningValue.class)
                                                   .map(FsmWarningValue::open)
                                                   .or(true)).as("the leader closes the row it cannot hold")
                                                             .isFalse());
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
