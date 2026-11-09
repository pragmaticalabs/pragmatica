# Slice Invocation and Routing

**Status:** Current

This document describes how slice methods are invoked, how requests are routed, and how pub/sub and scheduled tasks work.

## Invocation Architecture

```mermaid
graph TB
    subgraph Entry["Entry Points"]
        HTTP["HTTP Request"]
        Remote["Remote Invocation"]
        PubSub["Topic Publish"]
        Sched["Scheduled Task"]
    end

    subgraph Invocation["Invocation Layer"]
        SI["SliceInvoker"]
        IH["InvocationHandler"]
        DAI["DynamicAspectInterceptor"]
        IC["InvocationContext<br/>(ScopedValue)"]
    end

    subgraph Routing["Routing Decision"]
        ER["EndpointRegistry"]
        Local["Local Path<br/>(direct call)"]
        RemotePath["Remote Path<br/>(MessageRouter)"]
    end

    subgraph Target["Target Slice"]
        SS["SliceStore"]
        Slice["Slice Instance"]
    end

    HTTP --> SI
    Remote --> IH --> SI
    PubSub --> SI
    Sched --> SI

    SI --> IC
    SI --> ER
    ER --> Local
    ER --> RemotePath

    Local --> DAI --> SS --> Slice
    RemotePath -->|"Netty TCP"| IH
```

## SliceInvoker

Central component for all slice invocations. Handles local and remote dispatch transparently.

### Invocation Flow

```mermaid
sequenceDiagram
    participant Caller as Caller (Slice/HTTP)
    participant SI as SliceInvoker
    participant ER as EndpointRegistry
    participant Local as Local SliceStore
    participant MR as MessageRouter
    participant Remote as Remote Node

    Caller->>SI: invoke(artifact, method, request)
    SI->>SI: Set InvocationContext<br/>(requestId, depth, sampled)

    SI->>ER: selectEndpoint(artifact, method)
    ER-->>SI: Endpoint (nodeId, instance)

    alt Endpoint is local
        SI->>Local: Direct invocation
        Local-->>SI: Promise<Result>
    else Endpoint is remote
        SI->>MR: Send InvocationRequest to nodeId
        MR->>Remote: TCP transport
        Remote-->>MR: InvocationResponse
        MR-->>SI: Promise<Result>
    end

    SI-->>Caller: Promise<Result>
```

### Local-First Routing

SliceInvoker prefers local endpoints when available:

1. Check `CacheAffinityResolver` if registered (DHT-aware partition routing)
2. If affinity node found, prefer it via `selectEndpointByAffinity()`
3. Check for active deployment - use weighted routing if active
4. Otherwise, query EndpointRegistry with round-robin selection
5. If any endpoint is on the current node, use it (zero network hop)
6. Default invocation timeout: 20s (client-side), 15s (server-side)

### Affinity Routing

`CacheAffinityResolver` enables DHT-partition-aware routing. Registered per (artifact, method), it maps request keys to preferred nodes for cache locality.

### Retry and Failover

```mermaid
graph TB
    Invoke["invoke()"] --> Try1["Try Node A"]
    Try1 -->|"Success"| Done["Return result"]
    Try1 -->|"Node departed"| Exclude1["Exclude A"]
    Exclude1 --> Try2["Try Node B"]
    Try2 -->|"Success"| Done
    Try2 -->|"Timeout"| Exclude2["Exclude A, B"]
    Exclude2 --> Try3["Try Node C"]
    Try3 -->|"Success"| Done
    Try3 -->|"All exhausted"| Fail["Return failure"]
```

- Exponential backoff between retries (base: 100ms)
- Failed nodes excluded from selection
- On node departure, in-flight requests immediately retried on surviving nodes
- KSUID correlation IDs for distributed tracing
- Pending invocations tracked with per-node secondary index for fast cleanup
- Stale entry cleanup every 60 seconds

## InvocationContext

Per-request context carried via `ScopedValue` (no thread-local leaking):

| Field | Description |
|-------|-------------|
| `requestId` | KSUID - unique per request chain |
| `depth` | Invocation depth (prevents infinite recursion) |
| `sampled` | Whether this request is sampled for detailed metrics |
| `principal` | Security principal (if authenticated) |

### The caller's identity across an inter-slice invoke (#2157)

`principal` above is a trace string (logs, MDC, metrics). The caller's IDENTITY is the `SecurityContext` the edge validated,
and it reaches a slice only as an explicit `SecurityContext`/`Principal` method parameter:

| Hop | How the method gets the caller | Ambient `SecurityContextHolder` at the method |
|-----|--------------------------------|-----------------------------------------------|
| Entry route (HTTP) | the generated route reads it from the request (`RequestSecurity.securityOf`), which `AppHttpServer` (local and forwarded, the latter re-validated by the serving node) and Terra put on the request | bound on the entry thread only; nothing generated reads it |
| Slice-to-slice call, local or remote | the generated proxy takes the `SecurityContext` argument, wraps the payload in a `CallerRequest`; `SliceInvoker` carries it as `InvokeRequest.caller` (remote) or hands it to `SliceBridge.invokeAsCaller` (local); the receiving `DefaultSliceBridge` gives a method that declares `CallerRequest` the payload and the caller | empty on a remote hop (`InvocationHandler` binds nothing from `caller`) |

- `InvokeRequest.caller` is `Option<SecurityContext>`: the context exactly as validated (principal, roles, claims), or empty
  for an unauthenticated caller and for every call not made on a caller's behalf. The sender never reads ambient state;
  a call without an explicit `SecurityContext` argument carries nothing.
