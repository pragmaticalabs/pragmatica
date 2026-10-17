// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationError.General.NOT_ENOUGH_REPLICAS;
import static org.pragmatica.aether.stream.replication.ReplicationError.General.REPLICATION_TIMEOUT;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;

/// #1730: a confirmation-factor >= 2 acknowledgement needs EVERY member of the partition's committed in-sync replica
/// set, and only them. Deterministic: no failure detector and no clock decide anything here — the ack timeout is a
/// captured task the test fires by hand, standing for "the member never answered".
///
/// The minority-owner case is the ticket's: an owner cut off with fewer than all its ISR members can collect only
/// their acks, and the member on the far side of the partition never answers. The committed ISR is the owner's own
/// stale view (a partitioned owner cannot commit a shrink), so its publish times out and is never acknowledged.
class IsrAckRuleTest {
    private static final NodeId OWNER = new NodeId("owner");
    private static final NodeId NEAR = new NodeId("replica-near");
    private static final NodeId FAR = new NodeId("replica-far");
    private static final NodeId OUTSIDER = new NodeId("replica-outside");
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final int CF2_PEERS = 1;

    private ReplicaRegistry registry;
    private List<Runnable> timeouts;
    private DefaultReplicationManager manager;
    private final ScheduledExecutorService neverFires = Executors.newSingleThreadScheduledExecutor();

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        List.of(OWNER, NEAR, FAR, OUTSIDER)
            .forEach(node -> registry.registerReplica(STREAM, PARTITION, node));
        timeouts = new CopyOnWriteArrayList<>();
        manager = new DefaultReplicationManager(OWNER, registry, (_, _) -> {}, () -> {}, (task, _) -> {
            timeouts.add(task);

            return neverFires.schedule(() -> {}, 1, TimeUnit.HOURS);
        });
    }

    @AfterEach
    void tearDown() {
        neverFires.shutdownNow();
    }

    @Test
    void awaitReplication_waitsForEveryIsrMember_notAnyConfirmationFactorMinusOne() {
        isr(OWNER, NEAR, FAR);
        var ack = manager.awaitReplication(STREAM, PARTITION, 0L, CF2_PEERS);

        manager.handleAck(replicateAck(NEAR, STREAM, PARTITION, 0L));
        assertThat(ack.isResolved()).as("one of two ISR peers is not enough, although CF - 1 = 1").isFalse();

        manager.handleAck(replicateAck(FAR, STREAM, PARTITION, 0L));
        assertThat(ack.isResolved()).isTrue();
        assertThat(ack.await().isSuccess()).isTrue();
    }

    /// The partitioned minority owner: NEAR is on its side, FAR is not. FAR never answers, the owner cannot commit an
    /// ISR without FAR, so the timeout is the only outcome — `PublishOutcomeUnknown` upstream, never an ack.
    @Test
    void awaitReplication_minorityOwner_neverAcks_whileAnIsrMemberIsUnreachable() {
        isr(OWNER, NEAR, FAR);
        var ack = manager.awaitReplication(STREAM, PARTITION, 0L, CF2_PEERS);

        manager.handleAck(replicateAck(NEAR, STREAM, PARTITION, 0L));
        manager.handleAck(replicateAck(OUTSIDER, STREAM, PARTITION, 0L));
        assertThat(ack.isResolved()).as("the near replica and a non-ISR replica cannot stand in for FAR").isFalse();

        timeouts.forEach(Runnable::run);

        assertFailedWith(ack.await(), REPLICATION_TIMEOUT);
    }

    @Test
    void awaitReplication_ackFromOutsideTheIsr_isNotCounted() {
        isr(OWNER, NEAR);
        var ack = manager.awaitReplication(STREAM, PARTITION, 0L, CF2_PEERS);

        manager.handleAck(replicateAck(OUTSIDER, STREAM, PARTITION, 0L));
        assertThat(ack.isResolved()).isFalse();

        manager.handleAck(replicateAck(NEAR, STREAM, PARTITION, 0L));
        assertThat(ack.await().isSuccess()).isTrue();
    }

    /// min-ISR = CF: an ISR holding only the owner refuses a CF 2 publish BEFORE the append, however many replicas
    /// are registered — registered is not in sync.
    @Test
    void ensureReplicaFloor_belowMinIsr_refuses_evenWithRegisteredReplicas() {
        isr(OWNER);

        assertFailedWith(manager.ensureReplicaFloor(STREAM, PARTITION, CF2_PEERS), NOT_ENOUGH_REPLICAS);
        assertFailedWith(manager.awaitReplication(STREAM, PARTITION, 0L, CF2_PEERS).await(), NOT_ENOUGH_REPLICAS);
    }

    @Test
    void replicatedThrough_isTheLowestOffsetEveryIsrMemberConfirmed() {
        isr(OWNER, NEAR, FAR);
        manager.handleAck(replicateAck(NEAR, STREAM, PARTITION, 9L));
        manager.handleAck(replicateAck(FAR, STREAM, PARTITION, 4L));
        manager.handleAck(replicateAck(OUTSIDER, STREAM, PARTITION, 20L));

        assertThat(manager.replicatedThrough(STREAM, PARTITION, CF2_PEERS)).isEqualTo(4L);
    }

    /// Control for the rule: with no committed ISR (no record, or one minted before #1730) the previous
    /// any-`minAcks`-registered-replicas rule still applies, so the cases above are the ISR's doing.
    @Test
    void awaitReplication_withoutACommittedIsr_keepsTheAnyReplicaRule() {
        var ack = manager.awaitReplication(STREAM, PARTITION, 0L, CF2_PEERS);

        manager.handleAck(replicateAck(OUTSIDER, STREAM, PARTITION, 0L));

        assertThat(ack.await().isSuccess()).isTrue();
    }

    private static void assertFailedWith(Result<?> result, Cause expected) {
        assertThat(result.isFailure()).as("expected %s, got %s", expected, result).isTrue();
        result.onFailure(cause -> assertThat(cause).isEqualTo(expected));
    }

    private void isr(NodeId... members) {
        var isr = List.of(members);

        manager.inSyncReplicaSource((_, _) -> Option.some(isr));
    }
}
