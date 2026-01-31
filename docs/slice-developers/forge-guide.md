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

```bash
# Build Forge
mvn package -pl forge/forge-core -am -DskipTests

# Start Forge with default configuration
java -jar forge/forge-core/target/aether-forge.jar

# Start with blueprint and load config
java -jar forge/forge-core/target/aether-forge.jar \
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

### Management Ports

Each Forge node exposes a management API:

| Node | Cluster Port | Management Port |
|------|--------------|-----------------|
| node-1 | 5050 | 5150 |
| node-2 | 5051 | 5151 |
| node-3 | 5052 | 5152 |
| ... | ... | ... |

Access any node's management API directly:
```bash
curl http://localhost:5150/health
curl http://localhost:5151/metrics
curl http://localhost:5152/status
```

## Configuration

### forge.toml

```toml
# forge.toml - Forge cluster configuration

[cluster]
nodes = 5                    # Number of nodes to simulate
management_port = 5150       # Base management port
dashboard_port = 8888        # Dashboard port
```

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
- **Rolling Restart**: Restart nodes one by one
- **Add Node**: Add a new node to the cluster
- **Reset Metrics**: Clear all metrics and events

### Via REST API

```bash
# Kill a specific node
curl -X POST http://localhost:8888/api/chaos/kill/node-3

# Add a new node
curl -X POST http://localhost:8888/api/chaos/add-node

# Rolling restart all nodes
curl -X POST http://localhost:8888/api/chaos/rolling-restart

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
lsof -i :5050-5060
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
- [Architecture](../contributors/architecture.md) - How Aether handles failures
