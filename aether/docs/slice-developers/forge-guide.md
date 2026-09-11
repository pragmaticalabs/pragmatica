# Aether Forge: Local Development Environment

Aether Forge is a built-in local development environment for running, testing, and debugging Aether clusters. It provides a visual dashboard, real-time monitoring, and chaos engineering capabilities — all in a single process.

## Why Forge?

Traditional testing approaches fall short for distributed systems:

| Approach | Problem |
|----------|---------|
| Unit tests | Don't test distribution |
| Integration tests | Don't test failure scenarios |
| Staging environment | Expensive, often outdated |
| Production testing | Risky |

Forge provides:
- **Local multi-node cluster** — Run 5+ nodes in a single process
- **Visual dashboard** — Real-time cluster topology, per-node metrics (CPU, heap, leader status)
- **Management API access** — Each node exposes management API (ports 5150+)
- **Cluster operations** — Add/remove nodes, rolling restarts, scale up/down
- **Chaos operations** — Kill nodes, inject failures, observe recovery
- **Configurable load generation** — TOML-based multi-target load testing
- **Automated verification** — System invariants (planned)

## Quick Start

### Using run-forge.sh (Recommended)

If your project was created with `jbct init --slice`, it includes a `run-forge.sh` script:

```bash
./run-forge.sh
```

This starts Forge with sensible defaults. Open the dashboard at `http://localhost:8888`.

### Using java -jar (Manual)

```bash
# Build Forge
mvn package -pl aether/forge/forge-core -am -DskipTests

# Start Forge with default configuration
java -jar aether/forge/forge-core/target/aether-forge.jar

# Start with blueprint and load config
java -jar aether/forge/forge-core/target/aether-forge.jar \
  --blueprint examples/blueprint.toml \
  --load-config examples/load-config.toml \
  --auto-start

# Open dashboard
open http://localhost:8888
```

The dashboard shows:
- **Cluster topology** — Nodes with ports, leader indicator, health status
- **Per-node metrics** — CPU usage, heap memory, leader status
- **Cluster controls** — Add node, kill node, rolling restart
- **Management access** — Direct links to each node's management API (ports 5150+)

### CLI Options

```bash
java -jar aether-forge.jar [options]

Options:
  --config <forge.toml>       Forge cluster configuration
  --blueprint <file.toml>     Blueprint to deploy on startup
  --load-config <file.toml>   Load test configuration
  --auto-start                Start load generation after config loaded
```

### Environment Variables

Environment variables override CLI arguments:

| Variable | Description |
|----------|-------------|
| FORGE_CONFIG | Path to forge.toml |
| FORGE_BLUEPRINT | Path to blueprint file |
| FORGE_LOAD_CONFIG | Path to load config file |
| FORGE_AUTO_START | Set to "true" to auto-start load |
| FORGE_PORT | Dashboard port (default: 8888) |
| CLUSTER_SIZE | Number of nodes (default: 5) |
| LOAD_RATE | Initial load rate (legacy support) |
| AETHER_FORGE_DATA | Absolute path of the durable data directory (overrides the per-project default) |
| AETHER_HOME | Data directory parent, **for config-less runs only** — see below |

### Data Directory

Forge writes durable cluster state (artifact disk tier, per-node stream WAL) under a directory it
resolves at startup and logs as an absolute path. Precedence, first match wins:

1. `AETHER_FORGE_DATA` — used as given.
2. `<directory of the --config file>/.aether/forge-data` — the default for every `run-forge.sh`.
3. `$AETHER_HOME/forge-data` — only when no `--config` was given (the container entrypoint).
4. `~/.aether/forge-data` — final fallback.

Rule 2 is what keeps two projects on one host from sharing one cluster's state. `AETHER_HOME` sits
*below* it deliberately: `install.sh` reads that variable as the **install directory**, so scoping
run data on it would silently re-share state across every project of anyone who exports it to put
`$AETHER_HOME/bin` on `PATH`.

