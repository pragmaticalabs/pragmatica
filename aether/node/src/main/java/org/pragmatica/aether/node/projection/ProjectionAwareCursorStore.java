// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.projection;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamCursorCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The commit hook (#1333): decorates the node's cluster cursor store so that, once a commit has
/// RESOLVED, the group's projection — if this node hosts one — learns the committed cursor, stamped with
/// the epoch THE COMMITTING CONSUMER runs under (never the epoch current at report time — a zombie's
/// report must carry the zombie's epoch, which is how the store recognises and ignores it; #1304 X6).
///
/// The report is issued for a `Persisted` AND a `LocalOnly` outcome: the in-memory cursor has moved past
/// the acknowledged or dead-lettered event either way, which is the fact the skip signal states, and the
/// cluster checkpoint's retry is the runtime's business. It is never issued for a FAILED commit, nor for
/// a `Fenced` one (#1271: the assignment moved away, this node's projection is no longer the consumer).
///
/// **The cluster's committed cursor is reported too (#1400).** [#onCheckpointPut] reports every checkpoint the
/// applier ACCEPTED, whichever node committed it, stamped with the record's own rewind epoch. A node whose
/// assignment moved away mid-rebuild commits nothing more, so local commits alone left its store REBUILDING
/// forever; the new assignee resumes under the rewind's epoch, so its accepted checkpoints are honoured and the
/// old store leaves REBUILDING once the group's committed cursor passes the captured head. A refused put (a stale
/// token, an older epoch) emits no notification, so a zombie is never reported this way.
///
/// The hook never fails, delays or reorders the commit: the report hangs off the commit's own promise,
/// its failure is absorbed — logged once per `(group, partition)`, counted on [#reportFailures] — and the
/// commit's outcome is returned unchanged. A projection that throws does not break its group's cursor.
public record ProjectionAwareCursorStore(ConsumerCursorStore delegate,
                                         ProjectionRegistry registry,
                                         AtomicLong reportFailures,
                                         Set<String> reported) implements ConsumerCursorStore {
    private static final Logger log = LoggerFactory.getLogger(ProjectionAwareCursorStore.class);
    private static final String HOOK = "(report hook)";

    public static ProjectionAwareCursorStore projectionAwareCursorStore(ConsumerCursorStore delegate,
                                                                        ProjectionRegistry registry) {
        return new ProjectionAwareCursorStore(delegate, registry, new AtomicLong(), ConcurrentHashMap.newKeySet());
    }

    /// Unfenced commit (pull API): no managed group, so no projection to report to — passed through.
    @Override
    public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset) {
        return delegate.commit(consumerGroup, streamName, partition, offset);
    }

    @Override
    public Promise<CommitOutcome> commit(String consumerGroup,
                                         String streamName,
                                         int partition,
                                         long offset,
                                         Epoch assignmentEpoch) {
        return commit(consumerGroup, streamName, partition, offset, assignmentEpoch, RewindEpoch.NONE);
    }

    /// #1271: a `Fenced` outcome is not reported — the assignment moved away, so the projection on this
    /// node is no longer the group's live consumer and nothing cluster-visible moved.
    @Override
    public Promise<CommitOutcome> commit(String consumerGroup,
                                         String streamName,
                                         int partition,
                                         long offset,
                                         Epoch assignmentEpoch,
                                         RewindEpoch rewindEpoch) {
        return commit(consumerGroup, streamName, partition, offset, assignmentEpoch, rewindEpoch, Epoch.ZERO);
    }

    @Override
    public Promise<CommitOutcome> commit(String consumerGroup,
                                         String streamName,
                                         int partition,
                                         long offset,
                                         Epoch assignmentEpoch,
                                         RewindEpoch rewindEpoch,
                                         Epoch ownerEpoch) {
        return delegate.commit(consumerGroup, streamName, partition, offset, assignmentEpoch, rewindEpoch, ownerEpoch)
                       .withSuccess(outcome -> reportGuarded(outcome,
                                                             consumerGroup,
                                                             streamName,
                                                             partition,
                                                             offset,
                                                             rewindEpoch));
    }

    @Override
    public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
        return delegate.fetch(consumerGroup, streamName, partition);
    }

    @Override
    public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition, Epoch assignmentEpoch) {
        return delegate.fetch(consumerGroup, streamName, partition, assignmentEpoch);
    }

    @Override
    public Promise<Option<Cursor>> fetchCursor(String consumerGroup,
                                               String streamName,
                                               int partition,
                                               Epoch assignmentEpoch) {
        return delegate.fetchCursor(consumerGroup, streamName, partition, assignmentEpoch);
    }

    /// The hook is total: whatever the report raises (a registry supplied by user code, a null group) is counted and
    /// logged, never propagated, because a throwing `withSuccess` lambda would fail the commit it only observes.
    @Contract
    private void reportGuarded(CommitOutcome outcome,
                               String consumerGroup,
                               String streamName,
                               int partition,
                               long offset,
                               RewindEpoch epoch) {
        Result.lift(() -> reportUnlessFenced(outcome, consumerGroup, streamName, partition, offset, epoch)).onFailure(cause -> countFailure(HOOK,
                                                                                                                                            consumerGroup,
                                                                                                                                            partition,
                                                                                                                                            cause));
    }

    private void reportUnlessFenced(CommitOutcome outcome,
                                    String consumerGroup,
                                    String streamName,
                                    int partition,
                                    long offset,
                                    RewindEpoch epoch) {
        if (! (outcome instanceof CommitOutcome.Fenced)) {
            report(consumerGroup, streamName, partition, offset, epoch);
        }
    }

    /// #1400: routed every `ValuePut`; anything that is not a cursor checkpoint is ignored. The applier emits a
    /// `ValuePut` only for a put it accepted, so what is reported here is the group's COMMITTED cursor. The rewind
    /// record itself is reported too and moves nothing: it sits at the replay's `fromOffset`, which no partition's
    /// next offset is below. Total, like [#reportGuarded]: a throwing report would break the apply that observes it.
    @Contract
    public void onCheckpointPut(ValuePut<?, ?> put) {
        if (put.cause().key() instanceof StreamCursorCheckpointKey key && put.cause().value() instanceof StreamCursorCheckpointValue value) {
            reportCommitted(key, value);
        }
    }

    /// FER (degrade forward): a refused or throwing report is absorbed, counted and logged once, never propagated into
    /// the apply that observes it. The guarantee earned: the store keeps its state and the group's next accepted checkpoint
    /// reports a cursor at or past this one, so nothing is lost but promptness. Pinned by
    /// `onCheckpointPut_countsAThrowingProjection_andNeverPropagates`.
    @Contract
    private void reportCommitted(StreamCursorCheckpointKey key, StreamCursorCheckpointValue value) {
        Result.lift(() -> report(key.consumerGroup(),
                                 key.streamName(),
                                 key.partitionIndex(),
                                 value.committedOffset(),
                                 value.rewindEpoch())).onFailure(cause -> countFailure(HOOK,
                                                                                       key.consumerGroup(),
                                                                                       key.partitionIndex(),
                                                                                       cause));
    }

    @Contract
    private void report(String consumerGroup, String streamName, int partition, long offset, RewindEpoch epoch) {
        registry.lookup(streamName, consumerGroup)
                .onPresent(handle -> Result.lift(() -> handle.onCursorCommitted(NodeReplayCursor.tokenOf(epoch),
                                                                                partition,
                                                                                offset))
                                           .async()
                                           .flatMap(promise -> promise)
                                           .onFailure(cause -> reportFailed(handle, consumerGroup, partition, cause)));
    }

    /// The failure is counted every time and logged once per `(group, partition)`: a projection that
    /// fails every report would otherwise log on every 500ms checkpoint.
    private void reportFailed(ProjectionHandle handle, String consumerGroup, int partition, Cause cause) {
        countFailure(handle.projectionName(), consumerGroup, partition, cause);
    }

    private void countFailure(String projection, String consumerGroup, int partition, Cause cause) {
        reportFailures.incrementAndGet();
        if (reported.add(consumerGroup + "[" + partition + "]")) {
            log.warn("Projection {} refused the committed-cursor report for group {} partition {} (logged once; failures counted): {}",
                     projection,
                     consumerGroup,
                     partition,
                     cause.message());
        }
    }

    public long reportFailureCount() {
        return reportFailures.get();
    }
}
