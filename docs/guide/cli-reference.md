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
| `-h, --help` | Show help | |
| `-V, --version` | Show version | |

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

#### health

Health check:

```bash
aether health
```

#### deploy

Deploy a slice:

```bash
aether deploy <artifact> [instances]

# Examples
aether deploy org.example:order:1.0.0
aether deploy org.example:order:1.0.0 3
```

#### scale

Scale a slice:

```bash
aether scale <artifact> <instances>

# Example
aether scale org.example:order:1.0.0 5
```

#### undeploy

Remove a slice:

```bash
aether undeploy <artifact>

# Example
aether undeploy org.example:order:1.0.0
```

#### artifact

Artifact repository operations:

```bash
# Deploy JAR to repository
aether artifact deploy <jar-path> -g <groupId> -a <artifactId> -v <version>

# List artifacts
aether artifact list

# List versions
aether artifact versions <group:artifact>
```

Example:
```bash
aether artifact deploy target/my-slice.jar -g com.example -a my-slice -v 1.0.0
```

#### blueprint

Blueprint management:

```bash
# Apply a blueprint file
aether blueprint apply <file.toml>
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

Apply it:
```bash
aether blueprint apply order-system.toml
```

### REPL Mode

Start interactive mode by omitting the command:

```bash
./script/aether.sh --connect localhost:8080

Aether v0.6.2 - Connected to localhost:8080
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

# Deploy a slice with 3 instances
./script/aether.sh deploy org.example:my-slice:1.0.0 3

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
| `--node-id=<id>` | Node identifier | Random UUID |
| `--port=<port>` | Cluster port | 8090 |
| `--peers=<list>` | Comma-separated peer addresses | Self only |

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

---

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | General error |
| 2 | Invalid arguments |
| 3 | Connection failed |

## See Also

- [Getting Started](getting-started.md) - First steps with Aether
- [Forge Guide](forge-guide.md) - Detailed Forge documentation
- [Scaling Guide](scaling.md) - Scaling configuration
