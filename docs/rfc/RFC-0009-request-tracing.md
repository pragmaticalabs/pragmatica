---
RFC: 0009
Title: Built-in Request Tracing
Status: Superseded
Superseded-By: RFC-0010
Author: Aether Team
Created: 2026-02-15
Updated: 2026-02-20
Affects: [aether-invoke, aether-node, forge-core]
---

> **Note:** This RFC has been superseded by [RFC-0010: Unified Invocation Observability](RFC-0010-unified-invocation-observability.md), which unifies request tracing with depth-based automatic logging into a single invocation tree system.

## Summary

Add a two-tier built-in request tracing system to Aether. Tier 1 stores completed invocation trace entries in an in-memory ring buffer queryable via Management API and rendered in the dashboard. Tier 2 emits structured log entries at invocation boundaries for long-term retention via existing logging infrastructure.

## Motivation

Aether already records aggregated invocation metrics (`InvocationMetricsCollector`) and optional per-invocation aspect logging (`DynamicAspectInterceptor`). Neither provides a middle ground: the ability to inspect individual request call chains through the cluster without enabling verbose logging or attaching external tooling.

Developers and operators need to:

1. **Diagnose individual failures** -- metrics show error rates but not which specific request failed or why.
2. **Understand call chains** -- a single HTTP request can fan out into multiple local and remote slice invocations sharing a request ID. There is no way to see this chain today.
3. **Inspect latency breakdown** -- which hop in a multi-node call chain was slow?
4. **Do all of the above without external dependencies** -- Aether is a self-contained runtime. Adding OpenTelemetry, Jaeger, or Zipkin would introduce heavyweight dependencies for a problem solvable with existing infrastructure.

## Design

### Boundaries

- **aether-invoke**: `TraceEntry` record, `TraceCollector` interface, `TraceRingBuffer` implementation. Instrumentation hooks in `DynamicAspectInterceptor` / `SliceInvoker` / `InvocationHandler`.
- **aether-node**: `TraceRoutes` management API endpoints. `ManagementServer` wiring. Dashboard "Requests" tab.
- **forge-core**: `TraceProxyRoutes` for proxying trace queries to cluster nodes from the Forge dashboard.

### 1. Data Model

```java
package org.pragmatica.aether.invoke.trace;

import org.pragmatica.lang.Option;

import java.time.Instant;

/// A single trace entry capturing one invocation boundary crossing.
/// Immutable record -- created once when an invocation completes.
public record TraceEntry(String requestId,
                         Instant timestamp,
                         String nodeId,
                         String sourceSlice,
                         String targetSlice,
                         String method,
                         long durationNs,
                         Outcome outcome,
                         Option<String> errorMessage,
                         boolean local,
                         int hops) {

    public enum Outcome { SUCCESS, FAILURE }

    /// Factory: successful local invocation.
    public static TraceEntry traceEntry(String requestId,
                                        String nodeId,
                                        String sourceSlice,
                                        String targetSlice,
                                        String method,
                                        long durationNs,
                                        boolean local,
                                        int hops) {
        return new TraceEntry(requestId,
                              Instant.now(),
                              nodeId,
                              sourceSlice,
                              targetSlice,
                              method,
                              durationNs,
                              Outcome.SUCCESS,
                              Option.empty(),
                              local,
                              hops);
    }

    /// Factory: failed invocation.
    public static TraceEntry failedTraceEntry(String requestId,
                                              String nodeId,
                                              String sourceSlice,
                                              String targetSlice,
                                              String method,
                                              long durationNs,
                                              String errorMessage,
                                              boolean local,
                                              int hops) {
        return new TraceEntry(requestId,
                              Instant.now(),
                              nodeId,
                              sourceSlice,
                              targetSlice,
                              method,
                              durationNs,
                              Outcome.FAILURE,
                              Option.option(errorMessage),
                              local,
                              hops);
    }

    /// Duration in milliseconds (convenience).
    public double durationMs() {
        return durationNs / 1_000_000.0;
    }
}
```

**Field semantics:**

