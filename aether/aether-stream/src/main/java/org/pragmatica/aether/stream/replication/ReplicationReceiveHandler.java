// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;
import java.util.function.BiConsumer;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Result;
import org.pragmatica.messaging.MessageReceiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;


/// Replica-side receive/apply for the A6 replication path.
///
/// An owner of `(stream, partition)` replicates each published event to its registered replica set
/// via {@link DefaultReplicationManager#replicateEvent} → {@link ReplicationTransport#send} carrying a
/// {@link ReplicationMessage.ReplicateEvents}. THIS handler runs on each replica: it lands the
/// replicated payloads into the local partition ring and acks the highest applied offset back to the
/// owner.
///
/// ## Offset verification against the local head (S1 / #260)
/// A replicated batch carries the OWNER-frame `fromOffset` it was published at. Before applying, the
/// handler compares it to the replica's own next-expected offset ({@link LocalHead#nextExpectedOffset}
/// = local ring head + 1, or 0 for an empty partition) so the owner-frame and replica-frame offsets
/// stay in lock-step — the apply is NEVER a blind "append at whatever the local head happens to be":
///   - `fromOffset == localNext` — contiguous: apply the batch (the normal path).
///   - `fromOffset  > localNext` — a GAP: one or more earlier batches were dropped/reordered, so
///     applying this batch now would land owner-offset-N events at the wrong local offsets and
///     permanently diverge. The batch is REJECTED (nothing applied) and `onGap` fires so the replica
///     re-enters SYNCING/backfill and pulls the missing prefix from a caught-up source.
///   - `fromOffset  < localNext` — a stale/duplicate re-delivery: the overlapping prefix is already
///     present locally, so it is SKIPPED (idempotent) and only the tail at-or-beyond `localNext` is
///     applied. A batch fully below `localNext` is acked at its nominal end (the owner already has
///     these) with no append and no gap.
///
/// Offsets are preserved because each surviving batch is applied only when it is exactly contiguous
/// with what the replica already holds, and the ring assigns sequential offsets. The apply itself goes
/// through {@link RecoveredAppender#appendRecovered} (the A4 seam, backed by
/// `StreamPartitionManager::appendRecovered`) which appends WITHOUT re-invoking the replication
/// manager — this is what stops an infinite replicate→apply→replicate loop.
///
/// ## Ack
/// After applying the verified portion of a batch up to highest offset `H`, `H` is acked back so
/// {@link DefaultReplicationManager#handleAck} can advance the watermark and resolve any pending
/// `awaitReplication(minAcks)` promise. The ack always reflects the replica's VERIFIED state — never a
/// nominal batch end the replica did not actually reach contiguously.
///
/// ## Partial-apply repair (M5)
/// A mid-batch append failure applies only the contiguous prefix `[applyFrom, applyFrom+applied-1]`
/// and leaves a gap for the tail of the batch. The handler acks ONLY that contiguous prefix (never the
/// batch's nominal end), so the owner's watermark view never over-states what landed. A partial apply
/// is NOT treated as success: the `onGap` repair seam is fired for `(streamName, partition)` so the
/// replica re-enters SYNCING/backfill and pulls the missing tail from a caught-up source. The default
/// seam is a no-op; production wires it to the partition-backfill executor (same seam the
/// {@link ReplicaSetController} uses for newly-added replicas).
public final class ReplicationReceiveHandler {
    private static final Logger log = LoggerFactory.getLogger(ReplicationReceiveHandler.class);

    /// Non-replicating, offset-preserving append seam carrying the SENDING owner's `ownerEpoch`
    /// fencing token (#345 item 1d-ii). In production this is `StreamPartitionManager::appendRecovered`,
    /// which fences the append against the replica's own partition high-water before landing it — a
    /// deposed owner's batch is rejected at the replica's commit point.
    @FunctionalInterface
    public interface RecoveredAppender {
        Result<Long> appendRecovered(String streamName,
                                     int partition,
                                     byte[] payload,
                                     long timestamp,
                                     Epoch ownerEpoch);
    }

    /// Local next-expected-offset seam: the offset the NEXT contiguous append would be assigned for
    /// `(streamName, partition)` — local ring head + 1, or 0 for an empty/absent partition. In
    /// production this is `StreamPartitionManager::nextExpectedOffset`. Used to verify an incoming
    /// batch's owner-frame `fromOffset` before applying (S1 / #260).
    @FunctionalInterface
    public interface LocalHead {
        long nextExpectedOffset(String streamName, int partition);
    }

