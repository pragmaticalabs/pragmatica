// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;

/// B11 (v1890 probe E) (v1890, #1890 class): the owner-side registry row a replica's ack wrote during an EARLIER ownership tenure
/// of this node seeds `awaitReplication` in a LATER tenure. Rows carry no owner epoch, and no production path resets
/// them when this node regains ownership (`registerReplica`, which resets a row, runs only when the desired replica set
/// changes, `ReplicaSetController.reconcilePartition`). Schedule: B owns, C confirms B's lineage through 9; B is
/// deposed, another owner's lineage replaces C's 8..9; B is elected again with head 7 and appends 8. The await for 8
/// must not count C, which does not hold B's 8. The manager has no tenure input, so this probe can only show the
/// mechanism; whether that schedule happens end to end is not run here.
class StaleRegistryRowTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final NodeId NODE_C = NodeId.nodeId("node-c").unwrap();
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 1L, 2L);

    /// B11: a row C confirmed under B's earlier tenure (epoch E1) must not resolve an await of B's later tenure (E2). The row
    /// carries the epoch it was confirmed under and the owner counts it only while that is its current epoch.
    @Test
    void rowFromAnEarlierTenure_doesNotResolveTheAwaitOfALaterTenure() {
        var registry = replicaRegistry();
        var current = new AtomicReference<>(E1);

        registry.registerReplica(STREAM, PARTITION, NODE_C);
        var manager = ownerManager(registry, current);

        manager.handleAck(replicateAck(NODE_C, STREAM, PARTITION, 9L, E1));
        current.set(E2);
        var await = manager.awaitReplication(STREAM, PARTITION, 8L, 1);

        assertThat(await.isResolved()).as("await for B's new offset 8 must not resolve from C's row 9 written under E1").isFalse();
        assertThat(manager.replicatedThrough(STREAM, PARTITION, 1)).as("nor does it make anything visible").isLessThan(8L);
    }

    /// A late ack that was made under the earlier epoch does not resolve a pending await of the current one either.
    @Test
    void aLateAckFromAnEarlierEpoch_doesNotResolveAPendingAwait() {
        var registry = replicaRegistry();
        var current = new AtomicReference<>(E2);

        registry.registerReplica(STREAM, PARTITION, NODE_C);
        var manager = ownerManager(registry, current);
        var await = manager.awaitReplication(STREAM, PARTITION, 8L, 1);

        manager.handleAck(replicateAck(NODE_C, STREAM, PARTITION, 9L, E1));

        assertThat(await.isResolved()).isFalse();
    }

    /// Control: the same row confirmed under the CURRENT epoch resolves at once, so the red above is the epoch and nothing else.
    @Test
    void control_aRowConfirmedUnderTheCurrentEpoch_resolvesTheAwait() {
        var registry = replicaRegistry();
        var current = new AtomicReference<>(E2);

        registry.registerReplica(STREAM, PARTITION, NODE_C);
        var manager = ownerManager(registry, current);

        manager.handleAck(replicateAck(NODE_C, STREAM, PARTITION, 9L, E2));

        assertThat(manager.awaitReplication(STREAM, PARTITION, 8L, 1).isResolved()).isTrue();
    }

    private static DefaultReplicationManager ownerManager(ReplicaRegistry registry, AtomicReference<Epoch> current) {
        var manager = (DefaultReplicationManager) ReplicationManager.replicationManager(NODE_B, registry);

        manager.inSyncReplicaSource((_, _) -> Option.some(List.of(NODE_B, NODE_C)));
        manager.ownerEpochs((_, _) -> current.get());

        return manager;
    }
}
