# HTTP Request Routing

This document describes the HTTP request routing architecture in Pragmatica Aether, including how requests are forwarded between nodes when slices are deployed across the cluster.

## Overview

When an HTTP request arrives at a node, it may need to be handled locally (if the slice is deployed on this node) or forwarded to another node (if the slice is deployed elsewhere). The routing system handles this transparently.

```
┌─────────────┐     HTTP Request      ┌─────────────┐
│   Client    │ ──────────────────────▶│   Node A    │
└─────────────┘                        └──────┬──────┘
                                              │
                                              ▼
                                       ┌──────────────┐
                                       │ Local slice? │
                                       └──────┬───────┘
                                              │
                            ┌─────────────────┴─────────────────┐
                            ▼                                   ▼
                     ┌──────────────┐                    ┌──────────────┐
                     │  Yes: Handle │                    │  No: Forward │
                     │   Locally    │                    │  to Node B   │
                     └──────────────┘                    └──────────────┘
```

## Key Components

### HttpNodeRouteKey / HttpNodeRouteValue

Each node writes its own route entry. The key includes the node ID, eliminating read-modify-write races.

```java
record HttpNodeRouteKey(String httpMethod, String pathPrefix, NodeId nodeId) implements AetherKey {}

record HttpNodeRouteValue(
    String artifactCoord,
    String sliceMethod,
    SliceState state,
    int weight,
    Instant registeredAt
) implements AetherValue {}
```

**Key format:** `http-routes/{METHOD}:{PATH}:{NODE_ID}`
**Example:** `http-routes/GET:/api/v1/users/:node-3`

Consumers (e.g., `HttpRouteRegistry`) reconstruct the set of nodes per route in-memory by scanning all keys with the same method and path prefix.

### HttpRouteRegistry

Local cache of HTTP routes learned from KVStore. Watches for `ValuePut`/`ValueRemove` notifications to stay synchronized.

```java
record RouteInfo(String httpMethod, String pathPrefix, Set<NodeId> nodes) {
    // Node sets are reconstructed in-memory from individual HttpNodeRouteKey entries
}
```

### HttpRoutePublisher

Manages publishing/unpublishing of routes for locally deployed slices.

**On slice deploy:**
1. Write an `HttpNodeRouteValue` keyed by `HttpNodeRouteKey(method, path, selfNodeId)`
2. No read-modify-write — each node writes only its own key

**On slice undeploy:**
1. Delete the `HttpNodeRouteKey(method, path, selfNodeId)` entry

```java
interface HttpRoutePublisher {
    void publishRoutes(SliceRouter router, String artifact);
    void unpublishRoutes(String artifact);
    Set<HttpNodeRouteKey> allLocalRoutes();
    Option<SliceRouter> findLocalRouter(String method, String path);
}
```

### AppHttpServer

Main HTTP server that routes requests to local slices or forwards to remote nodes.

**Request handling flow:**
1. Receive HTTP request
2. Check if route exists locally → handle with local `SliceRouter`
3. Otherwise, look up route in `HttpRouteRegistry`
4. Filter to connected nodes only
5. Forward request to selected node with retry support

### HttpForwardMessage

Messages for inter-node HTTP forwarding:

```java
sealed interface HttpForwardMessage extends ProtocolMessage {
    record HttpForwardRequest(
        NodeId sender,
        String correlationId,
        String requestId,
        byte[] requestData    // Serialized HttpRequestContext
    ) implements HttpForwardMessage {}

    record HttpForwardResponse(
        NodeId sender,
        String correlationId,
        String requestId,
        boolean success,
        byte[] payload        // Serialized HttpResponseData or error
    ) implements HttpForwardMessage {}
}
```

## Request Flow

### Local Request

```
HTTP Request → AppHttpServer → findLocalRouter() → SliceRouter → Response
```

### Remote Request (Forward)

```
HTTP Request
    │
    ▼
AppHttpServer (Node A)
    │
    ├─ filterConnectedNodes(route.nodes())
    ├─ selectNodeRoundRobin(candidates)
    │
    ▼
HttpForwardRequest ──────────────────────▶ AppHttpServer (Node B)
                                                 │
                                                 ├─ findLocalRouter()
                                                 ├─ SliceRouter.handle()
                                                 │
HttpForwardResponse ◀─────────────────────────────┘
    │
    ▼
Response to Client
```

## Retry Logic

When a forward request fails (timeout or error), the system retries with a different node:

```java
forwardRequestWithRetry(request, response, availableNodes, triedNodes, routeKey, requestId, retriesRemaining)
```

**Configuration (`AppHttpConfig`):**
- `forwardTimeoutMs` - Timeout per attempt (default: 5000ms)

