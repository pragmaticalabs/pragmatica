// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.StorageInstance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;


public final class RetentionEnforcer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RetentionEnforcer.class);
    private static final TimeSpan DEFAULT_INTERVAL = TimeSpan.timeSpan(5 * 60 * 1000L).millis();

    /// How far a partition's sealed log may be reclaimed without destroying state something still needs
    /// to recover (#345 I3).
    ///
    /// ## Why this exists
    /// This enforcer is constructed ONCE per node with a single policy and applied to every partition in
    /// the segment index; it does not read per-stream [RetentionPolicy] at all. For ordinary event
    /// streams that is fine — a consumer that falls behind its retention window has missed events, which
    /// is the documented bargain.
    ///
    /// It is NOT fine for a durable entity, whose state IS its log. Under the plain age policy an
    /// entity's segments would be deleted a fixed interval after its last write, so a key written once
    /// and read for a year would silently cease to exist. That is data loss, not a missed event, and no
    /// later read could detect it.
    ///
    /// So a partition may declare how far deletion is safe. The contract is deliberately expressed as the
    /// highest offset that may be RECLAIMED rather than the lowest that must be kept, because the safe
    /// default is then the identity: [#NONE] returns [Long#MAX_VALUE], meaning the floor never blocks and
    /// the age policy alone decides, exactly as before this existed.
    @FunctionalInterface
    public interface SegmentRetentionFloor {
        /// The highest offset whose deletion cannot lose anything for `(streamName, partition)`.
        ///
        /// For an entity partition this is its committed checkpoint's `throughOffset`: everything at or
        /// below it is already folded into a durable snapshot, and everything above it is still the only
        /// copy of a mutation. An entity partition with NO checkpoint yet returns `-1` — nothing may be
        /// reclaimed, because nothing has been folded anywhere.
        long deletableThroughOffset(String streamName, int partition);
        /// The default for every stream that is not an entity log: no floor, so retention behaves exactly
        /// as it did before the floor existed.
        SegmentRetentionFloor NONE = (_, _) -> Long.MAX_VALUE;
    }

    private final StorageInstance storage;
    private final SegmentIndex index;
    private final RetentionPolicy retentionPolicy;
    private final SegmentRetentionFloor retentionFloor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> scheduledFuture;

    private RetentionEnforcer(StorageInstance storage,
                              SegmentIndex index,
                              RetentionPolicy retentionPolicy,
                              SegmentRetentionFloor retentionFloor) {
        this.storage = storage;
        this.index = index;
        this.retentionPolicy = retentionPolicy;
        this.retentionFloor = retentionFloor;
    }

    public static RetentionEnforcer retentionEnforcer(StorageInstance storage,
                                                      SegmentIndex index,
                                                      RetentionPolicy retentionPolicy) {
        return new RetentionEnforcer(storage, index, retentionPolicy, SegmentRetentionFloor.NONE);
    }

    public static RetentionEnforcer retentionEnforcer(StorageInstance storage,
                                                      SegmentIndex index,
                                                      RetentionPolicy retentionPolicy,
                                                      SegmentRetentionFloor retentionFloor) {
        return new RetentionEnforcer(storage, index, retentionPolicy, retentionFloor);
    }

    public static RetentionEnforcer retentionEnforcer(StorageInstance storage, SegmentIndex index, long retentionMs) {
        return new RetentionEnforcer(storage,
                                     index,
                                     RetentionPolicy.retentionPolicy(Long.MAX_VALUE, Long.MAX_VALUE, retentionMs),
                                     SegmentRetentionFloor.NONE);
    }

    public static RetentionEnforcer retentionEnforcer(StorageInstance storage,
                                                      SegmentIndex index,
                                                      long retentionMs,
                                                      SegmentRetentionFloor retentionFloor) {
        return new RetentionEnforcer(storage,
                                     index,
                                     RetentionPolicy.retentionPolicy(Long.MAX_VALUE, Long.MAX_VALUE, retentionMs),
                                     retentionFloor);
    }

    @Contract
    public void start() {
        start(DEFAULT_INTERVAL);
    }

    @Contract
    public void start(TimeSpan interval) {
        if (closed.get()) {
            return;
        }

        scheduledFuture = SharedScheduler.scheduleAtFixedRate(this::enforce, interval);
        log.info("RetentionEnforcer started with interval={}ms, policy={}", interval.millis(), retentionPolicy);
    }

    @Contract
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            option(scheduledFuture).onPresent(f -> f.cancel(false));
            log.info("RetentionEnforcer stopped");
        }
    }

    @Contract
    void enforce() {
        if (closed.get()) {
            return;
        }

        var now = System.currentTimeMillis();
        var partitionKeys = index.listPartitionKeys();
        var totalRemoved = partitionKeys.stream()
                                        .mapToInt(key -> enforcePartition(key.streamName(),
                                                                          key.partition(),
                                                                          now))
                                        .sum();

        if (totalRemoved > 0) {
            log.info("Retention enforcement removed {} expired segment(s)", totalRemoved);
        }
    }

    private int enforcePartition(String streamName, int partition, long now) {
        var expired = findExpiredSegments(streamName, partition, now);

        expired.forEach(ref -> removeSegment(streamName, partition, ref));

        return expired.size();
    }

    private List<SegmentIndex.SegmentRef> findExpiredSegments(String streamName, int partition, long now) {
        var segments = index.listSegments(streamName, partition);
        var segmentCount = segments.size();
        var totalBytes = segments.stream().mapToLong(SegmentIndex.SegmentRef::originalSize).sum();
        var deletableThrough = retentionFloor.deletableThroughOffset(streamName, partition);

        return segments.stream()
                       .filter(ref -> isReclaimable(ref, deletableThrough))
                       .filter(ref -> isSegmentExpired(ref, now, segmentCount, totalBytes))
                       .toList();
    }

    /// The floor is applied BEFORE the age/size policy and can only ever withhold a segment from
    /// deletion, never cause one — so a stream with no floor behaves exactly as it did before.
    ///
    /// A segment is reclaimable only when it lies ENTIRELY at or below the safe bound. A segment that
    /// straddles the bound is kept whole: segments are deleted as units, so reclaiming a straddling one
    /// to recover the part below the bound would take the part above it too, which is precisely the state
    /// nothing else holds.
    private static boolean isReclaimable(SegmentIndex.SegmentRef ref, long deletableThrough) {
        return ref.endOffset() <= deletableThrough;
    }

    /// An unknown timestamp disables the AGE term only — it must not disable the whole policy.
    ///
    /// A segment's `maxTimestamp` does not survive a restart: `SegmentIndex.rebuildFromRefs` reconstructs
    /// the index from ref NAMES, and a ref name carries only `streams/<stream>/<partition>/<start>-<end>`
    /// (`SegmentIndex.buildRefName`), so every rebuilt ref comes back with `maxTimestamp = 0`. This method
    /// used to `return false` on that, and because the check sat BEFORE the policy call it withheld the
    /// segment from the count- and size-based limits as well. The effect was that every segment sealed
    /// before a restart became permanently unreclaimable, and disk grew without bound across restarts.
    ///
    /// Passing an unknown age as `0` is the honest reading: nothing is claimed about the segment's age, so
    /// the age limit cannot fire on it, while `count`/`bytes` limits still apply. Under `ANY` the size and
    /// count terms are ORed and therefore work again; under `ALL` every limit must be exceeded, so an
    /// unknown age still withholds the segment — conservative in the direction that cannot delete data.
    ///
    /// This does NOT restore age-based retention for pre-restart segments; their age is genuinely not
    /// recorded anywhere. Fixing that means persisting `maxTimestamp` (a ref-name/metadata format change),
    /// which is a stored-format decision rather than a local fix.
    private boolean isSegmentExpired(SegmentIndex.SegmentRef ref, long now, long segmentCount, long totalBytes) {
        return retentionPolicy.shouldEvict(segmentCount, totalBytes, knownAgeMs(ref, now));
    }

    private static long knownAgeMs(SegmentIndex.SegmentRef ref, long now) {
        return ref.maxTimestamp() <= 0
               ? 0L
               : now - ref.maxTimestamp();
    }

    private void removeSegment(String streamName, int partition, SegmentIndex.SegmentRef ref) {
        var refName = SegmentIndex.buildRefName(streamName, partition, ref);

        storage.resolveRef(refName)
               .map(blockId -> deleteBlockAndRef(refName, blockId))
               .onPresent(promise -> promise.onFailure(cause -> logDeleteFailure(streamName, partition, ref, cause)));
        index.removeSegment(streamName, partition, ref.startOffset());
        log.debug("Removed expired segment {}/{}:[{}-{}] maxTimestamp={}",
                  streamName,
                  partition,
                  ref.startOffset(),
                  ref.endOffset(),
                  ref.maxTimestamp());
    }

    private Promise<Unit> deleteBlockAndRef(String refName, BlockId blockId) {
        return storage.deleteRef(refName)
                      .flatMap(_ -> storage.delete(blockId));
    }

    private static void logDeleteFailure(String streamName,
                                         int partition,
                                         SegmentIndex.SegmentRef ref,
                                         org.pragmatica.lang.Cause cause) {
        log.warn("Failed to delete segment block {}/{}:[{}-{}]: {}",
                 streamName,
                 partition,
                 ref.startOffset(),
                 ref.endOffset(),
                 cause.message());
    }
}
