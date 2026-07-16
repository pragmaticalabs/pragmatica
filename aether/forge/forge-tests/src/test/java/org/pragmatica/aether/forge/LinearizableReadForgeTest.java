// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.ReplicaPlacement;
import org.pragmatica.aether.stream.replication.ReplicaPlacement.Placement;
import org.pragmatica.aether.stream.replication.StreamPartitionOwnershipWriter;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.TerminalOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #345 item 1e end-to-end gate: a `LINEARIZABLE` stream read routes to the COMMITTED owner of the
/// `(stream, partition)` arc and returns the authoritative data, both when issued ON the committed owner
/// and from a NON-owner node (which forwards to the committed owner). The committed ownership record is
/// minted by the production [StreamPartitionOwnershipWriter] through REAL consensus — the same path
/// `AetherNode.driveStreamOwnership` uses — so the committed owner the read routes to is the fenced,
/// authoritative one, NOT the on-the-fly HRW owner.
///
/// ## What it proves
///   1. **Owner-served linearizable read.** After the writer commits ownership for the partition and the
///      committed owner has published events locally, a `LINEARIZABLE` read issued ON the committed owner
///      returns exactly the written events (the owner-side guards pass: it is the committed owner, its
///      epoch is current, and it is CAUGHT_UP for its own partition).
///   2. **Non-owner forwards to the committed owner.** The SAME `LINEARIZABLE` read issued from a node
///      that is NOT the committed owner returns the same events — proving the read followed the committed
///      owner across nodes via the read-forward transport, not the issuing node's local copy.
///
/// ## Scope
///   The full takeover-DURING-churn race (a `LINEARIZABLE` read rejected `NotCurrentOwner` because it
///   landed on a deposed owner mid-reshuffle) needs a live multi-node cluster with a real concurrent
///   ownership transfer — that is the cloud gate. The pure rejection logic (NotCurrentOwner /
///   StaleEpochRead / OwnerCatchupPending) is covered deterministically by `LinearizableReadRoutingTest`
///   in aether-stream; single-JVM Forge cannot force the race without killing the owner under test.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LinearizableReadForgeTest {
    private static final Logger log = LoggerFactory.getLogger(LinearizableReadForgeTest.class);

    private static final int SIZE = 5;
    private static final int BASE_PORT = 5980;
    private static final int BASE_MGMT_PORT = 6080;
    private static final int BASE_APP_HTTP_PORT = 6180;
    private static final String PREFIX = "linr";

    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration OBSERVE_TIMEOUT = Duration.ofSeconds(150);
    private static final Duration POLL = Duration.ofMillis(500);

    private static final String STREAM = "linr:events";
    private static final int PARTITION = 0;
    private static final int REQUESTED_RF = 1;

    private EmberCluster cluster;

    @BeforeAll
    @TerminalOperation
    void setUp() {
        cluster = emberCluster(SIZE, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, PREFIX);
        cluster.start().await().onFailure(LinearizableReadForgeTest::failStart);
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(this::allNodesReady);
        log.info("LINEARIZABLE-READ: {}-node cluster formed, leader={}", SIZE, cluster.currentLeader().or("none"));
    }

    @AfterAll
    @TerminalOperation
    void tearDown() {
        Option.option(cluster).onPresent(c -> c.stop().await());
    }

    @Test
    @TerminalOperation
    void linearizableRead_servedByCommittedOwner_returnsWrittenData_onOwnerAndForwardedFromNonOwner() {
        cluster.allNodes().forEach(node -> materialize(node, STREAM));

        var owner = hrwOwner(STREAM, PARTITION);
        var ownerNode = resolveNode(owner);
        var term = leaderNode().currentGenerationEpoch().rabiaTerm();
        log.info("LINEARIZABLE-READ: committed owner={} term={}", owner.id(), term);

        // Commit ownership through the production writer + REAL consensus, so the LINEARIZABLE arm has a
        // committed StreamPartitionOwnershipValue.owner to route to.
        var writer = liveWriter(term, owner);
        commitOwnershipVia(writer, STREAM, PARTITION);
        await().atMost(OBSERVE_TIMEOUT).pollInterval(POLL)
               .until(() -> committedOwner(PARTITION).map(v -> v.owner().equals(owner)).or(false));
        log.info("LINEARIZABLE-READ: committed ownership for {}[{}] owner={}", STREAM, PARTITION, owner.id());

        // The committed owner publishes the authoritative events locally.
        ownerNode.streamPartitionManager()
                 .publishLocal(STREAM, PARTITION, "lin-0".getBytes(UTF_8), System.currentTimeMillis())
                 .onFailure(LinearizableReadForgeTest::failScenario);
        ownerNode.streamPartitionManager()
                 .publishLocal(STREAM, PARTITION, "lin-1".getBytes(UTF_8), System.currentTimeMillis())
                 .onFailure(LinearizableReadForgeTest::failScenario);

        // 1. LINEARIZABLE read issued ON the committed owner returns the written events.
        await().atMost(OBSERVE_TIMEOUT).pollInterval(POLL)
               .until(() -> linearizableRead(ownerNode).size() == 2);
        assertThat(linearizableRead(ownerNode))
            .as("LINEARIZABLE read on the committed owner returns the authoritative events")
            .hasSize(2);
        log.info("LINEARIZABLE-READ: owner-served read returned {} events", linearizableRead(ownerNode).size());

        // 2. LINEARIZABLE read issued from a NON-owner node forwards to the committed owner and returns
        //    the same events.
        var nonOwnerNode = cluster.allNodes().stream()
                                  .filter(node -> !node.self().equals(owner))
                                  .findFirst()
                                  .orElseThrow();
        await().atMost(OBSERVE_TIMEOUT).pollInterval(POLL)
               .until(() -> linearizableRead(nonOwnerNode).size() == 2);
        assertThat(linearizableRead(nonOwnerNode))
            .as("LINEARIZABLE read from a non-owner forwards to the committed owner and returns the same events")
            .hasSize(2);
        log.info("LINEARIZABLE-READ: non-owner {} forwarded read returned {} events",
                 nonOwnerNode.self().id(), linearizableRead(nonOwnerNode).size());
    }

    private List<OffHeapRingEvent> linearizableRead(AetherNode node) {
        return node.streamReadRouter()
                   .read(STREAM, PARTITION, 0, 10, ReadPreference.LINEARIZABLE)
                   .await()
                   .map(events -> events.stream().map(e -> new OffHeapRingEvent(e.offset())).toList())
                   .or(List.of());
    }

    /// Minimal carrier so the read result is a plain immutable value the awaitility predicate can size.
    private record OffHeapRingEvent(long offset) {}

    private StreamPartitionOwnershipWriter liveWriter(long term, NodeId owner) {
        return StreamPartitionOwnershipWriter.streamPartitionOwnershipWriter(() -> true,
                                                                             () -> term,
                                                                             HlcClock.hlcClock(leaderNode().self()),
                                                                             (stream, partition) -> committedOwner(partition),
                                                                             (stream, partition) -> Option.some(owner));
    }

    @TerminalOperation
    private void commitOwnershipVia(StreamPartitionOwnershipWriter writer, String stream, int partition) {
        writer.writeOwnershipChange(stream, partition).onPresent(this::applyOnLeader);
    }

    @TerminalOperation
    private void applyOnLeader(KVCommand<org.pragmatica.aether.slice.kvstore.AetherKey> command) {
        leaderNode().<Object>apply(List.of(command)).await().onFailure(LinearizableReadForgeTest::failScenario);
    }

    private Option<StreamPartitionOwnershipValue> committedOwner(int partition) {
        return leaderNode().kvStore()
                           .getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(STREAM, partition),
                                     StreamPartitionOwnershipValue.class);
    }

    private NodeId hrwOwner(String stream, int partition) {
        var leader = leaderNode();
        var members = coreMembers(leader);
        var rf = ReplicaPlacement.replicationFactor(REQUESTED_RF, clusterSize(leader));

        return ReplicaPlacement.place(stream, partition, members, rf)
                               .map(Placement::owner)
                               .toResult(ForgeError.NO_PLACEMENT)
                               .onFailure(LinearizableReadForgeTest::failScenario)
                               .or(members.getFirst());
    }

    private void materialize(AetherNode node, String stream) {
        node.streamPartitionManager()
            .ensureStreamMaterialized(StreamConfig.streamConfig(stream))
            .onFailure(LinearizableReadForgeTest::failMaterialize);
    }

    private List<NodeId> coreMembers(AetherNode node) {
        return node.clusterTopologyManager().map(ctm -> List.copyOf(ctm.observer().coreNodes())).or(List.of());
    }

    private int clusterSize(AetherNode node) {
        return node.clusterTopologyManager().map(ctm -> ctm.observer().clusterSize()).or(SIZE);
    }

    private AetherNode resolveNode(NodeId nodeId) {
        return cluster.getNode(nodeId.id())
                      .toResult(ForgeError.NODE_UNRESOLVED)
                      .onFailure(LinearizableReadForgeTest::failScenario)
                      .or(cluster.allNodes().getFirst());
    }

    private AetherNode leaderNode() {
        return cluster.currentLeader().flatMap(cluster::getNode).or(cluster.allNodes().getFirst());
    }

    private boolean allNodesReady() {
        return cluster.allNodes().stream().allMatch(AetherNode::isReady);
    }

    private static void failStart(Cause cause) {
        throw new AssertionError("Cluster start failed: " + cause.message());
    }

    private static void failScenario(Cause cause) {
        throw new AssertionError("Scenario setup failed: " + cause.message());
    }

    private static void failMaterialize(Cause cause) {
        throw new AssertionError("Stream materialization failed: " + cause.message());
    }

    private enum ForgeError implements Cause {
        NO_PLACEMENT("ReplicaPlacement returned no owner for the partition"),
        NODE_UNRESOLVED("Computed owner NodeId could not be resolved to a cluster node");

        private final String message;

        ForgeError(String message) {
            this.message = message;
        }

        @Override
        public String message() {
            return message;
        }
    }
}
