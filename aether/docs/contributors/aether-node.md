# Aether Node

**AetherNode** is the core runtime component of the Aether distributed system. Each node participates in
cluster consensus, manages slice lifecycle, and processes requests.

## Overview

An Aether cluster consists of multiple AetherNodes that:

- **Form consensus**: Use the Rabia protocol for cluster-wide state agreement
- **Manage slices**: Load, activate, and deactivate deployable business logic units
- **Handle requests**: Route HTTP requests and inter-slice invocations
- **Collect metrics**: Monitor performance and resource usage
- **Auto-scale**: Run decision controllers for autonomous scaling

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         AetherNode                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐         │
│  │  RabiaNode   │   │   KVStore    │   │  SliceStore  │         │
│  │  (Consensus) │◄──│  (State)     │◄──│  (Lifecycle) │         │
│  └──────────────┘   └──────────────┘   └──────────────┘         │
│                                                                  │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐         │
│  │ControlLoop   │   │MetricsCollect│   │ HttpRouter   │         │
│  │ (Scaling)    │   │  (Monitor)   │   │  (Routing)   │         │
│  └──────────────┘   └──────────────┘   └──────────────┘         │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │               NodeDeploymentManager                       │   │
│  │               ClusterDeploymentManager (leader only)      │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
├─────────────────────────────────────────────────────────────────┤
│                    ManagementServer (REST API)                   │
│  GET /status  │  GET /nodes  │  GET /slices  │  GET /metrics    │
└─────────────────────────────────────────────────────────────────┘
```

## Key Components

| Component | Description |
|-----------|-------------|
| `AetherNode` | Main assembly, wires all components together |
| `AetherNodeConfig` | Configuration record for node settings |
| `RabiaNode` | Rabia consensus protocol implementation |
| `KVStore` | Distributed key-value store for cluster state |
| `SliceStore` | Slice lifecycle management (load/activate/deactivate) |
| `NodeDeploymentManager` | Watches KV-store, executes local slice operations |
| `ClusterDeploymentManager` | Leader-only orchestration (allocation, reconciliation) |
| `MetricsCollector` | JVM and call metrics collection |
| `MetricsScheduler` | Cluster-wide metrics aggregation |
| `ControlLoop` | Runs decision controllers for scaling |
| `HttpRouter` | HTTP request routing to slices |
| `SliceInvoker` | Inter-slice invocation with retry |
| `ManagementServer` | REST API for cluster management |
| `MavenProtocolHandler` | Maven-compatible artifact repository |
| `ArtifactStore` | DHT-backed artifact storage |

## Configuration

### AetherNodeConfig

```java
import static org.pragmatica.aether.node.AetherNodeConfig.*;

// Basic configuration
var config = aetherNodeConfig(
    NodeId.nodeId("node-1"),      // Node identifier
    5050,                          // Cluster port
    List.of(                       // Core nodes (initial topology)
        nodeInfo(nodeId("node-1"), nodeAddress("localhost", 5050)),
        nodeInfo(nodeId("node-2"), nodeAddress("localhost", 5051)),
        nodeInfo(nodeId("node-3"), nodeAddress("localhost", 5052))
    )
);

// With management API
var config = aetherNodeConfig(
    NodeId.nodeId("node-1"),
    5050,
    coreNodes,
    sliceActionConfig,
    8080  // Management port
);

// With HTTP router
var config = aetherNodeConfig(
    NodeId.nodeId("node-1"),
    5050,
    coreNodes,
    sliceActionConfig,
    8080,
    Option.option(RouterConfig.routerConfig(9000))  // HTTP router port
);
```

### Configuration Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `topology` | `TopologyConfig` | Cluster membership configuration |
| `protocol` | `ProtocolConfig` | Rabia consensus protocol settings |
| `sliceAction` | `SliceActionConfig` | Slice lifecycle timeouts |
| `managementPort` | `int` | REST API port (0 to disable) |
| `httpRouter` | `Option<RouterConfig>` | HTTP routing configuration |
| `artifactRepo` | `DHTConfig` | Artifact repository replication settings |

## Starting a Node

```java
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNodeConfig;

// Create configuration
var config = AetherNodeConfig.aetherNodeConfig(
    NodeId.nodeId("node-1"),
    5050,
    coreNodes
);

// Create and start node
var node = AetherNode.aetherNode(config);
node.start()
    .await(TimeSpan.timeSpan(30).seconds())
    .onFailure(cause -> log.error("Failed to start: {}", cause.message()))
    .onSuccess(_ -> log.info("Node started"));
```

## Management API

The Management Server provides REST endpoints for cluster administration.

### Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/status` | GET | Full cluster status including node ID, quorum state |
| `/nodes` | GET | List of active nodes in the cluster |
| `/slices` | GET | List of deployed slices with state |
| `/metrics` | GET | Current cluster metrics |
| `/blueprint` | POST | Deploy a new blueprint |
| `/blueprint/{artifact}` | DELETE | Remove a deployed blueprint |
| `/repository/**` | GET | Retrieve artifact from repository |
| `/repository/**` | PUT | Deploy artifact to repository |

