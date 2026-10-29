// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.SystemStreamFactories;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;

/// #1671 (I-35): a fact derived from a committed KV notification is raised by exactly one node per KV index, decided from
/// the committed ownership record at that notification, never from the node-local view. Two nodes share one cluster-events
/// stream; each is driven with the SAME committed notifications in the SAME order, while its node-local owner check is set to
/// what a lagging view says (both claim ownership, or neither does) -- the straddle of a handover. The existing at-least-once
/// pin (`ClusterEventAggregatorTest#emit_ownerHandover_...`) goes through `emit` and is not touched.
class ClusterEventAggregatorCommittedGateTest {
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:svc:1.0.0").unwrap();
    private static final String STREAM = SystemStreams.CLUSTER_EVENTS.asString();

    private StreamPartitionManager manager;
    private ClusterEventAggregator nodeA;
    private ClusterEventAggregator nodeB;
    private org.pragmatica.aether.slice.stream.FrameworkStreamPublisher<ClusterEvent> publisher;
    private org.pragmatica.aether.slice.stream.FrameworkStreamConsumer<ClusterEvent> consumer;

    @BeforeEach
    void setUp() {
        manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.close();
    }

    /// Both nodes' local views claim the partition: the straddle in which a lagging view lets both pass.
    @Test
    void ClusterEvents_handoverStraddle_kvDerivedFact_exactlyOneCopy() {
        bothNodes(() -> true, () -> true);

        both(ownershipPut(NODE_B), loadedPut("n1"));

        assertThat(raisedBy(nodeA)).as("one copy of the fact, raised by the committed owner").containsExactly("node-b");
    }

    /// Neither node's local view claims the partition: the mirrored schedule, in which the fact is dropped by all.
    @Test
    void ClusterEvents_handoverStraddle_kvDerivedFact_neverZero() {
        bothNodes(() -> false, () -> false);

        both(ownershipPut(NODE_B), loadedPut("n1"));

        assertThat(raisedBy(nodeA)).as("the committed owner raises it although its local view says no").containsExactly("node-b");
    }

    /// A node the committed record does not name emits none, whatever its local view says.
    @Test
    void ClusterEvents_nodeNotNamedByTheRecord_emitsNone() {
        bothNodes(() -> true, () -> true);

        both(ownershipPut(NODE_A), loadedPut("n1"));

        assertThat(raisedBy(nodeA)).containsExactly("node-a");
    }

    /// NT-169c: no ownership record at the index (bootstrap, quorum loss): the leader named by the committed `LeaderKey` raises
    /// the fact, whatever each node's local view says, so the "neither side passes" window is closed for it.
    @Test
    void ClusterEvents_noOwnershipRecordAtIndex_committedLeaderRaisesIt() {
        bothNodes(() -> false, () -> true);

        both(leaderPut(NODE_B, 1L), loadedPut("n1"));

        assertThat(raisedBy(nodeA)).containsExactly("node-b");
    }

    /// v-2500 T1: the roster is not an input. Two nodes with different local views and no record still raise one copy, because the
    /// committed leader is the same at the index on both.
    @Test
    void ClusterEvents_noRecord_nodesWithDifferentLocalViews_raiseExactlyOneCopy() {
        bothNodes(() -> true, () -> true);

        both(leaderPut(NODE_A, 1L), loadedPut("n1"));

        assertThat(raisedBy(nodeA)).containsExactly("node-a");
    }

    /// The leader decides in notification order too, and a record, once there, outranks the leader.
    @Test
    void ClusterEvents_leaderFollowsTheIndex_andARecordOutranksIt() {
        bothNodes(() -> true, () -> true);

        both(leaderPut(NODE_A, 1L), loadedPut("first"), leaderPut(NODE_B, 2L), loadedPut("second"), ownershipPut(NODE_A), loadedPut("third"));

        assertThat(raisedBy(nodeA, "first")).containsExactly("node-a");
        assertThat(raisedBy(nodeA, "second")).containsExactly("node-b");
        assertThat(raisedBy(nodeA, "third")).containsExactly("node-a");
    }

