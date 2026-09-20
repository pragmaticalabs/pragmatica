// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.PublishOutcomeUnknown;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.slice.StreamPublisher.StreamPublisherError;
import org.pragmatica.aether.slice.stream.PublishOutcome;
import org.pragmatica.aether.stream.consensus.ConsensusPublishPath;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.replication.ReplicaPlacement;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Serializer;


public final class DefaultStreamPublisher<T> implements StreamPublisher<T> {
    private final Serializer serializer;
    private final String streamName;
    private final int partitionCount;
    private final Option<Function<T, Object>> partitionKeyExtractor;
    private final AtomicLong roundRobinCounter;
    private final ConsistencyMode consistencyMode;
    private final Option<ConsensusPublishPath> consensusPath;
    private final StreamWriteRouter writeRouter;

    private DefaultStreamPublisher(Serializer serializer,
                                   String streamName,
                                   int partitionCount,
                                   Option<Function<T, Object>> partitionKeyExtractor,
                                   ConsistencyMode consistencyMode,
                                   Option<ConsensusPublishPath> consensusPath,
                                   StreamWriteRouter writeRouter) {
        this.serializer = serializer;
        this.streamName = streamName;
        this.partitionCount = partitionCount;
        this.partitionKeyExtractor = partitionKeyExtractor;
        this.roundRobinCounter = new AtomicLong(0);
        this.consistencyMode = consistencyMode;
        this.consensusPath = consensusPath;
        this.writeRouter = writeRouter;
    }

    public static <T> DefaultStreamPublisher<T> streamPublisher(StreamPartitionManager partitionManager,
                                                                Serializer serializer,
                                                                String streamName,
                                                                int partitionCount,
                                                                Option<Function<T, Object>> partitionKeyExtractor) {
        return streamPublisher(partitionManager,
                               serializer,
                               streamName,
                               partitionCount,
                               partitionKeyExtractor,
                               ConsistencyMode.EVENTUAL,
                               Option.none());
    }

    public static <T> DefaultStreamPublisher<T> streamPublisher(StreamPartitionManager partitionManager,
                                                                Serializer serializer,
                                                                String streamName,
                                                                int partitionCount,
                                                                Option<Function<T, Object>> partitionKeyExtractor,
                                                                ConsistencyMode consistencyMode,
                                                                Option<ConsensusPublishPath> consensusPath) {
        return streamPublisher(partitionManager,
                               serializer,
                               streamName,
                               partitionCount,
                               partitionKeyExtractor,
                               consistencyMode,
                               consensusPath,
                               Option.none(),
                               Option.none(),
                               Option.none(),
                               Option.none());
    }

    /// Full overload. The EVENTUAL write is delegated whole to {@link StreamWriteRouter} (#1263), built here
    /// from the forward client, the owner rule ({@link StreamWriteRouter#hrwOwner} over the partition-aware
    /// HRW resolver with the arg-less leader resolver as fallback) and the self identity. The min-sync
    /// barrier is no longer a constructor argument: the router reads the stream's committed
    /// `min-sync-replicas` live on every publish.
    public static <T> DefaultStreamPublisher<T> streamPublisher(StreamPartitionManager partitionManager,
                                                                Serializer serializer,
                                                                String streamName,
                                                                int partitionCount,
                                                                Option<Function<T, Object>> partitionKeyExtractor,
                                                                ConsistencyMode consistencyMode,
                                                                Option<ConsensusPublishPath> consensusPath,
                                                                Option<StreamForwardClient> forwardClient,
                                                                Option<Fn0<Option<NodeId>>> governorResolver,
                                                                Option<Function<Integer, Option<NodeId>>> partitionOwnerResolver,
                                                                Option<NodeId> selfNodeId) {
        var writeRouter = StreamWriteRouter.streamWriteRouter(partitionManager,
                                                              forwardClient,
                                                              selfNodeId,
                                                              (_, partition) -> StreamWriteRouter.hrwOwner(partitionOwnerResolver,
                                                                                                           governorResolver,
                                                                                                           partition));

        return new DefaultStreamPublisher<>(serializer,
                                            streamName,
                                            partitionCount,
                                            partitionKeyExtractor,
                                            consistencyMode,
                                            consensusPath,
                                            writeRouter);
    }

    @Override
    public Promise<Unit> publish(T event) {
        var bytes = serializer.encode(event);
        var partition = resolvePartition(event);
        var timestamp = System.currentTimeMillis();

        return switch (consistencyMode) {
            // #964 / #1262: UNKNOWN is NOT decided here. It takes the shared write path, whose single
            // consistency guard (StreamPartitionManager#ensureWritableConsistency, reading the stream's
            // committed config) refuses it with UNREADABLE_CONSISTENCY_MODE for every entry point alike.
            case EVENTUAL, UNKNOWN -> publishEventual(partition, bytes, timestamp).mapToUnit();
            case STRONG -> publishStrong(partition, bytes, timestamp);
        };
    }

