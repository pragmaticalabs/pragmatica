# Aether Integration Test Overhaul Specification

| Field       | Value                                           |
|-------------|-------------------------------------------------|
| Status      | Draft -- ready for implementation               |
| Date        | 2026-04-12                                      |
| Module      | `aether/tests/integration/`                     |
| Related     | cluster-bootstrap-spec.md, ClusterBootstrapConfig |

---

## Table of Contents

1. [Overview](#1-overview)
2. [Goals and Non-Goals](#2-goals-and-non-goals)
3. [Test Environment Abstraction](#3-test-environment-abstraction)
4. [Test Lifecycle](#4-test-lifecycle)
5. [Test Blueprints](#5-test-blueprints)
6. [Execution Strategy](#6-execution-strategy)
7. [Database Provisioning](#7-database-provisioning)
8. [Test Tooling](#8-test-tooling)
9. [Observability and Diagnostics](#9-observability-and-diagnostics)
10. [Migration Path](#10-migration-path)
11. [File Structure](#11-file-structure)
12. [References](#12-references)

---

## 1. Overview

### 1.1 Purpose

The Aether integration test suite consists of 16 suites (60+ shell script tests) that run against a live cluster. Today, provisioning and testing are decoupled manual steps. Port mappings, SSH paths, and environment detection are scattered across env vars and conditionals (`CLOUD_MODE`, port arithmetic, `direct_api_*` failover loops).

This specification defines a unified test framework where a single config file controls provisioning, and the same test scripts run unchanged across forge, docker, SSH, and cloud environments.

### 1.2 Current Pain Points

| Problem | Impact |
|---------|--------|
| Two manual steps: provision then test | Friction, easy to forget cleanup |
| Port hardcoding (compose: 5150-5154, cloud: 8080) | Tests break across environments |
| `CLOUD_MODE` conditionals throughout `common.sh` and `cluster.sh` | Fragile, grows with each new env |
| No forge or local docker support | Cannot test without remote host |
| Sequential execution only | Cloud runs are slow and expensive |
| No capability tagging | Cannot run "just smoke" or "just read-only" |
| Cluster re-provisioned per run | Wastes time when iterating on a single suite |
| Python dependency for TOML parsing | Adds external dependency for simple config reads |
| `ClusterBootstrapCommand` uses raw `printf` | Cannot machine-parse bootstrap output (node IPs, endpoints) |

### 1.3 Design Principles

1. **All environment-specific logic lives in the provisioner and the endpoint resolver.** Test scripts never branch on environment type.
2. **5 core nodes minimum for local-dev.** 3 is too fragile -- a single node failure loses quorum.
3. **Dual-cluster strategy for destructive tests.** Non-destructive and destructive suites run on separate clusters simultaneously to minimize wall time.
4. **Test blueprints as independent projects.** Each blueprint is a standalone Maven project with its own config sections, DB schemas, and stream names. No shared state.
5. **No Python anywhere.** TOML parsing via `aether` CLI or shell builtins. Config templates are TOML. Scripts are shell.
6. **TOML only.** NEVER YAML. All config examples, test configs, and batch command files use TOML.

---

## 2. Goals and Non-Goals

### 2.1 Goals

- REQ-1: Single config file (`test-env.toml`) selects environment type and cluster size
- REQ-2: Provisioning integrated into test lifecycle (`setup-test-env.sh` / `teardown-test-env.sh`)
- REQ-3: Tests tagged by capabilities; subset runs via `--tags smoke,streaming`
- REQ-4: Dual-cluster parallel execution: non-destructive suites on cluster 1, destructive on cluster 2
- REQ-5: Cloud cost optimization (parallel execution, auto-teardown, CI-published Docker images)
- REQ-6: Backward compatibility with current `TARGET_HOST` + `run-all.sh` workflow
- REQ-7: Support all four source types: forge, docker, ssh, cloud
- REQ-8: Step 0 -- fix `ClusterBootstrapCommand` to emit structured output via `OutputFormatter`
- REQ-9: Test blueprints as independent projects with full isolation (DB schema, streams, config)
- REQ-10: Leak audit after teardown to catch orphaned resources
- REQ-11: Test result persistence (`test-results.json`) and timing regression detection
- REQ-12: Flaky test quarantine mechanism

### 2.2 Non-Goals

- Rewriting test logic within individual test scripts (only the harness and library change)
- Kubernetes or container orchestrator support (Aether manages its own topology)
- CI/CD pipeline integration (this spec covers the framework; CI is a consumer)
- Load testing framework changes (k6/curl-based load stays as-is)
- Network partition simulation for cloud or forge (Docker only via `docker network disconnect/connect`)

---

## 3. Test Environment Abstraction

### 3.1 The `test-env.toml` Configuration File

A single TOML file at `aether/tests/integration/test-env.toml` controls the entire test environment. It is a purpose-built test config that references the ClusterBootstrapConfig model for provisioning, then adds test-specific sections.

```toml
# test-env.toml -- Integration test environment configuration

[environment]
name = "local-docker"
type = "docker"                   # forge | docker | ssh | cloud

[cluster]
node_count = 5                    # total core nodes (5 minimum for stability)
min_nodes = 3                     # minimum for quorum
lb = true                         # deploy passive load balancer

[cluster.timeouts]
health_check = 30                 # seconds to wait for cluster health
node_join = 60                    # seconds to wait for node to join
leader_election = 30              # seconds to wait for leader
suite_restore = 120               # seconds to wait for baseline restore after destructive test

[provisioning]
# Points to a ClusterBootstrapConfig-compatible TOML for `aether cluster bootstrap`
bootstrap_config = "envs/local-docker.toml"
skip_build = false                # skip Maven build
skip_examples = false             # skip building example slices

[test]
parallel_suites = true            # run independent suites in parallel
max_parallel = 4                  # max concurrent suite processes
auto_teardown = true              # destroy cluster after run
teardown_timeout = 300            # seconds: auto-teardown if tests hang (cloud safety)
collect_metrics = false           # node metrics collection
```

### 3.2 Config Templates

All config templates live under `aether/tests/integration/envs/`. These are ClusterBootstrapConfig-compatible TOML files that `aether cluster bootstrap` consumes directly.

#### 3.2.1 `local-dev.toml` (Forge, 5 cores)

```toml
config_version = "1"

[cluster]
name = "integration-test"
version = "1.0.0"

[core_topology]
min = 3
max = 7
max_unavailable = 1

[source.local]
type = "forge"

  [source.local.core]
  count = 5
  runtime = "ember"

[runtime.ember]
type = "ember"
java_opts = "-Xmx512m -XX:+UseZGC"

[infrastructure.database.forge-pg]
type = "postgresql"
host = "localhost"
port = 5432
name = "forge"
user = "forge"
password = "forge"
```

Matching `test-env.toml`:

```toml
[environment]
name = "local-forge"
type = "forge"

[cluster]
node_count = 5
min_nodes = 3
lb = false                        # forge: LB runs in-process

[cluster.timeouts]
health_check = 10                 # forge is fast
node_join = 15
leader_election = 10
suite_restore = 30

[provisioning]
bootstrap_config = "envs/local-dev.toml"
skip_build = false

[test]
parallel_suites = true
max_parallel = 4
auto_teardown = true
teardown_timeout = 60
```

#### 3.2.2 `local-docker.toml` (Docker, 5 cores)

```toml
config_version = "1"

[cluster]
name = "integration-test"
version = "1.0.0"

[core_topology]
min = 3
max = 15
max_unavailable = 1

[source.local]
type = "docker"

  [source.local.core]
  count = 5
  runtime = "container"

[runtime.container]
type = "container"
image = "ghcr.io/pragmaticalabs/aether-node:local"
java_opts = "-Xmx512m -XX:+UseZGC -Djava.net.preferIPv4Stack=true"
env = { AETHER_CLUSTER_SECRET = "aether-integration-test-cluster-secret" }

[infrastructure.database.test-pg]
type = "postgresql"
host = "test-postgres"            # same Docker network
port = 5432
name = "aether_test"
user = "aether"
password = "aether"
```

#### 3.2.3 `single-cloud.toml` (Hetzner single-zone, 5 cores + 3 workers, elected LB)

```toml
config_version = "1"

[cluster]
name = "integration-test"
version = "1.0.0"

[core_topology]
min = 3
max = 15
max_unavailable = 1

[source.hetzner-fsn1]
type = "cloud"
provider = "hetzner"
credentials = "${env:HCLOUD_TOKEN}"
region = "eu-central"
zone = "fsn1"
load_balancer = "elected"
user = "root"
key = "${env:AETHER_SSH_KEY}"

  [source.hetzner-fsn1.core]
  count = 5
  runtime = "container"

  [source.hetzner-fsn1.worker]
  count = 3
  runtime = "container"

[runtime.container]
type = "container"
image = "ghcr.io/pragmaticalabs/aether-node:1.0.0-rc1"
java_opts = "-Xmx512m -XX:+UseZGC"
env = { AETHER_CLUSTER_SECRET = "aether-integration-test-cluster-secret" }

[infrastructure.database.cloud-pg]
type = "postgresql"
host = "${first_node}"            # postgres container on first node
port = 5432
name = "aether_test"
user = "aether"
password = "aether"

[operations.auto_heal]
enabled = true
cooldown_seconds = 60
```

#### 3.2.4 `multi-zone.toml` (2 Hetzner zones with template inheritance)

```toml
config_version = "1"
inherits = "single-cloud.toml"

[cluster]
name = "integration-test-multi"

[source.hetzner-fsn1.core]
count = 3

[source.hetzner-nbg1]
type = "cloud"
provider = "hetzner"
credentials = "${env:HCLOUD_TOKEN}"
region = "eu-central"
zone = "nbg1"
load_balancer = "elected"
user = "root"
key = "${env:AETHER_SSH_KEY}"

  [source.hetzner-nbg1.core]
  count = 2
  runtime = "container"

  [source.hetzner-nbg1.worker]
  count = 2
  runtime = "container"
```

#### 3.2.5 `on-prem.toml` (SSH, 5 cores with hosts placeholder)

```toml
config_version = "1"

[cluster]
name = "integration-test"
version = "1.0.0"

[core_topology]
min = 3
max = 15
max_unavailable = 1

[source.remote]
type = "ssh"
user = "${env:AETHER_SSH_USER}"
key = "${env:AETHER_SSH_KEY}"

  [source.remote.core]
  count = 5
  hosts = [
    "${env:NODE_1}",
    "${env:NODE_2}",
    "${env:NODE_3}",
    "${env:NODE_4}",
    "${env:NODE_5}"
  ]
  runtime = "container"

[runtime.container]
type = "container"
image = "ghcr.io/pragmaticalabs/aether-node:1.0.0-rc1"
java_opts = "-Xmx512m -XX:+UseZGC -Djava.net.preferIPv4Stack=true"
env = { AETHER_CLUSTER_SECRET = "aether-integration-test-cluster-secret" }

[infrastructure.database.onprem-pg]
type = "postgresql"
host = "${env:NODE_1}"            # postgres container on first node
port = 5432
name = "aether_test"
user = "aether"
password = "aether"
```

### 3.3 Endpoint Resolution

Tests never compute endpoints. The provisioner writes an **endpoint file** that the test library reads. For environments using `aether cluster bootstrap`, the bootstrap command emits structured JSON output (see Step 0, Section 3.5) which the provisioner parses.

File: `/tmp/aether-test-endpoints.env` (generated by `setup-test-env.sh`)

```bash
# Auto-generated by setup-test-env.sh -- do not edit
CLUSTER_ENDPOINT=http://192.168.0.71:9091
APP_ENDPOINT=http://192.168.0.71:9090
LB_ENDPOINT=http://192.168.0.71:9090
DIRECT_ENDPOINTS=http://192.168.0.71:5150,http://192.168.0.71:5151,http://192.168.0.71:5152,http://192.168.0.71:5153,http://192.168.0.71:5154
MGMT_PORT=5150
APP_PORT=8070
LB_PORT=9090
LB_MGMT_PORT=9091
NODE_COUNT=5
TARGET_HOST=192.168.0.71
ENV_TYPE=docker
ENV_NAME=local-docker
DB_HOST=test-postgres
DB_PORT=5432
DB_NAME=aether_test
DB_USER=aether
DB_PASSWORD=aether
# Capabilities detected from environment
CAP_RESTART=true
CAP_SCALING=true
CAP_PERSISTENCE=true
CAP_NETWORK_PARTITION=true
CAP_CHAOS=true
CAP_LB=true
CAP_SSH=false
CAP_CLOUD_OPS=false
```

For forge:

```bash
CLUSTER_ENDPOINT=http://localhost:8080
APP_ENDPOINT=http://localhost:8070
LB_ENDPOINT=http://localhost:8070
DIRECT_ENDPOINTS=http://localhost:8080,http://localhost:8081,http://localhost:8082,http://localhost:8083,http://localhost:8084
MGMT_PORT=8080
APP_PORT=8070
NODE_COUNT=5
TARGET_HOST=localhost
ENV_TYPE=forge
ENV_NAME=local-forge
DB_HOST=localhost
DB_PORT=5432
DB_NAME=aether_test
DB_USER=aether
DB_PASSWORD=aether
CAP_RESTART=true
CAP_SCALING=true
CAP_PERSISTENCE=true
CAP_NETWORK_PARTITION=false
CAP_CHAOS=true
CAP_LB=false
CAP_SSH=false
CAP_CLOUD_OPS=false
```

### 3.4 Capability Detection

Each environment type declares capabilities that determine which tests can run.

| Capability | forge | docker | ssh | cloud | Description |
|-----------|-------|--------|-----|-------|-------------|
| `CAP_RESTART` | true | true | true | true | Can kill/restart individual nodes |
| `CAP_SCALING` | true | true | true | true | Can add/remove nodes via CTM |
| `CAP_PERSISTENCE` | true | true | true | true | Has PostgreSQL available |
| `CAP_CHAOS` | true | true | true | true | Can kill nodes to test failure recovery |
| `CAP_NETWORK_PARTITION` | false | true | false | false | Can partition network between nodes |
| `CAP_LB` | false | true | true | true | Has separate passive load balancer |
| `CAP_SSH` | false | false | true | true | Can SSH to nodes for container ops |
| `CAP_CLOUD_OPS` | false | false | false | true | Can create/destroy cloud instances |

**Forge chaos is enabled.** `EmberCluster` has `killNode()` and `addNode()` APIs exposed via `ChaosRoutes`. The Forge runtime supports full node lifecycle operations in-process. Tests set `CAP_CHAOS=true` for forge.

**Network partition is Docker-only.** Implemented via `docker network disconnect/connect` (~20 lines of shell). Cloud partition is deferred. Forge partition is impossible (single JVM, shared memory).

### 3.5 Step 0: OutputFormatter for Bootstrap (REQ-8)

`ClusterBootstrapCommand` currently uses raw `System.out.printf()` for all output. This must be fixed before anything else so that `--format json` returns machine-parseable node IPs and endpoints.

**Current state** (raw printf):
```java
System.out.printf("  Cluster:     %s%n", cluster.name());
System.out.printf("  Core nodes:  %d (derived)%n", config.derivedCoreCount());
```

**Required state** (structured output via `OutputFormatter`):

The `ClusterBootstrapCommand` must:
1. Accept `OutputOptions` mixin (already available via `@CommandLine.Mixin`)
2. Build a structured result object with cluster name, node IPs, endpoints, ports
3. Emit via `OutputFormatter.printAction()` for human-readable table or `OutputFormatter.printQuery()` for JSON

After the fix, `aether cluster bootstrap config.toml --format json` outputs:

```json
{
  "cluster": "integration-test",
  "version": "1.0.0",
  "nodes": [
    {"id": "node-0", "host": "192.168.0.71", "mgmt_port": 5150, "app_port": 8070},
    {"id": "node-1", "host": "192.168.0.71", "mgmt_port": 5151, "app_port": 8071}
  ],
  "lb": {"host": "192.168.0.71", "port": 9090, "mgmt_port": 9091},
  "status": "healthy"
}
```

This structured output replaces hardcoded IP discovery in provisioner scripts. The `setup-test-env.sh` script parses this JSON with `aether cluster bootstrap --format json | jq` to populate the endpoint file.

**File:** `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/ClusterBootstrapCommand.java`

### 3.6 LB-Based Test Routing

Non-destructive tests go through the elected LB when present (`CAP_LB=true`). This validates the LB path in addition to the application logic.

Destructive tests use direct node addresses (`DIRECT_ENDPOINTS`) because LB routing is unreliable when nodes are being killed or partitioned.

The test library provides:

```bash
# In lib/common.sh:
test_endpoint() {
    if [ "${TEST_DESTRUCTIVE:-false}" = "true" ]; then
        echo "${DIRECT_ENDPOINTS%%,*}"  # first direct endpoint
    elif [ "${CAP_LB}" = "true" ]; then
        echo "${LB_ENDPOINT}"
    else
        echo "${CLUSTER_ENDPOINT}"
    fi
}
```

---

## 4. Test Lifecycle

### 4.1 Lifecycle Phases

```
                    +-----------+
                    | configure |   Read test-env.toml
                    +-----+-----+
                          |
                    +-----v-----+
                    | provision  |   Start cluster 1 + cluster 2 simultaneously
                    +-----+-----+
                          |
                    +-----v-----+
                    | health     |   Wait for quorum, leader, LB on both clusters
                    +-----+-----+
                          |
                    +-----v-----+
                    | seed       |   Push test blueprints, deploy baseline on both clusters
                    +-----+-----+
                          |
              +-----------+-----------+
              |                       |
        +-----v-----+          +-----v-----+
        | cluster 1  |          | cluster 2  |
        | non-destr. |          | destructive|
        | (parallel) |          | (sequential|
        |            |          |  self-heal)|
        +-----+-----+          +-----+-----+
              |                       |
              +-----------+-----------+
                          |
                    +-----v-----+
                    | teardown   |   Destroy both clusters, run leak audit
                    +-----------+
```

### 4.2 Dual-Cluster Strategy (REQ-4)

Two clusters run simultaneously:

**Cluster 1 (non-destructive):** Runs all suites that do not kill nodes or change topology. Suites run in parallel where safe. Cluster is stopped when all suites complete.

**Cluster 2 (destructive):** Runs chaos, scaling, network partition, and edge-case suites sequentially. Between each destructive test, a self-heal step runs:

```bash
wait_for_baseline() {
    local timeout="${1:-120}"
    local deadline=$((SECONDS + timeout))

    echo "[HEAL] Waiting for baseline (timeout: ${timeout}s)"
    while [ "$SECONDS" -lt "$deadline" ]; do
        local current_count
        current_count=$(get_active_node_count)
        if [ "$current_count" -ge "$NODE_COUNT" ]; then
            echo "[HEAL] Baseline restored: ${current_count}/${NODE_COUNT} nodes"
            return 0
        fi
        sleep 2
    done

    echo "[HEAL] Timeout -- attempting full cluster restart"
    restart_all_nodes
    local restart_deadline=$((SECONDS + 60))
    while [ "$SECONDS" -lt "$restart_deadline" ]; do
        local count
        count=$(get_active_node_count)
        if [ "$count" -ge "$NODE_COUNT" ]; then
            echo "[HEAL] Cluster restarted successfully"
            return 0
        fi
        sleep 2
    done

    echo "[HEAL] FATAL: cluster restart failed -- aborting remaining tests"
    return 1
}
```

**Wall time = max(cluster1, cluster2).** Instance-minutes = sum of both clusters. This is the optimal tradeoff: faster wall time at the cost of more compute.

**Test design constraint:** Destructive tests must assert **relative state changes**, not absolute state. After self-heal, the cluster state is unpredictable (different leader, different slot assignments). Tests must check "did X change from before to after" rather than "is X exactly Y".

### 4.3 `setup-test-env.sh`

Location: `aether/tests/integration/scripts/setup-test-env.sh`

```bash
#!/bin/bash
# setup-test-env.sh -- Provision cluster from test-env.toml, write endpoint file
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="${SCRIPT_DIR}/.."
CONFIG="${TEST_ENV_CONFIG:-${ROOT_DIR}/test-env.toml}"
ENDPOINT_FILE="${ENDPOINT_FILE:-/tmp/aether-test-endpoints.env}"

# ---------------------------------------------------------------------------
# Step 1: Parse test-env.toml using aether CLI
# ---------------------------------------------------------------------------
env_type=$(aether config read "$CONFIG" --field environment.type --format value)
env_name=$(aether config read "$CONFIG" --field environment.name --format value)
node_count=$(aether config read "$CONFIG" --field cluster.node_count --format value)
bootstrap_config=$(aether config read "$CONFIG" --field provisioning.bootstrap_config --format value)
skip_build=$(aether config read "$CONFIG" --field provisioning.skip_build --format value 2>/dev/null || echo "false")
health_timeout=$(aether config read "$CONFIG" --field cluster.timeouts.health_check --format value)

echo "Environment: ${env_name} (${env_type}), ${node_count} nodes"

# ---------------------------------------------------------------------------
# Step 2: Build (unless skipped)
# ---------------------------------------------------------------------------
if [ "$skip_build" != "true" ]; then
    echo "[STEP] Building project"
    REPO_ROOT="$(cd "${ROOT_DIR}/../../.." && pwd)"
    (cd "$REPO_ROOT" && mvn clean install -DskipTests -q)
    # Build all test blueprints
    for bp in "$REPO_ROOT"/aether/tests/blueprints/test-*/; do
        [ -f "$bp/pom.xml" ] && (cd "$bp" && mvn clean install -DskipTests -q)
    done
fi

# ---------------------------------------------------------------------------
# Step 3: Provision cluster based on environment type
# ---------------------------------------------------------------------------
BOOTSTRAP_TOML="${ROOT_DIR}/${bootstrap_config}"

source "${ROOT_DIR}/lib/provisioner.sh"

case "$env_type" in
    forge)
        provision_forge "$BOOTSTRAP_TOML" "$node_count"
        ;;
    docker)
        provision_docker "$BOOTSTRAP_TOML" "$node_count"
        ;;
    ssh)
        provision_ssh "$BOOTSTRAP_TOML" "$node_count"
        ;;
    cloud)
        provision_cloud "$BOOTSTRAP_TOML" "$node_count"
        ;;
    *)
        echo "Unknown environment type: ${env_type}"
        exit 1
        ;;
esac

# ---------------------------------------------------------------------------
# Step 4: Detect capabilities and write endpoint file
# ---------------------------------------------------------------------------
write_endpoint_file "$env_type" "$env_name" "$node_count"

# ---------------------------------------------------------------------------
# Step 5: Wait for health
# ---------------------------------------------------------------------------
source "$ENDPOINT_FILE"
source "${ROOT_DIR}/lib/common.sh"
source "${ROOT_DIR}/lib/cluster.sh"
wait_for_cluster "$health_timeout"

# ---------------------------------------------------------------------------
# Step 6: Create test DB schemas
# ---------------------------------------------------------------------------
source "${ROOT_DIR}/lib/database.sh"
create_test_schemas

# ---------------------------------------------------------------------------
# Step 7: Seed test blueprints
# ---------------------------------------------------------------------------
for bp in test-echo test-persistence test-streaming test-http test-scheduled \
          test-security test-storage test-full; do
    local_blueprint="org.pragmatica.aether.test:${bp}:1.0.0"
    push_blueprint "$local_blueprint" || true
done
deploy_blueprint "org.pragmatica.aether.test:test-echo:1.0.0"
wait_for_slices_active 1 120

echo "[PASS] Test environment ready: ${env_name}"
```

### 4.4 Provisioner Functions

Each provisioner function lives in `lib/provisioner.sh`.

```bash
# lib/provisioner.sh -- Environment-specific provisioning functions

provision_forge() {
    local config="$1" node_count="$2"
    echo "[STEP] Starting Forge cluster (${node_count} Ember nodes)"
    aether cluster bootstrap "$config" --yes --format json > /tmp/aether-bootstrap-result.json
}

provision_docker() {
    local config="$1" node_count="$2"
    echo "[STEP] Starting Docker cluster (${node_count} containers)"

    # Start postgres sidecar on same Docker network
    start_postgres_sidecar "aether-test-net"

    aether cluster bootstrap "$config" --yes --format json > /tmp/aether-bootstrap-result.json
}

provision_ssh() {
    local config="$1" node_count="$2"
    echo "[STEP] Provisioning SSH cluster (${node_count} nodes)"

    # Start postgres container on first node
    ssh_start_postgres "${TARGET_HOST}"

    aether cluster bootstrap "$config" --yes --format json > /tmp/aether-bootstrap-result.json
}

provision_cloud() {
    local config="$1" node_count="$2"
    echo "[STEP] Provisioning cloud cluster (${node_count} nodes)"

    aether cluster bootstrap "$config" --yes --format json > /tmp/aether-bootstrap-result.json

    # Start postgres container on first provisioned node
    local first_ip
    first_ip=$(jq -r '.nodes[0].host' /tmp/aether-bootstrap-result.json)
    ssh_start_postgres "$first_ip"
}

write_endpoint_file() {
    local env_type="$1" env_name="$2" node_count="$3"
    local endpoint_file="${ENDPOINT_FILE:-/tmp/aether-test-endpoints.env}"
    local result="/tmp/aether-bootstrap-result.json"

    # Parse structured bootstrap output
    local nodes_json
    nodes_json=$(jq -r '.nodes' "$result")

    case "$env_type" in
        forge)
            write_forge_endpoints "$endpoint_file" "$env_name" "$node_count" "$result"
            ;;
        docker)
            write_docker_endpoints "$endpoint_file" "$env_name" "$node_count" "$result"
            ;;
        ssh|cloud)
            write_remote_endpoints "$endpoint_file" "$env_type" "$env_name" "$node_count" "$result"
            ;;
    esac

    echo "[INFO] Endpoint file written: ${endpoint_file}"
}

# Capability tables per environment type
caps_forge="CAP_RESTART=true
CAP_SCALING=true
CAP_PERSISTENCE=true
CAP_CHAOS=true
CAP_NETWORK_PARTITION=false
CAP_LB=false
CAP_SSH=false
CAP_CLOUD_OPS=false"

caps_docker="CAP_RESTART=true
CAP_SCALING=true
CAP_PERSISTENCE=true
CAP_CHAOS=true
CAP_NETWORK_PARTITION=true
CAP_LB=true
CAP_SSH=false
CAP_CLOUD_OPS=false"

caps_ssh="CAP_RESTART=true
CAP_SCALING=true
CAP_PERSISTENCE=true
CAP_CHAOS=true
CAP_NETWORK_PARTITION=false
CAP_LB=true
CAP_SSH=true
CAP_CLOUD_OPS=false"

caps_cloud="CAP_RESTART=true
CAP_SCALING=true
CAP_PERSISTENCE=true
CAP_CHAOS=true
CAP_NETWORK_PARTITION=false
CAP_LB=true
CAP_SSH=true
CAP_CLOUD_OPS=true"
```

### 4.5 `teardown-test-env.sh`

```bash
#!/bin/bash
# teardown-test-env.sh -- Destroy cluster, run leak audit, clean up
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="${SCRIPT_DIR}/.."
ENDPOINT_FILE="${ENDPOINT_FILE:-/tmp/aether-test-endpoints.env}"

if [ ! -f "$ENDPOINT_FILE" ]; then
    echo "[WARN] No endpoint file found -- nothing to tear down"
    exit 0
fi

source "$ENDPOINT_FILE"

echo "[STEP] Tearing down: ${ENV_NAME} (${ENV_TYPE})"

case "$ENV_TYPE" in
    forge)
        aether cluster destroy --force 2>/dev/null || true
        ;;
    docker)
        docker compose -f "${ROOT_DIR}/docker-compose.yml" down -v 2>/dev/null || true
        docker rm -f test-postgres 2>/dev/null || true
        ;;
    ssh)
        bash "${SCRIPT_DIR}/cleanup.sh"
        ;;
    cloud)
        aether cluster destroy --force 2>/dev/null || true
        ;;
esac

# Leak audit (REQ-10)
echo "[STEP] Running leak audit"
bash "${SCRIPT_DIR}/leak-audit.sh" "$ENV_TYPE"

rm -f "$ENDPOINT_FILE"
rm -f /tmp/aether-bootstrap-result.json
rm -f /tmp/load_result_*.txt /tmp/sustained_load*.log
echo "[PASS] Teardown complete"
```

---

## 5. Test Blueprints (REQ-9)

### 5.1 Blueprint Projects

Each test blueprint is an independent Maven project under `aether/tests/blueprints/`. Blueprints are NOT slices of the production `url-shortener` example. They are purpose-built test artifacts.

| Blueprint | Purpose | Suites |
|-----------|---------|--------|
| `test-echo` | Smoke, chaos, scaling, edge-cases | 00-smoke, 02-chaos, 03-scaling, 13-edge-cases |
| `test-persistence` | SQL queries, DB operations | 08-resources, 10-database |
| `test-streaming` | Streaming, pub-sub | 04-streaming |
| `test-http` | HTTP client testing | 07-cluster-mgmt |
| `test-scheduled` | Scheduled task execution | 13-edge-cases |
| `test-security` | Auth, route guards | 05-security |
| `test-storage` | KV-Store operations | 14-storage |
| `test-full` | Deployment upgrade, canary, blue-green | 06-deployment |

### 5.2 Full Isolation

Each blueprint has:
- **Own DB schema:** `CREATE SCHEMA test_echo`, `CREATE SCHEMA test_persistence`, etc.
- **Own stream names:** prefixed with blueprint name (e.g., `test-streaming.events`, `test-streaming.commands`)
- **Own config sections:** each blueprint's `aether.toml` uses unique slice names, route paths, and resource keys
- **No shared state:** blueprints never read/write the same DB tables, streams, or KV keys

This isolation is critical for the dual-cluster strategy. Both clusters can run different blueprints simultaneously without interference.

### 5.3 Blueprint Directory Structure

```
aether/tests/blueprints/
  test-echo/
    pom.xml
    src/main/java/org/pragmatica/aether/test/echo/
      EchoSlice.java
      EchoHandler.java
    src/main/resources/
      aether.toml                   # slice config with unique routes
  test-persistence/
    pom.xml
    src/main/java/org/pragmatica/aether/test/persistence/
      PersistenceSlice.java
      PersistenceHandler.java
    src/main/resources/
      aether.toml
      schema.sql                    # CREATE SCHEMA test_persistence; CREATE TABLE ...
  test-streaming/
    ...
  test-http/
    ...
  test-scheduled/
    ...
  test-security/
    ...
  test-storage/
    ...
  test-full/
    pom.xml
    src/main/java/org/pragmatica/aether/test/full/
      FullSlice.java                # v1.0.0
    v2/
      pom.xml                       # builds v1.0.1 for upgrade/canary tests
      src/main/java/.../FullSliceV2.java
```

---

## 6. Execution Strategy

### 6.1 Dual-Cluster Partitioning

Suites are partitioned into two clusters:

**Cluster 1 (non-destructive):**

| Suite | Tags | Blueprint | Parallel? |
|-------|------|-----------|-----------|
| 00-smoke | `smoke` | test-echo | sequential (gate) |
| 04-streaming | `streaming` | test-streaming | yes |
| 06-deployment | `deployment` | test-full | yes |
| 07-cluster-mgmt | `cluster-mgmt` | test-http | yes |
| 08-resources | `resources` | test-persistence | yes |
| 09-artifacts | `artifacts` | test-echo | yes |
| 10-database | `database` | test-persistence | yes |
| 11-observability | `observability` | test-echo | yes |
| 14-storage | `storage` | test-storage | yes |
| 15-delegation | `delegation` | test-echo | yes |

Smoke runs first as a gate. If smoke fails, abort. Then all remaining suites run in parallel (max 4 concurrent).

**Cluster 2 (destructive, sequential with self-heal):**

| Suite | Tags | Blueprint | Requires |
|-------|------|-----------|----------|
| 02-chaos | `chaos` | test-echo | `CAP_CHAOS` |
| 03-scaling | `scaling` | test-echo | `CAP_SCALING` |
| 05-security | `security` | test-security | (none) |
| 12-network | `network` | test-echo | `CAP_NETWORK_PARTITION` |
| 13-edge-cases | `edge-cases` | test-echo, test-scheduled | (none) |

Between each suite: `wait_for_baseline 120`. If timeout, full restart. If restart fails, abort remaining.

**Soak** (01-stability): runs on its own cluster, optional, never in CI.

### 6.2 Suite Metadata

Each suite directory contains a `suite.conf` file:

```bash
# suites/00-smoke/suite.conf
tags=smoke
requires=
read_only=true
destructive=false
min_nodes=3
estimated_duration=30
quarantine=false
blueprint=test-echo
description=Cluster formation and basic slice deployment
```

Full catalog:

| Suite | Tags | Requires | Destructive | Min Nodes | Est. Duration | Blueprint |
|-------|------|----------|-------------|-----------|---------------|-----------|
| 00-smoke | `smoke` | (none) | false | 3 | 30s | test-echo |
| 01-stability | `stability,soak` | (none) | false | 5 | 4h / 30m | test-echo |
| 02-chaos | `chaos` | `CAP_CHAOS` | true | 5 | 5m | test-echo |
| 03-scaling | `scaling` | `CAP_SCALING` | true | 5 | 8m | test-echo |
| 04-streaming | `streaming` | (none) | false | 3 | 3m | test-streaming |
| 05-security | `security` | (none) | true | 3 | 3m | test-security |
| 06-deployment | `deployment` | (none) | false | 3 | 5m | test-full |
| 07-cluster-mgmt | `cluster-mgmt` | (none) | false | 3 | 3m | test-http |
| 08-resources | `resources` | `CAP_PERSISTENCE` | false | 3 | 3m | test-persistence |
| 09-artifacts | `artifacts` | (none) | false | 3 | 2m | test-echo |
| 10-database | `database` | `CAP_PERSISTENCE` | false | 3 | 3m | test-persistence |
| 11-observability | `observability` | (none) | false | 3 | 2m | test-echo |
| 12-network | `network` | `CAP_NETWORK_PARTITION` | true | 5 | 4m | test-echo |
| 13-edge-cases | `edge-cases` | (none) | true | 3 | 3m | test-echo |
| 14-storage | `storage` | (none) | false | 3 | 2m | test-storage |
| 15-delegation | `delegation` | (none) | false | 3 | 2m | test-echo |

### 6.3 Capability and Tag Filtering

A suite is **skipped** (not failed) when its `requires` capabilities are not satisfied:

```bash
should_run_suite() {
    local suite_dir="$1"
    local conf="${suite_dir}/suite.conf"
    [ -f "$conf" ] || return 0

    # Check capabilities
    local requires
    requires=$(grep '^requires=' "$conf" | cut -d= -f2)
    if [ -n "$requires" ]; then
        IFS=',' read -ra caps <<< "$requires"
        for cap in "${caps[@]}"; do
            cap=$(echo "$cap" | tr -d ' ')
            [ -z "$cap" ] && continue
            local val="${!cap:-false}"
            if [ "$val" != "true" ]; then
                echo "SKIP: $(basename "$suite_dir") requires ${cap} (not available in ${ENV_TYPE})"
                return 1
            fi
        done
    fi

    # Check node count
    local min_nodes
    min_nodes=$(grep '^min_nodes=' "$conf" | cut -d= -f2)
    if [ -n "$min_nodes" ] && [ "$NODE_COUNT" -lt "$min_nodes" ]; then
        echo "SKIP: $(basename "$suite_dir") requires ${min_nodes} nodes (environment has ${NODE_COUNT})"
        return 1
    fi

    return 0
}
```

Tag-based filtering:

```bash
# Run only smoke and observability
./scripts/run-all-v2.sh --tags smoke,observability

# Run everything except soak
./scripts/run-all-v2.sh --exclude-tags soak

# Run destructive only
./scripts/run-all-v2.sh --tags chaos,scaling,network
```

### 6.4 Flaky Test Quarantine (REQ-12)

The `quarantine` field in `suite.conf` marks flaky suites. Quarantined tests run but failures are reported as warnings, not errors:

```bash
is_quarantined() {
    local suite_dir="$1"
    local conf="${suite_dir}/suite.conf"
    [ -f "$conf" ] || return 1
    local q
    q=$(grep '^quarantine=' "$conf" | cut -d= -f2)
    [ "$q" = "true" ]
}

run_suite_with_quarantine() {
    local suite_dir="$1"
    local name
    name=$(basename "$suite_dir")

    bash "${SCRIPT_DIR}/run-suite.sh" "$name"
    local rc=$?

    if [ "$rc" -ne 0 ] && is_quarantined "$suite_dir"; then
        echo "[WARN] Quarantined suite failed (non-blocking): ${name}"
        return 0  # do not fail the run
    fi
    return "$rc"
}
```

### 6.5 Network Partition Simulation (Docker Only)

`CAP_NETWORK_PARTITION=true` only for Docker. Implementation is ~20 lines of shell:

```bash
# lib/partition.sh -- Network partition simulation (Docker only)

partition_node() {
    local container_name="$1"
    local network="${2:-aether-test-net}"
    echo "[PARTITION] Disconnecting ${container_name} from ${network}"
    docker network disconnect "$network" "$container_name"
}

heal_partition() {
    local container_name="$1"
    local network="${2:-aether-test-net}"
    echo "[PARTITION] Reconnecting ${container_name} to ${network}"
    docker network connect "$network" "$container_name"
}

partition_between() {
    # Partition node A from the cluster (disconnect from shared network)
    # This simulates a network split where node A cannot reach others
    local node_a="$1"
    partition_node "$node_a"
}

heal_all_partitions() {
    local network="${1:-aether-test-net}"
    for container in $(docker ps --filter "label=aether.cluster=integration-test" --format '{{.Names}}'); do
        # Reconnect if disconnected (idempotent)
        docker network connect "$network" "$container" 2>/dev/null || true
    done
}
```

### 6.6 The New Runner: `run-all-v2.sh`

```bash
#!/bin/bash
# run-all-v2.sh -- Dual-cluster test runner
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="${SCRIPT_DIR}/.."
SUITE_DIR="${ROOT_DIR}/suites"
ENDPOINT_FILE="${ENDPOINT_FILE:-/tmp/aether-test-endpoints.env}"
CONFIG="${TEST_ENV_CONFIG:-${ROOT_DIR}/test-env.toml}"
RESULTS_DIR="${RESULTS_DIR:-${ROOT_DIR}/results}"

mkdir -p "$RESULTS_DIR"

# Parse arguments
TAGS="" EXCLUDE_TAGS="" SKIP_SETUP=false SKIP_TEARDOWN=false WATCH=false
while [ $# -gt 0 ]; do
    case "$1" in
        --tags)          TAGS="$2"; shift 2 ;;
        --exclude-tags)  EXCLUDE_TAGS="$2"; shift 2 ;;
        --skip-setup)    SKIP_SETUP=true; shift ;;
        --skip-teardown) SKIP_TEARDOWN=true; shift ;;
        --watch)         WATCH=true; shift ;;
        *)               shift ;;
    esac
done

# Phase 1: Setup
if [ "$SKIP_SETUP" != "true" ]; then
    bash "${SCRIPT_DIR}/setup-test-env.sh"
fi

source "$ENDPOINT_FILE"
source "${ROOT_DIR}/lib/common.sh"
source "${ROOT_DIR}/lib/cluster.sh"

# Phase 2: Classify suites
declare -a non_destructive=() destructive=()
for suite in "$SUITE_DIR"/*/; do
    [ -d "$suite" ] || continue
    should_run_suite "$suite" || continue
    matches_tags "$suite" "$TAGS" "$EXCLUDE_TAGS" || continue

    if suite_is_destructive "$suite"; then
        destructive+=("$suite")
    else
        non_destructive+=("$suite")
    fi
done

# Phase 3: Smoke gate (always first, always sequential)
if suite_in_list "00-smoke" "${non_destructive[@]:-}"; then
    run_suite_with_quarantine "${SUITE_DIR}/00-smoke" || { echo "Smoke failed -- aborting"; exit 1; }
    remove_from_list "00-smoke" non_destructive
fi

# Phase 4: Execute both tracks simultaneously
(
    # Track 1: non-destructive (parallel)
    run_wave_parallel "${non_destructive[@]}"
    echo $? > /tmp/aether-track1.rc
) &
TRACK1_PID=$!

(
    # Track 2: destructive (sequential with self-heal)
    local_rc=0
    for suite in "${destructive[@]}"; do
        run_suite_with_quarantine "$suite"
        local rc=$?
        if [ "$rc" -ne 0 ]; then local_rc=1; fi
        # Self-heal between destructive tests
        wait_for_baseline 120 || { echo "[FATAL] Self-heal failed"; echo 1 > /tmp/aether-track2.rc; exit 1; }
    done
    echo "$local_rc" > /tmp/aether-track2.rc
) &
TRACK2_PID=$!

# Wait for both tracks
wait "$TRACK1_PID" 2>/dev/null || true
wait "$TRACK2_PID" 2>/dev/null || true

TRACK1_RC=$(cat /tmp/aether-track1.rc 2>/dev/null || echo 1)
TRACK2_RC=$(cat /tmp/aether-track2.rc 2>/dev/null || echo 1)

# Phase 5: Collect results
collect_test_results "$RESULTS_DIR"
detect_timing_regressions "$RESULTS_DIR"

# Phase 6: Teardown
if [ "$SKIP_TEARDOWN" != "true" ]; then
    bash "${SCRIPT_DIR}/teardown-test-env.sh"
fi

# Phase 7: Summary
generate_status_html "$RESULTS_DIR"
if [ "$WATCH" = "true" ]; then
    generate_status_txt "$RESULTS_DIR"
fi
print_final_summary "$TRACK1_RC" "$TRACK2_RC"

exit $(( TRACK1_RC + TRACK2_RC > 0 ? 1 : 0 ))
```

### 6.7 Estimated Run Durations

Full run (excluding soak):

| Environment | Sequential | Dual-Cluster Parallel |
|-------------|-----------|----------------------|
| forge (5-node) | ~35 min | ~15 min |
| docker (5-node) | ~45 min | ~18 min |
| ssh (5-node) | ~50 min | ~20 min |
| cloud (5+3 node) | ~55 min | ~22 min |

Dual-cluster breakdown:

| Track | Duration | Notes |
|-------|----------|-------|
| Cluster 1: smoke | 30s | Gate -- sequential |
| Cluster 1: non-destructive | ~12m | 10 suites, max 4 parallel |
| Cluster 2: destructive | ~18m | 5 suites sequential + self-heal |
| **Wall time** | **~18m** | max(cluster1, cluster2) |
| **Instance-minutes** | **~30m** | sum(cluster1, cluster2) |

---

## 7. Database Provisioning (REQ-9)

### 7.1 PostgreSQL Sidecar Strategy

Each environment type provisions a containerized PostgreSQL sidecar:

| Environment | PostgreSQL Location | Network |
|-------------|-------------------|---------|
| forge | Local Docker container (`test-postgres`) | localhost:5432 |
| docker | Container on same Docker network (`test-postgres`) | `aether-test-net` |
| ssh | Docker container on first remote node | `host:5432` |
| cloud | Docker container on first provisioned node | `first_ip:5432` |

### 7.2 Schema Isolation

Each test blueprint gets its own schema. No shared tables.

```bash
# lib/database.sh

create_test_schemas() {
    local db_host="${DB_HOST:-localhost}"
    local db_port="${DB_PORT:-5432}"
    local db_user="${DB_USER:-aether}"
    local db_name="${DB_NAME:-aether_test}"

    for schema in test_echo test_persistence test_streaming test_http \
                  test_scheduled test_security test_storage test_full; do
        psql -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" \
             -c "CREATE SCHEMA IF NOT EXISTS ${schema};" 2>/dev/null || true
    done

    # Run blueprint-specific DDL
    for bp_dir in "${ROOT_DIR}/../blueprints"/test-*/; do
        local schema_file="${bp_dir}/src/main/resources/schema.sql"
        if [ -f "$schema_file" ]; then
            psql -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" \
                 -f "$schema_file" 2>/dev/null || true
        fi
    done
}

start_postgres_sidecar() {
    local network="${1:-bridge}"
    echo "[DB] Starting PostgreSQL sidecar on network ${network}"
    docker run -d \
        --name test-postgres \
        --network "$network" \
        --label "aether.cluster=integration-test" \
        -e POSTGRES_USER=aether \
        -e POSTGRES_PASSWORD=aether \
        -e POSTGRES_DB=aether_test \
        -p 5432:5432 \
        postgres:16-alpine

    # Wait for postgres to accept connections
    local deadline=$((SECONDS + 30))
    while [ "$SECONDS" -lt "$deadline" ]; do
        pg_isready -h localhost -p 5432 -U aether 2>/dev/null && return 0
        sleep 1
    done
    echo "[DB] WARN: PostgreSQL did not become ready in 30s"
}

ssh_start_postgres() {
    local host="$1"
    echo "[DB] Starting PostgreSQL on ${host}"
    ssh -o StrictHostKeyChecking=no "$host" \
        "docker run -d --name test-postgres \
         --label aether.cluster=integration-test \
         -e POSTGRES_USER=aether \
         -e POSTGRES_PASSWORD=aether \
         -e POSTGRES_DB=aether_test \
         -p 5432:5432 \
         postgres:16-alpine"
}
```

---

## 8. Test Tooling

### 8.1 Test Replay via CLI Batch Command (REQ-10)

`aether batch execute --file commands.toml` runs a sequence of CLI commands with optional assertions. TOML format:

```toml
# replay/smoke-check.toml

[[step]]
command = "cluster status"
assert.field = "leader"
assert.not_empty = true

[[step]]
command = "cluster topology"
assert.field = "nodes"
assert.min_count = 5

[[step]]
command = "deploy status test-echo"
assert.field = "status"
assert.equals = "ACTIVE"
```

Alternatively, a simpler plain-text format with one CLI command per line (no assertions, just execution):

```
# replay/smoke-check.txt
cluster status
cluster topology
deploy status test-echo
```

The TOML batch format supports assertions. The plain-text format is for replay-only (useful for reproducing test sequences).

### 8.2 Leak Audit (REQ-10)

`scripts/leak-audit.sh` runs after teardown and checks each provider for orphaned resources:

```bash
#!/bin/bash
# leak-audit.sh -- Check for orphaned test resources
set -euo pipefail

ENV_TYPE="${1:?Usage: leak-audit.sh <forge|docker|ssh|cloud>}"
LABEL="aether.cluster=integration-test"
LEAKED=0

echo "[AUDIT] Checking for orphaned resources (${ENV_TYPE})"

case "$ENV_TYPE" in
    forge)
        # Forge: check for leftover JVM processes
        if pgrep -f "aether.*integration-test" > /dev/null 2>&1; then
            echo "[LEAK] Found orphaned Forge processes"
            pgrep -af "aether.*integration-test"
            LEAKED=1
        fi
        ;;
    docker)
        # Docker: check for leftover containers
        local_containers=$(docker ps -a --filter "label=${LABEL}" --format '{{.Names}}' 2>/dev/null)
        if [ -n "$local_containers" ]; then
            echo "[LEAK] Found orphaned Docker containers:"
            echo "$local_containers"
            LEAKED=1
        fi
        # Check for leftover networks
        local_networks=$(docker network ls --filter "label=${LABEL}" --format '{{.Name}}' 2>/dev/null)
        if [ -n "$local_networks" ]; then
            echo "[LEAK] Found orphaned Docker networks:"
            echo "$local_networks"
            LEAKED=1
        fi
        ;;
    ssh)
        # SSH: check remote host for containers
        if [ -n "${TARGET_HOST:-}" ]; then
            remote_containers=$(ssh "$TARGET_HOST" \
                "docker ps -a --filter 'label=${LABEL}' --format '{{.Names}}'" 2>/dev/null)
            if [ -n "$remote_containers" ]; then
                echo "[LEAK] Found orphaned containers on ${TARGET_HOST}:"
                echo "$remote_containers"
                LEAKED=1
            fi
        fi
        ;;
    cloud)
        # Cloud: check for leftover Hetzner servers
        if command -v hcloud > /dev/null; then
            cloud_servers=$(hcloud server list -l "${LABEL}" -o noheader 2>/dev/null)
            if [ -n "$cloud_servers" ]; then
                echo "[LEAK] Found orphaned cloud servers:"
                echo "$cloud_servers"
                LEAKED=1
            fi
        fi
        ;;
esac

if [ "$LEAKED" -eq 0 ]; then
    echo "[AUDIT] Clean -- no orphaned resources"
else
    echo "[AUDIT] FAIL -- orphaned resources detected (see above)"
    exit 1
fi
```

### 8.3 CI Docker Image Publishing (REQ-5)

CI builds and publishes the Aether node Docker image so cloud/SSH configs reference pre-built images instead of building on the target:

```bash
# In CI pipeline (e.g., GitHub Actions):
docker build -t ghcr.io/pragmaticalabs/aether-node:${VERSION} -f aether/node/Dockerfile .
docker push ghcr.io/pragmaticalabs/aether-node:${VERSION}
```

Cloud and SSH config templates reference `ghcr.io/pragmaticalabs/aether-node:<version>`. Local Docker uses `aether-node:local` (built from `mvn install`). This eliminates the "build on target" step for remote environments.

---

## 9. Observability and Diagnostics

### 9.1 Test Result Persistence (REQ-11)

Every test run writes `test-results.json` to the results directory:

```json
{
  "run_id": "2026-04-12T14:30:00Z",
  "environment": "local-docker",
  "node_count": 5,
  "suites": [
    {
      "name": "00-smoke",
      "status": "pass",
      "duration_seconds": 28,
      "tests": [
        {"name": "cluster_formation", "status": "pass", "duration_seconds": 12},
        {"name": "slice_deployment", "status": "pass", "duration_seconds": 16}
      ]
    },
    {
      "name": "02-chaos",
      "status": "fail",
      "duration_seconds": 312,
      "tests": [
        {"name": "kill_leader_recovery", "status": "pass", "duration_seconds": 45},
        {"name": "kill_minority_recovery", "status": "fail", "duration_seconds": 267,
         "error": "Timeout: quorum not restored within 120s"}
      ]
    }
  ],
  "summary": {
    "total_suites": 16,
    "passed": 15,
    "failed": 1,
    "skipped": 0,
    "quarantine_warnings": 0,
    "total_duration_seconds": 1080
  }
}
```

The result file is generated by the test runner at the end of each run. Individual suite results are written to `/tmp/aether-suite-<name>.json` during execution and merged at the end.

### 9.2 Test Timing Regression Detection (REQ-11)

Suite durations are tracked in `test-timing-history.json`. If a suite takes >2x its historical average, it is flagged:

```bash
# lib/timing.sh

detect_timing_regressions() {
    local results_dir="$1"
    local history_file="${results_dir}/test-timing-history.json"
    local current_file="${results_dir}/test-results.json"

    [ -f "$history_file" ] || { echo "[TIMING] No history -- skipping regression check"; return 0; }
    [ -f "$current_file" ] || return 0

    # Compare each suite duration against 2x historical average
    jq -r '.suites[] | "\(.name) \(.duration_seconds)"' "$current_file" | while read -r name duration; do
        local avg
        avg=$(jq -r --arg name "$name" \
            '[.runs[].suites[] | select(.name == $name) | .duration_seconds] | if length > 0 then (add / length) else 0 end' \
            "$history_file")

        if [ "$avg" != "0" ] && [ "$(echo "$duration > 2 * $avg" | bc -l)" = "1" ]; then
            echo "[TIMING] REGRESSION: ${name} took ${duration}s (avg: ${avg}s, threshold: $(echo "$avg * 2" | bc -l)s)"
        fi
    done

    # Append current run to history
    jq --argjson run "$(cat "$current_file")" '.runs += [$run]' "$history_file" > "${history_file}.tmp"
    mv "${history_file}.tmp" "$history_file"
}
```

### 9.3 Health Dashboard (REQ-11)

A self-contained `test-status.html` with status data embedded as a JS variable. Zero server dependencies. Regenerated after each test.

```bash
# lib/dashboard.sh

generate_status_html() {
    local results_dir="$1"
    local results_json="${results_dir}/test-results.json"
    local output="${results_dir}/test-status.html"

    [ -f "$results_json" ] || return 0

    local data
    data=$(cat "$results_json")

    cat > "$output" <<'HEADER'
<!DOCTYPE html>
<html><head><title>Aether Test Status</title>
<style>
body { font-family: monospace; margin: 2em; background: #1a1a2e; color: #e0e0e0; }
.pass { color: #00ff41; } .fail { color: #ff4444; } .skip { color: #ffaa00; }
.warn { color: #ffaa00; }
table { border-collapse: collapse; width: 100%; }
td, th { padding: 8px; text-align: left; border-bottom: 1px solid #333; }
th { color: #8888ff; }
</style></head><body>
<h1>Aether Integration Test Status</h1>
<script>
HEADER

    printf 'var TEST_DATA = %s;\n' "$data" >> "$output"

    cat >> "$output" <<'FOOTER'
document.addEventListener('DOMContentLoaded', function() {
    var d = TEST_DATA;
    var summary = d.summary;
    var el = document.createElement('div');
    el.innerHTML = '<h2>Summary</h2>' +
        '<p>Total: ' + summary.total_suites +
        ' | <span class="pass">Passed: ' + summary.passed + '</span>' +
        ' | <span class="fail">Failed: ' + summary.failed + '</span>' +
        ' | <span class="skip">Skipped: ' + summary.skipped + '</span>' +
        (summary.quarantine_warnings > 0 ? ' | <span class="warn">Quarantine: ' + summary.quarantine_warnings + '</span>' : '') +
        ' | Duration: ' + summary.total_duration_seconds + 's</p>';
    var table = '<table><tr><th>Suite</th><th>Status</th><th>Duration</th><th>Tests</th></tr>';
    d.suites.forEach(function(s) {
        var cls = s.status === 'pass' ? 'pass' : 'fail';
        table += '<tr><td>' + s.name + '</td><td class="' + cls + '">' + s.status.toUpperCase() + '</td>' +
                 '<td>' + s.duration_seconds + 's</td><td>' + s.tests.length + '</td></tr>';
    });
    table += '</table>';
    el.innerHTML += table;
    document.body.appendChild(el);
});
</script></body></html>
FOOTER

    echo "[DASHBOARD] Written: ${output}"
    echo "  View: open ${output}"
}
```

CLI watch mode via plain text:

```bash
generate_status_txt() {
    local results_dir="$1"
    local results_json="${results_dir}/test-results.json"
    local output="${results_dir}/test-status.txt"

    [ -f "$results_json" ] || return 0

    {
        echo "=== Aether Test Status ==="
        echo "Time: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
        echo ""
        jq -r '.suites[] | "\(.status | ascii_upcase)\t\(.duration_seconds)s\t\(.name)"' "$results_json" \
            | column -t
        echo ""
        jq -r '"Total: \(.summary.total_suites) | Pass: \(.summary.passed) | Fail: \(.summary.failed) | Skip: \(.summary.skipped)"' "$results_json"
    } > "$output"
}
```

Usage: `open results/test-status.html` in browser. For CLI: `watch cat results/test-status.txt`.

---

## 10. Migration Path

### 10.1 Phased Approach

| Phase | Work | Duration | Risk |
|-------|------|----------|------|
| 0 | Fix `ClusterBootstrapCommand` to use `OutputFormatter` (REQ-8) | 0.5 day | Low -- existing patterns |
| 1 | Create test blueprint projects (`test-echo`, `test-persistence`, etc.) | 2 days | None -- additive |
| 2 | Add `suite.conf` to all 16 suites, add database/partition/timing libs | 1 day | None -- additive |
| 3 | Implement `setup-test-env.sh`, `teardown-test-env.sh`, `leak-audit.sh` | 1 day | None -- new files |
| 4 | Modify `common.sh` to read endpoint file with env-var fallback | 0.5 day | Low -- backward compat |
| 5 | Implement dual-cluster `run-all-v2.sh` with self-heal | 1.5 days | Medium -- complex orchestration |
| 6 | Add config templates (`local-dev.toml`, `local-docker.toml`, etc.) | 0.5 day | None -- additive |
| 7 | Replace `CLOUD_MODE` branches in `cluster.sh` with `ENV_TYPE` switch | 0.5 day | Medium -- must test all paths |
| 8 | Add observability: test-results.json, timing regression, dashboard | 1 day | None -- additive |
| 9 | CI Docker image publishing pipeline | 0.5 day | Low -- standard CI |
| 10 | Remove old scripts (`deploy-compose.sh`, `deploy-cloud.sh`, `run-all.sh`) | 0.5 day | Low -- after validation |

**Total: 9-10 days.**

### 10.2 Backward Compatibility (REQ-6)

During migration, both old and new workflows work:

**Old workflow** (unchanged):
```bash
TARGET_HOST=192.168.0.71 bash scripts/run-all.sh
```

`run-all.sh` is not modified. `common.sh` falls back to env vars when no endpoint file exists.

**New workflow**:
```bash
TEST_ENV_CONFIG=test-env.toml bash scripts/run-all-v2.sh
```

Or with the default config:
```bash
bash scripts/run-all-v2.sh
```

The old scripts are preserved until Phase 10.

### 10.3 Suite Migration Checklist

For each suite:

1. Create `suite.conf` with tags, requires, destructive, read_only, min_nodes, estimated_duration, blueprint
2. Replace hardcoded `wait_for_cluster 120` with `wait_for_cluster "$(timeout_health)"`
3. Replace any `CLOUD_MODE` checks with capability guards
4. Update test assertions to check relative state changes (not absolute) for destructive suites
5. Switch from `url-shortener` example to the appropriate test blueprint
6. Verify test still passes with old `run-all.sh` workflow

---

## 11. File Structure

### 11.1 New Directory Layout

```
aether/tests/
  integration/
    test-env.toml                     # NEW: default test environment config
    envs/                              # NEW: config templates
      local-dev.toml                   # forge, 5 cores
      local-docker.toml                # docker, 5 cores
      single-cloud.toml                # Hetzner single-zone, 5 cores + 3 workers
      multi-zone.toml                  # 2 Hetzner zones, inherits single-cloud
      on-prem.toml                     # SSH, 5 cores with host placeholders
    lib/
      common.sh                        # MODIFIED: endpoint file sourcing, timeout functions, test_endpoint()
      cluster.sh                       # MODIFIED: ENV_TYPE switch instead of CLOUD_MODE
      load.sh                          # UNCHANGED
      provisioner.sh                   # NEW: provision_forge/docker/ssh/cloud, write_endpoint_file
      database.sh                      # NEW: create_test_schemas, start_postgres_sidecar
      partition.sh                     # NEW: partition_node, heal_partition (Docker only)
      timing.sh                        # NEW: detect_timing_regressions
      dashboard.sh                     # NEW: generate_status_html, generate_status_txt
    scripts/
      setup-test-env.sh                # NEW: unified provisioner
      teardown-test-env.sh             # NEW: unified teardown
      run-all-v2.sh                    # NEW: dual-cluster runner
      leak-audit.sh                    # NEW: orphaned resource checker
      run-suite.sh                     # MODIFIED: source endpoint file
      deploy-compose.sh                # KEPT (backward compat, removed in Phase 10)
      deploy-cloud.sh                  # KEPT (backward compat, removed in Phase 10)
      run-all.sh                       # KEPT (backward compat, removed in Phase 10)
      setup.sh                         # KEPT (backward compat, removed in Phase 10)
      cleanup.sh                       # KEPT (used by teardown-test-env.sh)
    replay/                            # NEW: batch command files
      smoke-check.toml
      smoke-check.txt
    results/                           # NEW: test output (gitignored)
      test-results.json
      test-timing-history.json
      test-status.html
      test-status.txt
    suites/
      00-smoke/
        suite.conf                     # NEW
        test-cluster-formation.sh
        test-slice-deployment.sh
      01-stability/
        suite.conf                     # NEW
        ...
      ... (all 16 suites get suite.conf)
    docker-compose.yml                 # UNCHANGED
    cluster-config.toml                # UNCHANGED
  blueprints/                          # NEW: test blueprint projects
    test-echo/
      pom.xml
      src/main/java/.../EchoSlice.java
      src/main/resources/aether.toml
    test-persistence/
      pom.xml
      src/main/java/.../PersistenceSlice.java
      src/main/resources/aether.toml
      src/main/resources/schema.sql
    test-streaming/
      pom.xml
      ...
    test-http/
      pom.xml
      ...
    test-scheduled/
      pom.xml
      ...
    test-security/
      pom.xml
      ...
    test-storage/
      pom.xml
      ...
    test-full/
      pom.xml
      src/main/java/.../FullSlice.java
      v2/
        pom.xml
        src/main/java/.../FullSliceV2.java
```

### 11.2 New Files Summary

| File | Size Est. | Purpose |
|------|-----------|---------|
| `test-env.toml` | 30 lines | Default test environment config (docker-5node) |
| `envs/*.toml` | 5 files, ~30 lines each | Config templates |
| `lib/provisioner.sh` | ~150 lines | Provisioning functions + endpoint file writer |
| `lib/database.sh` | ~80 lines | DB schema creation + postgres sidecar |
| `lib/partition.sh` | ~30 lines | Network partition (Docker only) |
| `lib/timing.sh` | ~40 lines | Timing regression detection |
| `lib/dashboard.sh` | ~80 lines | HTML + TXT status generation |
| `scripts/setup-test-env.sh` | ~100 lines | Unified provisioner |
| `scripts/teardown-test-env.sh` | ~50 lines | Unified teardown + leak audit |
| `scripts/run-all-v2.sh` | ~200 lines | Dual-cluster runner |
| `scripts/leak-audit.sh` | ~60 lines | Orphaned resource checker |
| `suites/*/suite.conf` | 16 files, ~8 lines each | Suite metadata |
| `blueprints/test-*` | 8 projects | Test blueprint Maven projects |
| `replay/*.toml` | ~20 lines | Batch command replay files |

### 11.3 Modified Files Summary

| File | Changes |
|------|---------|
| `lib/common.sh` | Endpoint file sourcing (10 lines changed), timeout functions (15 lines added), `test_endpoint()` |
| `lib/cluster.sh` | Replace `CLOUD_MODE` with `ENV_TYPE` switch, add `wait_for_baseline()` with self-heal (~60 lines) |
| `scripts/run-suite.sh` | Add endpoint file sourcing, write per-suite result JSON (10 lines) |
| `ClusterBootstrapCommand.java` | Add `OutputOptions` mixin, emit structured JSON via `OutputFormatter` (~40 lines) |

---

## 12. References

### Technical Documentation

- [Cluster Bootstrap Spec](cluster-bootstrap-spec.md) -- ClusterBootstrapConfig model, source types, runtime profiles
- [ClusterBootstrapConfig.java](../../aether-config/src/main/java/org/pragmatica/aether/config/cluster/ClusterBootstrapConfig.java) -- Config record
- [SourceType.java](../../aether-config/src/main/java/org/pragmatica/aether/config/cluster/SourceType.java) -- CLOUD, SSH, FORGE, DOCKER
- [SourceProfile.java](../../aether-config/src/main/java/org/pragmatica/aether/config/cluster/SourceProfile.java) -- Source profile record
- [OutputFormatter.java](../../cli/src/main/java/org/pragmatica/aether/cli/OutputFormatter.java) -- CLI output formatting (JSON, table, CSV, value)
- [OutputOptions.java](../../cli/src/main/java/org/pragmatica/aether/cli/OutputOptions.java) -- Picocli mixin for --format, --field, --quiet
- [ClusterBootstrapCommand.java](../../cli/src/main/java/org/pragmatica/aether/cli/cluster/ClusterBootstrapCommand.java) -- Bootstrap command (needs OutputFormatter wiring)
- [EmberCluster.java](../../ember/src/main/java/org/pragmatica/aether/ember/EmberCluster.java) -- Forge cluster with `killNode()` and `addNode()` APIs
- [ChaosRoutes.java](../../forge/forge-api/src/main/java/org/pragmatica/aether/forge/api/ChaosRoutes.java) -- REST API for chaos operations

### Internal References

- `aether/tests/integration/lib/common.sh` -- Current test library
- `aether/tests/integration/lib/cluster.sh` -- Current cluster operations
- `aether/tests/integration/scripts/run-all.sh` -- Current sequential runner
- `aether/tests/integration/scripts/deploy-compose.sh` -- Docker compose provisioner
- `aether/tests/integration/scripts/deploy-cloud.sh` -- Cloud provisioner
- `aether/tests/integration/docker-compose.yml` -- 5-node compose definition

### Resolved Decisions

| ID | Decision | Rationale |
|----|----------|-----------|
| D-1 | 5 core nodes minimum | 3 nodes is too fragile -- single node loss = no quorum |
| D-2 | Dual-cluster strategy | Minimizes wall time; destructive tests cannot run with non-destructive |
| D-3 | Forge chaos enabled | EmberCluster has killNode()/addNode() APIs via ChaosRoutes |
| D-4 | Network partition Docker-only | `docker network disconnect/connect` is trivial; cloud deferred; forge impossible |
| D-5 | TOML only, no YAML | Project convention; consistency with all other configs |
| D-6 | No Python dependencies | Shell + jq + aether CLI for all parsing |
| D-7 | Test blueprints as independent projects | Full isolation prevents interference in dual-cluster runs |
| D-8 | Containerized PostgreSQL sidecar | Same pattern across all environments; schema isolation per blueprint |
| D-9 | CI publishes Docker images | Eliminates "build on target" for cloud/SSH |
| D-10 | Self-heal between destructive tests | `wait_for_baseline(120s)` then full restart then abort |
| D-11 | Relative state assertions | Post-heal state is unpredictable; tests must not assume absolute state |
| D-12 | OutputFormatter for bootstrap (step 0) | Machine-parseable output required before provisioner scripts work |

### Open Questions

- [TBD-1] Spot instance support in `aether cluster bootstrap` -- not yet implemented. Recommendation: spot for non-destructive, on-demand for destructive. Future work.
- [TBD-2] `aether config read` CLI command -- does it exist? If not, TOML parsing falls back to `jq` + a simple TOML-to-JSON converter in the aether CLI, or a shell-based parser for flat keys.
- [TBD-3] `aether batch execute` CLI command -- new command needed for test replay. Implementation scope TBD.
