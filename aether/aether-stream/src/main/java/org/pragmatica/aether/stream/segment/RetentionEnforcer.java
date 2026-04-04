package org.pragmatica.aether.stream.segment;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.StorageInstance;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Scheduled task that removes expired segments from storage and the segment index.
///
/// For each stream/partition tracked by the SegmentIndex, segments whose maxTimestamp
/// is older than the configured retention age are removed. Both the AHSE data blocks
/// and named references are deleted.
public final class RetentionEnforcer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RetentionEnforcer.class);

    private static final TimeSpan DEFAULT_INTERVAL = TimeSpan.timeSpan(5 * 60 * 1000L).millis();

    private final StorageInstance storage;
    private final SegmentIndex index;
    private final long retentionMs;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> scheduledFuture;

    private RetentionEnforcer(StorageInstance storage, SegmentIndex index, long retentionMs) {
        this.storage = storage;
        this.index = index;
        this.retentionMs = retentionMs;
    }

    public static RetentionEnforcer retentionEnforcer(StorageInstance storage, SegmentIndex index, long retentionMs) {
        return new RetentionEnforcer(storage, index, retentionMs);
    }

    @Contract public void start() {
        start(DEFAULT_INTERVAL);
    }

    @Contract public void start(TimeSpan interval) {
        if (closed.get()) {return;}
        scheduledFuture = SharedScheduler.scheduleAtFixedRate(this::enforce, interval);
        log.info("RetentionEnforcer started with interval={}ms, retentionMs={}", interval.millis(), retentionMs);
    }

    @Contract @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            var f = scheduledFuture;
            if (f != null) {f.cancel(false);}
            log.info("RetentionEnforcer stopped");
        }
    }

    /// Run a single enforcement pass. Package-private for direct testing.
    @Contract void enforce() {
        if (closed.get()) {return;}
        var now = System.currentTimeMillis();
        var cutoff = now - retentionMs;
        var partitionKeys = index.listPartitionKeys();
        var totalRemoved = partitionKeys.stream()
                                        .mapToInt(key -> enforcePartition(key.streamName(), key.partition(), cutoff))
                                        .sum();
        if (totalRemoved > 0) {log.info("Retention enforcement removed {} expired segment(s)", totalRemoved);}
    }

    private int enforcePartition(String streamName, int partition, long cutoff) {
        var expired = findExpiredSegments(streamName, partition, cutoff);
        expired.forEach(ref -> removeSegment(streamName, partition, ref));
        return expired.size();
    }

    private List<SegmentIndex.SegmentRef> findExpiredSegments(String streamName, int partition, long cutoff) {
        return index.listSegments(streamName, partition)
                    .stream()
                    .filter(ref -> ref.maxTimestamp() > 0 && ref.maxTimestamp() < cutoff)
                    .toList();
    }

    private void removeSegment(String streamName, int partition, SegmentIndex.SegmentRef ref) {
        var refName = SegmentIndex.buildRefName(streamName, partition, ref);
        storage.resolveRef(refName)
               .onPresent(blockId -> deleteBlockAndRef(refName, blockId));
        index.removeSegment(streamName, partition, ref.startOffset());
        log.debug("Removed expired segment {}/{}:[{}-{}] maxTimestamp={}",
                  streamName, partition, ref.startOffset(), ref.endOffset(), ref.maxTimestamp());
    }

    private void deleteBlockAndRef(String refName, BlockId blockId) {
        storage.deleteRef(refName).await();
        storage.delete(blockId).await();
    }
}
