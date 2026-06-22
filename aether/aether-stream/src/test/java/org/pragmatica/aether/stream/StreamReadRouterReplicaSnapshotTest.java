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

    private static ReplicaView byNode(ReplicaSetView view, String nodeId) {
        return view.replicas().stream().filter(replica -> replica.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }
}
