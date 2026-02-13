# Aether Forge & Demos

This document covers Aether Forge (the cluster simulator) and demonstration applications included with Aether.

## Overview

Aether includes:

| Component | Purpose | Audience |
|-----------|---------|----------|
| **Aether Forge** (`forge/`) | Cluster simulator with visual dashboard for resilience testing | Developers, DevOps, executives |
| **Real Cluster Demo** (`script/demo-cluster.sh`) | Multi-process cluster with slice deployment | Developers, DevOps |
| **E-commerce Example** (`examples/ecommerce/`) | Multi-slice business domain example | Developers, architects |

---

## Aether Forge

**Aether Forge** is a single-JVM cluster simulator with a visual web dashboard for testing and demonstrating
Aether's distributed capabilities. It provides:

- **Multi-node simulation**: Run multiple Aether nodes in a single JVM
- **Chaos engineering**: Kill nodes, crash nodes, simulate network partitions
- **Load testing**: Generate configurable request loads
- **Real-time dashboard**: Visualize cluster health, metrics, and events
- **API control**: Full REST API for automation and integration

### Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        ForgeServer                               │
│                     (HTTP port 8888)                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐         │
│  │ ForgeCluster │   │ LoadRunner   │   │ ForgeMetrics │         │
│  │              │   │              │   │              │         │
│  │ ┌────┬────┐  │   │ Configurable │   │ Success rate │         │
│  │ │N1  │N2  │  │◄──│ HTTP load    │──►│ Latency      │         │
│  │ ├────┼────┤  │   │ generation   │   │ Throughput   │         │
│  │ │N3  │N4  │  │   │              │   │              │         │
│  │ ├────┴────┤  │   └──────────────┘   └──────────────┘         │
│  │ │   N5    │  │                                                │
│  │ └─────────┘  │                                                │
│  └──────────────┘                                                │
│                                                                  │
├─────────────────────────────────────────────────────────────────┤
│  ForgeApiHandler          │  StaticFileHandler                   │
│  /api/status              │  /index.html                         │
│  /api/kill/{nodeId}       │  /dashboard.js                       │
│  /api/crash/{nodeId}      │  /style.css                          │
│  /api/add-node            │                                      │
│  /api/rolling-restart     │                                      │
│  /api/load/set/{rate}     │                                      │
│  /api/load/ramp           │                                      │
│  /api/events              │                                      │
│  /api/reset-metrics       │                                      │
└─────────────────────────────────────────────────────────────────┘
```

### Components

| Component | File | Description |
|-----------|------|-------------|
| `ForgeServer` | `forge/src/.../ForgeServer.java` | Main entry point, starts HTTP server and cluster |
| `ForgeCluster` | `forge/src/.../ForgeCluster.java` | Manages multiple AetherNodes in-process |
| `ConfigurableLoadRunner` | `forge/forge-load/src/.../ConfigurableLoadRunner.java` | Generates configurable HTTP load against the cluster |
| `ForgeMetrics` | `forge/src/.../ForgeMetrics.java` | Aggregates success/failure/latency metrics |
| `ForgeApiHandler` | `forge/src/.../ForgeApiHandler.java` | REST API for dashboard interactions |
| `StaticFileHandler` | `forge/src/.../StaticFileHandler.java` | Serves web dashboard files |

### Dashboard Features

The web dashboard (`index.html`) provides:

- **Cluster Topology Visualization**: D3.js-powered node graph showing leader and node states
- **Real-Time Metrics**: Requests/sec, success rate, average latency
- **Charts**: Historical success rate and throughput (Chart.js)
- **Event Timeline**: Scrolling log of cluster events
- **Control Panel**:
    - Chaos operations: Kill node, kill leader, crash node, rolling restart
    - Load control: Slider and preset buttons (1K, 5K, 10K req/sec)
    - Ramp-up functionality for gradual load increase

### Prerequisites

- JDK 25+
- Maven 3.9+
- Built Aether project (`mvn clean install`)

### Running Locally

```bash
# Build Forge
mvn package -pl forge/forge-core -am -DskipTests

# Run with defaults (5 nodes, 1000 req/sec)
java -jar forge/forge-core/target/aether-forge.jar

# Or with custom settings
CLUSTER_SIZE=7 LOAD_RATE=2000 java -jar forge/forge-core/target/aether-forge.jar
```

The dashboard opens automatically at `http://localhost:8888`.

### Running with Docker

```bash
# Build image
cd forge
docker build -t aether-forge .

# Run container
docker run -p 8888:8888 aether-forge

# Or with custom settings
docker run -p 8888:8888 \
  -e CLUSTER_SIZE=7 \
  -e LOAD_RATE=2000 \
  aether-forge
```

### Running with Docker Compose