| Field | Type | Description |
|-------|------|-------------|
| `requestId` | `String` | KSUID from `InvocationContext`. Shared across all hops of one logical request. |
| `timestamp` | `Instant` | Wall-clock time when the entry was created (invocation completion). |
| `nodeId` | `String` | `NodeId.id()` of the node that recorded this entry. |
| `sourceSlice` | `String` | Calling slice artifact string, or `"HTTP"` for external HTTP entry. |
| `targetSlice` | `String` | Target slice artifact string (full `groupId:artifactId:version`). |
| `method` | `String` | Method name being invoked. |
| `durationNs` | `long` | Nanosecond-precision duration from invocation start to completion. |
| `outcome` | `Outcome` | `SUCCESS` or `FAILURE`. |
| `errorMessage` | `Option<String>` | Failure cause message. Empty on success. |
| `local` | `boolean` | `true` if invocation was local (same node), `false` if remote. |
| `hops` | `int` | Number of nodes this request has traversed. Incremented on each remote hop. |

### 2. Ring Buffer

```java
package org.pragmatica.aether.invoke.trace;

import org.pragmatica.lang.Option;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/// Lock-free-read, lock-on-write bounded circular buffer for trace entries.
///
/// Design constraints:
/// - Fixed capacity, no allocation after initialization (array pre-allocated)
/// - Oldest entries silently evicted when full
/// - Thread-safe for concurrent writes from multiple invocation threads
/// - Query operations use a snapshot approach (copy-on-read)
public final class TraceRingBuffer {
    public static final int DEFAULT_CAPACITY = 50_000;

    private final TraceEntry[] buffer;
    private final int capacity;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicLong writeSequence = new AtomicLong();
    private final AtomicLong totalWrites = new AtomicLong();

    private TraceRingBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer = new TraceEntry[capacity];
    }

    /// Factory with default capacity (50,000 entries).
    public static TraceRingBuffer traceRingBuffer() {
        return new TraceRingBuffer(DEFAULT_CAPACITY);
    }

    /// Factory with custom capacity.
    public static TraceRingBuffer traceRingBuffer(int capacity) {
        return new TraceRingBuffer(capacity);
    }

    /// Add a trace entry. Overwrites oldest entry when full.
    public void add(TraceEntry entry) {
        writeLock.lock();
        try {
            var index = (int) (writeSequence.getAndIncrement() % capacity);
            buffer[index] = entry;
            totalWrites.incrementAndGet();
        } finally {
            writeLock.unlock();
        }
    }

    /// Query entries matching a predicate, most recent first.
    /// Returns at most `limit` entries.
    public List<TraceEntry> query(Predicate<TraceEntry> filter, int limit) {
        // snapshot read
        var result = new ArrayList<TraceEntry>(Math.min(limit, capacity));
        var seq = writeSequence.get();
        var count = Math.min(seq, capacity);

        for (long i = seq - 1; i >= seq - count && result.size() < limit; i--) {
            var entry = buffer[(int) (i % capacity)];
            if (entry != null && filter.test(entry)) {
                result.add(entry);
            }
        }
        return result;
    }

    /// Find all entries for a given request ID (full call chain).
    public List<TraceEntry> findByRequestId(String requestId) {
        return query(e -> e.requestId().equals(requestId), capacity);
    }

    /// Total number of entries written (including evicted).
    public long totalWrites() {
        return totalWrites.get();
    }

    /// Current number of entries in the buffer.
    public int size() {
        return (int) Math.min(writeSequence.get(), capacity);
    }

    /// Current capacity.
    public int capacity() {
        return capacity;
    }
}
```

**Memory footprint estimate:** Each `TraceEntry` is approximately 200-300 bytes (strings are interned/shared for slice artifacts). At 50,000 entries: ~12-15 MB. Acceptable for a runtime process.

### 3. Trace Collector

```java
package org.pragmatica.aether.invoke.trace;

/// Abstraction over trace recording. Allows disabling tracing entirely
/// or swapping implementations (e.g., sampling).
public interface TraceCollector {

    /// Record a completed trace entry.
    void record(TraceEntry entry);

    /// Query the trace buffer. Delegates to underlying ring buffer.
    TraceRingBuffer buffer();

    /// Whether tracing is currently enabled.
    boolean enabled();

    /// Create an active collector backed by a ring buffer.
    static TraceCollector traceCollector(TraceRingBuffer buffer, double samplingRate) {
        if (samplingRate >= 1.0) {
            return new FullTraceCollector(buffer);
        }
        return new SamplingTraceCollector(buffer, samplingRate);
    }

    /// Create a no-op collector (tracing disabled).
    static TraceCollector noOp() {
        return NoOpTraceCollector.INSTANCE;
    }
}
```