    /// v-2500 T4: a removal of the record (a snapshot install synthesises one for a vanished key) clears the owner this node holds;
    /// the gate falls back to the leader.
    @Test
    void ClusterEvents_recordRemoved_theGateFallsBackToTheLeader() {
        bothNodes(() -> true, () -> true);

        both(ownershipPut(NODE_B), leaderPut(NODE_A, 1L), loadedPut("before"));
        nodeA.onStreamPartitionOwnershipRemove(ownershipRemove());
        nodeB.onStreamPartitionOwnershipRemove(ownershipRemove());
        both(loadedPut("after"));

        assertThat(raisedBy(nodeA, "before")).containsExactly("node-b");
        assertThat(raisedBy(nodeA, "after")).containsExactly("node-a");
    }

    /// v-2500 T4: a removal of some other partition's record leaves the owner alone.
    @Test
    void ClusterEvents_removalOfAnotherPartitionsRecord_isIgnored() {
        bothNodes(() -> true, () -> true);

        both(ownershipPut(NODE_B));
        nodeA.onStreamPartitionOwnershipRemove(ownershipRemoveOf("orders"));
        nodeB.onStreamPartitionOwnershipRemove(ownershipRemoveOf("orders"));
        both(loadedPut("after"));

        assertThat(raisedBy(nodeA)).containsExactly("node-b");
    }

    /// v-2500 T4: with nothing committed to decide (no record, no leader, nothing bound), the node-local view decides, as in
    /// `emit`: the empty case is the legacy gate, not a pass for everyone and not a drop for everyone.
    @Test
    void ClusterEvents_nothingCommitted_theLocalViewDecides() {
        bothNodes(() -> false, () -> true);

        both(loadedPut("n1"));

        assertThat(raisedBy(nodeA)).containsExactly("node-b");
    }

    /// A node that has not yet seen a leader notification reads the committed leader from the store.
    @Test
    void ClusterEvents_noLeaderNotificationSeen_theStoredLeaderDecides() {
        bothNodes(() -> false, () -> false);
        nodeA.bindCommittedOwnerSources(Option::none, () -> Option.some(NODE_B));
        nodeB.bindCommittedOwnerSources(Option::none, () -> Option.some(NODE_B));

        both(loadedPut("n1"));

        assertThat(raisedBy(nodeA)).containsExactly("node-b");
    }

    /// A node that has not yet seen an ownership notification (it joined after the last one) reads the committed record from the
    /// store; a notification seen later takes over.
    @Test
    void ClusterEvents_noNotificationSeenYet_theStoredRecordDecides_andANotificationTakesOver() {
        bothNodes(() -> false, () -> false);
        nodeA.bindCommittedOwnerSources(() -> Option.some(NODE_B), Option::none);
        nodeB.bindCommittedOwnerSources(() -> Option.some(NODE_B), Option::none);

        both(loadedPut("first"), ownershipPut(NODE_A), loadedPut("second"));

        assertThat(raisedBy(nodeA, "first")).containsExactly("node-b");
        assertThat(raisedBy(nodeA, "second")).containsExactly("node-a");
    }

    /// The decision follows the notification order (the KV index), not the moment the handler runs: the record changes between two
    /// facts, and each fact goes to the owner named at ITS index.
    @Test
    void ClusterEvents_gateReadsTheIndex_notTheMoment() {
        bothNodes(() -> true, () -> true);

        both(ownershipPut(NODE_B), loadedPut("first"), ownershipPut(NODE_A), loadedPut("second"));

        assertThat(raisedBy(nodeA, "first")).containsExactly("node-b");
        assertThat(raisedBy(nodeA, "second")).containsExactly("node-a");
    }

    /// Every KV handler that raises a fact goes through the committed gate: the other deployment states, a community mint and a roster join.
    @Test
    void ClusterEvents_everyKvHandler_straddle_exactlyOneCopy() {
        bothNodes(() -> true, () -> true);

        both(ownershipPut(NODE_B),
             artifactPut("completed", SliceState.ACTIVE, Option.none()),
             artifactPut("failed", SliceState.FAILED, Option.some("boom")));
        nodeA.onCommunityPut(communityMinted());
        nodeB.onCommunityPut(communityMinted());
        nodeA.onGovernorAnnouncementPut(rosterJoin());
        nodeB.onGovernorAnnouncementPut(rosterJoin());

        assertThat(raisedBy(nodeA, ClusterEvent.DeploymentCompleted.class)).containsExactly("node-b");
        assertThat(raisedBy(nodeA, ClusterEvent.DeploymentFailed.class)).containsExactly("node-b");
        assertThat(raisedBy(nodeA, ClusterEvent.CommunityMinted.class)).containsExactly("node-b");
        assertThat(raisedBy(nodeA, ClusterEvent.CommunityMemberJoined.class)).containsExactly("node-b");
    }

