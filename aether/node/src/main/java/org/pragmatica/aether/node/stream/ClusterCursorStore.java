// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamCursorCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Cluster-visible consumer cursor (#488): the node-local [org.pragmatica.aether.stream.segment.CursorStore]
/// composed with a consensus-KV checkpoint.
///
/// The local store's ref index is per-node and never replicated, so a cursor written by the partition's
/// owner is invisible to whichever node takes ownership next. Without this composite, every ownership
/// change would restart the consumer at offset 0 and replay the whole retained partition.
///
/// Resume offset is `max(local, cluster)`, never just the cluster value: a same-node restart has a
/// local cursor that may be AHEAD of the last consensus checkpoint, and taking the cluster value alone
/// would silently redeliver events this node already processed.
///
/// Cost: one consensus round per checkpoint, and checkpoints fire on the consumer runtime's existing
/// cadence — every 1000 delivered events or the group's checkpoint interval (the 1s `ConsumerConfig`
/// default for declarative consumers, 500ms for durable-topic groups per `DurableGroupIdentity`) per
/// (consumer group, partition), whichever comes first; a failed periodic checkpoint is retried with
/// backoff until it persists (#1239). A consensus failure must never fail the local checkpoint — degrading
/// the failover replay bound back to the local-only behavior is an acceptable outcome, silently losing
/// the failure is not (#654 round 2): the publish is chained onto `commit(...)`'s own Promise so the
/// 5-second shutdown bound covers it too, and a failed publish resolves that commit as
/// [CommitOutcome.LocalOnly] carrying the cause. #1239: the outcome travels on the commit's own promise;
/// the per-key side map it replaces let two overlapping commits for one key read each other's cause.
/// The runtime counts a `LocalOnly` exactly like a local-commit failure (prefixed `checkpoint publish:`)
/// and retries the periodic checkpoint until it reports [CommitOutcome.Persisted]. `local` is expected
/// to be a single-stage store (the node's disk store), so only its failure — not its outcome — matters.
public record ClusterCursorStore(ConsumerCursorStore local,
                                 Fn1<Option<Long>, StreamCursorCheckpointKey> committedReader,
                                 Fn1<Promise<Unit>, KVCommand<AetherKey>> commandWriter) implements ConsumerCursorStore {
    private static final Logger log = LoggerFactory.getLogger(ClusterCursorStore.class);

    public static ConsumerCursorStore clusterCursorStore(ConsumerCursorStore local,
                                                         Fn1<Option<Long>, StreamCursorCheckpointKey> committedReader,
                                                         Fn1<Promise<Unit>, KVCommand<AetherKey>> commandWriter) {
        return new ClusterCursorStore(local, committedReader, commandWriter);
    }

    @Override
    public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset) {
        return local.commit(consumerGroup, streamName, partition, offset)
                    .flatMap(_ -> publishCheckpoint(consumerGroup, streamName, partition, offset));
    }

    @Override
    public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
        return local.fetch(consumerGroup, streamName, partition)
                    .map(localOffset -> resumeOffset(localOffset,
                                                     committed(consumerGroup, streamName, partition)));
    }

    /// FER: a failed consensus publish degrades this commit to [CommitOutcome.LocalOnly] instead of
    /// failing it — the local write stands, and the caller learns from the outcome that the cluster
    /// checkpoint did not land.
    private Promise<CommitOutcome> publishCheckpoint(String consumerGroup,
                                                     String streamName,
                                                     int partition,
                                                     long offset) {
        return commandWriter.apply(checkpointCommand(consumerGroup, streamName, partition, offset))
                            .map(_ -> CommitOutcome.persisted())
                            .recover(cause -> localOnly(consumerGroup, streamName, partition, cause));
    }

    private static CommitOutcome localOnly(String consumerGroup, String streamName, int partition, Cause cause) {
        log.warn("Cluster cursor checkpoint {}/{}[{}] not committed, local commit stands: {}",
                 consumerGroup,
                 streamName,
                 partition,
                 cause.message());

        return CommitOutcome.localOnly(cause);
    }

    private static StreamCursorCheckpointKey checkpointKey(String consumerGroup, String streamName, int partition) {
        return StreamCursorCheckpointKey.streamCursorCheckpointKey(streamName, partition, consumerGroup);
    }

    private static KVCommand<AetherKey> checkpointCommand(String consumerGroup,
                                                          String streamName,
                                                          int partition,
                                                          long offset) {
        return new KVCommand.Put<>(checkpointKey(consumerGroup, streamName, partition),
                                   StreamCursorCheckpointValue.streamCursorCheckpointValue(offset));
    }

    private Option<Long> committed(String consumerGroup, String streamName, int partition) {
        return committedReader.apply(checkpointKey(consumerGroup, streamName, partition));
    }

    static Option<Long> resumeOffset(Option<Long> localOffset, Option<Long> clusterOffset) {
        return Option.all(localOffset, clusterOffset)
                     .map(ClusterCursorStore::larger)
                     .orElse(() -> localOffset.orElse(() -> clusterOffset));
    }

    private static Long larger(Long first, Long second) {
        return Math.max(first, second);
    }
}
