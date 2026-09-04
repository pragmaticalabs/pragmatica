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

When `[tls]` `auto_generate = false` with operator-provided `cert_path`/`key_path`, the node
**refuses to start** if `AETHER_INSECURE_DEV_MODE=true` is also set — insecure dev-mode is
incompatible with real operator certificates. See
[TLS Certificate Management](../operators/tls-certificates.md).

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
| `operationTimeout` | 30 seconds | Operation timeout |

## Storage Encryption Configuration

### `[storage.encryption]` — #253

At-rest encryption for block-storage tiers, keyed through the node's existing `SecretsProvider`
(no dedicated key-management service; Vault/#119 integration is a deferred follow-up).

```toml
[storage.encryption]
active_key_id = "k1"
streams_encrypted = false        # opts the built-in `streams` instance's segment-block tiers in

[storage.encryption.keys]
k1 = "${secrets:storage-key-v1}"
k0 = "${secrets:storage-key-v0}"  # retired key, kept resolvable for reads only
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `active_key_id` | required if section present | Key id (must be a key in `keys`) new writes encrypt under |
| `streams_encrypted` | false | Opts the built-in `streams` instance's segment-block tiers into encryption — `streams` has no `[storage.streams] encrypted` field of its own |
| `keys.<id>` | none | `${secrets:<path>}` reference resolved via `SecretsProvider`, one per key id. Secret value must be a Base64-encoded 32-byte AES-256 key |

Per-instance opt-in (any `[storage.<name>]` section, e.g. `[storage.artifacts]`):

| Parameter | Default | Description |
|-----------|---------|-------------|
| `encrypted` | false | Wraps this instance's disk/DHT tiers in `EncryptingStorageTier`. Requires `[storage.encryption]` to be present with at least one key |

**Boot behavior.** Every key in `keys` is resolved through the live `SecretsProvider` once, at boot,
bounded by a 30-second timeout. Boot fails — naming the key id, never the secret value — if any key
fails to resolve (malformed `${secrets:...}` reference, provider failure, invalid Base64, wrong
decoded length), if `active_key_id` is absent from `keys`, or if encryption is requested
(`encrypted = true` on any instance, or `streams_encrypted = true`) but `[storage.encryption]` is
missing entirely. If the node's environment has no `SecretsProvider` at all while
`[storage.encryption]` is present, boot fails with `NoSecretsProviderForStorageEncryption`.

**Coverage.** Encrypts block payloads (`get`/`put`) on `LocalDiskTier` and `DhtStorageTier` for any
instance with `encrypted = true`, and on the `streams` instance's segment-block tiers when
`streams_encrypted = true`. Does **not** cover: `MemoryTier` (in-process, never touches disk);
metadata/refs/snapshot files (`MetadataStore`/`SnapshotManager`) for any instance, `streams`
included; the auto-synthesized default `artifacts` instance used when no explicit
`[storage.artifacts]` section is configured (hardcoded never-encrypted — configure the section
explicitly with `encrypted = true` to cover it); the `content` storage instance (provisioned via a
separate code path that does not currently accept a keyring at all — tracked as the same structural
gap as `content`'s exclusion from demotion/GC, #783).

**Not the same as `[streams.X].encryption-key-id`.** That per-stream blueprint key is unrelated and
was already found structurally inert and rejected at validation (`#576`, 2026-08-27) —
`StorageSegmentSink`'s own segment-payload pipeline has no encryptor wired to it, and `#253` does not
change that. `streams_encrypted` above only affects the generic storage-tier framework's `streams`
instance.

**Wire format.** Each encrypted block is stored as
`MAGIC("AEC1") | VERSION | KEY_ID_LEN | KEY_ID | NONCE(12B) | CIPHERTEXT+GCM_TAG`. Everything before
the nonce is authenticated (AES-GCM AAD) but not encrypted — editing the header, including swapping
in a different valid key id, fails decryption closed. A block with no recognizable header (fewer
than 4 bytes, or a non-matching magic) is treated as **legacy plaintext**: enabling encryption over a
local-disk directory that already holds unmarked block files is refused at boot; a DHT tier has no
directory to scan and instead fails closed per block on read. A block whose header names a key id
absent from the configured keyring fails with `UnknownKeyId` rather than a truncated read.

**Key rotation.** Add a new `keys.<id>` entry, flip `active_key_id`; every prior key remains
resolvable for existing blocks. Re-encrypting already-written blocks under the new active key
(including on tier demote/promote) is not part of this fix.

**Migration limitation.** There is no path from an existing plaintext tier to an encrypted one in
rc4 — enabling `encrypted = true` (or `streams_encrypted = true`) is new-instance/fresh-data only.

## Environment Variables

For container deployment, configuration via environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `NODE_ID` | auto-generated | Unique node identifier |
| `AETHER_CLUSTER_NAME` | **required** | Cluster name (1-63 lowercase DNS label, no trailing hyphen; single-character names allowed). Resolved from this env var or bootstrap-seeded KV — the node refuses to start if it resolves to missing/empty |
| `AETHER_INSECURE_DEV_MODE` | false | Enables dev-only inject/test endpoints. Incompatible with operator-provided TLS certificates — see TLS Configuration |
| `CLUSTER_PORT` | 8090 | Cluster communication port |
| `MANAGEMENT_PORT` | 8080 | HTTP API port |
| `CLUSTER_PEERS` | required | Cluster peer list |
| `JAVA_OPTS` | `-Xmx256m` | JVM options |
| `AETHER_CLUSTER_SECRET` | none | Shared secret used to generate TLS certificates; fallback for `tls.cluster_secret` |

There are no `TLS_ENABLED` / `TLS_CERT_PATH` / `TLS_KEY_PATH` environment variables — TLS is
configured via the `[cluster] tls` and `[tls]` TOML keys (`auto_generate`, `cert_path`,
`key_path`, `ca_path`), not environment variables.

A node **fails to start** (loud error, non-zero exit) when its cluster name is missing or empty.
The name is resolved from `AETHER_CLUSTER_NAME` or, for bootstrapped clusters, from the
bootstrap-seeded KV entry. The same validation regex (1-63 lowercase DNS label, no trailing
hyphen, single-character names permitted) is applied uniformly across the node, CLI, and config.

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
| `max_group_size` | int | `100` | **Inert today** — declared for the worker-group splitting mechanism, which is not built (#673: `GroupAssignment.computeGroups` has no live caller; communities are one-per-source). Parsed and validated (`< 2` refuses at parse); changes no behavior until #673's wire-or-delete decision. Community size in the shipping product is the per-source worker count. |

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

## Streaming Configuration

```toml
[streaming]
publish_forward_timeout = "5s"
read_forward_timeout = "2s"
max_read_response_bytes = "28MB"
reshuffle_concurrency = 2
caught_up_max_lag_offsets = 1024
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `publish_forward_timeout` | timespan | `5s` | Wait for a publish forwarded to the partition owner |
| `read_forward_timeout` | timespan | `2s` | Wait for a read forwarded to the partition owner |
| `max_read_response_bytes` | data size | `28MB` | Cap on a single forwarded-read response |
| `reshuffle_concurrency` | int | `2` | Partitions one node may hold in materialize+backfill at once. Must be `>= 1` |
| `caught_up_max_lag_offsets` | long | `1024` | How far a `CAUGHT_UP` replica may trail the freshest peer watermark and still serve reads or count toward the ring-release catch-up gate. Must be `>= 0` |

`reshuffle_concurrency` paces backfill work so a large reshuffle cannot flood a node. Raise it when
partitions queue behind slow backfills; lower it when backfill traffic competes with serving. A partition
that cannot get a slot is queued, not rejected, and the caller sees a retryable paced error naming this
key. A slot held past a bounded tenure while others are queued is preempted — the backfill continues but
stops counting against this limit — so a stalled backfill cannot starve the queue indefinitely.

`caught_up_max_lag_offsets` exists because the `CAUGHT_UP` replication state never downgrades: nothing
moves a replica out of it when it stops acking, so under a partition the state does not go stale — it
FREEZES at its last good value and reads as healthy indefinitely. Without a freshness bound a replica
that readers can still reach but which stopped acking to the owner keeps serving increasingly stale data
with no error, and the ring-release gate over-counts it, so an owner can release its partition believing
enough replicas are caught up.

Lag is measured in OFFSETS relative to the freshest peer watermark, deliberately not as a time-to-live: a
replica's watermark advances only on acks and backfill milestones, and nothing refreshes it on a quiet
partition, so a time-based rule would age out every replica of a write-idle stream and stop serving reads
from the healthiest streams in the cluster.

Raise it if healthy replicas are being skipped as read sources under heavy write bursts — normal
asynchronous replication means a healthy replica is transiently behind on every write. Lower it to demand
tighter read freshness at the cost of forwarding more reads to the owner. `0` demands exact watermark
parity and is legitimate but very strict. Two limits are inherent to a relative measure: a partition with
a single registered peer has nothing to compare against and is always considered fresh, and if every
replica freezes together their lags stay equal and none is flagged.

**The default is not a measured value.** It has not been derived from an observed steady-state lag
distribution; it is set above a typical in-flight batch depth and exposed as a knob so it can be relieved
without a rebuild.

## Cloud Configuration

Cloud provider integration is configured via the `[cloud]` TOML section. See [Cloud Integration](cloud-integration.md) for the full operator guide.

> **Provisioning a new cluster?** `[cloud.*]` below is *generated output* — `aether cluster bootstrap`
> composes it from a separate `[source.<name>]` schema you write once. See the
> [Bootstrap Config Reference](bootstrap-config.md) for that schema and a minimal working example; you
> should not hand-write `[cloud.*]` in a bootstrap-config file.

```toml
[cloud]
provider = "hetzner"                          # Required: hetzner | aws | gcp | azure

[cloud.credentials]                            # Provider-specific authentication
api_token = "${env:HCLOUD_TOKEN}"         # Supports ${env:VAR} interpolation

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

## Database Configuration & Schema Migration

### Multi-Datasource Convention

The `[database]` section configures the default datasource, used by `@Sql` and by migration scripts in the `schema/` root directory. Named datasources use `[database.<name>]` sections, corresponding to `schema/<name>/` subdirectories and `@ResourceQualifier(config="database.<name>")` annotations.

**`@Sql` takes no argument** — it is `@ResourceQualifier(type = SqlConnector.class, config = "database")` fixed at the annotation definition
[verified: `aether/resource/api/.../db/Sql.java`], so it always resolves the flat `[database]` section and only that one. `@Sql("name")` is not valid Java for this annotation and will not compile. To reach a *named* datasource, use `@ResourceQualifier(config = "database.<name>")` directly, as in `schema/analytics/` below — `@Sql` itself cannot select one (#577).

```toml
[database]                        # default datasource (used by @Sql and schema/ root)
name = "default"
type = "POSTGRESQL"
host = "localhost"
port = 5432
database = "myapp"
username = "app"
password = "secret"

[database.analytics]              # named datasource (used by schema/analytics/ and @ResourceQualifier(config="database.analytics"))
name = "analytics"
type = "POSTGRESQL"
host = "analytics-host"
port = 5432
database = "analytics"
username = "reader"
password = "secret"
```

**Strict resolution:** Every schema directory must have a corresponding config section. A missing section causes an explicit failure — there is no fallback or derivation between sections. This prevents silent misconfiguration where migrations run against the wrong database.

**Single-datasource zero-config:** Slices using only `@Sql` place migration scripts directly in `schema/` (no subdirectory). The scripts map to `[database]` — the same section `@Sql` resolves from.

**Bootstrapping a cluster:** a bootstrap-config `[source.<name>] databases.<name> = "url"` entry composes into a *named* `[database.<name>]` section on the provisioned node, never the flat `[database]` — a common trap for slices that expect the default datasource. See [Bootstrap Config Reference](bootstrap-config.md#c-databasesx-vs-flat-database).

## Blueprint Resources (`resources.toml`)

Application-level configuration that travels with the blueprint artifact. Loaded into ConfigService at GLOBAL scope when the blueprint is deployed.

Example:
```toml
[datasources.orders_db]
database = "orders"
schema_version = 12
migrations = "schema/orders_db"
pool.max_connections = 30

[datasources.analytics_db]
database = "analytics"
pool.max_connections = 10
```

## Infrastructure Endpoints (`[endpoints.*]`)

Node-level infrastructure endpoints configured in `aether.toml`. Loaded at NODE scope, overriding matching GLOBAL-scope keys from `resources.toml`.

Example:
```toml
[endpoints.orders_db]
host = "db.prod.internal"
port = 5432
username = "app"
password = "${env:DB_ORDERS_PASSWORD}"

[endpoints.analytics_db]
host = "analytics.prod.internal"
port = 5432
username = "reader"
password = "${env:DB_ANALYTICS_PASSWORD}"
```

### Config Merge Hierarchy

When a slice requests configuration (e.g., `@ResourceQualifier(config = "database.orders_db")`), the ConfigService resolves values in priority order:

1. **SLICE** scope — per-slice overrides (highest priority)
2. **NODE** scope — from `aether.toml` `[endpoints.*]` sections
3. **GLOBAL** scope — from blueprint's `resources.toml` `[datasources.*]` sections

This allows blueprints to define "what" (database name, pool size, schema) while nodes define "where" (host, port, credentials).

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
