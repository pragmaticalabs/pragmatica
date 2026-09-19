// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamCursorCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue.AssignmentToken;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
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
/// would silently redeliver events this node already processed. #1271: under an assignment the local half
/// counts only when it was written under THAT assignment's epoch — a local cursor from an earlier tenure
/// can be ahead of the committed cursor the successor left, and resuming from it would SKIP events.
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
public record ClusterCursorStore(ConsumerCursorStore local,
                                 NodeId self,
                                 Fn1<Option<StreamCursorCheckpointValue>, StreamCursorCheckpointKey> committedReader,
                                 Fn1<Promise<Unit>, List<KVCommand<AetherKey>>> commandWriter,
                                 BooleanSupplier forwarding) implements ConsumerCursorStore {
    private static final Logger log = LoggerFactory.getLogger(ClusterCursorStore.class);

    private static final String UNFENCED_REFUSED = "the cluster cursor admits only commits made under a committed consumer assignment";

    public static ConsumerCursorStore clusterCursorStore(ConsumerCursorStore local,
                                                         NodeId self,
                                                         Fn1<Option<StreamCursorCheckpointValue>, StreamCursorCheckpointKey> committedReader,
                                                         Fn1<Promise<Unit>, List<KVCommand<AetherKey>>> commandWriter,
                                                         BooleanSupplier forwarding) {
        return new ClusterCursorStore(local, self, committedReader, commandWriter, forwarding);
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
        var token = AssignmentToken.assignmentToken(self, assignmentEpoch);

        return local.commit(consumerGroup, streamName, partition, offset, assignmentEpoch)
                    .flatMap(_ -> publishCheckpoint(checkpointKey(consumerGroup, streamName, partition),
                                                    offset,
                                                    token));
    }

    @Override
    public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
        return local.fetch(consumerGroup, streamName, partition)
                    .map(localOffset -> resumeOffset(localOffset,
                                                     committedOffset(consumerGroup, streamName, partition)));
    }

    @Override
    public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition, Epoch assignmentEpoch) {
        return local.fetch(consumerGroup, streamName, partition, assignmentEpoch)
                    .map(localOffset -> resumeOffset(localOffset,
                                                     committedOffset(consumerGroup, streamName, partition)));
    }

    /// FER: a failed consensus publish degrades this commit to [CommitOutcome.LocalOnly] instead of
    /// failing it — the local write stands, and the caller learns from the outcome that the cluster
    /// checkpoint did not land. A publish that settled is then checked against committed state (#1271).
    private Promise<CommitOutcome> publishCheckpoint(StreamCursorCheckpointKey key,
                                                     long offset,
                                                     AssignmentToken token) {
        return commandWriter.apply(List.of(new KVCommand.Put<AetherKey, AetherValue>(key,
                                                                                     StreamCursorCheckpointValue.streamCursorCheckpointValue(offset,
                                                                                                                                             token))))
                            .flatMap(_ -> barrierIfForwarding(key))
                            .map(_ -> verdict(key, offset, token))
                            .recover(cause -> localOnly(key, cause));
    }

    private Promise<Unit> barrierIfForwarding(StreamCursorCheckpointKey key) {
        return forwarding.getAsBoolean()
               ? commandWriter.apply(List.of(new KVCommand.Noop<AetherKey>(key)))
               : Promise.unitPromise();
    }

    /// Our checkpoint stands only if the committed one IS ours: our token AND our offset. The applier
    /// refuses a stale token silently, so this read — not the publish's success — is what tells a deposed
    /// assignee apart. Comparing the token alone is not enough: a refused write leaves this node's OWN
    /// earlier checkpoint in place, which carries the same token.
    private CommitOutcome verdict(StreamCursorCheckpointKey key, long offset, AssignmentToken token) {
        return committedReader.apply(key)
                              .filter(committed -> committed.token()
                                                            .equals(token) && committed.committedOffset() == offset)
                              .map(_ -> CommitOutcome.persisted())
                              .or(() -> fenced(key, token));
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

    static Option<Long> resumeOffset(Option<Long> localOffset, Option<Long> clusterOffset) {
        return Option.all(localOffset, clusterOffset)
                     .map(ClusterCursorStore::larger)
                     .orElse(() -> localOffset.orElse(() -> clusterOffset));
    }

    private static Long larger(Long first, Long second) {
        return Math.max(first, second);
    }
}
