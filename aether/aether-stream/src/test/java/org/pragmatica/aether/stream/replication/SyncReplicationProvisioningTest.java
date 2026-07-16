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

/// #262 two-knob end-to-end provisioning: the `replicas` knob alone sizes the HRW replica set
/// (`RF = clamp(replicas, 1, N)`, owner + `replicas − 1` peers), while the independent
/// `min-sync-replicas` knob (which COUNTS the owner) sets the synchronous write-ack floor to
/// `minAcks = min-sync-replicas − 1` DISTINCT NON-SELF acks. Composes the real formula
/// ({@link ReplicaPlacement#replicationFactor(int, int)}) → placement ({@link ReplicaPlacement#place})
/// → registry → {@link ReplicationManager#awaitReplication}. With `replicas ≥ min-sync-replicas` the
/// required peers exist and the barrier is SATISFIABLE; a too-small cluster clamps RF down and the
/// await then fails CLEARLY with `NOT_ENOUGH_REPLICAS` rather than silently under-provisioning.
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

    /// Provision `(STREAM, PARTITION)` at the `replicas` knob on an N-node cluster exactly as the
    /// runtime does: effective RF (`clamp(replicas, 1, N)`) → HRW placement → registry, with a manager
    /// whose governor is the placement owner (so owner self-exclusion applies). The `min-sync-replicas`
    /// knob is applied separately by each test as the `minAcks` (= `min-sync-replicas − 1`) argument to
    /// {@link ReplicationManager#awaitReplication}.
    private record Provisioned(Placement placement, ReplicaRegistry registry, ReplicationManager manager) {
        static Provisioned provision(int replicas, int clusterSize) {
            var members = nodes(clusterSize);
            var rf = ReplicaPlacement.replicationFactor(replicas, clusterSize);
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
    void minSyncTwo_ownerPlusOnePeer_resolvesOnPeerAck() {
        // #262: replicas=2 → RF=2 = owner + 1 peer. min-sync-replicas=2 (owner + 1 in-sync peer) →
        // minAcks = 2 − 1 = 1, so the await RESOLVES on that single peer's ack.
        var provisioned = Provisioned.provision(2, 3);
        assertThat(provisioned.peers()).hasSize(1);
        var peer = provisioned.peers().getFirst();

        var pending = provisioned.manager().awaitReplication(STREAM, PARTITION, OFFSET, 1);
        assertThat(pending.isResolved()).isFalse();

        provisioned.manager().handleAck(replicateAck(peer, STREAM, PARTITION, OFFSET));
        assertThat(pending.await().isSuccess()).isTrue();
    }

    @Test
    void minSyncThree_ownerPlusTwoPeers_resolvesOnTwoPeerAcks() {
        // #262: replicas=3 → RF=3 = owner + 2 peers. min-sync-replicas=3 → minAcks = 3 − 1 = 2 needs
        // BOTH peers to ack.
        var provisioned = Provisioned.provision(3, 5);
        assertThat(provisioned.peers()).hasSize(2);
        var peers = provisioned.peers();

        var pending = provisioned.manager().awaitReplication(STREAM, PARTITION, OFFSET, 2);
        provisioned.manager().handleAck(replicateAck(peers.get(0), STREAM, PARTITION, OFFSET));
        assertThat(pending.isResolved()).isFalse(); // one peer is not enough for minAcks=2

        provisioned.manager().handleAck(replicateAck(peers.get(1), STREAM, PARTITION, OFFSET));
        assertThat(pending.await().isSuccess()).isTrue();
    }

    @Test
    void minSyncExceedsClusterPeers_failsNotEnoughReplicas() {
        // #262: replicas=3 requested but cluster N=2 → RF clamps to 2 (owner + 1 peer). With
        // min-sync-replicas=3 → minAcks=2 the barrier needs 2 peers but only 1 exists, so the await
        // fails CLEARLY with NOT_ENOUGH_REPLICAS — no silent under-provisioning.
        var provisioned = Provisioned.provision(3, 2);
        assertThat(provisioned.peers()).hasSize(1);

        var result = provisioned.manager().awaitReplication(STREAM, PARTITION, OFFSET, 2).await();
        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void minSyncZero_eventualDefault_ownerOnly_unaffected() {
        // #262: replicas=1 / min-sync-replicas=0 (EVENTUAL) is unchanged — RF=1, owner only, no peers.
        var provisioned = Provisioned.provision(1, 5);

        assertThat(provisioned.placement().replicas()).containsExactly(provisioned.placement().owner());
        assertThat(provisioned.peers()).isEmpty();
    }
}
