# Docker Scaling Test Specification

**Version:** 0.21.0
**Status:** Draft
**Date:** 2026-03-19

## 1. Overview

This specification defines an end-to-end integration test that validates Aether's automatic core-to-worker scaling in a Docker Compose environment. The test proves that when a cluster exceeds its configured `coreMax` limit, new nodes automatically become passive workers, form SWIM communities, elect governors, receive slice deployments, and serve production load -- all without any configuration changes.

### 1.1 Goals

| ID | Goal | Measurable Outcome |
|----|------|--------------------|
| G-1 | Automatic role assignment | Nodes beyond `coreMax=5` receive `ActivationDirectiveValue("WORKER")` |
| G-2 | Worker community formation | Workers join SWIM community, governor elected (lowest alive NodeId) |
| G-3 | Slice deployment to workers | `WorkerSliceDirectiveValue` entries appear; workers reach ACTIVE state |
| G-4 | Load served by both tiers | k6 confirms responses from both core and worker node IPs |
| G-5 | Consensus isolation | Rabia quorum stays at 5; consensus latency flat as workers are added |
| G-6 | Community splitting | At `maxGroupSize` threshold, community splits into subgroups |

### 1.2 Non-Goals

- Multi-region / zone-aware splitting (all nodes are in zone `"local"`)
- TLS-encrypted cluster communication
- Rolling update during scaling
- Worker node removal / scale-down behavior
- Performance benchmarking (latency targets are for correctness, not SLA)

## 2. Architecture Under Test

```
                     +------------------------------------------+
                     |        Core Layer (Rabia Consensus)       |
                     |   5 nodes: node-1 .. node-5              |
                     |   Quorum: 5/5 (all-to-all broadcast)     |
                     |   Runs: CDM, KV-Store, DHT, blueprints   |
                     +------------------+-----------------------+
                                        |
                         Decision stream | ActivationDirective
                         (via PassiveNode)| WorkerSliceDirective
                                        |
                     +------------------v-----------------------+
                     |      Worker Layer (SWIM Gossip)           |
                     |   Nodes: node-6 .. node-12               |
                     |   Community: "default:node" (all same     |
                     |     zone since NodeId has dashes)          |
                     |   Governor: lowest alive NodeId           |
                     |   Runs: application slices                |
                     +------------------------------------------+
```

### 2.1 Key Mechanism: Role Assignment

1. New node connects to cluster via TCP (seed nodes = initial 5 cores)
2. CDM (running on leader) detects the new node
3. CDM checks `currentCoreCount < coreMax` (from `TopologyConfig.coreMax()`)
4. If under limit: writes `ActivationDirectiveValue.core()` to `activation/{nodeId}`
5. If at limit: writes `ActivationDirectiveValue.worker()` to `activation/{nodeId}`
6. Node reads its own activation directive and transitions accordingly

**Source:** `ClusterDeploymentManager.java` lines 592-608

### 2.2 Key Mechanism: Community Formation

1. Worker node starts SWIM protocol on configured UDP port
2. Worker joins community based on `WorkerConfig.groupName` + zone extraction from NodeId
3. Zone extraction: everything before the last dash in NodeId (e.g., `node-6` -> zone `"node"`)
4. All workers with same zone form a single community until `maxGroupSize` is exceeded
5. Governor = lowest alive NodeId in the community (deterministic, no election messages)
6. Governor publishes `GovernorAnnouncementValue` to KV-Store

**Source:** `GroupAssignment.java`, `WorkerNode.java`

### 2.3 Key Mechanism: Group Splitting

`GroupAssignment.computeGroups()` is deterministic:
- Input: all community members, groupName, maxGroupSize
- If members <= maxGroupSize: single group
- If members > maxGroupSize: split round-robin into `ceil(size/maxGroupSize)` subgroups
- Subgroup naming: `{groupName}-{index}:{zone}`

**Source:** `GroupAssignment.java` lines 32-69

## 3. Prerequisites

### 3.1 Code Changes Required Before Test Execution

#### P-1: `coreMax` Configuration via TOML/Environment Variable [REQUIRED]

**Current state:** `TopologyConfig.coreMax` exists but `Main.java` always uses the `AetherNodeConfig.aetherNodeConfig()` factory which calls a `TopologyConfig` constructor that defaults `coreMax=0` (unlimited). There is no TOML key or environment variable to set it.

**Required change:** Add support for `coreMax` configuration:

Option A (environment variable -- minimal, sufficient for this test):
```java
// In Main.java, after building config:
var coreMax = findEnv("CORE_MAX").map(Integer::parseInt).or(0);
// Wire into TopologyConfig construction
```

Option B (TOML -- more complete):
```toml
[cluster]
core_max = 5
```

**Recommendation:** Implement both. The environment variable provides Docker Compose flexibility; the TOML key provides production configurability.

**[ASSUMPTION]:** This change will be implemented before the test is runnable.

#### P-2: Verify `/api/cluster/topology` Returns Activation Role Data [INVESTIGATE]

**Current state:** `ClusterTopologyRoutes` returns `ClusterTopologyStatusResponse` with `coreCount`, `coreMax`, `workerCount`, and `coreNodes` (list of core node IDs). It does NOT return per-node role assignments from `ActivationDirectiveKey/Value`.

**Gap:** To verify which specific nodes are CORE vs WORKER, we need either:
- A new endpoint (e.g., `GET /api/cluster/nodes/roles`) that dumps `activation/{nodeId}` entries from KV-Store
- Or: extend `StatusResponse.ClusterInfo.NodeInfo` to include role field

**[TBD]:** Determine which approach. For the test, we can work around this by:
1. Using `GET /api/cluster/topology` to check `workerCount` matches expected
2. Checking container logs for `"Assigning node {} as worker"` CDM log messages