**Sampling:** When `samplingRate < 1.0`, `SamplingTraceCollector` uses `ThreadLocalRandom` to probabilistically skip entries. This is useful for high-throughput clusters where recording every invocation is too expensive. The sampling decision is per-entry, not per-request -- so partial call chains may appear. This is an acceptable trade-off: operators who need full chains should use `samplingRate = 1.0`.

### 4. Collection Points

Tracing hooks into the existing invocation pipeline at three points. All three already record `requestId`, `Artifact`, `MethodName`, and duration -- the trace collector reuses this data.

#### 4.1 `SliceInvoker.invokeLocal()` -- Local Invocations

When `SliceInvoker` resolves a target to the local node and invokes directly via `SliceBridge`:

```java
// In SliceInvokerImpl.invokeLocal():
aspectInterceptor.intercept(slice, method, requestId, () -> {
    var startNs = System.nanoTime();
    return invokeViaBridge(bridge, method, request)
        .onResult(result -> {
            var durationNs = System.nanoTime() - startNs;
            traceCollector.record(result.isSuccess()
                ? TraceEntry.traceEntry(requestId, selfId, sourceSlice, slice.asString(),
                                        method.name(), durationNs, true, 0)
                : TraceEntry.failedTraceEntry(requestId, selfId, sourceSlice, slice.asString(),
                                              method.name(), durationNs,
                                              result.cause().message(), true, 0));
        });
});
```

**Source slice resolution:** For HTTP entry, `sourceSlice` is `"HTTP"`. For inter-slice calls, the calling slice artifact is propagated via `InvocationContext` (requires extending `InvocationContext` with an optional source slice field -- see Section 7).

#### 4.2 `InvocationHandler.invokeSliceMethod()` -- Inbound Remote

When a remote `InvokeRequest` arrives and the handler invokes the local slice:

```java
// In InvocationHandlerImpl.invokeSliceMethod():
// startTime and duration already tracked for metrics
// After invocation completes:
traceCollector.record(success
    ? TraceEntry.traceEntry(request.requestId(), selfId, request.sender().id(),
                            request.targetSlice().asString(), request.method().name(),
                            durationNs, false, request.hops() + 1)
    : TraceEntry.failedTraceEntry(request.requestId(), selfId, request.sender().id(),
                                  request.targetSlice().asString(), request.method().name(),
                                  durationNs, cause.message(), false, request.hops() + 1));
```

#### 4.3 `SliceInvoker.sendRequestResponse()` / `sendFireAndForget()` -- Outbound Remote

When the invoker sends a request to a remote node, a trace entry is recorded on the caller side when the response arrives (or timeout fires):

```java
// In SliceInvokerImpl, when response or timeout completes:
traceCollector.record(TraceEntry.traceEntry(requestId, selfId, sourceSlice,
                                            slice.asString(), method.name(),
                                            roundTripDurationNs, false, 0));
```

**Note:** This creates two entries per remote invocation -- one on the caller (round-trip) and one on the handler (processing). This is intentional: it allows the dashboard to show both the caller's perceived latency and the handler's processing time, making network overhead visible.

### 5. Hop Count Propagation

The `hops` field tracks how many network boundaries a request has crossed. This requires adding a `hops` field to `InvokeRequest`:

```java
// In InvocationMessage.InvokeRequest -- add hops field:
public record InvokeRequest(NodeId sender,
                             String correlationId,
                             String requestId,
                             Artifact targetSlice,
                             MethodName method,
                             byte[] payload,
                             boolean expectResponse,
                             int hops) { ... }
```

Each outbound `InvokeRequest` sets `hops` to the current hop count (0 for the first hop). The receiving `InvocationHandler` records `hops + 1`.

### 6. Management API Endpoints

New `TraceRoutes` added to `ManagementServer`:

#### `GET /api/traces` -- Recent Traces

Returns recent trace entries with optional filters. Paginated.

**Query parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `limit` | int | 100 | Max entries to return (capped at 1000) |
| `slice` | string | (none) | Filter by target slice (substring match) |
| `method` | string | (none) | Filter by method name (exact match) |
| `status` | string | (none) | `success` or `failure` |
| `since` | string | (none) | ISO-8601 timestamp lower bound |

**Response:**

