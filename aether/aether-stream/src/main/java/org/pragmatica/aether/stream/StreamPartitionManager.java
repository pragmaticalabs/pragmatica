package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
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

    /// Create a new StreamPartitionManager with the default 128MB memory cap and no eviction listener.
    public static StreamPartitionManager streamPartitionManager() {
        return new StreamPartitionManager(DEFAULT_MAX_TOTAL_BYTES, EvictionListener.NOOP, ReplicationManager.NONE);
    }

    /// Create a new StreamPartitionManager with a custom memory cap and no eviction listener.
    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes) {
        return new StreamPartitionManager(maxTotalBytes, EvictionListener.NOOP, ReplicationManager.NONE);
    }

    /// Create a new StreamPartitionManager with a custom memory cap and eviction listener.
    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes, EvictionListener evictionListener) {
        return new StreamPartitionManager(maxTotalBytes, evictionListener, ReplicationManager.NONE);
    }

    /// Create a new StreamPartitionManager with a custom memory cap, eviction listener, and replication manager.
    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes,
                                                                EvictionListener evictionListener,
                                                                ReplicationManager replicationManager) {
        return new StreamPartitionManager(maxTotalBytes, evictionListener, replicationManager);
    }

    /// Total off-heap bytes currently allocated across all streams.
    public long totalAllocatedBytes() {
        return totalAllocatedBytes.get();
    }

    /// Create a stream with the given config. Creates ring buffers for locally-owned partitions.
    /// Returns failure if a stream with the same name already exists or if the memory cap would be exceeded.
    public Result<Unit> createStream(StreamConfig config) {
        if ( streams.containsKey(config.name())) {
        return StreamError.General.STREAM_ALREADY_EXISTS.result();}
        var requiredBytes = calculateStreamBytes(config);
        if ( totalAllocatedBytes.get() + requiredBytes > maxTotalBytes) {
        return StreamError.General.STREAM_MEMORY_EXCEEDED.result();}
        var entry = StreamEntry.fromConfig(config, evictionListener);
        var previous = streams.putIfAbsent(config.name(), entry);
        if ( previous != null) {
            entry.close();
            return StreamError.General.STREAM_ALREADY_EXISTS.result();
        }
        totalAllocatedBytes.addAndGet(requiredBytes);
        return success(unit());
    }

    /// Destroy a stream, closing all ring buffers and releasing tracked memory.
    /// Returns failure if the stream does not exist.
    public Result<Unit> destroyStream(String streamName) {
        return option(streams.remove(streamName)).toResult(new StreamError.StreamNotFound(streamName))
                     .flatMap(this::closeAndRelease);
    }

    /// Publish an event to the appropriate partition.
    /// If this node owns the target partition, writes directly to the ring buffer.
    /// Otherwise, returns an error (remote routing is a future layer).
    /// Rejects events exceeding the stream's configured max event size.
    public Result<Long> publishLocal(String streamName, int partition, byte[] payload, long timestamp) {
        return resolveStreamEntry(streamName).flatMap(entry -> checkEventSize(entry, payload))
                                 .flatMap(_ -> resolvePartitionBuffer(streamName, partition))
                                 .flatMap(buffer -> buffer.append(payload, timestamp))
                                 .onSuccess(offset -> replicationManager.replicateEvent(streamName,
                                                                                        partition,
                                                                                        offset,
                                                                                        payload,
                                                                                        timestamp));
    }

    /// Read events from a locally-owned partition.
    public Result<List<OffHeapRingBuffer.RawEvent>> readLocal(String streamName,
                                                              int partition,
                                                              long fromOffset,
                                                              int maxEvents) {
        return resolvePartitionBuffer(streamName, partition).flatMap(buffer -> buffer.read(fromOffset, maxEvents));
    }

    /// Get metadata about a stream.
    public Option<StreamInfo> streamInfo(String streamName) {
        return option(streams.get(streamName)).map(entry -> buildStreamInfo(streamName, entry));
    }

    /// List all streams managed by this node.
    public List<StreamInfo> listStreams() {
        return streams.entrySet().stream()
                               .map(e -> buildStreamInfo(e.getKey(),
                                                         e.getValue()))
                               .toList();
    }

    /// Reap idle streams — destroy streams where all partitions are empty and the stream
    /// has been idle for at least the configured maxAgeMs. Returns the number of streams reaped.
    public int reapIdleStreams() {
        var now = System.currentTimeMillis();
        var reaped = new ArrayList<String>();
        streams.forEach((name, entry) -> {
                            var maxAge = entry.config().retention()
                                                     .maxAgeMs();
                            var isEmpty = java.util.Arrays.stream(entry.partitions()).allMatch(b -> b.eventCount() == 0);
                            var isExpired = (now - entry.createdAt()) > maxAge;
                            if ( isEmpty && isExpired) {
        reaped.add(name);}
                        });
        reaped.forEach(this::destroyStream);
        return reaped.size();
    }

    @Contract
    @Override public void close() {
        streams.values().forEach(StreamEntry::close);
        streams.clear();
        totalAllocatedBytes.set(0);
    }

    // --- Private helpers ---
    private Result<StreamEntry> resolveStreamEntry(String streamName) {
        var entry = streams.get(streamName);
        if ( entry == null) {
        return new StreamError.StreamNotFound(streamName).result();}
        return success(entry);
    }

    private static Result<Unit> checkEventSize(StreamEntry entry, byte[] payload) {
        if ( payload.length > entry.config().maxEventSizeBytes()) {
        return new StreamError.EventTooLarge(payload.length,
                                             entry.config().maxEventSizeBytes()).result();}
        return success(unit());
    }

    private Result<OffHeapRingBuffer> resolvePartitionBuffer(String streamName, int partition) {
        var entry = streams.get(streamName);
        if ( entry == null) {
        return new StreamError.StreamNotFound(streamName).result();}
        if ( partition < 0 || partition >= entry.partitions().length) {
        return new StreamError.PartitionOutOfRange(streamName, partition, entry.partitions().length).result();}
        return success(entry.partitions() [partition]);
    }

    private static StreamInfo buildStreamInfo(String name, StreamEntry entry) {
        var totalEvents = 0L;
        var totalBytes = 0L;
        for ( var buffer : entry.partitions()) {
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

    /// Get per-partition details for a specific partition of a stream.
    public Result<PartitionInfo> partitionInfo(String streamName, int partition) {
        return resolvePartitionBuffer(streamName, partition)
        .map(buffer -> PartitionInfo.partitionInfo(partition,
                                                   buffer.headOffset(),
                                                   buffer.tailOffset(),
                                                   buffer.eventCount()));
    }

    /// Get all partition details for a stream.
    public Result<List<PartitionInfo>> allPartitionInfo(String streamName) {
        var entry = streams.get(streamName);
        if ( entry == null) {
        return new StreamError.StreamNotFound(streamName).result();}
        var infos = new ArrayList<PartitionInfo>();
        for ( int i = 0; i < entry.partitions().length; i++) {
            var buffer = entry.partitions() [i];
            infos.add(PartitionInfo.partitionInfo(i, buffer.headOffset(), buffer.tailOffset(), buffer.eventCount()));
        }
        return success(List.copyOf(infos));
    }

    /// Record for stream information.
    public record StreamInfo(String name, int partitions, long totalEvents, long totalBytes) {
        public static StreamInfo streamInfo(String name, int partitions, long totalEvents, long totalBytes) {
            return new StreamInfo(name, partitions, totalEvents, totalBytes);
        }
    }

    /// Record for per-partition details.
    public record PartitionInfo(int partition, long headOffset, long tailOffset, long eventCount) {
        public static PartitionInfo partitionInfo(int partition, long headOffset, long tailOffset, long eventCount) {
            return new PartitionInfo(partition, headOffset, tailOffset, eventCount);
        }
    }

    /// Internal entry holding config, partition buffers, and creation timestamp for a single stream.
    record StreamEntry(StreamConfig config, OffHeapRingBuffer[] partitions, long createdAt) implements AutoCloseable {
        static StreamEntry fromConfig(StreamConfig config, EvictionListener listener) {
            var retention = config.retention();
            var buffers = new OffHeapRingBuffer[config.partitions()];
            for ( int i = 0; i < config.partitions(); i++) {
            buffers[i] = OffHeapRingBuffer.offHeapRingBuffer(config.name(),
                                                             i,
                                                             retention.maxCount(),
                                                             retention.maxBytes(),
                                                             listener);}
            return new StreamEntry(config, buffers, System.currentTimeMillis());
        }

        @Contract
        @Override public void close() {
            for ( var buffer : partitions) {
            buffer.close();}
        }
    }
}
