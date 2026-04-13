# Integration Test Overhaul v2 -- Implementation Spec

| Field   | Value                                    |
|---------|------------------------------------------|
| Status  | Approved -- ready for implementation     |
| Date    | 2026-04-13                               |
| Module  | `aether/tests/integration/`              |
| Related | cluster-bootstrap-spec.md                |

---

## 1. Overview

Replace the current ad-hoc integration test infrastructure (manual deploy scripts, `CLOUD_MODE` conditionals, sequential-only execution, Python JSON parsing) with a single-entry-point test runner that provisions a dual-cluster topology via the bootstrap CLI, runs 15 standard suites across non-destructive and destructive clusters (16th suite, 01-stability, is opt-in only), and produces a JSON report. Three environment modes (docker, remote, cloud) share identical test scripts -- all environment-specific logic lives in TOML templates and the bootstrap CLI.

---

## 2. Architecture

```
run-tests.sh --env docker|remote|cloud [--suites X,Y] [--skip-build] [--skip-deploy] [--skip-teardown]
    |
    +-- build.sh (Step 5: test blueprints)
    |
    +-- aether cluster bootstrap env/docker.toml    --> Cluster A (5 nodes, non-destructive)
    +-- aether cluster bootstrap env/docker-b.toml  --> Cluster B (5 nodes, destructive)
    |
    +-- aether artifact push (test-echo, test-persistence, test-full)
    +-- aether blueprint deploy (per cluster)
    |
    +-- wait_for_lb_ready (both clusters)
    |
    +-- [Cluster A] parallel runner (max 4 concurrent):
    |     00-smoke (gate) -> 04,06,07,08,09,10,11,14,15
    |
    +-- [Cluster B] sequential runner (self-heal between each):
    |     02-chaos -> 03-scaling -> 05-security -> 12-network -> 13-edge-cases
    |
    +-- collect results -> test-results.json
    +-- aether cluster destroy (both clusters, unless --skip-teardown)
```

---

## 3. Entry Point: `run-tests.sh`

**Location:** `aether/tests/integration/run-tests.sh`

```bash
#!/bin/bash
# Usage: ./run-tests.sh --env docker [--suites 00,02,04] [--skip-build] [--skip-deploy] [--skip-teardown]
```

### Flags

| Flag              | Required | Default      | Description                                    |
|-------------------|----------|--------------|------------------------------------------------|
| `--env`           | yes      | --           | `docker`, `remote`, or `cloud`                 |
| `--suites`        | no       | all          | Comma-separated suite prefixes (e.g., `00,02`) |
| `--skip-build`    | no       | false        | Skip `build.sh` and blueprint builds           |
| `--skip-deploy`   | no       | false        | Skip cluster bootstrap (reuse running cluster) |
| `--skip-teardown` | no       | false        | Leave clusters running after tests             |

### Environment Variables

| Var              | docker         | remote           | cloud            |
|------------------|----------------|------------------|------------------|
| `TARGET_HOST`    | defaults `localhost` | **required** | ignored (VMs)    |
| `AETHER_SSH_KEY` | not needed     | **required**     | not needed       |
| `HCLOUD_TOKEN`   | not needed     | not needed       | **required**     |
| `AETHER_API_KEY` | defaults test key | defaults test key | defaults test key |

### Execution Flow

1. Validate flags and env vars
2. If `!skip-build`: run `build.sh` (includes Step 5: test blueprints)
3. If `!skip-deploy`:
   a. Resolve TOML template: `env/${env}.toml` -> expand `${env:...}` placeholders
   b. `aether cluster bootstrap env/${env}.toml --cluster cluster-a --yes --wait --timeout 300`
   c. `aether cluster bootstrap env/${env}-b.toml --cluster cluster-b --yes --wait --timeout 300`
   d. Push test artifacts: `aether artifact push` for each blueprint JAR
   e. Deploy blueprints to each cluster per suite requirements
