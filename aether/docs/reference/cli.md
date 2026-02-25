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
git clone https://github.com/pragmatica-lite/aether.git
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

The CLI will display user-friendly error messages for authentication failures:
- `Authentication required` (401) — API key not provided
- `Access denied` (403) — Invalid API key

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

List deployed slices:

```bash
aether slices
```

Output:
```
Slices:
  org.example:order-processor:1.0.0    3 instances  ACTIVE
  org.example:inventory:1.0.0          2 instances  ACTIVE
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

#### scale

Scale a blueprint-deployed slice. The slice must be part of an active blueprint.

```bash
aether scale <artifact> -n <instances>

# Example
aether scale org.example:order:1.0.0 -n 5
```

> **Note:** Individual deploy/undeploy commands have been removed. Use `blueprint apply` and `blueprint delete` instead.

#### artifact

Artifact repository operations:

```bash
# Deploy JAR to repository
aether artifact deploy <jar-path> -g <groupId> -a <artifactId> -v <version>

# Push artifact from local Maven repository to cluster
aether artifact push <group:artifact:version>

# List artifacts
aether artifact list

# List versions
aether artifact versions <group:artifact>

# Show artifact metadata
aether artifact info <group:artifact:version>

# Delete an artifact
aether artifact delete <group:artifact:version>

# Show artifact storage metrics
aether artifact metrics
```

Examples:
```bash
# Deploy a JAR file directly
aether artifact deploy target/my-slice.jar -g com.example -a my-slice -v 1.0.0

# Push from local Maven repository (~/.m2/repository)
aether artifact push com.example:my-slice:1.0.0

# View artifact details
aether artifact info com.example:my-slice:1.0.0

# Remove an artifact
aether artifact delete com.example:my-slice:1.0.0
```

#### blueprint

Blueprint management:

```bash
# Apply a blueprint file
aether blueprint apply <file.toml>

# List all deployed blueprints
aether blueprint list [--format table|json]

# Get blueprint details
aether blueprint get <blueprintId> [--format table|json]

# Show deployment status of a blueprint
aether blueprint status <blueprintId> [--format table|json]

# Validate a blueprint file without deploying
aether blueprint validate <file.toml>

# Delete a blueprint
aether blueprint delete <blueprintId> [-f|--force]
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
aether blueprint validate order-system.toml

# Apply the blueprint
aether blueprint apply order-system.toml

# Check deployment status
aether blueprint status order-system:1.0.0

# List all blueprints
aether blueprint list

# Get details for a specific blueprint
aether blueprint get order-system:1.0.0

# Delete a blueprint (with force to skip confirmation)
aether blueprint delete order-system:1.0.0 -f
```

#### update

Rolling update management for zero-downtime deployments:

```bash
# Start a rolling update
aether update start <group:artifact> <version> [options]

# Options:
#   -n, --instances <n>      Number of new version instances (default: 1)
#   --error-rate <rate>      Max error rate threshold 0.0-1.0 (default: 0.01)
#   --latency <ms>           Max latency threshold in ms (default: 500)
#   --manual-approval        Require manual approval for routing changes
#   --cleanup <policy>       IMMEDIATE, GRACE_PERIOD, MANUAL (default: GRACE_PERIOD)

# Get update status
aether update status <updateId>

# List active updates
aether update list

# Adjust traffic routing (ratio new:old)
aether update routing <updateId> -r <ratio>

# Manually approve routing configuration
aether update approve <updateId>

# Complete update (all traffic to new version)
aether update complete <updateId>

# Rollback to old version
aether update rollback <updateId>

# View version health metrics
aether update health <updateId>
```

Example rolling update workflow:
```bash
# Start update: deploy 3 instances of v2.0.0 with 0% traffic
aether update start org.example:order-processor 2.0.0 -n 3

# Gradually shift traffic
aether update routing abc123 -r 1:3    # 25% new, 75% old
aether update routing abc123 -r 1:1    # 50% new, 50% old
aether update routing abc123 -r 3:1    # 75% new, 25% old
aether update routing abc123 -r 1:0    # 100% new

# Complete and cleanup old version
aether update complete abc123

# Or rollback if issues detected
aether update rollback abc123
```

#### invocation-metrics

View per-method invocation metrics:

```bash
# List all metrics
aether invocation-metrics list

# Show slow invocations
aether invocation-metrics slow

# Show or set threshold strategy
aether invocation-metrics strategy              # Show current
aether invocation-metrics strategy fixed 100    # Fixed 100ms threshold
aether invocation-metrics strategy adaptive 10 1000  # Adaptive 10-1000ms
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
```

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
```

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

Aether v0.18.0 - Connected to localhost:8080
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
aether node lifecycle

# Get lifecycle state for a specific node
aether node lifecycle <nodeId>

# Drain a node (ON_DUTY → DRAINING, CDM evacuates slices respecting budget)
aether node drain <nodeId>

# Activate a node (DRAINING/DECOMMISSIONED → ON_DUTY)
aether node activate <nodeId>

# Shut down a node (any → SHUTTING_DOWN)
aether node shutdown <nodeId>
```

Example workflow:
```bash
# Check current lifecycle states
aether node lifecycle

# Drain a node before maintenance
aether node drain node-2

# Verify it's draining
aether node lifecycle node-2

# Cancel drain and return to active duty
aether node activate node-2

# Initiate shutdown
aether node shutdown node-3
```

#### scheduled-tasks

Manage scheduled tasks:

```bash
# List all scheduled tasks with active timer count
aether scheduled-tasks
aether scheduled-tasks list

# Get scheduled tasks filtered by config section
aether scheduled-tasks get <configSection>
```

Example:
```bash
# Show all scheduled tasks
aether scheduled-tasks list

# Get tasks for a specific schedule
aether scheduled-tasks get scheduling.cleanup
```

---

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | General error |
| 2 | Invalid arguments |
| 3 | Connection failed |

## See Also

- [Getting Started](../slice-developers/getting-started.md) - First steps with Aether
- [Forge Guide](../slice-developers/forge-guide.md) - Detailed Forge documentation
- [Scaling Guide](../operators/scaling.md) - Scaling configuration
