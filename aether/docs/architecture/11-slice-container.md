# Slice Container

This document describes ClassLoader isolation, dependency materialization, and slice lifecycle hooks.

## ClassLoader Architecture

```mermaid
graph TB
    subgraph JVM["JVM ClassLoader Hierarchy"]
        System["System ClassLoader"]
        Shared["SharedLibraryClassLoader<br/>Pragmatica Lite core,<br/>common dependencies"]

        SCL1["SliceClassLoader A<br/>(child-first)"]
        SCL2["SliceClassLoader B<br/>(child-first)"]
        SCL3["SliceClassLoader C<br/>(child-first)"]
    end

    System --> Shared
    Shared --> SCL1
    Shared --> SCL2
    Shared --> SCL3

    subgraph Slices["Deployed Slices"]
        S1["Slice A<br/>Jackson 2.17"]
        S2["Slice B<br/>Jackson 2.15"]
        S3["Slice C<br/>No Jackson"]
    end

    SCL1 --> S1
    SCL2 --> S2
    SCL3 --> S3
```

### Child-First Delegation

Each `SliceClassLoader` uses child-first (parent-last) delegation:

1. Look in slice's own classpath first
2. If not found, delegate to `SharedLibraryClassLoader`
3. If not found, delegate to system classloader

This means two slices can use different versions of the same library without conflict.

### SharedLibraryClassLoader

Loaded once, shared across all slices:
- Pragmatica Lite core (`Result`, `Option`, `Promise`)
- Slice API interfaces
- Common serialization libraries

## Slice Loading

```mermaid
sequenceDiagram
    participant NDM as NodeDeploymentManager
    participant SS as SliceStore
    participant DHT as DHTNode
    participant SCL as SliceClassLoader

    NDM->>SS: Load artifact (coordinates)

    alt Development mode
        SS->>SS: Resolve from local Maven repo
    else Production mode
        SS->>DHT: Fetch artifact chunks
        DHT-->>SS: Artifact JAR
    end

    SS->>SCL: Create SliceClassLoader
    SCL->>SCL: Load classes from JAR

    SS->>SS: Discover slice interface<br/>(find @Slice annotation)
    SS->>SS: Create factory method handle

    SS-->>NDM: Slice loaded (LOADED state)
```

## Dependency Materialization

When a slice declares dependencies (other slices), they are materialized during activation:

```mermaid
sequenceDiagram
    participant SS as SliceStore
    participant Factory as Slice Factory
    participant Proxy as Proxy Generator
    participant ER as EndpointRegistry

    SS->>Factory: createSlice(aspect, invoker)

    loop For each dependency
        Factory->>Proxy: Create proxy for dependency
        Proxy->>Proxy: Generate proxy implementing<br/>dependency interface
        Proxy->>ER: Verify endpoint exists
        alt Endpoint available
            ER-->>Proxy: Confirmed
        else Endpoint unavailable
            ER-->>Proxy: Not found
            Proxy-->>Factory: Activation fails
            Note over Factory: Fail fast - don't wait<br/>for first request
        end
    end

    Factory->>Factory: Call developer's factory method<br/>with materialized dependencies
    Factory-->>SS: Slice instance ready
```

### Factory Method Pattern

```java
@Slice
public interface OrderService {
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);

    // Factory method - dependencies declared as parameters
    static OrderService orderService(InventoryService inventory, PricingEngine pricing) {
        return request -> inventory.check(request.items())
                                   .flatMap(available -> pricing.calculate(available))
                                   .map(priced -> OrderResult.placed(priced));
    }
}
```

- `InventoryService` and `PricingEngine` are materialized as proxies
- Proxies route calls through `SliceInvoker` (local or remote)
- The developer's code is identical regardless of where dependencies run

## Lifecycle Hooks

### start()

```mermaid
sequenceDiagram
    participant NDM as NodeDeploymentManager
    participant SS as SliceStore
    participant Slice

    Note over NDM: ACTIVATING state

    NDM->>SS: Activate slice
    SS->>SS: Materialize all dependency handles
    SS->>SS: Verify all endpoints available

    SS->>Slice: start() with startStopTimeout
    Note over Slice: Initialize resources,<br/>warm caches,<br/>establish connections

    alt Success within timeout
        Slice-->>SS: Ready
        SS-->>NDM: Register endpoints + routes
        Note over NDM: → ACTIVE
    else Timeout or exception
        SS-->>NDM: Activation failed
        Note over NDM: → FAILED
    end
```

### stop()

```mermaid
sequenceDiagram
    participant NDM as NodeDeploymentManager
    participant SS as SliceStore
    participant Slice

    Note over NDM: DEACTIVATING state

    NDM->>NDM: Remove endpoints from KV-Store
    NDM->>NDM: Remove routes (if last instance)

    NDM->>SS: Deactivate slice
    SS->>Slice: stop() with startStopTimeout
    Note over Slice: Drain connections,<br/>flush buffers,<br/>release resources

    alt Success within timeout
        Slice-->>SS: Cleaned up
        Note over NDM: → LOADED
    else Timeout or exception
        Note over NDM: → FAILED (logged WARNING)
    end
```

### Timeout Configuration

```java
public record SliceActionConfig(
    Duration startStopTimeout  // Default: 5 seconds
) {}
```

## Slice Registration

When a slice reaches ACTIVE state, it registers:

| Registration | Key Type | Description |
|-------------|----------|-------------|
| Endpoints | `EndpointKey` | Each method of the slice interface |
| HTTP routes | `HttpNodeRouteKey` | Routes declared by `routes()` method |
| Subscriptions | `TopicSubscriptionKey` | Topics declared by annotations |
| Scheduled tasks | `ScheduledTaskKey` | Periodic tasks declared by annotations |

All registrations are written to KV-Store in a single batch for atomicity.

## Serialization

| Context | Format | Library |
|---------|--------|---------|
| Inter-node invocation | Binary | Fury |
| HTTP request/response | JSON | Jackson |
| KV-Store values | Binary | Fury |

Request/response types must be:
- Records or immutable classes
- Serializable (no transient dependencies like DB connections)
- Compatible across slice versions (for rolling updates)

## Resource Provisioning (SPI)

Slices can access external resources via the `ResourceProvider` SPI:

| Resource | Description |
|----------|-------------|
| Database | Connection pool to PostgreSQL, etc. |
| HTTP client | Pre-configured HTTP client |
| Interceptors | Request/response interceptors |
| Configuration | Runtime configuration values |

Resources are provisioned during slice activation and cleaned up during deactivation.

## Related Documents

- [02-deployment.md](02-deployment.md) - Slice lifecycle state machine
- [03-invocation.md](03-invocation.md) - Generated proxies and invocation
- [09-storage.md](09-storage.md) - Artifact storage in DHT
