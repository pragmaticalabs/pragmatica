# Aether Integration Tests

End-to-end integration tests that exercise a live Aether cluster deployed on a remote host.

## Prerequisites

- **Remote host** with Docker installed (tested with Docker 29.x on Linux)
- **SSH access** to the remote host (key-based authentication)
- **Aether CLI** (`aether`) installed locally
- **aether-node Docker image** built and available on the remote host
- Python 3 (for JSON parsing in test assertions)
- curl, bash 4+

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `TARGET_HOST` | Yes | — | IP or hostname of the remote test machine |
| `AETHER_SSH_USER` | Yes | — | SSH username for remote commands |
| `AETHER_SSH_KEY` | Yes | — | Path to SSH private key |
| `AETHER_API_KEY` | No | `aether-integration-test-key` | API key for cluster authentication |
| `MGMT_PORT` | No | `5150` | Base management port (nodes use 5150..5154) |
| `APP_PORT` | No | `8070` | Application HTTP port |
| `NODE_COUNT` | No | `5` | Number of cluster nodes |
| `SKIP_SOAK` | No | `true` | Skip long-running soak tests |
| `COLLECT_METRICS` | No | `false` | Collect thread/heap metrics before/after tests |

## Quick Start

### 1. Build the node JAR

```bash
mvn install -DskipTests -Djbct.skip=true -q
```

### 2. Upload and build Docker image on remote host

```bash
scp -i "$AETHER_SSH_KEY" aether/node/target/aether-node.jar \
    aether/docker/aether-node/Dockerfile \
    aether/docker/aether-node/aether.toml \
    "${AETHER_SSH_USER}@${TARGET_HOST}:/tmp/"

ssh -i "$AETHER_SSH_KEY" "${AETHER_SSH_USER}@${TARGET_HOST}" \
    "cd /tmp && docker build -t aether-node:local \
     --build-arg JAR_PATH=aether-node.jar \
     --build-arg CONFIG_PATH=aether.toml \
     -f Dockerfile ."
```

### 3. Start the 5-node cluster

```bash
ssh -i "$AETHER_SSH_KEY" "${AETHER_SSH_USER}@${TARGET_HOST}" '
docker network create aether-network 2>/dev/null || true
DOCKER_GID=$(stat -c "%g" /var/run/docker.sock)
PEERS=""
for i in $(seq 1 5); do
    [ -n "$PEERS" ] && PEERS="${PEERS},"
    PEERS="${PEERS}integration-test-${i}:aether-integration-test-${i}:6000"
done
for i in $(seq 1 5); do
    docker run -d \
        --name "aether-integration-test-${i}" \
        --hostname "aether-integration-test-${i}" \
        --network aether-network \
        -v /var/run/docker.sock:/var/run/docker.sock \
        --group-add "$DOCKER_GID" \
        -p "$((5149 + i)):8080" \
        -p "$((8069 + i)):8070" \
        -e "NODE_ID=integration-test-${i}" \
        -e "CLUSTER_PORT=6000" \
        -e "MANAGEMENT_PORT=8080" \
        -e "PEERS=${PEERS}" \
        -e "CORE_MAX=5" \
        -e "AETHER_API_KEY=aether-integration-test-key" \
        aether-node:local
done
'
```

### 4. Run tests

```bash
# Run all suites (soak tests excluded by default)
bash aether/tests/integration/scripts/run-all.sh

# Run a single suite
bash aether/tests/integration/scripts/run-suite.sh 02-chaos

# Run a single test
bash aether/tests/integration/suites/02-chaos/test-kill-leader.sh

# Include soak tests (long-running)
SKIP_SOAK=false bash aether/tests/integration/scripts/run-all.sh
```

### 5. Tear down

```bash
ssh -i "$AETHER_SSH_KEY" "${AETHER_SSH_USER}@${TARGET_HOST}" \
    'docker rm -f $(docker ps -a --filter "name=aether-integration-test" -q) 2>/dev/null; \
     docker network rm aether-network 2>/dev/null'
```

## Test Suites

| Suite | Tests | Description |
|-------|-------|-------------|
| `00-smoke` | 2 | Cluster formation, slice deployment |
| `01-stability` | 2 | 4-hour soak test, streaming soak (skipped by default) |
| `02-chaos` | 4 | Kill leader, kill node, kill multiple, kill under load |
| `03-scaling` | 3 | Scale up, scale down, quorum safety |
| `04-streaming` | 4 | Publish, consume, replication, load |
| `05-security` | 3 | Cert rotation, principal injection, route security |
| `06-deployment` | 4 | Rolling upgrade, canary, blue-green, schema migration |
| `07-cluster-mgmt` | 4 | Bootstrap, apply, export, destroy |
| `08-resources` | 5 | SQL, HTTP client, pub-sub, scheduled tasks, streaming |
| `09-artifacts` | 3 | Push/resolve, replication, large artifacts |
| `10-database` | 3 | Schema baseline, versioned migration, retry |
| `11-observability` | 5 | Metrics, alerts, traces, transport, certificates |
| `12-network` | 3 | QUIC connectivity, SWIM detection, gossip encryption |
| `13-edge-cases` | 3 | Concurrent deploys, disruption budget, stale routes |
| `14-storage` | 2 | Storage CLI, storage management |

## Architecture

```
integration/
  lib/
    common.sh      # Assertions, HTTP helpers, SSH, logging, test runner
    cluster.sh     # Cluster queries, node ops, deploy, scaling, streams
  scripts/
    run-all.sh     # Run all suites sequentially
    run-suite.sh   # Run a single suite by name
  suites/
    00-smoke/      # Tests ordered by dependency and risk
    01-stability/
    ...
  cluster-config.toml   # Cluster topology for aether CLI bootstrap
```

Each test script:
1. Sources `common.sh` and `cluster.sh` from `lib/`
2. Defines test functions
3. Calls `run_test "name" function_name` for each test
4. Calls `print_summary` at the end (exit code reflects pass/fail)

## Writing New Tests

```bash
#!/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_my_feature() {
    # Use assert_eq, assert_ne, assert_gt, assert_ge, assert_contains
    local count
    count=$(cluster_node_count)
    assert_ge "$count" "3" "Cluster has quorum"

    # Use wait_for for async conditions
    wait_for "my condition" "some_check_command" 60

    # Use api_get/api_post for raw HTTP
    local response
    response=$(api_get "/api/some-endpoint")
    assert_contains "$response" "expected" "Response has expected content"
}

run_test "My feature test" test_my_feature
print_summary
```
