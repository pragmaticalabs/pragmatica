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
    sliceConfig,       // SliceConfig - slice repository configuration
    managementPort,    // int - HTTP API port (0 to disable)
    artifactRepoConfig // DHTConfig - artifact repository settings
);
```

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `self` | `NodeId` | required | Unique node identifier |
| `port` | `int` | required | Cluster communication port |
| `coreNodes` | `List<NodeInfo>` | required | List of cluster peers |
| `sliceActionConfig` | `SliceActionConfig` | defaults | Slice lifecycle timeouts |
| `sliceConfig` | `SliceConfig` | defaults | Slice repository configuration |
| `managementPort` | `int` | 8080 | HTTP management API port |
| `artifactRepoConfig` | `DHTConfig` | DEFAULT | Artifact repository settings |

Additional fields configured via `with*` builder methods:

| Method | Type | Default | Description |
|--------|------|---------|-------------|
| `withTls()` | `TlsConfig` | none | TLS for cluster and HTTP |
| `withAppHttp()` | `AppHttpConfig` | defaults | Application HTTP server for slice routes |
| `withControllerConfig()` | `ControllerConfig` | DEFAULT | Scaling thresholds and behavior |
| `withTtm()` | `TtmConfig` | defaults | TTM predictive scaling |
| `withRollback()` | `RollbackConfig` | defaults | Automatic rollback settings |
| `withConfigProvider()` | `ConfigurationProvider` | none | Resource provisioning |
| `withEnvironment()` | `EnvironmentIntegration` | none | Compute/secrets integration |
| `withAutoHeal()` | `AutoHealConfig` | DEFAULT | Auto-heal retry configuration |

### Factory Methods

```java
// Minimal configuration
AetherNodeConfig.aetherNodeConfig(nodeId, port, peers);

// With custom slice action config
AetherNodeConfig.aetherNodeConfig(nodeId, port, peers, sliceActionConfig);

// With management port
AetherNodeConfig.aetherNodeConfig(nodeId, port, peers, sliceActionConfig, 8080);

// Full configuration
AetherNodeConfig.aetherNodeConfig(nodeId, port, peers, sliceActionConfig, sliceConfig, 8080, dhtConfig);

// Test configuration (shorter timeouts, management disabled, full replication)
AetherNodeConfig.testConfig(nodeId, port, peers);

// Forge simulation configuration (CPU-based scaling disabled)
AetherNodeConfig.forgeConfig(nodeId, port, peers);
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
SliceActionConfig.sliceActionConfig();
SliceActionConfig.sliceActionConfig(serializerProvider);
SliceActionConfig.sliceActionConfig(serializerProvider, frameworkJarsPath);
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `loadingTimeout` | 2 minutes | Max time for slice loading |
| `activatingTimeout` | 1 minute | Max time for slice activation |
| `deactivatingTimeout` | 30 seconds | Max time for slice deactivation |
| `unloadingTimeout` | 2 minutes | Max time for slice unloading |
| `startStopTimeout` | 5 seconds | Max time for start/stop |
| `repositories` | Local repository | Artifact repositories |
| `serializerProvider` | Fury | Serialization provider |
| `frameworkJarsPath` | none | Custom framework JARs path |

## Controller Configuration

### ControllerConfig

Controls automatic scaling behavior.

```java
ControllerConfig.DEFAULT;  // Pre-validated production defaults
ControllerConfig.controllerConfig(
    cpuScaleUpThreshold,       // double - CPU % to trigger scale up
    cpuScaleDownThreshold,     // double - CPU % to trigger scale down
    callRateScaleUpThreshold,  // double - calls/sec to trigger scale up
    evaluationIntervalMs       // long - evaluation frequency
);  // Returns Result<ControllerConfig> with validation
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `cpuScaleUpThreshold` | 0.8 (80%) | CPU utilization to trigger scale up |
| `cpuScaleDownThreshold` | 0.2 (20%) | CPU utilization to trigger scale down |
| `callRateScaleUpThreshold` | 2000 | Calls/sec to trigger scale up |
| `evaluationIntervalMs` | 1000 | Evaluation interval (ms) |
| `warmUpPeriodMs` | 30000 | Warm-up period before scaling (ms) |
| `sliceCooldownMs` | 10000 | Cooldown between scaling actions (ms) |

### Runtime Configuration via API

```bash
# View current config
curl http://localhost:8080/api/controller/config

# Update config
curl -X POST http://localhost:8080/api/controller/config \
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
| `clusterSize` | required | Number of nodes in the cluster |
| `reconciliationInterval` | 5 seconds | Cluster state sync interval |
| `pingInterval` | 1 second | Health check interval |
| `helloTimeout` | 5 seconds | Connection handshake timeout |
| `coreNodes` | required | List of cluster peers |
| `tls` | none | TLS configuration |
| `backoff` | defaults | Connection backoff configuration |

### Peer Format

Peers are specified as `NodeInfo` objects:

```java
NodeInfo.nodeInfo(NodeId.nodeId("node-1"), "192.168.1.1", 8090);
```

Or in string format for CLI/Podman:
```
node-1:192.168.1.1:8090,node-2:192.168.1.2:8090,node-3:192.168.1.3:8090
```

## Protocol Configuration

### ProtocolConfig

Rabia consensus protocol settings.

```java
ProtocolConfig.defaultConfig();   // Production defaults
ProtocolConfig.testConfig();      // Faster sync retry for tests
```

