package org.pragmatica.aether.slice;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;


/// Functional interface for publishing events to a stream partition.
///
/// Provisioned via `@ResourceQualifier(type = StreamPublisher.class, config = "streams.xxx")`
/// on a slice factory method parameter. The runtime creates a publisher that
/// routes events to the correct partition owner (governor).
///
/// Partition routing:
/// - If the event type has a `@PartitionKey` field, hash of that field determines the partition.
/// - Otherwise, round-robin across partitions.
///
/// Example:
/// ```{@code
/// @ResourceQualifier(type = StreamPublisher.class, config = "streams.order-events")
/// @Retention(RUNTIME) @Target(PARAMETER)
/// public @interface OrderStream {}
///
/// static OrderService orderService(@OrderStream StreamPublisher<OrderEvent> stream) { ... }
/// }```
public interface StreamPublisher<T> {
    Promise<Unit> publish(T event);

    default Promise<Unit> publishBatch(List<T> events) {
        return Promise.allOf(events.stream().map(this::publish)
                                          .toList()).mapToUnit();
    }
}