    /// A snapshot or resync replay re-delivers old notifications: the committed gate suppresses the side effect, like `emit`.
    @Test
    void ClusterEvents_replayInProgress_suppressesTheCommittedEmit() {
        bothNodes(() -> true, () -> true);
        var replaying = ClusterEventAggregator.clusterEventAggregator(() -> publisher,
                                                                      () -> consumer,
                                                                      () -> true,
                                                                      NODE_B,
                                                                      HlcClock.hlcClock(NODE_B),
                                                                      () -> 3,
                                                                      () -> true,
                                                                      () -> true);

        replaying.onStreamPartitionOwnershipPut(ownershipPut(NODE_B));
        replaying.onNodeArtifactPut(loadedPut("n1"));

        assertThat(raisedBy(nodeA)).as("nothing was published while replaying").isEmpty();
    }

    /// The stated residual: a fact that does not originate in a KV notification (an ISR transition) keeps the node-local gate,
    /// whatever the committed record says.
    @Test
    void ClusterEvents_nonKvDerivedFact_isStillGatedNodeLocally() {
        bothNodes(() -> false, () -> true);

        both(ownershipPut(NODE_A));
        nodeA.onStreamIsrBelowMinimum(isrBelowMinimum());
        nodeB.onStreamIsrBelowMinimum(isrBelowMinimum());

        var isr = nodeA.events().await().unwrap().stream().filter(event -> event instanceof ClusterEvent.StreamIsrBelowMinimum).toList();

        assertThat(isr).as("raised by the node whose LOCAL view claims ownership, although the record names the other")
                       .extracting(event -> event.at().nodeId().id())
                       .containsExactly("node-b");
    }

    private void bothNodes(BooleanSupplier localViewA, BooleanSupplier localViewB) {
        var config = StreamConfig.streamConfig(STREAM,
                                               1,
                                               RetentionPolicy.retentionPolicy(10_000, 64L * 1024 * 1024, Long.MAX_VALUE, RetentionMode.ANY),
                                               "earliest",
                                               64L * 1024,
                                               ConsistencyMode.EVENTUAL,
                                               1);
        publisher = SystemStreamFactories.<ClusterEvent>systemStreamPublisher(SystemStreams.CLUSTER_EVENTS, manager, CODEC, config).unwrap();
        consumer = SystemStreamFactories.<ClusterEvent>systemStreamConsumer(SystemStreams.CLUSTER_EVENTS, manager, CODEC, CODEC, config).unwrap();

        nodeA = aggregator(NODE_A, localViewA, publisher, consumer);
        nodeB = aggregator(NODE_B, localViewB, publisher, consumer);
    }

    private static ClusterEventAggregator aggregator(NodeId self,
                                                     BooleanSupplier localView,
                                                     org.pragmatica.aether.slice.stream.FrameworkStreamPublisher<ClusterEvent> publisher,
                                                     org.pragmatica.aether.slice.stream.FrameworkStreamConsumer<ClusterEvent> consumer) {
        return ClusterEventAggregator.clusterEventAggregator(() -> publisher,
                                                             () -> consumer,
                                                             localView,
                                                             self,
                                                             HlcClock.hlcClock(self),
                                                             () -> 3,
                                                             () -> false,
                                                             () -> true);
    }

    /// The committed notifications, in commit order, as every node receives them.
    @SafeVarargs
    private void both(ValuePut<?, ?>... notifications) {
        for (var notification : notifications) {
            deliver(nodeA, notification);
            deliver(nodeB, notification);
        }
    }

    @SuppressWarnings("unchecked")
    private static void deliver(ClusterEventAggregator node, ValuePut<?, ?> notification) {
        if (notification.cause().key() instanceof StreamPartitionOwnershipKey) {
            node.onStreamPartitionOwnershipPut((ValuePut<StreamPartitionOwnershipKey, StreamPartitionOwnershipValue>) notification);
        } else if (notification.cause().key() instanceof LeaderKey) {
            node.onLeaderCommitted(notification);
        } else {
            node.onNodeArtifactPut((ValuePut<NodeArtifactKey, NodeArtifactValue>) notification);
        }
    }

