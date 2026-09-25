// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.node.AetherNode.StreamOwnershipViews;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource.CommittedOwner;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.replication.ReplicationReceiveHandler;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.StreamConfig.streamConfig;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateEvents.replicateEvents;
import static org.pragmatica.aether.stream.replication.ReplicationReceiveHandler.NO_DURABILITY_BARRIER;

/// #1230 ruling 3: the write side (append admission + replication sender check) reads the RAW committed
/// ownership record, never the #568 liveness-filtered view.
///
/// Fixture: the committed record names owner B; this node is A; the membership FSM has seen B depart, so the
/// liveness view reads B's record as ABSENT. Absent admits (ruling 1) — so a write side fed the filtered view
/// would let A append and accept A's batches while B, possibly only partitioned, still writes. Each
/// write-side assertion is paired with a control proving the routing view really does read absent here, so
/// the test discriminates between the two sources rather than passing for both.
class StreamOwnershipViewsTest {
    private static final NodeId SELF = new NodeId("node-a");
    private static final NodeId COMMITTED_OWNER = new NodeId("node-b");
    private static final String STREAM = "owned-stream";
    private static final int PARTITION = 0;
    private static final Epoch COMMITTED_EPOCH = Epoch.epoch(4L, 2L);

    private StreamOwnershipViews views;
    private StreamPartitionManager partitionManager;

    @BeforeEach
    void setUp() {
        var fsm = MembershipFsm.membershipFsm();

        fsm.onSwimHealthy(SELF, 1L);
        fsm.onSwimHealthy(COMMITTED_OWNER, 1L);
        fsm.onSwimDeparted(COMMITTED_OWNER, 2L);

        CommittedStreamOwnerSource committed = (_, _) -> Option.some(new CommittedOwner(COMMITTED_OWNER, COMMITTED_EPOCH));
        views = StreamOwnershipViews.streamOwnershipViews(committed, fsm);
        partitionManager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        partitionManager.createStream(streamConfig(STREAM));
    }

    @AfterEach
    void tearDown() {
        partitionManager.close();
    }

    @Test
    void routing_readsDeadOwnersRecordAsAbsent_control() {
        assertThat(views.routing().committedOwner(STREAM, PARTITION).isEmpty()).isTrue();
    }

    @Test
    void writeAdmission_refusesNonOwnerAppend_whenCommittedOwnerIsReportedDead() {
        partitionManager.ownerWriteAdmission(views.writeAdmission(SELF));

        partitionManager.publishLocal(STREAM, PARTITION, "e0".getBytes(), 1L)
                        .onSuccessRun(Assertions::fail)
                        .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.NotOwnerAppend(STREAM,
                                                                                                       PARTITION,
                                                                                                       COMMITTED_OWNER)));
        assertThat(partitionManager.nextExpectedOffset(STREAM, PARTITION)).isZero();
    }

    @Test
    void writeAuthority_refusesBatchFromNonOwner_whenCommittedOwnerIsReportedDead() {
        var acks = new ArrayList<ReplicationMessage.ReplicateAck>();
        var appends = new AtomicInteger();
        var handler = ReplicationReceiveHandler.replicationReceiveHandler(new NodeId("node-c"),
                                                                          (_, _, _, _, _, _) -> Result.success((long) appends.getAndIncrement()),
                                                                          (_, _) -> 0L,
                                                                          (_, message) -> acks.add((ReplicationMessage.ReplicateAck) message),
                                                                          (_, _) -> {},
                                                                          NO_DURABILITY_BARRIER,
                                                                          views.writeAuthority());

        handler.onReplicateEvents(replicateEvents(SELF,
                                                  STREAM,
                                                  PARTITION,
                                                  0L,
                                                  List.of("a".getBytes()),
                                                  List.of(1L),
                                                  COMMITTED_EPOCH));

        assertThat(appends.get()).isZero();
        assertThat(acks).isEmpty();
    }
}