Reuse is announced, never silent. Forge records the owning project in a `.forge-owner` file inside
the data directory and, at startup:

- empty or absent directory → starts fresh;
- populated and owned by this project → **reuses** it, logging the entry count (this is what makes
  the stream WAL survive a restart);
- populated and owned by a different project, or populated with no owner recorded → **refuses to
  start**, exits non-zero, and names both projects.

Forge never clears the directory. Delete it yourself for a clean slate.

The refusal is a check, not a lock: it inspects the directory before creating anything, so a process
claiming the directory inside that window is not caught, and two runs of the *same* project share one
directory by design and are not distinguished by the owner marker. Same time-of-check/time-of-use
limitation as the QUIC port preflight, and stated for the same reason.

### Ports

Each Forge node exposes multiple APIs:

| Node | Cluster Port | Management Port | App HTTP Port |
|------|--------------|-----------------|---------------|
| node-1 | 6000 | 5150 | 8070 |
| node-2 | 6001 | 5151 | 8071 |
| node-3 | 6002 | 5152 | 8072 |
| ... | ... | ... | ... |

- **Management API** — Node management, health checks, metrics
- **App HTTP API** — Application slice endpoints (load target)

Access any node's management API directly:
```bash
curl http://localhost:5150/health
curl http://localhost:5151/metrics
curl http://localhost:5152/status
```

Slice endpoints are available on app HTTP ports:
```bash
curl http://localhost:8070/api/v1/urls/abc123
```

## Debugging

Forge is **one plain JVM** — the whole 5-node cluster, every deployed slice included, runs in a single
process. That means standard JDWP remote attach works today, with no Forge-specific machinery:

```bash
# Attach-when-ready (Forge starts immediately; connect the debugger whenever you like)
FORGE_JVM_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005" ./run-forge.sh

# Debug startup itself (JVM blocks until the debugger connects)
FORGE_JVM_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005" ./run-forge.sh
```

Then attach from the IDE as a plain **Remote JVM Debug** configuration on `localhost:5005`
(IntelliJ: Run → Edit Configurations → + → Remote JVM Debug; the default template already matches the
flags above).

Three things worth knowing:

- **Breakpoints in slice code just work.** Slices load through per-slice classloaders, but JDWP is
  classloader-agnostic — a breakpoint in your slice source binds when the slice's class is loaded,
  whichever loader defines it. Keep the slice's source project open in the same IDE window so the
  debugger maps frames to source.
- **Breakpoints pause the whole cluster.** All five simulated nodes share the one JVM, so a hit
  breakpoint freezes consensus, heartbeats and timers with it. Expect SWIM suspicion and timer noise
  after long pauses — harmless in Forge, but worth knowing before you blame the runtime.
- **Every `run-forge.sh` honors `FORGE_JVM_OPTS`** (word-split deliberately, so several flags work),
  and the same flags work with a direct launch: `java $FORGE_JVM_OPTS -jar forge.jar ...`.

## Configuration

### forge.toml

```toml
# forge.toml - Forge cluster configuration

[cluster]
nodes = 5                    # Number of nodes to simulate
base_port = 6000             # Base QUIC/consensus UDP port (node i binds base_port + i)
management_port = 5150       # Base management port
dashboard_port = 8888        # Dashboard port
app_http_port = 8070         # Base app HTTP port (load target)
start_timeout_seconds = 60   # How long to wait for the cluster to finish forming before exiting
```