| Parameter | Production | Test | Description |
|-----------|------------|------|-------------|
| `cleanupInterval` | 60s | 60s | Interval for cleaning up old phases |
| `syncRetryInterval` | 5s | 100ms | State sync retry interval |
| `removeOlderThanPhases` | 100 | 100 | Remove phases older than N behind current |

## DHT Configuration

### DHTConfig

Distributed hash table for artifact storage.

```java
DHTConfig.DEFAULT;      // 3 replicas, quorum of 2
DHTConfig.FULL;         // Full replication (all nodes)
DHTConfig.SINGLE_NODE;  // Single-node testing
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `replicationFactor` | 3 | Number of replicas (0 = full replication) |
| `writeQuorum` | 2 | Write quorum size |
| `readQuorum` | 2 | Read quorum size |
| `operationTimeout` | 10 seconds | Operation timeout |

## Environment Variables

For container deployment, configuration via environment variables:

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
    SliceActionConfig.sliceActionConfig(),
    8080
).withTls(TlsConfig.tlsConfig(certPath, keyPath));
```

### Custom Timeouts

Since `SliceActionConfig` is a record, custom timeouts can be constructed directly:

```java
var sliceActionConfig = new SliceActionConfig(
    timeSpan(5).minutes(),    // loadingTimeout - longer for large slices
    timeSpan(2).minutes(),    // activatingTimeout
    timeSpan(1).minutes(),    // deactivatingTimeout
    timeSpan(5).minutes(),    // unloadingTimeout
    timeSpan(2).minutes(),    // startStopTimeout
    List.of(localRepository()),
    furySerializerFactoryProvider(),
    Option.empty()            // frameworkJarsPath
);

var config = AetherNodeConfig.aetherNodeConfig(
    nodeId, port, peers, sliceActionConfig
);
```

### Test Configuration

```java
// Shorter timeouts for faster tests
var config = AetherNodeConfig.testConfig(nodeId, port, peers);
```

## Worker Configuration

```toml
[worker]
group_name = "default"
zone = "local"
max_group_size = 100
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `group_name` | string | `"default"` | Logical group name for this worker pool |
| `zone` | string | `"local"` | Zone identifier for zone-aware grouping. Workers in the same zone auto-cluster |
| `max_group_size` | int | `100` | Maximum members per group before splitting. Groups split at this threshold; merge below 50% |

Zone is also extracted from the NodeId: everything before the last dash (e.g., `us-east-worker-1` → zone `us-east-worker`). The explicit `zone` config takes precedence for group computation.

Workers self-organize into groups deterministically from SWIM membership. Same membership produces identical groups on every worker — no coordination needed. Each group elects its own governor (lowest ALIVE NodeId).

## Backup Configuration

```toml
[backup]
enabled = true
interval = "5m"
path = "/data/backups"
remote = ""
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `enabled` | boolean | `false` | Enable durable backup |
| `interval` | string | `"5m"` | Backup frequency |
| `path` | string | env-dependent | Git repo directory for backups |
| `remote` | string | `""` | Optional git remote URL |

Default `path` by environment:
- LOCAL: `./aether-backups`
- DOCKER: `/data/backups`
- KUBERNETES: `/var/aether/backups`

## DHT Replication Configuration

```toml
[dht.replication]
cooldown_delay_ms = 10000
cooldown_rate = 10000
target_rf = 3
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `cooldown_delay_ms` | long | `10000` | Delay (ms) after node startup before upgrading to target RF |
| `cooldown_rate` | int | `10000` | Max entries/sec during replication warmup |
| `target_rf` | int | `3` | Target replication factor (0 = full replication) |

## Cloud Configuration

Cloud provider integration is configured via the `[cloud]` TOML section. See [Cloud Integration](cloud-integration.md) for the full operator guide.

```toml
[cloud]
provider = "hetzner"                          # Required: hetzner | aws | gcp | azure

[cloud.credentials]                            # Provider-specific authentication
api_token = "${env:HETZNER_API_TOKEN}"         # Supports ${env:VAR} interpolation

[cloud.compute]                                # Instance provisioning parameters
server_type = "cx22"
image = "ubuntu-24.04"
region = "fsn1"
user_data = "#!/bin/bash\n..."
ssh_key_ids = "12345,67890"                    # Comma-separated lists
network_ids = "11111"
firewall_ids = "22222"

[cloud.load_balancer]                          # Optional: LB target registration
load_balancer_id = "99999"
destination_port = "8090"

[cloud.discovery]                              # Optional: tag/label-based peer discovery
cluster_name = "production"
poll_interval_ms = "15000"

[cloud.secrets]                                # Optional: secrets backend config
```

| Section | Purpose | Required |
|---------|---------|----------|
| `[cloud]` | Provider selection | Yes (if using cloud) |
| `[cloud.credentials]` | Authentication keys | Yes |
| `[cloud.compute]` | VM/instance parameters | Yes |
| `[cloud.load_balancer]` | Load balancer integration | No |
| `[cloud.discovery]` | Peer auto-discovery | No |
| `[cloud.secrets]` | Secrets resolution backend | No |

**Environment variable interpolation:** Any value matching `${env:VAR_NAME}` in credentials or compute sections is resolved from the process environment at config load time.

**Supported providers:** Hetzner, AWS, GCP, Azure. Each provider module registers via ServiceLoader. Provider-specific credential and compute keys are documented in the [Cloud Integration](cloud-integration.md) reference.

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