```json
{
  "entries": [
    {
      "requestId": "2RQstP7vY3kFrJ...",
      "timestamp": "2026-02-15T10:30:45.123Z",
      "nodeId": "node-1",
      "sourceSlice": "HTTP",
      "targetSlice": "com.example:order-service:1.0.0",
      "method": "createOrder",
      "durationMs": 12.5,
      "outcome": "SUCCESS",
      "errorMessage": null,
      "local": true,
      "hops": 0
    }
  ],
  "totalBuffered": 42300,
  "bufferCapacity": 50000
}
```

#### `GET /api/traces/{requestId}` -- Request Call Chain

Returns all entries for a single request ID, sorted by timestamp (earliest first). Shows the full waterfall of an individual request across all local invocations.

**Note:** This endpoint returns entries from the queried node only. To get a cluster-wide view, the dashboard (or Forge proxy) queries all nodes and merges results.

**Response:**

```json
{
  "requestId": "2RQstP7vY3kFrJ...",
  "entries": [
    {
      "timestamp": "2026-02-15T10:30:45.100Z",
      "nodeId": "node-1",
      "sourceSlice": "HTTP",
      "targetSlice": "com.example:order-service:1.0.0",
      "method": "createOrder",
      "durationMs": 12.5,
      "outcome": "SUCCESS",
      "local": true,
      "hops": 0
    },
    {
      "timestamp": "2026-02-15T10:30:45.102Z",
      "nodeId": "node-1",
      "sourceSlice": "com.example:order-service:1.0.0",
      "targetSlice": "com.example:inventory-service:1.0.0",
      "method": "checkStock",
      "durationMs": 3.2,
      "outcome": "SUCCESS",
      "local": false,
      "hops": 1
    }
  ]
}
```

#### `GET /api/traces/stats` -- Summary Statistics

Aggregated statistics computed from the current ring buffer contents.

**Response:**

```json
{
  "totalEntries": 42300,
  "bufferCapacity": 50000,
  "oldestEntry": "2026-02-15T10:15:00Z",
  "newestEntry": "2026-02-15T10:30:45Z",
  "successCount": 41800,
  "failureCount": 500,
  "errorRate": 0.0118,
  "avgDurationMs": 8.3,
  "bySlice": {
    "com.example:order-service:1.0.0": {
      "count": 15000,
      "avgDurationMs": 12.1,
      "errorRate": 0.005
    }
  },
  "byMethod": {
    "createOrder": {
      "count": 8000,
      "avgDurationMs": 14.2,
      "errorRate": 0.01
    }
  }
}
```

#### Route Registration

```java
package org.pragmatica.aether.api.routes;

public final class TraceRoutes implements RouteSource {
    private final Supplier<AetherNode> nodeSupplier;

    private TraceRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static TraceRoutes traceRoutes(Supplier<AetherNode> nodeSupplier) {
        return new TraceRoutes(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(
            Route.<TraceListResponse>get("/api/traces")
                 .withQuery(anInt("limit"), aString("slice"),
                            aString("method"), aString("status"),
                            aString("since"))
                 .toValue(this::queryTraces)
                 .asJson(),
            Route.<TraceChainResponse>get("/api/traces")
                 .withPath(aString())
                 .to(this::getTraceChain)
                 .asJson(),
            Route.<TraceStatsResponse>get("/api/traces/stats")
                 .toJson(this::getTraceStats)
        );
    }
}
```

Wire into `ManagementServerImpl`:

```java
routeSources.add(TraceRoutes.traceRoutes(nodeSupplier));
```

### 7. InvocationContext Extension

To track `sourceSlice` in trace entries, extend `InvocationContext` with an optional source slice propagation:

```java
// Add to InvocationContext:
private static final ScopedValue<String> SOURCE_SLICE = ScopedValue.newInstance();

public static Option<String> currentSourceSlice() {
    return SOURCE_SLICE.isBound()
           ? Option.option(SOURCE_SLICE.get())
           : Option.empty();
}

public static <T> T runWithSource(String sourceSlice, Supplier<T> supplier) {
    return ScopedValue.where(SOURCE_SLICE, sourceSlice)
                      .call(supplier::get);
}
```

**Entry points set the source:**

- `AppHttpServer.handleRequest()` sets source to `"HTTP"`.
- `InvocationHandler.onInvokeRequest()` sets source to `request.sender().id()` (the sending node).
- `SliceInvoker` inter-slice calls set source to the calling slice artifact string.