    /// One [PublishOutcome] per event, in input order (#1342). The promise never fails on per-event grounds: a
    /// refused or timed-out event is [PublishOutcome.OutcomeUnknown] (#1236: it may already be in the log), an
    /// event that never reached the write path is [PublishOutcome.NotAttempted], and the offsets that DID land
    /// are reported, so a caller can retry without duplicating them (#1237).
    @Override
    public Promise<List<PublishOutcome>> publishBatch(List<T> events) {
        if (events.isEmpty()) {
            return Promise.success(List.of());
        }

        if (consistencyMode == ConsistencyMode.STRONG) {
            return publishBatchStrong(events);
        }
        // #964: an UNKNOWN mode takes the EVENTUAL batch path, where every event reaches the shared write
        // router and is refused there — the same single guard the single-event path relies on. Each refusal
        // is that event's outcome instead of being acknowledged as success.
        return publishBatchEventual(events);
    }

    /// #1262 B3 / #1342: with no consensus path every event is [PublishOutcome.NotAttempted] with the same typed
    /// cause a single publish gets, nothing written. With one, every event is proposed and its own result is
    /// its outcome — the earlier `Promise.allOf(...).mapToUnit()` had acknowledged a batch of refusals as success.
    private Promise<List<PublishOutcome>> publishBatchStrong(List<T> events) {
        return consensusPath.map(path -> proposeAll(path, events))
                            .or(() -> Promise.success(notAttempted(events.size(),
                                                                   StreamError.General.CONSENSUS_PATH_UNAVAILABLE)));
    }

    private Promise<List<PublishOutcome>> proposeAll(ConsensusPublishPath path, List<T> events) {
        var now = System.currentTimeMillis();

        return Promise.allOf(events.stream()
                                   .map(event -> path.publish(streamName,
                                                              resolvePartition(event),
                                                              serializer.encode(event),
                                                              now))
                                   .toList()).map(results -> results.stream()
                                                                    .map(PublishOutcome::attempted)
                                                                    .toList());
    }

    private static List<PublishOutcome> notAttempted(int count, Cause cause) {
        return Collections.nCopies(count, (PublishOutcome) new PublishOutcome.NotAttempted(cause));
    }

    /// #266: an EVENTUAL batch is grouped by each event's COMPUTED partition (not routed wholesale to
    /// the first event's partition) and each group is routed through the SAME per-event path as single
    /// {@link #publish} — local owner publish + replicate + min-sync await, or write-forward to the
    /// remote owner. This preserves key→partition affinity and gives the batch identical replication
    /// semantics to single publish (composes with #262), instead of the prior whole-batch misroute that
    /// also bypassed replication and failed `PARTITION_NOT_LOCAL` for any non-local partition. Groups run
    /// concurrently, so the batch is not atomic: one group's failure leaves the others' events in the log
    /// (#1342) — each event's outcome is placed back at its input index rather than folded into one result.
    private Promise<List<PublishOutcome>> publishBatchEventual(List<T> events) {
        var now = System.currentTimeMillis();
        var byPartition = groupByPartition(events);
        var groupRuns = byPartition.entrySet()
                                   .stream()
                                   .map(group -> publishGroupInOrder(group.getKey(),
                                                                     group.getValue(),
                                                                     events,
                                                                     now))
                                   .toList();

        return Promise.allOf(groupRuns).map(groupOutcomes -> placeByIndex(byPartition, groupOutcomes, events.size()));
    }

    /// Group event INDICES by computed partition, preserving encounter order within each partition group so
    /// per-key ordering is maintained. A `LinkedHashMap` keeps group iteration deterministic.
    private Map<Integer, List<Integer>> groupByPartition(List<T> events) {
        var groups = new LinkedHashMap<Integer, List<Integer>>();

        for (var index = 0; index < events.size(); index++) {
            groups.computeIfAbsent(resolvePartition(events.get(index)), _ -> new ArrayList<>()).add(index);
        }

        return groups;
    }

    /// Publish one partition's events strictly in order: each event awaits the previous so the partition's
    /// append/forward sequence preserves per-key ordering. Different partition groups run concurrently (the
    /// caller's `allOf`). The chain stops writing at the first failure — the failed event is
    /// [PublishOutcome.OutcomeUnknown] and every later event of the group is [PublishOutcome.NotAttempted]
    /// (#1342) — and always resolves with one outcome per event of the group.
    private Promise<List<PublishOutcome>> publishGroupInOrder(int partition,
                                                              List<Integer> indices,
                                                              List<T> events,
                                                              long timestamp) {
        var chain = Promise.success(List.<PublishOutcome> of());

        for (var index : indices) {
            var event = events.get(index);

            chain = chain.flatMap(outcomes -> publishNextInGroup(outcomes, partition, event, timestamp));
        }

        return chain;
    }

