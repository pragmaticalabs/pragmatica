// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource.CommittedOwner;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateEvents.replicateEvents;
import static org.pragmatica.aether.stream.replication.ReplicationReceiveHandler.NO_DURABILITY_BARRIER;
import static org.pragmatica.aether.stream.replication.ReplicationReceiveHandler.replicationReceiveHandler;

/// #1230 item 3: a replicated batch is landed and acked only when its sender can be the committed owner.
///
/// "The committed owner for that epoch" is decidable only when this replica's committed record is AT the
/// batch's epoch. Older than the record → a deposed owner, refused. Newer than the record → this replica's
/// view lags a commit the sender has already observed, so it cannot judge and accepts; the owner-handoff
/// flow depends on that. No record → nothing to judge against, accepted (cold-start window).
class ReplicationSenderValidationTest {
    private static final NodeId SELF = new NodeId("replica-self");
    private static final NodeId OWNER = new NodeId("committed-owner");
    private static final NodeId OTHER_REPLICA = new NodeId("other-replica");
    private static final String STREAM = "events";
    private static final int PARTITION = 0;
    private static final Epoch COMMITTED_EPOCH = Epoch.epoch(2L, 3L);

    private final List<ReplicationMessage.ReplicateAck> acks = new ArrayList<>();
    private final AtomicInteger appends = new AtomicInteger();

    private ReplicationReceiveHandler handler(long localNext, CommittedStreamOwnerSource committedOwners) {
        return replicationReceiveHandler(SELF,
                                         (_, _, _, _, _, _) -> Result.success((long) appends.getAndIncrement()),
                                         (_, _) -> localNext,
                                         (_, message) -> acks.add((ReplicationMessage.ReplicateAck) message),
                                         (_, _) -> {},
                                         NO_DURABILITY_BARRIER,
                                         committedOwners);
    }

    private static CommittedStreamOwnerSource committedTo(NodeId owner) {
        return (_, _) -> Option.some(new CommittedOwner(owner, COMMITTED_EPOCH));
    }

    private static ReplicationMessage.ReplicateEvents batch(NodeId sender, long fromOffset, Epoch epoch) {
        return replicateEvents(sender,
                               STREAM,
                               PARTITION,
                               fromOffset,
                               List.of("a".getBytes(), "b".getBytes()),
                               List.of(1L, 2L),
                               epoch);
    }

    @Test
    void onReplicateEvents_neitherAppendsNorAcks_whenSenderIsNotTheCommittedOwner() {
        handler(0L, committedTo(OWNER)).onReplicateEvents(batch(OTHER_REPLICA, 0L, COMMITTED_EPOCH));

        assertThat(appends.get()).isZero();
        assertThat(acks).isEmpty();
    }

    /// The ticket's divergence scenario: the non-owner's batch sits below this node's head, and was
    /// previously re-acked as a "stale duplicate" — satisfying the non-owner's min-sync barrier for an offset
    /// this node holds a DIFFERENT event at.
    @Test
    void onReplicateEvents_doesNotReAckDuplicate_whenSenderIsNotTheCommittedOwner() {
        handler(10L, committedTo(OWNER)).onReplicateEvents(batch(OTHER_REPLICA, 4L, COMMITTED_EPOCH));

        assertThat(acks).isEmpty();
    }

    @Test
    void onReplicateEvents_refused_whenBatchEpochIsOlderThanTheCommittedRecord() {
        handler(0L, committedTo(OWNER)).onReplicateEvents(batch(OWNER, 0L, Epoch.epoch(2L, 2L)));

        assertThat(appends.get()).isZero();
        assertThat(acks).isEmpty();
    }

    @Test
    void onReplicateEvents_appliesAndAcks_whenSenderIsTheCommittedOwner() {
        handler(0L, committedTo(OWNER)).onReplicateEvents(batch(OWNER, 0L, COMMITTED_EPOCH));

        assertThat(appends.get()).isEqualTo(2);
        assertThat(acks).extracting(ReplicationMessage.ReplicateAck::confirmedOffset).containsExactly(1L);
    }

    /// Owner handoff: the new owner observed its commit before this replica did. Refusing here would
    /// withhold acks from the legitimate owner until this replica's KV caught up.
    @Test
    void onReplicateEvents_appliesAndAcks_whenBatchEpochIsNewerThanThisReplicasCommittedView() {
        handler(0L, committedTo(OWNER)).onReplicateEvents(batch(OTHER_REPLICA, 0L, COMMITTED_EPOCH.nextCounter()));

        assertThat(appends.get()).isEqualTo(2);
        assertThat(acks).hasSize(1);
    }

    @Test
    void onReplicateEvents_appliesAndAcks_whenNoOwnershipIsCommitted() {
        handler(0L, CommittedStreamOwnerSource.none()).onReplicateEvents(batch(OTHER_REPLICA, 0L, Epoch.ZERO));

        assertThat(appends.get()).isEqualTo(2);
        assertThat(acks).hasSize(1);
    }
}