The `ContextSnapshot` record is extended to capture and restore `SOURCE_SLICE` across async boundaries.

### 8. Configuration

Tracing configuration is read from the node configuration (TOML):

```toml
[tracing]
enabled = true
buffer_size = 50000
sampling_rate = 1.0
```

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `tracing.enabled` | boolean | `true` | Master switch for tracing. When `false`, `TraceCollector.noOp()` is used. |
| `tracing.buffer_size` | int | `50000` | Ring buffer capacity. Min: 1000. Max: 500000. |
| `tracing.sampling_rate` | double | `1.0` | Fraction of invocations to trace. `1.0` = all, `0.1` = 10%. |

**Runtime reconfiguration:** Sampling rate can be changed at runtime via the Management API:

```
POST /api/traces/config
{ "samplingRate": 0.5 }
```

Buffer size changes require a restart (buffer is pre-allocated).

### 9. Structured Logging (Tier 2)

In addition to the ring buffer, completed trace entries are emitted as structured log entries. This enables long-term retention by piping logs to any external sink.

```java
// In TraceCollector, after recording to ring buffer:
if (traceLog.isInfoEnabled()) {
    traceLog.info("[trace] requestId={} source={} target={}.{} duration={}ms outcome={} local={} hops={}",
                  entry.requestId(),
                  entry.sourceSlice(),
                  entry.targetSlice(),
                  entry.method(),
                  entry.durationMs(),
                  entry.outcome(),
                  entry.local(),
                  entry.hops());
}
```

Logger name: `org.pragmatica.aether.trace`. Operators configure log level and appenders via standard SLF4J/Log4j2 configuration. When logging is disabled (`level = OFF`), zero overhead.

### 10. Dashboard Integration

New **"Requests"** tab in both the node dashboard and Forge dashboard.

#### Table View (Default)

| Timestamp | Request ID | Source -> Target.Method | Duration | Status |
|-----------|-----------|------------------------|----------|--------|
| 10:30:45 | 2RQst... | HTTP -> order-service.createOrder | 12.5ms | OK |
| 10:30:44 | 2RQsr... | order-service -> inventory.checkStock | 3.2ms | OK |
| 10:30:43 | 2RQsq... | HTTP -> user-service.getUser | 145.0ms | FAIL |

- Request ID is truncated to 8 characters with full ID on hover.
- Click a row to expand the **waterfall view** for that request ID.
- Color coding: green for success, red for failure.

#### Waterfall View (Expanded)

Shows all trace entries sharing a request ID, displayed as a timeline:

```
Request: 2RQstP7vY3kFrJ...   Total: 12.5ms

[node-1] HTTP -> order-service.createOrder       |============|     12.5ms
[node-1]   order-service -> inventory.checkStock    |====|           3.2ms
[node-2]     inventory.checkStock (handler)           |==|           2.1ms
```

#### Filters

- **Time range:** Last 1m / 5m / 15m / custom
- **Slice:** Dropdown populated from active slices
- **Method:** Text input with autocomplete
- **Status:** All / Success / Failure

#### Auto-Refresh

Uses existing WebSocket infrastructure (`/ws/dashboard`). The dashboard publishes trace summary updates (count, error rate, latest entries) alongside existing metrics pushes. No new WebSocket endpoint required -- trace data is added to the existing dashboard push payload.

### 11. Forge Proxy Routes

Forge proxies trace queries to individual cluster nodes and merges results:

```java
package org.pragmatica.aether.forge.api;

public final class TraceProxyRoutes implements RouteSource {
    // GET /api/traces -> fan out to all nodes, merge, sort by timestamp desc
    // GET /api/traces/{requestId} -> fan out to all nodes, merge entries
    // GET /api/traces/stats -> aggregate stats from all nodes
}
```

The Forge proxy follows the same pattern as existing `MetricsProxyRoutes` and `AspectProxyRoutes`.

### Contracts Summary

| Component | Module | Responsibility |
|-----------|--------|---------------|
| `TraceEntry` | aether-invoke | Immutable trace data record |
| `TraceRingBuffer` | aether-invoke | Bounded circular buffer with query support |
| `TraceCollector` | aether-invoke | Recording abstraction (full / sampling / no-op) |
| `TraceRoutes` | aether-node | Management API endpoints |
| `TraceProxyRoutes` | forge-core | Forge-to-cluster proxy |
| `InvocationContext` | aether-invoke | Extended with `SOURCE_SLICE` |
| `InvokeRequest` | aether-invoke | Extended with `hops` field |

