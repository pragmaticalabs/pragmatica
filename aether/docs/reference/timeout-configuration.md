# Timeout Configuration Reference

Complete reference for configuring timeouts across all Aether subsystems.

## Overview

Every operator-facing timeout in Aether is configurable via TOML using human-readable duration strings. All durations are standardized on `TimeSpan` internally. The centralized `TimeoutsConfig` record groups timeouts by subsystem, ensuring consistent defaults and a single place to tune cluster behavior.

Timeouts are loaded from the `[timeouts]` section of the node TOML configuration file. Any value not specified in TOML uses the compiled default.

## Duration Format

Duration strings support the following units:

| Unit | Suffix | Example |
|------|--------|---------|
| Days | `d` | `"1d"` |
| Hours | `h` | `"2h"` |
| Minutes | `m` | `"5m"` |
| Seconds | `s` | `"30s"` |
| Milliseconds | `ms` | `"500ms"` |
| Microseconds | `us` | `"100us"` |
| Nanoseconds | `ns` | `"1000ns"` |

Compound durations are supported: `"1m30s"`, `"2h30m"`, `"1d12h"`.

Plain numbers without a suffix are treated as seconds: `30` is equivalent to `"30s"`.

**Note:** The `w` (weeks) suffix is supported by `IntervalParser` in `ScheduledTaskManager` for scheduled task intervals, but is NOT supported by `TimeSpan`. Use `"7d"` instead of `"1w"` in timeout configuration.

## Quick Reference Table

All configurable timeouts in a single table, grouped by TOML section.

### `[timeouts.invocation]`

| TOML Key | Default | Description |
|----------|---------|-------------|
| `timeout` | `25s` | Maximum time for a single slice invocation |
| `invoker_timeout` | `30s` | Outer timeout wrapping invocation + retry overhead |
| `retry_base_delay` | `100ms` | Base delay between invocation retries (exponential backoff) |
| `max_retries` | `3` | Maximum number of invocation retry attempts |

### `[timeouts.forwarding]`

| TOML Key | Default | Description |
|----------|---------|-------------|
| `retry_delay` | `200ms` | Delay between HTTP forwarding retries |
| `max_retries` | `3` | Maximum number of forwarding retry attempts |

### `[timeouts.deployment]`

| TOML Key | Default | Description |
|----------|---------|-------------|
| `loading` | `2m` | Maximum time for slice loading (downloading + classloader setup) |
| `activating` | `1m` | Maximum time for slice activation (dependency resolution + start) |
| `deactivating` | `30s` | Maximum time for slice deactivation (stop + cleanup) |
| `unloading` | `2m` | Maximum time for slice unloading (classloader teardown) |
| `activation_chain` | `2m` | Maximum time for the full activation chain (all dependencies) |
| `transition_retry_delay` | `2s` | Delay between lifecycle state transition retries |
| `max_lifecycle_retries` | `60` | Maximum number of lifecycle transition retries |

### `[timeouts.rolling_update]`

| TOML Key | Default | Description |
|----------|---------|-------------|
| `kv_operation` | `30s` | Timeout for KV-Store operations during rolling updates |
| `terminal_retention` | `1h` | How long completed/failed rolling update records are retained |
| `cleanup_grace_period` | `5m` | Grace period before cleaning up old version artifacts |
| `rollback_cooldown` | `5m` | Minimum time between automatic rollback attempts |

### `[timeouts.cluster]`

| TOML Key | Default | Description |
|----------|---------|-------------|
| `hello` | `5s` | Timeout for initial connection handshake between nodes |
| `reconciliation_interval` | `5s` | Interval for cluster state reconciliation |
| `ping_interval` | `1s` | Interval for cluster health check pings |
| `channel_protection` | `15s` | Grace period before closing an idle cluster channel |

### `[timeouts.consensus]`

| TOML Key | Default | Description |
|----------|---------|-------------|
| `sync_retry_interval` | `5s` | Interval for retrying consensus state synchronization |
| `cleanup_interval` | `60s` | Interval for cleaning up old consensus phases |
| `proposal_timeout` | `3s` | Maximum time to wait for a consensus proposal to complete |
| `phase_stall_check` | `500ms` | Interval for checking stalled consensus phases |
| `git_persistence` | `30s` | Interval for persisting consensus state to git backup |

### `[timeouts.election]`

| TOML Key | Default | Description |
|----------|---------|-------------|
| `base_delay` | `2s` | Base delay before initiating leader election |
| `per_rank_delay` | `1s` | Additional delay per node rank (stagger elections) |
| `retry_delay` | `500ms` | Delay between election retry attempts |

### `[timeouts.swim]`