    /// #634 item 1: the durability barrier awaited BETWEEN applying a batch and acking it, so an ack
    /// means "fsynced here", not "in my RAM". Production wires `StreamPartitionManager::syncReplicated`;
    /// the default resolves immediately, which is byte-identical to the pre-barrier behaviour — unwired
    /// deployments (Forge, legacy tests, the explicit non-durable opt-in) keep their exact semantics.
    @FunctionalInterface
    public interface ReplicaDurability {
        Promise<Unit> sync(String streamName, int partition);
    }

    public static final ReplicaDurability NO_DURABILITY_BARRIER = (_, _) -> Promise.unitPromise();

    private final NodeId self;
    private final RecoveredAppender appender;
    private final LocalHead localHead;
    private final ReplicationTransport transport;
    private final BiConsumer<String, Integer> onGap;
    private final ReplicaDurability durability;

    private ReplicationReceiveHandler(NodeId self,
                                      RecoveredAppender appender,
                                      LocalHead localHead,
                                      ReplicationTransport transport,
                                      BiConsumer<String, Integer> onGap,
                                      ReplicaDurability durability) {
        this.self = self;
        this.appender = appender;
        this.localHead = localHead;
        this.transport = transport;
        this.onGap = onGap;
        this.durability = durability;
    }

    /// Backward-compatible factory with no local-head verification: the incoming `fromOffset` is
    /// trusted to be contiguous (a fresh/empty replica fed strictly in order). Retained for callers
    /// that cannot supply a local-head view; new wiring should prefer the verifying factory.
    public static ReplicationReceiveHandler replicationReceiveHandler(NodeId self,
                                                                      RecoveredAppender appender,
                                                                      ReplicationTransport transport) {
        return new ReplicationReceiveHandler(self,
                                             appender,
                                             NO_LOCAL_HEAD,
                                             transport,
                                             (_, _) -> {},
                                             NO_DURABILITY_BARRIER);
    }

    /// Factory with an explicit `onGap` repair seam, fired `(streamName, partition)` whenever a batch
    /// cannot apply in full so the replica re-enters SYNCING/backfill (M5). No local-head verification.
    public static ReplicationReceiveHandler replicationReceiveHandler(NodeId self,
                                                                      RecoveredAppender appender,
                                                                      ReplicationTransport transport,
                                                                      BiConsumer<String, Integer> onGap) {
        return new ReplicationReceiveHandler(self, appender, NO_LOCAL_HEAD, transport, onGap, NO_DURABILITY_BARRIER);
    }

    /// Verifying factory (S1 / #260): `localHead` reports the replica's next-expected offset so an
    /// incoming batch's owner-frame `fromOffset` is checked for gaps/duplicates before applying.
    public static ReplicationReceiveHandler replicationReceiveHandler(NodeId self,
                                                                      RecoveredAppender appender,
                                                                      LocalHead localHead,
                                                                      ReplicationTransport transport,
                                                                      BiConsumer<String, Integer> onGap) {
        return new ReplicationReceiveHandler(self, appender, localHead, transport, onGap, NO_DURABILITY_BARRIER);
    }

    /// Verifying factory WITH the replica durability barrier (#634 item 1) — the production wiring.
    public static ReplicationReceiveHandler replicationReceiveHandler(NodeId self,
                                                                      RecoveredAppender appender,
                                                                      LocalHead localHead,
                                                                      ReplicationTransport transport,
                                                                      BiConsumer<String, Integer> onGap,
                                                                      ReplicaDurability durability) {
        return new ReplicationReceiveHandler(self, appender, localHead, transport, onGap, durability);
    }

    @Contract
    @MessageReceiver
    public void onReplicateEvents(ReplicationMessage.ReplicateEvents message) {
        var streamName = message.streamName();
        var partition = message.partition();
        var payloads = message.payloads();
        var timestamps = message.timestamps();
        var fromOffset = message.fromOffset();
        var batchEnd = fromOffset + payloads.size() - 1;
        var localNext = resolveLocalNext(streamName, partition, fromOffset);

        if (fromOffset > localNext) {
            handleGapAhead(message, fromOffset, localNext);

            return;
        }

        if (batchEnd < localNext) {
            handleStaleDuplicate(message, fromOffset, batchEnd, localNext);

            return;
        }

        applyContiguous(message, streamName, partition, fromOffset, payloads, timestamps, localNext);
    }

    /// `fromOffset > localNext`: an earlier batch is missing. Applying now would diverge — reject the
    /// whole batch and trigger backfill repair. No ack: the replica's verified offset is unchanged.
    private void handleGapAhead(ReplicationMessage.ReplicateEvents message, long fromOffset, long localNext) {
        log.warn("ReplicationReceiveHandler: gap for {}[{}] — batch starts at offset {} but next expected is {} "
                + "(missing {} earlier offsets) — rejecting batch, triggering backfill repair",
                 message.streamName(),
                 message.partition(),
                 fromOffset,
                 localNext,
                 fromOffset - localNext);
        onGap.accept(message.streamName(), message.partition());
    }