4. Wait for LB ready on both clusters (poll `/api/health` via cluster status endpoint)
5. Detect capabilities (Section 8)
6. Run suites:
   a. **Gate:** Run 00-smoke on Cluster A. If it fails, abort all.
   b. **Cluster A suites:** Launch up to 4 concurrent (background processes, `wait -n` coordination)
   c. **Cluster B suites:** Run sequentially with self-heal (Section 9) between each
7. Collect results into `test-results.json`
8. If `!skip-teardown`: `aether cluster destroy` both clusters
9. Exit with 0 if all suites passed, 1 otherwise

---

## 4. Environment Templates

**Location:** `aether/tests/integration/env/`

### 4.1 `docker.toml` -- Local Docker (Cluster A)

```toml
config_version = "1.0.0"

[cluster]
name = "test-a"
version = "1.0.0-rc1"

[core_topology]
min = 3
max = 15
max_unavailable = 1

[source.docker]
type = "docker"
load_balancer = "elected"

[source.docker.core]
count = 5

[operations.ports]
cluster = 6000
management = 8080
app_http = 8070
swim = 6100

[operations.auto_heal]
enabled = true
retry_interval = "30s"
startup_cooldown = "15s"

[runtime.default]
type = "docker"
image = "aether-node:local"

[databases.forge]
url = "jdbc:postgresql://forge-postgres:5432/forge"
username = "forge"
password = "forge"
```

### 4.2 `docker-b.toml` -- Local Docker (Cluster B)

Same as `docker.toml` but:
- `name = "test-b"`
- Port range offset: management starts at 5160 (vs 5150), app-http at 8080 (vs 8070)
- Container name prefix: `aether-b-node-*`
- Network: `aether-b-network`
- Shares the same PostgreSQL container (same `[databases.forge]`)

### 4.3 `remote.toml` / `remote-b.toml`

Same as docker variants but with:
```toml
[deployment]
type = "ssh"
target_host = "${env:TARGET_HOST}"
ssh_key = "${env:AETHER_SSH_KEY}"
```

### 4.4 `cloud-hetzner.toml` / `cloud-hetzner-b.toml`

```toml
config_version = "1.0.0"

[cluster]
name = "cloud-test-a"
version = "1.0.0-rc1"

[core_topology]
min = 3
max = 9
max_unavailable = 1

[source.hetzner-eu]
type = "cloud"
provider = "hetzner"
zone = "fsn1-dc14"
region = "eu-central"
credentials = "${env:HCLOUD_TOKEN}"
load_balancer = "none"

[source.hetzner-eu.core]
count = 5
instance_type = "cx22"

[operations.ports]
cluster = 6000
management = 8080
app_http = 8070
swim = 6100

[operations.tls]
auto_generate = true

[runtime.default]
type = "container"
image = "ghcr.io/pragmaticalabs/aether-node:1.0.0-rc1"

[databases.forge]
url = "${env:PG_URL}"
username = "forge"
password = "${env:PG_PASSWORD}"
```

---

## 5. Dual-Cluster Orchestration

### Cluster Assignment

| Cluster | Mode             | Execution    | Suites                                                        |
|---------|------------------|--------------|---------------------------------------------------------------|
| A       | non-destructive  | parallel (4) | 00, 04, 06, 07, 08, 09, 10, 11, 14, 15                      |
| B       | destructive      | sequential   | 02, 03, 05, 12, 13                                           |

### Docker Compose Generation

For `docker` and `remote` envs, `run-tests.sh` generates two compose files from the TOML templates:
- `docker-compose-a.yml` -- Cluster A: nodes `aether-a-node-{1..5}`, ports 5150-5154 (mgmt), 8070-8074 (app), 9090/9091 (LB), network `aether-a-network`
- `docker-compose-b.yml` -- Cluster B: nodes `aether-b-node-{1..5}`, ports 5160-5164 (mgmt), 8080-8084 (app), 9092/9093 (LB), network `aether-b-network`
- Shared: single PostgreSQL container on `aether-a-network` with `aether-b-network` attached

[ASSUMPTION] Both clusters fit on a single 32GB host. Each node uses 512MB heap, so 10 nodes = 5GB + OS overhead.

