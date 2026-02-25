---
RFC: 0011
Title: Messaging & Resource Lifecycle
Status: Draft
Author: Aether Team
Created: 2026-02-21
Updated: 2026-02-21
Affects: [slice-processor, slice-api, aether-invoke, aether-deployment, resource-api]
---

## Summary

Adds inter-slice messaging (pub/sub with competing consumers) and resource lifecycle management (reference-counted cleanup on slice unload). Messaging reuses ~80% of existing invocation infrastructure (`SliceInvoker`, `MethodHandle.fireAndForget()`, `EndpointRegistry`). Resource lifecycle closes a cleanup gap where provisioned resources (DB pools, HTTP clients) have no shutdown path when slices unload.

## Motivation

### Three Resource Categories

Aether slices consume three distinct resource types:

| Category | Example | Provisioning | Current Status |
|----------|---------|-------------|----------------|
| **Regular resources** | `SqlConnector`, `HttpClient` | `@ResourceQualifier` + `SpiResourceProvider` | Implemented ([RFC-0012](RFC-0012-resource-provisioning.md)) |
| **Aspects** | `@Cached`, `@Logged` | Compile-time wiring + `AspectFactory` | Implemented ([RFC-0008](RFC-0008-aspect-framework.md)) |
| **Messaging** | `Publisher<T>`, `@Subscribe` | Not yet designed | **This RFC** |

### Messaging Use Cases

Slices need event-driven, decoupled communication:
- Order placed -> inventory reservation, notification dispatch, audit log
- User registered -> welcome email, analytics event, profile enrichment
- Payment processed -> receipt generation, ledger update

These are fire-and-forget flows where the publisher shouldn't know (or wait for) subscriber implementations. The existing `MethodHandle.fireAndForget()` already supports this invocation pattern — what's missing is topic-based discovery and fan-out.

### Resource Cleanup Gap

`SpiResourceProvider` (`aether/resource/api/src/main/java/org/pragmatica/aether/resource/SpiResourceProvider.java`) caches resources by `(resourceType, configSection)` key at node level. When a slice unloads:

1. `NodeDeploymentManager` unpublishes endpoints and HTTP routes during `DEACTIVATING`
2. `Slice.stop()` is called (currently a no-op in generated code)
3. The slice classloader is discarded

**Missing:** No path to release resources provisioned for the unloaded slice. If the last consumer of a DB pool unloads, the pool remains open indefinitely.

## Messaging Design

### Subscriber API

A subscriber is a regular slice method annotated with `@Subscribe`:

```java
@Slice
public interface AuditLogger {
    @Subscribe("order.placed")
    Promise<Unit> onOrderPlaced(OrderPlacedEvent event);

    @Subscribe("payment.processed")
    Promise<Unit> onPaymentProcessed(PaymentEvent event);
}
```

**Key insight:** Subscriber methods ARE regular `SliceMethod` entries — the `@Subscribe` annotation only adds topic metadata. Zero runtime changes needed for receiving messages. The existing `SliceBridge.invoke()` and `InvocationHandler` handle delivery unchanged.

**Rules:**
- Return type must be `Promise<Unit>` (fire-and-forget semantics)
- Exactly one parameter (the message type)
- Method is also callable directly via `MethodHandle` (dual-use)
- Multiple `@Subscribe` methods per slice allowed (different topics)

### Publisher API

A publisher is injected as a constructor parameter using a `@ResourceQualifier`-style annotation:

```java
@Publish(topic = "order.placed", config = "messaging.orders")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface OrderEvents {}
```

Used in a slice factory:

```java
@Slice
public interface OrderService {
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);

    static OrderService orderService(
            @PrimaryDb SqlConnector db,
            @OrderEvents Publisher<OrderPlacedEvent> publisher) {
        return new orderService(db, publisher);
    }
}
```

