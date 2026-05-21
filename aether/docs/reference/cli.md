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

# Transport-layer metrics (QUIC/Netty connection + I/O counters)
aether metrics transport

# Minute-aggregated comprehensive snapshot (most recent minute)
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
aether blueprints deploy <coords>

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

Manage observability depth configuration:

```bash
# List all depth overrides
aether observability depth

# Set depth threshold for a method
aether observability depth-set <artifact#method> <threshold>

# Remove depth override
aether observability depth-remove <artifact#method>
```

Example:
```bash
# Set depth threshold to 3 for a specific method
aether observability depth-set org.example:order-processor:1.0.0#processOrder 3

# Check configured overrides
aether observability depth

# Remove override
aether observability depth-remove org.example:order-processor:1.0.0#processOrder
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
aether nodes lifecycle --state ON_DUTY

# Multi-state union via `+`
aether nodes lifecycle --state ON_DUTY+JOINING

# Get lifecycle state for a specific node (--state ignored when [id] is supplied)
aether nodes lifecycle <nodeId>

# Drain a node (ON_DUTY → DRAINING, CDM evacuates slices respecting budget)
aether nodes drain <nodeId>

# Activate a node (DRAINING/DECOMMISSIONED → ON_DUTY)
aether nodes activate <nodeId>

# Shut down a node (any → SHUTTING_DOWN)
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

# Cancel drain and return to active duty
aether nodes activate node-2

# Initiate shutdown
aether nodes shutdown node-3

# Promote node-4 to a WORKER role at runtime (CORE → WORKER); reverse with --role CORE
aether nodes promote node-4 --role WORKER
```

#### workers

Manage worker pool nodes:

```bash
# List all worker nodes
aether workers list

# Show worker pool health summary
aether workers health

# List worker endpoints
aether workers endpoints
```

Example:
```bash
# Check worker pool status
aether workers list

# Verify worker health
aether workers health

# See all deployed endpoints across workers
aether workers endpoints
```

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
# (RC1-blocker #16 in aether/docs/internal/audits/integration-test-audit-2026-05-21.md §2.2).
aether scheduled-tasks inject \
    --section scheduling.cleanup \
    --artifact com.example:my-slice:1.0.0 \
    --method cleanup
```

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
aether backup restore <commit-id>      # restore from a specific backup commit
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
| `restore <commit>` | Restore the cluster KV-Store from the named backup commit (`POST /api/backups/restore`). |
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

# Baseline a datasource at a version
aether schema baseline <datasource> -v <version>
```

| Subcommand | Description |
|------------|-------------|
| `status [datasource]` | Show schema status (all or specific) |
| `history <datasource>` | Show migration history |
| `migrate <datasource>` | Trigger manual migration |
| `undo <datasource> -v N` | Undo to target version |
| `baseline <datasource> -v N` | Baseline at version |

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

### `aether streams list`

List all event streams with metadata.

```bash
aether streams list
```

### `aether streams status <name>`

Show detailed stream info including per-partition details.

```bash
aether streams status my-events
```

### `aether streams publish <name> <message>`

Publish a text message to a stream. The message is base64-encoded automatically.

```bash
aether streams publish my-events "Hello, world!"
```

### `aether streams read <name> <partition>`

Read events from a specific partition of a stream. Optional `--since <offset>` selects
the starting offset (maps to `?from=`), and `--limit <N>` caps the number of events
returned (maps to `?max=`).

```bash
aether streams read my-events 0
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

### `aether streams delete <name> [--force]`

Delete an event stream. Prompts for confirmation unless `--force` (`-f`) is supplied.

```bash
aether streams delete my-events
aether streams delete my-events --force
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
- Uses `restart: "no"` per the CTM auto-heal contract (see `aether/docs/operator/deployment-recovery.md`)
- Provisions a per-cluster bridge network `aether-<name>-network`

See `aether/docs/operator/multi-cluster-deployment.md` for the full labeling model.

### `aether cluster scale`

Scale the cluster core node count. Validates quorum safety on the CLI side before sending.

```bash
aether cluster scale --core <N>
```

| Option | Description |
|--------|-------------|
| `--core` | Target core node count (minimum 3, must be odd) |
| `--json` | Output raw JSON |

Example:
```bash
# Scale to 7 core nodes
aether cluster scale --core 7

# Output:
# Scale successful.
# Core nodes: 5 -> 7
# Config version: 8
```