### Parallel Execution (Cluster A)

```bash
# Pseudocode
run_suite "00-smoke" cluster_a || exit 1   # Gate -- must pass

pending_suites=(04 06 07 08 09 10 11 14 15)
active_pids=()
MAX_PARALLEL=4

for suite in "${pending_suites[@]}"; do
    while [ ${#active_pids[@]} -ge $MAX_PARALLEL ]; do
        wait -n -p done_pid "${active_pids[@]}"
        # remove done_pid from active_pids, record result
    done
    run_suite "$suite" cluster_a &
    active_pids+=($!)
done
wait  # remaining
```

### Sequential Execution (Cluster B)

```bash
for suite in 02 03 05 12 13; do
    run_suite "$suite" cluster_b || record_failure "$suite"
    self_heal cluster_b  # Section 9
done
```

---

## 6. Test Blueprints

**Location:** `aether/tests/blueprints/{test-echo,test-persistence,test-full}/`

Each is a standalone Maven project producing a deployable JAR.

### 6.1 `test-echo`

Stateless echo slice. Receives HTTP request, returns it back. No database, no streams.

**Used by:** 00-smoke, 02-chaos, 03-scaling, 05-security, 12-network, 13-edge-cases

```
test-echo/
  pom.xml          (parent: pragmatica root, deps: aether-slice-api)
  src/main/java/
    org/pragmatica/aether/test/echo/
      EchoSlice.java        (@Slice, @HttpRoute GET/POST /echo)
      EchoConfig.java       (minimal config section)
  blueprint.toml            (slice manifest)
```

### 6.2 `test-persistence`

PgSql-backed slice with streaming. Stores key-value pairs, publishes change events to a stream.

**Used by:** 06-deployment, 08-resources, 10-database, 11-observability, 14-storage

```
test-persistence/
  pom.xml          (deps: aether-slice-api, aether-storage, pg-tools)
  src/main/java/
    org/pragmatica/aether/test/persistence/
      PersistenceSlice.java   (@Slice, @PgSql, @Stream)
      PersistenceConfig.java
      KeyValueStore.java      (typed PgSql table)
  src/main/resources/
    db/migration/V1__create_kv.sql
  blueprint.toml
```

### 6.3 `test-full`

Multi-slice blueprint with HTTP client, delegation tasks, and artifact metadata.

**Used by:** 07-cluster-mgmt, 09-artifacts, 15-delegation

```
test-full/
  pom.xml          (deps: aether-slice-api, aether-http-client)
  src/main/java/
    org/pragmatica/aether/test/full/
      FullSlice.java          (@Slice, multiple @HttpRoute)
      DelegationTask.java     (@TaskGroup)
      ArtifactMetaSlice.java  (@Slice, metadata endpoint)
  blueprint.toml
```

### 6.4 build.sh Step 5

Add to `build.sh`:

```bash
# Step 5/5: Build test blueprints
echo ""
echo "Step 5/5: Build test blueprints..."
for bp in aether/tests/blueprints/test-echo aether/tests/blueprints/test-persistence aether/tests/blueprints/test-full; do
    mvn_quiet -f "$bp/pom.xml" install -DskipTests
done
```

---

## 7. Suite Metadata

**Location:** Each suite directory gets a `suite.conf` file.

### Format

```bash
# suite.conf -- parsed by run-tests.sh (source'd as shell vars)
tags=smoke
cluster=non-destructive
destructive=false
requires=
blueprint=test-echo
estimated_duration=30s
description=Cluster formation and basic deployment
```

### Field Definitions

| Field                | Values                                                      |
|----------------------|-------------------------------------------------------------|
| `tags`               | Comma-separated: `smoke`, `streaming`, `persistence`, etc.  |
| `cluster`            | `non-destructive` or `destructive`                          |
| `destructive`        | `true` or `false`                                           |
| `requires`           | Comma-separated: `CAP_PERSISTENCE`, `CAP_CHAOS`, `CAP_SCALING`, `CAP_NETWORK_PARTITION` |
| `blueprint`          | `test-echo`, `test-persistence`, or `test-full`             |
| `estimated_duration` | Human-readable estimate (informational)                     |
| `description`        | One-line description                                        |

