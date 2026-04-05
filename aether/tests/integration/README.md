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

## Quick Start (Docker Compose)

This is the standard workflow. All commands run from the repository root.

### 1. Deploy cluster to a remote host

```bash
# Full deploy: build + transfer + image build + start cluster
TARGET_HOST=192.168.0.71 \
AETHER_SSH_KEY=~/.ssh/aether_test \
bash aether/tests/integration/scripts/deploy-compose.sh

# Skip Maven build (reuse existing JAR):
TARGET_HOST=192.168.0.71 \
AETHER_SSH_KEY=~/.ssh/aether_test \
bash aether/tests/integration/scripts/deploy-compose.sh --skip-build

# Clean deploy (remove old containers/images first):
TARGET_HOST=192.168.0.71 \
AETHER_SSH_KEY=~/.ssh/aether_test \
bash aether/tests/integration/scripts/deploy-compose.sh --clean
```

The deploy script:
1. Builds the project locally (`mvn clean install -DskipTests`)
2. Builds example slices (`url-shortener`, `url-shortener-v2`)
3. Copies JAR, Dockerfile, config, and docker-compose.yml to the target
4. Builds the Docker image **on the target** (avoids arch mismatch)
5. Starts a 5-node cluster via `docker compose`
6. Waits for cluster health and prints connection details

### 2. Verify deployment

```bash
# Check build timestamp (confirms correct binary is running):
curl -s -H "X-API-Key: aether-integration-test-key" http://192.168.0.71:5150/api/status \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'Build: {d.get(\"buildTimestamp\",\"?\")}, Leader: {d[\"leader\"]}')"

# Check task delegation:
curl -s -H "X-API-Key: aether-integration-test-key" http://192.168.0.71:5150/api/cluster/tasks \
  | python3 -m json.tool
```

### 3. Run tests

```bash
# Run all suites (excludes soak tests by default):
TARGET_HOST=192.168.0.71 \
AETHER_SSH_KEY=~/.ssh/aether_test \
bash aether/tests/integration/scripts/run-all.sh

# Run a specific suite:
TARGET_HOST=192.168.0.71 \
AETHER_SSH_KEY=~/.ssh/aether_test \
bash aether/tests/integration/scripts/run-suite.sh 00-smoke

# Run a single test file:
TARGET_HOST=192.168.0.71 \
AETHER_SSH_KEY=~/.ssh/aether_test \
bash aether/tests/integration/suites/15-delegation/test-01-task-assignments.sh

# Include soak tests (4+ hours):
TARGET_HOST=192.168.0.71 \
AETHER_SSH_KEY=~/.ssh/aether_test \
SKIP_SOAK=false \
bash aether/tests/integration/scripts/run-all.sh
```

### 4. Tear down

```bash
ssh -i ~/.ssh/aether_test aether@192.168.0.71 'docker compose down -v'
```

## Cloud Deployment (Hetzner, AWS, etc.)

For testing on real cloud infrastructure (not docker-compose). Each cloud instance runs one
Aether node via Docker with `--network host`.

### Hetzner (with hcloud CLI)

```bash
# Create instances + deploy + start:
PROVIDER=hetzner \
AETHER_SSH_KEY=~/.ssh/hetzner \
HCLOUD_TOKEN=xxx \
bash aether/tests/integration/scripts/deploy-cloud.sh --create

# Run tests against the cluster:
TARGET_HOST=<first-node-ip> \
MGMT_PORT=8080 \
AETHER_SSH_KEY=~/.ssh/hetzner \
AETHER_SSH_USER=root \
bash aether/tests/integration/scripts/run-all.sh

# Destroy instances when done:
PROVIDER=hetzner bash aether/tests/integration/scripts/deploy-cloud.sh --destroy
```

### Generic (existing instances)

```bash
# Deploy to pre-provisioned instances:
NODES=10.0.0.1,10.0.0.2,10.0.0.3,10.0.0.4,10.0.0.5 \
AETHER_SSH_KEY=~/.ssh/cloud \
AETHER_SSH_USER=ubuntu \
bash aether/tests/integration/scripts/deploy-cloud.sh

# Important: with cloud deployment, management port is 8080 (no docker port mapping)
TARGET_HOST=10.0.0.1 \
MGMT_PORT=8080 \
APP_PORT=8070 \
AETHER_SSH_KEY=~/.ssh/cloud \
AETHER_SSH_USER=ubuntu \
bash aether/tests/integration/scripts/run-all.sh
```

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
  scripts/
    deploy-compose.sh  # One-command deploy via docker-compose (recommended)
    deploy-cloud.sh    # Deploy to bare cloud instances (Hetzner, AWS, etc.)
    run-all.sh         # Run all suites sequentially
    run-suite.sh       # Run a single suite by name
    setup.sh           # Legacy setup script (use deploy-compose.sh instead)
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
- Use `deploy-compose.sh` which handles tagging correctly

**Cross-architecture issues:**
- The deploy scripts always build the Docker image on the target host
- Never transfer a locally-built Docker image to a different architecture
