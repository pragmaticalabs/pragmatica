// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.StorageInstance;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;


/// Scheduled task that removes expired segments from storage and the segment index.
///
/// For each stream/partition tracked by the SegmentIndex, segments whose maxTimestamp
/// is older than the configured retention age are removed. Both the AHSE data blocks
/// and named references are deleted.
///
/// Supports compound retention policies: in ANY mode, a segment is expired when any limit
/// is exceeded; in ALL mode, a segment is expired only when all configured limits are exceeded.
public final class RetentionEnforcer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RetentionEnforcer.class);

    private static final TimeSpan DEFAULT_INTERVAL = TimeSpan.timeSpan(5 * 60 * 1000L).millis();

    private final StorageInstance storage;
    private final SegmentIndex index;
    private final RetentionPolicy retentionPolicy;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile ScheduledFuture<?> scheduledFuture;

    private RetentionEnforcer(StorageInstance storage, SegmentIndex index, RetentionPolicy retentionPolicy) {
        this.storage = storage;
        this.index = index;
        this.retentionPolicy = retentionPolicy;
    }

    public static RetentionEnforcer retentionEnforcer(StorageInstance storage,
                                                      SegmentIndex index,
                                                      RetentionPolicy retentionPolicy) {
        return new RetentionEnforcer(storage, index, retentionPolicy);
    }

    public static RetentionEnforcer retentionEnforcer(StorageInstance storage, SegmentIndex index, long retentionMs) {
        return new RetentionEnforcer(storage,
                                     index,
                                     RetentionPolicy.retentionPolicy(Long.MAX_VALUE, Long.MAX_VALUE, retentionMs));
    }

    @Contract public void start() {
        start(DEFAULT_INTERVAL);
    }

    @Contract public void start(TimeSpan interval) {
        if (closed.get()) {return;}
        scheduledFuture = SharedScheduler.scheduleAtFixedRate(this::enforce, interval);
        log.info("RetentionEnforcer started with interval={}ms, policy={}", interval.millis(), retentionPolicy);
    }

    @Contract@Override public void close() {
        if (closed.compareAndSet(false, true)) {
            option(scheduledFuture).onPresent(f -> f.cancel(false));
            log.info("RetentionEnforcer stopped");
        }
    }

    @Contract void enforce() {
        if (closed.get()) {return;}
        var now = System.currentTimeMillis();
        var partitionKeys = index.listPartitionKeys();
        var totalRemoved = partitionKeys.stream().mapToInt(key -> enforcePartition(key.streamName(),
                                                                                   key.partition(),
                                                                                   now))
                                               .sum();
        if (totalRemoved > 0) {log.info("Retention enforcement removed {} expired segment(s)", totalRemoved);}
    }

    private int enforcePartition(String streamName, int partition, long now) {
        var expired = findExpiredSegments(streamName, partition, now);
        expired.forEach(ref -> removeSegment(streamName, partition, ref));
        return expired.size();
    }

    private List<SegmentIndex.SegmentRef> findExpiredSegments(String streamName, int partition, long now) {
        var segments = index.listSegments(streamName, partition);
        var segmentCount = segments.size();
        var totalBytes = segments.stream().mapToLong(SegmentIndex.SegmentRef::originalSize)
                                        .sum();
        return segments.stream().filter(ref -> isSegmentExpired(ref, now, segmentCount, totalBytes))
                              .toList();
    }

    private boolean isSegmentExpired(SegmentIndex.SegmentRef ref, long now, long segmentCount, long totalBytes) {
        if (ref.maxTimestamp() <= 0) {return false;}
        var ageMs = now - ref.maxTimestamp();
        return retentionPolicy.shouldEvict(segmentCount, totalBytes, ageMs);
    }

    private void removeSegment(String streamName, int partition, SegmentIndex.SegmentRef ref) {
        var refName = SegmentIndex.buildRefName(streamName, partition, ref);
        storage.resolveRef(refName).map(blockId -> deleteBlockAndRef(refName, blockId))
                          .onPresent(promise -> promise.onFailure(cause -> logDeleteFailure(streamName,
                                                                                            partition,
                                                                                            ref,
                                                                                            cause)));
        index.removeSegment(streamName, partition, ref.startOffset());
        log.debug("Removed expired segment {}/{}:[{}-{}] maxTimestamp={}",
                  streamName,
                  partition,
                  ref.startOffset(),
                  ref.endOffset(),
                  ref.maxTimestamp());
    }

    private Promise<Unit> deleteBlockAndRef(String refName, BlockId blockId) {
        return storage.deleteRef(refName).flatMap(_ -> storage.delete(blockId));
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
