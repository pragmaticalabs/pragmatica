# Aether CLI Reference

## Installation

```bash
# Download
curl -sSL https://aether.pragmatica.dev/install.sh | bash

# Or build from source
git clone https://github.com/pragmatica-lite/aether.git
cd aether && mvn install -DskipTests
alias aether="java -jar cli/target/cli-0.6.1.jar"
```

## Commands Overview

| Command | Description |
|---------|-------------|
| `aether init` | Create new project |
| `aether start` | Start local cluster |
| `aether stop` | Stop cluster |
| `aether status` | Show cluster status |
| `aether deploy` | Deploy a slice |
| `aether undeploy` | Remove a slice |
| `aether scale` | Scale slice instances |
| `aether node` | Manage cluster nodes |
| `aether logs` | View slice logs |
| `aether events` | View cluster events |
| `aether forge` | Chaos testing |

## Project Commands

### aether init

Create a new Aether project:

```bash
aether init my-app
aether init my-app --group-id com.example
aether init my-app --template order-demo
```

Options:
- `--group-id` - Maven group ID (default: com.example)
- `--template` - Project template (minimal, order-demo)
- `--java` - Java version (default: 25)

## Cluster Commands

### aether start

Start a local cluster:

```bash
aether start                    # Single node
aether start --nodes 3          # 3-node cluster
aether start --config app.yml   # From config file
```

Options:
- `--nodes` - Number of nodes (default: 1)
- `--port` - Base port (default: 4040)
- `--config` - Configuration file
- `--background` - Run in background

### aether stop

Stop the cluster:

```bash
aether stop                     # Graceful shutdown
aether stop --force             # Immediate termination
```

### aether status

Show cluster status:

```bash
aether status
aether status --watch           # Live updates
aether status --verbose         # Detailed info
aether status --json            # JSON output
```

Output:
```
Cluster: healthy (3 nodes)

Nodes:
  node-1 (leader)  localhost:4040  CPU: 45%  Heap: 234MB/512MB
  node-2           localhost:4041  CPU: 38%  Heap: 189MB/512MB
  node-3           localhost:4042  CPU: 42%  Heap: 201MB/512MB

Slices:
  order-processor     3 instances  45 req/s  P99: 23ms
  inventory-service   2 instances  120 req/s  P99: 8ms
```

## Deployment Commands

### aether deploy

Deploy a slice:

```bash
aether deploy order-processor
aether deploy com.example:order-processor:1.0.0
aether deploy order-processor --instances 3
aether deploy order-processor --node node-1
```

Options:
- `--instances` - Number of instances (default: 1)
- `--node` - Target node for placement
- `--wait` - Wait for deployment to complete

### aether undeploy

Remove a slice:

```bash
aether undeploy order-processor
aether undeploy order-processor --force
```

Options:
- `--force` - Skip graceful shutdown

### aether scale

Scale slice instances:

```bash
aether scale order-processor --instances 5
aether scale order-processor --min 2 --max 10
aether scale order-processor --target-cpu 70
aether scale order-processor --auto=false
```

Options:
- `--instances` - Exact instance count
- `--min` - Minimum instances
- `--max` - Maximum instances
- `--target-cpu` - Target CPU percentage
- `--target-latency` - Target P99 latency
- `--auto` - Enable/disable auto-scaling

## Node Commands

### aether node add

Add a node to the cluster:

```bash
aether node add --port 4043
aether node add --address node-4.example.com:4040
```

### aether node remove

Remove a node:

```bash
aether node remove node-4
aether node remove node-4 --force
```

### aether node list

List all nodes:

```bash
aether node list
aether node list --json
```

## Observability Commands

### aether logs

View slice logs:

```bash
aether logs order-processor
aether logs order-processor --tail 100
aether logs order-processor --follow
aether logs order-processor --since 1h
aether logs --all                        # All slices
```

Options:
- `--tail` - Number of lines
- `--follow` - Stream new logs
- `--since` - Time range (1h, 30m, etc.)
- `--node` - Logs from specific node

### aether events

View cluster events:

```bash
aether events
aether events --type scaling
aether events --type deployment
aether events --type failure
aether events --last 50
aether events --follow
```

Options:
- `--type` - Event type filter
- `--last` - Number of events
- `--follow` - Stream new events
- `--json` - JSON output

### aether metrics

View metrics:

```bash
aether metrics
aether metrics order-processor
aether metrics --export prometheus
```

## Forge Commands

### aether forge start

Start Forge testing environment:

```bash
aether forge start
aether forge start --nodes 5
aether forge start --headless
aether forge start --config forge.yml
```

Options:
- `--nodes` - Number of simulated nodes
- `--headless` - No dashboard
- `--config` - Configuration file
- `--duration` - Run duration (headless mode)

### aether forge stop

Stop Forge:

```bash
aether forge stop
```

### aether forge status

Show Forge status:

```bash
aether forge status
aether forge status --json
```

### aether forge chaos

Inject chaos:

```bash
aether forge chaos kill-node node-3
aether forge chaos kill-leader
aether forge chaos kill-random
aether forge chaos rolling-restart --delay 30s
aether forge chaos inject-latency --slice order-processor --delay 500ms
aether forge chaos partition --isolate node-2,node-3 --duration 2m
```

### aether forge scenario

Run test scenarios:

```bash
aether forge scenario run node-failure.yml
aether forge scenario run scenarios/          # Run all in directory
aether forge scenario list
aether forge scenario validate my-scenario.yml
```

### aether forge load

Control load generation:

```bash
aether forge load start --rps 200
aether forge load stop
aether forge load status
aether forge load set --rps 500
```

## Configuration Commands

### aether config

Manage configuration:

```bash
aether config show
aether config set scaling.maxInstances 20
aether config get scaling.maxInstances
aether config validate
```

## Utility Commands

### aether version

Show version:

```bash
aether version
# Aether CLI 0.6.1
# Runtime 0.6.1
# Java 25
```

### aether help

Get help:

```bash
aether help
aether help deploy
aether deploy --help
```

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | General error |
| 2 | Invalid arguments |
| 3 | Connection failed |
| 4 | Timeout |
| 5 | Deployment failed |

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `AETHER_HOME` | Aether installation directory | `~/.aether` |
| `AETHER_CONFIG` | Default config file | `aether.yml` |
| `AETHER_CLUSTER` | Cluster address | `localhost:4040` |
| `AETHER_LOG_LEVEL` | CLI log level | `INFO` |

## Configuration File

```yaml
# ~/.aether/config.yml
cluster:
  address: localhost:4040
  timeout: 30s

defaults:
  instances: 2
  scaling:
    min: 1
    max: 10
    targetCpu: 70

logging:
  level: INFO
  format: text  # or json
```

## See Also

- [Getting Started](getting-started.md)
- [Scaling Guide](scaling.md)
- [Forge Guide](forge-guide.md)
