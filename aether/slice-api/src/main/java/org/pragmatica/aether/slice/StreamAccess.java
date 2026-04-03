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
    Promise<Long> publish(T event);
    Promise<List<StreamEvent<T>>> fetch(long fromOffset, int maxEvents);
    Promise<List<StreamEvent<T>>> fetch(int partition, long fromOffset, int maxEvents);
    Promise<Unit> commit(String consumerGroup, int partition, long offset);
    Promise<Option<Long>> committedOffset(String consumerGroup, int partition);
    Promise<StreamMetadata> metadata();

    record StreamEvent<T>(long offset, long timestamp, int partition, T payload){}

    record StreamMetadata(String streamName, int partitionCount, List<PartitionInfo> partitions){}

    record PartitionInfo(int partition, long headOffset, long tailOffset, long eventCount){}
}
