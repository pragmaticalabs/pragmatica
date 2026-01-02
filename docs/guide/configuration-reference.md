# Configuration Reference

Complete reference for configuring Aether nodes, cluster, and runtime behavior.

## Node Configuration

### AetherNodeConfig

Main configuration for an Aether cluster node.

```java
AetherNodeConfig.aetherNodeConfig(
    self,              // NodeId - unique node identifier
    port,              // int - cluster communication port
    coreNodes,         // List<NodeInfo> - cluster peers
    sliceActionConfig, // SliceActionConfig - slice lifecycle settings
    managementPort,    // int - HTTP API port (0 to disable)
    httpRouter,        // Option<RouterConfig> - HTTP routing (empty to disable)
    artifactRepoConfig // DHTConfig - artifact repository settings
);
```

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `self` | `NodeId` | required | Unique node identifier |
| `port` | `int` | required | Cluster communication port |
| `coreNodes` | `List<NodeInfo>` | required | List of cluster peers |
| `sliceActionConfig` | `SliceActionConfig` | defaults | Slice lifecycle timeouts |
| `managementPort` | `int` | 8080 | HTTP management API port |
| `httpRouter` | `Option<RouterConfig>` | empty | HTTP routing configuration |
| `artifactRepoConfig` | `DHTConfig` | DEFAULT | Artifact repository settings |

### Factory Methods

```java
// Minimal configuration
AetherNodeConfig.aetherNodeConfig(nodeId, port, peers);

// With custom slice config
AetherNodeConfig.aetherNodeConfig(nodeId, port, peers, sliceConfig);

// With management port
AetherNodeConfig.aetherNodeConfig(nodeId, port, peers, sliceConfig, 8080);

// Full configuration
AetherNodeConfig.aetherNodeConfig(nodeId, port, peers, sliceConfig, 8080, httpRouter, dhtConfig);

// Test configuration (shorter timeouts)
AetherNodeConfig.testConfig(nodeId, port, peers);
```

### TLS Configuration

```java
var tlsConfig = TlsConfig.tlsConfig(certPath, keyPath);
var config = AetherNodeConfig.aetherNodeConfig(...)
                             .withTls(tlsConfig);
```

## Slice Configuration

### SliceActionConfig

Controls slice lifecycle timeouts and behavior.

```java
SliceActionConfig.defaultConfiguration();
SliceActionConfig.defaultConfiguration(serializerProvider);
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `loadingTimeout` | 2 minutes | Max time for slice loading |
| `activatingTimeout` | 1 minute | Max time for slice activation |
| `deactivatingTimeout` | 30 seconds | Max time for slice deactivation |
| `unloadingTimeout` | 2 minutes | Max time for slice unloading |
| `startStopTimeout` | 30 seconds | Max time for start/stop |
| `repositories` | Maven Central | Artifact repositories |
| `serializerProvider` | Fury | Serialization provider |
| `frameworkJarsPath` | none | Custom framework JARs path |

### Custom Configuration

```java
var sliceConfig = new SliceActionConfig(
    timeSpan(3).minutes(),     // loadingTimeout
    timeSpan(2).minutes(),     // activatingTimeout
    timeSpan(1).minutes(),     // deactivatingTimeout
    timeSpan(3).minutes(),     // unloadingTimeout
    timeSpan(1).minutes(),     // startStopTimeout
    List.of(customRepo),       // repositories
    furySerializerFactoryProvider(),
    Option.some(Path.of("/custom/jars"))
);
```

## Controller Configuration

### ControllerConfig

Controls automatic scaling behavior.

```java
ControllerConfig.defaults();
ControllerConfig.controllerConfig(
    cpuScaleUpThreshold,       // double - CPU % to trigger scale up
    cpuScaleDownThreshold,     // double - CPU % to trigger scale down
    callRateScaleUpThreshold,  // double - calls/sec to trigger scale up
    evaluationIntervalMs       // long - evaluation frequency
);
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `cpuScaleUpThreshold` | 0.8 (80%) | CPU utilization to trigger scale up |
| `cpuScaleDownThreshold` | 0.2 (20%) | CPU utilization to trigger scale down |
| `callRateScaleUpThreshold` | 1000 | Calls/sec to trigger scale up |
| `evaluationIntervalMs` | 1000 | Evaluation interval (ms) |

### Runtime Configuration via API

```bash
# View current config
curl http://localhost:8080/controller/config

# Update config
curl -X POST http://localhost:8080/controller/config \
  -H "Content-Type: application/json" \
  -d '{
    "cpuScaleUpThreshold": 0.75,
    "cpuScaleDownThreshold": 0.15,
    "evaluationIntervalMs": 2000
  }'
```

## Topology Configuration

### TopologyConfig

Cluster topology and node discovery.

