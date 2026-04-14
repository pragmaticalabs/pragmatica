# Aether Integration Tests

End-to-end integration tests that exercise a live Aether cluster. Tests run against a remote host
(docker-compose or bare cloud instances) and verify cluster formation, slice deployment, delegation,
chaos recovery, scaling, streaming, security, and more.

## Prerequisites

**On your dev machine:**
- Java 25 + Maven 3.9+
- SSH key with access to the target host
- `curl`, `bash 4+`, `python3` (for JSON parsing in assertions)
- `aether` CLI installed locally (optional — tests use HTTP fallback)

**On the target host:**
- Linux (x86_64 tested; arm64 works but image is built on-target to avoid arch mismatch)
- Docker 27+ with Compose V2 (`docker compose`)
- SSH access with key-based auth
- Ports 5150-5154 (management), 8070-8074 (app HTTP) accessible from dev machine

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `TARGET_HOST` | **Yes** | — | IP or hostname of the target host |
| `AETHER_SSH_KEY` | **Yes** | — | Path to SSH private key |
| `AETHER_SSH_USER` | No | `aether` | SSH username |
| `AETHER_API_KEY` | No | `aether-integration-test-key` | API key for cluster authentication |
| `MGMT_PORT` | No | `5150` | Management port of node-1 (nodes: 5150-5154) |
| `APP_PORT` | No | `8070` | App HTTP port of node-1 (nodes: 8070-8074) |
| `NODE_COUNT` | No | `5` | Number of cluster nodes |
| `SKIP_SOAK` | No | `true` | Skip long-running soak tests |
| `COLLECT_METRICS` | No | `false` | Collect thread/heap metrics before/after tests |

## Quick Start

A single dual-cluster runner — `run-tests.sh` — handles provisioning, suite execution, and teardown across `docker` (local), `remote` (existing host), and `cloud` (Hetzner today) environments.

```bash
# Run all non-soak suites locally
./aether/tests/integration/run-tests.sh --env docker

# Run specific suites on a remote host
TARGET_HOST=192.168.0.71 AETHER_SSH_KEY=~/.ssh/aether_test \
  ./aether/tests/integration/run-tests.sh --env remote --suites 00,02,06

# Skip the cluster provisioning step (assume cluster is already up)
./aether/tests/integration/run-tests.sh --env docker --skip-deploy

# Cloud run, leave clusters up afterwards for inspection
HCLOUD_TOKEN=xxx \
  ./aether/tests/integration/run-tests.sh --env cloud --skip-teardown
```

Run `./aether/tests/integration/run-tests.sh --help` for the full flag list.

### Single suite or single file

```bash
# One suite via the runner
./aether/tests/integration/run-tests.sh --env docker --suites 00-smoke

# One file (suite scripts are self-contained, can be sourced directly)
TARGET_HOST=192.168.0.71 AETHER_SSH_KEY=~/.ssh/aether_test \
  bash aether/tests/integration/suites/15-delegation/test-01-task-assignments.sh
```

### Verify deployment

```bash
# Status and leader (uses the project CLI, no jq/python3)
aether --field status leader
aether topology
```

### Tear down

`run-tests.sh` tears the cluster down by default. Override with `--skip-teardown`.

## Test Suites

| Suite | Scripts | Description |
|-------|---------|-------------|
| `00-smoke` | 2 | Cluster formation, slice deployment |
| `01-stability` | 2 | 4-hour soak, streaming soak (skipped by default) |
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
| `15-delegation` | 2 | Task group assignments, operator reassignment, node failure recovery |

## Directory Structure

```
integration/
  lib/
    common.sh          # Assertions, HTTP helpers, SSH, logging, test runner
    cluster.sh         # Cluster queries, node ops, deploy, scaling, streams, delegation
    load.sh            # k6 load test helpers
  run-tests.sh         # Dual-cluster runner: provisioning + suites + teardown
  scripts/
    cleanup.sh         # Tear down cluster (also invoked by run-tests.sh teardown phase)
  suites/
    00-smoke/          # Tests ordered by dependency and risk
    01-stability/
    ...
    15-delegation/
  docker-compose.yml   # 5-node cluster definition
  cluster-config.toml  # Cluster topology for aether CLI bootstrap
```

## Writing New Tests

```bash
#!/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_my_feature() {
    # Assertions: assert_eq, assert_ne, assert_gt, assert_ge, assert_contains
    local count
    count=$(cluster_node_count)
    assert_ge "$count" "3" "Cluster has quorum"

    # Async wait: wait_for "description" "check_command" timeout_seconds
    wait_for "my condition" "some_check_command" 60

    # HTTP: api_get, api_post, api_put, api_delete (management API)
    # App HTTP: app_get, app_post
    local response
    response=$(api_get "/api/some-endpoint")
    assert_contains "$response" "expected" "Response has expected content"

    # JSON: json_field, json_len, assert_json_field
    assert_json_field "$response" "['status']" "UP" "Status is UP"

    # Delegation: cluster_tasks, task_group_status, task_group_node, reassign_task_group
    local status
    status=$(task_group_status "METRICS")
    assert_eq "$status" "ACTIVE" "METRICS group active"
}

run_test "My feature test" test_my_feature
print_summary
```

## Troubleshooting

**Tests fail with "connection refused":**
- Ensure `TARGET_HOST` is reachable and Docker containers are running
- Check: `curl -s http://${TARGET_HOST}:5150/health/live`

**Build timestamp shows "unknown":**
- The JAR was built before the build-info feature. Rebuild with `mvn clean install -DskipTests`

**Tasks API returns empty assignments:**
- Leader may not have been elected yet. Wait 30s after cluster start
- Check: `curl -s -H "X-API-Key: aether-integration-test-key" http://${TARGET_HOST}:5150/api/cluster/tasks`

**Slice deployment times out:**
- Example slices may need rebuilding (envelope version mismatch)
- Rebuild: `mvn -f examples/url-shortener/pom.xml clean install -DskipTests`

**Docker image tag mismatch:**
- docker-compose expects `aether-node:local` (not `latest`)
- `run-tests.sh` handles tagging correctly during the deploy phase

**Cross-architecture issues:**
- `run-tests.sh` always builds the Docker image on the target host
- Never transfer a locally-built Docker image to a different architecture
