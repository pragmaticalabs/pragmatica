// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;

/// PROBE E (v1890, #1890 class): the owner-side registry row a replica's ack wrote during an EARLIER ownership tenure
/// of this node seeds `awaitReplication` in a LATER tenure. Rows carry no owner epoch, and no production path resets
/// them when this node regains ownership (`registerReplica`, which resets a row, runs only when the desired replica set
/// changes, `ReplicaSetController.reconcilePartition`). Schedule: B owns, C confirms B's lineage through 9; B is
/// deposed, another owner's lineage replaces C's 8..9; B is elected again with head 7 and appends 8. The await for 8
/// must not count C, which does not hold B's 8. The manager has no tenure input, so this probe can only show the
/// mechanism; whether that schedule happens end to end is not run here.
class V1890StaleRegistryRowProbeTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final NodeId NODE_C = NodeId.nodeId("node-c").unwrap();

    @Test
    void probeE_rowFromAnEarlierTenure_mustNotResolveTheAwaitOfALaterTenure() {
        var registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, NODE_C);
        var tenure1 = ownerManager(registry);

        tenure1.handleAck(replicateAck(NODE_C, STREAM, PARTITION, 9L));
        var tenure2 = ownerManager(registry);
        var await = tenure2.awaitReplication(STREAM, PARTITION, 8L, 1);

        assertThat(await.isResolved()).as("await for B's new offset 8 resolved from C's row 9 written in B's earlier tenure").isFalse();
    }

    /// Control: the same later-tenure await with no earlier row stays pending, so the probe's red is the row.
    @Test
    void probeE_control_withoutAnEarlierRow_theAwaitStaysPending() {
        var registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, NODE_C);
        var await = ownerManager(registry).awaitReplication(STREAM, PARTITION, 8L, 1);

        assertThat(await.isResolved()).isFalse();
    }

    /// The node's manager instance outlives a tenure in production (one per node); a fresh instance over the SAME
    /// registry here is the more lenient model, and still seeds from the row.
    private static ReplicationManager ownerManager(ReplicaRegistry registry) {
        var manager = (DefaultReplicationManager) ReplicationManager.replicationManager(NODE_B, registry);

        manager.inSyncReplicaSource((_, _) -> Option.some(List.of(NODE_B, NODE_C)));

        return manager;
    }
}
