# Aether CLI Reference

## Overview

Aether provides three command-line tools:

| Tool | Purpose | Script |
|------|---------|--------|
| `aether` | Cluster management CLI | `script/aether.sh` |
| `aether-node` | Run a cluster node | `script/aether-node.sh` |
| `aether-forge` | Testing simulator | `script/aether-forge.sh` |

## Installation

### Build from Source

```bash
git clone https://github.com/pragmaticalabs/pragmatica.git
cd aether
mvn package -DskipTests
```

### Run Scripts

After building, use the scripts in the `script/` directory:

```bash
./script/aether.sh status
./script/aether-node.sh --port=8091
./script/aether-forge.sh
```

---

## aether: Cluster Management

Interactive CLI for managing Aether clusters.

### Usage

```bash
# Batch mode - execute single command
./script/aether.sh [options] <command>

# REPL mode - interactive shell
./script/aether.sh [options]
```

### Options

| Option | Description | Default |
|--------|-------------|---------|
| `-c, --connect <host:port>` | Node address to connect to | `localhost:8080` |
| `--config <path>` | Path to aether.toml config file | |
| `-k, --api-key <key>` | API key for authenticated access | `AETHER_API_KEY` env |
| `-h, --help` | Show help | |
| `-V, --version` | Show version | |

When `--config` is specified, the CLI reads the management port from the config file. The `--connect` option takes precedence if both are provided.

### Authentication

When connecting to a secured cluster, provide an API key:

```bash
# Via command-line flag
aether --api-key mykey123 status

# Via environment variable
export AETHER_API_KEY=mykey123
aether status
```

The CLI will display user-friendly error messages for authentication and authorization failures:
- `Authentication required` (401) — API key not provided
- `Access denied` (403) — Invalid API key or insufficient role for the requested operation

### Authorization Roles

API keys can be assigned an authorization role that restricts which operations the CLI can perform. Roles are configured in `aether.toml`:

```toml
[app-http.api-keys.my-admin-key]
name = "cluster-admin"
roles = ["admin"]
authorization_role = "ADMIN"

[app-http.api-keys.my-viewer-key]
name = "monitoring"
roles = ["service"]
authorization_role = "VIEWER"
```

| Role | CLI Access |
|------|-----------|
| **ADMIN** | All commands |
| **OPERATOR** | Status, scaling, drain, deploy from artifact, schema, updates, backup, config, alerts |
| **VIEWER** | Read-only commands: `status`, `nodes`, `slices`, `nodes slices`, `routes`, `nodes routes`, `metrics`, `events`, `health` |

