// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.node.stream.ConsumerAssignmentWriter.CommittedAssignments;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamCursorCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue.AssignmentToken;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore.Cursor;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

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
/// would silently redeliver events this node already processed. #1271: under an assignment the local half
/// counts only when it was written under THAT assignment's epoch — a local cursor from an earlier tenure
/// can be ahead of the committed cursor the successor left, and resuming from it would SKIP events.
/// #1333: the fenced resume ranks the two halves by `(rewind epoch, offset)` ([Cursor#later]), so a
/// rewind's `(epoch', fromOffset)` outranks a stale local `(epoch, high)` however high.
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
///
/// ## Assignment fence (#1271)
/// The checkpoint key is `AssignmentGuarded`: the consensus applier admits a checkpoint only while the
/// token it carries — this node at the consumer's admitted assignment epoch — is the committed assignment's.
/// A refused `Put` mutates nothing and reports nothing, so after the publish settles the store RE-READS the
/// committed checkpoint: carrying our token means it stands, anything else means the assignment moved and
/// the commit resolves [CommitOutcome.Fenced] (terminal). On a core node the re-read is exact —
/// `RabiaEngine` resolves the publish only after its own state machine has processed the decision. A
/// forwarding (worker) node's publish resolves on the core's reply, so it first sends a `Noop` barrier
/// through consensus before re-reading. [unverified: that the barrier also brings a forwarding node's
/// LOCAL mirror up to the decision — the fence itself does not depend on it; only how fast the loser
/// learns does, and the manager's own reconcile detaches it regardless.]
///
/// Both re-reads are of THIS node's committed-state mirror, which on a worker can trail the core that
/// admitted the write (#1335 B1). A mirror one decision behind shows this node's OWN previous checkpoint —
/// same token, older offset — and reading that as `Fenced` would latch delivery off on a node the record
/// still names. So a checkpoint that is not ours is `Fenced` only when the ASSIGNMENT record has moved
/// too; while it still names this node at the consumer's epoch, the verdict is [CommitOutcome.LocalOnly]:
/// retryable, and the retry re-reads the cursor. The applier stays the one authority — a retry cannot
/// bypass it — only the loser's self-diagnosis changes. The same lag delays a GENUINE fence by one retry
/// (the mirror shows the reassignment a moment after the core refused the write), never skips it.
///
/// ## Rewind fence (#1333)
/// The checkpoint value is also [org.pragmatica.cluster.state.kvstore.EpochBearing] on its rewind epoch,
/// so the applier refuses a checkpoint stamped with a strictly older epoch than the committed one. Both
/// fences are pure predicates the applier ORs — a checkpoint lands only when the assignment guard AND the
/// rewind fence admit it, whichever is evaluated first. The read-back verdict therefore compares the rewind
/// epoch as well: a zombie consumer (pre-restart, at the old epoch) whose put the rewind arm refused reads
/// back a same-token record at another offset and epoch; the assignment still names this node, so it is
/// reported [CommitOutcome.LocalOnly] naming the rewind — retryable, and bounded by the manager's
/// `restartRewound`, which the checkpoint put itself triggers.
public record ClusterCursorStore(ConsumerCursorStore local,
                                 NodeId self,
                                 Fn1<Option<StreamCursorCheckpointValue>, StreamCursorCheckpointKey> committedReader,
                                 CommittedAssignments committedAssignments,
                                 Fn1<Promise<Unit>, List<KVCommand<AetherKey>>> commandWriter,
                                 BooleanSupplier forwarding) implements ConsumerCursorStore {
    private static final Logger log = LoggerFactory.getLogger(ClusterCursorStore.class);

    private static final String UNFENCED_REFUSED = "the cluster cursor admits only commits made under a committed consumer assignment";

    public static ConsumerCursorStore clusterCursorStore(ConsumerCursorStore local,
                                                         NodeId self,
                                                         Fn1<Option<StreamCursorCheckpointValue>, StreamCursorCheckpointKey> committedReader,
                                                         CommittedAssignments committedAssignments,
                                                         Fn1<Promise<Unit>, List<KVCommand<AetherKey>>> commandWriter,
                                                         BooleanSupplier forwarding) {
        return new ClusterCursorStore(local, self, committedReader, committedAssignments, commandWriter, forwarding);
    }

    /// #1271: a commit with no assignment cannot pass the applier's guard, so it is not published at all.
    /// The local write still lands; the outcome says nothing cluster-visible moved, and — being terminal —
    /// that no retry will change it.
    @Override
    public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset) {
        return local.commit(consumerGroup, streamName, partition, offset)
                    .map(_ -> CommitOutcome.fenced(UNFENCED_REFUSED));
    }

    @Override
    public Promise<CommitOutcome> commit(String consumerGroup,
                                         String streamName,
                                         int partition,
                                         long offset,
                                         Epoch assignmentEpoch) {
        return commit(consumerGroup, streamName, partition, offset, assignmentEpoch, RewindEpoch.NONE);
    }

    @Override
    public Promise<CommitOutcome> commit(String consumerGroup,
                                         String streamName,
                                         int partition,
                                         long offset,
                                         Epoch assignmentEpoch,
                                         RewindEpoch rewindEpoch) {
        var token = AssignmentToken.assignmentToken(self, assignmentEpoch);

        return local.commit(consumerGroup, streamName, partition, offset, assignmentEpoch, rewindEpoch)
                    .flatMap(_ -> publishCheckpoint(checkpointKey(consumerGroup, streamName, partition),
                                                    offset,
                                                    token,
                                                    rewindEpoch));
    }

    @Override
    public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
        return local.fetch(consumerGroup, streamName, partition)
                    .map(localOffset -> resumeOffset(localOffset,
                                                     committedOffset(consumerGroup, streamName, partition)));
    }

    @Override
    public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition, Epoch assignmentEpoch) {
        return fetchCursor(consumerGroup, streamName, partition, assignmentEpoch).map(cursor -> cursor.map(Cursor::offset));
    }

    @Override
    public Promise<Option<Cursor>> fetchCursor(String consumerGroup,
                                               String streamName,
                                               int partition,
                                               Epoch assignmentEpoch) {
        return local.fetchCursor(consumerGroup, streamName, partition, assignmentEpoch)
                    .map(localCursor -> resumeCursor(localCursor,
                                                     committedCursor(consumerGroup, streamName, partition)));
    }

    /// FER: a failed consensus publish degrades this commit to [CommitOutcome.LocalOnly] instead of
    /// failing it — the local write stands, and the caller learns from the outcome that the cluster
    /// checkpoint did not land. A publish that settled is then checked against committed state (#1271).
    private Promise<CommitOutcome> publishCheckpoint(StreamCursorCheckpointKey key,
                                                     long offset,
                                                     AssignmentToken token,
                                                     RewindEpoch rewindEpoch) {
        return commandWriter.apply(List.of(checkpointCommand(key, offset, token, rewindEpoch)))
                            .flatMap(_ -> barrierIfForwarding(key))
                            .map(_ -> verdict(key, offset, token, rewindEpoch))
                            .recover(cause -> localOnly(key, cause));
    }

    public static KVCommand<AetherKey> checkpointCommand(StreamCursorCheckpointKey key,
                                                         long offset,
                                                         AssignmentToken token,
                                                         RewindEpoch rewindEpoch) {
        return new KVCommand.Put<AetherKey, AetherValue>(key,
                                                         StreamCursorCheckpointValue.streamCursorCheckpointValue(offset,
                                                                                                                 token,
                                                                                                                 rewindEpoch));
    }

    public static KVCommand<AetherKey> checkpointCommand(String consumerGroup,
                                                         String streamName,
                                                         int partition,
                                                         long offset,
                                                         AssignmentToken token,
                                                         RewindEpoch rewindEpoch) {
        return checkpointCommand(checkpointKey(consumerGroup, streamName, partition), offset, token, rewindEpoch);
    }

    private Promise<Unit> barrierIfForwarding(StreamCursorCheckpointKey key) {
        return forwarding.getAsBoolean()
               ? commandWriter.apply(List.of(new KVCommand.Noop<AetherKey>(key)))
               : Promise.unitPromise();
    }

    /// Our checkpoint stands only if the committed one IS ours: our token AND our offset AND our rewind
    /// epoch. The applier refuses a stale token or a stale rewind epoch silently, so this read — not the
    /// publish's success — is what tells a deposed assignee or a rewound-past consumer apart. Comparing the
    /// token alone is not enough: a refused write leaves this node's OWN earlier checkpoint in place, which
    /// carries the same token.
    private CommitOutcome verdict(StreamCursorCheckpointKey key,
                                  long offset,
                                  AssignmentToken token,
                                  RewindEpoch rewindEpoch) {
        return committedReader.apply(key)
                              .filter(committed -> committed.token()
                                                            .equals(token)
                                                   && committed.committedOffset() == offset
                                                   && committed.rewindEpoch()
                                                               .equals(rewindEpoch))
                              .map(_ -> CommitOutcome.persisted())
                              .or(() -> notOurs(key, token, rewindEpoch));
    }

    /// The committed checkpoint is not ours. Fenced only if the assignment record agrees — it no longer
    /// names this node at the consumer's epoch. If it still does, either the group was rewound past this
    /// consumer (#1333: the committed rewind epoch is strictly newer than the one it commits under — the
    /// manager restarts it under the new epoch; the outcome names the rewind so the log does not read as a
    /// lagging mirror), or the applier admitted the write and this node's mirror has not caught up with it
    /// (#1335 B1). Both are retryable, not terminal.
    private CommitOutcome notOurs(StreamCursorCheckpointKey key, AssignmentToken token, RewindEpoch rewindEpoch) {
        return committedAssignments.assignmentOf(key.streamName(),
                                                 key.partitionIndex(),
                                                 key.consumerGroup())
                                   .filter(assignment -> assignment.token()
                                                                   .equals(token))
                                   .map(_ -> stillOurs(key, token, rewindEpoch))
                                   .or(() -> fenced(key, token));
    }

    private CommitOutcome stillOurs(StreamCursorCheckpointKey key, AssignmentToken token, RewindEpoch rewindEpoch) {
        return committedReader.apply(key)
                              .map(StreamCursorCheckpointValue::rewindEpoch)
                              .filter(committed -> committed.isStrictlyAfter(rewindEpoch))
                              .map(committed -> rewoundPast(key, rewindEpoch, committed))
                              .or(() -> mirrorLagging(key, token));
    }

    private static CommitOutcome rewoundPast(StreamCursorCheckpointKey key, RewindEpoch ours, RewindEpoch committed) {
        return localOnly(key,
                         Causes.cause("checkpoint " + key
                                     + " at rewind epoch " + ours
                                     + " refused: the group was rewound to epoch " + committed
                                     + "; the consumer restarts under it"));
    }

    private static CommitOutcome mirrorLagging(StreamCursorCheckpointKey key, AssignmentToken token) {
        return localOnly(key,
                         Causes.cause("checkpoint " + key
                                     + " not yet visible in this node's committed state; " + token.assignee()
                                     + " at " + token.epoch()
                                     + " is still the committed consumer assignment"));
    }

    private static CommitOutcome fenced(StreamCursorCheckpointKey key, AssignmentToken token) {
        log.warn("Cluster cursor checkpoint {} refused: {} at {} is no longer the committed consumer assignment",
                 key,
                 token.assignee(),
                 token.epoch());

        return CommitOutcome.fenced("checkpoint " + key + " refused for " + token.assignee() + " at " + token.epoch());
    }

    private static CommitOutcome localOnly(StreamCursorCheckpointKey key, Cause cause) {
        log.warn("Cluster cursor checkpoint {} not committed, local commit stands: {}", key, cause.message());

        return CommitOutcome.localOnly(cause);
    }

    private static StreamCursorCheckpointKey checkpointKey(String consumerGroup, String streamName, int partition) {
        return StreamCursorCheckpointKey.streamCursorCheckpointKey(streamName, partition, consumerGroup);
    }

    private Option<Long> committedOffset(String consumerGroup, String streamName, int partition) {
        return committedReader.apply(checkpointKey(consumerGroup, streamName, partition))
                              .map(StreamCursorCheckpointValue::committedOffset);
    }

    private Option<Cursor> committedCursor(String consumerGroup, String streamName, int partition) {
        return committedReader.apply(checkpointKey(consumerGroup, streamName, partition))
                              .map(ClusterCursorStore::toCursor);
    }

    static Cursor toCursor(StreamCursorCheckpointValue value) {
        return Cursor.cursor(value.committedOffset(), value.rewindEpoch());
    }

    /// #1333: the fenced resume — later of the two by `(rewind epoch, offset)`.
    static Option<Cursor> resumeCursor(Option<Cursor> localCursor, Option<Cursor> clusterCursor) {
        return Option.all(localCursor, clusterCursor)
                     .map(Cursor::later)
                     .orElse(() -> localCursor.orElse(() -> clusterCursor));
    }

    /// The unfenced resume (pull API): offsets only, the larger wins.
    static Option<Long> resumeOffset(Option<Long> localOffset, Option<Long> clusterOffset) {
        return Option.all(localOffset, clusterOffset)
                     .map(ClusterCursorStore::larger)
                     .orElse(() -> localOffset.orElse(() -> clusterOffset));
    }

    private static Long larger(Long first, Long second) {
        return Math.max(first, second);
    }
}