**`@Publish` meta-annotation:**

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface Publish {
    String topic();
    String config() default "";  // Optional config section for tuning
}
```

`@Publish` follows the same meta-annotation pattern as `@ResourceQualifier` (`aether/slice-api/src/main/java/org/pragmatica/aether/slice/annotation/ResourceQualifier.java`). The annotation processor detects `@Publish` on parameter annotations and generates provisioning code.

### Publisher<T> Interface

```java
public interface Publisher<T> {
    /// Publish a message to all subscribers of this topic.
    /// Fire-and-forget from the caller's perspective — delivery is best-effort
    /// with in-memory retries to available subscribers.
    Promise<Unit> publish(T message);
}
```

Internally, `Publisher<T>` wraps a `MethodHandle.fireAndForget()` call with dynamic subscriber lookup. Each `publish()` call:

1. Looks up current subscribers for the topic via `TopicSubscriptionRegistry`
2. Selects one subscriber endpoint per artifact (competing consumers)
3. Calls `MethodHandle.fireAndForget()` to each selected endpoint

This is conceptually a multi-cast `MethodHandle.fireAndForget()` with dynamic subscriber lookup.

### Delivery Semantics

**Competing consumers (default and only mode for v1):**
- When multiple instances of the same subscriber artifact are deployed, ONE instance handles each message
- Endpoint selection uses existing round-robin in `EndpointRegistry.selectEndpoint()` (`aether/aether-invoke/src/main/java/org/pragmatica/aether/endpoint/EndpointRegistry.java:161`)
- This matches the default `SliceInvoker` behavior — no new routing logic needed

**Wire protocol:**
- Uses existing `InvokeRequest` with `expectResponse=false` (`aether/aether-invoke/src/main/java/org/pragmatica/aether/invoke/InvocationMessage.java:31`)
- No protocol changes required — fire-and-forget invocations already work

**Retry and failover:**
- Reuses existing `SliceInvoker` retry logic for fire-and-forget
- If the selected endpoint's node is unreachable, `SliceInvoker` detects node departure via `onNodeRemoved`/`onNodeDown` and retries to another instance

### Serialization

Message types must be serializable by Fury (the existing Aether serializer).

The annotation processor extracts `T` from:
- `@Subscribe` method: parameter type of the annotated method
- `Publisher<T>`: type argument of the `Publisher` parameter

Both are registered with the serializer factory during `registerSliceBridge()` in `NodeDeploymentManager` (`aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/node/NodeDeploymentManager.java:316`), alongside existing `SliceMethod` type tokens.

### Topic Discovery

#### Consensus Key Types

New `AetherKey` variant in the sealed hierarchy (`aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherKey.java`):

```java
/// Topic subscription key format:
/// ```
/// topic-sub/{topicName}/{groupId}:{artifactId}:{version}/{methodName}
/// ```
/// Maps a topic to a subscribing slice method.
record TopicSubscriptionKey(String topicName,
                            Artifact artifact,
                            MethodName methodName) implements AetherKey {
    private static final String PREFIX = "topic-sub/";

    @Override
    public String asString() {
        return PREFIX + topicName + "/" + artifact.asString() + "/" + methodName.name();
    }
}
```

Value type mirrors the `EndpointValue` pattern:

```java
record TopicSubscriptionValue(NodeId nodeId) implements AetherValue {
    // Same pattern as EndpointValue
}
```

#### TopicSubscriptionRegistry

Mirrors `EndpointRegistry` (`aether/aether-invoke/src/main/java/org/pragmatica/aether/endpoint/EndpointRegistry.java`) — a passive KV-Store watcher that maintains a local cache of topic subscriptions:

```java
public interface TopicSubscriptionRegistry {
    @MessageReceiver
    void onSubscriptionPut(ValuePut<TopicSubscriptionKey, TopicSubscriptionValue> valuePut);

    @MessageReceiver
    void onSubscriptionRemove(ValueRemove<TopicSubscriptionKey, TopicSubscriptionValue> valueRemove);

    /// Find all subscriber endpoints for a topic.
    /// Returns one endpoint per unique artifact (competing consumers —
    /// only one instance per artifact handles each message).
    List<TopicSubscriber> findSubscribers(String topicName);