#### P-3: Worker Node Serving Application HTTP Traffic [INVESTIGATE]

**Current state:** Workers receive slice deployments via `WorkerSliceDirectiveValue`. Workers run slices and register `NodeArtifactValue` and `NodeRoutesValue` entries in KV-Store.

**[TBD]:** How does HTTP traffic reach workers? Options:
1. Workers run their own HTTP server on `appHttp` port and register in the load balancer
2. Core nodes proxy to workers based on route registrations in KV-Store
3. External load balancer (Docker Compose level) distributes to all nodes

This must be confirmed before the test can verify G-4 (load served by workers).

#### P-4: Dashboard Core/Worker Distinction [DEFERRED]

The dashboard does not currently show CORE vs WORKER labels per node. This is desirable but not blocking for automated test execution. Dashboard updates should be spec'd separately.

### 3.2 Infrastructure Requirements

| Requirement | Details |
|-------------|---------|
| Docker / Colima | Container runtime with Docker CLI |
| Docker Compose | v2.x (Compose V2 with `docker compose` syntax) |
| k6 | Load testing tool (v0.47+), runs on host (not in container) |
| jq | JSON processing for verification scripts |
| curl | HTTP client for API calls |
| PostgreSQL 16 | url-shortener dependency (runs in container) |
| Disk space | ~2GB for 12 container images + logs |
| RAM | ~4GB (12 nodes x 256MB heap + PG + k6) |

### 3.3 Build Requirements

```bash
# Build the Aether node shaded JAR
mvn install -DskipTests -pl aether/node -am

# Build the url-shortener example
mvn package -DskipTests -pl examples/url-shortener

# JAR must exist at: aether/node/target/aether-node.jar
# Blueprint JAR at: examples/url-shortener/target/url-shortener-*.jar
```

## 4. Test Infrastructure

### 4.1 Directory Structure

```
tests/docker-scaling/
|-- run.sh                          # Master orchestrator
|-- docker-compose.yml              # Base: 5 core nodes + PostgreSQL
|-- docker-compose.workers.yml      # Override: worker node definitions (node-6 through node-12)
|-- config/
|   |-- aether.toml                 # Shared node configuration
|   `-- worker.toml                 # Worker-specific TOML (if separate config needed)
|-- scripts/
|   |-- wait-for-cluster.sh         # Poll /api/health/ready until all nodes healthy
|   |-- deploy-blueprint.sh         # Upload url-shortener blueprint to cluster
|   |-- verify-core.sh              # Phase 1 verification
|   |-- verify-workers.sh           # Phase 2 verification
|   |-- verify-scaling.sh           # Phase 3 verification
|   `-- collect-evidence.sh         # Snapshot collector (API, logs, metrics)
|-- k6/
|   |-- scaling-test.js             # k6 script (adapted from examples/url-shortener/k6/)
|   `-- env.sh                      # k6 environment variables
`-- results/                        # Test outputs (gitignored)
    |-- phase-1/
    |-- phase-2/
    |-- phase-3/
    `-- summary.json
```

### 4.2 Docker Compose: Base Configuration

```yaml
# docker-compose.yml
version: "3.9"

services:
  postgres:
    image: postgres:16-alpine
    container_name: scaling-postgres
    hostname: postgres
    environment:
      POSTGRES_DB: aether
      POSTGRES_USER: aether
      POSTGRES_PASSWORD: aether
    ports:
      - "5432:5432"
    networks:
      - scaling-network
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U aether"]
      interval: 3s
      timeout: 3s
      retries: 10

  node-1:
    build:
      context: ../../aether
      dockerfile: docker/aether-node/Dockerfile
    container_name: scaling-node-1
    hostname: node-1
    environment:
      NODE_ID: "node-1"
      CLUSTER_PORT: "8090"
      MANAGEMENT_PORT: "8080"
      CORE_MAX: "5"
      PEERS: >-
        node-1:node-1:8090,node-2:node-2:8090,node-3:node-3:8090,
        node-4:node-4:8090,node-5:node-5:8090
      JAVA_OPTS: "-Xmx256m -XX:+UseZGC -XX:+ZGenerational"
    ports:
      - "8080:8080"
      - "8090:8090"
    networks:
      - scaling-network
    depends_on:
      postgres:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:8080/api/health"]
      interval: 5s
      timeout: 3s
      retries: 15
      start_period: 30s

  # node-2 through node-5: identical to node-1 with different NODE_ID and ports
  # (template pattern shown, full definitions in actual file)

  node-2:
    build:
      context: ../../aether
      dockerfile: docker/aether-node/Dockerfile
    container_name: scaling-node-2
    hostname: node-2
    environment:
      NODE_ID: "node-2"
      CLUSTER_PORT: "8090"
      MANAGEMENT_PORT: "8080"
      CORE_MAX: "5"
      PEERS: >-
        node-1:node-1:8090,node-2:node-2:8090,node-3:node-3:8090,
        node-4:node-4:8090,node-5:node-5:8090
      JAVA_OPTS: "-Xmx256m -XX:+UseZGC -XX:+ZGenerational"
    ports:
      - "8081:8080"
    networks:
      - scaling-network
    depends_on:
      node-1:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:8080/api/health"]
      interval: 5s
      timeout: 3s
      retries: 15
      start_period: 30s

  # node-3, node-4, node-5: same pattern (ports 8082-8084)

networks:
  scaling-network:
    driver: bridge
