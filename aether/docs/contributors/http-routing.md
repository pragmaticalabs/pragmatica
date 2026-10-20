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

### HTTP/3 listener and ALPN

`AppHttpServer` and `ManagementServer` build their QUIC server contexts with `Http3.supportedApplicationProtocols()`.
That is the `h3` ALPN token, and QUIC refuses a handshake without one. The same holds for every path that builds a
context: self-signed, a configured certificate, and certificate rotation, including the `TlsRotation` pre-check. A new
caller of `HttpServer.http3Server` must pass a context built the same way (#2310). `AppHttpServerH3AlpnTest` and
`ManagementServerH3AlpnTest` pin this with a standard HTTP/3 client.
The HTTP/3 response writer also lowercases header names (RFC 9114 §4.2), because Netty refuses a HEADERS frame with an
uppercase one, such as the `X-Request-Id` the app server sets.

### Request trailers

A client can send fields after the body: chunked-encoding trailers on HTTP/1.1, or a trailing HEADERS frame on
HTTP/3. Both transports deliver them as `HttpRequest.trailers()`, a `Headers` value (#2310).

- **Kept apart from the headers.** Trailers are never merged into `headers()`, as RFC 9110 §6.5.2 requires.
  Security validation, routing and content-type handling read the header section only.
- **Names are folded with `Locale.ROOT`, and case variants merge.** `x-a` and `X-A` are one field, and their values
  are kept in arrival order. The JVM default locale never affects matching: under `tr-TR`, `AUTHORIZATION` is still
  forbidden.
- **Empty when the client sent none.** The accessor returns `Headers.empty()`, never `null`. A request built
  without a transport, such as a test double, also has no trailers: the interface's default answers empty.
- **Forbidden fields are discarded; the request is still served.** `Headers.FORBIDDEN_TRAILER_FIELDS` lists the
  names whose meaning must be settled before the body is read (RFC 9110 §6.5.1). They cover framing
  (`content-length`, `transfer-encoding`, `trailer`, `te`, the hop-by-hop fields), routing (`host`), authentication
  (`authorization`, `proxy-authorization`, `cookie`), request modifiers (`expect`, `max-forwards`, `cache-control`,
  `pragma`, `range`, the `if-*` conditionals) and content format (`content-type`, `content-encoding`,
  `content-language`, `content-location`, `content-range`). The request is not refused, because the RFC treats
  discarding trailers as the normal outcome, and a value the server would never act on is no reason to fail a
  request whose body has already arrived. On HTTP/3, pseudo-header fields in trailers are refused by Netty's
  stream validation.
- **Size.**
  - **HTTP/1.1:** the decoder's 8192-byte field limit covers the header section and the trailer section
    together. A request over it is answered `431 Request Header Fields Too Large`, the connection is closed, and
    the handler is never called. Any other undecodable request gets `400`.
  - **HTTP/3:** `SETTINGS_MAX_FIELD_SECTION_SIZE` (8192 bytes) applies to each field section separately, as
    RFC 9114 defines it. A trailer section over it is a connection error, so the request is never dispatched.
- **Forwarding.** `HttpRequestContext` carries the trailers in its `trailers` component, so a request forwarded
  to another node reaches its slice with them. The same holds for a management request forwarded to its owner,
  which is rebuilt as `ForwardedRequestContext`. Adding this component changed the record's wire shape. A node
  running a build without it cannot decode a forward from a node running a build with it, and the reverse also
  fails. Upgrade all nodes of a cluster before relying on mixed-version HTTP forwarding.

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

**Configuration (`TimeoutsConfig.ForwardingTimeouts`):**
- `appTimeout` - Timeout per attempt (default: 5s)

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
public record AppHttpConfig(boolean enabled, int port, Map<String, ApiKeyEntry> apiKeys, ...) { }
public record TimeoutsConfig.ForwardingTimeouts(TimeSpan retryDelay,
                                                int maxRetries,
                                                TimeSpan appTimeout,        // default 5s
                                                TimeSpan managementTimeout) // default 5s
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