### Complete Suite Map

| Suite            | Cluster         | Destructive | Requires             | Blueprint        |
|------------------|-----------------|-------------|----------------------|------------------|
| 00-smoke         | non-destructive | false       |                      | test-echo        |
| 02-chaos         | destructive     | true        | CAP_CHAOS            | test-echo        |
| 03-scaling       | destructive     | true        | CAP_SCALING          | test-echo        |
| 04-streaming     | non-destructive | false       |                      | test-persistence |
| 05-security      | destructive     | true        |                      | test-echo        |
| 06-deployment    | non-destructive | false       | CAP_PERSISTENCE      | test-persistence |
| 07-cluster-mgmt  | non-destructive | false       |                      | test-full        |
| 08-resources     | non-destructive | false       | CAP_PERSISTENCE      | test-persistence |
| 09-artifacts     | non-destructive | false       |                      | test-full        |
| 10-database      | non-destructive | false       | CAP_PERSISTENCE      | test-persistence |
| 11-observability | non-destructive | false       |                      | test-persistence |
| 12-network       | destructive     | true        | CAP_NETWORK_PARTITION| test-echo        |
| 13-edge-cases    | destructive     | true        |                      | test-echo        |
| 14-storage       | non-destructive | false       | CAP_PERSISTENCE      | test-persistence |
| 15-delegation    | non-destructive | false       |                      | test-full        |

**Note:** Suite 01-stability (soak tests) is excluded from the standard run. It requires `--suites 01` explicitly and runs for 4+ hours.

---

## 8. Capability System

### Detection

Capabilities are detected once at startup, after clusters are provisioned.

```bash
detect_capabilities() {
    local env="$1"
    CAP_CHAOS=true
    CAP_SCALING=true
    CAP_NETWORK_PARTITION=false
    CAP_PERSISTENCE=false

    # Persistence: check if PostgreSQL is reachable from test runner
    if pg_isready -h "${PG_HOST:-localhost}" -p "${PG_PORT:-5432}" -U forge >/dev/null 2>&1; then
        CAP_PERSISTENCE=true
    fi

    # Network partition: only docker/remote (iptables available)
    case "$env" in
        docker|remote) CAP_NETWORK_PARTITION=true ;;
        cloud)         CAP_NETWORK_PARTITION=false ;;
    esac
}
```

### Gating

Before running a suite, `run-tests.sh` checks:

```bash
check_requirements() {
    local suite_dir="$1"
    source "${suite_dir}/suite.conf"
    IFS=',' read -ra reqs <<< "$requires"
    for req in "${reqs[@]}"; do
        [ -z "$req" ] && continue
        local val="${!req:-false}"
        if [ "$val" != "true" ]; then
            echo "SKIP: ${suite_dir} requires ${req}"
            return 1
        fi
    done
    return 0
}
```

### Per-Environment Matrix

| Capability             | docker | remote | cloud |
|------------------------|--------|--------|-------|
| CAP_CHAOS              | yes    | yes    | yes   |
| CAP_SCALING            | yes    | yes    | yes   |
| CAP_PERSISTENCE        | auto   | auto   | auto  |
| CAP_NETWORK_PARTITION  | yes    | yes    | no    |

---

## 9. Self-Heal Between Destructive Suites

After each destructive suite completes on Cluster B:

```
1. Poll: GET /api/cluster/topology on Cluster B
   - Expect: all 5 nodes healthy, correct node count
   - Timeout: 120 seconds, poll every 5s

2. If timeout:
   - Docker/remote: `docker compose -f docker-compose-b.yml restart`
   - Cloud: `aether cluster heal --cluster cluster-b`
   - Wait additional 120s for quorum

3. If second wait fails:
   - Log error with diagnostics (node states, leader status)
   - Abort remaining destructive suites
   - Mark remaining as SKIPPED in results

4. On success:
   - Verify leader elected
   - Verify LB endpoint responsive
   - Continue to next suite
```