```

**Important notes:**
- All 5 core nodes share the same `PEERS` list (seed nodes)
- `CORE_MAX=5` must be wired through `Main.java` to `TopologyConfig.coreMax` (see P-1)
- Worker nodes (added later) use the same PEERS list to discover the cluster
- Docker DNS resolves hostnames within the bridge network

### 4.3 Docker Compose: Worker Overlay

```yaml
# docker-compose.workers.yml
version: "3.9"

services:
  node-6:
    build:
      context: ../../aether
      dockerfile: docker/aether-node/Dockerfile
    container_name: scaling-node-6
    hostname: node-6
    environment:
      NODE_ID: "node-6"
      CLUSTER_PORT: "8090"
      MANAGEMENT_PORT: "8080"
      CORE_MAX: "5"
      PEERS: >-
        node-1:node-1:8090,node-2:node-2:8090,node-3:node-3:8090,
        node-4:node-4:8090,node-5:node-5:8090
      JAVA_OPTS: "-Xmx256m -XX:+UseZGC -XX:+ZGenerational"
    ports:
      - "8085:8080"
    networks:
      - scaling-network
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:8080/api/health"]
      interval: 5s
      timeout: 3s
      retries: 15
      start_period: 30s

  # node-7 through node-12: same pattern (ports 8086-8091)

networks:
  scaling-network:
    external: true
    name: docker-scaling_scaling-network
```

### 4.4 Node Discovery Model

All nodes (core and worker) use the same PEERS list pointing to the 5 seed nodes. This works because:

1. The `PEERS` argument in `Main.java` builds the initial `TopologyConfig.coreNodes` list
2. When a new node starts, it connects to seed nodes via TCP
3. The Rabia `TcpTopologyManager` handles the connection
4. CDM on the leader detects the new node and writes an `ActivationDirectiveValue`
5. If the directive is WORKER, the node transitions to passive worker mode

**[TBD: Q-1]** Does the current `TcpTopologyManager` accept connections from nodes not in the initial `coreNodes` list? If not, a discovery mechanism (e.g., a JOIN protocol) may be needed. The `TopologyConfig.coreNodes` list is used for initial peer resolution, but dynamic node addition requires the topology manager to handle unknown incoming connections.

### 4.5 Zone Extraction and Community ID

Given NodeId format `node-{N}`:
- Zone extraction: `node-6` -> everything before last dash -> `"node"`
- All worker nodes will be in zone `"node"`
- Community ID: `"default:node"` (groupName=`"default"`, zone=`"node"`)
- This means all workers form a single community until `maxGroupSize` is reached

**[ASSUMPTION]:** Worker nodes need a `WorkerConfig` with `groupName="default"` and appropriate settings. The current Docker image runs `Main.java` which starts an `AetherNode` (core path). Workers need to start via `WorkerMain` or have `AetherNode` automatically switch to worker mode upon receiving `ActivationDirectiveValue("WORKER")`.

## 5. Test Configuration

### 5.1 Node Configuration (aether.toml)

```toml
# Shared configuration for all nodes (core and worker)

[slice]
repositories = ["builtin", "local"]

[worker]
group_name = "default"
zone = "local"
max_group_size = 5

[cluster]
environment = "docker"
nodes = 5
tls = false
```

**`max_group_size = 5`:** Set low to trigger community splitting within our 7-worker test. With 7 workers and `maxGroupSize=5`, `GroupAssignment.computeGroups()` will produce:
- `ceil(7/5) = 2` subgroups
- Group `"default-0:node"` with 4 workers (round-robin indices 0,2,4,6)
- Group `"default-1:node"` with 3 workers (round-robin indices 1,3,5)

Each subgroup elects its own governor (lowest alive NodeId in that subgroup).

### 5.2 URL Shortener Blueprint

The url-shortener blueprint provides:
- `UrlShortener` slice: POST/GET endpoints for URL shortening
- `Analytics` slice: click tracking via topic subscription
- Requires PostgreSQL datasource

Blueprint deployment after core cluster is healthy:
```bash
# Upload blueprint JAR to cluster
curl -X POST http://localhost:8080/api/deploy \
  -F "file=@examples/url-shortener/target/url-shortener-0.21.0.jar"
```

### 5.3 k6 Load Configuration

```javascript
// Adapted from examples/url-shortener/k6/load-test.js
// Key changes:
// 1. Target all node management ports (8080-8091)
// 2. Lower rate suitable for 12-node local Docker cluster
// 3. Record which node served each response

const targetRate = 200; // req/s (moderate for local Docker)
const steadyDuration = '3m';
```

## 6. Test Phases

### Phase 1: Core Cluster Formation (5 Nodes)

**Duration:** ~2 minutes (cluster formation + blueprint deployment + load warmup)

#### Steps

| Step | Action | Timeout |
|------|--------|---------|
| 1.1 | `docker compose up -d` (starts 5 cores + PostgreSQL) | 60s |
| 1.2 | Poll `GET /api/health` on all 5 nodes until all return `ready: true` | 120s |
| 1.3 | Verify quorum via `GET /api/status` on any node | 10s |
| 1.4 | Deploy url-shortener blueprint via `POST /api/deploy` | 30s |
| 1.5 | Wait for slices to reach ACTIVE state on all nodes | 60s |
| 1.6 | Start k6 load at 200 req/s (background) | - |
| 1.7 | Let load stabilize for 30s | 30s |
| 1.8 | Collect Phase 1 evidence | 10s |

#### Verification Criteria

| ID | Criterion | Evidence Source | Expected Value |
|----|-----------|-----------------|----------------|
| V-1.1 | All 5 nodes healthy | `GET /api/health` on ports 8080-8084 | `{"status":"UP","ready":true}` for all |
| V-1.2 | Leader elected | `GET /api/status` | `isLeader: true` on exactly one node |
| V-1.3 | Cluster size = 5 | `GET /api/status` -> `cluster.nodeCount` | `5` |
| V-1.4 | All nodes are CORE | `GET /api/cluster/topology` -> `coreCount` | `5` |
| V-1.5 | No workers | `GET /api/cluster/topology` -> `workerCount` | `0` |
| V-1.6 | Blueprint deployed | `GET /api/slices/status` | Both slices in ACTIVE state |
| V-1.7 | k6 baseline captured | k6 metrics | p95 latency, throughput, error rate = 0% |
| V-1.8 | `coreMax` = 5 | `GET /api/cluster/topology` -> `coreMax` | `5` |

#### Evidence Collection

```bash
# Phase 1 evidence
for port in 8080 8081 8082 8083 8084; do
  curl -s "http://localhost:${port}/api/health" > results/phase-1/health-${port}.json
