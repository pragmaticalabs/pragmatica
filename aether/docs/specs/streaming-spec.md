# Aether Streaming: Implementation Specification

## Version: 1.0
## Status: Implementation-Ready
## Target Release: Post Passive Worker Pools Phase 1
## Last Updated: 2026-03-20

---

## Table of Contents

1. [Overview](#1-overview)
2. [Slice API](#2-slice-api)
3. [Blueprint Declaration](#3-blueprint-declaration)
4. [Annotation Processor Changes](#4-annotation-processor-changes)
5. [KV-Store Types](#5-kv-store-types)
6. [Ring Buffer Implementation](#6-ring-buffer-implementation)
7. [Partition Ownership](#7-partition-ownership)
8. [Produce Path](#8-produce-path)
9. [Consume Path](#9-consume-path)
10. [Cursor Management](#10-cursor-management)
11. [CDM Integration](#11-cdm-integration)
12. [CDC Adapter](#12-cdc-adapter)
13. [Module Structure](#13-module-structure)
14. [Implementation Phases](#14-implementation-phases)
15. [Testing Strategy](#15-testing-strategy)

---

## 1. Overview

### 1.1 Purpose

This specification defines the concrete implementation plan for Aether Streaming. It translates the design decisions in the [exploratory spec](in-memory-streams-spec.md) into API signatures, data structures, module layouts, and processor changes that a developer can implement directly.

### 1.2 Relationship to Existing Pub/Sub

Aether already has a pub/sub resource (`Publisher<T>` / `Subscriber`) for fire-and-forget fan-out messaging. Streaming adds ordered, replayable, consumer-paced semantics on top of the same `@ResourceQualifier` pattern. The two resources are complementary and share no runtime infrastructure beyond the annotation processor's model detection.

| Dimension | Pub/Sub | Streaming |
|-----------|---------|-----------|
| Resource qualifier type | `Publisher.class` / `Subscriber.class` | `StreamPublisher.class` / `StreamSubscriber.class` / `StreamAccess.class` |
| Config section prefix | `messaging.*` | `streams.*` |
| Ordering | None | Per-partition strict |
| Replay | No | Yes, within retention window |
| Consumer groups | No | Yes |
| Delivery | Push (fire-and-forget) | Push (annotated consumer) or pull (`StreamAccess`) |

### 1.3 Phase Scope

**Phase 1 (this spec):** In-memory ring buffer, standard consistency (governor-local sequencing), consumer groups, partition assignment via consensus, annotation processor integration, CDC adapter.

**Phase 2+:** Replication (governor-push), persistent backend (PostgreSQL), strong consistency (Rabia path), log compaction, transactional cursor commits. These are explicitly out of scope for Phase 1 but the Phase 1 design must not preclude them (see Section 17 of the exploratory spec for constraints).

---

## 2. Slice API

### 2.1 StreamPublisher<T>

Functional interface for producing events to a stream. Injected as a parameter-level resource via `@ResourceQualifier`.

```java
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

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
@FunctionalInterface
public interface StreamPublisher<T> {
    Promise<Unit> publish(T event);
}
```

**Design notes:**
- REQ-SP-01: Returns `Promise<Unit>`, not `Promise<Long>` (offset). Offset is an internal detail; producers that need it use `StreamAccess<T>`.
- REQ-SP-02: The generic type `T` is extracted by the annotation processor from the parameter declaration, exactly as `Publisher<T>` works today.
- REQ-SP-03: No `publish(key, event)` overload. The partition key is extracted from the event via `@PartitionKey` annotation. If no `@PartitionKey` field exists, round-robin is used.

### 2.2 StreamSubscriber

Marker type for stream subscription annotations. Method-level annotation, analogous to `Subscriber`.

```java
package org.pragmatica.aether.slice;

/// Marker type for stream subscription annotations.
///
/// Used with `@ResourceQualifier` on method annotations to declare stream consumers.
/// The annotated method receives stream events. Two delivery modes:
///
/// - **Single event:** method takes `T` — called once per event.
/// - **Batch:** method takes `List<T>` — called with a batch of events.
///
/// Batch size is configurable per consumer group in TOML (`batch-size`).
///
/// The method must return `Promise<Unit>`.
///
/// Example (single event):
/// ```{@code
/// @ResourceQualifier(type = StreamSubscriber.class, config = "streams.order-events")
/// @Retention(RUNTIME) @Target(METHOD)
/// public @interface OrderStreamConsumer {}
///
/// @OrderStreamConsumer
/// Promise<Unit> processOrder(OrderEvent event);
/// }```
///
/// Example (batch):
/// ```{@code
/// @OrderStreamConsumer
/// Promise<Unit> processOrderBatch(List<OrderEvent> events);
/// }```
public interface StreamSubscriber {}
```

**Design notes:**
- REQ-SS-01: Consumer group name defaults to `{sliceName}-{methodName}` but can be overridden in TOML via `consumer-group` in the stream's consumer section.
- REQ-SS-02: Batch vs single is detected by the annotation processor: if the parameter type is `List<T>`, it is batch mode; otherwise single.
- REQ-SS-03: The method must return `Promise<Unit>`. This is validated at compile time by the processor (same validation as `Subscriber`).

### 2.3 StreamAccess<T>

Advanced access interface for manual cursor control, replay, and seek. Injected as a parameter-level resource.

```java
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
    record StreamEvent<T>(long offset, long timestamp, int partition, T payload) {}

    /// Stream metadata.
    record StreamMetadata(
        String streamName,
        int partitionCount,
        List<PartitionInfo> partitions
    ) {}

    /// Per-partition metadata.
    record PartitionInfo(int partition, long headOffset, long tailOffset, long eventCount) {}
}
```

**Design notes:**
- REQ-SA-01: `StreamAccess<T>` is for advanced use cases (audit, replay, manual offset management). Most consumers should use `StreamSubscriber` annotations.
- REQ-SA-02: The `fetch` methods return deserialized `T` objects, not raw bytes. Codec is applied automatically.
- REQ-SA-03: The `commit` method writes through to the consensus cursor checkpoint (same path as automatic consumer commits).

### 2.4 @PartitionKey Annotation

Marks a record field as the partition key. The annotation processor generates a key extractor at compile time.

```java
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
```

**Design notes:**
- REQ-PK-01: Only one `@PartitionKey` per record. The annotation processor enforces this at compile time.
- REQ-PK-02: The processor generates a `PartitionKeyExtractor` functional interface implementation for each annotated type, invoked at publish time.
- REQ-PK-03: `@PartitionKey` on a non-record component is a compile error.
- REQ-PK-04: The hash function is `Math.floorMod(field.hashCode(), partitionCount)`. Same approach as Kafka's default partitioner.

### 2.5 Resource Qualifier Annotations (User-Defined)

Users define their own annotations using the `@ResourceQualifier` meta-annotation, following the established pattern from `@Sql`, `@Http`, `@OrderPublisher`, etc.

**Producer annotation:**
```java
@ResourceQualifier(type = StreamPublisher.class, config = "streams.order-events")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface OrderStream {}
```

**Consumer annotation:**
```java
@ResourceQualifier(type = StreamSubscriber.class, config = "streams.order-events")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OrderStreamConsumer {}
```

**Advanced access annotation:**
```java
@ResourceQualifier(type = StreamAccess.class, config = "streams.order-events")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface OrderStreamAccess {}
```

### 2.6 Complete Slice Example

```java
@Slice
public interface OrderProcessor {

    Promise<OrderResult> placeOrder(PlaceOrderRequest request);

    @OrderStreamConsumer
    Promise<Unit> processOrderEvent(OrderEvent event);

    @OrderStreamConsumer
    Promise<Unit> processOrderBatch(List<OrderEvent> events);

    static OrderProcessor orderProcessor(
            @OrderStream StreamPublisher<OrderEvent> stream,
            @PrimaryDb SqlConnector db) {
        record orderProcessor(StreamPublisher<OrderEvent> stream,
                              SqlConnector db) implements OrderProcessor {
            @Override
            public Promise<OrderResult> placeOrder(PlaceOrderRequest request) {
                return stream.publish(new OrderEvent(request.orderId(),
                                                     request.customerId(),
                                                     request.amount()))
                             .map(ignored -> new OrderResult(request.orderId(), "PLACED"));
            }

            @Override
            public Promise<Unit> processOrderEvent(OrderEvent event) {
                return db.update("INSERT INTO order_events (order_id, amount) VALUES (?, ?)",
                                 event.orderId(), event.amount())
                         .map(ignored -> Unit.unit());
            }

            @Override
            public Promise<Unit> processOrderBatch(List<OrderEvent> events) {
                // Batch insert
                return db.batchUpdate("INSERT INTO order_events (order_id, amount) VALUES (?, ?)",
                                      events.stream()
                                            .map(e -> new Object[]{e.orderId(), e.amount()})
                                            .toList())
                         .map(ignored -> Unit.unit());
            }
        }
        return new orderProcessor(stream, db);
    }
}
```

**Event type with partition key:**
```java
@Codec
public record OrderEvent(
    @PartitionKey String orderId,
    String customerId,
    BigDecimal amount
) {}
```

---

## 3. Blueprint Declaration

### 3.1 Stream Declaration (TOML)

Streams are declared in the blueprint's `resources.toml` alongside other resource configurations, under the `[streams.*]` section.

```toml
[streams.order-events]
partitions = 6                    # Number of partitions (default: 4)
retention = "time"                # "time", "count", or "size" (default: "time")
retention-value = "5m"            # Duration for time, integer for count, size string for size
max-event-size = "64KB"           # Maximum serialized event size (default: "1MB")
backpressure = "drop-oldest"      # "block", "drop-oldest", "reject" (default: "drop-oldest")
```

### 3.2 Consumer Group Configuration

Consumer groups are configured inline under the stream section. Each consumer group maps to a consumer annotation's config section.

```toml
[streams.order-events.consumers.analytics]
auto-offset-reset = "latest"     # "latest" or "earliest" (default: "latest")
checkpoint-interval = "1s"       # Cursor checkpoint frequency (default: "1s")
batch-size = 100                 # Events per batch for batch consumers (default: 1)
processing = "ordered"           # "ordered" or "parallel" (default: "ordered")
on-failure = "retry"             # "retry", "skip", "stall" (default: "retry")
max-retries = 3                  # Max retries before dead-letter or skip (default: 3)
dead-letter = "order-events-dlq" # Dead-letter stream name (optional, omit for no DLQ)

[streams.order-events.consumers.audit]
auto-offset-reset = "earliest"
checkpoint-interval = "5s"
processing = "ordered"
on-failure = "stall"             # Stall on failure — manual intervention required
```

### 3.3 Configuration Reference

#### Stream-Level Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `partitions` | int | `4` | Number of partitions. Immutable after creation (Phase 1). |
| `retention` | string | `"time"` | Retention mode: `"time"`, `"count"`, or `"size"`. |
| `retention-value` | string/int | `"5m"` | Value interpreted by retention mode. Time: duration string. Count: integer. Size: byte size string. |
| `max-event-size` | string | `"1MB"` | Maximum serialized event size. Events exceeding this are rejected at publish. |
| `backpressure` | string | `"drop-oldest"` | Behavior when ring buffer is full: `"block"`, `"drop-oldest"`, `"reject"`. |

#### Consumer-Level Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `auto-offset-reset` | string | `"latest"` | Start position for new consumers: `"latest"` or `"earliest"`. |
| `checkpoint-interval` | string | `"1s"` | How often cursors are checkpointed to consensus. |
| `batch-size` | int | `1` | Number of events per delivery for batch consumers. Ignored for single-event consumers. |
| `processing` | string | `"ordered"` | `"ordered"`: sequential within partition. `"parallel"`: virtual thread per partition. |
| `on-failure` | string | `"retry"` | `"retry"`: retry with backoff. `"skip"`: skip failed event. `"stall"`: stop consuming until manual intervention. |
| `max-retries` | int | `3` | Maximum retry attempts before dead-letter or skip. Only applies when `on-failure = "retry"`. |
| `dead-letter` | string | (none) | Stream name to publish failed events to. If omitted and retries exhausted, event is logged and skipped. |
| `consumer-group` | string | auto-generated | Override the consumer group name. Default: `{sliceName}-{methodName}`. |

### 3.4 Duration and Size String Formats

**Duration:** `"30s"`, `"5m"`, `"1h"`, `"1d"`. Parsed by the existing `TimeSpan` utility.

**Size:** `"64KB"`, `"1MB"`, `"512MB"`, `"2GB"`. Parsed with standard SI suffixes (powers of 1024).

---

## 4. Annotation Processor Changes

### 4.1 New Type Constants

Add to `DependencyModel.java`:

```java
private static final String STREAM_PUBLISHER_TYPE = "org.pragmatica.aether.slice.StreamPublisher";
private static final String STREAM_ACCESS_TYPE = "org.pragmatica.aether.slice.StreamAccess";
```

Add to `MethodModel.java`:

```java
private static final String STREAM_SUBSCRIBER_TYPE = "org.pragmatica.aether.slice.StreamSubscriber";
```

### 4.2 DependencyModel Extensions

**New detection methods:**

```java
/// Check if this dependency is a StreamPublisher resource.
public boolean isStreamPublisher() {
    return resourceQualifier.map(q -> STREAM_PUBLISHER_TYPE.equals(q.resourceType().toString()))
                            .or(false);
}

/// Check if this dependency is a StreamAccess resource.
public boolean isStreamAccess() {
    return resourceQualifier.map(q -> STREAM_ACCESS_TYPE.equals(q.resourceType().toString()))
                            .or(false);
}

/// Extract event type from StreamPublisher<T> or StreamAccess<T> generic parameter.
public Option<String> streamEventType() {
    if ((!isStreamPublisher() && !isStreamAccess()) || !(interfaceType instanceof DeclaredType dt)) {
        return Option.none();
    }
    var typeArgs = dt.getTypeArguments();
    return typeArgs.isEmpty() ? Option.none() : Option.some(typeArgs.getFirst().toString());
}
```

### 4.3 MethodModel Extensions

Add `STREAM_SUBSCRIBER_TYPE` to `classifyAnnotation`:

```java
private static void classifyAnnotation(ResourceQualifierModel model,
                                        List<ResourceQualifierModel> interceptors,
                                        List<ResourceQualifierModel> subscriptions,
                                        List<ResourceQualifierModel> scheduled,
                                        List<ResourceQualifierModel> streamSubscriptions) {
    if (SUBSCRIBER_TYPE.equals(model.resourceType().toString())) {
        subscriptions.add(model);
    } else if (SCHEDULED_TYPE.equals(model.resourceType().toString())) {
        scheduled.add(model);
    } else if (STREAM_SUBSCRIBER_TYPE.equals(model.resourceType().toString())) {
        streamSubscriptions.add(model);
    } else {
        interceptors.add(model);
    }
}
```

**Stream subscription validation:**

- REQ-AP-01: Stream consumer method must have exactly one parameter: either `T` (single event) or `List<T>` (batch).
- REQ-AP-02: Return type must be `Promise<Unit>`.
- REQ-AP-03: If the parameter is `List<T>`, the method is marked as batch mode in the manifest. The element type `T` is extracted from the generic argument.

**Batch detection logic:**

```java
/// Returns true if the method parameter is List<T> (batch consumer).
public boolean isBatchStreamConsumer() {
    if (streamSubscriptions.isEmpty() || parameters.size() != 1) {
        return false;
    }
    var paramType = parameters.getFirst().type();
    return paramType instanceof DeclaredType dt
           && dt.asElement().toString().equals("java.util.List");
}

/// Extract the stream event type from consumer method parameter.
/// For single: parameter type T.
/// For batch: element type T from List<T>.
public Option<String> streamConsumerEventType() {
    if (streamSubscriptions.isEmpty() || parameters.size() != 1) {
        return Option.none();
    }
    var paramType = parameters.getFirst().type();
    if (paramType instanceof DeclaredType dt) {
        if (dt.asElement().toString().equals("java.util.List") && !dt.getTypeArguments().isEmpty()) {
            return Option.some(dt.getTypeArguments().getFirst().toString());
        }
        return Option.some(dt.toString());
    }
    return Option.none();
}
```

### 4.4 @PartitionKey Processing

The annotation processor scans event types referenced by `StreamPublisher<T>` and `StreamSubscriber` methods for `@PartitionKey` annotations on record components.

**Generated partition key extractor:**

For a record `OrderEvent` with `@PartitionKey String orderId`, the processor generates:

```java
// Inside the factory class, as a private static method
private static int orderEventPartitionKey(OrderEvent event, int partitionCount) {
    return Math.floorMod(event.orderId().hashCode(), partitionCount);
}
```

The generated factory wires this extractor into the `StreamPublisher` provisioning call.

**Processor validation:**
- REQ-PK-AP-01: At most one `@PartitionKey` per record. Multiple = compile error.
- REQ-PK-AP-02: `@PartitionKey` on a non-record-component = compile error.
- REQ-PK-AP-03: The annotated field type must not be a primitive (use boxed types). Primitives lack stable `hashCode()` semantics across the cluster. [ASSUMPTION: Java record components with primitive types auto-box correctly for `hashCode()`. If this is acceptable, this constraint can be relaxed.]

### 4.5 ManifestGenerator Extensions

New properties written to the slice manifest:

```properties
# Stream publisher metadata
stream.publishers.count=1
stream.publisher.0.config=streams.order-events
stream.publisher.0.eventType=com.example.OrderEvent
stream.publisher.0.partitionKeyField=orderId
stream.publisher.0.partitionKeyExtractor=orderEventPartitionKey

# Stream subscription metadata
stream.subscriptions.count=2
stream.subscription.0.config=streams.order-events
stream.subscription.0.method=processOrderEvent
stream.subscription.0.eventType=com.example.OrderEvent
stream.subscription.0.batch=false
stream.subscription.1.config=streams.order-events
stream.subscription.1.method=processOrderBatch
stream.subscription.1.eventType=com.example.OrderEvent
stream.subscription.1.batch=true

# Stream access metadata
stream.access.count=0

# Stream event codec classes (for codec registration)
stream.event.classes=com.example.OrderEvent
```

### 4.6 Envelope Version

The current `ENVELOPE_FORMAT_VERSION` is `6`. Adding stream metadata to the manifest changes the manifest structure.

**Decision: Bump to `ENVELOPE_FORMAT_VERSION = 7`.**

The runtime's `SliceManifest` parser must handle version 7 manifests (with stream properties) and remain backward-compatible with version 6 (no stream properties = no streams for that slice).

### 4.7 FactoryClassGenerator Extensions

**StreamPublisher provisioning:**

```java
// Generated code for @OrderStream StreamPublisher<OrderEvent> stream parameter:
var streamPublisher_stream = ctx.resources()
    .provide(StreamPublisher.class, "streams.order-events",
             ProvisioningContext.streamPublisher(
                 OrderEventCodec.INSTANCE,
                 OrderProcessor::orderEventPartitionKey));
```

**StreamSubscriber registration:**

The generated factory registers stream subscription handlers in the same pattern as topic subscriptions. The runtime receives:
- Config section (stream name)
- Method reference (the consumer handler)
- Event type codec
- Batch mode flag

**StreamAccess provisioning:**

```java
// Generated code for @OrderStreamAccess StreamAccess<OrderEvent> access parameter:
var streamAccess_access = ctx.resources()
    .provide(StreamAccess.class, "streams.order-events",
             ProvisioningContext.streamAccess(OrderEventCodec.INSTANCE));
```

### 4.8 Codec Generation

Stream event types must be annotated with `@Codec`. The existing codec processor generates serializer/deserializer classes. No changes to the codec processor are needed -- the stream event types are regular records.

The annotation processor validates that stream event types are `@Codec`-annotated. If not, it emits a compile error:

> Stream event type `com.example.OrderEvent` must be annotated with `@Codec` for serialization.

---

## 5. KV-Store Types

### 5.1 New AetherKey Variants

Add to the `AetherKey` sealed interface:

#### StreamMetadataKey

```
stream-meta/{streamName}
```

Stores stream existence, partition count, configuration. Written by CDM when a blueprint with streams is deployed.

```java
record StreamMetadataKey(String streamName) implements AetherKey {
    private static final String PREFIX = "stream-meta/";

    @Override
    public String asString() {
        return PREFIX + streamName;
    }

    @SuppressWarnings("JBCT-VO-02")
    public static StreamMetadataKey streamMetadataKey(String streamName) {
        return new StreamMetadataKey(streamName);
    }

    public static Result<StreamMetadataKey> streamMetadataKey(String key, boolean isKey) {
        if (!key.startsWith(PREFIX)) {
            return STREAM_METADATA_KEY_FORMAT_ERROR.apply(key).result();
        }
        var name = key.substring(PREFIX.length());
        if (name.isEmpty()) {
            return STREAM_METADATA_KEY_FORMAT_ERROR.apply(key).result();
        }
        return success(new StreamMetadataKey(name));
    }
}
```

#### StreamPartitionAssignmentKey

```
stream-assign/{streamName}/{consumerGroup}
```

Maps partitions to consumer nodes for a consumer group.

```java
record StreamPartitionAssignmentKey(String streamName,
                                     String consumerGroup) implements AetherKey {
    private static final String PREFIX = "stream-assign/";

    @Override
    public String asString() {
        return PREFIX + streamName + "/" + consumerGroup;
    }

    @SuppressWarnings("JBCT-VO-02")
    public static StreamPartitionAssignmentKey streamPartitionAssignmentKey(String streamName,
                                                                            String consumerGroup) {
        return new StreamPartitionAssignmentKey(streamName, consumerGroup);
    }

    public static Result<StreamPartitionAssignmentKey> streamPartitionAssignmentKey(String key) {
        if (!key.startsWith(PREFIX)) {
            return STREAM_PARTITION_ASSIGNMENT_KEY_FORMAT_ERROR.apply(key).result();
        }
        var content = key.substring(PREFIX.length());
        var slashIndex = content.indexOf('/');
        if (slashIndex == -1 || slashIndex == 0 || slashIndex == content.length() - 1) {
            return STREAM_PARTITION_ASSIGNMENT_KEY_FORMAT_ERROR.apply(key).result();
        }
        var streamName = content.substring(0, slashIndex);
        var consumerGroup = content.substring(slashIndex + 1);
        return success(new StreamPartitionAssignmentKey(streamName, consumerGroup));
    }
}
```

#### StreamCursorCheckpointKey

```
stream-cursor/{streamName}/{partitionIndex}/{consumerGroup}
```

Periodic cursor checkpoint for a consumer group on a specific partition.

```java
record StreamCursorCheckpointKey(String streamName,
                                  int partitionIndex,
                                  String consumerGroup) implements AetherKey {
    private static final String PREFIX = "stream-cursor/";

    @Override
    public String asString() {
        return PREFIX + streamName + "/" + partitionIndex + "/" + consumerGroup;
    }

    @SuppressWarnings("JBCT-VO-02")
    public static StreamCursorCheckpointKey streamCursorCheckpointKey(String streamName,
                                                                      int partitionIndex,
                                                                      String consumerGroup) {
        return new StreamCursorCheckpointKey(streamName, partitionIndex, consumerGroup);
    }

    public static Result<StreamCursorCheckpointKey> streamCursorCheckpointKey(String key) {
        if (!key.startsWith(PREFIX)) {
            return STREAM_CURSOR_CHECKPOINT_KEY_FORMAT_ERROR.apply(key).result();
        }
        var content = key.substring(PREFIX.length());
        var parts = content.split("/");
        if (parts.length != 3) {
            return STREAM_CURSOR_CHECKPOINT_KEY_FORMAT_ERROR.apply(key).result();
        }
        return Number.parseInt(parts[1])
                     .map(partition -> new StreamCursorCheckpointKey(parts[0], partition, parts[2]));
    }
}
```

#### StreamRegistrationKey

```
stream-reg/{streamName}/{configSection}/{groupId}:{artifactId}:{version}/{methodName}
```

Maps stream subscriptions to slice method handlers, analogous to `TopicSubscriptionKey`.

```java
record StreamRegistrationKey(String streamName,
                              String configSection,
                              Artifact artifact,
                              MethodName methodName) implements AetherKey {
    private static final String PREFIX = "stream-reg/";

    @Override
    public String asString() {
        return PREFIX + streamName + "/" + configSection + "/"
               + artifact.asString() + "/" + methodName.name();
    }

    // ... standard factory methods and parser following existing patterns
}
```

### 5.2 New AetherValue Variants

Add to the `AetherValue` sealed interface:

#### StreamMetadataValue

```java
record StreamMetadataValue(String streamName,
                            int partitionCount,
                            String retention,
                            String retentionValue,
                            String maxEventSize,
                            String backpressure,
                            BlueprintId owningBlueprint,
                            long createdAt) implements AetherValue {
    public static StreamMetadataValue streamMetadataValue(String streamName,
                                                          int partitionCount,
                                                          String retention,
                                                          String retentionValue,
                                                          String maxEventSize,
                                                          String backpressure,
                                                          BlueprintId owningBlueprint) {
        return new StreamMetadataValue(streamName, partitionCount, retention, retentionValue,
                                       maxEventSize, backpressure, owningBlueprint,
                                       System.currentTimeMillis());
    }
}
```

#### StreamPartitionAssignmentValue

```java
record StreamPartitionAssignmentValue(
    List<PartitionAssignment> assignments,
    long updatedAt
) implements AetherValue {

    public record PartitionAssignment(int partition, NodeId consumerNode) {}

    public static StreamPartitionAssignmentValue streamPartitionAssignmentValue(
            List<PartitionAssignment> assignments) {
        return new StreamPartitionAssignmentValue(List.copyOf(assignments),
                                                   System.currentTimeMillis());
    }
}
```

#### StreamCursorCheckpointValue

```java
record StreamCursorCheckpointValue(long committedOffset,
                                    long commitTimestamp) implements AetherValue {
    public static StreamCursorCheckpointValue streamCursorCheckpointValue(long committedOffset) {
        return new StreamCursorCheckpointValue(committedOffset, System.currentTimeMillis());
    }
}
```

#### StreamRegistrationValue

```java
record StreamRegistrationValue(NodeId nodeId,
                                String consumerGroup,
                                boolean batchMode,
                                String eventType) implements AetherValue {
    public static StreamRegistrationValue streamRegistrationValue(NodeId nodeId,
                                                                   String consumerGroup,
                                                                   boolean batchMode,
                                                                   String eventType) {
        return new StreamRegistrationValue(nodeId, consumerGroup, batchMode, eventType);
    }
}
```

### 5.3 Error Constants

Add to `AetherKey`:

```java
Fn1<Cause, String> STREAM_METADATA_KEY_FORMAT_ERROR =
    Causes.forOneValue("Invalid stream-meta key format: %s");
Fn1<Cause, String> STREAM_PARTITION_ASSIGNMENT_KEY_FORMAT_ERROR =
    Causes.forOneValue("Invalid stream-assign key format: %s");
Fn1<Cause, String> STREAM_CURSOR_CHECKPOINT_KEY_FORMAT_ERROR =
    Causes.forOneValue("Invalid stream-cursor key format: %s");
Fn1<Cause, String> STREAM_REGISTRATION_KEY_FORMAT_ERROR =
    Causes.forOneValue("Invalid stream-reg key format: %s");
```

### 5.4 Cardinality Analysis

For a typical deployment:

| Key Type | Formula | Example (20 streams, 8 partitions, 5 consumer groups) |
|----------|---------|-------------------------------------------------------|
| `StreamMetadataKey` | S | 20 |
| `StreamPartitionAssignmentKey` | S x G | 100 |
| `StreamCursorCheckpointKey` | S x P x G | 800 |
| `StreamRegistrationKey` | S x G (one per consumer method) | 100 |
| **Total** | | **1,020** |

Well within consensus KV-Store budget (typical limit: ~100K keys).

---

## 6. Ring Buffer Implementation

### 6.1 OffHeapRingBuffer

The core data structure for each partition. Uses `java.lang.foreign.MemorySegment` with `Arena.ofShared()` for off-heap allocation.

```java
package org.pragmatica.aether.stream;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Off-heap ring buffer for a single stream partition.
///
/// Memory layout:
/// ```
/// [Header: 64 bytes] [Index: 16 bytes * capacity] [Data: variable]
/// ```
///
/// Header (64 bytes, cache-line aligned):
///   offset 0:  headOffset    (long) — next write position (monotonic)
///   offset 8:  tailOffset    (long) — oldest readable position
///   offset 16: eventCount    (long) — current number of events in buffer
///   offset 24: dataWritePos  (long) — current write position in data region
///   offset 32: dataSize      (long) — total data region size in bytes
///   offset 40: capacity      (long) — max number of events (for count-based retention)
///   offset 48: reserved      (long) — future use
///   offset 56: reserved      (long) — future use
///
/// Index (16 bytes per slot):
///   offset 0: dataOffset    (long) — position in data region
///   offset 8: dataLength    (int)  — serialized event size
///   offset 12: reserved     (int)  — padding / future use
///
/// Data (circular region):
///   Raw serialized event bytes, appended sequentially.
///   When the data region wraps, old data is overwritten.
public final class OffHeapRingBuffer {
    // ... implementation details below
}
```

### 6.2 Memory Layout Constants

```java
// Header offsets
private static final long HEADER_HEAD_OFFSET = 0;
private static final long HEADER_TAIL_OFFSET = 8;
private static final long HEADER_EVENT_COUNT = 16;
private static final long HEADER_DATA_WRITE_POS = 24;
private static final long HEADER_DATA_SIZE = 32;
private static final long HEADER_CAPACITY = 40;
private static final long HEADER_SIZE = 64;

// Index entry layout
private static final long INDEX_ENTRY_SIZE = 16;
private static final long INDEX_DATA_OFFSET = 0;
private static final long INDEX_DATA_LENGTH = 8;

// Derived offsets
private final long indexStart;  // HEADER_SIZE
private final long dataStart;   // HEADER_SIZE + INDEX_ENTRY_SIZE * capacity
```

### 6.3 Construction

```java
/// Create a new off-heap ring buffer.
///
/// @param capacity maximum number of events (for count-based retention)
/// @param dataRegionSize size of the data region in bytes (for size-based retention)
/// @param arena shared arena for memory lifecycle
public static OffHeapRingBuffer offHeapRingBuffer(long capacity,
                                                   long dataRegionSize,
                                                   Arena arena) {
    var indexSize = INDEX_ENTRY_SIZE * capacity;
    var totalSize = HEADER_SIZE + indexSize + dataRegionSize;
    var segment = arena.allocate(totalSize, 64); // 64-byte alignment for cache lines
    // Initialize header
    segment.set(ValueLayout.JAVA_LONG, HEADER_HEAD_OFFSET, -1L);  // no events yet
    segment.set(ValueLayout.JAVA_LONG, HEADER_TAIL_OFFSET, 0L);
    segment.set(ValueLayout.JAVA_LONG, HEADER_EVENT_COUNT, 0L);
    segment.set(ValueLayout.JAVA_LONG, HEADER_DATA_WRITE_POS, 0L);
    segment.set(ValueLayout.JAVA_LONG, HEADER_DATA_SIZE, dataRegionSize);
    segment.set(ValueLayout.JAVA_LONG, HEADER_CAPACITY, capacity);
    return new OffHeapRingBuffer(segment, capacity, dataRegionSize);
}
```

### 6.4 Append Operation

```java
/// Append a serialized event to the buffer.
/// Returns the assigned offset or an error if the event exceeds max size or buffer rejects.
///
/// Thread safety: single-writer (governor thread). No concurrent appends.
/// Consumers may read concurrently (memory segment is shared).
public Result<Long> append(byte[] serializedEvent, long timestamp) {
    if (serializedEvent.length > maxEventSize) {
        return StreamError.eventTooLarge(serializedEvent.length, maxEventSize).result();
    }

    var currentHead = headOffset();
    var newOffset = currentHead + 1;
    var slotIndex = Math.floorMod(newOffset, capacity);

    // Evict if at capacity (count-based)
    while (eventCount() >= capacity) {
        evictOldest();
    }

    // Evict if data region would overflow (size-based)
    while (dataWritePos() + serializedEvent.length > dataRegionSize && eventCount() > 0) {
        evictOldest();
    }

    // Write data to data region (circular)
    var dataPos = Math.floorMod(dataWritePos(), dataRegionSize);
    var dataSegment = segment.asSlice(dataStart + dataPos, serializedEvent.length);
    MemorySegment.copy(MemorySegment.ofArray(serializedEvent), 0,
                       dataSegment, 0, serializedEvent.length);

    // Write index entry
    var indexPos = indexStart + slotIndex * INDEX_ENTRY_SIZE;
    segment.set(ValueLayout.JAVA_LONG, indexPos + INDEX_DATA_OFFSET, dataPos);
    segment.set(ValueLayout.JAVA_INT, indexPos + INDEX_DATA_LENGTH, serializedEvent.length);

    // Update header atomically
    segment.set(ValueLayout.JAVA_LONG, HEADER_HEAD_OFFSET, newOffset);
    segment.set(ValueLayout.JAVA_LONG, HEADER_DATA_WRITE_POS, dataWritePos() + serializedEvent.length);
    segment.set(ValueLayout.JAVA_LONG, HEADER_EVENT_COUNT, eventCount() + 1);

    return Result.success(newOffset);
}
```

### 6.5 Read Operation

```java
/// Read events starting from the given offset.
/// Returns up to maxEvents events. If fromOffset < tailOffset, returns CURSOR_EXPIRED error.
///
/// Thread safety: safe for concurrent reads (no mutation).
public Result<List<RawEvent>> read(long fromOffset, int maxEvents) {
    var tail = tailOffset();
    var head = headOffset();

    if (fromOffset < tail) {
        return StreamError.cursorExpired(fromOffset, tail).result();
    }
    if (fromOffset > head) {
        return Result.success(List.of()); // no new events
    }

    var count = (int) Math.min(maxEvents, head - fromOffset + 1);
    var events = new ArrayList<RawEvent>(count);

    for (long offset = fromOffset; offset < fromOffset + count; offset++) {
        var slotIndex = Math.floorMod(offset, capacity);
        var indexPos = indexStart + slotIndex * INDEX_ENTRY_SIZE;
        var dataPos = segment.get(ValueLayout.JAVA_LONG, indexPos + INDEX_DATA_OFFSET);
        var dataLen = segment.get(ValueLayout.JAVA_INT, indexPos + INDEX_DATA_LENGTH);

        var eventBytes = new byte[dataLen];
        MemorySegment.copy(segment, dataStart + dataPos,
                           MemorySegment.ofArray(eventBytes), 0, dataLen);

        events.add(new RawEvent(offset, eventBytes));
    }

    return Result.success(List.copyOf(events));
}

record RawEvent(long offset, byte[] data) {}
```

### 6.6 Eviction

```java
/// Evict the oldest event from the buffer.
/// Advances tailOffset and decrements eventCount.
private void evictOldest() {
    var tail = tailOffset();
    if (tail > headOffset()) {
        return; // buffer empty
    }
    segment.set(ValueLayout.JAVA_LONG, HEADER_TAIL_OFFSET, tail + 1);
    segment.set(ValueLayout.JAVA_LONG, HEADER_EVENT_COUNT, eventCount() - 1);
}
```

### 6.7 Time-Based Retention

For time-based retention, a periodic sweep runs on the governor thread:

```java
/// Evict events older than the retention duration.
/// Called periodically: interval = min(retentionDuration / 10, 1 second).
public void evictByTime(long retentionMs) {
    var cutoff = System.currentTimeMillis() - retentionMs;
    // Events are stored with timestamps in the index.
    // Walk from tail forward, evicting until we find an event within retention.
    while (eventCount() > 0) {
        var tailSlot = Math.floorMod(tailOffset(), capacity);
        var timestamp = readTimestamp(tailSlot);
        if (timestamp >= cutoff) {
            break;
        }
        evictOldest();
    }
}
```

[ASSUMPTION: Timestamps are stored as an additional field in the index entry. The index entry size should be increased to 24 bytes to accommodate: dataOffset (8) + dataLength (4) + timestamp (8) + padding (4). This changes the memory layout from the initial 16-byte description.]

**Revised index entry (24 bytes):**

| Offset | Type | Field |
|--------|------|-------|
| 0 | long | dataOffset |
| 8 | int | dataLength |
| 12 | int | reserved |
| 16 | long | timestamp |

### 6.8 Memory Accounting

Each ring buffer reports its memory usage:

```java
/// Total memory allocated for this buffer (header + index + data).
public long allocatedBytes() {
    return segment.byteSize();
}

/// Current memory used by live events in the data region.
/// This is an approximation (does not account for fragmentation in circular data region).
public long usedBytes() {
    return HEADER_SIZE + INDEX_ENTRY_SIZE * eventCount() + estimatedDataUsage();
}
```

The governor aggregates per-partition memory usage into its `WorkerGroupHealthReport` for CDM visibility.

### 6.9 Co-Located Zero-Copy Optimization

When producer and consumer are in the same JVM (same node, same classloader), the runtime bypasses serialization:

- REQ-RB-01: The producer passes the Java object directly to the consumer's handler.
- REQ-RB-02: The object is still serialized into the ring buffer for durability and replay, but the consumer receives the original object reference.
- REQ-RB-03: This optimization is transparent -- the `StreamPublisher` implementation detects co-located consumers and maintains a list of direct delivery targets alongside the ring buffer append.

**Implementation:** The `StreamPartitionManager` on the governor maintains a `Map<String, List<DirectConsumer>>` keyed by consumer group. When a produce arrives from a local slice, the manager:
1. Serializes and appends to ring buffer (for replay and remote consumers).
2. Delivers the original Java object to each local `DirectConsumer`.

---

## 7. Partition Ownership

### 7.1 Mapping to DHT Hash Ring

Stream partitions map to governors via the existing consistent hash ring. The partition key is:

```
hash(streamName + ":" + partitionIndex)
```

Each partition hashes to a position on the ring. The governor whose ring segment contains that position owns the partition. This reuses the DHT infrastructure with zero new machinery.

### 7.2 PartitionOwnershipTable

The governor maintains a local lookup table mapping `(streamName, partitionIndex)` to the owning governor:

```java
record PartitionOwner(String streamName, int partitionIndex, NodeId governorId) {}
```

This table is rebuilt when:
- A stream is created or destroyed (KV-Store watch on `stream-meta/*`).
- The hash ring changes (governor join/leave).

### 7.3 Partition Assignment to Consumers

Phase 1 uses **consensus-coordinated assignment** (Option A from exploratory spec, Section 9.3).

When CDM deploys a stream consumer group:
1. CDM reads the `StreamMetadataValue` to get partition count.
2. CDM reads registered consumers from `StreamRegistrationKey` entries.
3. CDM computes a round-robin assignment: partition N -> consumer N % consumerCount.
4. CDM writes the assignment to `StreamPartitionAssignmentKey/Value`.
5. Consumers watch `stream-assign/*` for their group and begin consuming assigned partitions.

### 7.4 Rebalancing Protocol

When a consumer joins or leaves a group:

1. CDM detects membership change (node join/leave, or slice deployment change).
2. CDM recomputes partition assignment.
3. CDM writes new `StreamPartitionAssignmentValue`.
4. Consumers draining old partitions:
   a. Complete in-flight event processing.
   b. Checkpoint cursor.
   c. Release partition.
5. Consumers receiving new partitions:
   a. Read last committed cursor from `StreamCursorCheckpointKey`.
   b. Begin fetching from committed offset.

**Cooperative rebalancing:** Only partitions that change ownership are paused. Consumers keeping their partitions are uninterrupted.

---

## 8. Produce Path

### 8.1 Standard Consistency (Phase 1)

The governor is the single sequencer for each partition it owns. Offset assignment is a local atomic increment.

```
Producer Slice              Local Governor              Owning Governor
     |                           |                           |
     |--[publish(event)]-------->|                           |
     |                           |                           |
     |                  [is local governor the               |
     |                   partition owner?]                   |
     |                           |                           |
     |            YES: [assign offset]                       |
     |                 [append to ring buffer]               |
     |                 [deliver to local consumers]          |
     |                           |                           |
     |            NO:  |--[RouteProduceRequest]------------>|
     |                           |            [assign offset]|
     |                           |            [append]       |
     |                           |<--[ProduceAck]-----------|
     |                           |                           |
     |<--[Promise.success()]-----|                           |
```

### 8.2 Partition Routing

```java
/// Determine target partition for an event.
///
/// If the event type has a @PartitionKey field, use the generated extractor.
/// Otherwise, use round-robin.
int routeToPartition(T event, int partitionCount) {
    if (partitionKeyExtractor != null) {
        return partitionKeyExtractor.apply(event, partitionCount);
    }
    return roundRobinCounter.getAndIncrement() % partitionCount;
}
```

### 8.3 Cross-Governor Produce Routing

Phase 1 uses **routing via core** (Option A from exploratory spec, Section DD-4).

When the producer's local governor does not own the target partition:
1. Local governor forwards the produce request to a core node.
2. Core node routes to the owning governor.
3. Owning governor assigns offset, appends, and returns ACK.

This reuses the existing mutation forwarding path. Latency: +1 hop through core (~1-2ms same-DC).

### 8.4 Co-Located Producer Optimization

If the producer slice runs on the same node as the partition-owning governor:

```
Producer Slice           Governor (same node)
     |                        |
     |--[direct method call]->|
     |                        |  [assign offset]
     |                        |  [serialize + append to ring buffer]
     |                        |  [deliver original object to local consumers]
     |<-[Promise.success()]---|
```

No serialization for local consumer delivery. Serialization happens only for the ring buffer (replay) and remote consumers.

---

## 9. Consume Path

### 9.1 Push Subscription (Default for Annotated Consumers)

Annotated `@StreamSubscriber` methods receive events via push from the governor. The runtime manages the subscription lifecycle.

```
Governor (partition owner)          Consumer Runtime (worker node)
     |                                       |
     |  [new event appended]                 |
     |                                       |
     |--[EventBatch]----------------------->|
     |   events[], highWaterMark             |
     |                                       |  [deserialize]
     |                                       |  [invoke handler method]
     |                                       |  [on success: advance local cursor]
     |                                       |
     |<--[BatchAck]-------------------------|
     |   lastProcessedOffset                 |
```

### 9.2 Pull-Based Fetch (StreamAccess)

`StreamAccess<T>` provides explicit pull semantics:

```java
// Fetch from a partition
stream.fetch(partition, fromOffset, maxEvents)
    .onSuccess(events -> {
        // process events
        stream.commit(consumerGroup, partition, lastOffset);
    });
```

### 9.3 Single Event vs Batch Delivery

**Single event mode** (method takes `T`):
- Runtime invokes the handler once per event.
- Events are delivered sequentially within a partition.
- Natural backpressure: next event is not delivered until the handler's `Promise<Unit>` completes.

**Batch mode** (method takes `List<T>`):
- Runtime accumulates up to `batch-size` events before invoking the handler.
- If fewer events are available, the runtime delivers what it has after a short timeout (100ms).
- Batch delivery is still sequential within a partition (one batch at a time).

### 9.4 Processing Modes

**`processing = "ordered"` (default):**
- Single virtual thread per assigned partition per consumer.
- Events within a partition are processed strictly in order.
- No concurrency within a partition.

**`processing = "parallel"`:**
- One virtual thread per partition, but multiple partitions process concurrently.
- Within each partition, events are still ordered.
- Cross-partition ordering is not guaranteed (same as Kafka).

Implementation:

```java
// Ordered: single executor for all partitions assigned to this consumer
var executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());

// Parallel: one virtual thread per partition
for (var partition : assignedPartitions) {
    Thread.ofVirtual().start(() -> consumePartition(partition));
}
```

### 9.5 Error Handling

#### `on-failure = "retry"` (default)

```
Event processing failed
  |
  v
Retry with exponential backoff (100ms, 200ms, 400ms, ...)
  |
  v
Max retries exceeded?
  |
  YES --> dead-letter configured?
  |         |
  |         YES --> publish to dead-letter stream, advance cursor
  |         NO  --> log error, skip event, advance cursor
  |
  NO  --> retry
```

Retry backoff: `100ms * 2^(attempt - 1)`, capped at 10 seconds.

#### `on-failure = "skip"`

Failed event is logged and skipped immediately. Cursor advances.

#### `on-failure = "stall"`

Consumer stops processing. Event remains at the head of the consumer's queue. Manual intervention required (via Management API: reset cursor or skip event).

### 9.6 Dead-Letter Stream

When `dead-letter` is configured, the dead-letter stream must be declared in the same blueprint:

```toml
[streams.order-events-dlq]
partitions = 1
retention = "time"
retention-value = "24h"
```

Failed events are published to the DLQ stream with metadata headers:
- Original stream name
- Original partition
- Original offset
- Consumer group name
- Failure reason (cause message)
- Retry count

The DLQ event wraps the original serialized bytes. A dedicated `@StreamSubscriber` on the DLQ stream can process failures.

---

## 10. Cursor Management

### 10.1 Governor-Local Tracking

Each consumer maintains its cursor in memory on the governor node:

```java
/// Governor-local cursor state for one consumer-partition pair.
record PartitionCursor(
    String consumerGroup,
    int partition,
    long lastDeliveredOffset,    // last offset delivered to consumer
    long lastAcknowledgedOffset, // last offset ACK'd by consumer
    long lastCheckpointedOffset  // last offset written to consensus
) {}
```

### 10.2 Checkpoint Protocol

Cursors are periodically checkpointed to the consensus KV-Store:

```
Consumer processes events, ACKs to governor
  |
  v
Governor tracks lastAcknowledgedOffset in memory
  |
  v
Checkpoint timer fires (configured by checkpoint-interval)
  |
  v
If lastAcknowledgedOffset > lastCheckpointedOffset:
  |
  v
Write StreamCursorCheckpointValue to consensus KV-Store
  |
  v
Update lastCheckpointedOffset
```

**Consensus checkpoint is a standard KV mutation** -- forwarded through the existing worker -> governor -> core -> consensus path. At a 1-second checkpoint interval, this adds ~1 KV mutation per consumer-partition pair per second.

### 10.3 Checkpoint Interval Configuration

| Setting | Tradeoff |
|---------|----------|
| Short (100ms-500ms) | Less reprocessing on failover. More consensus load. |
| Medium (1s-5s, default 1s) | Good balance. At most 1s of reprocessing on failover. |
| Long (10s-60s) | Minimal consensus load. Up to 60s of reprocessing on failover. |

### 10.4 Recovery on Governor Failover

When a governor fails and a new governor takes over:

1. New governor reads `StreamCursorCheckpointKey/Value` from consensus for all consumer groups on affected partitions.
2. Consumers reconnect and resume from last checkpointed offset.
3. Events between last checkpoint and failure are redelivered (at-least-once within checkpoint window).
4. Consumer handlers must be idempotent within the checkpoint window.

**Phase 1 limitation:** Ring buffer data is lost on governor failure (no replication). Consumers resume from checkpoint offset but events between checkpoint and failure are gone. Consumers receive `CURSOR_EXPIRED` if the new governor's buffer does not contain the checkpointed offset, and fall back to `auto-offset-reset` policy.

---

## 11. CDM Integration

### 11.1 Stream Lifecycle

CDM manages stream lifecycle alongside slices:

**On blueprint deploy:**
1. CDM reads `[streams.*]` sections from the blueprint's `resources.toml`.
2. For each stream, CDM writes a `StreamMetadataKey/Value` to consensus.
3. CDM computes partition-to-governor mappings using the hash ring.
4. Governors watching `stream-meta/*` create `OffHeapRingBuffer` instances for their assigned partitions.

**On blueprint undeploy:**
1. CDM deletes `StreamMetadataKey` from consensus.
2. Governors destroy ring buffers and release memory.
3. CDM deletes associated `StreamPartitionAssignmentKey` and `StreamCursorCheckpointKey` entries.

### 11.2 Consumer Group Management

When CDM detects a slice with stream subscription manifest entries:
1. CDM reads the `stream.subscriptions.*` properties from the manifest.
2. CDM writes `StreamRegistrationKey/Value` to consensus.
3. CDM computes or updates partition assignments (`StreamPartitionAssignmentKey/Value`).
4. The consumer runtime on the assigned node begins consuming.

### 11.3 Consumer Lag-Based Autoscaling

CDM extends its existing autoscaling logic with consumer lag as a signal:

```java
/// Scaling decision for stream consumer slices.
///
/// Inputs:
///   - consumerLag: events behind head per partition, aggregated
///   - lagGrowthRate: is lag increasing, stable, or decreasing?
///   - currentInstances: current consumer slice instance count
///   - maxInstances: max from blueprint config
///
/// Decision:
///   if (consumerLag > lagThreshold && lagGrowthRate > 0 && currentInstances < maxInstances)
///       -> scale up
///   if (consumerLag == 0 && currentInstances > minInstances)
///       -> scale down (with cooldown)
```

**Lag threshold:** Configurable per consumer group. Default: `1000` events sustained for 30 seconds.

**Scaling limits:** Consumer instances cannot exceed partition count (each partition is assigned to at most one consumer in a group).

### 11.4 Stream Health Metrics

The governor reports stream metrics in `WorkerGroupHealthReport`:

```java
record StreamPartitionMetrics(
    String streamName,
    int partitionIndex,
    long headOffset,
    long tailOffset,
    long eventCount,
    long memoryUsedBytes,
    double produceRatePerSecond,    // windowed 1-minute average
    Map<String, Long> consumerLag   // consumer group -> events behind head
) {}
```

These metrics feed into:
- CDM autoscaling decisions (consumer lag).
- Management API / CLI stream status.
- Micrometer metrics export (Prometheus).

### 11.5 Management API Extensions

New endpoints for stream management:

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/streams` | List all streams with partition counts and status |
| GET | `/api/streams/{name}` | Stream detail: partitions, offsets, consumer groups |
| GET | `/api/streams/{name}/consumers` | Consumer groups with lag and assignment details |
| POST | `/api/streams/{name}/consumers/{group}/reset` | Reset consumer group cursor to earliest/latest |
| POST | `/api/streams/{name}/consumers/{group}/skip` | Skip current event (for stalled consumers) |

---

## 12. CDC Adapter

### 12.1 KV-Store Change Notification to Typed Stream

A thin adapter that bridges KV-Store change notifications to a typed stream. When a KV-Store key matching a pattern changes, the adapter publishes a change event to a stream.

### 12.2 Configuration

```toml
[streams.kv-changes]
partitions = 4
retention = "time"
retention-value = "1h"

[cdc.kv-to-stream]
source = "kv-store"
key-pattern = "slice-target/*"     # KV key pattern to watch
target-stream = "kv-changes"       # Target stream name
```

### 12.3 Change Event Type

```java
@Codec
public record KvChangeEvent(
    @PartitionKey String key,
    String oldValue,    // serialized, empty for creates
    String newValue,    // serialized, empty for deletes
    ChangeType changeType,
    long timestamp
) {
    public enum ChangeType { PUT, DELETE }
}
```

### 12.4 Implementation

The CDC adapter runs on core nodes (which have direct access to KV-Store state). It watches the KV-Store for changes matching the configured pattern and publishes `KvChangeEvent` instances to the target stream.

```java
/// CDC adapter: watches KV-Store changes and publishes to a stream.
/// Runs on the leader node (CDM host) to avoid duplicate change events.
///
/// Integration point: registers as a KV-Store mutation listener
/// in the consensus module.
```

### 12.5 Resource Qualifier

```java
// No user-facing resource qualifier needed for CDC.
// CDC is configured in TOML and runs automatically as part of the runtime.
// Consumers of CDC events use standard @StreamSubscriber annotations
// on the target stream.
```

---

## 13. Module Structure

### 13.1 New Maven Modules

#### `aether/aether-stream` (core streaming runtime)

```xml
<artifactId>aether-stream</artifactId>
```

**Contains:**
- `OffHeapRingBuffer` — ring buffer implementation
- `StreamPartitionManager` — partition lifecycle, produce/consume coordination
- `StreamConsumerRuntime` — push subscription, cursor management, error handling
- `StreamProducerProxy` — `StreamPublisher<T>` implementation
- `StreamAccessProxy` — `StreamAccess<T>` implementation
- `StreamResourceFactory` — SPI `ResourceFactory` for stream resource provisioning

**Dependencies:**
- `aether/slice` (KV types, AetherKey/AetherValue)
- `aether/slice-api` (StreamPublisher, StreamSubscriber, StreamAccess, PartitionKey)
- `core` (Result, Option, Promise)
- `integrations/serialization` (Codec runtime)

#### `aether/slice-api` (API additions only)

Add to existing module:
- `StreamPublisher<T>` interface
- `StreamSubscriber` marker interface
- `StreamAccess<T>` interface
- `@PartitionKey` annotation

No new module needed -- these are API types used by slice developers.

### 13.2 Modified Modules

| Module | Changes |
|--------|---------|
| `aether/slice-api` | Add `StreamPublisher`, `StreamSubscriber`, `StreamAccess`, `@PartitionKey` |
| `aether/slice` | Add new `AetherKey`/`AetherValue` variants for stream state |
| `jbct/slice-processor` | Add stream publisher/subscriber/access detection, manifest generation, `@PartitionKey` processing, bump `ENVELOPE_FORMAT_VERSION` to 7 |
| `aether/aether-invoke` | Add `StreamResourceFactory` SPI (or add to existing `ResourceFactory` registry) |
| `aether/aether-deployment` | CDM stream lifecycle, partition assignment, consumer lag scaling |
| `aether/aether-control` | Management API routes for stream status, cursor reset |
| `aether/node` | Wire `aether-stream` into node startup, register stream resource factories |
| `aether/cli` | CLI commands for stream management |

### 13.3 Dependency Graph

```
slice-api (interfaces + annotations)
    |
    v
slice (KV types)
    |
    v
aether-stream (runtime implementation)
    |
    +---> aether-invoke (resource factory registration)
    +---> aether-deployment (CDM integration)
    +---> aether-control (management API)
    +---> node (wiring)
```

---

## 14. Implementation Phases

### Phase 1: Core Streaming (8-10 weeks)

**Sprint 1 (2 weeks): API + Processor**
- Add `StreamPublisher<T>`, `StreamSubscriber`, `StreamAccess<T>`, `@PartitionKey` to `slice-api`
- Annotation processor changes: detection, validation, manifest generation
- Bump `ENVELOPE_FORMAT_VERSION` to 7
- Unit tests for processor

**Sprint 2 (2 weeks): Ring Buffer + KV Types**
- `OffHeapRingBuffer` implementation with full test suite
- New `AetherKey`/`AetherValue` types
- `StreamPartitionManager` with produce/consume operations
- Retention modes (time, count, size)
- Unit tests and benchmarks

**Sprint 3 (2 weeks): Consumer Runtime**
- `StreamConsumerRuntime`: push subscriptions, cursor tracking
- Single event + batch delivery
- Error handling (retry, skip, stall)
- Consumer group assignment via CDM
- Checkpoint protocol

**Sprint 4 (2 weeks): CDM + Integration**
- CDM stream lifecycle (create/destroy)
- Partition assignment and rebalancing
- Management API endpoints
- CLI commands
- Integration tests with Forge

**Sprint 5 (1 week): Polish**
- Co-located zero-copy optimization
- Cross-governor produce routing
- Dead-letter stream support
- Documentation

### Phase 1+ (2-3 weeks)

- Consumer lag-based autoscaling in CDM
- CDC adapter (KV-Store change notifications to stream)
- Micrometer metrics for streams (produce rate, consumer lag, buffer usage)

### Phase 2 (6-8 weeks)

- Governor-push replication to worker replicas
- Governor failover recovery from replicas
- Strong consistency (Rabia produce path)
- Consumer read-preference (leader vs replica)

### Phase 3 (8-10 weeks)

- PostgreSQL persistent backend
- Transactional cursor commits (exactly-once consumer semantics)
- Log compaction
- Compound retention policies

### Total Effort Estimate

| Phase | Scope | Duration |
|-------|-------|----------|
| Phase 1 | Core streaming | 8-10 weeks |
| Phase 1+ | CDM autoscaling, CDC, metrics | 2-3 weeks |
| Phase 2 | Replication, strong consistency | 6-8 weeks |
| Phase 3 | Persistence, transactions, compaction | 8-10 weeks |

---

## 15. Testing Strategy

### 15.1 Unit Tests

| Component | Tests |
|-----------|-------|
| `OffHeapRingBuffer` | Append/read, capacity eviction, time-based eviction, size-based eviction, concurrent read, empty buffer, full buffer, offset wraparound |
| `@PartitionKey` processing | Single field, no field (round-robin), multiple fields (compile error), non-record (compile error) |
| Partition routing | Key-based routing determinism, round-robin distribution, partition count changes |
| Cursor management | Checkpoint write/read, recovery from checkpoint, expired cursor handling |
| Error handling | Retry with backoff, skip, stall, dead-letter publish |
| Manifest generation | Stream publisher/subscriber/access properties, event type classes, batch flag |

**Naming convention:** `methodName_scenario_expectation()` per project standard.

### 15.2 Integration Tests (Forge)

| Test | Description |
|------|-------------|
| `publishAndConsume_singlePartition_eventsDeliveredInOrder` | Single-partition stream, one producer, one consumer. Verify ordering. |
| `publishAndConsume_multiPartition_eventsRoutedByKey` | Multi-partition stream, publish events with partition keys. Verify key-based routing. |
| `consumerGroup_multipleConsumers_partitionsDistributed` | Deploy multiple consumer instances. Verify each gets a subset of partitions. |
| `consumerRebalance_consumerLeaves_partitionsReassigned` | Remove a consumer instance. Verify orphaned partitions reassigned. |
| `cursorCheckpoint_governorRestart_consumeResumesFromCheckpoint` | Checkpoint cursors, restart governor, verify consumption resumes from checkpoint. |
| `batchConsumer_batchSize10_eventsDeliveredInBatches` | Configure batch-size=10, verify handler receives lists of 10. |
| `retention_timeBasedEviction_oldEventsRemoved` | Publish events, wait for retention window, verify old events are evicted. |
| `errorHandling_retryExhausted_eventSentToDeadLetter` | Configure retry + DLQ, fail processing, verify event appears in DLQ stream. |

### 15.3 E2E Tests (Multi-Node Cluster)

| Test | Description |
|------|-------------|
| `crossNodeProduce_producerOnDifferentNode_eventDelivered` | Producer on node A, partition owner on node B. Verify event is routed and delivered. |
| `governorFailover_partitionOwnerDies_consumptionResumes` | Kill governor owning partitions. Verify new governor takes over, consumers resume. |
| `scalingTest_consumerLag_autoscaleUp` | Generate high produce rate, verify CDM scales up consumer instances based on lag. |

### 15.4 Performance Benchmarks

| Benchmark | Target |
|-----------|--------|
| Single-partition produce throughput | > 500K events/s (co-located, 100B events) |
| Single-partition produce latency (co-located) | < 10 microseconds p99 |
| Cross-node produce latency | < 2ms p99 |
| Ring buffer memory overhead per event | < 40 bytes (index entry + header amortization) |
| Checkpoint consensus overhead | < 1ms per checkpoint |

---

## References

### Internal
- [In-Memory Streams Exploratory Spec](in-memory-streams-spec.md) -- full design exploration and decision rationale
- [Passive Worker Pools Spec](passive-worker-pools-spec.md) -- two-layer topology, governor protocol, hash ring
- [KV-Store Scalability Analysis](../internal/kv-store-scalability.md) -- consensus data budget
- [Slice API Reference](../reference/slice-api.md) -- `@ResourceQualifier`, manifest format, factory generation
- [Envelope Versioning](../contributors/envelope-versioning.md) -- `ENVELOPE_FORMAT_VERSION` policy

### External
- [Apache Kafka Partitioner](https://kafka.apache.org/documentation/#producerconfigs_partitioner.class) -- key-based partition routing reference
- [LMAX Disruptor](https://lmax-exchange.github.io/disruptor/) -- ring buffer mechanical sympathy patterns
- [Java Foreign Function & Memory API](https://docs.oracle.com/en/java/javase/22/core/foreign-function-and-memory-api.html) -- `MemorySegment`, `Arena` usage
- [Chronicle Queue](https://chronicle.software/chronicle-queue/) -- off-heap queue patterns for Java