    private Promise<List<PublishOutcome>> publishNextInGroup(List<PublishOutcome> outcomes,
                                                             int partition,
                                                             T event,
                                                             long timestamp) {
        return precedingFailure(outcomes).map(cause -> Promise.success(appended(outcomes,
                                                                                new PublishOutcome.NotAttempted(precedingEventFailed(partition,
                                                                                                                                     cause)))))
                               .or(() -> publishEventual(partition,
                                                         serializer.encode(event),
                                                         timestamp).fold(result -> Promise.success(appended(outcomes,
                                                                                                            PublishOutcome.attempted(result)))));
    }

    /// The cause that stopped this group, if any: the group appends in order, so only the LAST outcome can be
    /// the failure — every outcome before it is [PublishOutcome.Published].
    private static Option<Cause> precedingFailure(List<PublishOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return Option.none();
        }

        return switch (outcomes.getLast()) {
            case PublishOutcome.Published _ -> Option.none();
            case PublishOutcome.OutcomeUnknown(var cause) -> Option.some(cause);
            case PublishOutcome.NotAttempted(var cause) -> Option.some(cause);
        };
    }

    private static Cause precedingEventFailed(int partition, Cause cause) {
        return cause instanceof StreamPublisherError.PrecedingEventFailed
               ? cause
               : StreamPublisherError.PrecedingEventFailed.precedingEventFailed(partition, cause);
    }

    private static List<PublishOutcome> appended(List<PublishOutcome> outcomes, PublishOutcome outcome) {
        var next = new ArrayList<>(outcomes);

        next.add(outcome);

        return List.copyOf(next);
    }

    /// Put each group's outcomes back at the input indices of its events. Every index belongs to exactly one
    /// group and a group run resolves with exactly one outcome per event, so every slot is filled; a group run
    /// that failed as a whole (it cannot, by construction) would leave its events outcome-unknown, never silent.
    private static List<PublishOutcome> placeByIndex(Map<Integer, List<Integer>> byPartition,
                                                     List<Result<List<PublishOutcome>>> groupOutcomes,
                                                     int size) {
        var slots = new PublishOutcome[size];
        var groupIndex = 0;

        for (var indices : byPartition.values()) {
            var outcomes = groupOutcomes.get(groupIndex++)
                                        .fold(cause -> notAttemptedAsUnknown(indices.size(),
                                                                             cause),
                                              resolved -> resolved);

            for (var i = 0; i < indices.size(); i++) {
                slots[indices.get(i)] = outcomes.get(i);
            }
        }

        return List.of(slots);
    }

    private static List<PublishOutcome> notAttemptedAsUnknown(int count, Cause cause) {
        return Collections.nCopies(count, (PublishOutcome) new PublishOutcome.OutcomeUnknown(cause));
    }

    /// EVENTUAL write: delegated whole to the ONE write operation, {@link StreamWriteRouter} (#1263) — owner
    /// routing by authority rather than ring presence (#1230), the committed-owner redirect, the bounded
    /// forward retry (#485) and the min-sync barrier read live. STRONG takes the explicit consensus
    /// alternative in {@link #publishStrong}, never this path.
    private Promise<Long> publishEventual(int partition, byte[] bytes, long timestamp) {
        return writeRouter.publish(streamName, partition, bytes, timestamp);
    }

    private Promise<Unit> publishStrong(int partition, byte[] bytes, long timestamp) {
        return consensusPath.async(StreamError.General.CONSENSUS_PATH_UNAVAILABLE)
                            .flatMap(path -> path.publish(streamName, partition, bytes, timestamp))
                            .mapToUnit();
    }

    /// Resolve the target partition for `event`. A configured key extractor routes through the STABLE
    /// 64-bit hash used for replica placement ({@link ReplicaPlacement#stableHash64}) rather than
    /// identity-unstable `Object#hashCode()`, so the same logical key maps to the same partition on
    /// every node/JVM (m2). Keyless publishes fall back to round-robin.
    private int resolvePartition(T event) {
        return partitionKeyExtractor.map(extractor -> stablePartition(extractor.apply(event)))
                                    .or(() -> (int)(roundRobinCounter.getAndIncrement() % partitionCount));
    }

    private int stablePartition(Object key) {
        return (int) Math.floorMod(ReplicaPlacement.stableHash64(String.valueOf(key)),
                                   (long) partitionCount);
    }
}