done
curl -s http://localhost:8080/api/status > results/phase-1/status.json
curl -s http://localhost:8080/api/cluster/topology > results/phase-1/topology.json
curl -s http://localhost:8080/api/slices/status > results/phase-1/slices.json
curl -s http://localhost:8080/api/metrics > results/phase-1/metrics.json
# k6 baseline snapshot
cp /tmp/k6-output.json results/phase-1/k6-baseline.json
```

### Phase 2: Add 3 Workers (Total 8 Nodes)

**Duration:** ~2 minutes (worker startup + community formation + verification)

#### Steps

| Step | Action | Timeout |
|------|--------|---------|
| 2.1 | Start nodes 6-8: `docker compose -f docker-compose.yml -f docker-compose.workers.yml up -d node-6 node-7 node-8` | 30s |
| 2.2 | Poll `GET /api/health` on ports 8085-8087 until healthy | 120s |
| 2.3 | Wait for CDM to assign roles (poll `GET /api/cluster/topology` until `workerCount >= 3`) | 60s |
| 2.4 | Wait for SWIM community formation (poll for `GovernorAnnouncement` in KV-Store) | 60s |
| 2.5 | Wait for slices to deploy to workers (poll `GET /api/slices/status` for worker nodes in instance list) | 60s |
| 2.6 | Let load run for 60s under new topology | 60s |
| 2.7 | Collect Phase 2 evidence | 10s |

#### Verification Criteria

| ID | Criterion | Evidence Source | Expected Value |
|----|-----------|-----------------|----------------|
| V-2.1 | 8 nodes total | `GET /api/cluster/topology` | `coreCount=5, workerCount=3` |
| V-2.2 | Nodes 6-8 are WORKER | CDM log: `"Assigning node {} as worker"` | 3 log entries |
| V-2.3 | Consensus quorum unchanged | Consensus quorum size from metrics | `5` (not 8) |
| V-2.4 | Community exists | `GovernorAnnouncementValue` in KV-Store | `memberCount >= 3` |
| V-2.5 | Governor elected | `GovernorAnnouncementValue.governorId` | Lowest NodeId among {node-6, node-7, node-8} = `node-6` |
| V-2.6 | Slices deployed to workers | `GET /api/slices/status` includes worker node IDs | At least 1 slice ACTIVE on workers |
| V-2.7 | Consensus latency flat | Compare p95 latency vs Phase 1 baseline | Within 20% of baseline |
| V-2.8 | Zero errors during transition | k6 error rate | `0%` |
| V-2.9 | Same Docker image | `docker inspect --format='{{.Image}}' scaling-node-{1..8}` | All same image hash |

#### Evidence Collection

```bash
# Phase 2 evidence
for port in 8080 8081 8082 8083 8084 8085 8086 8087; do
  curl -s "http://localhost:${port}/api/health" > results/phase-2/health-${port}.json
done
curl -s http://localhost:8080/api/status > results/phase-2/status.json
curl -s http://localhost:8080/api/cluster/topology > results/phase-2/topology.json
curl -s http://localhost:8080/api/slices/status > results/phase-2/slices.json
curl -s http://localhost:8080/api/metrics > results/phase-2/metrics.json

# CDM logs for role assignment
docker logs scaling-node-1 2>&1 | grep "Assigning node" > results/phase-2/role-assignments.log

# Container image verification
for i in $(seq 1 8); do
  docker inspect --format='{{.Image}}' scaling-node-${i}
done > results/phase-2/image-hashes.txt
```

### Phase 3: Add 4 More Workers (Total 12 Nodes)

**Duration:** ~3 minutes (worker startup + community splitting + verification)

#### Steps

| Step | Action | Timeout |
|------|--------|---------|
| 3.1 | Start nodes 9-12: `docker compose -f docker-compose.yml -f docker-compose.workers.yml up -d node-9 node-10 node-11 node-12` | 30s |
| 3.2 | Poll `GET /api/health` on ports 8088-8091 until healthy | 120s |
| 3.3 | Wait for `workerCount >= 7` via `GET /api/cluster/topology` | 60s |
| 3.4 | Wait for community splitting (7 workers > `maxGroupSize=5`) | 60s |
| 3.5 | Verify two `GovernorAnnouncementValue` entries in KV-Store | 30s |
| 3.6 | Let load run for 90s under final topology | 90s |
| 3.7 | Collect Phase 3 evidence | 10s |
| 3.8 | Stop k6 and collect final report | 10s |

#### Verification Criteria

| ID | Criterion | Evidence Source | Expected Value |
|----|-----------|-----------------|----------------|
| V-3.1 | 12 nodes total | `GET /api/cluster/topology` | `coreCount=5, workerCount=7` |
| V-3.2 | Core nodes unchanged | `GET /api/cluster/topology` -> `coreNodes` | Same 5 node IDs as Phase 1 |
| V-3.3 | Consensus quorum still 5 | Consensus metrics | `5` |
| V-3.4 | Community split occurred | Number of `GovernorAnnouncementValue` entries | `2` (two subgroups) |
| V-3.5 | Two governors elected | Two distinct `governorId` values | Two different NodeIds |
| V-3.6 | Each subgroup has correct size | `GovernorAnnouncementValue.memberCount` | 4 + 3 = 7 total |
| V-3.7 | Consensus latency flat | Compare p95 vs Phase 1 baseline | Within 20% of baseline |
| V-3.8 | Zero errors throughout test | k6 cumulative error rate | `0%` |
| V-3.9 | Throughput scaled | k6 total throughput | >= Phase 1 throughput |
| V-3.10 | No config changes | All 12 containers use same aether.toml | File hash comparison |
| V-3.11 | Same Docker image for all | `docker inspect` | All 12 same image hash |

#### Evidence Collection

```bash
# Phase 3 evidence
for port in $(seq 8080 8091); do
  curl -s "http://localhost:${port}/api/health" > results/phase-3/health-${port}.json