- It is identity, never authorization: no route security on the receiving node reads it, and a context whose principal is
  anonymous, or that is not a `SecurityContext`, is delivered as the anonymous context.
- A method taking only a `Principal` keeps it in the payload on an inter-slice call (the caller holds no validated context).
- `SecurityContext.toString()` withholds claim values, since the context now rides a logged message.

Pinned by `PrincipalPropagationTripwireTest` (`aether-invoke`: wire shape, value on the wire, delivery, the context-free
call, the forged caller; permanent pins for the receiver's ambient state, the local hop and plain continuations; hazard
tripwires for the executor hop), `GeneratedCallerPropagationTest` (`slice-processor-tests`: edge, one hop, two hops,
unauthenticated, forged) and `TerraHttpServerTest` (`holder_*` pins and the request-carried context surviving async steps).

## EndpointRegistry

Pure event-driven component - watches KV-Store, maintains local routing cache.

```mermaid
graph LR
    KV["KV-Store<br/>EndpointKey events"] -->|"ValuePut"| ER["EndpointRegistry"]
    KV -->|"ValueRemove"| ER

    ER --> Cache["Local Cache<br/>artifact:method → List<Endpoint>"]

    Cache --> RR["Round-Robin<br/>Selection"]
    Cache --> WR["Weighted Routing<br/>(during deployments)"]
```

### Weighted Routing for Deployments

During active deployments, `selectEndpointWithRouting()` uses `VersionRoutingKey` weights:

```
v1 weight: 3, v2 weight: 1
→ 75% of requests go to v1, 25% to v2
```

Weights are adjusted via `aether deploy promote <id>` (or the `/api/v1/deploy/promote/{id}` endpoint).

## DynamicAspectInterceptor

Runtime-configurable per-method instrumentation:

| Mode | Effect |
|------|--------|
| `LOG` | Log entry/exit with timing |
| `METRICS` | Collect invocation metrics (count, latency, success/failure) |
| `LOG_AND_METRICS` | Both |
| `NONE` | No interception |

Toggled at runtime via REST API (`/api/v1/observability/depth`, `/api/v1/observability/config`) without redeployment. Configuration persisted in KV-Store via `AspectConfigKey`.

## Generated Proxies

The annotation processor generates proxy implementations for slice interfaces:

```java
// Developer writes:
@Slice
public interface InventoryService {
    Promise<StockResult> checkStock(StockRequest request);
}

// Generated proxy:
public class InventoryServiceProxy implements InventoryService {
    private final SliceInvokerFacade invoker;

    @Override
    public Promise<StockResult> checkStock(StockRequest request) {
        return invoker.invoke(ARTIFACT, "checkStock", request, StockResult.class);
    }
}
```

The proxy is what gets materialized when another slice declares `InventoryService` as a dependency. Whether the real implementation is local or remote is transparent.

## Serialization

| Path | Format | Library |
|------|--------|---------|
| Inter-node invocation | Binary | Generated `@Codec` codecs |
| HTTP request/response | JSON | Jackson |
| KV-Store values | Binary | Generated `@Codec` codecs |

Compile-time generated `@Codec` codecs provide the binary serialization for inter-node communication, with no reflection. JSON is used only at the HTTP boundary.

## Pub/Sub

Topic-based publish/subscribe via KV-Store-backed subscription registry.

```mermaid
sequenceDiagram
    participant Pub as Publisher Slice
    participant TP as TopicPublisher
    participant TSR as TopicSubscriptionRegistry
    participant SI as SliceInvoker
    participant Sub1 as Subscriber 1
    participant Sub2 as Subscriber 2

    Note over TSR: Subscriptions stored in KV-Store<br/>TopicSubscriptionKey entries

    Pub->>TP: publish("order.created", event)
    TP->>TSR: getSubscribers("order.created")
    TSR-->>TP: [Subscriber1, Subscriber2]

    par Fan-out
        TP->>SI: invoke Subscriber1.onEvent(event)
        TP->>SI: invoke Subscriber2.onEvent(event)
    end

    SI->>Sub1: (local or remote)
    SI->>Sub2: (local or remote)
```

- Subscriptions declared via slice annotations, registered in KV-Store
- Fan-out uses SliceInvoker - same routing, retry, and failover as regular invocations
- At-least-once delivery semantics

## Scheduled Tasks

```mermaid
sequenceDiagram
    participant STM as ScheduledTaskManager
    participant KV as KV-Store
    participant SI as SliceInvoker
    participant Slice as Target Slice

    Note over STM: Runs on leader only

    STM->>KV: Read ScheduledTaskKey entries
    KV-->>STM: Task definitions<br/>(interval, cron, artifact, method)

    loop Every evaluation cycle
        STM->>STM: Check if task is due
        alt Task due
            STM->>SI: invoke(artifact, method, empty)
            SI->>Slice: Execute task
            Slice-->>SI: Result
        end
    end
```

- Supports interval-based and cron-based scheduling
- Task definitions stored in KV-Store (cluster-wide consistency)
- Execution via SliceInvoker - inherits routing and retry

## Related Documents

- [06-http-routing.md](06-http-routing.md) - HTTP request routing and forwarding
- [02-deployment.md](02-deployment.md) - Endpoint registration during deployment
- [07-observability.md](07-observability.md) - Invocation metrics and aspect configuration