If the cluster does not finish forming inside `start_timeout_seconds`, Forge exits non-zero and
reports what it had reached at the deadline — how many nodes were consensus-active, the leader (or
`none`), each node with its state and QUIC port, and any per-node start failure. Raise the value if
formation on your host is merely slow; a stale `forge-data` directory (whose path Forge logs at
startup — see [Data Directory](#data-directory)) is a common reason for formation to consume the
whole budget.

### Running two Forge instances on one host

All four port settings must be moved, `base_port` included. It is the cluster's QUIC/consensus range
(`base_port` through `base_port + nodes - 1`), and it is the one that does **not** announce a
collision by itself: the QUIC sockets are bound with `SO_REUSEADDR`, so a second instance left on the
default 6000 binds successfully, silently splits the range's datagrams with the first instance, and
never reaches quorum — surfacing only as `activePeerCount=1`, which is also what genuinely stale
`forge-data/` state looks like.

Forge therefore checks the whole range at startup and refuses to start when any of it is held,
naming the ports. If you see that message it is a collision, not stale state — no node was started.

Move the **data directory** as well. Two instances launched from *different* projects already get
separate directories by default, but two launched from the *same* project resolve to one directory
and are not distinguished by the owner marker, so the second must be given its own with
`AETHER_FORGE_DATA=<dir>`. See [Data Directory](#data-directory).

### Auto-Healing

Forge automatically maintains cluster size via the NodeProvider SPI. When a node fails or is killed, the CDM (Cluster Deployment Manager) detects the deficit and provisions replacements through the NodeProvider interface.

- **Always active**: Auto-healing is enabled whenever a NodeProvider is present (Forge always provides one)
- **Reactive**: CDM detects node deficit and provisions replacements automatically
- **Periodic recheck**: Retries at configured intervals until cluster size is restored

### Blueprint (blueprint.toml)

Blueprints define what slices to deploy:

```toml
# blueprint.toml - Slices to deploy

[[slices]]
artifact = "com.example:order-processor:1.0.0"
instances = 3

[[slices]]
artifact = "com.example:inventory-service:1.0.0"
instances = 2
```

### Load Configuration (load-config.toml)

```toml
# load-config.toml - Load generation configuration

[[targets]]
name = "place-order"
target = "/api/orders"
rate = "100/s"
duration = "10m"

[targets.body]
template = '''
{
  "customerId": "${uuid}",
  "items": [{"productId": "PROD-${range:1000:9999}", "quantity": ${range:1:5}}]
}
'''

[[targets]]
name = "get-order"
target = "/api/orders/{orderId}"
rate = "50/s"

[targets.path_vars]
orderId = "${uuid}"
```

## Load Generation

Forge supports configurable load generation via TOML configuration.

### Target Configuration

Each target defines:
- **name** — Identifier for metrics
- **target** — HTTP path or slice method
- **rate** — Requests per second (e.g., "100/s")
- **duration** — Optional duration limit (e.g., "10m")
- **body** — Optional request body with template
- **path_vars** — Variables for path substitution

### Template Patterns

Templates support these pattern generators:

| Pattern | Description | Example |
|---------|-------------|---------|
| `${uuid}` | Random UUID | `550e8400-e29b-41d4-a716-446655440000` |
| `${range:min:max}` | Random integer in range | `${range:1:100}` → `42` |
| `${choice:a,b,c}` | Random choice from list | `${choice:red,green,blue}` → `green` |
| `${seq:prefix}` | Sequential with prefix | `${seq:ORD}` → `ORD-00001` |
| `${random:pattern}` | Random string matching pattern | `${random:[A-Z]{3}-[0-9]{4}}` |

### Example Configurations

#### Simple GET requests

```toml
[[targets]]
name = "health-check"
target = "/health"
rate = "10/s"
```

#### POST with body

```toml
[[targets]]
name = "create-user"
target = "/api/users"
rate = "50/s"

[targets.body]
template = '''
{
  "name": "User ${seq:U}",
  "email": "${uuid}@example.com"
}
'''
```

#### Path variables

```toml
[[targets]]
name = "get-user"
target = "/api/users/{userId}"
rate = "100/s"

[targets.path_vars]
userId = "${uuid}"
```

#### Duration-limited target

```toml
[[targets]]
name = "stress-test"
target = "/api/heavy-operation"
rate = "500/s"
duration = "5m"
```

### Dashboard Load Controls

The dashboard provides controls for load generation:
- **Start/Stop** — Start or stop all load generation
- **Pause/Resume** — Temporarily pause without stopping
- **Per-target metrics** — View rate, latency, success rate per target
- **Upload Config** — Paste TOML configuration directly

### REST API

```bash
# Get load config
curl http://localhost:8888/api/load/config

# Upload load config
curl -X POST http://localhost:8888/api/load/config \
  -d '[[targets]]
name = "test"
target = "/health"
rate = "10/s"'

# Start load generation
curl -X POST http://localhost:8888/api/load/start

# Stop load generation
curl -X POST http://localhost:8888/api/load/stop

# Pause load generation
curl -X POST http://localhost:8888/api/load/pause

# Resume load generation
curl -X POST http://localhost:8888/api/load/resume

# Get load status with metrics
curl http://localhost:8888/api/load/status
```

## Chaos Operations

### Via Dashboard

The dashboard provides one-click chaos operations:

- **Kill Node**: Immediately terminate a node
- **Kill Leader**: Terminate the current leader node
- **Rolling Restart**: Toggle continuous rolling restart (kills random node, waits 2.5s, adds replacement, repeats)
- **Add Node**: Add a new node to the cluster
- **Reset Metrics**: Clear all metrics and events

The Rolling Restart button toggles between "Rolling Restart" (start) and "Stop Restart" (stop) states.

### Via REST API

```bash
# Kill a specific node
curl -X POST http://localhost:8888/api/chaos/kill/node-3

# Add a new node
curl -X POST http://localhost:8888/api/chaos/add-node

# Start continuous rolling restart
curl -X POST http://localhost:8888/api/chaos/start-rolling-restart

# Stop rolling restart
curl -X POST http://localhost:8888/api/chaos/stop-rolling-restart

# Get rolling restart status
curl http://localhost:8888/api/chaos/rolling-restart-status

# Inject chaos event
curl -X POST http://localhost:8888/api/chaos/inject \
  -H "Content-Type: application/json" \
  -d '{"type":"LATENCY_SPIKE","nodeId":"node-2","latencyMs":500,"durationSeconds":60}'

# Stop all chaos
curl -X POST http://localhost:8888/api/chaos/stop-all

# Get chaos status
curl http://localhost:8888/api/chaos/status
```

### Chaos Event Types

| Type | Description | Parameters |
|------|-------------|------------|
| NODE_KILL | Kill a node | nodeId |
| LATENCY_SPIKE | Add latency | nodeId, latencyMs, durationSeconds |
| SLICE_CRASH | Crash a slice | artifact, nodeId |
| INVOCATION_FAILURE | Inject failures | artifact, failureRate |
| CPU_SPIKE | Simulate CPU load | nodeId, level |
| MEMORY_PRESSURE | Simulate memory pressure | nodeId, level |

## Dashboard Features

### Topology View

Visual representation of your cluster with:
- Node status (healthy, unhealthy, leader)
- Management port links
- Slice distribution

### Metrics Charts

Real-time charts showing:
- Requests per second
- Latency distribution
- Error rates
- CPU and memory per node

### Event Log

Timeline of cluster events:
- Node joins/leaves
- Leader elections
- Scaling events
- Chaos operations

## Troubleshooting

### Forge Won't Start

```bash
# Check port availability
lsof -i :5150-5160
lsof -i :8888

# Check Java version
java -version  # Must be 25+
```

### Load Not Generating

```bash
# Check load status
curl http://localhost:8888/api/load/status

# Verify config loaded
curl http://localhost:8888/api/load/config
```

### Dashboard Not Loading

```bash
# Check if server is running
curl http://localhost:8888/health

# Check logs for errors
```

## Planned Features

The following features are planned but not yet implemented:

- **Scenario Runner** — YAML-based test scenarios with steps
- **Verification Framework** — Custom verification classes
- **Spike/Realistic Load Patterns** — Advanced load patterns
- **Production Pattern Replay** — Load based on production metrics

## Next Steps

- [Scaling Guide](../operators/scaling.md) - Understand what Forge tests
- [Architecture](../architecture/00-overview.md) - How Aether handles failures