    private List<String> raisedBy(ClusterEventAggregator reader) {
        return deploymentStarted(reader).stream().map(event -> event.at().nodeId().id()).toList();
    }

    private List<String> raisedBy(ClusterEventAggregator reader, Class<? extends ClusterEvent> type) {
        return reader.events().await().unwrap().stream().filter(type::isInstance).map(event -> event.at().nodeId().id()).toList();
    }

    private List<String> raisedBy(ClusterEventAggregator reader, String nodeInSummary) {
        return deploymentStarted(reader).stream()
                                        .filter(event -> event.details().get("nodeId").equals(nodeInSummary))
                                        .map(event -> event.at().nodeId().id())
                                        .toList();
    }

    private List<ClusterEvent> deploymentStarted(ClusterEventAggregator reader) {
        return reader.events().await().unwrap().stream().filter(event -> event instanceof ClusterEvent.DeploymentStarted).toList();
    }

    private static ValuePut<StreamPartitionOwnershipKey, StreamPartitionOwnershipValue> ownershipPut(NodeId owner) {
        var epoch = Epoch.epoch(0L, 7, 3);

        return new ValuePut<>(new KVCommand.Put<>(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(STREAM, 0),
                                                  StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner,
                                                                                                              epoch,
                                                                                                              epoch.localCounter(),
                                                                                                              HlcTimestamp.ZERO)),
                              Option.none());
    }

    /// A committed `LOAD` of the artifact on `node`: the KV-derived `DeploymentStarted`.
    private static ValuePut<LeaderKey, LeaderValue> leaderPut(NodeId leader, long viewSequence) {
        return new ValuePut<>(new KVCommand.Put<>(LeaderKey.INSTANCE, LeaderValue.leaderValue(leader, viewSequence)), Option.none());
    }

    private static ValueRemove<StreamPartitionOwnershipKey, StreamPartitionOwnershipValue> ownershipRemove() {
        return ownershipRemoveOf(STREAM);
    }

    private static ValueRemove<StreamPartitionOwnershipKey, StreamPartitionOwnershipValue> ownershipRemoveOf(String stream) {
        return new ValueRemove<>(new KVCommand.Remove<>(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream, 0), Option.none()), Option.none());
    }

    /// A committed `LOAD` of the artifact on `node`: the KV-derived `DeploymentStarted`.
    private static ValuePut<NodeArtifactKey, NodeArtifactValue> loadedPut(String node) {
        return new ValuePut<>(new KVCommand.Put<>(NodeArtifactKey.nodeArtifactKey(new NodeId(node), ARTIFACT),
                                                  new NodeArtifactValue(SliceState.LOAD, Option.none(), false, 0, List.of(), 0L)),
                              Option.none());
    }

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> artifactPut(String node, SliceState state, Option<String> reason) {
        return new ValuePut<>(new KVCommand.Put<>(NodeArtifactKey.nodeArtifactKey(new NodeId(node), ARTIFACT),
                                                  new NodeArtifactValue(state, reason, false, 0, List.of(), 0L)),
                              Option.none());
    }

    private static ValuePut<CommunityKey, CommunityValue> communityMinted() {
        return new ValuePut<>(new KVCommand.Put<>(CommunityKey.communityKey("default:local:0"),
                                                  CommunityValue.communityValue("", ActivationDirectiveValue.WORKER, 3, CommunityState.FORMING, 1L, Option.none())),
                              Option.none());
    }

    private static ValuePut<GovernorAnnouncementKey, GovernorAnnouncementValue> rosterJoin() {
        return new ValuePut<>(new KVCommand.Put<>(new GovernorAnnouncementKey("default:local:0"),
                                                  new GovernorAnnouncementValue(NODE_A,
                                                                                1,
                                                                                List.of(new NodeId("member-1")),
                                                                                "",
                                                                                0L,
                                                                                0L,
                                                                                Epoch.ZERO,
                                                                                Epoch.ZERO,
                                                                                HlcTimestamp.ZERO,
                                                                                false)),
                              Option.none());
    }

    private static OperationalEvent.StreamIsrBelowMinimum isrBelowMinimum() {
        return OperationalEvent.StreamIsrBelowMinimum.streamIsrBelowMinimum("orders", 2, "node-a", List.of("node-a"), List.of("node-b"), 2, "below-id");
    }
}