When `authorization_role` is omitted, the key defaults to `ADMIN`. See [Management API - Authorization](management-api.md#authorization-rbac) for the full permission mapping.

### Commands

#### status

Show cluster status:

```bash
aether status
```

Output:
```
Cluster Status:
  Leader: node-1
  Nodes: 3
  Healthy: true
```

#### whoami

Show the authenticated principal, authorization role, and roles attached to the request. Useful for integration-test identity assertions and operator triage — confirms which API key (or anonymous viewer fallback) the management plane resolved for the caller.

```bash
aether whoami
```

Output (authenticated admin API key):
```json
{
  "principal": "api-key:ops-admin",
  "authorizationRole": "ADMIN",
  "roles": ["admin", "service"],
  "authenticated": true
}
```

Output (no API key supplied; anonymous viewer):
```json
{
  "principal": "anonymous",
  "authorizationRole": "VIEWER",
  "roles": [],
  "authenticated": false
}
```

Fields:
- `principal` — `api-key:<keyName>` / `user:<subject>` / `service:<name>` / `anonymous`.
- `authorizationRole` — `ADMIN`, `OPERATOR`, or `VIEWER`.
- `roles` — sorted, lower-case role values (e.g. `admin`, `service`, `user`).
- `authenticated` — `false` for the anonymous viewer fallback.

#### nodes

List cluster nodes:

```bash
aether nodes
```

Output:
```
Nodes:
  node-1 (leader)  localhost:8091  ACTIVE
  node-2           localhost:8092  ACTIVE
  node-3           localhost:8093  ACTIVE
```

#### nodes resolve

Resolve a single node to its cluster-transport `host:port` and probe its reachability. Wraps
`GET /api/nodes/endpoint/{id}`. The address is printed regardless of reachability; the exit code
reports the probe result, so the command doubles as a connectivity check in scripts.

```bash
aether nodes resolve node-2
```

Arguments:

| Name | Description |
|------|-------------|
| `<nodeId>` | Node identifier to resolve |

Output:
```
node-2  10.0.0.8:7100  reachable
```

Exit code: `0` when the node is reachable, `1` when it is not (the address is still printed).
Use `--format value --field address` to extract just the `host:port` for piping.

#### nodes live

Show the unified live-node view — each known node's transport address, role, SWIM liveness, and
reported work-state in one table. Wraps `GET /api/nodes/live` (served from any core node).

```bash
aether nodes live
```

Options:

| Flag | Description |
|------|-------------|
| `--only-alive` | Restrict output to nodes with `swimAlive=true`; recomputes `liveCount` and zeroes `zombieCount`. |

Output:
```
Live nodes (live: 2, zombie: 1):
  node-1  10.0.0.7:7100  CORE  alive    READY
  node-2  10.0.0.8:7100  CORE  alive    READY
  node-3  -              CORE  dead     READY
```

A node listed with `dead` SWIM state and no address is a **zombie** — present in a stale
reported-state map but absent from the SWIM membership view and consensus topology. Use
`--format json` for the full structured document.

#### slices

Show all slices across the cluster with per-node instances, target counts, and version:

```bash
aether slices
```

Options:

| Flag | Description |
|------|-------------|
| `--state <STATE>` | Filter to slices/instances in this state (case-insensitive, e.g. `ACTIVE`, `LOADED`). When supplied, the response restricts each slice's `instances[]` to entries whose state matches, and drops slices with no matching instances. |

Output:
```
Slices (cluster-wide):
  org.example:order-processor:1.0.0    target: 3  min: 1  version: 1.0.0
    node-1  ACTIVE
    node-2  ACTIVE
    node-3  ACTIVE
  org.example:inventory:1.0.0          target: 2  min: 1  version: 1.0.0
    node-1  ACTIVE
    node-2  ACTIVE
```

Filtered example — only ACTIVE instances:

```bash
aether slices --state ACTIVE
```

Multi-state union via `+`:

```bash
aether slices --state LOADED+ACTIVE
```

#### slices status

Show the aggregate slice state breakdown across the cluster — counts by lifecycle state, per-slice rollup. Wraps `GET /api/slices/status`.

```bash
aether slices status
```

#### slices topology

Show the slice topology — the per-slice governor mapping across the cluster (which node currently owns each slice's governor assignment). Wraps `GET /api/slices/topology`.

```bash
aether slices topology
```

#### slices config

Show the effective configuration view for a loaded slice with per-key layer attribution. Wraps `GET /api/slices/config/{id}`.

```bash
aether slices config <artifact>
```

Arguments:

| Name | Description |
|------|-------------|
| `<artifact>` | Slice artifact coordinates (`group:artifact:version`) |

Each entry's `source` field reports which layer of the slice-composite (`slice.toml ⊕ KV-overlay ⊕ node.toml`) produced the resolved value — one of `slice.toml`, `KV`, or `node.toml`. Output is sorted alphabetically by key.

Example (JSON):

```bash
aether slices config org.example:my-slice:1.0.0 --format json
```

```json
{
  "sliceId": "org.example:my-slice:1.0.0",
  "entries": [
    {"key": "schedule.interval", "value": "30s", "source": "KV"},
    {"key": "topic.orders", "value": "orders.v1", "source": "slice.toml"}
  ]
}
```

#### nodes slices

Show slices loaded on the connected node (flat list of artifact names). Pass `[id]` for a specific node:

```bash
aether nodes slices
```

Output:
```
Slices (node):
  org.example:order-processor:1.0.0
  org.example:inventory:1.0.0
```

#### routes

Show HTTP routes across the cluster:

```bash
aether routes
```

Output:
```
Routes (cluster-wide):
  GET  /orders   [node-1, node-2]  security: none
  POST /orders   [node-1, node-2]  security: api-key
```

#### versions

Show the versioned slices deployed on the connected node and their API version registries (#198).
Lists each slice's `apiPrefix`, the header-mode detection knobs (`requireVersionHeader`,
`defaultVersion`), and per-version lifecycle metadata (`deprecated`, `sunset`, `defaultIfMissing`):

```bash
aether versions
```

Output:
```
{
  "slices": [
    {
      "slice": "org.example:orders:1.0.0",
      "apiPrefix": "/api/orders",
      "requireVersionHeader": false,
      "defaultVersion": 2,
      "versions": [
        { "version": 1, "deprecated": true, "sunset": "2026-12-31", "defaultIfMissing": false },
        { "version": 2, "deprecated": false, "defaultIfMissing": true }
      ]
    }
  ]
}
```

#### nodes routes

Show HTTP routes on the connected node. Pass `[id]` for a specific node:

```bash
aether nodes routes
```

Output:
```
Routes (node):
  GET  /orders   [node-1, node-2]  security: none
  POST /orders   [node-1, node-2]  security: api-key
```

#### metrics

Show cluster metrics:

```bash
aether metrics
```

Output:
```
Metrics:
  CPU: 45% (node-1), 38% (node-2), 42% (node-3)
  Memory: 234MB/512MB, 189MB/512MB, 201MB/512MB

  Deployments (last 10):
    org.example:order:1.0.0  node-1  1234ms  SUCCESS
    org.example:order:1.0.0  node-2  1156ms  SUCCESS
```

Variants (wrap `/api/metrics/*` REST routes):

```bash
# Prometheus-format scrape (text/plain exposition)
aether metrics prometheus

# Transport-layer metrics (node-level QUIC message/backpressure counters)
aether metrics transport

# Comprehensive snapshot: minute-aggregated node stats + LIVE consensus-load block (#674)
aether metrics comprehensive

# Derived/computed: trends, saturation, cluster health score
aether metrics derived

# Historical metrics over a time range
aether metrics history                  # default range (1h)
aether metrics history --range 15m      # 5m | 15m | 1h | 2h
aether metrics history --since 5m       # --since is an alias for --range

# Per-subsystem timeout-fired counters (one entry per [timeouts.*] section)
aether metrics timeouts
```

#### events

Show cluster events:

```bash
# All events
aether events

# Events since a specific time
aether events --since 2024-01-15T10:30:00Z
```

Output:
```
[
  {
    "timestamp": "2024-01-15T10:30:00Z",
    "type": "NODE_JOINED",
    "severity": "INFO",
    "summary": "Node node-2 joined cluster (now 3 nodes)",
    "details": {
      "nodeId": "node-2",
      "clusterSize": "3"
    }
  }
]
```

#### health

Health check:

```bash
aether health
```

#### nodes health

Per-node readiness/liveness check. Defaults to the connected node; pass `[id]` to query a specific
node (the request is forwarded by the management plane to that node and the response carries that
node's per-component readiness breakdown). Use `--liveness` to query `/health/live` instead of the
default `/health/ready`.

```bash
# Readiness on the connected node
aether nodes health

# Readiness on a specific node
aether nodes health node-2

# Liveness on a specific node
aether nodes health node-2 --liveness
```

Output mirrors the JSON shape of `GET /health/ready` (or `/health/live` with `--liveness`).
#### scale

Scale a blueprint-deployed slice. The slice must be part of an active blueprint.

```bash
aether scale <artifact> -n <instances> [-p <placement>]

# Scale instances only
aether scale org.example:order:1.0.0 -n 5

# Scale with placement strategy
aether scale org.example:order:1.0.0 -n 5 -p WORKER_PREFERRED
```

| Option | Description |
|--------|-------------|
| `-n, --instances` | Target number of instances (required) |
| `-p, --placement` | Placement strategy: `CORE_ONLY`, `WORKER_PREFERRED`, `WORKER_ONLY` (optional) |

> **Note:** Individual deploy/undeploy commands have been removed. Use `blueprint apply` and `blueprint delete` instead.

#### artifact

Artifact repository operations:

```bash
# Deploy JAR to repository
aether artifacts deploy <jar-path> -g <groupId> -a <artifactId> -v <version>

# Push blueprint and all its slices from local Maven repository to cluster
aether artifacts push <group:artifact:version>

# List artifacts
aether artifacts list

# List versions
aether artifacts versions <group:artifact>

# List versions as a structured JSON envelope (groupId / artifactId / versions[])
aether artifacts versions <group:artifact> --format json

# Show artifact metadata
aether artifacts info <group:artifact:version>

# Download an artifact file (writes to stdout or --out)
aether artifacts get <group:artifact:version> [--out=<file>] [--file=<filename>]

# Delete an artifact
aether artifacts delete <group:artifact:version>

# Show artifact storage metrics
aether artifacts metrics
```

The `push` command takes blueprint coordinates and automatically pushes the blueprint JAR
along with all referenced slice JARs. It reads `META-INF/blueprint.toml` from the blueprint
JAR (located at `~/.m2/repository/{group}/{artifact}/{version}/{artifact}-{version}-blueprint.jar`)
to discover slice references, then pushes each artifact to the cluster repository.

Examples:
```bash
# Deploy a JAR file directly
aether artifacts deploy target/my-slice.jar -g com.example -a my-slice -v 1.0.0

# Push blueprint + all slices from local Maven repository
aether artifacts push org.pragmatica.aether.example:url-shortener:1.0.0-rc1

# Example output:
# Pushing url-shortener blueprint (3 artifacts):
#   + org.pragmatica.aether.example:url-shortener:1.0.0-rc1:blueprint (65KB)
#   + org.pragmatica.aether.example:url-shortener-url-shortener:1.0.0-rc1 (34KB)
#   + org.pragmatica.aether.example:url-shortener-analytics:1.0.0-rc1 (32KB)
# All artifacts pushed successfully.

# View artifact details
aether artifacts info com.example:my-slice:1.0.0

# Download an artifact (default file: <artifactId>-<version>.jar)
aether artifacts get com.example:my-slice:1.0.0 --out=/tmp/my-slice.jar

# Stream artifact bytes to stdout (defaults to <artifactId>-<version>.jar)
aether artifacts get com.example:my-slice:1.0.0 > my-slice.jar

# Download a specific file from the artifact (e.g. sources jar)
aether artifacts get com.example:my-slice:1.0.0 --file=my-slice-1.0.0-sources.jar --out=src.jar

# Remove an artifact
aether artifacts delete com.example:my-slice:1.0.0
```

#### blueprint

Blueprint management:

```bash
# Apply a blueprint file
aether blueprints apply <file.toml>

# List all deployed blueprints
aether blueprints list [--format table|json]

# Get blueprint details
aether blueprints get <blueprintId> [--format table|json]

# Show deployment status of a blueprint
aether blueprints status <blueprintId> [--format table|json]

# Validate a blueprint file without deploying
aether blueprints validate <file.toml>

# Delete a blueprint
aether blueprints delete <blueprintId> [-f|--force]

# Deploy a blueprint from an artifact in the cluster repository
aether blueprints deploy <coords> [--wait] [--timeout <seconds>]

# Publish a blueprint already present in the artifact repository
aether blueprints publish <coords>

# Upload a blueprint JAR file and deploy it
aether blueprints upload <file> -g <groupId> -a <artifactId> -v <version>
```

Example blueprint file (`order-system.toml`):
```toml
id = "order-system:1.0.0"

[slices.order_processor]
artifact = "org.example:order-processor:1.0.0"
instances = 3

[slices.inventory]
artifact = "org.example:inventory:1.0.0"
instances = 2
```

Example workflow:
```bash
# Validate before deploying
aether blueprints validate order-system.toml

# Apply the blueprint
aether blueprints apply order-system.toml

# Check deployment status
aether blueprints status order-system:1.0.0

# List all blueprints
aether blueprints list

# Get details for a specific blueprint
aether blueprints get order-system:1.0.0

# Delete a blueprint (with force to skip confirmation)
aether blueprints delete order-system:1.0.0 -f

# Deploy from artifact coordinates
aether blueprints deploy org.example:my-app:1.0.0

# Publish a blueprint already present in the repository (POST /api/blueprints/publish)
aether blueprints publish org.example:my-app:1.0.0

# Upload a blueprint JAR and deploy it
aether blueprints upload my-app-1.0.0-blueprint.jar -g org.example -a my-app -v 1.0.0
```

**Single-migrator gate.** `deploy` and `publish` (the artifact-based paths) are refused with
HTTP 409 when the artifact declares migrations for a datasource that a **different** blueprint
already migrates; a refused request writes nothing. Republishing the *same* blueprint at a newer
version is fine (ownership matches on `group:artifact`, version stripped), and a blueprint that
declares no migrations is unaffected: only duplicate migration *ownership* is refused. `apply`
(raw TOML) is not subject to the gate, because migrations are read from the artifact jar's
`schema/` directory. The check runs at deploy time on the node owning the deployment task group
(requests are forwarded there), and it is a read-then-write, not an atomic compare-and-swap: two
publishes issued *concurrently* for the same unclaimed datasource can both get through. Sequential
publishes are reliably refused. See [`aether schema status`](#schema) for who currently owns a
datasource.

#### deploy

Unified deployment management for zero-downtime deployments. Supports immediate, canary, blue-green, and rolling strategies through a single command.

```bash
# Immediate deployment (default)
aether deploy <group:artifact:version>

# Canary deployment — progressive traffic shift with health monitoring
aether deploy <group:artifact:version> --canary [--traffic <percent>]

# Blue-green deployment — atomic switchover with instant rollback
aether deploy <group:artifact:version> --blue-green

# Rolling deployment — gradual instance replacement with traffic control
aether deploy <group:artifact:version> --rolling

# Common options (all strategies):
#   -n, --instances <n>      Number of new version instances (default: 1)
#   --error-rate <rate>      Max error rate threshold 0.0-1.0 (default: 0.01)
#   --latency <ms>           Max latency threshold in ms (default: 500)
#   --manual-approval        Require manual approval for routing changes
#   --cleanup <policy>       IMMEDIATE, GRACE_PERIOD, MANUAL (default: GRACE_PERIOD)

# List active deployments
aether deploy list

# Show deployment status
aether deploy status <deploymentId>

# Advance deployment (promote canary stage, switch blue-green, shift rolling traffic)
aether deploy promote <deploymentId>

# Rollback to previous version
aether deploy rollback <deploymentId>

# Finalize deployment (cleanup old version)
aether deploy complete <deploymentId>
```

| Subcommand | Description |
|------------|-------------|
| `<coords>` | Start immediate deployment |
| `<coords> --canary` | Start canary deployment |
| `<coords> --blue-green` | Start blue-green deployment |
| `<coords> --rolling` | Start rolling deployment |
| `list` | List active deployments |
| `status <id>` | Show deployment status |
| `promote <id>` | Advance deployment (next canary stage, switch traffic, shift routing) |
| `rollback <id>` | Rollback to previous version |
| `complete <id>` | Finalize and cleanup |

Example canary workflow:
```bash
# Start canary: deploy v2.0.0 with progressive traffic shift
aether deploy org.example:my-service:2.0.0 --canary -n 3

# Check deployment health
aether deploy status abc123

# Promote through stages: 1% -> 5% -> 25% -> 50% -> 100%
aether deploy promote abc123
aether deploy promote abc123
aether deploy promote abc123
aether deploy promote abc123

# Or rollback if issues detected
aether deploy rollback abc123
```

Example blue-green workflow:
```bash
# Deploy green version alongside current blue
aether deploy org.example:my-service:2.0.0 --blue-green -n 3

# Verify green is ready
aether deploy status bg-xyz

# Switch traffic atomically
aether deploy promote bg-xyz

# If issues, instant rollback
aether deploy rollback bg-xyz

# When satisfied, complete and clean up
aether deploy complete bg-xyz
```

Example rolling deployment workflow:
```bash
# Start rolling deployment: deploy 3 instances of v2.0.0 with 0% traffic
aether deploy org.example:order-processor:2.0.0 --rolling -n 3

# Gradually shift traffic
aether deploy promote abc123    # shift to next traffic stage

# Complete and cleanup old version
aether deploy complete abc123

# Or rollback if issues detected
aether deploy rollback abc123
```

#### ab-test

A/B testing management:

```bash
# Create an A/B test with variant definitions
aether ab-tests create -a <artifact> --variants <v1=ver1,v2=ver2>

# List active A/B tests
aether ab-tests list

# Show test status
aether ab-tests status <testId>

# Show per-variant metrics
aether ab-tests metrics <testId>

# Conclude test and promote winner
aether ab-tests conclude <testId> --winner <variant>
```

| Subcommand | Description |
|------------|-------------|
| `create -a <artifact> --variants <v1=ver1,v2=ver2>` | Create A/B test |
| `list` | List active tests |
| `status <testId>` | Show test status |
| `metrics <testId>` | Show per-variant metrics |
| `conclude <testId> --winner <variant>` | Conclude test |

Example workflow:
```bash
# Create A/B test: 50/50 split between v1.0.0 and v2.0.0
aether ab-tests create -a org.example:my-service --variants control=1.0.0,experiment=2.0.0

# Monitor per-variant metrics
aether ab-tests metrics ab-001

# Conclude test and promote winner
aether ab-tests conclude ab-001 --winner experiment
```

#### invocation-metrics

View per-method invocation metrics:

```bash
# List all metrics
aether invocation-metrics list

# Show slow invocations
aether invocation-metrics slow

# Show threshold strategy
aether invocation-metrics strategy              # Show current

# Note: Strategy changes via API are not currently supported at runtime.
# The following commands will return an error:
# aether invocation-metrics strategy fixed 100
# aether invocation-metrics strategy adaptive 10 1000
```

#### controller

Manage the cluster controller:

```bash
# Show current configuration
aether controller config

# Update thresholds
aether controller config --cpu-up 0.8 --cpu-down 0.3

# Show controller status
aether controller status

# Show per-slice scaling decision snapshot (outcome, guard, load factor, instance arithmetic)
# plus cluster-average CPU as node-capacity context
aether controller decisions

# Force evaluation cycle
aether controller evaluate
```

#### alerts

Manage cluster alerts:

```bash
# List all alerts
aether alerts list

# Show active alerts only
aether alerts active

# Show alert history
aether alerts history

# Clear all active alerts
aether alerts clear

# Inject a synthetic alert (operator-driven; visible via 'aether alerts list')
aether alerts inject \
    --name test-alert \
    --severity WARNING \
    --message "synthetic alert from operator" \
    [--metric test.integration.counter] \
    [--value 42.0]
```

The `inject` subcommand inserts a synthetic alert entry directly, bypassing threshold evaluation. The entry is visible in `aether alerts list` immediately and is also written to alert history with status `INJECTED`. Used by integration tests and operator tooling when no threshold-driven path can produce the alert under test. `--name`, `--severity` (one of `INFO`, `WARNING`, `CRITICAL`), and `--message` are required; `--metric` and `--value` are optional context fields.

#### thresholds

Manage alert thresholds:

```bash
# List all thresholds
aether thresholds list

# Set a threshold
aether thresholds set cpu -w 0.7 -c 0.9

# Remove a threshold
aether thresholds remove cpu
```

#### aspects

Manage dynamic aspects on slice methods:

```bash
# List all configured aspects
aether aspects list

# Set aspect mode on a method
aether aspects set org.example:my-slice:1.0.0#processOrder LOG_AND_METRICS

# Remove aspect configuration
aether aspects remove org.example:my-slice:1.0.0#processOrder
```

Available modes: `NONE`, `LOG`, `METRICS`, `LOG_AND_METRICS`

#### traces

View distributed invocation traces:

```bash
# List recent traces
aether traces list [--limit N] [--method METHOD] [--status SUCCESS|FAILURE]

# Get traces for a specific request
aether traces get <requestId>

# Show trace statistics
aether traces stats

# Inject a synthetic trace entry (operator-driven; visible via 'aether traces list')
aether traces inject \
    --operation processOrder \
    [--duration-ms 123] \
    [--depth 2] \
    [--request-id req-abc-123] \
    [--trace-id trace-xyz-789]
```

The `inject` subcommand inserts a synthetic trace entry directly into the node-local trace store, bypassing the runtime invocation pipeline. The entry is visible in `aether traces list` immediately. Used by integration tests and operator tooling when no deterministic invocation path can produce a trace under test. `--operation` is required; `--duration-ms` defaults to `10`, `--depth` defaults to `0`. `--request-id` and `--trace-id` are independently optional — a UUID is generated when both are omitted; if only `--trace-id` is given, it fills the `requestId` slot.

#### observability

Manage runtime observability. Two related surfaces share one config store (#277): **depth**
(the logging-ladder threshold) and **config** (the per-injection-point facet snapshot).

```bash
# List all depth overrides
aether observability depth

# Set depth threshold for a method
aether observability depth-set <artifact#method> <threshold>

# Remove depth override
aether observability depth-remove <artifact#method>
```

`depth-set` **materializes** a method-scope config: on an unconfigured method it pins the
baseline-equivalent facets (logging + metrics + tracing on, spans off) with the new depth — so
setting a depth never darkens an injection point, it only changes the logging-ladder threshold.

Example:
```bash
# Set depth threshold to 3 for a specific method
aether observability depth-set org.example:order-processor:1.0.0#processOrder 3

# Check configured overrides
aether observability depth

# Remove override
aether observability depth-remove org.example:order-processor:1.0.0#processOrder
```

Per-injection-point facet control. Each injection point resolves to an effective state —
`baseline` (no config; ambient facets run), `configured` (only the toggled facets run), or
`darkened` (explicit all-off = identity). Scope hierarchy: method → artifact (`*` method) →
global (`*` artifact + `*` method) → baseline; nearest scope wins whole.

```bash
# List every injection point's effective state (baseline|configured|darkened) + invocation counts
aether observability config

# Show the effective state for one artifact/method (use * for the artifact or global scope)
aether observability config-get <artifact> <method>

# Set a config snapshot for a scope (absent facet flags = off; --depth default 1)
aether observability config-set <artifact|*> <method|*> [--logging] [--metrics] [--tracing] [--spans] [--depth N]

# Remove the config at a scope (falls back per hierarchy)
aether observability config-remove <artifact|*> <method|*>
```

Example:
```bash
# Enable logging + metrics for one method at depth 2
aether observability config-set org.example:order-processor processOrder --logging --metrics --depth 2

# Darken (identity) every injection point of an artifact
aether observability config-set org.example:order-processor '*'

# Darken the whole cluster (explicit all-off at the global scope)
aether observability config-set '*' '*'

# Fall back to baseline for one method
aether observability config-remove org.example:order-processor processOrder
```

#### config

Manage dynamic configuration overrides:

```bash
# Show all configuration (base + overrides merged)
aether config list

# Show only dynamic overrides from KV store
aether config overrides

# Set a cluster-wide override
aether config set database.pool.max_size 20

# Set a node-specific override
aether config set server.port 9090 --node node-2

# Remove a cluster-wide override (base value restored)
aether config remove database.pool.max_size

# Remove a node-specific override
aether config remove server.port --node node-2
```

#### logging

Manage runtime log levels:

```bash
# List all runtime-configured log level overrides
aether logging list

# Set log level for a specific logger
aether logging set <logger> <level>

# Reset logger to configuration default
aether logging reset <logger>
```

Available levels: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `FATAL`, `OFF`

Example workflow:
```bash
# Enable debug logging for a package
aether logging set org.pragmatica.aether.node DEBUG

# Check active overrides
aether logging list

# Reset to default
aether logging reset org.pragmatica.aether.node
```

### REPL Mode

Start interactive mode by omitting the command:

```bash
./script/aether.sh --connect localhost:8080

Aether v0.20.0 - Connected to localhost:8080
Type 'help' for available commands, 'exit' to quit.

aether> status
Cluster Status:
  Leader: node-1
  Nodes: 3
  Healthy: true

aether> nodes
...

aether> exit
```

### Examples

```bash
# Check cluster status
./script/aether.sh status

# Connect to specific node
./script/aether.sh --connect node1.example.com:8080 status

# Scale a slice to 5 instances
./script/aether.sh scale org.example:my-slice:1.0.0 -n 5

# Apply a blueprint
./script/aether.sh blueprint apply order-system.toml

# Interactive mode
./script/aether.sh --connect localhost:8080
```

---

## aether-node: Cluster Node

Run an Aether cluster node.

### Usage

```bash
./script/aether-node.sh [options]
```

### Options

| Option | Description | Default |
|--------|-------------|---------|
| `--config=<path>` | Path to aether.toml config file | |
| `--node-id=<id>` | Node identifier | Random UUID |
| `--port=<port>` | Cluster port | 8090 |
| `--management-port=<port>` | Management API port | 8080 |
| `--peers=<list>` | Comma-separated peer addresses | Self only |

Command-line options override values from the config file.

### Environment Variables

| Variable | Description |
|----------|-------------|
| `NODE_ID` | Node identifier |
| `CLUSTER_PORT` | Cluster communication port |
| `MANAGEMENT_PORT` | Management API port |
| `CLUSTER_PEERS` | Comma-separated peer addresses |

### Peer Address Format

```
host:port           # Auto-generate node ID from address
nodeId:host:port    # Explicit node ID
```

### Examples

```bash
# Start single node (standalone)
./script/aether-node.sh

# Start node with specific ID and port
./script/aether-node.sh --node-id=node-1 --port=8091

# Start node and join cluster
./script/aether-node.sh \
  --node-id=node-2 \
  --port=8092 \
  --peers=localhost:8091,localhost:8092
```

### Starting a 3-Node Cluster

Run each command in a separate terminal:

```bash
# Terminal 1
./script/aether-node.sh \
  --node-id=node-1 \
  --port=8091 \
  --peers=localhost:8091,localhost:8092,localhost:8093

# Terminal 2
./script/aether-node.sh \
  --node-id=node-2 \
  --port=8092 \
  --peers=localhost:8091,localhost:8092,localhost:8093

# Terminal 3
./script/aether-node.sh \
  --node-id=node-3 \
  --port=8093 \
  --peers=localhost:8091,localhost:8092,localhost:8093
```

---

## aether-forge: Testing Simulator

Standalone cluster simulator with visual dashboard for load and chaos testing.

### Usage

```bash
./script/aether-forge.sh
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `FORGE_PORT` | Dashboard HTTP port | 8888 |
| `CLUSTER_SIZE` | Number of simulated nodes | 5 |
| `LOAD_RATE` | Initial requests per second | 1000 |

### Examples

```bash
# Start with defaults
./script/aether-forge.sh

# Custom cluster size
CLUSTER_SIZE=10 ./script/aether-forge.sh

# Custom load rate
LOAD_RATE=5000 ./script/aether-forge.sh

# Custom port
FORGE_PORT=9999 ./script/aether-forge.sh

# All options
FORGE_PORT=9999 CLUSTER_SIZE=10 LOAD_RATE=5000 ./script/aether-forge.sh
```

### Dashboard

After starting, open the dashboard in your browser:

```
http://localhost:8888
```

The dashboard provides:
- Real-time cluster visualization
- Load generation controls
- Chaos injection (kill nodes, network partitions)
- Metrics monitoring

### REST API

Forge exposes a REST API for automation:

```bash
# Get cluster status
curl http://localhost:8888/api/cluster

# Get metrics
curl http://localhost:8888/api/metrics

# Kill a node
curl -X POST http://localhost:8888/api/chaos/kill-node/node-3

# Set load rate
curl -X POST http://localhost:8888/api/load/rate/500
```

#### node lifecycle

Manage node lifecycle states:

```bash
# List all node lifecycle states
aether nodes lifecycle

# Filter the list to a single state (case-insensitive)
aether nodes lifecycle --state READY

# Multi-state union via `+`
aether nodes lifecycle --state READY+SYNCING

# Get lifecycle state for a specific node (--state ignored when [id] is supplied)
aether nodes lifecycle <nodeId>

# Drain a node (READY → DRAINING via the membership-v2 DRAIN-command heartbeat;
# the target self-drains, finishing in-flight requests, respecting disruption budget)
aether nodes drain <nodeId>

# Shut down a node (self-drain then halt via the DRAIN-command heartbeat; CTM
# grace-terminate backstop reaps the container)
aether nodes shutdown <nodeId>

# Promote a node to a new role (CORE or WORKER) via consensus
aether nodes promote <nodeId> --role WORKER
aether nodes promote <nodeId> --role CORE
```

Example workflow:
```bash
# Check current lifecycle states
aether nodes lifecycle

# Drain a node before maintenance
aether nodes drain node-2

# Verify it's draining
aether nodes lifecycle node-2

# Initiate shutdown
aether nodes shutdown node-3

# Promote node-4 to a WORKER role at runtime (CORE → WORKER); reverse with --role CORE
aether nodes promote node-4 --role WORKER
```

#### workers

Inspect worker nodes:

```bash
# List worker nodes and the community each belongs to
aether workers list
```

`workers list` reads the roster from committed consensus state (the per-community governor
announcements). Each row carries the worker's node id, its community, that community's governor, and
whether the worker IS the governor. Workers belonging to a dissolved community are omitted; a cluster
running no workers reports an empty list.

> **Removed in #525:** `aether workers health` and `aether workers endpoints`. Neither could ever be
> answered — workers publish only their community roster to consensus, so no per-worker health fact
> and no per-worker endpoint is replicated for the leader to report. The underlying routes remain
> declared and return an honest `501 Not Implemented`. Use `aether cluster membership` for per-node
> SWIM state and `aether routes` for the cluster HTTP route table.

#### scheduled-tasks

Manage scheduled tasks:

```bash
# List all scheduled tasks with active timer count
aether scheduled-tasks
aether scheduled-tasks list

# Get scheduled tasks filtered by config section
aether scheduled-tasks get <configSection>

# Pause a scheduled task
aether scheduled-tasks pause <configSection> <artifact> <method>

# Resume a paused scheduled task
aether scheduled-tasks resume <configSection> <artifact> <method>

# Manually trigger a scheduled task
aether scheduled-tasks trigger <configSection> <artifact> <method>

# Synchronously fire a task and advance its lastExecutionTime (dev-mode only)
aether scheduled-tasks inject \
    --section <configSection> \
    --artifact <artifact> \
    --method <method>

# Surface per-node execution attribution for a scheduled task (P-NEW-H)
aether scheduled-tasks executions-by-node <configSection> <artifact> <method>
```

Example:
```bash
# Show all scheduled tasks
aether scheduled-tasks list

# Get tasks for a specific schedule
aether scheduled-tasks get scheduling.cleanup

# Pause a task
aether scheduled-tasks pause scheduling.cleanup com.example:my-slice:1.0.0 cleanup

# Resume a paused task
aether scheduled-tasks resume scheduling.cleanup com.example:my-slice:1.0.0 cleanup

# Manually trigger a task
aether scheduled-tasks trigger scheduling.cleanup com.example:my-slice:1.0.0 cleanup

# Synchronously inject a task execution (dev-mode only — requires AETHER_INSECURE_DEV_MODE=true
# on the target node). Returns previousExecutionMs + currentExecutionMs so integration tests
# can assert strict monotonic advancement. Unblocks 08-resources scheduled-task assertions
# (RC1-blocker #16 in aether/docs/.internal/audits/integration-test-audit-2026-05-21.md §2.2).
aether scheduled-tasks inject \
    --section scheduling.cleanup \
    --artifact com.example:my-slice:1.0.0 \
    --method cleanup
```

> Dev-mode precondition: a node with operator-provided TLS certificates refuses to start in dev-mode, so `scheduled-tasks inject` is never reachable on a node configured with real TLS.

---

### backup

Manage cluster backups. Two surfaces are available: the **singular** `aether backup` parent
with verb-style subcommands (`create`, `restore`, `list`) introduced for operator-facing
workflows in P-NEW-C, and the legacy **plural** `aether backups` parent with the original
`trigger`/`list`/`restore` subcommands. Both call the same REST routes
(`POST /api/backups`, `POST /api/backups/restore`, `GET /api/backups`) — pick whichever
reads more naturally for your scripts.

```bash
# Singular surface (P-NEW-C, recommended for new scripts)
aether backup create                   # create a new backup (synchronous)
aether backup create --wait            # create + poll /api/backups until the new entry appears
aether backup create --wait --timeout 120
aether backup restore <commit-id>      # restore from a specific backup commit (prompts for confirmation)
aether backup restore <commit-id> --yes  # skip confirmation (required in non-interactive shells)
aether backup list                     # list available backups

# Plural surface (legacy alias, identical routes)
aether backups trigger
aether backups list
aether backups restore <commit-id>
```

#### `aether backup` subcommands

| Subcommand | Description |
|------------|-------------|
| `create [--wait] [--timeout N]` | Create a new backup (`POST /api/backups`). With `--wait`, polls `GET /api/backups` until the new entry appears or `--timeout` (default 60s) elapses. |
| `restore <commit> [--yes\|--force]` | Restore the cluster KV-Store from the named backup commit (`POST /api/backups/restore`). Destructive — overwrites current state, so it prompts for confirmation; `--yes`/`--force` skips the prompt (required in non-interactive shells). |
| `list` | List available backups (`GET /api/backups`). |

#### `aether backups` subcommands (legacy)

| Subcommand | Description |
|------------|-------------|
| `trigger` | Trigger a manual backup |
| `list` | List available backups |
| `restore <commit>` | Restore from backup |

---

### schema

Manage datasource schema migrations.

```bash
# Show schema status for all datasources
aether schema status

# Show schema status for a specific datasource
aether schema status <datasource>

# Show migration history for a datasource
aether schema history <datasource>

# Trigger manual migration
aether schema migrate <datasource>

# Undo migrations to a target version
aether schema undo <datasource> -v <version>

# Retry a failed migration
aether schema retry <datasource>

# Baseline a datasource at a version
aether schema baseline <datasource> -v <version>
```

| Subcommand | Description |
|------------|-------------|
| `status [datasource]` | Show schema status (all or specific) |
| `history <datasource>` | Show migration history |
| `migrate <datasource>` | Trigger manual migration (refused with `409` if the record is COMPLETED and already serving, or already PENDING) |
| `undo <datasource> -v N` | Undo to target version |
| `retry <datasource>` | Retry a failed migration (clears the activation hold) |
| `baseline <datasource> -v N` | Baseline at version |

#### `aether schema status` output

`status` and `history` render as a table under the default `--format table`, and honor
`--format json`, `--format csv`, and `--format value --field <path>` like every other query
command.

| Column | JSON field | Meaning |
|--------|-----------|---------|
| `DATASOURCE` | `datasource` | Datasource name (cluster-global, not per-blueprint) |
| `STATUS` | `status` | `PENDING`, `MIGRATING`, `COMPLETED`, `FAILED` |
| `HELD SLICES` | `heldSlices` | Slice artifacts withheld from activation by this record (#760) — always empty for `COMPLETED` |
| `VERSION` | `currentVersion` | Highest version recorded |
| `LAST MIGRATION` | `lastMigration` | Filename of the last migration recorded |
| `OWNING BLUEPRINT` | `owningBlueprint` | Blueprint that declared the migrations — **whose slices this record holds** while `status` is not `COMPLETED` |

```bash
# Just the owning blueprint of one datasource
aether schema status orders_db --format value --field owningBlueprint

# Machine-readable sweep of every datasource
aether schema status --format csv
```

#### Recovering from a FAILED activation hold

A slice is withheld from activation **if and only if its own blueprint owns a datasource whose
migration is in `PENDING`, `MIGRATING` or `FAILED`**. A failed or in-flight migration owned by any
*other* blueprint does not affect it. `COMPLETED` is the only status that releases activation.

So when a blueprint's slices sit in `LOADED` and never activate, the `OWNING BLUEPRINT` column is
the diagnostic: find the row whose owner is the stuck blueprint and whose status is not
`COMPLETED`. Rows owned by other blueprints are irrelevant no matter how broken they look.

```bash
# 1. Find the record that is holding this blueprint
aether schema status

# 2. Recover — pick ONE:
aether schema retry orders_db              # FAILED -> PENDING -> COMPLETED (re-runs the migration)
aether schema baseline orders_db -v 3      # -> COMPLETED (marks V001..V003 applied WITHOUT running them)
aether blueprints deploy org.example:my-app:1.0.1   # redeploy the owning blueprint

# 3. Confirm the hold is gone
aether schema status orders_db
```

`retry` applies to a record in `FAILED` **or `PENDING`** (#724 widened the guard — a migration
that never dispatched has no other lever short of retry or a redeploy); against `MIGRATING` or
`COMPLETED` it fails with `409 Conflict` and ``Schema for datasource '<name>' is not in FAILED
state (currently <STATUS>) — retry applies to FAILED or PENDING migrations only``, naming the
status it actually observed. `baseline` requires an
existing record — it inherits that record's owning blueprint rather than inventing one, so
baselining a datasource that has never been published fails with `404 Not Found` and
``Schema status not found for datasource '<name>'``.

`migrate` similarly refuses (#760 review BLOCKING 1) when the record is `COMPLETED` **and** the
owning blueprint has at least one slice instance already `ACTIVE` — re-arming to `MIGRATING` would
hold the next slice reaching `LOADED` (scale-up, rolling redeploy, a rejoining node) with no
automatic recovery, since only a `PENDING` record's Put actually dispatches a migration run. The
409 names the datasource and how many active instances it is serving; `baseline` or `undo` first
if a genuine re-migration is intended. A COMPLETED record with zero live ACTIVE slices still goes
through unchanged.

`migrate` also refuses (#760/#724 review round 2 item l) when the record is already `PENDING`: a
fresh `PENDING` Put is what dispatches a migration run, so a second `migrate` call has no dispatch
effect of its own to join — it neither adds nor replaces any in-flight tracking (a `PENDING` record
can otherwise sit with zero in-flight tracking at all, which is exactly the stuck state #724 fixed)
and would only strand the record with no automatic clearing path. The 409 names the datasource.
`aether schema retry <datasource>` is the lever for a `PENDING` record and re-triggers dispatch,
not only once the record has since failed (it accepts `PENDING`, unlike `migrate` — see above).
Dispatch requires this node's deployment FSM to be the elected leader and `Active` (only that
state's handler consumes the consensus Put `retry` writes); given that, it still no-ops when either
guard `SchemaOrchestratorService.acquireLock` checks is already held — the local per-JVM fence or the
cross-node consensus lock — so a retry racing an in-progress attempt for the same datasource returns
`LOCK_HELD` rather than starting a second run. `migrate` itself does not re-trigger dispatch
`[mechanism: SchemaRoutes.guardReactivation
switches on the observed status before any orchestrator effect and returns SchemaAlreadyPending for
PENDING without writing MIGRATING]`.

The cluster leader also writes a `SCHEMA_ACTIVATION_BLOCKED` audit entry when it observes a
`FAILED` record, naming the datasource, the owning blueprint, and the held slices. The same held
slices render in the `HELD SLICES` column of `aether schema status` output (#760/#724 review round
2 item k), without waiting for that audit entry — before this fix the table stopped at `OWNING
BLUEPRINT` and `heldSlices` was reachable only via `--format json` or `--field heldSlices`.

> **Known limit — the gate scopes by migration *ownership*, not by *usage*.** A blueprint that
> reads or writes a datasource **without declaring migrations for it** is never held when that
> datasource's owner fails. `aether schema status` tells you who migrates a datasource, not who
> uses it.

Related: because datasource names are cluster-global (the default `schema/V001__*.sql` layout
names the datasource `database` for every blueprint), publishing a blueprint whose migrations claim
a datasource that a **different** blueprint already migrates is refused with HTTP 409 at deploy
time — see [`aether blueprints deploy` / `publish`](#blueprint) for the exact scope of that check.

Example:
```bash
# Check all datasource schemas
aether schema status

# Check a specific datasource
aether schema status orders_db

# Trigger migration for a datasource
aether schema migrate orders_db

# Undo to version 2
aether schema undo orders_db -v 2

# Retry a failed migration
aether schema retry orders_db

# Baseline at version 3
aether schema baseline orders_db -v 3
```

---

## Stream Management

> **`status`/`publish`/`read`/`delete` take an address, not just a name.** These now dispatch to
> catalog-form `(namespace, stream, version)` routes (management-api-versioning-spec.md §3.2, #742).
> A bare name (no colon) defaults to `system:<name>:1.0.0`, preserving the original single-name UX;
> a `namespace:stream:version` triple addresses any stream. `list`, `consumers`, and `create` are
> unaffected and remain on their existing flat addressing.

### `aether streams list`

List all event streams with metadata.

```bash
aether streams list
```

### `aether streams status <name-or-address>`

Show detailed stream info including per-partition details. Bare name defaults to
`system:<name>:1.0.0`; a `namespace:stream:version` address targets any stream. Wraps
`GET /api/v1/streams/{namespace}/{stream}/{version}/info`.

```bash
aether streams status my-events                  # -> system:my-events:1.0.0
aether streams status orders:order-events:1.0.0
```

### `aether streams consumers`

Show the declarative `[streams.X]` consumers this node knows about — slice methods the runtime invokes
for every event on the partitions assigned to them.

```bash
aether streams consumers
```

Per-node: the declarations are cluster-wide, but `attachedSubscriptions` and `assignedPartitions`
describe the node you asked. `partitionAssignments` names which node consumes each partition and which
owns it — reads are forwarded to the owner whenever those differ — and is computed identically on every
node, so one call answers "who consumes partition 3". `unassignedPartitions` is the gap worth alerting
on: partitions no node can consume because the declaring slice is `ACTIVE` nowhere.

See [Management API — Declarative Stream Consumers](management-api.md#declarative-stream-consumers).

### `aether streams publish <name-or-address> <message>`

Publish a text message to a stream. The message is base64-encoded automatically. Bare name
defaults to `system:<name>:1.0.0`; a `namespace:stream:version` address targets any stream.
Wraps `POST /api/v1/streams/{namespace}/{stream}/{version}/publish`.

```bash
aether streams publish my-events "Hello, world!"
aether streams publish orders:order-events:1.0.0 "Hello, world!"
```

### `aether streams read <name-or-address> <partition>`

Read events from a specific partition of a stream. Bare name defaults to `system:<name>:1.0.0`;
a `namespace:stream:version` address targets any stream. Optional `--since <offset>` selects the
starting offset (maps to `?from=`), and `--limit <N>` caps the number of events returned (maps to
`?max=`). Wraps `GET /api/v1/streams/{namespace}/{stream}/{version}/read/{partition}`.

```bash
aether streams read my-events 0                  # -> system:my-events:1.0.0
aether streams read orders:order-events:1.0.0 0
aether streams read my-events 0 --since 100 --limit 50
```

### `aether streams create <name> [--partitions N]`

Create a new event stream. The optional `--partitions N` flag overrides the server-side
default partition count. The underlying `POST /api/streams` route is idempotent — calling
`create` on an existing stream returns the existing metadata (status `exists`).

```bash
aether streams create my-events
aether streams create my-events --partitions 8
```

### `aether streams delete <name-or-address> [--force]`

Delete an event stream. Prompts for confirmation unless `--force` (`-f`) is supplied. Bare
name defaults to `system:<name>:1.0.0`; a `namespace:stream:version` address targets any
stream. Wraps `DELETE /api/v1/streams/{namespace}/{stream}/{version}`.

```bash
aether streams delete my-events
aether streams delete orders:order-events:1.0.0 --force
```

### `aether streams consumer-group join <group> <stream> --consumer-id <id> [--partitions N]`

Register a consumer in a consumer group on the given stream. `--consumer-id` is the unique
identifier of the consumer within the group. `--partitions N` (default `1`) is the partition
count the consumer expects to be assigned to.

```bash
aether streams consumer-group join orders-workers orders --consumer-id worker-1 --partitions 4
```

### `aether streams consumer-group leave <group> <stream> --consumer-id <id>`

Remove a consumer from a consumer group on the given stream.

```bash
aether streams consumer-group leave orders-workers orders --consumer-id worker-1
```

### `aether streams consumer-group status <group> [<stream>]`

Show the per-stream consumer assignments for the given group. The optional `<stream>`
positional is informational — the server-side `/api/streams/groups/{id}` returns the full
multi-stream group status.

```bash
aether streams consumer-group status orders-workers
aether streams consumer-group status orders-workers orders
```

### Examples

```bash
# List all streams
aether streams list

# Check stream details
aether streams status user-events

# Publish a message
aether streams publish user-events "order_created:12345"

# Read events from partition 0, starting at offset 100, max 50 events
aether streams read user-events 0 --since 100 --limit 50
```

---

## Stream Namespaces (`aether stream`)

The `aether stream` command group (singular) operates on **namespaced** streams addressed by a
fully-qualified `namespace:stream:version` triple (version is `MAJOR.MINOR.PATCH`). It wraps the
namespaced `/api/streams/*` route surface. This is distinct from the legacy `aether streams`
(plural) commands above, which use flat single-name addressing.

Exit codes: `0` success, `1` error, `2` validation, `3` user-cancelled, `4` not-found,
`5` conflict, `6` gone.

### `aether stream list [--namespace <ns>]`

List all registered stream versions, optionally filtered to a single namespace.

```bash
aether stream list
aether stream list --namespace orders
```

### `aether stream show <namespace:stream:version>`

Show registry metadata for a specific stream version.

```bash
aether stream show orders:order-events:1.0.0
```

### `aether stream replicas <stream> <partition> [--local]`

Show per-node replica state for a stream partition — the replication/backfill-health sensor for the stream-replication class (#260/#261/#333). Renders a per-replica table (each replica's `STATE` — `SYNCING`/`CAUGHT_UP`/`LAGGING` — its `CONFIRMED` acked offset, and whether it is the partition's HRW owner) and surfaces the partition-level fields (`hrwOwner`, `servedByOwner`, `ownerHeadOffset`, `earliestRetainedOffset`) in `--format json`. Compare a `CAUGHT_UP` replica's `CONFIRMED` against `ownerHeadOffset` to spot the #333 write-idle residual (a replica that reports caught-up but lags the owner's true tail).

**`<stream>` means something different depending on `--local` (#753):**
- **Without `--local`** (default): `<stream>` must be a full `namespace:stream:version` catalog address — there is no bare-name-defaults-to-`system` convenience here (unlike `streams status/publish/read/delete`), because the raw engine key a `--local` query needs and the catalog address this path needs are two different shapes for a non-`system` stream, and silently guessing between them is worse than requiring the caller to say which one. Dispatches to the catalog-form `STREAM_REPLICAS` route.
- **With `--local`**: `<stream>` is the partition manager's raw *engine key* (`StreamManager#engineKey` — bare name for `system`-namespace streams, e.g. `cluster-events`; the full `namespace:stream:version` triple for any other namespace), passed through unparsed. Dispatches to `STREAM_REPLICAS_LOCAL`, keyed on that engine key rather than the catalog address.

**Owner authority:** the answering node's `ReplicaRegistry` holds the complete per-peer watermark view only when that node IS the partition's HRW owner (`servedByOwner: true`). By default the query is served from an arbitrary STREAMING-capable delegate and is owner-aware but **not** owner-forwarded — and because of that delegation, re-querying another port still lands on a delegate. Pass **`--local`** (#490) to make the ADDRESSED node answer from its OWN registry: point the CLI at the `hrwOwner` node's management port with `--local` to get the authoritative full set (`servedByOwner: true`), or sweep each node's port with `--local` to compare per-node views during failover diagnosis. Wraps `GET /api/v1/streams/{namespace}/{stream}/{version}/replicas/{partition}` (default) or `GET /api/v1/streams/{name}/{partition}/replicas-local` (`--local`).

```bash
aether stream replicas system:cluster-events:1.0.0 0

# Machine-readable (includes hrwOwner / servedByOwner / ownerHeadOffset)
aether stream replicas system:cluster-events:1.0.0 0 --format json

# Owner-authoritative view: address the hrwOwner node's management port + --local, engine key form (#490)
aether stream replicas cluster-events 0 --local
aether stream replicas orders:order-events:1.0.0 0 --local
```

Example output (table):
```
NODE      STATE      CONFIRMED  HRW-OWNER
core-1    CAUGHT_UP  255        yes
core-3    CAUGHT_UP  255        no
core-4    SYNCING    240        no
```

### `aether stream hydration`

Show per-node stream hydration state — the memory/placement observability sensor for placement-aware-stream-hydration (#265). Renders a per-stream table (each stream's `DECLARED` partition count, `RINGS` materialized locally, `DEFERRED` held-but-not-yet-materialized partitions, `FLOOR-BYTES` reserved, `OVER-CEIL` whether the committed config is over the per-stream partition ceiling, and the node's `OWNER`/`REPLICA`/`NONE` placement-role tally) and surfaces the per-node budget fields (`totalAllocatedBytes`, `maxTotalBytes`, `overBudget`, `deferredPartitions`), the partition-cap fields (`perStreamCeiling`, `clusterAggregateGuard`, `currentAggregatePartitionSlots`, `aggregateHeadroom`, `configOverCeilingStreams`), and the reshuffle-lifecycle fields (`releaseCandidates`, `releasedPartitionsSinceBoot`, `materializeQueueDepth` — increment 5) in `--format json`. Answered by the STREAMING-capable node from its own `StreamPartitionManager` — a PER-NODE view, not leader-forwarded.

Materialization is placement-gated: `RINGS` drops below `DECLARED` on non-replicas and `REPLICA`/`NONE` are non-zero off-owner. Per §6 a follower that cannot admit a held partition's floor NO LONGER over-subscribes — it holds the partition metadata-only and counts it under `DEFERRED` until budget frees, when the deferred-retry hook materializes it. Partition caps (§7, increment 4) are admission control: a create over the per-stream ceiling (`1024`) or the cluster aggregate guard (`100 × nodes × maxDeclaredReplicas`) is rejected pre-commit; a follower observing an over-ceiling committed config flags it under `OVER-CEIL` / `configOverCeilingStreams` (never rejecting — the budget machinery is the memory backstop). The reshuffle lifecycle (§5/§14.2, increment 5) releases a ring on confirmed role loss (catch-up-gated + flap-debounced, freeing memory + budget while keeping the WAL) — `releaseCandidates` counts partitions debouncing toward release, `releasedPartitionsSinceBoot` the running release total, and `materializeQueueDepth` the partitions paced behind the `reshuffle_concurrency = 2` slot limit (system streams drain first). This command is how that memory win, any budget pressure, cap headroom, and reshuffle pacing are observed. Wraps `GET /api/streams/hydration`.

```bash
aether stream hydration

# Machine-readable (includes totalAllocatedBytes / maxTotalBytes / overBudget / deferredPartitions,
# the partition-cap fields perStreamCeiling / clusterAggregateGuard / currentAggregatePartitionSlots
# / aggregateHeadroom / configOverCeilingStreams, and the reshuffle-lifecycle fields releaseCandidates
# / releasedPartitionsSinceBoot / materializeQueueDepth)
aether stream hydration --format json
```

Example output (table):
```
STREAM                        DECLARED  RINGS  DEFERRED  FLOOR-BYTES     OVER-CEIL  OWNER  REPLICA  NONE
orders                        4         4      0         5320704         false      4      0        0
system:cluster-events:1.0.0   1         1      0         2660352         false      1      0        0
```

### `aether stream tail <namespace:stream:version> [options]`

Tail events from a stream version. Tailing is **polling-based** (paginated GETs against
`/api/streams/events/...`); each event payload is printed on its own stdout line. Press Ctrl-C to
stop. A streaming SSE/WebSocket subscription is **deferred to issue #212**.

```bash
aether stream tail orders:order-events:1.0.0
aether stream tail orders:order-events:1.0.0 --from-offset 100 --max-events 200
aether stream tail orders:order-events:1.0.0 --no-follow   # one-shot drain, then exit
```

| Option | Description |
|--------|-------------|
| `--interval` | Polling interval in milliseconds (default `500`) |
| `--from-offset` | Initial offset to read from (default `0` — from beginning) |
| `--max-events` | Max events per poll page (default `100`, server-capped at `1000`) |
| `--follow` / `--no-follow` | Keep polling (default) vs. one-shot drain then exit |

### `aether stream delete <namespace:stream:version> [--force]`

Force-purge a specific stream version. Prompts for confirmation unless `--force` (`-f`) is given.
Writes to `system:*` streams are rejected by the server with `405`.

```bash
aether stream delete orders:order-events:1.0.0
aether stream delete orders:order-events:1.0.0 --force
```

### `aether stream group create <namespace:stream:version> <group> [--initial-position earliest|latest]`

Create a durable consumer group on a stream version.

```bash
aether stream group create orders:order-events:1.0.0 fulfillment
aether stream group create orders:order-events:1.0.0 fulfillment --initial-position earliest
```

### `aether stream group delete <namespace:stream:version> <group> [--force]`

Delete a durable consumer group; releases its reference on the stream version. Prompts for
confirmation unless `--force` (`-f`) is given.

```bash
aether stream group delete orders:order-events:1.0.0 fulfillment --force
```

---

## Pub/Sub Topics

Pub/sub topics share the streams' `namespace:topic:version` addressing model (the topic-flavored
view of the same `ResourceAddress` abstraction — see [Stream Namespaces](#stream-namespaces-aether-stream)).
A bare/legacy topic name has its namespace derived from the publishing slice's blueprint Maven
coordinates (`groupId.artifactId`) and its version defaulted to `1.0.0`; an explicit
`namespace:topic:version` declaration in slice config is accepted verbatim. The `system` namespace is
reserved for framework topics.

Unlike streams, pub/sub is an in-process, declaration-driven delivery mechanism: there is **no
`aether topic` CLI command group** and **no topic management HTTP route**. Operators inspect topic
wiring (publishers, subscribers, and their resolved `namespace:topic:version` addresses) through the
topology graph — `GET /api/slices/topology` and the dashboard topology view — where pub→sub edges
are matched on the resolved canonical address.

---

## Cluster Management

### `aether cluster init`

Generate a `cluster-config.toml` interactively (default) or in batch mode driven by flags. Used as the first step of cluster setup; the resulting file is consumed by `aether cluster bootstrap`.

```bash
# Interactive wizard (default)
aether cluster init --output cluster-config.toml

# Batch mode — driven by --target plus per-target flags
aether cluster init --target docker --name test-cluster --nodes 5 --output cluster-config.toml

# Strict non-interactive mode — fails fast if required flags are missing (P-NEW-G)
aether cluster init --non-interactive --name test-cluster --nodes 5 --output cluster-config.toml
```

| Option | Description |
|--------|-------------|
| `--output` | Output path (default `cluster-config.toml`) |
| `--force` | Overwrite existing output file |
| `--non-interactive` | Force non-interactive mode; default `--target=docker` if absent, fail fast on missing required flags (P-NEW-G, 2026-05-21). Required for CI/integration test usage (TC-07-J3). |
| `--name` | Cluster name (regex `^[a-z][a-z0-9-]{0,62}$`) |
| `--target` | Deployment target: `docker`, `ssh`, `cloud`, or `forge` |
| `--nodes` | Total node count (>= 3 for non-SSH targets) |
| `--hosts` | SSH hosts (ssh target only), comma-separated |
| `--ssh-user`, `--ssh-key`, `--ssh-port` | SSH credentials (ssh target only) |
| `--provider`, `--region`, `--instance-type`, `--credential-env` | Cloud target only |
| `--db-host`, `--db-port`, `--db-name`, `--db-user`, `--db-password-env` | Optional Postgres backing store |
| `--firewall` | Firewall preset: `standard`, `restrictive`, `open`, `custom` |
| `--admin-cidr`, `--internal-cidr` | Restrictive firewall preset only |
| `--tls`, `--tls-cert-env`, `--tls-key-env` | TLS mode: `auto` (default) or `env` |
| `--secret`, `--secret-env` | Cluster secret mode: `auto` (default) or `env` |

When `--non-interactive` is set without `--target`, the command applies `--target=docker` as the default. Missing required flags (e.g. `--nodes` for docker target) produce a `MissingField` failure and a non-zero exit code rather than dropping into prompts.

### `aether cluster scaffold`

Emit a deployment-manifest template with `aether.cluster` and `aether.node-id` labels pre-set. Operators get a working starting point that's correct-by-construction — no chance of forgetting to label containers, which would otherwise leave cross-cluster tooling unable to distinguish two clusters sharing infrastructure.

```bash
aether cluster scaffold --name <cluster-name> --format docker-compose [--nodes N] [--image IMG] \
                        [--mgmt-port-base 5150] [--app-port-base 8070] [--cluster-port 6000] > compose.yml
```

| Option | Description |
|--------|-------------|
| `--name` | Cluster name (regex `^[a-z][a-z0-9-]{0,62}$`) |
| `--format` | Output format. Currently `docker-compose` |
| `--nodes` | Compose-fixed node count (default 5) |
| `--image` | Container image (default `aether-node:local`) |
| `--mgmt-port-base` | Host port base for management API (default 5150) |
| `--app-port-base` | Host port base for application HTTP (default 8070) |
| `--cluster-port` | QUIC cluster transport port (default 6000) |

Example:
```bash
aether cluster scaffold --name us-prod --format docker-compose --nodes 5 > compose.yml
docker compose -f compose.yml up -d
```

The generated manifest:
- Sets `aether.cluster=<name>` on every service (matches what `DockerComputeProvider.buildRunCommand` sets on CTM-provisioned replacements)
- Sets `aether.node-id=node-N` on each compose-fixed service
- Uses `restart: "no"` per the CTM auto-heal contract (see `aether/docs/operators/deployment-recovery.md`)
- Provisions a per-cluster bridge network `aether-<name>-network`
- Emits `AETHER_CLUSTER_SECRET` as a required `${AETHER_CLUSTER_SECRET:?...}` shell reference, never
  a literal — `export AETHER_CLUSTER_SECRET=<your-secret>` before `docker compose up` (#684). A file
  generated before this fix instead has the value baked in as
  `AETHER_CLUSTER_SECRET: "change-me-cluster-secret"`; see `SECURITY.md`'s `cluster_secret` hygiene
  section for the migration steps and for what this fix does and does not close.

See `aether/docs/operators/multi-cluster-deployment.md` for the full labeling model.

### `aether cluster scale`

Scale one source and role of the cluster topology.

```bash
aether cluster scale [--source <name>] [--role <role>] --count <N>
```

| Option | Description |
|--------|-------------|
| `--source` | Source name. Omit it and the server infers the source, which works when exactly one source declares the role. |
| `--role` | `core`, `worker` or `spot`. Defaults to `core`. |
| `--count` | Target node count for this source and role. |
| `--yes`, `--force` | Skip interactive confirmation (required in non-interactive shells) |
| `-o json` | Output raw JSON (`-o=<format>`; `--json` never existed — corrected 2026-08-09) |

Examples:
```bash
# Single-source cluster — the source is unambiguous, so naming it is optional
aether cluster scale --role core --count 7

# Multi-source cluster — name the source that absorbs the change
aether cluster scale --source hetzner-eu --role core --count 7
aether cluster scale --source aws-us --role worker --count 12
```

Default output is the confirmation line only:
```
Scale successful.
```

Pass `-o json` for the counts the server applied:
```json
{
  "success": true,
  "source": "hetzner-eu",
  "role": "core",
  "previousCount": 5,
  "newCount": 7,
  "configVersion": 8
}
```

Omitting `--source` in a cluster where several sources carry the role is refused, listing the
candidates:

```
Role 'core' is declared by 2 sources (hetzner-eu, aws-us). Re-run naming one with --source.
```

A `(source, role)` the topology does not declare is also refused rather than created — otherwise a
mistyped source name would become a real provisioning target. Add the pair to the config and use
`aether cluster apply` instead.

Scaling a cluster with no stored config (e.g. right after a `docker compose down -v` volume wipe and
fresh bootstrap — #335) is refused the same way, not created from the scale request: a `--count`
alone has none of the cluster name, version, or deployment settings a config needs. The error names
the actual recovery:

```
No cluster configuration stored. A scale request cannot create one — it carries only
source/role/count, not the cluster name, version, or deployment settings a config requires. Run
'aether cluster bootstrap <aether-cluster.toml>' first, then retry 'aether cluster scale --role core
--count 7'.
```

**Quorum safety is validated by the server, not the CLI.** A per-source count is not the cluster
total: scaling one core source to 1 is legal when another source carries 2. Only the server holds
the whole topology, so only the server can do that arithmetic. It checks the resulting cluster-wide
core total (at least 3, odd, within `core.min`/`core.max`); worker and spot counts carry no quorum
constraint.

Scaling is destructive (a scale-down terminates nodes), so it prompts for confirmation in an
interactive shell. Pass `--yes` (or `--force`) to skip the prompt; a non-interactive shell without
`--yes` refuses to proceed.

### `aether cluster topology`

Show cluster topology with per-node details including role, health, hostname, and zone.

```bash
aether cluster topology
```

| Option | Description |
|--------|-------------|
| `--format` | Output format: `table` (default), `json`, `value`, `csv` |

The `ASSIGNED` column shows the CDM-assigned role (from the KV-Store `ActivationDirective`),
distinct from the `ROLE` (self-asserted descriptor) column. When they diverge — e.g. `ROLE=core`
but `ASSIGNED=WORKER` — the node was demoted by the controller and runs in observer mode.

Example:
```bash
aether cluster topology

# Output (table):
# NODE              ROLE        ASSIGNED    HEALTH        HOSTNAME              ZONE            ADDRESS
# node-1            ACTIVE      CORE        HEALTHY       aether-node-1                         aether-node-1:6000
# node-2            ACTIVE      CORE        HEALTHY       aether-node-2                         aether-node-2:6000
# lb-passive        PASSIVE     UNASSIGNED  HEALTHY       aether-lb                             0.0.0.0:7000
```

### `aether cluster topology circuit-breaker status`

Show the CTM (Cluster Topology Manager) provisioning circuit breaker state. The breaker trips after 3 consecutive provisioning failures and halts auto-heal until reset.

```bash
aether cluster topology circuit-breaker status
```

Example output:
```json
{"consecutiveFailures": 0, "trippedAt": 3, "nextAllowedMs": 0, "tripped": false}
```

### `aether cluster topology circuit-breaker reset`

Operator-triggered reset of the CTM provisioning circuit breaker. Use after fixing the underlying provisioning issue (provider credentials, network connectivity, capacity quota) when none of the auto-recovery triggers (`scale`, node-ready, phase NORMAL, leader handoff) have fired. Returns the prior consecutive-failure count.

```bash
aether cluster topology circuit-breaker reset
```

Example output:
```json
{"status": "reset", "priorFailureCount": 3}
```

### `aether cluster topology auto-heal status`

Show whether CTM auto-heal (deficit-driven replacement provisioning) is currently enabled. Operator-controlled gate, distinct from the failure-driven circuit breaker.

```bash
aether cluster topology auto-heal status
```

Example output:
```json
{"enabled": true}
```

### `aether cluster topology auto-heal disable`

Disable CTM auto-heal — `handleDeficit` becomes a no-op until re-enabled. Use during disruption-budget testing, planned maintenance windows, or scenarios where the cluster must not automatically rebuild after node loss. Already-in-flight provisioning attempts continue to completion.

```bash
aether cluster topology auto-heal disable
```

Example output:
```json
{"enabled": false, "previousState": true}
```

### `aether cluster topology auto-heal enable`

Re-enable CTM auto-heal. If a deficit is pending, the next reconcile picks it up immediately.

```bash
aether cluster topology auto-heal enable
```

Example output:
```json
{"enabled": true, "previousState": false}
```

### `aether cluster governors`

Show the per-slice governor assignment across the cluster — which node currently owns the governor role for each slice. Wraps `GET /api/cluster/governors`.

```bash
aether cluster governors
```

### `aether cluster provisioning`

Show leader provisioning diagnostics — why a core-membership deficit is or is not being filled (configured vs counted-core membership, effective capacity, deficit, the arm + reached-full-membership latches, quorum safety, deficit-run age, the precise suppression reason, the provisioning circuit-breaker state, and the most recent provisioning failure). Surfaced only on the leader that owns a Cluster Topology Manager; against any other node it returns a `leader: false` body with zeroed counters and an explanatory reason. Wraps `GET /api/cluster/provisioning`.

```bash
aether cluster provisioning

# Machine-readable
aether cluster provisioning --format json
```

| Option | Description |
|--------|-------------|
| `--format` | Output format: `table` (default), `json`, `value`, `csv` |

### `aether cluster membership`

Show the queried node's membership diagnostics — the responding node's authoritative membership-FSM lifecycle view plus its quorum-loss self-drain readiness. Renders a per-peer table (each tracked peer's FSM state, role, incarnation, and whether it is in the strict `Member`-only quorum set and the counted `Member`+`Suspect` set) followed by the summary counts (strict member count, quorum threshold, below-threshold flag, armed latch). Use to diagnose SWIM-under-concurrent-loss — per survivor, which peers are SUSPECT/DEAD and whether this node's self-drain window is armed and below threshold. **Per-node local view** (not leader-forwarded) — target a specific node (`-c <host>`) to read its view. Wraps `GET /api/cluster/membership`.

Since #590 the response also carries `coreAbsence` — the **community tier's** fence, alongside the core tier's. It answers "is this node about to dissolve because it has lost the core", with `remainingMs` counting down to the local dissolve. Like the quorum-loss summary fields, it lives at the response root, so it is shown by `--format json` rather than in the per-peer table:

```bash
# Is this node about to fence itself? (the field to watch during a suspected partition)
aether cluster membership -c <suspect-host> --format json | jq .coreAbsence
```

`coreAbsence.armed: false` means the node has never heard the core — cold-starting, **not** isolated. `fenced: true` means the local dissolve has already fired and the node has stopped serving; recovery is a re-join. Query the *suspect* node directly: a node losing the core is precisely the one whose view the leader cannot fetch for you.

```bash
aether cluster membership

# A specific survivor's view during a multi-core-loss window
aether cluster membership -c <host>

# Machine-readable
aether cluster membership --format json
```

| Option | Description |
|--------|-------------|
| `--format` | Output format: `table` (default), `json`, `value`, `csv` |

Example output (table):
```
NODE    STATE    ROLE  INCARNATION  STRICT-CORE  COUNTED
core-1  Member   core  1            yes          yes
core-3  Suspect  core  2            no           yes
core-4  Dead     core  2            no           no

strict=2  threshold=3  below=true  armed=true
```

### `aether cluster ownership`

Show the queried node's committed ownership + fence view (#345 item 1f) for a domain — for every partition/key the responding node has committed in that domain: the owner `NodeId`, the committed fence `Epoch`, the node's LOCAL per-domain epoch high-water, and whether the entry is `fenced`. Renders a per-entry table (`identity`, `owner`, the committed epoch split into `EPOCH-TERM`/`EPOCH-CTR`, the local high-water split into `HW-TERM`/`HW-CTR`, and `FENCED`). Use to verify the ownership fence engaged after a takeover: the committed epoch is the fencing token the Rabia applier uses to reject a deposed owner's strictly-older epoch, and `FENCED=true` pinpoints the node/arc that has already observed a newer epoch than the still-committed owner (the deposed-owner window). **Per-node local view** (not leader/owner-forwarded) — target a specific node (`-c <host>`) to read its committed + high-water view. Wraps `GET /api/ownership/{domain}`.

The `<domain>` argument is one of `community` (governor ownership — identity is the community id, owner is the governor), `dht` (DHT partition ownership — identity is the partition id), or `stream` (stream-partition ownership — identity is `{stream}:{partition}`). Any other value is rejected with an error.

```bash
# Stream-partition ownership + fence epochs
aether cluster ownership stream

# DHT partition ownership on a specific node
aether cluster ownership dht -c <host>

# Machine-readable
aether cluster ownership community --format json
```

| Option | Description |
|--------|-------------|
| `<domain>` | Ownership domain: `community`, `dht`, or `stream` (required positional argument) |
| `--format` | Output format: `table` (default), `json`, `value`, `csv` |

`FENCED` is `true` when the local high-water is strictly after the committed epoch — this node has observed a newer epoch than the committed owner record shows, so the committed owner would be rejected as stale here. In steady state `HW-TERM`/`HW-CTR` equal `EPOCH-TERM`/`EPOCH-CTR` and `FENCED` is `false`.

Example output (table):
```
IDENTITY  OWNER   EPOCH-TERM  EPOCH-CTR  HW-TERM  HW-CTR  FENCED
orders:0  core-1  7           3          7        3       false
orders:1  core-2  7           1          8        0       true
```

### `aether cluster journal`

Dump the target node's transition journal (cluster-topology-overhaul spec, Wave 1) — a bounded per-node ring buffer recording every membership-FSM transition (layer `FSM`) and every transport peer-lifecycle transition (layer `PEER`), plus the dialer expected-vs-actual Hello diagnostic and the boot future-history detection. Wraps `GET /api/cluster/journal`.

**Per-node scope:** the journal is local to the node serving the request — target a specific node (`-c <host>`) to read its view (e.g. the leader's view AND the victim's view during a chaos window).

```bash
# Both layers, most recent 256 entries per layer
aether cluster journal

# Only membership-FSM transitions
aether cluster journal --layer fsm

# Only transport peer-lifecycle transitions, last 50 entries
aether cluster journal --layer peer --limit 50
```

| Option | Description |
|--------|-------------|
| `--layer` | Journal layer to dump: `fsm` or `peer` (default: both, merged in sequence order) |
| `--limit` | Maximum number of entries per layer, newest kept (default: 256) |
| `--format` | Output format: `table` (default), `json`, `value`, `csv` |

Example output (table):
```
# SEQ      TIME(MS)       LAYER  NODE              FROM            TO              CAUSE                             INC    ROLE
# 17       1765432100123  FSM    node-3            Suspect         Dead            Stopped                           2      core
# 18       1765432100456  PEER   node-3            CONNECTED       REMOVED         authoritative-remove              -1
```

### `aether cluster audit`

**Phase 3 PR-C (lifecycle reconciler):** show recent `audit.lifecycle.commands` events seen by the target node. Backed by the per-node in-memory `RecentCommandsBuffer` populated via a tee on the lifecycle audit publisher.

Each `LifecycleCommand` emitted via `LifecycleWriter.applyCommand(...)` produces a `CommandReceived` + `CommandApplied` pair in the buffer. Use this to inspect operator-issued `aether nodes decommission|force-on-duty|...` actions, and (Phase 4-5) reconciler-emitted recovery commands.

**Per-node scope:** the buffer is local to the node serving the request. Target the leader (`-c <leader-host>`) for cluster-wide visibility. RC2 follow-up: full stream subscription survives restarts and cross-node fan-out.

```bash
# All events the local node has seen (most recent 100)
aether cluster audit

# Only operator-emitted events
aether cluster audit --source operator

# Reconciler-emitted events in the last hour
aether cluster audit --source reconciler --since 1h

# Specific ISO-8601 window, limit 50, JSON output
aether cluster audit --since 2026-05-23T10:00:00Z --limit 50 --format json
```

Options:

| Option | Description |
|--------|-------------|
| `--source <name>` | Filter by emitter discriminator: `operator`, `reconciler`, `ctm`, `drain_coordinator`, `bootstrap`, `unknown`, or `all` (default). Case-insensitive. |
| `--since <when>` | Time window. Accepts epoch-millis, ISO-8601 (`2026-05-23T10:00:00Z`), or relative duration (`30s`, `5m`, `1h`, `2d`). Default: entire buffer. |
| `--limit <N>` | Max entries to return. Default: 100. Capped by buffer capacity. |

### LifecycleReconciler observability (Phase 4 PR-D)

**No dedicated CLI subcommand.** Observability for the leader-only
`LifecycleReconciler` (see `aether/docs/specs/membership-architecture-v2-spec.md`) is exposed through the
audit channel:

- **`aether cluster audit --source reconciler`** — surfaces the reconciler's rule
  emissions. After the Phase 5 PR-E enforcing flip, five rules emit a
  `CommandReceived` + `CommandApplied` pair (KV write applied); the two audit-only
  rules (`JoiningStuckAlert`, `StoppedZombie`) and any rule with operator-overridden
  `enforce=false` emit a `CommandReceived` only.

```bash
# Tail reconciler activity (per spec §7.3)
aether cluster audit --source reconciler --since 5m
```

**Operator escape hatch — dry-run override.** To roll an enforcing rule back to
audit-only (e.g. when a false-positive storm surfaces in production), set
`enforce = false` on the offending rule in `aether.toml`:

```toml
[reconciler.rules.joiningTimeout]
enforce = false

[reconciler.rules.onDutyFaulty]
enforce = false
```

Setting all five enforcing rules to `enforce = false` reverts the reconciler to the
Phase 4 dry-run shape. The rule still ticks and still emits `CommandReceived` audit
events — only the KV write is suppressed. Note that `JoiningStuckAlert` and
`StoppedZombie` are audit-only forever per spec §7.1 and do not honour a
`enforce = true` override.

### `aether cluster generation`

Show the current cluster generation snapshot as observed by the queried node. The snapshot summarises the leader-projected epoch, core members, communities, and DHT partition ownership. See [`cluster-generation-spec.md`](../specs/cluster-generation-spec.md) §14.

```bash
aether cluster generation
```

| Option | Description |
|--------|-------------|
| `--format` | Output format: `table` (default), `json`, `value`, `csv` |

Example:
```bash
aether cluster generation

# Output (table):
# Epoch:              7:142
# Mode:               HIERARCHICAL
# Quiescence:         QUIESCED
# Rabia term:         7
# Desired core size:  5
# Core members:       5
# Communities:        1
# Partitions:         2
```

JSON output returns the full snapshot shape exposed by `GET /api/cluster/generation`.

### `aether cluster await-quiesced`

Block until the queried node observes the requested cluster generation epoch AND the snapshot reports cluster-wide quiescence. Use this in test harnesses or operator scripts that depend on a deterministic settled state before proceeding.

```bash
aether cluster await-quiesced --epoch <T:C> [--timeout 30s]
```

| Option | Description |
|--------|-------------|
| `--epoch` | Required, epoch in `term:counter` form (e.g. `7:142`). |
| `--timeout` | Optional, default `30s`, capped at `120s`. |
| `--format` | Output format: `table` (default) — concise one-liner; `json` — raw response body. |

Exit codes: `0` on success, non-zero on timeout (HTTP 408) or other failure.

Example:
```bash
aether cluster await-quiesced --epoch 7:142 --timeout 60s
# Output: Quiesced at 7:142 (response: {"epoch":"7:142","quiescence":"QUIESCED","waitedMs":1234})
```

See [`cluster-generation-spec.md`](../specs/cluster-generation-spec.md) §14.

### `aether cluster upgrade`

Initiate a cluster version upgrade.

```bash
aether cluster upgrade --version <X.Y.Z>
```

| Option | Description |
|--------|-------------|
| `--version` | Target version in X.Y.Z format |
| `-o json` | Output raw JSON (`-o=<format>`; `--json` never existed — corrected 2026-08-09) |

Example:
```bash
# Upgrade cluster to 0.26.0
aether cluster upgrade --version 0.26.0

# Output:
# Upgrade initiated.
# Version: 1.0.0-rc1 -> 0.26.0
```

If the cluster is already at the target version:
```
Already at version 0.26.0. No upgrade needed.
```

### `aether cluster bootstrap`

Bootstrap a new cluster from a configuration file. For the full bootstrap-config TOML schema, a
minimal validated Hetzner example, and the tribal-knowledge traps (security mode, jar_url pinning,
database section naming), see the [Bootstrap Config Reference](bootstrap-config.md).

```bash
aether cluster bootstrap <config-file> [--cluster <name>] [--yes] [--resume] [--full-check] \
                                       [--wait [--timeout <seconds>]] [--keep-on-failure] \
                                       [--ssh-public-key <path>]
```

| Option | Description |
|--------|-------------|
| `--cluster <name>` | Override `[cluster].name` from the TOML (precedence: CLI > TOML > default) |
| `--yes` | Skip confirmation prompt |
| `--resume` | Resume a failed bootstrap from last completed phase |
| `--full-check` | Run full network pre-flight checks (SSH, Docker, floating IP) |
| `--wait` | Wait for cluster to become healthy (`state == CONVERGED`) after bootstrap |
| `--timeout <seconds>` | Timeout when using `--wait` (default: `300`) |
| `--keep-on-failure` | On failure, skip auto-cleanup so VMs/SSH keys remain for SSH-based diagnosis |
| `--ssh-public-key <path>` | Operator SSH public key for cloud-init injection. Resolution priority: CLI > `[infrastructure.ssh] public_key_files` TOML > `${AETHER_SSH_KEY}.pub` sibling. Cloud sources fail fast if no key resolves |

Seven-phase flow: Validate → Upload SSH Keys → Provision → Collect Addresses → Deploy Runtime → Cluster Formation → Post-Bootstrap. State persisted to `~/.aether/clusters/<name>/bootstrap-state.json` after each phase.

**Runtime modes** (`[runtime.default] type = "container" | "jvm"`):
- **container** — VMs install Docker, pull `ghcr.io/.../aether-node:<tag>` (from `[runtime.default] image`), run with the composed `aether.toml` bind-mounted over `/app/aether.toml`. Restart via `--restart unless-stopped`.
- **jvm** — VMs install Eclipse Temurin 25 from Adoptium, download `aether-node.jar` from `[runtime.default] jar_url` (or auto-derived `https://github.com/pragmaticalabs/pragmatica/releases/download/v<version>{-candidate?}/aether-node.jar` — pin `jar_url` explicitly whenever that derivation doesn't match a published release tag, see [Bootstrap Config Reference](bootstrap-config.md#b-jar_url-pinning)), run via `nohup java -jar … & disown`. No process supervision (consider auto-heal for crash recovery).

After provisioning, the deploy phase SSHes each cloud node (via `cloud-init status --wait` preflight) and restarts the runtime with the finalized 3-part PEERS list (`nodeId:host:port`). On default (`--keep-on-failure` not set), all tracked resources (VMs, SSH keys, firewall rules, floating IPs) are cleaned up automatically on failure.

### `aether cluster destroy`

Destroy the active cluster: drain and shut down all nodes, terminate its cloud resources (VMs, SSH keys), and remove the local registry entry. Symmetric counterpart to `aether cluster bootstrap`.

```bash
aether cluster destroy --cluster=my-cluster --yes
```

| Flag | Description |
|------|-------------|
| `--cluster <name>` | Destroy the named cluster instead of the active-context one (CLI > active-context) |
| `--yes` | Skip the interactive confirmation prompt |
| `--keep-resources` | Skip cloud resource termination — remove the registry entry only |
| `-q`, `--no-color`, `-o <format>`, `--field <field>` | Standard output controls |

> **Cleanup failure is loud (#521).** If cloud resource termination fails, `destroy` exits
> non-zero (`ExitCode.CLEANUP_FAILED`) and deliberately **keeps** the registry entry — the
> summary prints `Registry entry: KEPT` with the retry command — so the cluster stays
> addressable while its VMs may still be billing. Just re-run the command. From a repo
> checkout, `tools/cloud-reaper.sh --cluster <name>` (dry-run; add `--destroy` to delete)
> is the label-driven safety net that finds resources no local state knows about.

### `aether cluster apply`

Apply cluster configuration changes with desired-state reconciliation.

```bash
aether cluster apply <config-file> [--dry-run] [--yes] [--resume] [--rollback] [--full-check]
```

| Option | Description |
|--------|-------------|
| `--dry-run` | Show planned changes without executing |
| `--yes` | Skip confirmation prompt |
| `--resume` | Resume a halted apply from first unfinished wave |
| `--rollback` | Rollback completed waves to pre-apply state |
| `--full-check` | Run full network pre-flight checks |

**Plain `apply` (no `--resume`/`--rollback`) actuates scale changes only.** It diffs the config
file against what's stored and classifies every change: a role's worker/core count going up or
down is applied immediately as a fenced desired-count write, for which the worker reconciler then
provisions or drains nodes. Any other kind of change in the same file — adding/removing a source
or role, changing a source's type, or an immutable field like the cluster name — is rejected
before anything is actuated, either as a typed `UnsupportedApplyAction` error naming the
unsupported action, or (for an immutable field) a validation error naming the field. A plan mixing
a valid scale with an unsupported action is rejected in full; the scale is not applied on its own.
Recovery: split the file so scale changes go through plain `apply` and everything else is handled
separately, or wait for the change to be supported.

**The terraform-style plan (`[+]`/`[~]`/`[-]`) and wave-based rollout (additions → modifications →
removals, respecting `maxUnavailable` for core nodes) is the `--resume`/`--rollback` path**
(`ApplyOrchestrator` → `WaveExecutor`), not plain `apply` — reachable only by first halting an
apply and then resuming or rolling it back.

### `aether cluster rotate-key`

Rotate the cluster API key with zero-downtime grace period.

```bash
aether cluster rotate-key [--grace-period <duration>] [--role <role>] [--key-id <keyId>]
```

| Option | Description |
|--------|-------------|
| `--grace-period` | Grace period for old key (default: `5m`). Accepts `s`, `m`, `h` suffixes |
| `--role` | Authorization role for the new key: `ADMIN`, `OPERATOR`, or `VIEWER` (default: `VIEWER`) |
| `--key-id` | Key ID to retire. Required when the cluster has more than one `ACTIVE` key |

Generates new key, pushes to cluster, marks old key REVOKED with grace period, updates local `~/.aether/clusters/<name>/api-key`.

The key to retire is chosen by reading each record's own `status` in `GET /api/cluster/keys`, so
listing order never decides which credential is revoked. With exactly one `ACTIVE` key the command
retires it and names it in the output. With several, it refuses and lists the candidates — re-run
with `--key-id` naming the one to retire. A key listing that cannot be read fails the rotation
rather than resolving to some key.

### `aether cluster revoke-key`

Revoke an API key by ID.

```bash
aether cluster revoke-key <keyId> [--immediate]
```

| Option | Description |
|--------|-------------|
| `--immediate` | Skip grace period, revoke immediately |

### `aether cluster list-keys`

List cluster API keys and optionally show audit trail.

```bash
aether cluster list-keys [--audit]
```

| Option | Description |
|--------|-------------|
| `--audit` | Show full key operation history (create, rotate, revoke, expire) |

---

## TTM (Foundation Model)

### `aether ttm status`

Show the foundation-model / TTM (training & model) runtime status. Wraps `GET /api/ttm/status`.

```bash
aether ttm status
```

### `aether ttm training-data`

Show the foundation-model / TTM training-data snapshot. Wraps `GET /api/ttm/training-data`.

```bash
aether ttm training-data
```

---

## DHT

### `aether dht replication-map [--limit N] [--prefix P]`

Show the active DHT replication map — which keys live on which nodes under
the current replication factor. Operator-facing inspection. Wraps
`GET /api/dht/replication-map`.

Options:
- `-l`, `--limit N` — max entries to return (default 100, capped at 10000).
- `-p`, `--prefix P` — only include keys whose UTF-8 prefix matches.

```bash
aether dht replication-map
aether dht replication-map --limit 50 --prefix user:
aether dht replication-map --format json
```

Each entry's `nodes[0]` is the primary; subsequent entries are replicas
walking the consistent-hash ring clockwise. The endpoint reports one node's
storage view — for cluster-wide audits invoke the same command against every
node and union the results.

---

## Durable Entities

### `aether entity checkpoints`

Show this node's durable-entity checkpoint progress (#345 I3).

Reads `GET /api/entity/checkpoints`, which is LOCAL — each node checkpoints only the partitions it folds,
so this reports the node you queried. Point `--node` / the management endpoint at a specific node to see
that node's view.

```bash
aether entity checkpoints
```

A checkpoint is the only thing that bounds an entity log: until a partition is checkpointed, the retention
floor reclaims nothing for it. **`writes` climbing is the signal that the driver is alive** — writes and
reads keep succeeding even when checkpointing has stopped, so a flat `writes` under load is the fault to
act on. `failures` and `checkpointedThrough` say which partitions are stuck; a partition this node has
never folded is absent rather than reported as offset 0.

Output is the endpoint's JSON, pretty-printed:

```json
{
  "keyspaces": [
    {"keyspace": "orders", "partitionCount": 8, "writes": 214, "failures": 0,
     "checkpointedThrough": {"0": 1841, "3": 990, "5": 1502}}
  ]
}
```

### `aether entity keyspaces`

Show durable-entity keyspaces with their hosting node sets (#634-3).

Reads [`GET /api/entity/keyspaces`](management-api.md#entity-keyspaces-hosting-view). Assembled from
replicated KV, so any caught-up node answers identically — no need to sweep ports (unlike
`checkpoints` above, which is per-node).

```bash
aether entity keyspaces
```

Fields: `keyspace` — the entity keyspace (stream `entity:<keyspace>`); `hosts` — the nodes with a
committed registration — an UPPER BOUND on the candidate set the leader mints entity-arc owners over
(the 02w hosting-set fix): the leader intersects this set with live members before placing, so a
departed-but-not-yet-pruned node appears here without being a candidate; owners are always drawn
from this set and no others; `partitionCount` —
declared partition count, the max across hosts during a rolling redeploy; `partitionCountsDisagree` —
`true` while hosts declare different counts (rolling-redeploy window; arcs span the max until configs
re-converge — persistent disagreement outside a deploy window means a stale slice version on some
node). Full schema in the Management API section linked above.

## Storage

Inspect and snapshot the node's Hierarchical Storage Engine instances (#207) — the
content-addressed block stores backing content, artifact, and stream storage.
Wraps the [`/api/storage`](management-api.md#storage-hierarchical-storage-engine)
Management-API surface.

### `aether storage list [--node <id>]`

List storage instances with their tier utilisation and readiness. By default
returns the **cluster-wide** rollup (per-instance totals + a per-node breakdown,
via the leader, `GET /api/cluster/storage`). With `--node <id>` it returns a single
node's local view (`GET /api/storage`).

Options:
- `--node <id>` — target a specific node's local storage view instead of the cluster rollup.

```bash
aether storage list
aether storage list --node node-1
aether storage list --format json
```

### `aether storage status <name> [--node <id>]`

Show one named instance's tier topology, snapshot marker, and readiness. By default
returns the cluster-wide per-node breakdown (`GET /api/cluster/storage/{name}`);
with `--node <id>` it returns that node's local detail (`GET /api/storage/{name}`).

Options:
- `--node <id>` — target a specific node's local view instead of the cluster rollup.

```bash
aether storage status content
aether storage status content --node node-1
```

### `aether storage snapshot <name>`

Force an immediate metadata snapshot of the named instance
(`POST /api/storage/snapshot/{name}`). Prints the epoch and timestamp of the
snapshot just taken. Routed to the `STORAGE` task-group owner.

```bash
aether storage snapshot content
```

### `aether storage retention`

Show the per-partition tri-floor retention view (#634-3/4):
[`GET /api/storage/retention`](management-api.md#get-apistorageretention). LOCAL — WAL, ring, and
segment offsets describe the node you query; the `checkpointFloor` comes from replicated KV, so it
agrees everywhere.

```bash
aether storage retention
aether storage retention --format json
```

Per `(stream, partition)` row (offsets are `-1` when the source/floor is absent): `wal` — the live
WAL counters (`sizeBytes`, replayable window `(truncatedUpto, lastOffset]`, fsync count/latency),
`null` when the partition has no WAL; `ringTail` — earliest offset still in the in-memory ring;
`sealedThrough` / `earliestSegment` — the durable sealed bound and the earliest retained sealed
segment; `checkpointFloor` — the entity checkpoint; `coveredFrom` — earliest offset reachable from
any local source; `violated` / `violation` — the tri-floor invariant verdict. `walTotalBytes` at the
root is this node's total live WAL footprint. Full schema and the precise invariant in the
Management API section linked above.

**A `violated: true` row means this node cannot rebuild that partition from its checkpoint** — the
records in `[checkpointFloor + 1, coveredFrom - 1]` are on no local source, so a fold here would
refuse. Recovery: restore the missing range from a replica that still holds it (re-replication via
partition backfill); if no replica holds it, accept the documented loss and re-baseline the
checkpoint — see the [operator recovery action](management-api.md#get-apistorageretention). The flag
clears on the next read after local sources again cover `checkpoint + 1`. A periodic watch re-checks
every 5 minutes and raises the `retention-invariant` alert (severity `CRITICAL`) once per
newly-violated partition — see `aether alerts active`.

---

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | General error |
| 2 | Invalid arguments |
| 3 | Connection failed |

## App HTTP Security Configuration

The app HTTP server supports three security modes configured in `aether.toml`:

### Security Modes

| Mode | Value | Description |
|------|-------|-------------|
| None | `security_mode = "none"` | No authentication — dev/eval only, see [Bootstrap Config Reference](bootstrap-config.md#a-security_mode--none--why-deveval-bootstrap-needs-it) |
| API Key | `security_mode = "api-key"` | Reuses management API keys via `X-API-Key` header (**default** when `security_mode` is omitted — issue #290, "secure by default"; if no key is provisioned, a fresh cluster auto-generates one ADMIN key on first leadership and prints it once, see [SECURITY.md](../../../SECURITY.md#default-security-posture-management-api)) |
| JWT | `security_mode = "jwt"` | Bearer token auth with JWKS validation (RS256/ES256) |

### Example Configurations

**Default (API key, auto-provisioned):**
```toml
[app-http]
enabled = true
port = 8070
# security_mode omitted -> defaults to "api-key"; capture the auto-generated
# ADMIN key from the leader's log on first boot, or pre-provision one — see
# the Bootstrap Config Reference link above.
```

**API key security:**
```toml
[app-http]
enabled = true
security_mode = "api-key"

[app-http.api-keys.my-key]
name = "my-service"
roles = ["service"]
authorization_role = "OPERATOR"
```

**JWT security:**
```toml
[app-http]
enabled = true
security_mode = "jwt"
jwks_url = "https://auth.example.com/.well-known/jwks.json"
issuer = "https://auth.example.com/"
audience = "my-api"
```

### Request Size and Multipart

```toml
[app-http]
max_request_size = "5MB"    # Default: 10MB. Accepts KB, MB, GB.
```

Multipart file uploads are supported and subject to the same size limit.

### API Version Detection (#198 §7)

A slice whose `routes.toml` declares API versions (`[api] prefix` + `[vN.routes]`) is exposed in one
of two detection modes, chosen at the cluster level — the same compiled slice serves either, no
recompile:

```toml
[app-http]
api_versioning_detection = "path"     # Default. Version travels in the URL: {prefix}/v{N}/{path}
# api_versioning_detection = "header"  # Version travels in a request header; routes mount at {prefix}/{path}
api_version_header = "API-Version"     # Header name read in header mode (default shown)
```

- **`path`** (default): `GET {prefix}/v1/{id}` / `GET {prefix}/v2/{id}` — byte-for-byte the prior behavior.
- **`header`**: all versions share `GET {prefix}/{id}`; the requested version comes from the
  `api_version_header` request header. Selection (§7): header naming a known version → that version;
  unknown/non-numeric → `404`; absent with `requireVersionHeader = true` (a per-slice `routes.toml`
  flag) → `400` naming the header; absent with a `defaultIfMissing` version → that version; absent
  with no default → highest declared version (latest-wins).

Per-slice override of the detection mode is a planned follow-up; the setting is cluster-level today.
Unversioned slices are unaffected by the mode.

See [Management API - App HTTP Security](management-api.md#app-http-security) for full details.

---

## See Also

- [Getting Started](../slice-developers/getting-started.md) - First steps with Aether
- [Forge Guide](../slice-developers/forge-guide.md) - Detailed Forge documentation
- [Scaling Guide](../operators/scaling.md) - Scaling configuration
