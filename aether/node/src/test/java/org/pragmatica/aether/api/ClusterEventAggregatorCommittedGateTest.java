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
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.SystemStreamFactories;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
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

    /// The decision follows the notification order (the KV index), not the moment the handler runs: the record changes between two
    /// facts, and each fact goes to the owner named at ITS index.
    @Test
    void ClusterEvents_gateReadsTheIndex_notTheMoment() {
        bothNodes(() -> true, () -> true);

        both(ownershipPut(NODE_B), loadedPut("first"), ownershipPut(NODE_A), loadedPut("second"));

        assertThat(raisedBy(nodeA, "first")).containsExactly("node-b");
        assertThat(raisedBy(nodeA, "second")).containsExactly("node-a");
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
        var publisher = SystemStreamFactories.<ClusterEvent>systemStreamPublisher(SystemStreams.CLUSTER_EVENTS, manager, CODEC, config).unwrap();
        var consumer = SystemStreamFactories.<ClusterEvent>systemStreamConsumer(SystemStreams.CLUSTER_EVENTS, manager, CODEC, CODEC, config).unwrap();

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
        } else {
            node.onNodeArtifactPut((ValuePut<NodeArtifactKey, NodeArtifactValue>) notification);
        }
    }

    private List<String> raisedBy(ClusterEventAggregator reader) {
        return deploymentStarted(reader).stream().map(event -> event.at().nodeId().id()).toList();
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
    private static ValuePut<NodeArtifactKey, NodeArtifactValue> loadedPut(String node) {
        return new ValuePut<>(new KVCommand.Put<>(NodeArtifactKey.nodeArtifactKey(new NodeId(node), ARTIFACT),
                                                  new NodeArtifactValue(SliceState.LOAD, Option.none(), false, 0, List.of(), 0L)),
                              Option.none());
    }

    private static OperationalEvent.StreamIsrBelowMinimum isrBelowMinimum() {
        return OperationalEvent.StreamIsrBelowMinimum.streamIsrBelowMinimum("orders", 2, "node-a", List.of("node-a"), List.of("node-b"), 2, "below-id");
    }
}