### Example Responses

**GET /status**
```json
{
  "nodeId": "node-1",
  "quorumState": "QUORUM",
  "isLeader": true,
  "activeNodes": 3,
  "sliceCount": 5
}
```

**GET /slices**
```json
{
  "slices": [
    {
      "artifact": "org.example:user-service:1.0.0",
      "state": "ACTIVE",
      "instances": 2
    }
  ]
}
```

**GET /metrics**
```json
{
  "cpu.usage": 0.45,
  "heap.used": 256000000,
  "heap.max": 512000000,
  "calls.total": 15000,
  "calls.success": 14950
}
```

## Consensus and State

### KV-Store Schema

The cluster state is stored in a distributed KV-store with structured keys:

| Key Pattern | Description |
|-------------|-------------|
| `blueprint/{artifact}` | Desired slice configuration |
| `app-blueprint/{id}` | Application blueprint definition |
| `slices/{nodeId}/{artifact}` | Slice state on specific node |
| `endpoints/{artifact}/{method}:{instance}` | Endpoint registration |
| `routes/{method}:{pathHash}` | HTTP route registration |

### State Flow

1. **Blueprint deployed** → Stored in KV-store
2. **ClusterDeploymentManager** (leader) → Allocates slices to nodes
3. **NodeDeploymentManager** → Watches for local allocations
4. **SliceStore** → Loads and activates slices
5. **EndpointRegistry** → Registers slice endpoints

## Metrics Collection

### Per-Node Metrics

```java
var metrics = node.metricsCollector().collectLocal();

// JVM metrics
metrics.get("cpu.usage");      // CPU utilization (0.0-1.0)
metrics.get("heap.used");      // Heap memory used (bytes)
metrics.get("heap.max");       // Max heap memory (bytes)

// Call metrics
metrics.get("calls.total");    // Total method calls
metrics.get("calls.success");  // Successful calls
metrics.get("calls.failed");   // Failed calls
```

### Cluster-Wide Metrics

The leader node aggregates metrics from all nodes via `MetricsScheduler`:

1. Leader sends `MetricsPing` to all nodes (every 1 second)
2. Nodes respond with `MetricsPong` containing their metrics
3. All nodes store received metrics for cluster-wide visibility

## Control Loop

The control loop evaluates scaling decisions:

```java
// Built-in decision tree controller
var controller = DecisionTreeController.decisionTreeController()
    .addRule(snapshot -> {
        // Scale up if CPU > 80%
        if (snapshot.avgCpuUsage() > 0.8) {
            return Optional.of(ScaleUp.scaleUp("high-load"));
        }
        return Optional.empty();
    });
```

### Layered Autonomy

Controllers operate in layers (see `docs/metrics-and-control.md`):

- **Layer 1**: DecisionTreeController (always running, 1 second interval)
- **Layer 2**: SLM integration (future, 2-5 second interval)
- **Layer 3**: LLM integration (future, 30-60 second interval)
- **Layer 4**: User overrides

## Inter-Slice Invocation

### SliceInvoker

```java
// Invoke a method on another slice
node.sliceInvoker()
    .invoke(
        Artifact.artifact("org.example:service:1.0.0").unwrap(),
        MethodName.methodName("processOrder").unwrap(),
        request,
        Response.class
    )
    .onSuccess(response -> log.info("Result: {}", response));
```

### InvocationHandler

The `InvocationHandler` processes incoming invocation requests:

1. Receives `InvocationMessage` from remote nodes
2. Finds local slice instance via `EndpointRegistry`
3. Dispatches to appropriate `SliceMethod`
4. Returns response via `Promise`

## HTTP Routing

When HTTP routing is enabled, the node can serve HTTP requests:

```java
var routerConfig = RouterConfig.routerConfig(9000);
var config = aetherNodeConfig(nodeId, port, nodes, sliceConfig, mgmtPort,
    Option.option(routerConfig));
```

Routes are registered by slices during activation via `RouteRegistry`.

## Error Handling

Node errors flow through the `Cause` system:

```java
node.start()
    .onFailure(cause -> {
        switch (cause) {
            case ClusterError.QuorumNotReached _ -> handleQuorumLoss();
            case SliceError.LoadFailed e -> handleSliceError(e);
            default -> log.error("Unexpected error: {}", cause.message());
        }
    });
```

## Testing

For integration tests, use the test configuration:

```java
var config = AetherNodeConfig.testConfig(nodeId, port, coreNodes);
// Faster timeouts, no management server
```

## See Also

- [Architecture Overview](architecture.md) - Complete system architecture
- [Slice Lifecycle](slice-lifecycle.md) - Slice state machine
- [Metrics and Control](metrics-control.md) - Metrics and controller details
- [KV Schema](archive/kv-schema-simplified.md) - KV-store key/value formats