| TOML Key | Default | Description |
|----------|---------|-------------|
| `period` | `1s` | SWIM protocol round period |
| `probe_timeout` | `500ms` | Timeout for direct and indirect SWIM probes |
| `suspect_timeout` | `5s` | Duration a node stays in SUSPECT state before being declared DEAD |

### `[timeouts.observability]`

| TOML Key | Default | Description |
|----------|---------|-------------|
| `dashboard_broadcast` | `1s` | Interval for broadcasting dashboard metrics via WebSocket |
| `metrics_sliding_window` | `2h` | Retention window for metrics history |
| `event_loop_probe` | `100ms` | Interval for event loop lag probing |
| `sampler_recalculation` | `5s` | Interval for recalculating adaptive sampling rates |
| `invocation_cleanup` | `60s` | Interval for cleaning up stale invocation tracking entries |
| `trace_store_capacity` | `50000` | Maximum number of traces in the ring buffer (count, not duration) |
| `alert_history_size` | `100` | Maximum number of alerts retained in history (count, not duration) |

### `[timeouts.dht]`

| TOML Key | Default | Description |
|----------|---------|-------------|
| `operation` | `10s` | Timeout for individual DHT get/put operations |
| `anti_entropy_interval` | `30s` | Interval for anti-entropy digest exchange between replicas |

### `[timeouts.worker]`

| TOML Key | Default | Description |
|----------|---------|-------------|
| `heartbeat_interval` | `500ms` | Interval for worker heartbeat messages to governor |
| `heartbeat_timeout` | `2s` | Time without heartbeat before a worker is considered unhealthy |
| `metrics_aggregation` | `5s` | Interval for aggregating worker metrics |

### `[timeouts.security]`

| TOML Key | Default | Description |
|----------|---------|-------------|
| `websocket_auth` | `5s` | Maximum time for WebSocket authentication handshake |
| `dns_query` | `10s` | Timeout for DNS resolution queries |
| `cert_renewal_retry` | `1h` | Delay between certificate renewal retry attempts on failure |

### `[timeouts.repository]`

| TOML Key | Default | Description |
|----------|---------|-------------|
| `http_timeout` | `30s` | Timeout for HTTP artifact downloads from remote repositories |
| `locate_timeout` | `30s` | Timeout for locating artifacts in local repository |

### `[timeouts.scaling]`

| TOML Key | Default | Description |
|----------|---------|-------------|
| `evaluation_interval` | `1s` | Interval for auto-scaler evaluation tick |
| `warmup_period` | `30s` | Suppression period after slice activation before scaling decisions |
| `slice_cooldown` | `10s` | Minimum time between scaling actions for a single slice |
| `community_cooldown` | `60s` | Minimum time between scaling actions for a worker community |
| `auto_heal_retry` | `10s` | Delay between auto-heal retry attempts |
| `auto_heal_startup_cooldown` | `15s` | Grace period after node startup before auto-heal activates |

### Other Timeout-Related Configuration

These timeouts live outside `[timeouts]` for historical reasons but use the same duration format:

| Section | TOML Key | Default | Description |
|---------|----------|---------|-------------|
| `[app-http]` | `forward_timeout` | `5s` | HTTP forwarding timeout (also accepts legacy `forward_timeout_ms` in milliseconds) |
| `[dht.replication]` | `cooldown_delay` | `10s` | Delay after node startup before upgrading to target RF (also accepts legacy `cooldown_delay_ms` in milliseconds) |

## Request Path Timeouts

When a client request arrives at an Aether node, it passes through a chain of timeouts:

```
Client Request
  |
  v
[app-http] forward_timeout = 5s     <-- HTTP forwarding to target node
  |
  v
[timeouts.invocation] timeout = 25s  <-- Slice method execution
  |
  v
[timeouts.invocation] invoker_timeout = 30s  <-- Outer wrapper (includes retry overhead)
```

**Constraint:** `invoker_timeout` (30s) > `timeout` (25s) > `forward_timeout` (5s)

The 5-second gap between `invoker_timeout` and `timeout` accommodates retry delays and routing overhead. The `forward_timeout` is shorter because it covers only the network hop, not the execution.

**Retry behavior:** On invocation failure, the system retries up to `max_retries` (3) times with exponential backoff starting from `retry_base_delay` (100ms). Forwarding failures retry up to `forwarding.max_retries` (3) times with a fixed `retry_delay` (200ms). Retries on node departure are immediate (event-driven, no delay).

## Cluster Infrastructure

### SWIM Failure Detection

SWIM provides failure detection with sub-second accuracy:

```
period (1s)  ──> probe_timeout (500ms) ──> suspect_timeout (5s) ──> DEAD
```