### Implementation

```bash
self_heal() {
    local cluster="$1"
    local endpoint="${CLUSTER_B_ENDPOINT}"

    # Step 1: wait for natural recovery
    if wait_for_node_count_on "$endpoint" 5 120; then
        wait_for_leader_on "$endpoint" 30
        return 0
    fi

    # Step 2: force restart
    log_warn "Cluster B did not self-heal, forcing restart"
    case "$ENV" in
        docker|remote)
            docker compose -f docker-compose-b.yml restart
            ;;
        cloud)
            aether cluster heal --cluster cluster-b
            ;;
    esac

    if wait_for_node_count_on "$endpoint" 5 120; then
        wait_for_leader_on "$endpoint" 30
        return 0
    fi

    # Step 3: abort
    log_error "Cluster B unrecoverable -- aborting destructive suites"
    return 1
}
```

---

## 10. LB Discovery

### Current Problem

Tests hardcode `LB_PORT=9090` and `LB_MGMT_PORT=9091`. This breaks across environments.

### Solution

Tests discover the LB endpoint from `aether cluster status`:

```bash
discover_endpoints() {
    local cluster_name="$1"
    local status
    status=$(aether cluster status --cluster "$cluster_name" --format json)

    # Extract from status response
    LB_ENDPOINT=$(echo "$status" | jq -r '.loadBalancer.appEndpoint')
    LB_MGMT_ENDPOINT=$(echo "$status" | jq -r '.loadBalancer.mgmtEndpoint')
    DIRECT_ENDPOINT=$(echo "$status" | jq -r '.nodes[0].mgmtEndpoint')
}
```

### API Change Required

Add to cluster status response:

```json
{
  "loadBalancer": {
    "type": "elected",
    "nodeId": "node-1",
    "appEndpoint": "http://192.168.0.71:9090",
    "mgmtEndpoint": "http://192.168.0.71:9091"
  }
}
```

**Implementation:** `ClusterStatusResponse` gains a `loadBalancer` field. `ManagementServer` populates it from the elected LB node's advertised endpoints.

### Wait Logic

```bash
wait_for_lb_ready() {
    local endpoint="$1" timeout="${2:-120}"
    wait_for "LB ready at ${endpoint}" \
        "curl -sf ${endpoint}/api/health >/dev/null 2>&1" \
        "$timeout"
}
```

---

## 11. DB Integration

### Docker/Remote

PostgreSQL runs as a compose service shared between both clusters:

```yaml
services:
  postgres:
    image: postgres:17-alpine
    container_name: forge-postgres
    hostname: forge-postgres
    networks:
      - aether-a-network
      - aether-b-network
    environment:
      POSTGRES_USER: forge
      POSTGRES_PASSWORD: forge
      POSTGRES_DB: forge
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U forge"]
      interval: 5s
      timeout: 3s
      retries: 10
```

DB URL in cluster TOML:
```toml
[databases.forge]
url = "jdbc:postgresql://forge-postgres:5432/forge"
username = "forge"
password = "forge"
```

### Cloud

Dedicated PostgreSQL VM provisioned during bootstrap:
```toml
[databases.forge]
type = "dedicated-vm"
instance = "cx22"
```

The bootstrap CLI creates a Hetzner VM, installs PostgreSQL, and writes the resolved URL into the cluster's KV-Store via `${databases.forge.url}` deferred resolution.

---

## 12. Implementation Layers

Ordered by dependency. Each layer is a separate GitHub issue.

### Layer 1: Test Blueprints (no dependencies)

- Create `aether/tests/blueprints/test-echo/` Maven project + `EchoSlice`
- Create `aether/tests/blueprints/test-persistence/` Maven project + `PersistenceSlice` + migrations
- Create `aether/tests/blueprints/test-full/` Maven project + `FullSlice` + `DelegationTask`
- Add Step 5 to `build.sh`
- Verify all three build and produce deployable JARs

