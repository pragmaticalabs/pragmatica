// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupRequest.catchupRequest;

/// A4 backfill orchestrator. When a node newly becomes a replica for a non-empty `(stream, partition)`
/// it must pull the partition's existing events from an up-to-date source and reach `CAUGHT_UP`
/// before it is eligible to serve reads.
///
/// ## Source selection
/// From {@link ReplicaRegistry#replicasFor} it picks the `CAUGHT_UP` replica (owner or replica) with
/// the highest `confirmedOffset`, excluding self. If there is no such source (no peer is caught up,
/// or self is the only replica) backfill cannot proceed and the promise fails — self stays SYNCING.
///
/// ## Flow
///   1. read self's current `confirmedOffset` from the registry (`-1` for a fresh replica),
///   2. issue a single {@link CatchupTransport#requestCatchup} from `confirmedOffset + 1`; the
///      production transport ({@link ForwardCatchupTransport}) pages internally until the source is
///      drained,
///   3. apply every returned event into the local ring via {@link StreamPartitionRecovery}
///      (offset-preserving, non-replicating),
///   4. on full success flip self to `CAUGHT_UP` at the source watermark
///      ({@link ReplicaRegistry#updateWatermark}).
///
/// ## Failure safety
/// If the catch-up request fails (source unreachable / timeout) or any local apply fails, the promise
/// fails and `updateWatermark` is NOT called — self remains `SYNCING` and is excluded from the read
/// path by the existing `selectReplicaAndRead` filter. No partial-success state machine: a fresh
/// replica re-runs backfill on the next reconcile.
public final class PartitionBackfill {
    private static final Logger log = LoggerFactory.getLogger(PartitionBackfill.class);

    private final ReplicaRegistry registry;
    private final StreamPartitionRecovery partitionRecovery;
    private final CatchupTransport transport;
    private final NodeId self;

    private PartitionBackfill(ReplicaRegistry registry,
                              StreamPartitionRecovery partitionRecovery,
                              CatchupTransport transport,
                              NodeId self) {
        this.registry = registry;
        this.partitionRecovery = partitionRecovery;
        this.transport = transport;
        this.self = self;
    }

    public static PartitionBackfill partitionBackfill(ReplicaRegistry registry,
                                                      StreamPartitionRecovery partitionRecovery,
                                                      CatchupTransport transport,
                                                      NodeId self) {
        return new PartitionBackfill(registry, partitionRecovery, transport, self);
    }

    /// Backfill `(streamName, partition)` onto self from the best caught-up peer. Resolves with the
    /// number of events applied on success; fails (leaving self SYNCING) when no source is available
    /// or the source/apply path fails.
    public Promise<Long> backfill(String streamName, int partition) {
        var replicas = registry.replicasFor(streamName, partition);
        return selectSource(replicas).fold(() -> noSource(streamName, partition),
                                           source -> backfillFrom(streamName, partition, source, selfConfirmedOffset(replicas)));
    }

    private Promise<Long> backfillFrom(String streamName, int partition, ReplicaDescriptor source, long selfConfirmed) {
        var fromOffset = selfConfirmed + 1;
        var request = catchupRequest(source.nodeId(), streamName, partition, fromOffset);
        log.debug("Backfill {}[{}]: source={} from offset {} (source watermark {})",
                  streamName, partition, source.nodeId(), fromOffset, source.confirmedOffset());
        return transport.requestCatchup(source.nodeId(), request)
                        .flatMap(response -> applyAndPromote(streamName, partition, source, response));
    }

    private Promise<Long> applyAndPromote(String streamName,
                                          int partition,
                                          ReplicaDescriptor source,
                                          ReplicationMessage.CatchupResponse response) {
        return applyEvents(streamName, partition, response)
                .fold(cause -> failApply(streamName, partition, cause),
                      applied -> promote(streamName, partition, source, response, applied));
    }

    private Promise<Long> promote(String streamName,
                                  int partition,
                                  ReplicaDescriptor source,
                                  ReplicationMessage.CatchupResponse response,
                                  long applied) {
        var watermark = Math.max(response.toOffset(), source.confirmedOffset());
        registry.updateWatermark(streamName, partition, self, watermark);
        log.info("Backfill {}[{}] complete: applied {} events, self CAUGHT_UP at offset {}",
                 streamName, partition, applied, watermark);
        return Promise.success(applied);
    }

    /// Apply every recovered event in order. Short-circuits to the first failure, so a local append
    /// error aborts the backfill before promotion.
    private org.pragmatica.lang.Result<Long> applyEvents(String streamName,
                                                         int partition,
                                                         ReplicationMessage.CatchupResponse response) {
        var payloads = response.payloads();
        var timestamps = response.timestamps();
        var count = Math.min(payloads.size(), timestamps.size());
        return IntStream.range(0, count)
                        .boxed()
                        .reduce(org.pragmatica.lang.Result.success(0L),
                                (acc, i) -> acc.flatMap(applied -> partitionRecovery.appendRecoveredEvent(streamName,
                                                                                                          partition,
                                                                                                          payloads.get(i),
                                                                                                          timestamps.get(i))
                                                                                    .map(_ -> applied + 1)),
                                (a, _) -> a);
    }

    private Promise<Long> failApply(String streamName, int partition, Cause cause) {
        log.warn("Backfill {}[{}] failed applying events: {} — staying SYNCING", streamName, partition, cause.message());
        return Promise.failure(cause);
    }

    private Promise<Long> noSource(String streamName, int partition) {
        log.warn("Backfill {}[{}]: no caught-up source available — staying SYNCING", streamName, partition);
        return BackfillError.NO_SOURCE_REPLICA.promise();
    }

    private long selfConfirmedOffset(List<ReplicaDescriptor> replicas) {
        return replicas.stream()
                       .filter(descriptor -> descriptor.nodeId().equals(self))
                       .mapToLong(ReplicaDescriptor::confirmedOffset)
                       .max()
                       .orElse(-1L);
    }

    private Option<ReplicaDescriptor> selectSource(List<ReplicaDescriptor> replicas) {
        return Option.from(replicas.stream()
                                   .filter(descriptor -> !descriptor.nodeId().equals(self))
                                   .filter(descriptor -> descriptor.state() == ReplicationState.CAUGHT_UP)
                                   .max(Comparator.comparingLong(ReplicaDescriptor::confirmedOffset)));
    }
}
