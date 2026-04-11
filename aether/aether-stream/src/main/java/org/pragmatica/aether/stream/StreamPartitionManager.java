package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


/// Manages ring buffers for locally-owned stream partitions.
///
/// This is the core runtime component for single-node streaming.
/// Each stream is backed by an array of `OffHeapRingBuffer` instances (one per partition).
/// Remote routing (cross-node produce/consume) is a future layer.
public final class StreamPartitionManager implements AutoCloseable {
    private static final long DEFAULT_MAX_TOTAL_BYTES = 128 * 1024 * 1024L;

    private final ConcurrentHashMap<String, StreamEntry> streams = new ConcurrentHashMap<>();

    private final AtomicLong totalAllocatedBytes = new AtomicLong(0);

    private final long maxTotalBytes;
    private final EvictionListener evictionListener;
    private final ReplicationManager replicationManager;

    private StreamPartitionManager(long maxTotalBytes,
                                   EvictionListener evictionListener,
                                   ReplicationManager replicationManager) {
        this.maxTotalBytes = maxTotalBytes;
        this.evictionListener = evictionListener;
        this.replicationManager = replicationManager;
    }

    public static StreamPartitionManager streamPartitionManager() {
        return new StreamPartitionManager(DEFAULT_MAX_TOTAL_BYTES, EvictionListener.NOOP, ReplicationManager.NONE);
    }

    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes) {
        return new StreamPartitionManager(maxTotalBytes, EvictionListener.NOOP, ReplicationManager.NONE);
    }

    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes, EvictionListener evictionListener) {
        return new StreamPartitionManager(maxTotalBytes, evictionListener, ReplicationManager.NONE);
    }

    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes,
                                                                EvictionListener evictionListener,
                                                                ReplicationManager replicationManager) {
        return new StreamPartitionManager(maxTotalBytes, evictionListener, replicationManager);
    }

    public long totalAllocatedBytes() {
        return totalAllocatedBytes.get();
    }

    public long maxTotalBytes() {
        return maxTotalBytes;
    }

    @SuppressWarnings("JBCT-NULL-01") // ConcurrentHashMap.putIfAbsent returns null on success per API contract
    public Result<Unit> createStream(StreamConfig config) {
        if (streams.containsKey(config.name())) {return StreamError.General.STREAM_ALREADY_EXISTS.result();}
        if (config.consistencyMode() == ConsistencyMode.STRONG && evictionListener == EvictionListener.NOOP) {return StreamError.General.AHSE_REQUIRED_FOR_STRONG.result();}
        var requiredBytes = calculateStreamBytes(config);
        if (totalAllocatedBytes.get() + requiredBytes > maxTotalBytes) {return StreamError.General.STREAM_MEMORY_EXCEEDED.result();}
        var entry = StreamEntry.fromConfig(config, evictionListener);
        var previous = streams.putIfAbsent(config.name(), entry);
        if (previous != null) {
            entry.close();
            return StreamError.General.STREAM_ALREADY_EXISTS.result();
        }
        totalAllocatedBytes.addAndGet(requiredBytes);
        return success(unit());
    }

    public Result<Unit> destroyStream(String streamName) {
        return option(streams.remove(streamName)).toResult(new StreamError.StreamNotFound(streamName))
                     .flatMap(this::closeAndRelease);
    }

    public Result<Long> publishLocal(String streamName, int partition, byte[] payload, long timestamp) {
        return resolveStreamEntry(streamName).flatMap(entry -> appendToPartition(entry,
                                                                                 streamName,
                                                                                 partition,
                                                                                 payload,
                                                                                 timestamp))
                                 .onSuccess(offset -> replicationManager.replicateEvent(streamName,
                                                                                        partition,
                                                                                        offset,
                                                                                        payload,
                                                                                        timestamp));
    }

    public Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks) {
        return replicationManager.awaitReplication(streamName, partition, offset, minAcks);
    }

    private Result<Long> appendToPartition(StreamEntry entry,
                                           String streamName,
                                           int partition,
                                           byte[] payload,
                                           long timestamp) {
        return checkEventSize(entry, payload).flatMap(_ -> resolvePartitionBuffer(streamName, partition))
                             .flatMap(buffer -> buffer.append(payload, timestamp))
                             .onSuccess(_ -> entry.updateActivity());
    }

    public Option<OffHeapRingBuffer> partitionBuffer(String streamName, int partition) {
        return resolvePartitionBuffer(streamName, partition).option();
    }

    public Result<List<OffHeapRingBuffer.RawEvent>> readLocal(String streamName,
                                                              int partition,
                                                              long fromOffset,
                                                              int maxEvents) {
        return resolvePartitionBuffer(streamName, partition).flatMap(buffer -> buffer.read(fromOffset, maxEvents));
    }

    public Option<StreamInfo> streamInfo(String streamName) {
        return option(streams.get(streamName)).map(entry -> buildStreamInfo(streamName, entry));
    }

    public List<StreamInfo> listStreams() {
        return streams.entrySet().stream()
                               .map(e -> buildStreamInfo(e.getKey(),
                                                         e.getValue()))
                               .toList();
    }

    public int reapIdleStreams() {
        var now = System.currentTimeMillis();
        var reaped = new AtomicInteger(0);
        streams.forEach((name, entry) -> reapIfIdle(name, entry, now, reaped));
        return reaped.get();
    }

    private void reapIfIdle(String name, StreamEntry entry, long now, AtomicInteger reaped) {
        var maxAge = entry.config().retention()
                                 .maxAgeMs();
        var isEmpty = java.util.Arrays.stream(entry.partitions()).allMatch(b -> b.eventCount() == 0);
        var isExpired = (now - entry.createdAt()) > maxAge;
        var isIdle = (now - entry.lastActivity()) > maxAge;
        if (isEmpty && isExpired && isIdle) {
            var capturedActivity = entry.lastActivity();
            streams.computeIfPresent(name, (_, current) -> removeIfStillIdle(current, capturedActivity, reaped));
        }
    }

    @SuppressWarnings("JBCT-RET-03") private StreamEntry removeIfStillIdle(StreamEntry current,
                                                                           long capturedActivity,
                                                                           AtomicInteger reaped) {
        if (current.lastActivity() == capturedActivity) {
            closeAndRelease(current);
            reaped.incrementAndGet();
            return null;
        }
        return current;
    }

    @Contract@Override public void close() {
        streams.values().forEach(StreamEntry::close);
        streams.clear();
        totalAllocatedBytes.set(0);
    }

    private Result<StreamEntry> resolveStreamEntry(String streamName) {
        return option(streams.get(streamName)).toResult(new StreamError.StreamNotFound(streamName));
    }

    private static Result<Unit> checkEventSize(StreamEntry entry, byte[] payload) {
        if (payload.length > entry.config().maxEventSizeBytes()) {return new StreamError.EventTooLarge(payload.length,
                                                                                                       entry.config()
                                                                                                                   .maxEventSizeBytes()).result();}
        return success(unit());
    }

    private Result<OffHeapRingBuffer> resolvePartitionBuffer(String streamName, int partition) {
        return option(streams.get(streamName)).toResult(new StreamError.StreamNotFound(streamName))
                     .flatMap(entry -> resolvePartitionInEntry(streamName, partition, entry));
    }

    private static Result<OffHeapRingBuffer> resolvePartitionInEntry(String streamName,
                                                                      int partition,
                                                                      StreamEntry entry) {
        if (partition <0 || partition >= entry.partitions().length) {return new StreamError.PartitionOutOfRange(streamName,
                                                                                                                partition,
                                                                                                                entry.partitions().length).result();}
        return success(entry.partitions() [partition]);
    }

    private static StreamInfo buildStreamInfo(String name, StreamEntry entry) {
        var totalEvents = 0L;
        var totalBytes = 0L;
        for (var buffer : entry.partitions()) {
            totalEvents += buffer.eventCount();
            totalBytes += buffer.allocatedBytes();
        }
        return StreamInfo.streamInfo(name, entry.partitions().length, totalEvents, totalBytes);
    }

    private Result<Unit> closeAndRelease(StreamEntry entry) {
        totalAllocatedBytes.addAndGet(- calculateStreamBytes(entry.config()));
        entry.close();
        return success(unit());
    }

    private static long calculateStreamBytes(StreamConfig config) {
        var retention = config.retention();
        var perPartition = 64 + (24 * retention.maxCount()) + retention.maxBytes();
        return perPartition * config.partitions();
    }

    public Result<PartitionInfo> partitionInfo(String streamName, int partition) {
        return resolvePartitionBuffer(streamName, partition).map(buffer -> PartitionInfo.partitionInfo(partition,
                                                                                                       buffer.headOffset(),
                                                                                                       buffer.tailOffset(),
                                                                                                       buffer.eventCount()));
    }

    public Result<List<PartitionInfo>> allPartitionInfo(String streamName) {
        return option(streams.get(streamName)).toResult(new StreamError.StreamNotFound(streamName))
                     .map(StreamPartitionManager::buildAllPartitionInfo);
    }

    private static List<PartitionInfo> buildAllPartitionInfo(StreamEntry entry) {
        var infos = new ArrayList<PartitionInfo>();
        for (int i = 0;i <entry.partitions().length;i++) {
            var buffer = entry.partitions() [i];
            infos.add(PartitionInfo.partitionInfo(i, buffer.headOffset(), buffer.tailOffset(), buffer.eventCount()));
        }
        return List.copyOf(infos);
    }

    public record StreamInfo(String name, int partitions, long totalEvents, long totalBytes) {
        public static StreamInfo streamInfo(String name, int partitions, long totalEvents, long totalBytes) {
            return new StreamInfo(name, partitions, totalEvents, totalBytes);
        }
    }

    public record PartitionInfo(int partition, long headOffset, long tailOffset, long eventCount) {
        public static PartitionInfo partitionInfo(int partition, long headOffset, long tailOffset, long eventCount) {
            return new PartitionInfo(partition, headOffset, tailOffset, eventCount);
        }
    }

    record StreamEntry(StreamConfig config,
                       OffHeapRingBuffer[] partitions,
                       long createdAt,
                       AtomicLong lastActivityRef) implements AutoCloseable {
        static StreamEntry fromConfig(StreamConfig config, EvictionListener listener) {
            var retention = config.retention();
            var policy = deriveEvictionPolicy(config);
            var buffers = new OffHeapRingBuffer[config.partitions()];
            for (int i = 0;i <config.partitions();i++) {buffers[i] = OffHeapRingBuffer.offHeapRingBuffer(config.name(),
                                                                                                         i,
                                                                                                         retention.maxCount(),
                                                                                                         retention.maxBytes(),
                                                                                                         listener,
                                                                                                         policy);}
            var now = System.currentTimeMillis();
            return new StreamEntry(config, buffers, now, new AtomicLong(now));
        }

        long lastActivity() {
            return lastActivityRef.get();
        }

        void updateActivity() {
            lastActivityRef.set(System.currentTimeMillis());
        }

        private static EvictionPolicy deriveEvictionPolicy(StreamConfig config) {
            return config.consistencyMode() == ConsistencyMode.STRONG
                  ? EvictionPolicy.REJECT_WHEN_FULL
                  : EvictionPolicy.DROP_OLDEST;
        }

        @Contract@Override public void close() {
            for (var buffer : partitions) {buffer.close();}
        }
    }
}
