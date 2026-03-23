package org.pragmatica.aether.slice.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Marks a record component as the partition key for stream routing.
///
/// When a record with `@PartitionKey` is published to a stream, the runtime
/// hashes the annotated field's value to determine the target partition.
/// This ensures events with the same key always land in the same partition,
/// preserving per-key ordering.
///
/// At most one field per record may be annotated with `@PartitionKey`.
/// The field type must implement a stable `hashCode()` (records, String,
/// boxed primitives, UUID all qualify).
///
/// Example:
/// ```{@code
/// @Codec
/// public record OrderEvent(
///     @PartitionKey String orderId,
///     String customerId,
///     BigDecimal amount
/// ) {}
/// }```
///
/// If no `@PartitionKey` is present on the event type, the stream publisher
/// uses round-robin partition assignment.
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface PartitionKey {}