Every `period`, each node probes one random peer. If the direct probe fails within `probe_timeout`, indirect probes are sent through K other nodes. If all probes fail, the target enters SUSPECT state. After `suspect_timeout` without a successful probe, the node is declared DEAD and removed from the cluster.

Typical detection latency: 1-2 seconds (vs 15s-2min with TCP disconnect detection).

### Consensus Timing

| Timeout | Purpose |
|---------|---------|
| `proposal_timeout` (3s) | Maximum time for a single Rabia consensus round |
| `phase_stall_check` (500ms) | Detects stuck phases and triggers recovery |
| `sync_retry_interval` (5s) | Catches up nodes that fell behind |
| `cleanup_interval` (60s) | Removes phases older than 100 behind current |
| `git_persistence` (30s) | Snapshot interval for durable backup |

### Leader Election

Election timing uses staggered delays to minimize contention:

```
Total delay = base_delay (2s) + (rank * per_rank_delay (1s))
```

Node with rank 0 (lowest NodeId) starts election after 2 seconds. Rank 1 starts after 3 seconds. This ensures deterministic, contention-free elections. On retry, `retry_delay` (500ms) is used.

### Channel Protection

`channel_protection` (15s) prevents premature closure of cluster TCP channels. After a node departs, its channel remains open for this duration to allow in-flight messages to drain.

## Deployment Lifecycle

### Slice State Machine Timeouts

Each lifecycle transition has its own timeout:

```
DOWNLOADING ──> LOADING (2m) ──> ACTIVATING (1m) ──> ACTIVE
                                                        |
UNLOADED <── UNLOADING (2m) <── DEACTIVATING (30s) <────┘
```

If any transition exceeds its timeout, the slice transitions to FAILED. The `max_lifecycle_retries` (60) with `transition_retry_delay` (2s) controls automatic recovery attempts.

### Activation Chain

`activation_chain` (2m) is the total time budget for activating a slice and all its dependencies. This is separate from individual `activating` timeouts -- a slice with many dependencies needs the chain timeout to cover the cumulative activation time.

### Rolling Updates

| Timeout | Purpose |
|---------|---------|
| `kv_operation` (30s) | KV-Store reads/writes during update orchestration |
| `cleanup_grace_period` (5m) | Old version artifact cleanup after successful update |
| `terminal_retention` (1h) | How long completed update records stay visible in API |
| `rollback_cooldown` (5m) | Prevents rollback thrashing |

## Scaling and Control

| Timeout | Purpose |
|---------|---------|
| `evaluation_interval` (1s) | How often the auto-scaler evaluates metrics |
| `warmup_period` (30s) | Suppresses scaling decisions for newly activated slices |
| `slice_cooldown` (10s) | Prevents rapid scale up/down oscillation per slice |
| `community_cooldown` (60s) | Prevents rapid scaling across a worker community |
| `auto_heal_retry` (10s) | Delay between attempts to reconcile desired vs actual state |
| `auto_heal_startup_cooldown` (15s) | Prevents auto-heal from firing before the node is fully initialized |

## Data Layer

| Timeout | Purpose |
|---------|---------|
| `dht.operation` (10s) | Timeout for individual DHT get/put operations |
| `dht.anti_entropy_interval` (30s) | How often replicas exchange CRC32 digests to detect drift |
| `dht.replication.cooldown_delay` (10s) | Startup delay before upgrading from RF=1 to target RF |

Anti-entropy runs periodically: each node compares partition digests with its replica peers. Mismatches trigger targeted data migration. The `operation` timeout bounds individual get/put calls during both normal operation and repair.

## Observability

| Timeout | Purpose |
|---------|---------|
| `dashboard_broadcast` (1s) | WebSocket push frequency for dashboard metrics |
| `metrics_sliding_window` (2h) | How long historical metrics are retained |
| `event_loop_probe` (100ms) | Frequency of event loop lag measurement |
| `sampler_recalculation` (5s) | How often adaptive sampling rates are adjusted |
| `invocation_cleanup` (60s) | Cleanup interval for stale invocation tracking entries |
| `trace_store_capacity` (50,000) | Maximum traces in the ring buffer |
| `alert_history_size` (100) | Maximum alerts retained in history |

## Security

| Timeout | Purpose |
|---------|---------|
| `websocket_auth` (5s) | WebSocket clients must authenticate within this window |
| `dns_query` (10s) | DNS resolution timeout for node discovery |
| `cert_renewal_retry` (1h) | Retry interval when certificate renewal fails |

## Repository

| Timeout | Purpose |
|---------|---------|
| `http_timeout` (30s) | Maximum time for downloading artifacts from remote Maven repositories |
| `locate_timeout` (30s) | Maximum time for locating artifacts in local repository |

