# Hetzner Cloud End-to-End Test Specification

**Version:** 1.0
**Status:** Draft
**Target Release:** 0.21.0
**Author:** spec-writer agent
**Date:** 2026-03-19

---

## Table of Contents

1. [Overview and Goals](#1-overview-and-goals)
2. [Infrastructure Architecture](#2-infrastructure-architecture)
3. [File Structure](#3-file-structure)
4. [Master Orchestrator](#4-master-orchestrator)
5. [Phase 1: Setup](#5-phase-1-setup)
6. [Phase 2: Deploy](#6-phase-2-deploy)
7. [Phase 3: Benchmark](#7-phase-3-benchmark)
8. [Phase 4: Resilience](#8-phase-4-resilience)
9. [Phase 5: Report](#9-phase-5-report)
10. [Phase 6: Teardown](#10-phase-6-teardown)
11. [Configuration Templates](#11-configuration-templates)
12. [Success Criteria](#12-success-criteria)
13. [Error Handling and Safety](#13-error-handling-and-safety)
14. [Relationship to Existing Cloud Tests](#14-relationship-to-existing-cloud-tests)
15. [Open Questions](#15-open-questions)
16. [References](#16-references)

---

## 1. Overview and Goals

### 1.1 Purpose

This specification defines a fully automated, repeatable end-to-end test that validates the Aether distributed runtime on real Hetzner Cloud infrastructure. The test deploys a 5-node Aether cluster, runs the url-shortener example application via blueprint artifact, executes k6 benchmarks at production-level throughput, and exercises resilience scenarios (graceful drain, hard kill, auto-heal, leader kill) while monitoring latency and error rates.

### 1.2 Goals

- **G-1:** Validate Aether cluster formation, leader election, and quorum on Hetzner Cloud with private networking
- **G-2:** Validate blueprint artifact deployment via the management API on cloud infrastructure
- **G-3:** Establish baseline performance metrics (latency, throughput, error rate) for the url-shortener workload at 500, 2000, and 4000 req/s
- **G-4:** Validate resilience: graceful drain (zero errors), hard kill (recovery < 15s), auto-heal (new node < 90s), leader kill (re-election < 5s)
- **G-5:** Produce machine-readable test results (k6 JSON, cluster logs) for regression tracking
- **G-6:** Entire test suite must be runnable with a single command: `./run.sh all`
- **G-7:** Idempotent: re-running any phase must be safe (no orphaned resources, no duplicate infrastructure)

### 1.3 Non-Goals

- Multi-region load balancing (single-region Hetzner LB is in scope; multi-region is not)
- Multi-region deployment
- TLS/certificate provisioning
- Persistent storage benchmarking (PostgreSQL is ephemeral for the test)
- Cost optimization (CX32 instances are minimum production-grade)

### 1.4 Prerequisites

| Prerequisite | Details |
|-------------|---------|
| `HETZNER_TOKEN` | Hetzner Cloud API token with read/write access to servers, networks, firewalls, SSH keys |
| `AETHER_DB_PASSWORD` | Password for the PostgreSQL database (defaults to `aether-e2e-test` if unset) |
| Local build | `aether/node/target/aether-node.jar` must exist (run `mvn install -DskipTests` first) |
| Local build | `examples/url-shortener/target/url-shortener-0.21.0-blueprint.jar` must exist |
| `hcloud` CLI | Installed automatically by setup phase if missing |
| `k6` CLI | Only needed on the k6 VM (installed via cloud-init); NOT required locally |
| SSH key | Generated automatically by setup phase; no pre-existing key required |
| `jq` | Required locally for JSON parsing in report phase |

---

## 2. Infrastructure Architecture

### 2.1 Network Topology

```
                    10.0.0.0/16 Private Network (aether-e2e-net)
                    Subnet: 10.0.1.0/24 (nbg1)
    +---------------------------------------------------------------------------+
    |                                                                           |
    |  +-------------+  +-------------+  +-------------+  +-------------+      |
    |  | aether-e2e-1|  | aether-e2e-2|  | aether-e2e-3|  | aether-e2e-4|     |
    |  | CX32        |  | CX32        |  | CX32        |  | CX32        |     |
    |  | 10.0.1.11   |  | 10.0.1.12   |  | 10.0.1.13   |  | 10.0.1.14   |     |
    |  | :8080 mgmt  |  | :8080 mgmt  |  | :8080 mgmt  |  | :8080 mgmt  |     |
    |  | :8070 app   |  | :8070 app   |  | :8070 app   |  | :8070 app   |     |
    |  | :9100 cluster|  | :9100 cluster|  | :9100 cluster|  | :9100 cluster|  |
    |  +-------------+  +-------------+  +-------------+  +-------------+      |
    |                                                                           |
    |  +-------------+  +-------------+  +-------------+                        |
    |  | aether-e2e-5|  | pg-e2e      |  | k6-e2e      |                       |
    |  | CX32        |  | CX32        |  | CX32        |                       |
    |  | 10.0.1.15   |  | 10.0.1.20   |  | 10.0.1.30   |                       |
    |  | :8080 mgmt  |  | :5432 PG    |  | k6 runner   |                       |
    |  | :8070 app   |  |             |  |             |                        |
    |  | :9100 cluster|  |             |  |             |                       |
    |  +-------------+  +-------------+  +-------------+                        |
    +---------------------------------------------------------------------------+
```

### 2.2 VM Specifications

| VM Name | Role | Type | Private IP | Ports | Labels |
|---------|------|------|-----------|-------|--------|
| `aether-e2e-1` | Aether node | CX32 (4 vCPU, 8 GB) | 10.0.1.11 | 8080, 8070, 9100 | `aether-cluster=e2e-test`, `aether-port=9100` |
| `aether-e2e-2` | Aether node | CX32 | 10.0.1.12 | 8080, 8070, 9100 | `aether-cluster=e2e-test`, `aether-port=9100` |
| `aether-e2e-3` | Aether node | CX32 | 10.0.1.13 | 8080, 8070, 9100 | `aether-cluster=e2e-test`, `aether-port=9100` |
| `aether-e2e-4` | Aether node | CX32 | 10.0.1.14 | 8080, 8070, 9100 | `aether-cluster=e2e-test`, `aether-port=9100` |
| `aether-e2e-5` | Aether node | CX32 | 10.0.1.15 | 8080, 8070, 9100 | `aether-cluster=e2e-test`, `aether-port=9100` |
| `pg-e2e` | PostgreSQL 16 | CX32 | 10.0.1.20 | 5432 | `aether-cluster=e2e-test`, `role=postgres` |
| `k6-e2e` | Load generator | CX32 | 10.0.1.30 | - | `aether-cluster=e2e-test`, `role=k6` |
| `lb-e2e` | Hetzner LB | LB11 | public IP | 8070 → nodes:8070 | Health: `GET /health/ready` on 8080 |

**Load Balancer:** Hetzner LB (`lb-e2e`) fronts all 5 Aether nodes on app HTTP port 8070. k6 targets the single LB public IP for realistic production topology. Health checks use `/health/ready` on management port 8080. `LoadBalancerManager` auto-registers/deregisters nodes as they join, drain, or die.

### 2.3 Region and Image

- **Region:** `nbg1` (Nuremberg, Germany)
- **Image:** `ubuntu-24.04`
- **SSH:** Ephemeral key pair generated per test run, registered with Hetzner, destroyed on teardown

### 2.4 Firewall Rules

Firewall name: `aether-e2e-fw`

| Direction | Protocol | Port Range | Source/Dest | Purpose |
|-----------|----------|-----------|-------------|---------|
| Inbound | TCP | 22 | Operator IP (`$(curl -s ifconfig.me)/32`) | SSH access |
| Inbound | TCP/UDP | 1-65535 | 10.0.0.0/16 | Internal cluster communication |
| Inbound | ICMP | - | 10.0.0.0/16 | Internal health checks |

[ASSUMPTION] No public access is needed beyond SSH. k6 runs from within the private network. If external access to app ports is needed for debugging, the operator can temporarily add rules via `hcloud firewall add-rule`.

### 2.5 Cost Estimate

| Resource | Unit Cost | Quantity | Duration (est.) | Total |
|----------|-----------|----------|-----------------|-------|
| CX32 server | ~EUR 0.0154/hr | 7 | ~1 hour | ~EUR 0.11 |
| LB11 load balancer | ~EUR 0.0082/hr | 1 | ~1 hour | ~EUR 0.01 |
| Private network | Free | 1 | - | EUR 0 |
| Firewall | Free | 1 | - | EUR 0 |
| **Total per run** | | | | **~EUR 0.12** |

---

## 3. File Structure

```
tests/hetzner-e2e/
  run.sh                          # Master orchestrator (REQ-ORCH-01)
  scripts/
    setup.sh                      # Infrastructure provisioning (REQ-SETUP-*)
    deploy.sh                     # Aether + blueprint deployment (REQ-DEPLOY-*)
    benchmark.sh                  # k6 benchmark runs (REQ-BENCH-*)
    resilience.sh                 # Drain/kill/auto-heal scenarios (REQ-RESIL-*)
    report.sh                     # Results collection (REQ-REPORT-*)
    teardown.sh                   # Cleanup (REQ-TEAR-*)
    lib.sh                        # Shared functions (logging, polling, SSH helpers)
  config/
    aether.toml.template          # Node config template with envsubst markers
    cloud-init-node.yaml          # Cloud-init for Aether nodes
    cloud-init-pg.yaml            # Cloud-init for PostgreSQL VM
    cloud-init-k6.yaml            # Cloud-init for k6 VM
    aether-node.service           # systemd unit for aether-node
  k6/                             # Symlink or copy from examples/url-shortener/k6/
    load-test.js
    spike.js
    ramp-up.js
    env.sh                        # Overridden for Hetzner (FORGE_NODES points to cluster)
  results/                        # Test outputs (.gitignored)
    baseline/                     # k6 JSON outputs from benchmark phase
    resilience/                   # k6 JSON outputs from resilience phase
    logs/                         # Collected node logs
    summary.json                  # Machine-readable summary
    summary.txt                   # Human-readable summary
```

### 3.1 .gitignore

The `results/` directory MUST be gitignored. The `tests/hetzner-e2e/.gitignore` file:

```
results/
*.pem
*.key
state.env
```

---

## 4. Master Orchestrator

### REQ-ORCH-01: `run.sh` Command Interface

```bash
./run.sh <phase>
```

| Phase | Description | Idempotent | Depends On |
|-------|-------------|------------|------------|
| `setup` | Provision infrastructure | Yes (skips existing resources) | None |
| `deploy` | Deploy Aether + blueprint | Yes (re-deploys) | `setup` |
| `benchmark` | Run baseline benchmarks | Yes (overwrites results) | `deploy` |
| `resilience` | Run resilience scenarios | No (destructive by nature) | `deploy` |
| `report` | Collect results and logs | Yes | `benchmark` or `resilience` |
| `teardown` | Destroy all infrastructure | Yes (skips missing resources) | None |
| `all` | Run all phases sequentially | No | None |

### REQ-ORCH-02: State File

A `state.env` file persists resource IDs between phases. Format:

```bash
NETWORK_ID=12345
FIREWALL_ID=12346
SSH_KEY_ID=12347
SSH_KEY_PATH=/tmp/aether-e2e-key
PG_SERVER_ID=12348
PG_IP=10.0.1.20
PG_PUBLIC_IP=1.2.3.4
K6_SERVER_ID=12349
K6_IP=10.0.1.30
K6_PUBLIC_IP=1.2.3.5
NODE_1_SERVER_ID=12350
NODE_1_IP=10.0.1.11
NODE_1_PUBLIC_IP=1.2.3.6
NODE_2_SERVER_ID=12351
NODE_2_IP=10.0.1.12
NODE_2_PUBLIC_IP=1.2.3.7
NODE_3_SERVER_ID=12352
NODE_3_IP=10.0.1.13
NODE_3_PUBLIC_IP=1.2.3.8
NODE_4_SERVER_ID=12353
NODE_4_IP=10.0.1.14
NODE_4_PUBLIC_IP=1.2.3.9
NODE_5_SERVER_ID=12354
NODE_5_IP=10.0.1.15
NODE_5_PUBLIC_IP=1.2.3.10
LEADER_NODE=NODE_1
CLUSTER_READY=true
BLUEPRINT_DEPLOYED=true
```

### REQ-ORCH-03: Environment Validation

Before any phase, `run.sh` MUST validate:

1. `HETZNER_TOKEN` is set and non-empty
2. `hcloud` CLI is available (install if missing via `brew install hcloud` on macOS or download binary on Linux)
3. `jq` is available
4. `ssh` and `scp` are available
5. For `deploy`/`benchmark`/`resilience`: `state.env` exists and contains required IDs
6. For `deploy`: `aether-node.jar` exists at `../../aether/node/target/aether-node.jar` (relative to script)
7. For `deploy`: `url-shortener-*-blueprint.jar` exists at `../../examples/url-shortener/target/`

### REQ-ORCH-04: Logging

All output MUST use a consistent format:

```
[2026-03-19 14:32:01] [SETUP] Creating private network aether-e2e-net...
[2026-03-19 14:32:03] [SETUP] Network created: id=12345
[2026-03-19 14:32:03] [SETUP] Creating firewall aether-e2e-fw...
```

Prefix is the current phase name. Timestamps are ISO-8601 local time.

### REQ-ORCH-05: Timeout and Abort

Each phase has a maximum duration. If exceeded, the phase aborts with a non-zero exit code.

| Phase | Max Duration |
|-------|-------------|
| `setup` | 10 minutes |
| `deploy` | 10 minutes |
| `benchmark` | 15 minutes |
| `resilience` | 20 minutes |
| `report` | 5 minutes |
| `teardown` | 5 minutes |

`run.sh all` total timeout: 60 minutes.

---

## 5. Phase 1: Setup

### REQ-SETUP-01: hcloud CLI Installation

If `hcloud` is not found on `$PATH`:
- macOS: `brew install hcloud`
- Linux: download from `https://github.com/hetznercloud/cli/releases/latest`

Verify: `hcloud version` succeeds.

### REQ-SETUP-02: SSH Key Generation

1. Generate an Ed25519 key pair: `ssh-keygen -t ed25519 -f /tmp/aether-e2e-key -N "" -C "aether-e2e-test"`
2. Register with Hetzner: `hcloud ssh-key create --name aether-e2e-key --public-key-from-file /tmp/aether-e2e-key.pub`
3. Store the SSH key ID and path in `state.env`

Idempotency: If `hcloud ssh-key list -o noheader | grep aether-e2e-key` returns a result, skip creation and use the existing key ID. Re-generate the local key file if missing.

### REQ-SETUP-03: Private Network Creation

```bash
hcloud network create --name aether-e2e-net --ip-range 10.0.0.0/16
hcloud network add-subnet aether-e2e-net --type server --network-zone eu-central --ip-range 10.0.1.0/24
```

Idempotency: If `hcloud network list -o noheader | grep aether-e2e-net` returns a result, skip and use existing.

### REQ-SETUP-04: Firewall Creation

```bash
hcloud firewall create --name aether-e2e-fw
OPERATOR_IP="$(curl -s ifconfig.me)"
hcloud firewall add-rule aether-e2e-fw --direction in --protocol tcp --port 22 \
  --source-ips "${OPERATOR_IP}/32" --description "SSH from operator"
hcloud firewall add-rule aether-e2e-fw --direction in --protocol tcp --port any \
  --source-ips 10.0.0.0/16 --description "Internal TCP"
hcloud firewall add-rule aether-e2e-fw --direction in --protocol udp --port any \
  --source-ips 10.0.0.0/16 --description "Internal UDP"
hcloud firewall add-rule aether-e2e-fw --direction in --protocol icmp \
  --source-ips 10.0.0.0/16 --description "Internal ICMP"
```

Idempotency: If firewall exists, skip creation. Always verify rules are present.

### REQ-SETUP-05: PostgreSQL VM Provisioning

```bash
hcloud server create \
  --name pg-e2e \
  --type cx22 \
  --image ubuntu-24.04 \
  --location nbg1 \
  --ssh-key aether-e2e-key \
  --network aether-e2e-net \
  --firewall aether-e2e-fw \
  --user-data-from-file config/cloud-init-pg.yaml \
  --label aether-cluster=e2e-test \
  --label role=postgres
```

The `cloud-init-pg.yaml` template (see [Section 11.2](#112-cloud-init-pgyaml)):
1. Installs Docker via `apt`
2. Starts PostgreSQL 16 container:
   ```bash
   docker run -d --name postgres \
     --restart unless-stopped \
     -e POSTGRES_DB=aether \
     -e POSTGRES_USER=aether \
     -e POSTGRES_PASSWORD=${AETHER_DB_PASSWORD} \
     -p 5432:5432 \
     -v pgdata:/var/lib/postgresql/data \
     postgres:16-alpine
   ```
3. Waits for PostgreSQL to accept connections
4. Runs the url-shortener schema migration (the SQL from `examples/url-shortener/schema/url_shortener_pg/V001__create_tables.sql`)

After server is running, verify:
```bash
ssh -i $SSH_KEY_PATH root@$PG_PUBLIC_IP "docker exec postgres pg_isready -U aether"
```

### REQ-SETUP-06: Aether Node Provisioning

Create 5 Aether nodes sequentially (to get predictable private IPs) or in parallel (IPs assigned dynamically and read from `hcloud server describe`).

For each node `i` (1-5):

```bash
hcloud server create \
  --name aether-e2e-$i \
  --type cx22 \
  --image ubuntu-24.04 \
  --location nbg1 \
  --ssh-key aether-e2e-key \
  --network aether-e2e-net \
  --firewall aether-e2e-fw \
  --user-data-from-file config/cloud-init-node.yaml \
  --label aether-cluster=e2e-test \
  --label aether-port=9100
```

The `cloud-init-node.yaml` template (see [Section 11.1](#111-cloud-init-nodeyaml)):
1. Installs Java 25 (Eclipse Temurin) from Adoptium
2. Creates `/opt/aether/` directory
3. Creates systemd service file for `aether-node`

After cloud-init completes:
1. SCP `aether-node.jar` to each node: `scp -i $KEY aether-node.jar root@$IP:/opt/aether/`
2. Generate `aether.toml` from template with envsubst (substituting PG IP, HETZNER_TOKEN, etc.)
3. SCP `aether.toml` to each node: `scp -i $KEY aether.toml root@$IP:/opt/aether/`
4. Start the service: `ssh -i $KEY root@$IP "systemctl start aether-node"`

### REQ-SETUP-07: k6 VM Provisioning

```bash
hcloud server create \
  --name k6-e2e \
  --type cx22 \
  --image ubuntu-24.04 \
  --location nbg1 \
  --ssh-key aether-e2e-key \
  --network aether-e2e-net \
  --firewall aether-e2e-fw \
  --user-data-from-file config/cloud-init-k6.yaml \
  --label aether-cluster=e2e-test \
  --label role=k6
```

The `cloud-init-k6.yaml` template (see [Section 11.3](#113-cloud-init-k6yaml)):
1. Adds Grafana APT repository
2. Installs k6 and jq

### REQ-SETUP-08: Health Verification

After all servers are provisioned and Aether nodes are started:

1. Poll each node's `/api/health` endpoint (via public IP, port 8080) every 5 seconds for up to 3 minutes
2. Wait for all 5 nodes to report `{"ready":true}`
3. Poll `/api/status` on any node for leader election (non-null `leader` field) for up to 3 minutes
4. Verify all nodes agree on the same leader (query `/api/status` on each, extract leader, assert single value)
5. Verify all nodes see each other (query `/api/nodes` on each, assert 5 nodes visible)

This mirrors the validation in the existing `ClusterFormationCloudIT` Java test but using shell + curl.

Polling function in `lib.sh`:

```bash
wait_for_health() {
    local ip=$1 max_wait=${2:-180} interval=${3:-5}
    local elapsed=0
    while [ $elapsed -lt $max_wait ]; do
        if curl -sf "http://${ip}:8080/api/health" | jq -e '.ready == true' > /dev/null 2>&1; then
            return 0
        fi
        sleep $interval
        elapsed=$((elapsed + interval))
    done
    return 1
}
```

### REQ-SETUP-09: Private IP Resolution

After server creation, resolve private IPs:

```bash
NODE_IP=$(hcloud server describe aether-e2e-$i -o json | jq -r '.private_net[0].ip')
NODE_PUBLIC_IP=$(hcloud server describe aether-e2e-$i -o json | jq -r '.public_net.ipv4.ip')
```

Store both in `state.env`. The `aether.toml` on each node uses private IPs for cluster communication. SSH and health-check polling use public IPs.

---

## 6. Phase 2: Deploy

### REQ-DEPLOY-01: Cluster Readiness Verification

Before deploying the blueprint, verify:
1. `state.env` exists and contains all server IDs
2. All 5 Aether nodes respond to `/api/health` with `ready:true`
3. A leader is elected (`/api/status` returns non-null leader)

If any check fails, exit with error.

### REQ-DEPLOY-02: Build Blueprint Locally

If the blueprint JAR does not exist:

```bash
cd $PROJECT_ROOT
mvn install -pl examples/url-shortener -am -DskipTests
```

Verify: `examples/url-shortener/target/url-shortener-*-blueprint.jar` exists.

[ASSUMPTION] The blueprint JAR name follows the pattern `url-shortener-${version}-blueprint.jar`. The exact name is resolved via glob.

### REQ-DEPLOY-03: Upload Blueprint Artifact

Upload the blueprint JAR to the cluster artifact repository via the management API. Target any node (preferably the leader).

```bash
LEADER_IP=$(get_leader_ip)  # Resolved from state.env
BLUEPRINT_JAR=$(ls $PROJECT_ROOT/examples/url-shortener/target/url-shortener-*-blueprint.jar | head -1)
VERSION=$(basename $BLUEPRINT_JAR | sed 's/url-shortener-\(.*\)-blueprint.jar/\1/')

curl -X PUT \
  "http://${LEADER_IP}:8080/repository/org/pragmatica/aether/example/url-shortener/${VERSION}/url-shortener-${VERSION}.jar" \
  -H "Content-Type: application/octet-stream" \
  --data-binary @"${BLUEPRINT_JAR}" \
  --fail --silent --show-error
```

This matches the artifact upload pattern used in `CloudNode.uploadArtifact()`.

### REQ-DEPLOY-04: Deploy Blueprint

```bash
BLUEPRINT_TOML=$(cat <<EOF
id = "org.pragmatica.aether.example:url-shortener:${VERSION}"

[[slices]]
artifact = "org.pragmatica.aether.example:url-shortener:${VERSION}"
instances = 5
EOF
)

curl -X POST \
  "http://${LEADER_IP}:8080/api/blueprint" \
  -H "Content-Type: application/toml" \
  -d "${BLUEPRINT_TOML}" \
  --fail --silent --show-error
```

This matches the deploy pattern used in `CloudNode.deploy()`.

### REQ-DEPLOY-05: Wait for Slices Active

Poll `/api/slices/status` on any node every 5 seconds for up to 3 minutes. Wait until:
- The artifact appears in the response
- Status is `ACTIVE`

```bash
wait_for_slices_active() {
    local ip=$1 artifact=$2 max_wait=${3:-180}
    local elapsed=0
    while [ $elapsed -lt $max_wait ]; do
        if curl -sf "http://${ip}:8080/api/slices/status" | jq -e ".[] | select(.artifact == \"${artifact}\" and .status == \"ACTIVE\")" > /dev/null 2>&1; then
            return 0
        fi
        sleep 5
        elapsed=$((elapsed + 5))
    done
    return 1
}
```

### REQ-DEPLOY-06: Smoke Test

After slices are active, verify the url-shortener works:

```bash
# Create a short URL
RESPONSE=$(curl -sf -X POST "http://${NODE_1_PUBLIC_IP}:8070/api/v1/urls/" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/e2e-smoke-test"}')

SHORT_CODE=$(echo $RESPONSE | jq -r '.shortCode')

# Resolve it from a different node
RESOLVE=$(curl -sf "http://${NODE_2_PUBLIC_IP}:8070/api/v1/urls/${SHORT_CODE}")
ORIGINAL_URL=$(echo $RESOLVE | jq -r '.originalUrl')

[ "$ORIGINAL_URL" = "https://example.com/e2e-smoke-test" ] || exit 1
```

---

## 7. Phase 3: Benchmark

### REQ-BENCH-01: k6 Script Deployment

Copy k6 scripts to the k6 VM:

```bash
scp -i $SSH_KEY_PATH -r k6/ root@$K6_PUBLIC_IP:/opt/k6/
```

The k6 scripts are from `examples/url-shortener/k6/` with the `env.sh` overridden to point to Aether node private IPs. The existing `load-test.js` supports `FORGE_NODES` environment variable for targeting specific nodes.

Override `env.sh` on the k6 VM:

```bash
cat > /tmp/env-override.sh <<EOF
export FORGE_NODES="http://${NODE_1_IP}:8070,http://${NODE_2_IP}:8070,http://${NODE_3_IP}:8070,http://${NODE_4_IP}:8070,http://${NODE_5_IP}:8070"
EOF
scp -i $SSH_KEY_PATH /tmp/env-override.sh root@$K6_PUBLIC_IP:/opt/k6/env.sh
```

### REQ-BENCH-02: Baseline Runs

Execute three benchmark runs at different rates. Each run uses the `load-test.js` script from the url-shortener example, which implements the warmup + steady-state + analytics pattern.

| Run | Rate | Duration | Warmup | Output File |
|-----|------|----------|--------|-------------|
| 1 | 500 req/s | 2m | 30s | `results/baseline/k6-500.json` |
| 2 | 2000 req/s | 2m | 30s | `results/baseline/k6-2000.json` |
| 3 | 4000 req/s | 2m | 30s | `results/baseline/k6-4000.json` |

Remote execution on k6 VM:

```bash
ssh -i $SSH_KEY_PATH root@$K6_PUBLIC_IP \
  "cd /opt/k6 && source env.sh && FORGE_RATE=500 FORGE_DURATION=2m FORGE_WARMUP=30s \
   k6 run --out json=results-500.json load-test.js 2>&1"
```

After each run, SCP the JSON output back:

```bash
scp -i $SSH_KEY_PATH root@$K6_PUBLIC_IP:/opt/k6/results-500.json results/baseline/k6-500.json
```

### REQ-BENCH-03: Cluster Snapshot Between Runs

Between each benchmark run, capture cluster state:

```bash
for i in 1 2 3 4 5; do
    curl -sf "http://${NODE_IPS[$i]}:8080/api/status" > results/baseline/status-node-${i}.json
    curl -sf "http://${NODE_IPS[$i]}:8080/api/nodes" > results/baseline/nodes-node-${i}.json
    curl -sf "http://${NODE_IPS[$i]}:8080/api/slices/status" > results/baseline/slices-node-${i}.json
done
```

### REQ-BENCH-04: Between-Run Cooldown

Wait 30 seconds between benchmark runs to allow the cluster to stabilize and connection pools to drain.

---

## 8. Phase 4: Resilience

All resilience scenarios run k6 at 2000 req/s continuously on the k6 VM. The scenarios are executed sequentially. k6 output is captured with `--out json=` for post-analysis.

### REQ-RESIL-01: Scenario A -- Graceful Drain

**Objective:** Verify that draining a non-leader node causes zero application errors.

**Procedure:**

1. Start k6 at 2000 req/s on the k6 VM (background, output to `results/resilience/k6-drain.json`)
2. Wait 30 seconds for steady state
3. Identify a non-leader node (e.g., node-3): verify via `/api/status` that node-3 is NOT the leader
4. Initiate drain:
   ```bash
   curl -X POST "http://${NODE_3_PUBLIC_IP}:8080/api/node/drain" --fail
   ```
   [TBD] Exact drain API endpoint. If drain is only available via CLI: `ssh root@$NODE_3 "aether node drain"`. If via management API: confirm endpoint path.
5. Poll node-3's `/api/status` until it reports `DRAINING` or `DRAINED` state
6. Record timestamp of drain start and drain completion
7. Wait additional 30 seconds after drain completes
8. Stop k6
9. Parse k6 JSON output:
   - Count errors during the drain window (between drain-start and drain-complete timestamps)
   - Record p95/p99 latency during the drain window
   - Record time for slices to migrate off the drained node

**Expected Results:**

| Metric | Threshold |
|--------|-----------|
| Errors during drain | 0 |
| p95 latency spike | < 50ms (relative to baseline) |
| Time to rebalance | < 30s |

**Cleanup:** Rejoin the drained node (restart `aether-node` service) before the next scenario.

### REQ-RESIL-02: Scenario B -- Hard Kill

**Objective:** Verify the cluster recovers from a sudden node death within 15 seconds.

**Procedure:**

1. Start k6 at 2000 req/s on the k6 VM (background, output to `results/resilience/k6-kill.json`)
2. Wait 30 seconds for steady state
3. Record timestamp `T_kill`
4. Power off node-4: `hcloud server poweroff $NODE_4_SERVER_ID`
5. Monitor remaining nodes:
   - Poll `/api/nodes` on node-1 every 2 seconds
   - Wait for node-4 to disappear from the member list
   - Record timestamp `T_detected` when node-4 is no longer listed
6. Wait for slices to rebalance:
   - Poll `/api/slices/status` on node-1
   - Wait for all slices to show `ACTIVE` on 4 remaining nodes
   - Record timestamp `T_recovered`
7. Continue k6 for 30 seconds after recovery
8. Stop k6
9. Parse k6 JSON output:
   - Count errors during `[T_kill, T_recovered]`
   - Measure error spike duration: time window where error rate > 0
   - Record `T_detected - T_kill` (detection time) and `T_recovered - T_kill` (total recovery time)

**Expected Results:**

| Metric | Threshold |
|--------|-----------|
| Total recovery time (`T_recovered - T_kill`) | < 15s |
| Error spike duration | < 5s |
| Throughput after recovery | >= 90% of baseline |

**Cleanup:** Leave node-4 powered off for Scenario C.

### REQ-RESIL-03: Scenario C -- Auto-Heal

**Objective:** Verify the CDM auto-provisions a replacement node when the cluster drops below target size.

**Procedure:**

1. Cluster is at 4/5 nodes (node-4 still down from Scenario B)
2. Start k6 at 2000 req/s on the k6 VM (background, output to `results/resilience/k6-autoheal.json`)
3. Record timestamp `T_start`
4. Monitor for new node:
   - Poll `hcloud server list --selector aether-cluster=e2e-test,aether-port=9100 -o noheader` every 10 seconds
   - Wait for a NEW server ID to appear (one not in our original 5)
   - Record timestamp `T_provisioned`
5. Wait for the new node to join the cluster:
   - Poll `/api/nodes` on node-1
   - Wait for 5 nodes to appear again
   - Record timestamp `T_joined`
6. Wait for slices to rebalance to the new node:
   - Poll `/api/slices/status` on node-1
   - Wait for slices on the new node to reach `ACTIVE`
   - Record timestamp `T_active`
7. Continue k6 for 30 seconds after `T_active`
8. Stop k6

**Expected Results:**

| Metric | Threshold |
|--------|-----------|
| Time to new node provisioned (`T_provisioned - T_start`) | < 60s |
| Time to new node active (`T_active - T_start`) | < 90s |
| Errors during healing | Minimal (only from Scenario B leftovers) |

[ASSUMPTION] The CDM's `ComputeProvider.provision()` call creates the Hetzner VM with the same cloud-init and labels. The `HetznerComputeProvider` uses `config.userData()` for cloud-init and the `clusterName` config for label-based discovery. The new node will:
1. Be provisioned by CDM via `HetznerComputeProvider.provision()`
2. Receive the `aether-cluster=e2e-test` label via `ComputeProvider.applyTags()`
3. Be discovered by other nodes via `HetznerDiscoveryProvider.discoverPeers()` (label-based polling)

[TBD] The auto-provisioned node needs `aether-node.jar` and `aether.toml`. These must be embedded in the cloud-init user data or downloaded from a known location. If the CDM passes these via `userData`, the cloud-init script handles installation. Verify that `HetznerEnvironmentConfig.userData()` contains the full bootstrap script.

### REQ-RESIL-04: Scenario D -- Leader Kill

**Objective:** Verify that killing the current leader triggers re-election within 5 seconds and the cluster continues serving requests.

**Procedure:**

1. Identify current leader:
   ```bash
   LEADER=$(curl -sf "http://${NODE_1_PUBLIC_IP}:8080/api/status" | jq -r '.leader')
   ```
   Map leader ID to server ID via `state.env`.
2. Start k6 at 2000 req/s (background, output to `results/resilience/k6-leader-kill.json`)
3. Wait 30 seconds for steady state
4. Record timestamp `T_kill`
5. Power off the leader: `hcloud server poweroff $LEADER_SERVER_ID`
6. Monitor for new leader:
   - Poll `/api/status` on a surviving node every 1 second
   - Wait for a new leader value (different from killed leader, non-null)
   - Record timestamp `T_elected`
7. Verify cluster health:
   - All surviving nodes report `ready:true`
   - All surviving nodes agree on new leader
8. Continue k6 for 60 seconds after `T_elected`
9. Stop k6

**Expected Results:**

| Metric | Threshold |
|--------|-----------|
| Re-election time (`T_elected - T_kill`) | < 5s |
| Error spike duration | < 10s |
| Consensus recovery | < 5s (Rabia is leaderless for consensus; leader is for CDM only) |

[NOTE] Aether uses Rabia consensus (leaderless). The "leader" is the CDM leader, not a consensus leader. Consensus should continue uninterrupted. The impact of leader kill is:
- CDM operations pause until re-election
- SWIM detects the dead node
- Remaining nodes re-elect a CDM leader
- The new CDM leader resumes auto-heal and deployment management

---

## 9. Phase 5: Report

### REQ-REPORT-01: Log Collection

Collect logs from all surviving Aether nodes:

```bash
for i in 1 2 3 4 5; do
    IP_VAR="NODE_${i}_PUBLIC_IP"
    IP=${!IP_VAR}
    if ssh -i $SSH_KEY_PATH -o ConnectTimeout=5 root@$IP "true" 2>/dev/null; then
        ssh -i $SSH_KEY_PATH root@$IP "journalctl -u aether-node --no-pager" \
            > results/logs/node-${i}.log
    fi
done
```

### REQ-REPORT-02: k6 Result Parsing

For each k6 JSON output, extract key metrics using jq:

```bash
parse_k6_results() {
    local json_file=$1
    jq '{
        http_reqs: [.[] | select(.type == "Point" and .metric == "http_reqs")] | length,
        errors: [.[] | select(.type == "Point" and .metric == "errors" and .data.value == 1)] | length,
        p95_latency: [.[] | select(.type == "Point" and .metric == "http_req_duration")] | sort_by(.data.value) | .[length * 95 / 100].data.value,
        p99_latency: [.[] | select(.type == "Point" and .metric == "http_req_duration")] | sort_by(.data.value) | .[length * 99 / 100].data.value
    }' "$json_file"
}
```

[NOTE] k6's JSON output format uses newline-delimited JSON (NDJSON), not a single JSON array. The actual parsing requires `jq --slurp` or line-by-line processing. The k6 `--summary-export` flag produces a cleaner summary JSON. Consider using `--summary-export` instead:

```bash
k6 run --out json=details.json --summary-export=summary.json load-test.js
```

### REQ-REPORT-03: Summary Generation

Generate `results/summary.json`:

```json
{
    "test_run": "2026-03-19T14:32:00Z",
    "cluster_size": 5,
    "region": "nbg1",
    "baseline": {
        "500_rps": {
            "p95_latency_ms": 4.2,
            "p99_latency_ms": 8.1,
            "error_rate": 0.0,
            "total_requests": 75000
        },
        "2000_rps": {
            "p95_latency_ms": 6.8,
            "p99_latency_ms": 12.3,
            "error_rate": 0.0,
            "total_requests": 300000
        },
        "4000_rps": {
            "p95_latency_ms": 9.1,
            "p99_latency_ms": 18.7,
            "error_rate": 0.001,
            "total_requests": 600000
        }
    },
    "resilience": {
        "graceful_drain": {
            "errors_during_drain": 0,
            "rebalance_time_s": 12.3,
            "latency_spike_ms": 15.2
        },
        "hard_kill": {
            "recovery_time_s": 8.7,
            "error_spike_duration_s": 3.2,
            "errors_total": 47
        },
        "auto_heal": {
            "provision_time_s": 45.0,
            "join_time_s": 62.0,
            "active_time_s": 78.0
        },
        "leader_kill": {
            "reelection_time_s": 2.1,
            "error_spike_duration_s": 4.5
        }
    },
    "pass": true,
    "failures": []
}
```

### REQ-REPORT-04: Human-Readable Summary

Generate `results/summary.txt`:

```
=== Hetzner E2E Test Results ===
Date: 2026-03-19T14:32:00Z
Cluster: 5 nodes, CX32, nbg1

--- Baseline Performance ---
  500 req/s: p95=4.2ms p99=8.1ms errors=0%     PASS
 2000 req/s: p95=6.8ms p99=12.3ms errors=0%    PASS
 4000 req/s: p95=9.1ms p99=18.7ms errors=0.1%  PASS

--- Resilience ---
 Graceful drain:  0 errors, rebalance=12.3s     PASS
 Hard kill:       recovery=8.7s, spike=3.2s      PASS
 Auto-heal:       new node active=78.0s          PASS
 Leader kill:     re-election=2.1s               PASS

Overall: PASS
```

### REQ-REPORT-05: Pass/Fail Determination

The report phase evaluates all metrics against the success criteria in [Section 12](#12-success-criteria). If ANY criterion fails, `pass` is `false` and the failing metrics are listed in `failures[]`. The exit code of `report.sh` is 0 on pass, 1 on fail.

---

## 10. Phase 6: Teardown

### REQ-TEAR-01: Server Deletion

Delete all VMs by label:

```bash
hcloud server list --selector aether-cluster=e2e-test -o noheader -o columns=id | while read id; do
    hcloud server delete "$id"
done
```

This catches:
- Original 5 Aether nodes
- PostgreSQL VM
- k6 VM
- Any auto-healed nodes provisioned during the test

### REQ-TEAR-02: Network Deletion

```bash
hcloud network delete aether-e2e-net
```

Network deletion may fail if servers are still attached. Retry after a 10-second delay.

### REQ-TEAR-03: Firewall Deletion

```bash
hcloud firewall delete aether-e2e-fw
```

### REQ-TEAR-04: SSH Key Deletion

```bash
hcloud ssh-key delete aether-e2e-key
rm -f /tmp/aether-e2e-key /tmp/aether-e2e-key.pub
```

### REQ-TEAR-05: State File Cleanup

```bash
rm -f state.env
```

### REQ-TEAR-06: Verification

After teardown, verify no resources remain:

```bash
REMAINING=$(hcloud server list --selector aether-cluster=e2e-test -o noheader | wc -l)
if [ "$REMAINING" -gt 0 ]; then
    echo "WARNING: $REMAINING servers still exist with label aether-cluster=e2e-test"
    exit 1
fi
```

---

## 11. Configuration Templates

### 11.1 cloud-init-node.yaml

```yaml
#cloud-config
package_update: true
packages:
  - wget
  - curl
  - jq

runcmd:
  - mkdir -p /opt/java /opt/aether
  - cd /opt/java
  - wget -q "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse" -O jdk.tar.gz
  - tar xzf jdk.tar.gz --strip-components=1
  - ln -sf /opt/java/bin/java /usr/local/bin/java
  - rm -f jdk.tar.gz
```

This matches the cloud-init pattern used in `HetznerCloudCluster.CLOUD_INIT_SCRIPT`.

After cloud-init finishes, the deploy phase copies `aether-node.jar`, `aether.toml`, and the systemd service file.

### 11.2 cloud-init-pg.yaml

```yaml
#cloud-config
package_update: true
packages:
  - docker.io
  - curl

runcmd:
  - systemctl enable docker
  - systemctl start docker
  - docker pull postgres:16-alpine
  - |
    docker run -d --name postgres \
      --restart unless-stopped \
      -e POSTGRES_DB=aether \
      -e POSTGRES_USER=aether \
      -e POSTGRES_PASSWORD=aether-e2e-test \
      -p 5432:5432 \
      -v pgdata:/var/lib/postgresql/data \
      postgres:16-alpine
  - sleep 10
  - |
    docker exec -i postgres psql -U aether -d aether <<'SQL'
    CREATE TABLE IF NOT EXISTS urls (
        id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        short_code VARCHAR(10) UNIQUE NOT NULL,
        original_url VARCHAR(2048) NOT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );
    CREATE INDEX IF NOT EXISTS idx_urls_short_code ON urls(short_code);
    CREATE INDEX IF NOT EXISTS idx_urls_original_url ON urls(original_url);
    CREATE TABLE IF NOT EXISTS clicks (
        id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        short_code VARCHAR(10) NOT NULL,
        clicked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        FOREIGN KEY (short_code) REFERENCES urls(short_code)
    );
    CREATE INDEX IF NOT EXISTS idx_clicks_short_code ON clicks(short_code);
    SQL
```

[NOTE] The password `aether-e2e-test` is used as the default for `AETHER_DB_PASSWORD`. If a custom password is set, the cloud-init must be templated with envsubst before passing to `hcloud`.

### 11.3 cloud-init-k6.yaml

```yaml
#cloud-config
package_update: true

runcmd:
  - apt-get install -y gpg apt-transport-https
  - mkdir -p /etc/apt/keyrings
  - curl -fsSL https://dl.k6.io/key.gpg | gpg --dearmor -o /etc/apt/keyrings/k6.gpg
  - echo "deb [signed-by=/etc/apt/keyrings/k6.gpg] https://dl.k6.io/deb stable main" > /etc/apt/sources.list.d/k6.list
  - apt-get update -qq
  - apt-get install -y k6 jq
  - mkdir -p /opt/k6
```

### 11.4 aether.toml.template

```toml
[cluster]
management_port = 8080
app_http_port = 8070

[cluster.topology]
core_max = 5

[database]
type = "POSTGRESQL"
async_url = "postgresql://${PG_PRIVATE_IP}:5432/aether"
username = "aether"
password = "${AETHER_DB_PASSWORD}"

[database.pool_config]
min_connections = 5
max_connections = 30

[environment]
provider = "hetzner"

[environment.hetzner]
api_token = "${HETZNER_TOKEN}"
cluster_name = "e2e-test"
server_type = "cx32"
image = "ubuntu-24.04"
region = "nbg1"
```

Substitution: `envsubst < config/aether.toml.template > /tmp/aether.toml` with the appropriate environment variables.

### 11.5 aether-node.service

```ini
[Unit]
Description=Aether Node
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/opt/java/bin/java -Xmx512m -XX:+UseZGC -jar /opt/aether/aether-node.jar
WorkingDirectory=/opt/aether
Restart=on-failure
RestartSec=5
LimitNOFILE=65535
Environment="JAVA_HOME=/opt/java"

[Install]
WantedBy=multi-user.target
```

[NOTE] CX32 has 8 GB RAM. `-Xmx512m` is conservative; can increase to `-Xmx2g` if needed under heavy load.

---

## 12. Success Criteria

### 12.1 Baseline Performance

| Scenario | Metric | Threshold | Rationale |
|----------|--------|-----------|-----------|
| 500 req/s | p95 latency | < 10ms | Baseline expectation for low load |
| 500 req/s | error rate | 0% | No errors under light load |
| 2000 req/s | p95 latency | < 10ms | Primary benchmark target |
| 2000 req/s | error rate | 0% | No errors under moderate load |
| 4000 req/s | p95 latency | < 20ms | Acceptable degradation under high load |
| 4000 req/s | error rate | < 0.1% | Minimal errors under high load |

### 12.2 Resilience

| Scenario | Metric | Threshold | Rationale |
|----------|--------|-----------|-----------|
| Graceful drain | errors during drain | 0 | Graceful drain must be lossless |
| Graceful drain | rebalance time | < 30s | Slices must migrate promptly |
| Hard kill | total recovery time | < 15s | SWIM detection + rebalance |
| Hard kill | error spike duration | < 5s | Errors must stop quickly |
| Auto-heal | new node active | < 90s | Includes VM provision + boot + join |
| Leader kill | re-election time | < 5s | CDM leader re-election is fast |
| Leader kill | error spike duration | < 10s | Consensus is leaderless (Rabia), errors are from connection loss |

### 12.3 Infrastructure

| Check | Threshold |
|-------|-----------|
| All 5 nodes form quorum | < 3 minutes after start |
| Leader elected | < 3 minutes after quorum |
| All nodes see each other | All 5 visible to each |
| Blueprint deploys | < 3 minutes |
| Teardown leaves no resources | 0 servers, 0 networks, 0 firewalls with `e2e-test` label |

---

## 13. Error Handling and Safety

### 13.1 Resource Leak Prevention

Every `hcloud` resource MUST be labeled with `aether-cluster=e2e-test`. This enables:
- Teardown by label selector (catches auto-healed nodes too)
- Manual cleanup if the script crashes: `hcloud server list --selector aether-cluster=e2e-test`
- Cost monitoring: filter Hetzner billing by label

### 13.2 Idempotency Rules

| Resource | Create Behavior | Delete Behavior |
|----------|----------------|-----------------|
| SSH key | Skip if name exists | Delete if exists, skip if missing |
| Network | Skip if name exists | Delete if exists, skip if missing |
| Firewall | Skip if name exists | Delete if exists, skip if missing |
| Server | Skip if name AND label match | Delete by label selector |
| state.env | Overwrite | Delete |

### 13.3 Failure Modes

| Failure | Behavior |
|---------|----------|
| `HETZNER_TOKEN` missing | Exit 1 immediately with clear error message |
| Server creation fails | Abort setup, run teardown to clean partial state |
| Cloud-init times out | Log warning, continue (Java may install slowly) |
| Health check times out | Abort deploy phase, suggest checking logs |
| k6 fails | Save partial results, continue to report phase |
| Teardown fails | Log warning, list remaining resources for manual cleanup |
| Network quota exceeded | Abort with message listing current resources |

### 13.4 Trap Handler

`run.sh` MUST install a trap for SIGINT/SIGTERM:

```bash
trap 'echo "[ABORT] Interrupted. Run ./run.sh teardown to clean up."; exit 130' INT TERM
```

The `all` phase does NOT auto-teardown on failure. This allows the operator to inspect the cluster, collect logs, or debug. The operator must explicitly run `./run.sh teardown`.

---

## 14. Relationship to Existing Cloud Tests

### 14.1 Existing Java Cloud Tests

The project already has Java-based cloud integration tests in `aether/cloud-tests/`:

| Test Class | Purpose | Cluster Size |
|-----------|---------|-------------|
| `ClusterFormationCloudIT` | Quorum, leader election, node visibility | 5 nodes |
| `SliceDeploymentCloudIT` | Artifact upload, deploy, invoke, scale, undeploy | 3 nodes |

These tests use `HetznerCloudCluster` which provisions servers programmatically via `HetznerClient`. They use public IPs and a peer list (not private networking or label-based discovery).

### 14.2 How This Spec Differs

| Aspect | Existing Java tests | This E2E spec |
|--------|-------------------|---------------|
| Infrastructure | Public IPs, no private network | Private network + firewall |
| Discovery | Static peer list (`--peers=`) | Label-based via `HetznerDiscoveryProvider` |
| Application | Echo slice (trivial) | url-shortener (database-backed, realistic) |
| Database | None | PostgreSQL 16 |
| Load testing | None | k6 at 500-4000 req/s |
| Resilience | None | Drain, kill, auto-heal, leader kill |
| Automation | Maven/JUnit (requires Java, Maven) | Shell scripts (requires only `hcloud`, `ssh`, `jq`) |
| Output | JUnit XML | k6 JSON + summary JSON/TXT |

### 14.3 Complementary, Not Replacement

This E2E test complements the existing Java cloud tests:
- Java tests validate the programmatic API and basic cluster operations
- This E2E test validates the full operational workflow: infrastructure provisioning, blueprint deployment, performance, and resilience under load

---

## 15. Open Questions

| ID | Question | Impact | Default Assumption |
|----|----------|--------|--------------------|
| TBD-1 | What is the exact Management API endpoint for node drain? | REQ-RESIL-01 | `POST /api/node/drain` |
| TBD-2 | Does the CDM auto-heal include the full cloud-init bootstrap (Java + JAR + config)? | REQ-RESIL-03 | Yes, via `HetznerEnvironmentConfig.userData()` |
| TBD-3 | How does the auto-provisioned node get `aether.toml`? | REQ-RESIL-03 | Embedded in cloud-init or downloaded from a bootstrap URL |
| TBD-4 | Should k6 target private IPs (within network) or public IPs? | REQ-BENCH-* | Private IPs (k6 VM is on the same network) |
| TBD-5 | Should this test run in CI or only manually? | REQ-ORCH-01 | Manually for now; CI integration is future work |
| TBD-6 | Should the PostgreSQL password be injected via Hetzner's user data substitution or SCP'd after boot? | REQ-SETUP-05 | SCP a `.env` file after boot for security |
| TBD-7 | How does the aether-node discover its own server ID for `selfServerId` in `HetznerEnvironmentConfig`? | REQ-SETUP-06 | Via Hetzner metadata service: `curl http://169.254.169.254/hetzner/v1/metadata/instance-id` |

---

## 16. References

### Internal References

| Reference | Path | Relevance |
|-----------|------|-----------|
| Hetzner Cloud Client | `integrations/cloud/hetzner/src/main/java/org/pragmatica/cloud/hetzner/HetznerClient.java` | Low-level Hetzner API client |
| Hetzner Environment Config | `aether/environment/hetzner/src/main/java/org/pragmatica/aether/environment/hetzner/HetznerEnvironmentConfig.java` | Node configuration model for Hetzner |
| Hetzner Compute Provider | `aether/environment/hetzner/src/main/java/org/pragmatica/aether/environment/hetzner/HetznerComputeProvider.java` | VM provisioning SPI implementation (used by CDM for auto-heal) |
| Hetzner Discovery Provider | `aether/environment/hetzner/src/main/java/org/pragmatica/aether/environment/hetzner/HetznerDiscoveryProvider.java` | Label-based peer discovery (polling `aether-cluster` label) |
| Existing Cloud Cluster Test Harness | `aether/cloud-tests/src/test/java/org/pragmatica/aether/cloud/HetznerCloudCluster.java` | Java-based cloud test infrastructure |
| Cluster Formation Cloud IT | `aether/cloud-tests/src/test/java/org/pragmatica/aether/cloud/ClusterFormationCloudIT.java` | Existing 5-node formation test |
| Slice Deployment Cloud IT | `aether/cloud-tests/src/test/java/org/pragmatica/aether/cloud/SliceDeploymentCloudIT.java` | Existing deploy/scale/undeploy test |
| Cloud Node (SSH/HTTP helpers) | `aether/cloud-tests/src/test/java/org/pragmatica/aether/cloud/CloudNode.java` | SSH, SCP, HTTP helper patterns |
| k6 Load Test Script | `examples/url-shortener/k6/load-test.js` | Primary benchmark script (warmup + steady-state) |
| k6 Spike Test Script | `examples/url-shortener/k6/spike.js` | Spike/burst test pattern |
| k6 Env Script | `examples/url-shortener/k6/env.sh` | Node URL configuration for k6 |
| URL Shortener Schema | `examples/url-shortener/schema/url_shortener_pg/V001__create_tables.sql` | PostgreSQL schema for url-shortener |
| URL Shortener Config | `examples/url-shortener/aether.toml` | Reference config (local dev) |
| Cloud Integration SPI Spec | `aether/docs/specs/cloud-integration-spi-spec.md` | SPI architecture for cloud providers |
| E2E Test Suite | `aether/e2e-tests/src/test/java/org/pragmatica/aether/e2e/` | Local Docker-based E2E tests (Testcontainers) |

### External References

| Reference | URL | Relevance |
|-----------|-----|-----------|
| Hetzner Cloud API | `https://docs.hetzner.cloud/` | Server, network, firewall API |
| hcloud CLI | `https://github.com/hetznercloud/cli` | CLI used by all setup/teardown scripts |
| Hetzner Cloud-Init | `https://docs.hetzner.com/cloud/servers/getting-started/server-config-cloud-init/` | Cloud-init support details |
| Hetzner Metadata Service | `https://docs.hetzner.cloud/#server-metadata` | Instance self-identification (`instance-id`) |
| k6 Documentation | `https://grafana.com/docs/k6/latest/` | Load testing tool |
| k6 JSON Output | `https://grafana.com/docs/k6/latest/results-output/real-time/json/` | NDJSON output format |
| Eclipse Temurin | `https://adoptium.net/temurin/releases/` | Java 25 distribution |
| PostgreSQL 16 Docker | `https://hub.docker.com/_/postgres` | Database image |
