// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.resource.entity.EntityLogError;
import org.pragmatica.aether.resource.entity.EntityLogSubstrate;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.AckOwnershipCheck;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.replication.ReplicationReceiveHandler;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.StreamEntityLogSubstrate.streamEntityLogSubstrate;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #2981 direction B on the entity-log writer: `StreamEntityLogSubstrate#append` acknowledges through the same
/// [StreamPartitionManager#awaitReplication] site, so an entity append a deposed owner's old in-sync peer confirmed is NOT
/// acknowledged. It surfaces as [EntityLogError.ReplicationBarrierUnmet] carrying [StreamError.OwnershipMovedBeforeAck],
/// whose documented recovery (read back, local fold advanced) is unchanged.
///
/// Real substrates, real managers, real replication between the owner `X` and its in-sync peer `R`. The barrier stand-in
/// applies the committed record into `X`'s view, as a Rabia no-op round does.
class EntityLogAckOwnershipCheckTest {
    private static final NodeId X = new NodeId("x");
    private static final NodeId Y = new NodeId("y");
    private static final NodeId R = new NodeId("r");
    private static final String KEYSPACE = "orders";
    private static final int PARTITION = 0;
    private static final TimeSpan BOUND = TimeSpan.timeSpan(3).seconds();
    private static final ReplicationFactors CF_TWO = new ReplicationFactors(3, 2);

    private final AtomicReference<ReplicationManager> ownerReplication = new AtomicReference<>();
    private final AtomicReference<Option<StreamPartitionOwnershipValue>> localView = new AtomicReference<>(Option.some(record(X, 2)));
    private final AtomicReference<Option<StreamPartitionOwnershipValue>> committed = new AtomicReference<>(Option.some(record(X, 2)));
    private final BlockingQueue<Runnable> roundStarts = new LinkedBlockingQueue<>();
    private StreamPartitionManager owner;
    private StreamPartitionManager replica;
    private EntityLogSubstrate substrate;
    private ReplicationReceiveHandler receiver;

    @BeforeEach
    void setUp() {
        replica = streamPartitionManager(Long.MAX_VALUE);
        receiver = ReplicationReceiveHandler.replicationReceiveHandler(R,
                                                                       replica::appendRecovered,
                                                                       replica::nextExpectedOffset,
                                                                       this::toOwner,
                                                                       (_, _) -> {});
        var registry = ReplicaRegistry.replicaRegistry();

        registry.registerReplica(EntityPartitionArc.arcName(KEYSPACE), PARTITION, X);
        registry.registerReplica(EntityPartitionArc.arcName(KEYSPACE), PARTITION, R);
        ownerReplication.set(ReplicationManager.replicationManager(X, registry, this::toReplica));
        owner = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, ownerReplication.get());
        substrate = substrate(owner);
        substrate.ensureLog(KEYSPACE, 1, CF_TWO)
                 .unwrap();
        substrate(replica).ensureLog(KEYSPACE, 1, CF_TWO)
                          .unwrap();
        owner.ownershipRecords((_, _) -> localView.get());
        owner.ackOwnershipCheck(AckOwnershipCheck.batched(X, (_, _) -> localView.get(), this::round, roundStarts::add));
    }

    @AfterEach
    void tearDown() {
        owner.close();
        replica.close();
    }

    @Test
    void append_deposedOwnerAckedByOldIsr_isReplicationBarrierUnmetNotOk() throws InterruptedException {
        committed.set(Option.some(record(Y, 3)));

        var outcome = appendAndStartRound();

        assertThat(outcome.isFailure()).as("a deposed owner must not acknowledge an entity append (%s)", outcome)
                                       .isTrue();
        outcome.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(EntityLogError.ReplicationBarrierUnmet.class,
                                                                           unmet -> assertThat(unmet.reason()).isInstanceOf(StreamError.OwnershipMovedBeforeAck.class)));
    }

    @Test
    void append_ownershipUnchanged_acknowledges() throws InterruptedException {
        var outcome = appendAndStartRound();

        assertThat(outcome.isSuccess()).as("%s", outcome)
                                       .isTrue();
    }

    private Result<Long> appendAndStartRound() throws InterruptedException {
        var appended = substrate.append(KEYSPACE, PARTITION, new byte[] {1, 2, 3});
        var start = roundStarts.poll(BOUND.millis(), TimeUnit.MILLISECONDS);

        assertThat(start).as("the entity append reached the acknowledgement check").isNotNull();
        start.run();

        return appended.timeout(BOUND)
                       .await();
    }

    private Promise<Unit> round(String stream, int partition) {
        localView.set(committed.get());

        return Promise.unitPromise();
    }

    private Promise<WriteOutcome> toReplica(NodeId target, ReplicationMessage message) {
        if (message instanceof ReplicationMessage.ReplicateEvents events) {
            receiver.onReplicateEvents(events);
        }
        return Promise.success(new WriteOutcome.Sent(target));
    }

    private Promise<WriteOutcome> toOwner(NodeId target, ReplicationMessage message) {
        if (message instanceof ReplicationMessage.ReplicateAck ack) {
            ownerReplication.get()
                            .handleAck(ack);
        }
        return Promise.success(new WriteOutcome.Sent(target));
    }

    private static EntityLogSubstrate substrate(StreamPartitionManager manager) {
        return streamEntityLogSubstrate(manager,
                                        (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0, false),
                                        null,
                                        null,
                                        EvictionListener.NOOP,
                                        null,
                                        null,
                                        null);
    }

    private static StreamPartitionOwnershipValue record(NodeId node, int term) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(node,
                                                                           Epoch.epoch(0L, term, 0),
                                                                           term,
                                                                           HlcTimestamp.ZERO,
                                                                           List.of(node, R),
                                                                           term);
    }
}