| Parameter | Default | Description |
|-----------|---------|-------------|
| `self` | required | This node's identifier |
| `reconciliationInterval` | 5 seconds | Cluster state sync interval |
| `pingInterval` | 1 second | Health check interval |
| `helloTimeout` | 30 seconds | Connection handshake timeout |
| `coreNodes` | required | List of cluster peers |
| `tls` | none | TLS configuration |

### Peer Format

Peers are specified as `NodeInfo` objects:

```java
NodeInfo.nodeInfo(NodeId.nodeId("node-1"), "192.168.1.1", 8090);
```

Or in string format for CLI/Docker:
```
node-1:192.168.1.1:8090,node-2:192.168.1.2:8090,node-3:192.168.1.3:8090
```

## Protocol Configuration

### ProtocolConfig

Rabia consensus protocol settings.

```java
ProtocolConfig.defaultConfig();   // Production defaults
ProtocolConfig.testConfig();      // Faster timeouts for tests
```

| Parameter | Production | Test | Description |
|-----------|------------|------|-------------|
| `batchSize` | 100 | 10 | Commands per batch |
| `batchTimeout` | 10ms | 5ms | Max batch wait time |
| `proposalTimeout` | 500ms | 100ms | Proposal round timeout |
| `syncTimeout` | 5s | 1s | State sync timeout |

## DHT Configuration

### DHTConfig

Distributed hash table for artifact storage.

```java
DHTConfig.DEFAULT;  // 3 replicas, default partitions
DHTConfig.FULL;     // Full replication (all nodes)
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `replicationFactor` | 3 | Number of replicas |
| `partitions` | 1024 | Hash ring partitions |
| `virtualNodes` | 100 | Virtual nodes per real node |

## Environment Variables

For Docker deployment, configuration via environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `NODE_ID` | auto-generated | Unique node identifier |
| `CLUSTER_PORT` | 8090 | Cluster communication port |
| `MANAGEMENT_PORT` | 8080 | HTTP API port |
| `PEERS` | required | Cluster peer list |
| `JAVA_OPTS` | `-Xmx512m` | JVM options |
| `TLS_ENABLED` | false | Enable TLS |
| `TLS_CERT_PATH` | none | TLS certificate path |
| `TLS_KEY_PATH` | none | TLS key path |

## CLI Arguments

Command-line arguments for `aether-node`:

```bash
java -jar aether-node.jar \
    --node-id=node-1 \
    --port=8090 \
    --management-port=8080 \
    --peers=node-1:host1:8090,node-2:host2:8090
```

| Argument | Description |
|----------|-------------|
| `--node-id` | Node identifier |
| `--port` | Cluster communication port |
| `--management-port` | HTTP API port (0 to disable) |
| `--peers` | Cluster peer list |

## Configuration Examples

### Minimal 3-Node Cluster

```java
var peers = List.of(
    NodeInfo.nodeInfo(NodeId.nodeId("node-1"), "192.168.1.1", 8090),
    NodeInfo.nodeInfo(NodeId.nodeId("node-2"), "192.168.1.2", 8090),
    NodeInfo.nodeInfo(NodeId.nodeId("node-3"), "192.168.1.3", 8090)
);

var config = AetherNodeConfig.aetherNodeConfig(
    NodeId.nodeId("node-1"),
    8090,
    peers
);
```

### Production with TLS

```java
var config = AetherNodeConfig.aetherNodeConfig(
    NodeId.nodeId("node-1"),
    8090,
    peers,
    SliceActionConfig.defaultConfiguration(),
    8080,
    Option.some(routerConfig),
    DHTConfig.DEFAULT
).withTls(TlsConfig.tlsConfig(certPath, keyPath));
```

### Custom Timeouts

```java
var sliceConfig = new SliceActionConfig(
    timeSpan(5).minutes(),    // Longer loading for large slices
    timeSpan(2).minutes(),
    timeSpan(1).minutes(),
    timeSpan(5).minutes(),
    timeSpan(2).minutes(),
    List.of(mavenCentral, privateRepo),
    furySerializerFactoryProvider(),
    Option.empty()
);

var config = AetherNodeConfig.aetherNodeConfig(
    nodeId, port, peers, sliceConfig
);
```

### Test Configuration

```java
// Shorter timeouts for faster tests
var config = AetherNodeConfig.testConfig(nodeId, port, peers);
```

## Configuration Best Practices

### Production

1. **Use TLS** for all cluster communication
2. **Set appropriate timeouts** based on slice complexity
3. **Configure replication** based on cluster size
4. **Use separate ports** for management and cluster traffic

### Development

1. **Use test configuration** for faster iteration
2. **Disable TLS** for simplicity
3. **Use local artifact repository** for faster loading

### Testing

1. **Use `testConfig()`** for shorter timeouts
2. **Use full replication** for simplicity
3. **Disable management port** if not needed
