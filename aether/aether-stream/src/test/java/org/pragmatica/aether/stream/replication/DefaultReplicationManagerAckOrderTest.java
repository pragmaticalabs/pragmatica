// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Result;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicaDescriptor.replicaDescriptor;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateEvents.replicateEvents;
import static org.pragmatica.aether.stream.replication.ReplicationReceiveHandler.replicationReceiveHandler;

/// #1697 (PR-6b, I-23): the owner's view of a replica's confirmed offset is monotone within one `(owner epoch, replica boot
/// token)`. A reordered ack of the same replica process cannot lower it; an ack of ANOTHER process of the same node id (a
/// restart, which may hold less) replaces it, so the owner never keeps counting copies the replica lost.
class DefaultReplicationManagerAckOrderTest {
    private static final NodeId OWNER = new NodeId("owner");
    private static final NodeId REPLICA = new NodeId("replica");
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final Epoch EPOCH_1 = Epoch.epoch(1L, 1L, 0L);
    private static final Epoch EPOCH_2 = Epoch.epoch(1L, 2L, 0L);
    private static final long BOOT_A = 0x0A0A_0A0AL;
    private static final long BOOT_B = 0x0B0B_0B0BL;

    private ReplicaRegistry registry;
    private ReplicationManager replication;

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        registry.registerReplica(STREAM, PARTITION, REPLICA);
        replication = replicationManager(OWNER, registry, ReplicationTransport.NOOP, (_, _) -> -1L);
        replication.ownerEpochs((_, _) -> EPOCH_1);
    }

    /// NT-144.
    @Test
    void reorderedAcks_sameBootToken_keepMax() {
        replication.handleAck(replicateAck(REPLICA, STREAM, PARTITION, 10L, EPOCH_1, BOOT_A));
        replication.handleAck(replicateAck(REPLICA, STREAM, PARTITION, 5L, EPOCH_1, BOOT_A));

        assertThat(row().confirmedOffset()).isEqualTo(10L);
        assertThat(row().bootToken()).isEqualTo(BOOT_A);
    }

    /// NT-145: RED under the #2888 rule (max within an epoch, token ignored), which keeps 10 for a replica now holding 5.
    @Test
    void differentBootToken_lowerAck_lowersView() {
        replication.handleAck(replicateAck(REPLICA, STREAM, PARTITION, 10L, EPOCH_1, BOOT_A));
        replication.handleAck(replicateAck(REPLICA, STREAM, PARTITION, 5L, EPOCH_1, BOOT_B));

        assertThat(row().confirmedOffset()).as("a restarted replica process may hold less").isEqualTo(5L);
        assertThat(row().bootToken()).as("the row now names the new process").isEqualTo(BOOT_B);
    }

    @Test
    void afterReplacement_reorderedAckOfTheNewProcess_keepsMax() {
        replication.handleAck(replicateAck(REPLICA, STREAM, PARTITION, 10L, EPOCH_1, BOOT_A));
        replication.handleAck(replicateAck(REPLICA, STREAM, PARTITION, 5L, EPOCH_1, BOOT_B));
        replication.handleAck(replicateAck(REPLICA, STREAM, PARTITION, 3L, EPOCH_1, BOOT_B));

        assertThat(row().confirmedOffset()).isEqualTo(5L);
    }

    @Test
    void olderEpochAck_ofAnotherProcess_isRefused() {
        replication.handleAck(replicateAck(REPLICA, STREAM, PARTITION, 4L, EPOCH_2, BOOT_A));
        replication.handleAck(replicateAck(REPLICA, STREAM, PARTITION, 10L, EPOCH_1, BOOT_B));

        assertThat(row().confirmedOffset()).isEqualTo(4L);
        assertThat(row().epoch()).isEqualTo(EPOCH_2);
        assertThat(row().bootToken()).isEqualTo(BOOT_A);
    }

    /// Evidence naming no process (the owner's own pull from a survivor) only raises the row and keeps the row's process, so
    /// that process's later reordered ack is still refused.
    @Test
    void evidenceWithoutBootToken_neitherLowersTheRow_norForgetsItsProcess() {
        replication.handleAck(replicateAck(REPLICA, STREAM, PARTITION, 10L, EPOCH_1, BOOT_A));
        registry.advanceWatermark(STREAM, PARTITION, REPLICA, 7L, ReplicationState.CAUGHT_UP, EPOCH_1);

        assertThat(row().confirmedOffset()).isEqualTo(10L);

        registry.advanceWatermark(STREAM, PARTITION, REPLICA, 12L, ReplicationState.CAUGHT_UP, EPOCH_1);
        replication.handleAck(replicateAck(REPLICA, STREAM, PARTITION, 11L, EPOCH_1, BOOT_A));

        assertThat(row().confirmedOffset()).isEqualTo(12L);
        assertThat(row().bootToken()).isEqualTo(BOOT_A);
    }

    /// The overlay a publish reads before the ack is recorded follows the same rule as the registry.
    @Test
    void overlaidAck_ofAnotherProcess_lowersTheCoveredOffset() {
        replication.handleAck(replicateAck(REPLICA, STREAM, PARTITION, 10L, EPOCH_1, BOOT_A));

        assertThat(replication.replicatedThrough(replicateAck(REPLICA, STREAM, PARTITION, 5L, EPOCH_1, BOOT_A), 1))
                  .as("the same process: never lowered")
                  .isEqualTo(10L);
        assertThat(replication.replicatedThrough(replicateAck(REPLICA, STREAM, PARTITION, 5L, EPOCH_1, BOOT_B), 1))
                  .as("another process: replaced")
                  .isEqualTo(5L);
    }

    /// The overlay looks only at the acking replica's own row: another replica of a different process must not make a same-process
    /// reordered ack look like a process change (#1697). Mutation: `ofSameProcess` without its `nodeId` filter.
    @Test
    void overlaidAck_withAnotherReplicaOfAnotherProcess_stillKeepsMaxForTheSameProcess() {
        var other = new NodeId("replica-2");

        registry.registerReplica(STREAM, PARTITION, other);
        replication.handleAck(replicateAck(REPLICA, STREAM, PARTITION, 10L, EPOCH_1, BOOT_A));
        replication.handleAck(replicateAck(other, STREAM, PARTITION, 3L, EPOCH_1, BOOT_B));

        assertThat(replication.replicatedThrough(replicateAck(REPLICA, STREAM, PARTITION, 5L, EPOCH_1, BOOT_A), 1))
                  .as("a reordered ack of the same process keeps its replica's max, whatever the other replica's process")
                  .isEqualTo(10L);
        assertThat(replication.replicatedThrough(replicateAck(REPLICA, STREAM, PARTITION, 5L, EPOCH_1, BOOT_B), 2))
                  .as("control: the other process's ack replaces, and the other replica still counts")
                  .isEqualTo(3L);
    }

    /// The legacy receive-handler factories name no replica process: their ack is taken as the row's own, so it never lowers a row
    /// another process confirmed. Mutation: the legacy `NO_BOOT_TOKEN` supplier returning a real token.
    @Test
    void legacyReceiveHandler_acksNameNoProcess() {
        replication.handleAck(replicateAck(REPLICA, STREAM, PARTITION, 10L, Epoch.ZERO, BOOT_A));

        var legacy = replicationReceiveHandler(REPLICA,
                                               (_, _, _, _, _, _) -> Result.success(0L),
                                               AcceptingReplicationTransport.accepting((_, message) -> replication.handleAck((ReplicationMessage.ReplicateAck) message)));

        legacy.onReplicateEvents(replicateEvents(OWNER, STREAM, PARTITION, 0L, payloads(5), timestamps(5), Epoch.ZERO));

        assertThat(row().confirmedOffset()).as("a legacy ack of 4 is not an ack of another process").isEqualTo(10L);
        assertThat(row().bootToken()).isEqualTo(BOOT_A);
    }

    /// The four- and five-argument ack factories name no replica process (the `PartitionBackfill` default sink relies on it).
    /// Mutation: either default returning a real token.
    @Test
    void ackFactoriesWithoutAToken_nameNoProcess() {
        assertThat(replicateAck(REPLICA, STREAM, PARTITION, 5L).bootToken()).isEqualTo(ReplicationMessage.ReplicateAck.NO_BOOT_TOKEN);
        assertThat(replicateAck(REPLICA, STREAM, PARTITION, 5L, EPOCH_1).bootToken()).isEqualTo(ReplicationMessage.ReplicateAck.NO_BOOT_TOKEN);

        replication.handleAck(replicateAck(REPLICA, STREAM, PARTITION, 10L, EPOCH_1, BOOT_A));
        replication.handleAck(replicateAck(REPLICA, STREAM, PARTITION, 5L, EPOCH_1));

        assertThat(row().confirmedOffset()).as("a no-token ack is not an ack of another process").isEqualTo(10L);
        assertThat(row().bootToken()).isEqualTo(BOOT_A);
    }

    /// A row built without a token names no replica process. Mutation: either descriptor factory defaulting to a real token.
    @Test
    void descriptorFactoriesWithoutAToken_nameNoProcess() {
        assertThat(replicaDescriptor(REPLICA, STREAM, PARTITION, 5L, ReplicationState.CAUGHT_UP).bootToken())
                  .isEqualTo(ReplicationMessage.ReplicateAck.NO_BOOT_TOKEN);
        assertThat(replicaDescriptor(REPLICA, STREAM, PARTITION, 5L, ReplicationState.CAUGHT_UP, EPOCH_1).bootToken())
                  .isEqualTo(ReplicationMessage.ReplicateAck.NO_BOOT_TOKEN);
    }

    /// NT-145b: two processes of ONE node id, each acking through the production receive-handler factory with its own boot
    /// token. The second process holds less; its ack must lower the owner's view. Pins the seam: a handler that dropped its
    /// token would send acks naming no process, which the owner takes as the row's own, and the view would stay at 19.
    @Test
    void restartedReplica_sameNodeId_holdingLess_lowersView() {
        replicaProcess(REPLICA, BOOT_A, 10L).onReplicateEvents(replicateEvents(OWNER,
                                                                                STREAM,
                                                                                PARTITION,
                                                                                10L,
                                                                                payloads(10),
                                                                                timestamps(10),
                                                                                Epoch.ZERO));

        assertThat(row().confirmedOffset()).isEqualTo(19L);

        replicaProcess(REPLICA, BOOT_B, 0L).onReplicateEvents(replicateEvents(OWNER,
                                                                               STREAM,
                                                                               PARTITION,
                                                                               0L,
                                                                               payloads(5),
                                                                               timestamps(5),
                                                                               Epoch.ZERO));

        assertThat(row().confirmedOffset()).as("the restarted process holds 0..4").isEqualTo(4L);
        assertThat(row().bootToken()).isEqualTo(BOOT_B);
    }

    private ReplicationReceiveHandler replicaProcess(NodeId self, long bootToken, long head) {
        return replicationReceiveHandler(self,
                                         (_, _, _, _, _, _) -> Result.success(0L),
                                         (_, _) -> head,
                                         AcceptingReplicationTransport.accepting((_, message) -> replication.handleAck((ReplicationMessage.ReplicateAck) message)),
                                         (_, _) -> {},
                                         ReplicationReceiveHandler.NO_DURABILITY_BARRIER,
                                         CommittedStreamOwnerSource.none(),
                                         OperatorWarningSink.logOnly(),
                                         () -> bootToken);
    }

    private ReplicaDescriptor row() {
        return registry.replicasFor(STREAM, PARTITION).getFirst();
    }

    private static List<byte[]> payloads(int count) {
        var list = new ArrayList<byte[]>(count);
        for (var i = 0; i < count; i++) {
            list.add(("p-" + i).getBytes());
        }
        return list;
    }

    private static List<Long> timestamps(int count) {
        var list = new ArrayList<Long>(count);
        for (var i = 0; i < count; i++) {
            list.add(1000L + i);
        }
        return list;
    }
}