    record TopicSubscriber(Artifact artifact,
                           MethodName methodName,
                           NodeId nodeId) {}
}
```

**Fan-out logic in `findSubscribers()`:**
- Groups subscribers by `(artifact, methodName)`
- Selects one `NodeId` per group (round-robin, same as `EndpointRegistry.selectEndpoint()`)
- Returns the selected subscriber per artifact — each message goes to exactly one instance of each subscriber type

### Lifecycle

Topic subscriptions follow the same lifecycle as endpoints in `NodeDeploymentManager`:

**ACTIVATING phase** (alongside `publishEndpoints` at line 238):
1. Read `@Subscribe` metadata from slice manifest
2. Create `TopicSubscriptionKey` for each `(topic, artifact, method)` tuple
3. Submit `KVCommand.Put` to consensus — same batch as endpoint puts

**DEACTIVATING phase** (alongside `unpublishEndpoints` at line 390):
1. Create `KVCommand.Remove` for each `TopicSubscriptionKey`
2. Submit alongside endpoint removals

This ensures topic subscriptions and endpoints are always in sync — a slice is only a subscriber when it's ACTIVE.

### Manifest Changes

The slice manifest (`META-INF/slice/{SliceName}.manifest`) gains topic subscription metadata:

```properties
# Topic subscriptions
topic.subscriptions.count=2
topic.subscription.0.topic=order.placed
topic.subscription.0.method=onOrderPlaced
topic.subscription.1.topic=payment.processed
topic.subscription.1.method=onPaymentProcessed
```

This enables `NodeDeploymentManager` to publish subscription keys without inspecting annotations at runtime.

## Resource Lifecycle Design

### Problem

`SpiResourceProvider` (`aether/resource/api/src/main/java/org/pragmatica/aether/resource/SpiResourceProvider.java`) uses a flat `ConcurrentHashMap<CacheKey, Promise<?>>` keyed by `(resourceType, configSection)`. Resources are created on first request and cached indefinitely. There is no mechanism to:

1. Track which slices use which resources
2. Release resources when slices unload
3. Close shared resources when the last consumer unloads

### Reference-Counted Resource Tracking

Extend `SpiResourceProvider` with per-slice reference counting:

```java
/// Track which slices are using each cached resource.
/// When a slice provides resources, increment ref count.
/// When a slice stops, decrement. Close resource when count reaches 0.
private final Map<CacheKey, AtomicInteger> refCounts = new ConcurrentHashMap<>();
private final Map<CacheKey, Set<Artifact>> consumers = new ConcurrentHashMap<>();
```

**Acquire (during slice activation):**

Each `provide()` call is already artifact-scoped via `ProvisioningContext`. The generated factory code passes the slice artifact:

```java
// Generated: resource acquisition tracked per slice
ctx.resources().provide(SqlConnector.class, "database.primary", provisioningContext)
```

The provider increments the ref count for `(SqlConnector, "database.primary")` and records the slice artifact as a consumer.

**Release (during slice deactivation):**

New method on `ResourceProvider`:

```java
/// Release all resources held by the given slice artifact.
/// Decrements ref counts; closes resources that reach zero consumers.
Promise<Unit> releaseAll(Artifact sliceArtifact);
```

Called from the generated `stop()` implementation (see below).

### Generated stop() Implementation

Currently, `Slice.stop()` returns `Promise.unitPromise()` (no-op). The annotation processor generates cleanup code:

```java
@Override
public Promise<Unit> stop() {
    return ResourceProvider.instance()
                           .map(provider -> provider.releaseAll(artifact))
                           .or(Promise.unitPromise());
}
```

This is called by `NodeDeploymentManager` during `DEACTIVATING` → `sliceStore.deactivateSlice()` which invokes `Slice.stop()`.

### Closeable Resources

`ResourceFactory` gains an optional cleanup hook:

```java
public interface ResourceFactory<T, C> {
    // ... existing methods ...