### Layer 2: Suite Metadata (no dependencies)

- Add `suite.conf` to all 15 suite directories (per Section 7 table)
- Write `parse_suite_conf()` shell function in new `lib/suite.sh`
- Write `check_requirements()` capability gate function

### Layer 3: Environment Templates (depends on bootstrap CLI)

- Create `aether/tests/integration/env/` directory
- Write `docker.toml`, `docker-b.toml` for dual-cluster docker
- Write `remote.toml`, `remote-b.toml` for SSH target
- Write `cloud-hetzner.toml`, `cloud-hetzner-b.toml` for Hetzner
- Generate `docker-compose-a.yml` and `docker-compose-b.yml` from templates (or write static files with distinct port ranges)

### Layer 4: LB Discovery API (depends on ManagementServer)

- Add `loadBalancer` field to `ClusterStatusResponse`
- Populate from elected LB node's advertised endpoints
- Write `discover_endpoints()` in `lib/cluster.sh`
- Write `wait_for_lb_ready()`

### Layer 5: Capability Detection (depends on Layer 3)

- Implement `detect_capabilities()` in `lib/suite.sh`
- Wire into `run-tests.sh` after cluster provisioning

### Layer 6: Self-Heal (depends on Layer 3)

- Implement `self_heal()` function
- Implement `wait_for_node_count_on()` that takes an explicit endpoint (not global)
- Add compose restart logic for docker/remote
- Add `aether cluster heal` for cloud

### Layer 7: Test Runner (`run-tests.sh`) (depends on Layers 1-6)

- Implement `run-tests.sh` with all flags
- Parallel execution for Cluster A (background processes, `wait -n`)
- Sequential execution for Cluster B with self-heal
- JSON result collection
- Teardown logic

### Layer 8: Migration and Cleanup (depends on Layer 7)

- Update `lib/common.sh`: remove `CLOUD_MODE` conditionals, Python JSON parsing
- Replace `python3 -c "import json..."` with `jq` throughout (jq is a standard tool, no Python)
- Remove `scripts/setup.sh`, `scripts/deploy-compose.sh`, `scripts/deploy-cloud.sh`, `scripts/run-all.sh` (superseded by `run-tests.sh`)
- Update `README.md`
- Archive `aether/tests/cloud/` scripts (superseded by `--env cloud`)

---

## 13. Migration

### Transition Strategy

1. **Layers 1-6** can be implemented without breaking existing scripts
2. **Layer 7** (`run-tests.sh`) is additive -- old `run-all.sh` continues to work
3. **Layer 8** removes old scripts only after `run-tests.sh` is validated
4. During transition, both paths work:
   - Old: `TARGET_HOST=x ./scripts/run-all.sh`
   - New: `./run-tests.sh --env remote`

### Breaking Changes

- `CLOUD_MODE` env var removed (replaced by `--env cloud`)
- `SKIP_BOOTSTRAP` env var removed (replaced by `--skip-deploy`)
- Port constants in `common.sh` removed (replaced by LB discovery)
- Python dependency removed (replaced by `jq`)
- Individual deploy scripts removed (replaced by bootstrap CLI)

### Backward Compatibility

`run-tests.sh --env docker --skip-deploy --skip-teardown` replicates the current `SKIP_BOOTSTRAP=true ./scripts/run-all.sh` workflow for iterating on a single suite against a running cluster.

---

## References

### Internal
- `aether/docs/specs/cluster-bootstrap-spec.md` -- Bootstrap CLI spec
- `aether/tests/integration/lib/common.sh` -- Current test library (to be refactored)
- `aether/tests/integration/lib/cluster.sh` -- Current cluster operations
- `aether/tests/cloud/aether-cloud.toml` -- Current cloud config (to be superseded)

### Technical
- [Hetzner Cloud API](https://docs.hetzner.cloud/) -- Cloud VM provisioning
- [jq Manual](https://jqlang.github.io/jq/manual/) -- JSON processing replacement for Python
