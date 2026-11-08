// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;

/// #2888: the owner keeps a replica's watermark monotone within one owner epoch, so a reordered or duplicated ack (transport
/// retry, the #2882 resend) cannot move it backwards. A new epoch is a new lineage and its first ack replaces the row.
class OwnerAckWatermarkMonotoneTest {
    private static final NodeId SELF = new NodeId("owner");
    private static final NodeId PEER = new NodeId("peer");
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final Epoch EPOCH_1 = Epoch.epoch(1L, 1L, 0L);
    private static final Epoch EPOCH_2 = Epoch.epoch(1L, 2L, 0L);

    private ReplicaRegistry registry;
    private ReplicationManager replication;
    private CopyOnWriteArrayList<Long> persisted;

    @BeforeEach
    void setUp() {
        persisted = new CopyOnWriteArrayList<>();
        registry = replicaRegistry((_, _, _, offset) -> persisted.add(offset));
        registry.registerReplica(STREAM, PARTITION, PEER);
        replication = replicationManager(SELF, registry);
    }

    @Test
    void lowerAck_afterHigherAck_inTheSameEpoch_leavesTheWatermark() {
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 10L, EPOCH_1));
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 7L, EPOCH_1));

        assertThat(watermark()).isEqualTo(10L);
        assertThat(persisted).as("the persisted watermark follows the surviving row").containsExactly(10L, 10L);
    }

    @Test
    void higherAck_inTheSameEpoch_advancesTheWatermark() {
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 7L, EPOCH_1));
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 10L, EPOCH_1));

        assertThat(watermark()).isEqualTo(10L);
    }

    @Test
    void equalAck_inTheSameEpoch_isApplied() {
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 10L, EPOCH_1));
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 10L, EPOCH_1));

        assertThat(watermark()).isEqualTo(10L);
    }

    @Test
    void lowerAck_underANewEpoch_replacesTheRow() {
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 10L, EPOCH_1));
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 4L, EPOCH_2));

        assertThat(watermark()).isEqualTo(4L);
        assertThat(registry.replicasFor(STREAM, PARTITION).getFirst().epoch()).isEqualTo(EPOCH_2);
    }

    @Test
    void ack_fromAnOlderEpoch_leavesTheNewerEpochRow() {
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 4L, EPOCH_2));
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 10L, EPOCH_1));

        assertThat(watermark()).isEqualTo(4L);
        assertThat(registry.replicasFor(STREAM, PARTITION).getFirst().epoch()).isEqualTo(EPOCH_2);
    }

    private long watermark() {
        return registry.replicasFor(STREAM, PARTITION).getFirst().confirmedOffset();
    }
}
