---
RFC: 0010
Title: Unified Invocation Observability
Status: Draft
Supersedes: RFC-0009
Author: Aether Team
Created: 2026-02-20
Updated: 2026-02-20
Affects: [aether-invoke, aether-node, forge-core, slice-processor, resource-api]
---

## Summary

Single invocation tree structure that captures timing, input/output, and call depth at every dependency boundary. Three views from one data source:

| View | Data | Consumer |
|------|------|----------|
| Request Tracing | caller→callee, duration, status, hops | Dashboard waterfall, Management API |
| Depth Logging | input/output at each node, depth-filtered | SLF4J projection, Dashboard tree view |
| Metrics | timing aggregates per method | Prometheus, dashboard charts |

Traditional logging assigns subjective severity (DEBUG/INFO/WARN/ERROR) at code-writing time. This fits poorly with Aether's assembly-driven architecture where the runtime owns the call graph. A better model: **structural depth** — an objective property of the call tree that Aether can instrument automatically, with zero business logic involvement.

Since Aether already controls all dependency boundaries (slice entry, cross-slice calls, resource calls), it can automatically log input/output at each boundary with a depth counter. Adjusting the max depth dynamically controls verbosity — a runtime "microscope" on any part of the call tree.

This subsumes [RFC-0009](RFC-0009-request-tracing.md) (Request Tracing) — both are call-tree-shaped. Unifying them yields one data structure serving tracing (timing/flow), depth-logging (input/output), and metrics.

## Core Data Model: InvocationNode

```java
record InvocationNode(
    String requestId,          // KSUID, shared across full call chain
    int depth,                 // 1 = slice entry, increments at each boundary
    Instant timestamp,         // wall clock at completion
    String nodeId,             // which cluster node recorded this
    String caller,             // source: "HTTP", slice artifact, or resource name
    String callee,             // target: slice.method or resource.operation
    long durationNs,           // nanosecond precision
    Outcome outcome,           // SUCCESS or FAILURE
    Option<String> errorMessage,
    Option<String> input,      // serialized args (redacted)
    Option<String> output,     // serialized result (redacted)
    boolean local,             // same-node or remote
    int hops                   // network boundaries crossed
) {}
```

Compared to RFC-0009's `TraceEntry`, `InvocationNode` adds:
- `depth` — structural position in the call tree
- `caller`/`callee` — generalized from `sourceSlice`/`targetSlice` to cover resource calls
- `input`/`output` — captured payload for depth-logging view

## Depth Boundaries

Depth increments ONLY at well-defined boundaries Aether controls:

| Boundary | Instrumentation Point | Already Exists? |
|----------|----------------------|-----------------|
| Slice entry (HTTP→slice) | `AppHttpServer` / `InvocationHandler` | Yes — requestId set here |
| Cross-slice call | `SliceInvoker.invokeLocal()` / `sendRequestResponse()` | Yes — timing, metrics here |
| Resource call | Resource proxy (generated factory wraps resources) | Partial — interceptors exist |

Internal composition (flatMap chains within a method) stays at the same depth. This is the key insight: depth is a structural property of the dependency graph, not a code nesting level.

## Depth Tracking Mechanism

New `ScopedValue<Integer> INVOCATION_DEPTH` in `InvocationContext`:

```java
// In InvocationContext:
private static final ScopedValue<Integer> INVOCATION_DEPTH = ScopedValue.newInstance();

public static int currentDepth() {
    return INVOCATION_DEPTH.isBound() ? INVOCATION_DEPTH.get() : 0;
}
```

Propagation:
- HTTP entry: depth = 1
- Each cross-slice call: depth + 1
- Each resource call: depth + 1
- Propagated via existing `ContextSnapshot` across async boundaries

## SLF4J Bridge (Projection)

Configurable function `(depth, isError) → SLF4J level`:

| Condition | Default Projection |
|-----------|-------------------|
| Error (any depth) | ERROR |
| Depth 1 | INFO |
| Depth 2-3 | DEBUG |
| Depth 4+ | TRACE |

Emitted on each node completion:

```java
slf4jLogger.atLevel(projected)
           .addKeyValue("depth", node.depth)
           .addKeyValue("rid", node.requestId)
           .log("{} → {} | in={} out={}", caller, callee, summarize(input), summarize(output));
```

Traditional consumers see normal log lines. JSON appender gets full structured data. This replaces RFC-0009's Tier 2 (structured logging) with a more nuanced approach: instead of all traces at INFO, the depth projection provides meaningful severity gradation.

## Focused Subtree Control

Per-method depth overrides in KV store (same pattern as `DynamicAspectRegistry`):

```
/config/observability/depth/default = 1
/config/observability/depth/OrderService.placeOrder = 3
/config/observability/depth/PaymentService.charge = 2
```

Management API: `GET/PUT /api/observability/depth`

This enables a runtime "microscope" — an operator investigating `PaymentService.charge` increases its depth to 3, observes the full subtree (resource calls, downstream slices), then resets to 1. No restart, no config file change, no redeployment.

## Ring Buffer Storage

Reuse existing `RingBuffer<T>` from `integrations/utility/.../RingBuffer.java` — generic, thread-safe, O(1) add, `filter(Predicate)` for queries. Already used in metrics sliding windows (`MinuteAggregator`, `DerivedMetricsCalculator`).

Store `InvocationNode` directly — no new buffer implementation needed. RFC-0009 proposed a custom `TraceRingBuffer` — unnecessary given the existing generic one.

Default capacity: 50K entries (~15MB).

## Management API

Adapted from RFC-0009 for the unified model:

### Trace Endpoints