    /// Close/cleanup a resource instance.
    /// Default implementation handles AutoCloseable resources.
    default Promise<Unit> close(T resource) {
        if (resource instanceof AutoCloseable closeable) {
            return Promise.lift(() -> { closeable.close(); return Unit.unit(); });
        }
        return Promise.unitPromise();
    }
}
```

When `SpiResourceProvider` decrements a ref count to zero, it calls `factory.close(resource)` to release the underlying connection pool, HTTP client, etc.

### Shared vs Per-Slice Resources

| Resource Type | Scope | Cleanup |
|--------------|-------|---------|
| **DB pool** (`SqlConnector`) | Node-scoped, shared | Ref-counted; closed when last slice releases |
| **HTTP client** (`HttpClient`) | Node-scoped, shared | Ref-counted; closed when last slice releases |
| **Publisher** (`Publisher<T>`) | Per-slice (lightweight) | Released with owning slice; no shared state |
| **Cache** | Node-scoped, shared | Ref-counted; entries evicted when closed |

Node-scoped resources benefit from sharing (connection pool reuse across slices using the same DB). The ref counting ensures they're cleaned up when no longer needed, without premature closure while other slices still use them.

## Annotation Processor Changes

### @Subscribe Detection

In `FactoryClassGenerator` (`jbct/slice-processor/src/main/java/org/pragmatica/jbct/slice/generator/FactoryClassGenerator.java`):

1. Scan `@Slice` interface methods for `@Subscribe` annotation
2. For each `@Subscribe` method:
   - Validate: return type is `Promise<Unit>`, exactly one parameter
   - Extract topic name from annotation value
   - Add to manifest subscription list
   - Register parameter type with serializer (existing TypeToken path)

No changes to the generated `SliceMethod` entry — the method is a regular slice method. The annotation only affects manifest metadata.

### @Publish Detection

1. Scan factory method parameters for annotations meta-annotated with `@Publish`
2. For each `@Publish` parameter:
   - Validate: parameter type is `Publisher<T>`
   - Extract `T` via `TypeMirror` processing
   - Extract topic name and optional config section
   - Generate `TopicPublisherFactory.create(topicName, typeToken, registry)` call in `Promise.all()` chain
   - Register message type `T` with serializer

### Generated stop() with Resource Release

1. Track all `@ResourceQualifier` and `@Publish` resources provisioned during factory execution
2. Generate `stop()` that calls `resourceProvider.releaseAll(artifact)` for resource cleanup
3. Publisher cleanup: unsubscribe from `TopicSubscriptionRegistry` (handled by endpoint unpublish)

## Reused Infrastructure

| Component | Location | Reuse |
|-----------|----------|-------|
| `MethodHandle.fireAndForget()` | `aether/slice-api/.../MethodHandle.java:27` | Publisher delivery mechanism |
| `SliceInvoker.invoke(artifact, method, request)` | `aether/aether-invoke/.../SliceInvoker.java:123` | Fire-and-forget invocation path |
| `InvokeRequest(expectResponse=false)` | `aether/aether-invoke/.../InvocationMessage.java:31` | Wire protocol for messaging |
| `EndpointRegistry.selectEndpoint()` | `aether/aether-invoke/.../EndpointRegistry.java:54` | Round-robin subscriber selection |
| `EndpointKey` / `EndpointValue` | `aether/slice/.../AetherKey.java:136` | Pattern for `TopicSubscriptionKey/Value` |
| `NodeDeploymentManager.publishEndpoints()` | `aether/aether-deployment/.../NodeDeploymentManager.java:340` | Pattern for publishing topic subscriptions |
| `NodeDeploymentManager.unpublishEndpoints()` | `aether/aether-deployment/.../NodeDeploymentManager.java:416` | Pattern for unpublishing subscriptions |
| `@ResourceQualifier` | `aether/slice-api/.../annotation/ResourceQualifier.java` | Pattern for `@Publish` meta-annotation |
| `SpiResourceProvider` | `aether/resource/api/.../SpiResourceProvider.java` | Extended with ref counting |
| `FactoryClassGenerator` | `jbct/slice-processor/.../FactoryClassGenerator.java` | Extended for `@Subscribe`/`@Publish` |
| `Slice.stop()` | `aether/slice-api/.../Slice.java:11` | Hook for generated resource cleanup |
| Fury serializer registration | `NodeDeploymentManager.registerSliceBridge()` line 316 | Message type registration path |

## Non-Goals / Deferred

The following are explicitly out of scope for v1:

| Feature | Rationale |
|---------|-----------|
| **Broadcast delivery** | All subscribers get every message. Would require `findAllSubscribers()` variant. Design later based on demand. |
| **Message ordering / partitioning** | Requires partition-aware routing (hash key → node). Complex; not needed for initial use cases. |
| **Durable at-least-once delivery** | Requires persistence (WAL or external store). In-memory retries via existing `SliceInvoker` failover suffice for v1. |
| **Dead letter handling** | Failed messages silently dropped (logged). Proper DLQ requires storage and management API. |
| **Idempotency framework** | Subscriber-side deduplication. Application concern for now — can add `@Idempotent` annotation later. |
| **External message broker integration** | Kafka/RabbitMQ bridge. Aether messaging is internal; external integration is a separate concern. |
| **Backpressure / flow control** | Rate limiting publishers. Fire-and-forget semantics mean no backpressure signal path. |

## Alternatives Considered

### Dedicated Messaging Runtime

**Approach:** Build a separate messaging subsystem with its own message routing, serialization, and delivery pipeline.

**Rejected:** The existing invocation infrastructure (`SliceInvoker`, `EndpointRegistry`, `InvokeRequest`, Fury serialization) already provides message routing, serialization, endpoint selection, retry, and failover. Building a parallel system would duplicate all of this with no added value for the competing-consumers pattern.

### Topic as a First-Class Slice

**Approach:** Model each topic as a virtual slice that fans out to subscribers.

**Rejected:** Adds unnecessary indirection. A topic is a routing concept, not a computation unit. Reifying it as a slice would bloat the endpoint registry and add a network hop (publisher → topic-slice → subscriber) where direct delivery suffices.

### @Subscribe on the Slice Interface (Not Methods)

**Approach:** `@Subscribe("order.placed") @Slice interface AuditLogger` — entire slice subscribes to one topic.

**Rejected:** Too coarse. A single slice often subscribes to multiple topics (audit logger listens to order events AND payment events). Per-method annotation is more flexible and mirrors how `@Http` routes work on individual methods.

### Resource Lifecycle via Explicit close() on ResourceProvider

**Approach:** Require slices to explicitly call `resourceProvider.close(resource)` in their `stop()` method.

**Rejected:** Error-prone (developers forget), violates the declarative philosophy, and duplicates information already known at compile time (which resources were provisioned). Generated cleanup is safer and complete by construction.

## References

- [RFC-0001: Core Slice Contract](RFC-0001-core-slice-contract.md) — `Slice` interface, `SliceMethod`, factory conventions, manifest format
- [RFC-0012: Resource Provisioning](RFC-0012-resource-provisioning.md) — `@ResourceQualifier`, `SpiResourceProvider`, `ResourceFactory` SPI
- [RFC-0008: Aspect Framework](RFC-0008-aspect-framework.md) — Compile-time aspect wiring, `@Aspect` annotations
- [RFC-0010: Unified Invocation Observability](RFC-0010-unified-invocation-observability.md) — Invocation tracing, `InvocationContext`
- Existing: `aether/slice-api/src/main/java/org/pragmatica/aether/slice/MethodHandle.java`
- Existing: `aether/aether-invoke/src/main/java/org/pragmatica/aether/invoke/SliceInvoker.java`
- Existing: `aether/aether-invoke/src/main/java/org/pragmatica/aether/endpoint/EndpointRegistry.java`
- Existing: `aether/resource/api/src/main/java/org/pragmatica/aether/resource/SpiResourceProvider.java`
- Existing: `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/node/NodeDeploymentManager.java`
- Existing: `aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherKey.java`