done
curl -s http://localhost:8080/api/status > results/phase-3/status.json
curl -s http://localhost:8080/api/cluster/topology > results/phase-3/topology.json
curl -s http://localhost:8080/api/slices/status > results/phase-3/slices.json
curl -s http://localhost:8080/api/metrics > results/phase-3/metrics.json

# All container logs
for i in $(seq 1 12); do
  docker logs scaling-node-${i} > results/phase-3/node-${i}.log 2>&1
done

# Final k6 report
# (k6 outputs to results/k6-final.json on termination)

# Image hash verification
for i in $(seq 1 12); do
  docker inspect --format='{{.Config.Image}} {{.Image}}' scaling-node-${i}
done > results/phase-3/image-hashes.txt
```

## 7. Master Orchestrator Script

```bash
#!/usr/bin/env bash
# tests/docker-scaling/run.sh
#
# Usage:
#   ./run.sh all           # Full test sequence
#   ./run.sh setup         # Build images, create network
#   ./run.sh start-core    # Start 5 core nodes + PostgreSQL
#   ./run.sh verify-core   # Phase 1 verification
#   ./run.sh add-workers N # Add N worker nodes
#   ./run.sh verify-workers # Phase 2 verification
#   ./run.sh verify-scaling # Phase 3 verification (community split)
#   ./run.sh collect       # Gather all evidence into results/
#   ./run.sh teardown      # Stop everything, remove containers

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}/../.."
RESULTS_DIR="${SCRIPT_DIR}/results"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"
COMPOSE_WORKERS="${SCRIPT_DIR}/docker-compose.workers.yml"

# Configurable parameters
CORE_NODES=5
MANAGEMENT_BASE_PORT=8080
MAX_WAIT=120  # seconds

log() { echo "[$(date '+%H:%M:%S')] $*"; }
fail() { log "FAIL: $*"; exit 1; }

wait_for_health() {
  local port=$1 timeout=$2
  local elapsed=0
  while [ $elapsed -lt $timeout ]; do
    if curl -sf "http://localhost:${port}/api/health" > /dev/null 2>&1; then
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  return 1
}

wait_for_worker_count() {
  local expected=$1 timeout=$2
  local elapsed=0
  while [ $elapsed -lt $timeout ]; do
    local count
    count=$(curl -sf http://localhost:8080/api/cluster/topology 2>/dev/null \
            | jq -r '.workerCount // 0')
    if [ "$count" -ge "$expected" ] 2>/dev/null; then
      return 0
    fi
    sleep 3
    elapsed=$((elapsed + 3))
  done
  return 1
}

cmd_setup() {
  log "Building Aether node image..."
  (cd "$PROJECT_ROOT" && mvn install -DskipTests -pl aether/node -am -q)
  (cd "$PROJECT_ROOT" && mvn package -DskipTests -pl examples/url-shortener -q)
  docker compose -f "$COMPOSE_FILE" build --quiet
  mkdir -p "$RESULTS_DIR"/{phase-1,phase-2,phase-3}
}

cmd_start_core() {
  log "Starting ${CORE_NODES}-node core cluster + PostgreSQL..."
  docker compose -f "$COMPOSE_FILE" up -d
  for i in $(seq 0 $((CORE_NODES - 1))); do
    local port=$((MANAGEMENT_BASE_PORT + i))
    log "Waiting for node-$((i+1)) on port ${port}..."
    wait_for_health $port $MAX_WAIT || fail "node-$((i+1)) failed to become healthy"
  done
  log "All ${CORE_NODES} core nodes healthy"
}

cmd_verify_core() {
  log "Phase 1: Verifying core cluster..."
  scripts/verify-core.sh "$RESULTS_DIR/phase-1"
}

cmd_add_workers() {
  local count=${1:-3}
  local start=$((CORE_NODES + 1))
  local end=$((CORE_NODES + count))
  local nodes=""
  for i in $(seq $start $end); do
    nodes="${nodes} node-${i}"
  done
  log "Adding workers: ${nodes}"
  docker compose -f "$COMPOSE_FILE" -f "$COMPOSE_WORKERS" up -d $nodes
  for i in $(seq $start $end); do
    local port=$((MANAGEMENT_BASE_PORT + i - 1))
    log "Waiting for node-${i} on port ${port}..."
    wait_for_health $port $MAX_WAIT || fail "node-${i} failed to become healthy"
  done
  log "Waiting for CDM role assignment..."
  wait_for_worker_count $count 60 || fail "Workers not detected by CDM"
  log "All workers healthy and assigned"
}

