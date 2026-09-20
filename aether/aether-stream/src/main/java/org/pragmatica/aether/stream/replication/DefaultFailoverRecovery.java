// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.pragmatica.aether.stream.replication.FailoverRecovery.RecoveryResult.recoveryResult;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupRequest.catchupRequest;


/// #1244 backfill-commit ruling, applied to this failover path on 2026-09-20 (CTO ruling, #1235 × #1244):
/// replica WAL frames carry no per-record fsync and a WAL-backed record becomes visible on this replica
/// only at the barrier, so each recovered partition commits through the replica WAL barrier
/// (`StreamPartitionManager::syncReplicated`) ONCE, after its last `appendRecoveredEvent` and before it
/// counts as recovered — one fsync per partition per run, and the recovered records are visible here when
/// the run completes instead of when the next live batch's barrier happens to cover them. A failed
/// barrier fails the run: the events landed in RAM but were never made durable or visible here.
final class DefaultFailoverRecovery implements FailoverRecovery {
    private final ReplicaRegistry registry;
    private final StreamPartitionRecovery partitionRecovery;
    private final CatchupTransport transport;
    private final ReplicationReceiveHandler.ReplicaDurability durability;

    DefaultFailoverRecovery(ReplicaRegistry registry,
                            StreamPartitionRecovery partitionRecovery,
                            CatchupTransport transport,
                            ReplicationReceiveHandler.ReplicaDurability durability) {
        this.registry = registry;
        this.partitionRecovery = partitionRecovery;
        this.transport = transport;
        this.durability = durability;
    }

    @Override
    public Promise<RecoveryResult> recover(String streamName, int partitionCount) {
        var startMs = System.currentTimeMillis();
        var partitionIndices = IntStream.range(0, partitionCount).boxed().toList();

        return recoverPartitions(streamName, partitionIndices, 0, RecoveryProgress.EMPTY).map(progress -> progress.toResult(startMs));
    }

    private Promise<RecoveryProgress> recoverPartitions(String streamName,
                                                        List<Integer> partitions,
                                                        int index,
                                                        RecoveryProgress progress) {
        if (index >= partitions.size()) {
            return Promise.success(progress);
        }

        return recoverSinglePartition(streamName,
                                      partitions.get(index)).map(progress::withEvents)
                                     .flatMap(updated -> recoverPartitions(streamName, partitions, index + 1, updated));
    }

    private Promise<Long> recoverSinglePartition(String streamName, int partition) {
        var replicas = registry.replicasFor(streamName, partition);

        return findBestReplica(replicas).map(best -> requestCatchupFromReplica(streamName, partition, best))
                              .or(Promise.success(0L));
    }

    private Promise<Long> requestCatchupFromReplica(String streamName, int partition, ReplicaDescriptor bestReplica) {
        var fromOffset = bestReplica.confirmedOffset() + 1;
        var request = catchupRequest(bestReplica.nodeId(), streamName, partition, fromOffset);

        return transport.requestCatchup(bestReplica.nodeId(),
                                        request)
                        .map(response -> applyRecoveredEvents(streamName, partition, response))
                        .flatMap(count -> durability.sync(streamName, partition)
                                                    .map(_ -> count));
    }

    private long applyRecoveredEvents(String streamName, int partition, ReplicationMessage.CatchupResponse response) {
        var payloads = response.payloads();
        var timestamps = response.timestamps();
        var count = Math.min(payloads.size(), timestamps.size());

        IntStream.range(0, count).forEach(i -> partitionRecovery.appendRecoveredEvent(streamName,
                                                                                      partition,
                                                                                      payloads.get(i),
                                                                                      timestamps.get(i)));

        return count;
    }

    private static Option<ReplicaDescriptor> findBestReplica(List<ReplicaDescriptor> replicas) {
        return Option.from(replicas.stream().max(Comparator.comparingLong(ReplicaDescriptor::confirmedOffset)));
    }

    record RecoveryProgress(int partitionsRecovered, long eventsReplayed) {
        static final RecoveryProgress EMPTY = new RecoveryProgress(0, 0L);

        RecoveryProgress withEvents(long events) {
            return events > 0
                   ? new RecoveryProgress(partitionsRecovered + 1, eventsReplayed + events)
                   : this;
        }

        RecoveryResult toResult(long startMs) {
            return recoveryResult(partitionsRecovered, eventsReplayed, System.currentTimeMillis() - startMs);
        }
    }
}
