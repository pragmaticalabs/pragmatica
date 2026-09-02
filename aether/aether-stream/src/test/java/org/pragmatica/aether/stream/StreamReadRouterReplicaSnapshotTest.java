// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.StreamReadRouter.ReplicaSetView;
import org.pragmatica.aether.stream.StreamReadRouter.ReplicaView;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.StreamConfig.streamConfig;


/// Replica-state observability snapshot (`#260/#261/#333` sensor). Verifies the owner-aware assembly
/// in {@link StreamReadRouter#replicaSnapshot}: per-replica state/offset, HRW-owner marking, the
/// `servedByOwner` authority flag, and the owner head offset used for `#333` lag comparison.
class StreamReadRouterReplicaSnapshotTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId REPLICA_B = new NodeId("replica-b");
    private static final String STREAM = "test-stream";
    private static final int PARTITION = 0;

    private StreamPartitionManager partitionManager;
    private ReplicaRegistry replicaRegistry;

    @BeforeEach
    void setUp() {
        partitionManager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        partitionManager.createStream(streamConfig(STREAM));
        replicaRegistry = ReplicaRegistry.replicaRegistry();
    }

    private StreamReadRouter router(NodeId owner) {
        return StreamReadRouter.streamReadRouter(partitionManager,
                                                 Option.some(replicaRegistry),
                                                 Option.none(),
                                                 SELF,
                                                 (_, _) -> Option.some(owner),
                                                 StreamReadForwardMetrics.NOOP);
    }

    @Test
    void replicaSnapshot_marksSelfOwnerAndReportsOwnerHead_whenSelfIsHrwOwner() {
        partitionManager.publishLocal(STREAM, PARTITION, "e0".getBytes(), 1L);
        partitionManager.publishLocal(STREAM, PARTITION, "e1".getBytes(), 2L);
        replicaRegistry.registerReplica(STREAM, PARTITION, SELF);
        replicaRegistry.registerReplica(STREAM, PARTITION, REPLICA_B);
        replicaRegistry.updateWatermark(STREAM, PARTITION, SELF, 1L, ReplicationState.CAUGHT_UP);
        replicaRegistry.updateWatermark(STREAM, PARTITION, REPLICA_B, 0L, ReplicationState.SYNCING);

        var view = router(SELF).replicaSnapshot(STREAM, PARTITION);

        assertThat(view.servedByOwner()).isTrue();
        assertThat(view.ownerNodeId()).isEqualTo(Option.some(SELF.id()));
        assertThat(view.ownerHeadOffset()).isEqualTo(partitionManager.nextExpectedOffset(STREAM, PARTITION));
        assertThat(view.replicas()).extracting(ReplicaView::nodeId).containsExactly(REPLICA_B.id(), SELF.id());

        var self = byNode(view, SELF.id());
        assertThat(self.hrwOwner()).isTrue();
        assertThat(self.state()).isEqualTo("CAUGHT_UP");
        assertThat(self.confirmedOffset()).isEqualTo(1L);

        var replicaB = byNode(view, REPLICA_B.id());
        assertThat(replicaB.hrwOwner()).isFalse();
        assertThat(replicaB.state()).isEqualTo("SYNCING");
    }

    /// #593 — the production-realistic case, and the one that was broken live.
    ///
    /// NOTE what this test does NOT do: it never calls `updateWatermark` for SELF. Neither does
    /// production. `registerReplica` seeds every descriptor at `SYNCING`/`-1` and only an ACK
    /// advances it (`DefaultReplicationManager.handleAck`) — and a node never acks to itself. The
    /// sibling test above fabricates that call, which is exactly why it stayed green while a live
    /// owner reported itself `SYNCING`/`-1` for hours while serving a complete partition
    /// (`ownerHeadOffset` 24, peer `CAUGHT_UP` at 23, all 24 events readable in order).
    ///
    /// The answering node must report its OWN row from local truth.
    @Test
    void replicaSnapshot_reportsOwnRowFromLocalTruth_whenNoAckEverArrivesForSelf() {
        publish(3);
        replicaRegistry.registerReplica(STREAM, PARTITION, SELF);
        replicaRegistry.registerReplica(STREAM, PARTITION, REPLICA_B);
        replicaRegistry.updateWatermark(STREAM, PARTITION, REPLICA_B, 2L, ReplicationState.CAUGHT_UP);

        var view = router(SELF).replicaSnapshot(STREAM, PARTITION);
        var self = byNode(view, SELF.id());

        assertThat(self.state())
            .as("an owner holding the partition must not report itself SYNCING — it has nothing to sync from")
            .isEqualTo("CAUGHT_UP");
        assertThat(self.confirmedOffset())
            .as("own confirmed offset must come from the local ring head, not from an ack that never arrives")
            .isEqualTo(2L);
    }

    /// The substitution is scoped to the answering node. A peer's row must still come from the
    /// registry, where an ack is the honest source — a node cannot vouch for what a peer holds.
    @Test
    void replicaSnapshot_leavesPeerRowsToTheRegistry() {
        publish(3);
        replicaRegistry.registerReplica(STREAM, PARTITION, SELF);
        replicaRegistry.registerReplica(STREAM, PARTITION, REPLICA_B);

        var replicaB = byNode(router(SELF).replicaSnapshot(STREAM, PARTITION), REPLICA_B.id());

        assertThat(replicaB.state())
            .as("peer row must stay at its registry value — local ring presence says nothing about a peer")
            .isEqualTo("SYNCING");
        assertThat(replicaB.confirmedOffset()).isEqualTo(-1L);
    }

    /// When this node does NOT hold the partition, absence of a local ring is not evidence about
    /// anything — the registry value stands rather than being overwritten with a fabricated one.
    @Test
    void replicaSnapshot_doesNotSubstitute_whenPartitionIsNotHeldLocally() {
        replicaRegistry.registerReplica("other-stream", 9, SELF);

        var view = router(SELF).replicaSnapshot("other-stream", 9);
        var self = byNode(view, SELF.id());

        assertThat(self.state()).isEqualTo("SYNCING");
        assertThat(self.confirmedOffset()).isEqualTo(-1L);
    }

    @Test
    void replicaSnapshot_servedByOwnerFalseAndNamesOwner_whenSelfIsNotHrwOwner() {
        replicaRegistry.registerReplica(STREAM, PARTITION, SELF);

        var view = router(REPLICA_B).replicaSnapshot(STREAM, PARTITION);

        assertThat(view.servedByOwner()).isFalse();
        assertThat(view.ownerNodeId()).isEqualTo(Option.some(REPLICA_B.id()));
        assertThat(byNode(view, SELF.id()).hrwOwner()).isFalse();
    }

    @Test
    void replicaSnapshot_emptyAndNoOwner_forUnknownPartition() {
        var view = router(SELF).replicaSnapshot("missing-stream", 7);

        assertThat(view.replicas()).isEmpty();
        assertThat(view.servedByOwner()).isTrue();
        assertThat(view.earliestRetainedOffset()).isEqualTo(-1L);
    }

    private void publish(int events) {
        for (var i = 0; i < events; i++) {
            partitionManager.publishLocal(STREAM, PARTITION, ("e" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8), i)
                            .onFailure(cause -> { throw new IllegalStateException("publish failed: " + cause.message()); });
        }
    }

    private static ReplicaView byNode(ReplicaSetView view, String nodeId) {
        return view.replicas().stream().filter(replica -> replica.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }
}
