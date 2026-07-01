// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.replication.ReplicaPlacement.Placement;
import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;

/// #378 end-to-end provisioning: the effective APP replication factor (owner + `minSyncReplicas` sync
/// peers) sizes the HRW replica set so that a synchronous publish awaiting `minSyncReplicas` DISTINCT
/// NON-SELF acks is SATISFIABLE. Composes the real formula
/// ({@link ReplicaPlacement#replicationFactor(int, int)}) → placement ({@link ReplicaPlacement#place})
/// → registry → {@link ReplicationManager#awaitReplication}. Before the fix the set was one peer short,
/// so every `minSyncReplicas >= 1` await failed NOT_ENOUGH_REPLICAS.
class SyncReplicationProvisioningTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final long OFFSET = 5L;

    private static List<NodeId> nodes(int count) {
        var list = new ArrayList<NodeId>();

        for (var i = 0; i < count; i++) {
            list.add(new NodeId("node-" + i));
        }

        return list;
    }

    /// Provision `(STREAM, PARTITION)` at `minSyncReplicas` on an N-node cluster exactly as the runtime
    /// does: effective RF → HRW placement → registry, with a manager whose governor is the placement
    /// owner (so owner self-exclusion applies).
    private record Provisioned(Placement placement, ReplicaRegistry registry, ReplicationManager manager) {
        static Provisioned provision(int minSyncReplicas, int clusterSize) {
            var members = nodes(clusterSize);
            var rf = ReplicaPlacement.replicationFactor(minSyncReplicas, clusterSize);
            var placement = ReplicaPlacement.place(STREAM, PARTITION, members, rf)
                                            .or(() -> {
                                                throw new AssertionError("expected a placement");
                                            });
            var registry = replicaRegistry();

            placement.replicas().forEach(node -> registry.registerReplica(STREAM, PARTITION, node));

            var manager = replicationManager(placement.owner(), registry, (target, message) -> {});

            return new Provisioned(placement, registry, manager);
        }

        List<NodeId> peers() {
            return placement.replicas()
                            .stream()
                            .filter(node -> !node.equals(placement.owner()))
                            .toList();
        }
    }

    @Test
    void minSyncOne_ownerPlusOnePeer_resolvesOnPeerAck() {
        // #378: minSyncReplicas=1 → RF=2 = owner + 1 peer. The await for minAcks=1 RESOLVES on that
        // single peer's ack. Before the fix RF was 1 (owner only) → 0 peers → NOT_ENOUGH_REPLICAS.
        var provisioned = Provisioned.provision(1, 3);
        assertThat(provisioned.peers()).hasSize(1);
        var peer = provisioned.peers().getFirst();

        var pending = provisioned.manager().awaitReplication(STREAM, PARTITION, OFFSET, 1);
        assertThat(pending.isResolved()).isFalse();

        provisioned.manager().handleAck(replicateAck(peer, STREAM, PARTITION, OFFSET));
        assertThat(pending.await().isSuccess()).isTrue();
    }

    @Test
    void minSyncTwo_ownerPlusTwoPeers_resolvesOnTwoPeerAcks() {
        // #378: minSyncReplicas=2 → RF=3 = owner + 2 peers. minAcks=2 needs BOTH peers to ack.
        var provisioned = Provisioned.provision(2, 5);
        assertThat(provisioned.peers()).hasSize(2);
        var peers = provisioned.peers();

        var pending = provisioned.manager().awaitReplication(STREAM, PARTITION, OFFSET, 2);
        provisioned.manager().handleAck(replicateAck(peers.get(0), STREAM, PARTITION, OFFSET));
        assertThat(pending.isResolved()).isFalse(); // one peer is not enough for minAcks=2

        provisioned.manager().handleAck(replicateAck(peers.get(1), STREAM, PARTITION, OFFSET));
        assertThat(pending.await().isSuccess()).isTrue();
    }

    @Test
    void minSyncTwo_clusterTooSmall_failsNotEnoughReplicas() {
        // #378: minSyncReplicas + 1 > N. RF clamps to N=2 (owner + 1 peer), one peer short of minAcks=2,
        // so the await fails CLEARLY with NOT_ENOUGH_REPLICAS — no silent under-provisioning.
        var provisioned = Provisioned.provision(2, 2);
        assertThat(provisioned.peers()).hasSize(1);

        var result = provisioned.manager().awaitReplication(STREAM, PARTITION, OFFSET, 2).await();
        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void minSyncZero_eventualDefault_ownerOnly_unaffected() {
        // #378: minSyncReplicas=0 (EVENTUAL) is unchanged — RF=1, owner only, no sync peers.
        var provisioned = Provisioned.provision(0, 5);

        assertThat(provisioned.placement().replicas()).containsExactly(provisioned.placement().owner());
        assertThat(provisioned.peers()).isEmpty();
    }
}