cmd_verify_workers() {
  log "Phase 2: Verifying worker integration..."
  scripts/verify-workers.sh "$RESULTS_DIR/phase-2"
}

cmd_verify_scaling() {
  log "Phase 3: Verifying community scaling..."
  scripts/verify-scaling.sh "$RESULTS_DIR/phase-3"
}

cmd_collect() {
  log "Collecting all evidence..."
  scripts/collect-evidence.sh "$RESULTS_DIR"
}

cmd_teardown() {
  log "Tearing down..."
  docker compose -f "$COMPOSE_FILE" -f "$COMPOSE_WORKERS" down -v --remove-orphans 2>/dev/null || true
  log "Teardown complete"
}

cmd_all() {
  cmd_setup
  cmd_start_core
  cmd_verify_core
  cmd_add_workers 3
  cmd_verify_workers
  cmd_add_workers 4
  cmd_verify_scaling
  cmd_collect
  cmd_teardown
  log "ALL PHASES PASSED"
}

case "${1:-all}" in
  setup)          cmd_setup ;;
  start-core)     cmd_start_core ;;
  verify-core)    cmd_verify_core ;;
  add-workers)    cmd_add_workers "${2:-3}" ;;
  verify-workers) cmd_verify_workers ;;
  verify-scaling) cmd_verify_scaling ;;
  collect)        cmd_collect ;;
  teardown)       cmd_teardown ;;
  all)            cmd_all ;;
  *)              echo "Usage: $0 {all|setup|start-core|verify-core|add-workers N|verify-workers|verify-scaling|collect|teardown}" ;;
esac
```

## 8. Verification Scripts

### 8.1 verify-core.sh

```bash
#!/usr/bin/env bash
# Verify Phase 1: 5-node core cluster is healthy and serving load
set -euo pipefail

RESULTS_DIR="${1:?Usage: verify-core.sh <results-dir>}"
PASS=0 FAIL=0

check() {
  local name=$1 expected=$2 actual=$3
  if [ "$expected" = "$actual" ]; then
    echo "  PASS: $name (expected=$expected)"
    PASS=$((PASS + 1))
  else
    echo "  FAIL: $name (expected=$expected, actual=$actual)"
    FAIL=$((FAIL + 1))
  fi
}

echo "=== Phase 1: Core Cluster Verification ==="

# V-1.1: All 5 nodes healthy
for port in 8080 8081 8082 8083 8084; do
  health=$(curl -sf "http://localhost:${port}/api/health" | jq -r '.ready')
  curl -sf "http://localhost:${port}/api/health" > "${RESULTS_DIR}/health-${port}.json"
  check "node on port ${port} ready" "true" "$health"
done