## Configuration Profiles

### Low-Latency Profile

Aggressive timeouts for fast failure detection and quick recovery. Suitable for latency-sensitive workloads with reliable infrastructure.

```toml
[timeouts.invocation]
timeout = "10s"
invoker_timeout = "15s"
retry_base_delay = "50ms"
max_retries = 2

[timeouts.forwarding]
retry_delay = "100ms"
max_retries = 2

[timeouts.swim]
period = "500ms"
probe_timeout = "250ms"
suspect_timeout = "2s"

[timeouts.cluster]
hello = "3s"
reconciliation_interval = "3s"
ping_interval = "500ms"
channel_protection = "10s"

[timeouts.scaling]
evaluation_interval = "500ms"
warmup_period = "15s"
slice_cooldown = "5s"

[timeouts.observability]
dashboard_broadcast = "500ms"
event_loop_probe = "50ms"

[app-http]
forward_timeout = "3s"
```

### High-Throughput Profile

Relaxed timeouts with larger windows. Suitable for batch processing or high-volume workloads where occasional latency spikes are acceptable.

```toml
[timeouts.invocation]
timeout = "60s"
invoker_timeout = "90s"
retry_base_delay = "200ms"
max_retries = 5

[timeouts.forwarding]
retry_delay = "500ms"
max_retries = 5

[timeouts.deployment]
loading = "5m"
activating = "3m"
activation_chain = "5m"

[timeouts.swim]
period = "2s"
probe_timeout = "1s"
suspect_timeout = "10s"

[timeouts.scaling]
evaluation_interval = "5s"
warmup_period = "60s"
slice_cooldown = "30s"
community_cooldown = "120s"

[timeouts.observability]
metrics_sliding_window = "6h"
trace_store_capacity = 200000
```

### Resilient Profile

Generous retries and long retention. Suitable for clusters with unreliable network or nodes that may experience transient failures.

```toml
[timeouts.invocation]
timeout = "45s"
invoker_timeout = "60s"
retry_base_delay = "500ms"
max_retries = 5

[timeouts.forwarding]
retry_delay = "1s"
max_retries = 5

[timeouts.deployment]
loading = "5m"
activating = "3m"
deactivating = "2m"
unloading = "5m"
activation_chain = "5m"
transition_retry_delay = "5s"
max_lifecycle_retries = 120

[timeouts.rolling_update]
kv_operation = "60s"
terminal_retention = "6h"
cleanup_grace_period = "15m"
rollback_cooldown = "10m"

[timeouts.cluster]
hello = "10s"
reconciliation_interval = "10s"
channel_protection = "30s"

[timeouts.consensus]
sync_retry_interval = "10s"
proposal_timeout = "10s"

[timeouts.swim]
period = "2s"
probe_timeout = "1s"
suspect_timeout = "15s"

[timeouts.scaling]
auto_heal_retry = "30s"
auto_heal_startup_cooldown = "30s"

[timeouts.security]
cert_renewal_retry = "30m"

[timeouts.observability]
metrics_sliding_window = "12h"
alert_history_size = 500
trace_store_capacity = 100000
```

## Interaction Effects

Certain timeouts have ordering constraints. Violating these constraints may cause unexpected behavior such as premature timeouts or retries that never have time to complete.

| Constraint | Reason |
|------------|--------|
| `invoker_timeout` > `invocation.timeout` | Invoker wraps invocation; must allow time for retries |
| `invocation.timeout` > `app-http.forward_timeout` | Forwarding is one step within invocation |
| `worker.heartbeat_timeout` > `worker.heartbeat_interval` | Must allow at least one heartbeat cycle |
| `swim.suspect_timeout` > 2 x `swim.period` | Node needs at least two probe rounds before being declared dead |
| `swim.period` > `swim.probe_timeout` | Probe must complete within a single round |
| `deployment.activation_chain` >= `deployment.activating` | Chain covers one or more activations |
| `consensus.cleanup_interval` > `consensus.sync_retry_interval` | Sync must have time to catch up before cleanup runs |
| `scaling.warmup_period` > `scaling.evaluation_interval` | Multiple evaluations should occur during warmup |
| `scaling.community_cooldown` >= `scaling.slice_cooldown` | Community-level decisions should be less frequent |
| `election.base_delay` > `election.retry_delay` | Initial election should take longer than retries |
| `rolling_update.rollback_cooldown` >= `rolling_update.cleanup_grace_period` | Prevent cleaning up artifacts that might be needed for rollback |

---

*Last updated: 2026-03-16 (v0.20.0)*