### Examples

#### Tracing a Simple HTTP Request

```
1. HTTP request arrives at AppHttpServer on node-1
2. InvocationContext sets requestId = KSUID, sourceSlice = "HTTP"
3. SliceInvoker.invokeLocal() invokes order-service.createOrder
4. TraceCollector records: {requestId, "HTTP", "order-service", "createOrder", 12ms, SUCCESS, local=true, hops=0}
5. Entry stored in ring buffer, structured log emitted
```

#### Tracing a Cross-Node Call Chain

```
1. HTTP request arrives at node-1, requestId = "abc123"
2. order-service.createOrder invokes inventory-service.checkStock
3. SliceInvoker determines inventory-service is on node-2
4. InvokeRequest sent with hops=0
5. TraceCollector on node-1 records outbound entry when response arrives
6. InvocationHandler on node-2 receives request, invokes locally
7. TraceCollector on node-2 records: {requestId="abc123", source=node-1, target=inventory-service, hops=1}
8. Dashboard query for "abc123" hits both nodes, merges into waterfall view
```

#### Querying Failures in the Last 5 Minutes

```
GET /api/traces?status=failure&since=2026-02-15T10:25:00Z&limit=50
```

Returns up to 50 failed trace entries from the last 5 minutes, most recent first.

## Alternatives Considered

### OpenTelemetry

Rejected. OTel is designed for microservices where tracing is mandatory due to architecture constraints (inter-service visibility). Aether's transparent invocation model is fundamentally different. OTel would add heavyweight dependencies (gRPC, protobuf, OTel SDK) for a problem that can be solved more elegantly with existing infrastructure. Additionally, Aether has no users yet -- built-in tracing is likely sufficient and avoids forcing an OTel dependency on adopters.

### External Tracing Only (Log-Based)

Rejected. Logs alone lack queryability and dashboard visualization. Operators would need to set up ELK/Loki/etc. just to diagnose a single failed request. The ring buffer provides immediate, zero-setup visibility.

### Metrics Only (No Individual Traces)

Rejected. Metrics show aggregates but cannot diagnose individual request failures. When a user reports "my request failed," aggregated p99 latency is not helpful. The trace buffer provides the specific request's call chain and failure cause.

### Distributed Trace Assembly at Ingest

Considered: assembling full call chains at the recording node by forwarding trace entries via cluster messaging. Rejected in favor of fan-out-on-query (Forge proxy queries each node). Rationale: trace assembly at ingest adds cluster traffic proportional to invocation rate. Fan-out-on-query adds traffic only when someone looks at the dashboard -- a human-rate operation. At typical cluster sizes (3-7 nodes), querying all nodes is fast enough.

## Migration

No migration required. This is a purely additive feature. Existing deployments gain tracing when upgraded to the version containing this RFC.

**Wire-level change:** `InvokeRequest` gains a `hops` field. This is a breaking change to the cluster messaging protocol. Old nodes cannot decode new `InvokeRequest` messages. Rolling update from a pre-tracing version requires a coordinated upgrade (all nodes must be upgraded together). This is acceptable because tracing is introduced in a minor version bump and Aether does not yet guarantee wire-level backward compatibility.

## References

- [RFC-0001: Core Slice Contract](RFC-0001-core-slice-contract.md) -- Slice interface and invocation patterns
- [RFC-0008: Aspect Framework](RFC-0008-aspect-framework.md) -- Compile-time aspects and `DynamicAspectInterceptor`
- Existing implementation: `aether/aether-invoke/src/main/java/org/pragmatica/aether/invoke/DynamicAspectInterceptor.java`
- Existing implementation: `aether/aether-invoke/src/main/java/org/pragmatica/aether/invoke/InvocationContext.java`
- Existing implementation: `aether/aether-metrics/src/main/java/org/pragmatica/aether/metrics/invocation/InvocationMetricsCollector.java`
- Management API pattern: `aether/node/src/main/java/org/pragmatica/aether/api/routes/MetricsRoutes.java`
- Management server wiring: `aether/node/src/main/java/org/pragmatica/aether/api/ManagementServer.java`