Scaling down displays a warning:
```
Warning: scaling down from 7 to 5 nodes. Excess nodes will be drained.
```

### `aether cluster topology`

Show cluster topology with per-node details including role, health, hostname, and zone.

```bash
aether cluster topology
```

| Option | Description |
|--------|-------------|
| `--format` | Output format: `table` (default), `json`, `value`, `csv` |

Example:
```bash
aether cluster topology

# Output (table):
# NODE              ROLE        HEALTH        HOSTNAME              ZONE            ADDRESS
# node-1            ACTIVE      HEALTHY       aether-node-1                         aether-node-1:6000
# node-2            ACTIVE      HEALTHY       aether-node-2                         aether-node-2:6000
# lb-passive        PASSIVE     HEALTHY       aether-lb                             0.0.0.0:7000
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

### `aether cluster tasks`

Inspect and reassign task group delegation. Without a subcommand, lists all assignments (same as `list`).

```bash
aether cluster tasks                          # list (legacy default)
aether cluster tasks list                     # explicit list form
aether cluster tasks status <group>           # single-group view
aether cluster tasks reassign --group <g> --target <node-id>
```

Subcommands:

| Subcommand | Purpose |
|------------|---------|
| `list` | List all task group assignments. Mirrors the bare `aether cluster tasks` default. |
| `status <group>` | Show the assignment for a single task group. `<group>` is case-insensitive; common values: `METRICS`, `SCALING`, `STRATEGIES`, `DEPLOYMENT`, `STORAGE`, `STREAMING`. Returns exit code ERROR with `Error: task group '<input>' not found` on stderr when the group is absent. |
| `reassign` | Move a task group to a specific node (`--group <name> --target <node-id>`). |

Examples:

```bash
# Inspect a single group's status field via --format value
aether cluster tasks status METRICS --format value --field assignments.0.status
# -> ACTIVE

# Inspect which node currently owns a group
aether cluster tasks status SCALING --format value --field assignments.0.assignedTo
# -> node-3

# Reassign STORAGE to node-4
aether cluster tasks reassign --group STORAGE --target node-4
```

The output JSON shape mirrors `GET /api/cluster/tasks` — see [`management-api.md`](management-api.md) for the per-record fields (`group`, `assignedTo`, `assignedAt`, `status`, `failureReason`).

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
| `--json` | Output raw JSON |

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

Bootstrap a new cluster from a configuration file.

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
- **jvm** — VMs install Eclipse Temurin 25 from Adoptium, download `aether-node.jar` from `[runtime.jvm] jar_url` (or auto-derived `https://github.com/pragmaticalabs/pragmatica/releases/download/v<version>{-candidate?}/aether-node.jar`), run via `nohup java -jar … & disown`. No process supervision (consider auto-heal for crash recovery).

After provisioning, the deploy phase SSHes each cloud node (via `cloud-init status --wait` preflight) and restarts the runtime with the finalized 3-part PEERS list (`nodeId:host:port`). On default (`--keep-on-failure` not set), all tracked resources (VMs, SSH keys, firewall rules, floating IPs) are cleaned up automatically on failure.

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

Computes diff against stored config, presents terraform-style plan (`[+]`/`[~]`/`[-]`), then executes in waves: additions → modifications → removals. Rolling restart respects `maxUnavailable` budget for core nodes.

### `aether cluster rotate-key`

Rotate the cluster API key with zero-downtime grace period.

```bash
aether cluster rotate-key [--grace-period <duration>]
```

| Option | Description |
|--------|-------------|
| `--grace-period` | Grace period for old key (default: `5m`). Accepts `s`, `m`, `h` suffixes |

Generates new key, pushes to cluster, marks old key REVOKED with grace period, updates local `~/.aether/clusters/<name>/api-key`.

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
| None | `security_mode = "none"` | No authentication (default) |
| API Key | `security_mode = "api-key"` | Reuses management API keys via `X-API-Key` header |
| JWT | `security_mode = "jwt"` | Bearer token auth with JWKS validation (RS256/ES256) |

### Example Configurations

**No security (default):**
```toml
[app-http]
enabled = true
port = 8070
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

See [Management API - App HTTP Security](management-api.md#app-http-security) for full details.

---

## See Also

- [Getting Started](../slice-developers/getting-started.md) - First steps with Aether
- [Forge Guide](../slice-developers/forge-guide.md) - Detailed Forge documentation
- [Scaling Guide](../operators/scaling.md) - Scaling configuration
