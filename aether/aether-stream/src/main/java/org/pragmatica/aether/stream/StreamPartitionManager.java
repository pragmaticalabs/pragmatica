package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;

/// Manages ring buffers for locally-owned stream partitions.
///
/// This is the core runtime component for single-node streaming.
/// Each stream is backed by an array of `OffHeapRingBuffer` instances (one per partition).
/// Remote routing (cross-node produce/consume) is a future layer.
public final class StreamPartitionManager implements AutoCloseable {

    private final ConcurrentHashMap<String, StreamEntry> streams = new ConcurrentHashMap<>();

    /// Create a new StreamPartitionManager instance.
    public static StreamPartitionManager streamPartitionManager() {
        return new StreamPartitionManager();
    }

    /// Create a stream with the given config. Creates ring buffers for locally-owned partitions.
    /// Returns failure if a stream with the same name already exists.
    public Result<Unit> createStream(StreamConfig config) {
        var existing = streams.putIfAbsent(config.name(), StreamEntry.fromConfig(config));
        return existing == null
            ? success(unit())
            : StreamError.General.STREAM_ALREADY_EXISTS.result();
    }

    /// Destroy a stream, closing all ring buffers.
    /// Returns failure if the stream does not exist.
    public Result<Unit> destroyStream(String streamName) {
        var entry = streams.remove(streamName);
        return entry == null
            ? new StreamError.StreamNotFound(streamName).result()
            : closeEntry(entry);
    }

    /// Publish an event to the appropriate partition.
    /// If this node owns the target partition, writes directly to the ring buffer.
    /// Otherwise, returns an error (remote routing is a future layer).
    public Result<Long> publishLocal(String streamName, int partition, byte[] payload, long timestamp) {
        return resolvePartitionBuffer(streamName, partition)
                   .flatMap(buffer -> buffer.append(payload, timestamp));
    }

    /// Read events from a locally-owned partition.
    public Result<List<OffHeapRingBuffer.RawEvent>> readLocal(String streamName, int partition, long fromOffset, int maxEvents) {
        return resolvePartitionBuffer(streamName, partition)
                   .flatMap(buffer -> buffer.read(fromOffset, maxEvents));
    }

    /// Get metadata about a stream.
    public Option<StreamInfo> streamInfo(String streamName) {
        var entry = streams.get(streamName);
        return entry == null
            ? none()
            : some(buildStreamInfo(streamName, entry));
    }

    /// List all streams managed by this node.
    public List<StreamInfo> listStreams() {
        return streams.entrySet()
                      .stream()
                      .map(e -> buildStreamInfo(e.getKey(), e.getValue()))
                      .toList();
    }

    @Override
    public void close() {
        streams.values().forEach(StreamEntry::close);
        streams.clear();
    }

    // --- Private helpers ---

    private Result<OffHeapRingBuffer> resolvePartitionBuffer(String streamName, int partition) {
        var entry = streams.get(streamName);
        if (entry == null) {
            return new StreamError.StreamNotFound(streamName).result();
        }
        if (partition < 0 || partition >= entry.partitions().length) {
            return new StreamError.PartitionOutOfRange(streamName, partition, entry.partitions().length).result();
        }
        return success(entry.partitions()[partition]);
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

    private static Result<Unit> closeEntry(StreamEntry entry) {
        entry.close();
        return success(unit());
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
        if (entry == null) {
            return new StreamError.StreamNotFound(streamName).result();
        }
        var infos = new ArrayList<PartitionInfo>();
        for (int i = 0; i < entry.partitions().length; i++) {
            var buffer = entry.partitions()[i];
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

    /// Internal entry holding config and partition buffers for a single stream.
    record StreamEntry(StreamConfig config, OffHeapRingBuffer[] partitions) implements AutoCloseable {

        static StreamEntry fromConfig(StreamConfig config) {
            var retention = config.retention();
            var buffers = new OffHeapRingBuffer[config.partitions()];
            for (int i = 0; i < config.partitions(); i++) {
                buffers[i] = OffHeapRingBuffer.offHeapRingBuffer(retention.maxCount(), retention.maxBytes());
            }
            return new StreamEntry(config, buffers);
        }

        @Override
        public void close() {
            for (var buffer : partitions) {
                buffer.close();
            }
        }
    }
}
