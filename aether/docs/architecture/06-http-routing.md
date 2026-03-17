# HTTP Request Routing

This document describes how HTTP requests are routed to slices, including cross-node forwarding and retry logic.

## Request Flow

```mermaid
sequenceDiagram
    participant Client
    participant AHS as AppHttpServer<br/>(Any Node)
    participant HRP as HttpRoutePublisher
    participant HRR as HttpRouteRegistry
    participant Local as Local SliceRouter
    participant MR as MessageRouter
    participant Remote as Remote Node

    Client->>AHS: HTTP Request<br/>POST /api/orders

    AHS->>HRP: findLocalRouter(POST, /api/orders)

    alt Local slice available
        HRP-->>AHS: SliceRouter
        AHS->>Local: Handle locally
        Local-->>AHS: Response
    else No local slice
        AHS->>HRR: findRoute(POST, /api/orders)
        HRR-->>AHS: RouteInfo(nodes=[B, C])
        AHS->>AHS: filterConnectedNodes([B, C])
        AHS->>AHS: selectNodeRoundRobin([B, C])
        AHS->>MR: HttpForwardRequest → Node B
        MR->>Remote: TCP transport
        Remote-->>MR: HttpForwardResponse
        MR-->>AHS: Response data
    end

    AHS-->>Client: HTTP Response
```

## Key Components

### AppHttpServer

Main HTTP server on port `:8081`. Handles both local and forwarded requests.

### HttpRoutePublisher

Manages route publication for locally deployed slices:
- **On activation**: Writes `HttpNodeRouteKey(method, path, selfNodeId)` to KV-Store
- **On deactivation**: Deletes own route key (only if last instance)
- Each node writes only its own keys - no read-modify-write races

### HttpRouteRegistry

Local cache of all HTTP routes, kept in sync via KV-Store notifications:

```mermaid
graph LR
    KV["KV-Store"] -->|"ValuePut<br/>HttpNodeRouteKey"| HRR["HttpRouteRegistry"]
    KV -->|"ValueRemove<br/>HttpNodeRouteKey"| HRR

    HRR --> Cache["Route Cache<br/>method:path → Set<NodeId>"]
```

Consumers reconstruct the node set in-memory by scanning all `HttpNodeRouteKey` entries with the same method and path prefix.

### HttpNodeRouteKey Design

```
Key:   http-routes/{METHOD}:{PATH}:{NODE_ID}
Value: {artifactCoord, sliceMethod, state, weight, registeredAt}
```

**Why flat keys (one per node) instead of Set\<NodeId\>?**

The previous design stored a single key per route with a set-valued payload. This required read-modify-write on every publish/unpublish, creating races when multiple nodes deployed the same slice concurrently. The flat design eliminates these races entirely.

## Forwarding Protocol

### HttpForwardMessage

```mermaid
graph LR
    subgraph Request["HttpForwardRequest"]
        R1["sender: NodeId"]
        R2["correlationId: String"]
        R3["requestId: String"]
        R4["requestData: byte[]<br/>(serialized HttpRequestContext)"]
    end

    subgraph Response["HttpForwardResponse"]
        S1["sender: NodeId"]
        S2["correlationId: String"]
        S3["requestId: String"]
        S4["success: boolean"]
        S5["payload: byte[]<br/>(serialized response or error)"]
    end

    Request -->|"TCP via MessageRouter"| Response
```

## Retry with Failover

```mermaid
sequenceDiagram
    participant AHS as AppHttpServer<br/>(Node A)
    participant B as Node B
    participant C as Node C
    participant D as Node D

    Note over AHS: Route nodes: [B, C, D]<br/>Retries = connectedNodes - 1

    AHS->>B: HttpForwardRequest
    Note over B: Timeout!
    B--xAHS: No response

    AHS->>C: HttpForwardRequest (retry 1)
    Note over C: Timeout!
    C--xAHS: No response

    AHS->>D: HttpForwardRequest (retry 2)
    D-->>AHS: HttpForwardResponse (success)

    AHS-->>AHS: Return response to client
```

### Retry Rules

| Rule | Description |
|------|-------------|
| Retry count | `connectedNodes.size() - 1` (try every node exactly once) |
| Node selection | Round-robin, excluding already-tried nodes |
| Timeout per attempt | `forwardTimeoutMs` (default: 5000ms) |
| All retries exhausted | 504 Gateway Timeout |

## Connectivity Filtering

Routes in KV-Store may reference disconnected nodes. Filtering happens at request time:

```mermaid
graph TB
    HRR["HttpRouteRegistry<br/>nodes=[A, B, C, D]"] --> Filter["filterConnectedNodes()"]
    CN["ClusterNetwork<br/>connectedPeers=[A, B, D]"] --> Filter
    Filter --> Result["Available nodes=[A, B, D]<br/>(C filtered out)"]
```

If no connected nodes remain: 503 Service Unavailable.

## Route Cleanup

Dead nodes cannot unpublish their routes. Cleanup happens in two places:

### On Node Removal

CDM (leader) scans and deletes all `HttpNodeRouteKey` entries where `key.nodeId() == removedNode`.

### On Leader Activation

New leader scans for stale routes during reconciliation - any `HttpNodeRouteKey` where `key.nodeId()` is not in the current topology is deleted.

## Error Responses

| Scenario | HTTP Status |
|----------|-------------|
| No route found | 404 |
| No connected nodes for route | 503 |
| All retry attempts exhausted | 504 |
| Serialization error | 500 |
| Remote processing error | Forwarded status |

## Related Documents

- [03-invocation.md](03-invocation.md) - Slice invocation (separate from HTTP routing)
- [02-deployment.md](02-deployment.md) - Route self-registration during deployment
- [04-networking.md](04-networking.md) - MessageRouter transport
