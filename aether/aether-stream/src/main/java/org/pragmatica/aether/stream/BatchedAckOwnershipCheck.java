// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.PartitionKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// [AckOwnershipCheck] with one barrier round per resolved batch of acknowledgements per partition (group commit, #2981
/// direction B). A waiter whose acks resolved while no round of its partition is open joins the OPEN batch; the batch's round
/// starts through `roundStarter`, so every waiter released by the same ack joins it. A waiter that arrives while a round is
/// in flight joins the next batch, which starts when that round ends: it never joins a round that started before its acks
/// resolved, because such a round may be ordered before the ownership change it must see. At most one round per partition is
/// in flight, and the committed record is read once per round.
final class BatchedAckOwnershipCheck implements AckOwnershipCheck {
    private record Waiter(Option<StreamPartitionOwnershipValue> admittedUnder, Promise<Unit> outcome) {}

    /// One partition's batching state, guarded by its own monitor (a leaf: only list and flag updates run under it).
    private static final class Rounds {
        private List<Waiter> open = new ArrayList<>();
        private boolean scheduled;
        private boolean running;
    }

    private final NodeId self;
    private final OwnerActivation.OwnershipRecordSource records;
    private final LinearizableBarrier barrier;
    private final Executor roundStarter;
    private final Map<PartitionKey, Rounds> rounds = new ConcurrentHashMap<>();

    private BatchedAckOwnershipCheck(NodeId self,
                                     OwnerActivation.OwnershipRecordSource records,
                                     LinearizableBarrier barrier,
                                     Executor roundStarter) {
        this.self = self;
        this.records = records;
        this.barrier = barrier;
        this.roundStarter = roundStarter;
    }

    static BatchedAckOwnershipCheck batchedAckOwnershipCheck(NodeId self,
                                                             OwnerActivation.OwnershipRecordSource records,
                                                             LinearizableBarrier barrier,
                                                             Executor roundStarter) {
        return new BatchedAckOwnershipCheck(self, records, barrier, roundStarter);
    }

    @Override
    public Promise<Unit> confirm(String stream, int partition, Option<StreamPartitionOwnershipValue> admittedUnder) {
        var waiter = new Waiter(admittedUnder, Promise.promise());

        join(PartitionKey.partitionKey(stream, partition), waiter);

        return waiter.outcome();
    }

    @Contract
    private void join(PartitionKey key, Waiter waiter) {
        var partitionRounds = rounds.computeIfAbsent(key, _ -> new Rounds());

        synchronized (partitionRounds) {
            partitionRounds.open.add(waiter);
            scheduleIfIdle(key, partitionRounds);
        }
    }

    /// Called under the partition's monitor: schedules the open batch's round unless one is already scheduled or running.
    @Contract
    private void scheduleIfIdle(PartitionKey key, Rounds partitionRounds) {
        if (partitionRounds.scheduled || partitionRounds.running || partitionRounds.open.isEmpty()) {
            return;
        }

        partitionRounds.scheduled = true;
        roundStarter.execute(() -> startRound(key, partitionRounds));
    }

    @Contract
    private void startRound(PartitionKey key, Rounds partitionRounds) {
        List<Waiter> batch;

        synchronized (partitionRounds) {
            batch = partitionRounds.open;
            partitionRounds.open = new ArrayList<>();
            partitionRounds.scheduled = false;
            partitionRounds.running = true;
        }
        // A dependent step: it runs on the thread that resolves the round, so the record read follows the round's local apply.
        barrier.awaitRound(key.streamName(),
                           key.partition())
               .withResult(round -> finishRound(key, partitionRounds, batch, round));
    }

    @Contract
    private void finishRound(PartitionKey key, Rounds partitionRounds, List<Waiter> batch, Result<Unit> round) {
        var outcome = round.map(_ -> records.committed(key.streamName(), key.partition()));

        batch.forEach(waiter -> waiter.outcome()
                                      .resolve(outcome.flatMap(committed -> decide(key, waiter, committed))));
        synchronized (partitionRounds) {
            partitionRounds.running = false;
            scheduleIfIdle(key, partitionRounds);
        }
    }

    /// The acknowledgement may complete only while the committed ownership is the one the append was admitted under, and
    /// that ownership names this node (or no record exists at all, before and after: nobody else can have activated).
    private Result<Unit> decide(PartitionKey key, Waiter waiter, Option<StreamPartitionOwnershipValue> committed) {
        return OwnerActivation.sameOwnership(waiter.admittedUnder(), committed) && namesSelfOrNone(committed)
               ? Result.unitResult()
               : new StreamError.OwnershipMovedBeforeAck(key.streamName(), key.partition()).result();
    }

    private boolean namesSelfOrNone(Option<StreamPartitionOwnershipValue> committed) {
        return committed.map(record -> record.owner()
                                             .equals(self))
                        .or(true);
    }
}