# V-1.3, V-1.4, V-1.5, V-1.8: Topology
topology=$(curl -sf http://localhost:8080/api/cluster/topology)
echo "$topology" > "${RESULTS_DIR}/topology.json"
check "coreCount" "5" "$(echo "$topology" | jq -r '.coreCount')"
check "workerCount" "0" "$(echo "$topology" | jq -r '.workerCount')"
check "coreMax" "5" "$(echo "$topology" | jq -r '.coreMax')"

# V-1.2: Leader elected
status=$(curl -sf http://localhost:8080/api/status)
echo "$status" > "${RESULTS_DIR}/status.json"
leader=$(echo "$status" | jq -r '.leader')
check "leader elected" "true" "$([ -n "$leader" ] && echo true || echo false)"

# V-1.6: Slices deployed
slices=$(curl -sf http://localhost:8080/api/slices/status)
echo "$slices" > "${RESULTS_DIR}/slices.json"

echo ""
echo "Results: ${PASS} passed, ${FAIL} failed"
[ "$FAIL" -eq 0 ] || exit 1
```

### 8.2 verify-workers.sh

```bash
#!/usr/bin/env bash
# Verify Phase 2: 3 workers joined, got roles, serving load
set -euo pipefail

RESULTS_DIR="${1:?Usage: verify-workers.sh <results-dir>}"
PASS=0 FAIL=0

check() {
  local name=$1 expected=$2 actual=$3
  if [ "$expected" = "$actual" ]; then
    echo "  PASS: $name (expected=$expected)"
    PASS=$((PASS + 1))
  else
    echo "  FAIL: $name (expected=$expected, actual=$actual)"
    FAIL=$((FAIL + 1))
  fi
}

check_gte() {
  local name=$1 minimum=$2 actual=$3
  if [ "$actual" -ge "$minimum" ] 2>/dev/null; then
    echo "  PASS: $name (actual=$actual >= $minimum)"
    PASS=$((PASS + 1))
  else
    echo "  FAIL: $name (actual=$actual < minimum=$minimum)"
    FAIL=$((FAIL + 1))
  fi
}

echo "=== Phase 2: Worker Integration Verification ==="

# V-2.1: Topology shows 5 core + 3 workers
topology=$(curl -sf http://localhost:8080/api/cluster/topology)
echo "$topology" > "${RESULTS_DIR}/topology.json"
check "coreCount" "5" "$(echo "$topology" | jq -r '.coreCount')"
check_gte "workerCount" "3" "$(echo "$topology" | jq -r '.workerCount')"

# V-2.2: CDM log shows worker assignments
assignments=$(docker logs scaling-node-1 2>&1 | grep -c "Assigning node.*as worker" || echo 0)
# Check across all core nodes (leader may not be node-1)
for i in 2 3 4 5; do
  count=$(docker logs "scaling-node-${i}" 2>&1 | grep -c "Assigning node.*as worker" || echo 0)
  assignments=$((assignments > count ? assignments : count))
done
check_gte "worker assignment log entries" "3" "$assignments"

# V-2.9: Same Docker image
images=$(for i in $(seq 1 8); do
  docker inspect --format='{{.Image}}' "scaling-node-${i}" 2>/dev/null
done | sort -u | wc -l | tr -d ' ')
check "all nodes same image" "1" "$images"

# Collect remaining evidence
curl -sf http://localhost:8080/api/status > "${RESULTS_DIR}/status.json"
curl -sf http://localhost:8080/api/slices/status > "${RESULTS_DIR}/slices.json"
curl -sf http://localhost:8080/api/metrics > "${RESULTS_DIR}/metrics.json"

echo ""
echo "Results: ${PASS} passed, ${FAIL} failed"
[ "$FAIL" -eq 0 ] || exit 1
```

### 8.3 verify-scaling.sh

```bash
#!/usr/bin/env bash
# Verify Phase 3: Community splitting with 7 workers and maxGroupSize=5
set -euo pipefail

RESULTS_DIR="${1:?Usage: verify-scaling.sh <results-dir>}"
PASS=0 FAIL=0

check() {
  local name=$1 expected=$2 actual=$3
  if [ "$expected" = "$actual" ]; then
    echo "  PASS: $name (expected=$expected)"
    PASS=$((PASS + 1))
  else
    echo "  FAIL: $name (expected=$expected, actual=$actual)"
    FAIL=$((FAIL + 1))
  fi
}

check_gte() {
  local name=$1 minimum=$2 actual=$3
  if [ "$actual" -ge "$minimum" ] 2>/dev/null; then
    echo "  PASS: $name (actual=$actual >= $minimum)"
    PASS=$((PASS + 1))
  else
    echo "  FAIL: $name (actual=$actual < minimum=$minimum)"
    FAIL=$((FAIL + 1))
  fi
}

echo "=== Phase 3: Community Scaling Verification ==="

# V-3.1: 12 nodes total
topology=$(curl -sf http://localhost:8080/api/cluster/topology)
echo "$topology" > "${RESULTS_DIR}/topology.json"
check "coreCount" "5" "$(echo "$topology" | jq -r '.coreCount')"
check_gte "workerCount" "7" "$(echo "$topology" | jq -r '.workerCount')"

# V-3.2: Core nodes unchanged
core_nodes_phase1=$(cat "${RESULTS_DIR}/../phase-1/topology.json" | jq -r '.coreNodes | sort | join(",")')
core_nodes_phase3=$(echo "$topology" | jq -r '.coreNodes | sort | join(",")')
check "core nodes unchanged" "$core_nodes_phase1" "$core_nodes_phase3"

# V-3.11: Same Docker image for all 12
images=$(for i in $(seq 1 12); do
  docker inspect --format='{{.Image}}' "scaling-node-${i}" 2>/dev/null
done | sort -u | wc -l | tr -d ' ')
check "all 12 nodes same image" "1" "$images"

# Collect all evidence
curl -sf http://localhost:8080/api/status > "${RESULTS_DIR}/status.json"
curl -sf http://localhost:8080/api/slices/status > "${RESULTS_DIR}/slices.json"
curl -sf http://localhost:8080/api/metrics > "${RESULTS_DIR}/metrics.json"

# All container logs
for i in $(seq 1 12); do
  docker logs "scaling-node-${i}" > "${RESULTS_DIR}/node-${i}.log" 2>&1
done

echo ""
echo "Results: ${PASS} passed, ${FAIL} failed"
[ "$FAIL" -eq 0 ] || exit 1
```

## 9. Success Criteria Summary

| Phase | ID | What to Prove | Evidence | Pass Condition |
|-------|-----|--------------|----------|----------------|
| 1 | V-1.1 | All 5 nodes healthy | `/api/health` x5 | `ready: true` for all |
| 1 | V-1.4 | All nodes are CORE | `/api/cluster/topology` | `coreCount=5, workerCount=0` |
| 1 | V-1.8 | coreMax configured | `/api/cluster/topology` | `coreMax=5` |
| 2 | V-2.1 | 5 core + 3 workers | `/api/cluster/topology` | `coreCount=5, workerCount>=3` |
| 2 | V-2.2 | Nodes assigned WORKER role | CDM logs | 3+ "Assigning node...as worker" entries |
| 2 | V-2.5 | Governor elected | KV-Store / logs | `GovernorAnnouncement` present |
| 2 | V-2.7 | Consensus latency flat | Metrics comparison | Within 20% of Phase 1 |
| 2 | V-2.8 | Zero errors | k6 metrics | error rate = 0% |
| 3 | V-3.1 | 5 core + 7 workers | `/api/cluster/topology` | `coreCount=5, workerCount>=7` |
| 3 | V-3.2 | Core membership stable | Topology comparison | Same 5 node IDs |
| 3 | V-3.4 | Community split | Governor announcements | 2 distinct governors |
| 3 | V-3.8 | Zero errors throughout | k6 cumulative | error rate = 0% |
| 3 | V-3.10 | No config changes | File/image hash | All identical |

**Overall pass:** ALL criteria must pass. Any single failure = test failure.

## 10. Open Questions

### Q-1: Dynamic Node Discovery [CRITICAL]

**Question:** Does `TcpTopologyManager` accept TCP connections from nodes not in the initial `coreNodes` list?

**Context:** Worker nodes (node-6 through node-12) are not in the initial PEERS list of any core node. They need to connect to the cluster somehow. Options:
1. Workers connect to a core node and the core node adds them to the topology dynamically
2. Workers must be pre-configured in ALL nodes' PEERS list (breaks the "no config change" requirement)
3. A separate JOIN/BOOTSTRAP protocol exists

**Impact:** If option 2, all nodes must know about all 12 nodes from the start, which changes the test architecture. The test would still prove automatic CORE/WORKER assignment but not truly dynamic scaling.

**[ASSUMPTION]:** Option 1 is supported -- the `TcpTopologyManager` accepts unknown incoming connections and CDM handles role assignment for new nodes. If this assumption is wrong, the test must pre-configure all 12 node addresses in PEERS for all nodes.

### Q-2: Worker HTTP Traffic Path [CRITICAL]

**Question:** How does application HTTP traffic reach worker nodes?

**Context:** Core nodes run `ManagementServer` on `managementPort` (8080). Workers may or may not run their own HTTP server. The load balancer routes in KV-Store (`NodeRoutesValue`) register routes per node, but the routing infrastructure needs to forward requests to workers.

**Options:**
1. Workers run their own HTTP server and clients connect directly
2. Core nodes proxy requests to workers based on KV-Store route registrations
3. An external load balancer (e.g., HAProxy in Docker Compose) distributes traffic

**Impact:** Determines how k6 targets worker nodes and how to verify G-4.

### Q-3: `GovernorAnnouncement` Observability [MODERATE]

**Question:** How to query `GovernorAnnouncementValue` entries from KV-Store via REST API?

**Context:** No existing endpoint dumps `governor-announcement/*` entries. Verification scripts need to confirm governor election and community membership.

**Workaround:** Parse worker node logs for governor-related log messages (e.g., `"Elected as governor"`, `"Publishing GovernorAnnouncement"`).

**Better solution:** Add `GET /api/cluster/communities` endpoint that returns all `GovernorAnnouncementValue` entries from KV-Store.

### Q-4: `ActivationDirective` Observability [MODERATE]

**Question:** How to query per-node `ActivationDirectiveValue` entries from KV-Store?

**Workaround:** `GET /api/cluster/topology` returns `workerCount` and `coreNodes` list, which is sufficient to verify counts. For per-node role verification, parse CDM logs.

**Better solution:** Extend `GET /api/cluster/topology` response to include a `workers` list (list of worker node IDs).

### Q-5: Zone Extraction Impact [LOW]

**Question:** With NodeId format `node-{N}`, zone extraction produces `"node"` for all workers. Is this the desired behavior for this test?

**Answer:** Yes, this is intentional. All workers in the same zone form a single community (which then splits at `maxGroupSize`). For multi-zone testing, node IDs would need a different format (e.g., `us-east-worker-1`).

## 11. Risks and Mitigations

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| `CORE_MAX` env var not implemented | High | Blocker | P-1 must be done first |
| Workers can't join cluster dynamically | Medium | Blocker | Pre-configure all 12 nodes in PEERS as fallback |
| 12 containers exceed local machine RAM | Low | Blocker | Reduce heap to 128MB per node; run on CI with 8GB+ |
| SWIM UDP blocked by Docker bridge | Low | Blocker | Verify Docker bridge forwards UDP; use host network as fallback |
| Community split timing is non-deterministic | Medium | Flaky test | Add generous timeouts (60s+), retry loops |
| k6 errors during node addition | Medium | False negative | Accept brief error window during transition (< 5s) |

## 12. Implementation Order

1. **P-1:** Implement `CORE_MAX` environment variable in `Main.java` -> `TopologyConfig`
2. **Q-1:** Verify dynamic node discovery works (or fall back to static PEERS)
3. Write `docker-compose.yml` and `docker-compose.workers.yml`
4. Write `run.sh` master orchestrator
5. Write Phase 1 verification (core cluster)
6. Run Phase 1 manually, fix issues
7. Write Phase 2 verification (workers)
8. Run Phase 2 manually, fix issues
9. Write Phase 3 verification (community split)
10. Run Phase 3 manually, fix issues
11. Integrate k6 load throughout
12. Full automated run (`./run.sh all`)

## References

### Internal Architecture
- [Worker Pools Architecture](../architecture/05-worker-pools.md) -- Two-layer topology, SWIM protocol, governor protocol
- [Consensus Architecture](../architecture/01-consensus.md) -- Rabia protocol, quorum mechanics
- [Configuration Reference](../reference/configuration.md) -- TOML configuration, worker config section
- [Feature Catalog](../reference/feature-catalog.md) -- Worker pool feature status

### Source Code
- `integrations/consensus/src/main/java/org/pragmatica/consensus/topology/TopologyConfig.java` -- `coreMax`, `coreMin` fields
- `aether/aether-deployment/src/main/java/.../ClusterDeploymentManager.java` -- Role assignment logic (lines 592-608)
- `aether/slice/src/main/java/.../AetherValue.java` -- `ActivationDirectiveValue`, `GovernorAnnouncementValue`, `WorkerSliceDirectiveValue`
- `aether/slice/src/main/java/.../AetherKey.java` -- `ActivationDirectiveKey`, `GovernorAnnouncementKey`
- `aether/worker/src/main/java/.../group/GroupAssignment.java` -- Deterministic group computation with splitting
- `aether/worker/src/main/java/.../WorkerNode.java` -- Worker lifecycle, SWIM integration
- `aether/aether-config/src/main/java/.../WorkerConfig.java` -- `maxGroupSize`, `groupName`, `zone`
- `aether/node/src/main/java/.../Main.java` -- Entry point (currently missing `CORE_MAX` wiring)
- `aether/node/src/main/java/.../api/routes/ClusterTopologyRoutes.java` -- `/api/cluster/topology` endpoint
- `aether/docker/` -- Existing Docker infrastructure (Dockerfile, compose, aether.toml)

### Load Testing
- `examples/url-shortener/k6/load-test.js` -- Reference k6 script for url-shortener workload