**Retry behavior:**
1. Retry count is derived automatically from connected nodes: `connectedNodes.size() - 1` (try every available node exactly once)
2. On timeout/failure, select different node from `availableNodes - triedNodes`
3. If no untried nodes remain, return 504 Gateway Timeout
4. Track tried nodes to avoid repeating failures

## Node Connectivity Filtering

Routes in KVStore may reference nodes that have disconnected. The system filters to connected nodes only:

```java
private List<NodeId> filterConnectedNodes(Set<NodeId> nodes) {
    var connected = clusterNetwork.unwrap().connectedPeers();
    return nodes.stream()
                .filter(connected::contains)
                .toList();
}
```

If no connected nodes are available, returns 503 Service Unavailable.

## Route Cleanup

Dead nodes cannot unpublish their routes. The system handles this in two ways:

### 1. On Node Removal (ClusterDeploymentManager)

When a node is removed from the cluster:

```java
private List<KVCommand<AetherKey>> cleanupHttpRoutesForNode(NodeId removedNode) {
    // Scan all HttpNodeRouteKey entries
    // Delete entries where key.nodeId() == removedNode
}
```

### 2. On Leader Activation

New leader scans for stale routes during reconciliation:

```java
private void cleanupStaleHttpRoutes() {
    var currentNodes = new HashSet<>(activeNodes.get());
    // For each HttpNodeRouteKey:
    //   If key.nodeId() is NOT in current topology, delete the entry
}
```

## Error Handling

| Scenario | HTTP Status | Description |
|----------|-------------|-------------|
| No route found | 404 | Route not in registry |
| No connected nodes | 503 | All nodes for route are disconnected |
| Forward timeout | 504 | All retry attempts exhausted |
| Serialization error | 500 | Failed to serialize request/response |
| Remote processing error | Varies | Forwarded from remote node |

## Configuration

```java
public record AppHttpConfig(
    boolean enabled,
    int port,
    Map<String, ApiKeyEntry> apiKeys,
    long forwardTimeoutMs    // Default: 5000ms
) { }
```

Retry count is derived automatically from the number of connected nodes for the route (`connectedNodes.size() - 1`), ensuring every available node is tried exactly once.

## Sequence Diagram: Remote Forward with Retry

```
Client          Node A              Node B (dead)      Node C
  │                │                      │               │
  │─── Request ───▶│                      │               │
  │                │                      │               │
  │                │── ForwardRequest ───▶│               │
  │                │       (timeout)      X               │
  │                │                                      │
  │                │──────── ForwardRequest ─────────────▶│
  │                │                                      │
  │                │◀─────── ForwardResponse ─────────────│
  │                │                                      │
  │◀── Response ───│                                      │
  │                │                                      │
```

## Key Files

| File | Purpose |
|------|---------|
| `slice/.../kvstore/AetherValue.java` | `HttpNodeRouteKey`/`HttpNodeRouteValue` records |
| `node/.../http/HttpRouteRegistry.java` | Route cache with KV notifications |
| `node/.../http/HttpRoutePublisher.java` | Route publish/unpublish logic |
| `node/.../http/AppHttpServer.java` | Request handling and forwarding |
| `node/.../http/forward/HttpForwardMessage.java` | Forward message types |
| `node/.../deployment/cluster/ClusterDeploymentManager.java` | Route cleanup on node removal |
| `aether-config/.../config/AppHttpConfig.java` | Forwarding configuration |

## Design Decisions

### Why not use SliceInvoker for HTTP?

The previous design used `SliceInvoker` (designed for inter-slice RPC) for HTTP forwarding. This was problematic because:

1. **Different semantics** - HTTP requests have different timeout/retry needs than slice invocations
2. **Unreliable routing** - SliceInvoker wasn't designed for HTTP-style routing
3. **~40% failure rate** - Messages often didn't reach destinations

The new design uses dedicated `HttpForwardRequest`/`HttpForwardResponse` messages with HTTP-specific handling.

### Why flat keys (one per node) instead of Set<NodeId>?

The previous design stored `HttpRouteValue(Set<NodeId>)` — a single key per route with a set-valued payload. This required read-modify-write on every publish/unpublish, creating races when multiple nodes deployed the same slice concurrently.

The flat `HttpNodeRouteKey(method, path, nodeId)` design eliminates these races: each node writes only its own key, with no contention. The value now carries `artifactCoord`, `sliceMethod`, `state`, and `weight`, enabling richer routing decisions. Consumers reconstruct the node set in-memory by scanning matching keys.

### Why filter connected nodes at request time?

KVStore may have stale entries (node died before unpublishing). Filtering at request time using `connectedPeers()` ensures we only forward to reachable nodes, with cleanup happening asynchronously via `ClusterDeploymentManager`.