```bash
cd forge
docker-compose up
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `FORGE_PORT` | 8888 | HTTP server port |
| `CLUSTER_SIZE` | 5 | Initial number of nodes |
| `LOAD_RATE` | 1000 | Initial requests per second |
| `JAVA_OPTS` | (see Dockerfile) | JVM options |

---

## Forge Demo Scenarios

### Scenario 1: Basic Cluster Formation

**Goal**: Demonstrate that the cluster forms and handles load.

**Steps**:

1. Start the demo with defaults
2. Observe dashboard showing 5 healthy nodes
3. Note consistent ~1000 req/sec throughput
4. Success rate should be 100%

**Expected Result**: Green success rate, stable throughput, leader clearly marked.

---

### Scenario 2: Node Failure Recovery

**Goal**: Demonstrate cluster continues operating when a follower node fails.

**Steps**:

1. Start demo with 5 nodes
2. Wait for stable metrics (30 seconds)
3. Click "KILL NODE" and select a non-leader node
4. Observe brief dip in success rate
5. Watch as remaining nodes take over

**Expected Result**:

- Brief dip in success rate (< 5 seconds)
- Throughput recovers to previous level
- Dashboard shows 4 healthy nodes

---

### Scenario 3: Leader Failure Recovery

**Goal**: Demonstrate automatic leader election when leader fails.

**Steps**:

1. Start demo with 5 nodes
2. Wait for stable metrics
3. Click "KILL LEADER"
4. Observe leader re-election

**Expected Result**:

- Slightly longer recovery than follower failure (due to leader election)
- New leader automatically selected (first node in sorted topology)
- Success rate recovers within 5-10 seconds

---

### Scenario 4: Cascading Failures

**Goal**: Demonstrate cluster survives multiple failures (up to N/2 - 1).

**Steps**:

1. Start demo with 5 nodes
2. Kill 2 nodes in succession (wait 5 seconds between kills)
3. Observe that cluster continues operating with 3 nodes
4. Optionally kill a 3rd node to demonstrate quorum loss

**Expected Result**:

- With 3/5 nodes: Cluster operates normally
- With 2/5 nodes: Cluster loses quorum, operations fail

---

### Scenario 5: Node Addition (Scale Out)

**Goal**: Demonstrate dynamic cluster expansion.

**Steps**:

1. Start demo with 3 nodes
2. Observe throughput ceiling
3. Click "+ ADD NODE" repeatedly to add 2 more nodes
4. Observe throughput capacity increase

**Expected Result**:

- New nodes join cluster within seconds
- Load distributes across all nodes
- Throughput capacity increases proportionally

---

### Scenario 6: Rolling Restart

**Goal**: Demonstrate zero-downtime upgrades.

**Steps**:

1. Start demo with 5 nodes at 5000 req/sec
2. Click "ROLLING RESTART"
3. Observe nodes restarting one by one
4. Monitor success rate throughout

**Expected Result**:

- Each node restarts sequentially
- Success rate remains high (may dip briefly per node)
- No total outage during process
- Timeline shows each node restart event

---

### Scenario 7: Load Spike Handling

**Goal**: Demonstrate behavior under sudden load increase.

**Steps**:

1. Start demo with 5 nodes at 1000 req/sec
2. Suddenly set load to 10K req/sec using button
3. Observe latency increase and potential backpressure
4. Click "RAMP UP" to demonstrate gradual increase

**Expected Result**:

- Sudden spike: Higher latency, possible brief success rate drop
- Ramped increase: Smoother transition, stable success rate

---

### Scenario 8: Chaos Engineering Sequence

**Goal**: Comprehensive resilience demonstration for executives.

**Steps**:

1. Start with 5 nodes, 1000 req/sec
2. Wait 30 seconds for baseline
3. Kill a follower node
4. Wait for recovery (10 seconds)
5. Add a new node
6. Wait 10 seconds
7. Kill the leader
8. Wait for new leader election
9. Initiate rolling restart
10. During rolling restart, ramp load to 5K req/sec
11. After restart completes, kill 2 nodes simultaneously

**Expected Result**:

- Cluster survives all operations
- Success rate never drops to 0%
- Recovery happens automatically without manual intervention

---

## Real Cluster Demo

Run a **real multi-process cluster** on your local machine with actual slice deployment.

### Quick Start

```bash
# Build everything
mvn package -DskipTests

# Start 5-node cluster
./script/demo-cluster.sh start

# Deploy ecommerce slices
./script/demo-cluster.sh deploy

# Check status
./script/demo-cluster.sh status

# View logs (all nodes)
./script/demo-cluster.sh logs