- `GET /api/traces` — recent entries with filters

  | Parameter | Type | Default | Description |
  |-----------|------|---------|-------------|
  | `limit` | int | 100 | Max entries (capped at 1000) |
  | `slice` | string | — | Filter by target (substring match) |
  | `method` | string | — | Filter by method (exact match) |
  | `status` | string | — | `success` or `failure` |
  | `since` | string | — | ISO-8601 timestamp lower bound |
  | `minDepth` | int | — | Minimum depth filter |
  | `maxDepth` | int | — | Maximum depth filter |

- `GET /api/traces/{requestId}` — full call tree for one request, sorted by depth then timestamp
- `GET /api/traces/stats` — aggregated statistics (counts, error rates, timing by slice/method)

### Depth Configuration Endpoints

- `GET /api/observability/depth` — current depth configuration (default + per-method overrides)
- `PUT /api/observability/depth` — update depth thresholds

### Runtime Configuration

- `POST /api/traces/config` — runtime sampling rate adjustment

  ```json
  { "samplingRate": 0.5 }
  ```

## Annotation-Based Redaction

> **⚠️ SEPARATE DESIGN DECISION** — needs its own detailed design.

Core mechanism:

```java
@Sensitive  // on record fields → serializer replaces with "***"
```

- Marker annotation on types or fields
- Serialization layer checks for annotation before including in input/output capture
- Compile-time visible, zero runtime config

## Dashboard Integration

> **⚠️ SEPARATE DESIGN DECISION** — UI design tracked in development priorities, not in this RFC.

RFC defines the data model and API; dashboard consumes it. The unified model enables richer visualization than RFC-0009's table+waterfall: a tree view showing depth, with expandable nodes showing input/output at each level.

## Items Marked for Separate Design Decisions

1. **Redaction Strategy** — `@Sensitive` annotation semantics, inheritance, nested field handling, integration with Jackson serialization
2. **Dashboard UI** — tree view visualization, waterfall rendering, filter UX
3. **Input/Output Serialization** — size limits, format (JSON summary vs full), performance impact at high throughput
4. **Sampling Strategy** — per-entry vs per-request sampling, adaptive sampling under load
5. **Cross-Node Tree Assembly** — fan-out-on-query (from RFC-0009) vs ingest-time assembly

## Reused Infrastructure

| Component | Location | Reuse |
|-----------|----------|-------|
| `InvocationContext` | `aether/aether-invoke/.../InvocationContext.java` | Add `INVOCATION_DEPTH` ScopedValue |
| `DynamicAspectInterceptor` | `aether/aether-invoke/.../DynamicAspectInterceptor.java` | Primary instrumentation point for slice calls |
| `InvocationTimingContext` | `aether/aether-invoke/.../InvocationTimingContext.java` | Stage timing data feeds into `InvocationNode.durationNs` |
| `InvocationMetricsCollector` | `aether/aether-metrics/.../InvocationMetricsCollector.java` | Metrics view consumes same boundary events |
| `SliceInvoker` | `aether/aether-invoke/.../SliceInvoker.java` | Cross-slice boundary — depth increment point |
| `FactoryClassGenerator` | `jbct/slice-processor/.../FactoryClassGenerator.java` | Resource proxy generation — depth increment for resource calls |
| `DynamicAspectRegistry` | `aether/aether-invoke/.../DynamicAspectRegistry.java` | Pattern for depth config storage in KV store |
| `ContextSnapshot` | `aether/aether-invoke/.../InvocationContext.java` | Async boundary propagation of depth |
| `RingBuffer<T>` | `integrations/utility/.../RingBuffer.java` | Generic thread-safe ring buffer — used as-is for `InvocationNode` storage |

## Wire Protocol Change

`InvokeRequest` gains `hops` and `depth` fields. Same migration note as RFC-0009: breaking wire change, coordinated upgrade required. Acceptable pre-1.0.

## Alternatives Considered

### Separate Tracing + Logging Systems

RFC-0009 proposed tracing as a standalone system. Adding depth-based logging as a second system would duplicate instrumentation points, data structures, and management APIs. The unified model eliminates this redundancy.

### OpenTelemetry

Rejected (same rationale as RFC-0009). OTel is designed for microservices where tracing is mandatory due to architecture constraints. Aether's transparent invocation model is fundamentally different. OTel would add heavyweight dependencies (gRPC, protobuf, OTel SDK) for a problem solvable with existing infrastructure.

### Traditional Severity-Based Logging

Rejected. Severity is a subjective judgment made at code-writing time. In Aether's assembly-driven model, the runtime (not the developer) knows the call graph structure. Depth is an objective, automatically derivable property that provides better signal than hand-assigned severity levels.

## References

- [RFC-0001: Core Slice Contract](RFC-0001-core-slice-contract.md) — Slice interface and invocation patterns
- [RFC-0008: Aspect Framework](RFC-0008-aspect-framework.md) — Compile-time aspects and `DynamicAspectInterceptor`
- [RFC-0009: Request Tracing](RFC-0009-request-tracing.md) — Superseded predecessor, ring buffer + structured logging approach
- Existing: `aether/aether-invoke/src/main/java/org/pragmatica/aether/invoke/DynamicAspectInterceptor.java`
- Existing: `aether/aether-invoke/src/main/java/org/pragmatica/aether/invoke/InvocationContext.java`
- Existing: `aether/aether-metrics/src/main/java/org/pragmatica/aether/metrics/invocation/InvocationMetricsCollector.java`
- Existing: `integrations/utility/src/main/java/org/pragmatica/utility/RingBuffer.java`
- Management API pattern: `aether/node/src/main/java/org/pragmatica/aether/api/routes/MetricsRoutes.java`
