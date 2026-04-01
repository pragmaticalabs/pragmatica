package org.pragmatica.aether.slice;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

/// Advanced stream access for manual cursor management, replay, and seek.
///
/// Provisioned via `@ResourceQualifier(type = StreamAccess.class, config = "streams.xxx")`
/// on a slice factory method parameter.
///
/// For most use cases, prefer `StreamPublisher<T>` (produce) or `@StreamSubscription` (consume).
///
/// Example:
/// ```{@code
/// @ResourceQualifier(type = StreamAccess.class, config = "streams.order-events")
/// @Retention(RUNTIME) @Target(PARAMETER)
/// public @interface OrderStreamAccess {}
///
/// static AuditService auditService(@OrderStreamAccess StreamAccess<OrderEvent> stream) { ... }
/// }```
public interface StreamAccess<T> {
    /// Publish an event to the stream. Returns the assigned offset.
    Promise<Long> publish(T event);

    /// Fetch events starting from the given offset (inclusive).
    /// Returns up to `maxEvents` events.
    Promise<List<StreamEvent<T>>> fetch(long fromOffset, int maxEvents);

    /// Fetch events from a specific partition starting at the given offset.
    Promise<List<StreamEvent<T>>> fetch(int partition, long fromOffset, int maxEvents);

    /// Commit the consumer cursor for the given consumer group.
    /// The offset represents the last successfully processed event.
    Promise<Unit> commit(String consumerGroup, int partition, long offset);

    /// Get the current committed offset for a consumer group on a partition.
    Promise<Option<Long>> committedOffset(String consumerGroup, int partition);

    /// Get stream metadata: partition count, retention config, head/tail offsets.
    Promise<StreamMetadata> metadata();

    /// Event wrapper with offset and timestamp metadata.
    record StreamEvent<T>(long offset, long timestamp, int partition, T payload){}

    /// Stream metadata.
    record StreamMetadata(String streamName,
                          int partitionCount,
                          List<PartitionInfo> partitions){}

    /// Per-partition metadata.
    record PartitionInfo(int partition, long headOffset, long tailOffset, long eventCount){}
}