# Stop and cleanup
./script/demo-cluster.sh clean
```

### Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Local Machine                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌────────────┐
│  │   node-1    │  │   node-2    │  │   node-3    │  │   node-4    │  │   node-5   │
│  │ Cluster:8091│  │ Cluster:8092│  │ Cluster:8093│  │ Cluster:8094│  │Cluster:8095│
│  │ Mgmt:8081   │  │ Mgmt:8082   │  │ Mgmt:8083   │  │ Mgmt:8084   │  │ Mgmt:8085  │
│  │ (Leader)    │  │             │  │             │  │             │  │            │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘  └────────────┘
│        │                │                │                │                │
│        └────────────────┴────────────────┴────────────────┴────────────────┘
│                              Rabia Consensus (CFT)
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Commands

| Command | Description |
|---------|-------------|
| `start` | Start 5-node cluster (auto-builds if needed) |
| `stop` | Gracefully stop all nodes |
| `status` | Show node health and cluster status |
| `deploy` | Deploy ecommerce blueprint |
| `logs` | Tail all node logs |
| `clean` | Stop cluster and remove logs/pids |

### Endpoints

After starting, access these endpoints:

| Node | Management API | Dashboard |
|------|----------------|-----------|
| node-1 (leader) | http://localhost:8081 | http://localhost:8081/dashboard |
| node-2 | http://localhost:8082 | http://localhost:8082/dashboard |
| node-3 | http://localhost:8083 | http://localhost:8083/dashboard |
| node-4 | http://localhost:8084 | http://localhost:8084/dashboard |
| node-5 | http://localhost:8085 | http://localhost:8085/dashboard |

### Step-by-Step Demo

**1. Start the cluster:**

```bash
./script/demo-cluster.sh start
```

Output:
```
[INFO] Starting 5-node Aether cluster...
[INFO] Starting node-1 on ports 8091 (cluster) / 8081 (mgmt)...
[INFO] node-1 started (PID: 12345)
...
[INFO] Cluster is UP!
```

**2. Verify cluster health:**

```bash
curl http://localhost:8081/health
```

```json
{"status":"UP","quorum":true,"nodeCount":5,"sliceCount":0}
```

**3. Deploy slices:**

```bash
./script/demo-cluster.sh deploy
```

**4. Check deployed slices:**

```bash
./script/demo-cluster.sh status
```

Shows slice distribution across nodes.

**5. Open dashboard:**

Navigate to http://localhost:8081/dashboard for real-time metrics.

**6. Clean up:**

```bash
./script/demo-cluster.sh clean
```

### Logs

Node logs are written to `logs/` directory:

```bash
# Follow all logs
./script/demo-cluster.sh logs

# Or individual nodes
tail -f logs/node-1.log
tail -f logs/node-3.log
```

### Troubleshooting

**Port conflicts:**
```bash
lsof -i :8081-8095  # Check for processes using these ports
```

**Cluster won't form:**
```bash
./script/demo-cluster.sh logs  # Check for errors
./script/demo-cluster.sh clean  # Reset and try again
```

**Slice deployment fails:**
```bash
# Ensure ecommerce example is installed
cd examples/ecommerce && mvn install -DskipTests
```

---

## API Reference

### Forge API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/status` | GET | Full cluster status, metrics, load info |
| `/api/add-node` | POST | Add a new node to the cluster |
| `/api/kill/{nodeId}` | POST | Gracefully stop a node |
| `/api/crash/{nodeId}` | POST | Abruptly terminate a node |
| `/api/rolling-restart` | POST | Restart all nodes sequentially |
| `/api/load/set/{rate}` | POST | Set request rate immediately |
| `/api/load/ramp` | POST | Gradually ramp to target rate |
| `/api/events` | GET | Get event timeline |
| `/api/reset-metrics` | POST | Reset all metrics and events |

### Status Response Format

```json
{
  "cluster": {
    "nodes": [
      {"id": "node-1", "port": 5050, "state": "healthy", "isLeader": true},
      {"id": "node-2", "port": 5051, "state": "healthy", "isLeader": false}
    ],
    "leaderId": "node-1",
    "nodeCount": 5
  },
  "metrics": {
    "requestsPerSecond": 1000.0,
    "successRate": 99.8,
    "avgLatencyMs": 2.5,
    "totalSuccess": 150000,
    "totalFailures": 300
  },
  "load": {
    "currentRate": 1000,
    "targetRate": 1000,
    "running": true
  },
  "uptimeSeconds": 120
}
```

---

## Troubleshooting

### Demo Fails to Start

**Symptom**: `Cluster start failed` error

**Solutions**:

1. Ensure ports 5050-5060 are available
2. Check for other Aether processes: `lsof -i :5050`
3. Increase startup timeout if slow machine

### Dashboard Not Loading

**Symptom**: Browser shows connection refused

**Solutions**:

1. Verify demo is running: `curl http://localhost:8888/api/status`
2. Check firewall settings
3. For Docker, ensure port mapping: `-p 8888:8888`

### Low Success Rate

**Symptom**: Success rate below 90% even with all nodes healthy

**Solutions**:

1. Reduce load rate
2. Increase JVM heap: `JAVA_OPTS="-Xmx4g"`
3. Check system resources (CPU, memory)

### Node Won't Join

**Symptom**: Added node stays in "joining" state

**Solutions**:

1. Check port availability for new node
2. Verify network connectivity between nodes
3. Check logs for consensus errors
