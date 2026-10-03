// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;

/// #1730: an ISR EXPANSION racing an in-flight acknowledgement. The owner's monitor admits JOINER at the high-water
/// mark (4) while offset 5 is appended and awaiting NEAR. Failover elects from the COMMITTED ISR, so once JOINER can be
/// committed an ack for 5 must wait for it — whether the await was registered before the expansion, or the expansion
/// is committed but not yet applied on this owner (Kafka's maximal ISR). The ack set is read through
/// [IsrMonitor#maximalIsr] exactly as `AetherNode` wires it.
class IsrExpansionAckTest {
    private static final NodeId OWNER = new NodeId("owner");
    private static final NodeId NEAR = new NodeId("replica-near");
    private static final NodeId JOINER = new NodeId("replica-joiner");
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final int CF2_PEERS = 1;
    private static final long IN_FLIGHT = 5L;

    private final ScheduledExecutorService neverFires = Executors.newSingleThreadScheduledExecutor();
    private final StreamPartitionOwnershipValue base = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(OWNER,
                                                                                                                  Epoch.epoch(1L,
                                                                                                                              1L,
                                                                                                                              1L),
                                                                                                                  1L,
                                                                                                                  HlcTimestamp.ZERO,
                                                                                                                  List.of(OWNER,
                                                                                                                          NEAR),
                                                                                                                  1L);
    private final AtomicReference<StreamPartitionOwnershipValue> committed = new AtomicReference<>(base);
    private ReplicaRegistry registry;
    private DefaultReplicationManager manager;
    private IsrMonitor monitor;

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        List.of(OWNER, NEAR, JOINER)
            .forEach(node -> registry.registerReplica(STREAM, PARTITION, node));
        manager = new DefaultReplicationManager(OWNER,
                                                registry,
                                                (_, _) -> {},
                                                () -> {},
                                                (_, _) -> neverFires.schedule(() -> {}, 1, TimeUnit.HOURS));
        // The proposal's consensus round never completes here: proposed, not yet applied on this owner.
        monitor = IsrMonitor.isrMonitor(OWNER,
                                        () -> List.of(new IsrMonitor.Owned(STREAM, PARTITION, committed.get(), IN_FLIGHT)),
                                        registry,
                                        () -> Option.some(new LeaderValue(OWNER, 1L)),
                                        _ -> Promise.promise(),
                                        TimeSpan.timeSpan(30).seconds(),
                                        () -> 0L);
        manager.inSyncReplicaSource((_, _) -> Option.some(monitor.maximalIsr(STREAM, PARTITION, committed.get())));
        manager.handleAck(replicateAck(NEAR, STREAM, PARTITION, 4L));
        manager.handleAck(replicateAck(JOINER, STREAM, PARTITION, 4L));
    }

    @AfterEach
    void tearDown() {
        neverFires.shutdownNow();
    }

    @Test
    void ackRegisteredBeforeACommittedExpansion_waitsForTheNewMember() {
        var ack = manager.awaitReplication(STREAM, PARTITION, IN_FLIGHT, CF2_PEERS);

        assertThat(monitor.tick()).as("precondition: the monitor proposes JOINER at the high-water mark 4").hasSize(1);
        committed.set(base.withIsr(List.of(OWNER, NEAR, JOINER)));
        manager.handleAck(replicateAck(NEAR, STREAM, PARTITION, IN_FLIGHT));
        assertThat(ack.isResolved()).as("JOINER is in the committed ISR and confirmed only through 4").isFalse();

        manager.handleAck(replicateAck(JOINER, STREAM, PARTITION, IN_FLIGHT));
        assertThat(ack.isResolved()).isTrue();
    }

    @Test
    void expansionProposedButNotYetApplied_ackWaitsForTheProposedMember() {
        var ack = manager.awaitReplication(STREAM, PARTITION, IN_FLIGHT, CF2_PEERS);

        assertThat(monitor.tick()).as("precondition: JOINER proposed; this owner has not applied the commit").hasSize(1);
        manager.handleAck(replicateAck(NEAR, STREAM, PARTITION, IN_FLIGHT));
        assertThat(ack.isResolved()).as("the cluster may already hold JOINER in the committed ISR").isFalse();

        manager.handleAck(replicateAck(JOINER, STREAM, PARTITION, IN_FLIGHT));
        assertThat(ack.isResolved()).isTrue();
    }

    /// Liveness control: a proposal superseded by a different commit (its CAS can never apply) stops counting, so the
    /// ack is not held for a member that will not join.
    @Test
    void proposalSupersededByAnotherCommit_stopsCounting() {
        var ack = manager.awaitReplication(STREAM, PARTITION, IN_FLIGHT, CF2_PEERS);

        assertThat(monitor.tick()).hasSize(1);
        committed.set(base.withIsr(List.of(OWNER, NEAR)));
        manager.handleAck(replicateAck(NEAR, STREAM, PARTITION, IN_FLIGHT));

        assertThat(ack.isResolved()).isTrue();
    }
}
