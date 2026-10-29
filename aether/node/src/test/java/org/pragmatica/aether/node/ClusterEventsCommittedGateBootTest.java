// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.aether.slice.kvstore.AetherValue;
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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// v-2500 T2 (#1671): pins the PRODUCTION WIRING of the committed gate of the KV-derived cluster events. The unit tests drive the aggregator
/// directly, so deleting the `bindCommittedOwnerSources` call in `ClusterEventsAssembly`, or either route that feeds the aggregator the
/// leader and the removal of the ownership record in `AetherNode`, reddened nothing.
///
/// A real single-node boot (the shape of `LeaderKeyCommitWiringBootTest`). The committed leader names ANOTHER node, so the committed gate must
/// keep this node from raising the fact. Two paths: the leader arrives as a notification (the route), and the leader is already in the store
/// when no notification was delivered (the store lookup the assembly binds -- state restored from a snapshot is delivered only by a later replay).
/// The control raises the same fact first, with nothing committed, so the gate is what holds it back.
class ClusterEventsCommittedGateBootTest {
    @TempDir
    Path tempDir;

    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final NodeId OTHER = new NodeId("committed-leader-elsewhere");

    private static final org.pragmatica.serialization.SliceCodec CODEC = NodeCodecs.nodeCodecs(org.pragmatica.serialization.FrameworkCodecs.frameworkCodecs());

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }

        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void bootedNode_doesNotRaiseAKvDerivedFact_whenTheCommittedLeaderNotificationNamesAnotherNode() throws Exception {
        boot();
        commitLeaderKey(node.self(), 500_000L);
        assertThat(raisedBy("control")).as("control: the committed leader is this node, so it raises the fact").isTrue();

        commitLeaderKey(OTHER, 1_000_000L);

        assertThat(raisedBy("after-leader-notification")).as("the leader notification route reaches the aggregator").isFalse();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void bootedNode_doesNotRaiseAKvDerivedFact_whenTheCommittedLeaderIsOnlyInTheStore() {
        boot();
        restoreSnapshotNaming(node.self());
        assertThat(raisedBy("control")).as("control: the stored leader is this node, so it raises the fact").isTrue();

        restoreSnapshotNaming(OTHER);

        assertThat(node.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).map(LeaderValue::leader).or(new NodeId("none")))
            .as("control: the leader is in the store").isEqualTo(OTHER);
        assertThat(raisedBy("after-restore")).as("the lookup bound by the assembly reads it").isFalse();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void bootedNode_followsTheCommittedOwnershipRecord_andItsRemoval() {
        boot();
        commitLeaderKey(node.self(), 500_000L);
        var record = StreamPartitionOwnershipKey.streamPartitionOwnershipKey(SystemStreams.CLUSTER_EVENTS.asString(), 0);
        var epoch = Epoch.epoch(0L, 7, 3);
        var named = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(OTHER, epoch, epoch.localCounter(), HlcTimestamp.ZERO);

        assertThat(raisedBy("control")).as("control: no record, the committed leader is this node").isTrue();

        commitAsLeader(record, Option.none(), Option.some(named));
        assertThat(node.kvStore().getTyped(record, StreamPartitionOwnershipValue.class).isPresent()).as("control: the record is committed").isTrue();
        assertThat(raisedBy("record-names-another")).as("the record outranks the leader (ownership route and lookup)").isFalse();

        commitAsLeader(record, Option.some(named), Option.none());
        assertThat(node.kvStore().getTyped(record, StreamPartitionOwnershipValue.class).isPresent()).as("control: the record is gone").isFalse();
        assertThat(raisedBy("record-removed")).as("the removal route clears the owner this node holds").isTrue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void commitAsLeader(AetherKey key, Option<AetherValue> expected, Option<AetherValue> value) {
        var kvStore = node.kvStore();
        var leader = kvStore.getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap();

        kvStore.process(kvStore.createBatch((List) List.of(new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                                                 UUID.randomUUID().toString(),
                                                                                                                 leader,
                                                                                                                 List.of(),
                                                                                                                 List.of(new KVCommand.Mutation<>(key, expected, value))))));
    }

    /// What the node holds is what the notifications delivered, in commit order, not what the store holds now: a store that is AHEAD of
    /// the notifications (state restored without a notification) does not move the gate until a notification says so.
    @Test
    @Timeout(value = 120, unit = SECONDS)
    void bootedNode_gatesOnTheLastLeaderNotification_notOnAStoreThatIsAhead() {
        boot();
        commitLeaderKey(OTHER, 1_000_000L);
        restoreSnapshotNaming(node.self());

        assertThat(raisedBy("store-ahead")).as("the leader route holds what the notification delivered").isFalse();
    }

    /// The ownership record that is only in the store (restored without a notification) is read by the lookup the assembly binds, for
    /// partition 0 of the cluster-events stream, and outranks the stored leader (this node).
    @Test
    @Timeout(value = 120, unit = SECONDS)
    void bootedNode_readsTheStoredOwnershipRecord_ofTheClusterEventsPartition() {
        boot();
        restoreSnapshotNaming(node.self());
        assertThat(raisedBy("control")).as("control: the stored leader is this node, no record, so it raises the fact").isTrue();

        restoreSnapshotWithOwner(node.self(), OTHER);

        assertThat(raisedBy("stored-record")).as("the stored record names another node").isFalse();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void restoreSnapshotWithOwner(NodeId leader, NodeId owner) {
        var donor = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), CODEC, CODEC);
        var key = StreamPartitionOwnershipKey.streamPartitionOwnershipKey(SystemStreams.CLUSTER_EVENTS.asString(), 0);
        var epoch = Epoch.epoch(0L, 7, 3);
        var leaderValue = LeaderValue.leaderValue(leader, 3_000_000L);

        donor.process(donor.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, leaderValue))));
        donor.process(donor.createBatch((List) List.of(new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                                             UUID.randomUUID().toString(),
                                                                                                             leaderValue,
                                                                                                             List.of(),
                                                                                                             List.of(new KVCommand.Mutation<>(key,
                                                                                                                                              Option.none(),
                                                                                                                                              Option.some(StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner,
                                                                                                                                                                                                                      epoch,
                                                                                                                                                                                                                      epoch.localCounter(),
                                                                                                                                                                                                                      HlcTimestamp.ZERO))))))));
        node.kvStore().restoreSnapshot(donor.makeSnapshot().unwrap()).onFailure(cause -> fail("restore must succeed: " + cause.message()));
        assertThat(node.kvStore().getTyped(key, StreamPartitionOwnershipValue.class).map(StreamPartitionOwnershipValue::owner).or(leader))
            .as("control: the record is in the store").isEqualTo(owner);
    }

    private void boot() {
        var self = NodeId.nodeId("committed-gate-boot-" + UUID.randomUUID()).unwrap();

        node = AetherNode.aetherNode(minimalConfig(self, tempDir), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed: " + cause.message()));
    }

    /// Whether the committed gate let the fact through to the publish path: raised through `emitCommitted`, and counted as accepted by the
    /// redelivery stage (whether or not the lone node's stream lands it).
    private boolean raisedBy(String marker) {
        var before = accepted();

        node.eventAggregator()
            .emitCommitted(new ClusterEvent.DeploymentStarted(org.pragmatica.hlc.HlcClock.hlcClock(node.self()).now(),
                                                              ClusterEvent.Severity.INFO,
                                                              "Deploying " + marker,
                                                              Map.of("artifact", marker, "nodeId", marker)));

        return accepted() > before;
    }

    private long accepted() {
        return node.eventAggregator().redeliveryCounters().get("accepted");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void commitLeaderKey(NodeId leader, long viewSequence) {
        var kvStore = node.kvStore();

        kvStore.process(kvStore.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, LeaderValue.leaderValue(leader, viewSequence)))));
    }

    /// State that reaches the store with no notification: a snapshot of a donor store that holds the leader record.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void restoreSnapshotNaming(NodeId leader) {
        var donor = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), CODEC, CODEC);

        donor.process(donor.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, LeaderValue.leaderValue(leader, 2_000_000L)))));
        node.kvStore().restoreSnapshot(donor.makeSnapshot().unwrap()).onFailure(cause -> fail("restore must succeed: " + cause.message()));
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