    /// Whole batch is below `localNext`: the replica already holds these offsets (a duplicate/stale
    /// re-delivery). Nothing to apply; re-ack the batch end so the owner's watermark view is not stuck
    /// below what the replica already has, and surface no gap.
    private void handleStaleDuplicate(ReplicationMessage.ReplicateEvents message,
                                      long fromOffset,
                                      long batchEnd,
                                      long localNext) {
        log.debug("ReplicationReceiveHandler: duplicate batch for {}[{}] — [{}, {}] already applied (next expected {}) "
                 + "— re-acking, no append",
                  message.streamName(),
                  message.partition(),
                  fromOffset,
                  batchEnd,
                  localNext);
        transport.send(message.governorId(),
                       replicateAck(self, message.streamName(), message.partition(), batchEnd));
    }

    /// `fromOffset <= localNext <= batchEnd`: the batch is contiguous with (or overlaps the tail of)
    /// what the replica holds. Skip the already-present prefix `[fromOffset, localNext-1]` and apply
    /// from `localNext` onward, preserving offsets.
    private void applyContiguous(ReplicationMessage.ReplicateEvents message,
                                 String streamName,
                                 int partition,
                                 long fromOffset,
                                 List<byte[]> payloads,
                                 List<Long> timestamps,
                                 long localNext) {
        var skip = (int)(localNext - fromOffset);
        var applied = applyBatch(streamName, partition, localNext, payloads, timestamps, skip, message.ownerEpoch());
        var expected = payloads.size() - skip;

        if (applied < expected) {
            log.warn("ReplicationReceiveHandler: applied {}/{} new events for {}[{}] from offset {} (owner {}) "
                    + "— triggering backfill repair",
                     applied,
                     expected,
                     streamName,
                     partition,
                     localNext,
                     message.governorId());
            onGap.accept(streamName, partition);
        }

        if (applied <= 0) {
            return;
        }

        var highestApplied = localNext + applied - 1;

        // Ack ONLY after the batch is fsynced here (#634 item 1): the owner's min-sync barrier counts
        // this ack as a durable copy, so acking from RAM would let correlated power loss inside the
        // unsealed window erase writes the caller was told reached RF. A failed sync WITHHOLDS the ack —
        // the record is applied and serveable, but this replica must not be counted toward durability;
        // the owner's barrier degrades honestly instead of over-counting.
        durability.sync(streamName, partition)
                  .onSuccess(_ -> transport.send(message.governorId(),
                                                 replicateAck(self, streamName, partition, highestApplied)))
                  .onFailure(cause -> log.warn("ReplicationReceiveHandler: durability sync failed for {}[{}] "
                                              + "up to {} — WITHHOLDING ack (applied but not fsynced): {}",
                                               streamName,
                                               partition,
                                               highestApplied,
                                               cause.message()));
    }

    private int applyBatch(String streamName,
                           int partition,
                           long applyFrom,
                           List<byte[]> payloads,
                           List<Long> timestamps,
                           int skip,
                           Epoch ownerEpoch) {
        var applied = 0;

        for (var i = skip; i < payloads.size(); i++) {
            var result = appender.appendRecovered(streamName, partition, payloads.get(i), timestamps.get(i), ownerEpoch);

            if (result.isFailure()) {
                result.onFailure(cause -> log.warn("ReplicationReceiveHandler: append failed for {}[{}] at offset {}: {}",
                                                   streamName,
                                                   partition,
                                                   applyFrom,
                                                   cause.message()));
                break;
            }

            applied++;
        }

        return applied;
    }

    /// The replica's next-expected offset for `(streamName, partition)`, or `fromOffset` itself when no
    /// real local-head view is wired ({@link #NO_LOCAL_HEAD}) so the batch applies verbatim — the
    /// pre-#260 trust-the-owner behavior of the non-verifying factories.
    private long resolveLocalNext(String streamName, int partition, long fromOffset) {
        return localHead == NO_LOCAL_HEAD
               ? fromOffset
               : localHead.nextExpectedOffset(streamName, partition);
    }

    /// Sentinel seam for the non-verifying factories: {@link #resolveLocalNext} short-circuits it to
    /// `fromOffset` so every batch is treated as contiguous (no gap detection, no skip). Never invoked.
    private static final LocalHead NO_LOCAL_HEAD = (_, _) -> 0L;
}
