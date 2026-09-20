// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import org.pragmatica.aether.slice.generation.RewindEpoch;
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
/// Resume position is the LATER of local and cluster by `(rewindEpoch, offset)` (#1333), never just the
/// cluster value: a same-node restart has a local cursor that may be AHEAD of the last consensus
/// checkpoint, and taking the cluster value alone would silently redeliver events this node already
/// processed. The epoch ranks first so that a projection rebuild's rewind — `(epoch', fromOffset)` put
/// into KV — outranks a stale-high local cursor from before it: a node that crashed before applying the
/// rewind resumes at the rewound position, not at its old high-water mark. Every checkpoint put carries
/// the consumer's epoch, and [StreamCursorCheckpointValue] is `EpochBearing`, so the KV applier refuses
/// a put stamped with a strictly older epoch — a zombie consumer cannot move a rewound cursor forward.
///
/// Cost: one consensus round per checkpoint, and checkpoints fire on the consumer runtime's existing
/// cadence — every 1000 delivered events or the group's checkpoint interval (the 1s `ConsumerConfig`
/// default for declarative consumers, 500ms for durable-topic groups per `DurableGroupIdentity`) per
/// (consumer group, partition), whichever comes first, plus one forced checkpoint per dead-lettered
/// event (#1333); a failed periodic checkpoint is retried with backoff until it persists (#1239). A
/// consensus failure must never fail the local checkpoint — degrading the failover replay bound back to
/// the local-only behavior is an acceptable outcome, silently losing the failure is not (#654 round 2):
/// the publish is chained onto `commit(...)`'s own Promise so the 5-second shutdown bound covers it too,
/// and a failed publish resolves that commit as [CommitOutcome.LocalOnly] carrying the cause. #1239: the
/// outcome travels on the commit's own promise; the per-key side map it replaces let two overlapping
/// commits for one key read each other's cause. The runtime counts a `LocalOnly` exactly like a
/// local-commit failure (prefixed `checkpoint publish:`) and retries the periodic checkpoint until it
/// reports [CommitOutcome.Persisted]. `local` is expected to be a single-stage store (the node's disk
/// store), so only its failure — not its outcome — matters.
public record ClusterCursorStore(ConsumerCursorStore local,
                                 Fn1<Option<StreamCursorCheckpointValue>, StreamCursorCheckpointKey> committedReader,
                                 Fn1<Promise<Unit>, KVCommand<AetherKey>> commandWriter) implements ConsumerCursorStore {
    private static final Logger log = LoggerFactory.getLogger(ClusterCursorStore.class);

    public static ConsumerCursorStore clusterCursorStore(ConsumerCursorStore local,
                                                         Fn1<Option<StreamCursorCheckpointValue>, StreamCursorCheckpointKey> committedReader,
                                                         Fn1<Promise<Unit>, KVCommand<AetherKey>> commandWriter) {
        return new ClusterCursorStore(local, committedReader, commandWriter);
    }

    @Override
    public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset) {
        return commit(consumerGroup, streamName, partition, offset, RewindEpoch.NONE);
    }

    @Override
    public Promise<CommitOutcome> commit(String consumerGroup,
                                         String streamName,
                                         int partition,
                                         long offset,
                                         RewindEpoch epoch) {
        return local.commit(consumerGroup, streamName, partition, offset, epoch)
                    .flatMap(_ -> publishCheckpoint(consumerGroup, streamName, partition, offset, epoch));
    }

    @Override
    public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
        return fetchCursor(consumerGroup, streamName, partition).map(cursor -> cursor.map(Cursor::offset));
    }

    @Override
    public Promise<Option<Cursor>> fetchCursor(String consumerGroup, String streamName, int partition) {
        return local.fetchCursor(consumerGroup, streamName, partition)
                    .map(localCursor -> resumeCursor(localCursor,
                                                     committed(consumerGroup, streamName, partition)));
    }

    /// FER: a failed consensus publish degrades this commit to [CommitOutcome.LocalOnly] instead of
    /// failing it — the local write stands, and the caller learns from the outcome that the cluster
    /// checkpoint did not land.
    private Promise<CommitOutcome> publishCheckpoint(String consumerGroup,
                                                     String streamName,
                                                     int partition,
                                                     long offset,
                                                     RewindEpoch epoch) {
        return commandWriter.apply(checkpointCommand(consumerGroup, streamName, partition, offset, epoch))
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

    public static KVCommand<AetherKey> checkpointCommand(String consumerGroup,
                                                  String streamName,
                                                  int partition,
                                                  long offset,
                                                  RewindEpoch epoch) {
        return new KVCommand.Put<>(checkpointKey(consumerGroup, streamName, partition),
                                   StreamCursorCheckpointValue.streamCursorCheckpointValue(offset, epoch));
    }

    private Option<Cursor> committed(String consumerGroup, String streamName, int partition) {
        return committedReader.apply(checkpointKey(consumerGroup, streamName, partition))
                              .map(ClusterCursorStore::toCursor);
    }

    static Cursor toCursor(StreamCursorCheckpointValue value) {
        return Cursor.cursor(value.committedOffset(), value.rewindEpoch());
    }

    static Option<Cursor> resumeCursor(Option<Cursor> localCursor, Option<Cursor> clusterCursor) {
        return Option.all(localCursor, clusterCursor)
                     .map(Cursor::later)
                     .orElse(() -> localCursor.orElse(() -> clusterCursor));
    }

    /// Offset-only form of [#resumeCursor], for stores and callers that know no epoch.
    static Option<Long> resumeOffset(Option<Long> localOffset, Option<Long> clusterOffset) {
        return resumeCursor(localOffset.map(Cursor::unrewound), clusterOffset.map(Cursor::unrewound)).map(Cursor::offset);
    }
}
