// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamCursorCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.cluster.state.kvstore.KVCommand;
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
/// cadence — every 1000 delivered events or 30 seconds per (consumer group, partition), whichever comes
/// first. A consensus failure must never fail the local checkpoint — degrading the failover replay
/// bound back to the local-only behavior is an acceptable outcome, silently losing the failure is not
/// (#654 round 2): the publish is chained onto `commit(...)`'s own Promise so the 5-second shutdown
/// bound covers it too, and a failure is recovered into a successful `Unit` (never failing the local
/// checkpoint) but recorded in [#recoveredFailures] for [#lastRecoveredFailure] to surface — the
/// runtime polls that right after `commit(...)` resolves and folds it into the same
/// `cursorCommitFailureCount` / `lastCursorCommitFailure` surface a local-commit failure uses.
public record ClusterCursorStore(ConsumerCursorStore local,
                                 Fn1<Option<Long>, StreamCursorCheckpointKey> committedReader,
                                 Fn1<Promise<Unit>, KVCommand<AetherKey>> commandWriter,
                                 ConcurrentHashMap<StreamCursorCheckpointKey, String> recoveredFailures) implements ConsumerCursorStore {
    private static final Logger log = LoggerFactory.getLogger(ClusterCursorStore.class);

    public static ConsumerCursorStore clusterCursorStore(ConsumerCursorStore local,
                                                         Fn1<Option<Long>, StreamCursorCheckpointKey> committedReader,
                                                         Fn1<Promise<Unit>, KVCommand<AetherKey>> commandWriter) {
        return new ClusterCursorStore(local, committedReader, commandWriter, new ConcurrentHashMap<>());
    }

    @Override
    public Promise<Unit> commit(String consumerGroup, String streamName, int partition, long offset) {
        return local.commit(consumerGroup, streamName, partition, offset)
                    .flatMap(_ -> publishCheckpoint(consumerGroup, streamName, partition, offset));
    }

    @Override
    public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
        return local.fetch(consumerGroup, streamName, partition)
                    .map(localOffset -> resumeOffset(localOffset,
                                                     committed(consumerGroup, streamName, partition)));
    }

    /// #654 round 2: read right after `commit(...)` resolves — [#publishCheckpoint] clears the entry
    /// on a subsequent successful publish, so a stale detail from an earlier attempt never lingers
    /// past the next attempt for the same key.
    @Override
    public Option<String> lastRecoveredFailure(String consumerGroup, String streamName, int partition) {
        return Option.option(recoveredFailures.get(checkpointKey(consumerGroup, streamName, partition)));
    }

    private Promise<Unit> publishCheckpoint(String consumerGroup, String streamName, int partition, long offset) {
        var key = checkpointKey(consumerGroup, streamName, partition);

        return commandWriter.apply(checkpointCommand(consumerGroup, streamName, partition, offset))
                            .onSuccess(_ -> recoveredFailures.remove(key))
                            .recover(cause -> {
                                recoveredFailures.put(key, cause.message());
                                log.warn("Cluster cursor checkpoint {}/{}[{}] not committed, local commit stands: {}",
                                        consumerGroup,
                                        streamName,
                                        partition,
                                        cause.message());
                                return Unit.unit();
                            });
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
