# Aether Integration Tests

End-to-end integration tests that exercise a live Aether cluster. `run-tests.sh` provisions a dual-cluster layout (non-destructive cluster A + destructive cluster B), runs selected suites, prints a timing and pass/fail summary, and tears the clusters down.

## Table of Contents
1. [Prerequisites](#prerequisites)
2. [Environment Setup](#environment-setup)
3. [Building](#building)
4. [Running Tests](#running-tests)
5. [Suite Selection](#suite-selection)
6. [Interpreting Results](#interpreting-results)
7. [Troubleshooting](#troubleshooting)
8. [Adding New Tests](#adding-new-tests)

## Prerequisites

**Dev machine:**
- Java 25 + Maven 3.9+
- `bash` 4+, `curl`
- `aether` CLI on `PATH` (preferred — the test harness uses it for management-API calls; a raw curl fallback exists for environments without the CLI)
- SSH key with access to the `--env remote` / `--env cloud` target host

**Target host (for `--env docker` and `--env remote`):**
- Linux x86_64 or arm64 (image is built on the target host, so arch matches automatically)
- Docker 27+ with Compose V2 plugin (`docker compose ...` — no hyphen)
- Colima or native Docker daemon (macOS dev host: `colima` backend with `~/.colima/default/docker.sock`)
- SSH with key-based auth for `--env remote`
- Free ports: `5150-5154` and `5160-5164` (management, cluster A + B), `8070-8074` and `8080-8084` (app HTTP), `9090-9091` (passive LB)
- Disk: ~8 GB free for docker images + logs per full run
- Memory: ~1 GB per node (5 nodes × 2 clusters = 10 containers ≈ 10 GB recommended)

**Target host (for `--env cloud`):**
- Hetzner Cloud account with a project API token (`HCLOUD_TOKEN`)
- Private network provisioned per [`env/cloud-hetzner.toml`](env/cloud-hetzner.toml)

## Environment Setup

| Variable | Required for | Default | Purpose |
|----------|--------------|---------|---------|
| `TARGET_HOST` | `docker` (when not localhost), `remote` | `localhost` | Host running the Docker daemon that will host the clusters |
| `AETHER_SSH_USER` | `remote` | `root` | SSH login user on `TARGET_HOST` |
| `AETHER_SSH_KEY` | `remote` | — | Absolute path to SSH private key |
| `AETHER_API_KEY` | all | `aether-integration-test-key` | X-API-Key value sent to management API |
| `AETHER_INSECURE_DEV_MODE` | all | `true` (in compose files) | Required for non-TLS cluster_secret handshake in test clusters |
| `HCLOUD_TOKEN` | `cloud` | — | Hetzner Cloud project token |
| `MAX_PARALLEL` | optional | `4` | Concurrent cluster-A suites |
| `COLLECT_METRICS` | optional | `false` | Capture `/proc/1/status` + heap diffs per test |

Export these in your shell before running:

```bash
export TARGET_HOST=192.168.0.71
export AETHER_SSH_USER=root
export AETHER_SSH_KEY=~/.ssh/aether_test
```

## Building

### Local (cluster A + B on the same machine)

```bash
./build.sh                           # full repo build + JBCT lint
```

This produces `aether/node/target/aether-node.jar` and `aether/lb/target/aether-lb.jar`, both shaded fat-jars. Docker images `aether-node:local` / `aether-lb:local` are built on-demand by `run-tests.sh`.

### Remote (build locally, push to target host)

```bash
./aether/tests/integration/scripts/build-and-push.sh    # optional helper
```

Or just run `run-tests.sh --env remote` — it calls `rebuild_remote_node_image` internally, SCPs the jar, and rebuilds the image on the target before deploying.

### Cloud (Hetzner)

The cloud path provisions VMs that pull `aether-node:<version>` from the configured registry (see [`env/cloud-hetzner.toml`](env/cloud-hetzner.toml)). Push your build to the registry first:

```bash
mvn -pl aether/node -am deploy -DskipTests
```

## Running Tests

```bash
# All suites, local Docker
./aether/tests/integration/run-tests.sh --env docker

# All suites, remote host
./aether/tests/integration/run-tests.sh --env remote

# All suites, Hetzner Cloud
./aether/tests/integration/run-tests.sh --env cloud

# Skip the build step (rely on already-compiled jars)
./aether/tests/integration/run-tests.sh --env docker --skip-build

# Re-use running clusters between invocations
./aether/tests/integration/run-tests.sh --env docker --skip-deploy

# Keep clusters alive after tests (cloud: also skips destroy)
./aether/tests/integration/run-tests.sh --env cloud --skip-teardown
```

`--help` prints the authoritative flag list.

## Suite Selection

Suites are numbered `00`–`15` and split across two clusters:

| Cluster | Type | Parallelism | Suites |
|---------|------|-------------|--------|
| A | non-destructive | Up to `MAX_PARALLEL` parallel | `00 04 06 07 08 09 10 11 14 15` |
| B | destructive (node kills, drains, scale churn) | Sequential | `02 03 05 12 13` |

Select a subset with comma-separated prefixes:

```bash
./aether/tests/integration/run-tests.sh --env docker --suites 00,02,08
./aether/tests/integration/run-tests.sh --env docker --suites 06-deployment
```

Suite `00-smoke` is treated as a **gate** — if it fails, all other suites are skipped for the run.

Each suite directory has:
- `suite.conf` — capabilities (`requires=docker,remote,cloud`), optional `cluster=destructive`
- `test-*.sh` — one executable bash file per scenario; invoked in lexical order

## Interpreting Results

When the run finishes, the terminal prints three blocks:

```
========================================
  INTEGRATION TEST RESULTS
========================================
  [PASS] 00-smoke                   2p/0f  (34s)
  [FAIL] 02-chaos                   3p/1f  (128s)
  ...
========================================
  Total: 15 | Passed: 13 | Failed: 1 | Skipped: 1
========================================

========================================
  TIMING SUMMARY
========================================
  Provisioning:        41s
  Cluster formation:   22s
  Blueprint deploy:    18s
  Quiesce barriers:
    quiesce_after_02-chaos:           12
    quiesce_after_13-edge-cases:      8
    test_Kill_leader_and_re-elect:    14
    ...
========================================
```

The machine-readable JSON report is written to `aether/tests/integration/test-results.json`:

```json
[
  { "suite": "00-smoke",  "status": "passed", "pass": 2, "fail": 0, "duration": 34 },
  { "suite": "02-chaos",  "status": "failed", "pass": 3, "fail": 1, "duration": 128 },
  ...
]
```

`duration` is wall-clock seconds for the whole suite. Per-test durations are emitted to stdout as `duration: <test_name>=<N>s` and captured in the timing summary.

### Timing categories

| Phase | Definition |
|-------|------------|
| Provisioning | `deploy_docker` start → all containers reported running |
| Cluster formation | containers up → leader elected + quorum formed + first quiesced snapshot |
| Blueprint deploy | `aether blueprint deploy` round-trip → post-deploy generation quiesces |
| Quiesce barrier | `await_generation_quiesced` call duration (seconds) — after destructive suites or deploy steps |
| Per-test | individual `test_*` function wall time |

The quiesce barriers replace the old `self_heal` 3-step recovery. See [`aether/docs/specs/cluster-generation-spec.md`](../../docs/specs/cluster-generation-spec.md) §13.3.

## Troubleshooting

**"connection refused" at startup**
- `curl -s http://${TARGET_HOST}:5150/health/live` — cluster A's node-1 management port.
- Check the docker daemon is up: `ssh $TARGET_HOST docker ps | grep aether-`.

**"port 5150 already in use"**
- A previous run didn't tear down cleanly. Full cleanup:
  ```bash
  ssh $TARGET_HOST 'docker rm -f $(docker ps -aq --filter "name=aether-"); docker network prune -f'
  ```

**Stale `aether-core-*` containers** (CTM auto-heal remnants)
- `docker rm -f $(docker ps -aq --filter name=aether-core)` on the target host.
- With the ClusterGeneration barrier in place, CTM's reconciliation drives these to a quiesced steady state — leftovers usually mean the test was killed mid-suite.

**Test hangs in `await_generation_quiesced`**
- The server enforces a hard cap of 120s; the helper caps at the requested timeout + 5s on the outer curl.
- Inspect the snapshot directly:
  ```bash
  curl -s -H "X-API-Key: $AETHER_API_KEY" http://${TARGET_HOST}:5150/api/cluster/generation | head -c 800
  ```
- If `mode: "unknown"` or `epoch: null`, pings haven't propagated yet — check that the leader node is actually up.

**Slice never activates**
- Blueprint was pushed but never deployed: `aether -c $TARGET_HOST:5150 --api-key $AETHER_API_KEY blueprint list`.
- Check recent events: `aether -c $TARGET_HOST:5150 --api-key $AETHER_API_KEY events --limit 50`.

**Grep logs on the target**
```bash
ssh $TARGET_HOST 'docker logs aether-a-node-1 2>&1 | tail -200'
ssh $TARGET_HOST 'docker logs aether-b-node-1 2>&1 | grep -iE "ERROR|WARN" | tail -50'
```

**Rebuild the Docker image without re-running the full test sweep**
```bash
./aether/tests/integration/scripts/build-and-push.sh
./aether/tests/integration/run-tests.sh --env remote --skip-build --suites 00
```

## Adding New Tests

1. Pick the suite directory that matches the feature area (or create `suites/NN-my-area/` and add a `suite.conf`).
2. Create `test-<scenario>.sh` with the skeleton below. Tests run under `set -euo pipefail`.
3. Use the shared helpers — **do not** reimplement retry loops or sleeps. The ClusterGeneration barrier makes state transitions deterministic.

### Suite skeleton

```bash
#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_my_feature() {
    wait_for_cluster_ready 60
    # Deterministic barrier — replaces ad-hoc sleeps.
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current" 30 || log_warn "pre-test snapshot not quiesced"

    local count
    count=$(cluster_member_count)
    assert_ge "$count" "3" "Cluster has quorum"

    # HTTP helpers: api_get, api_post, api_put, api_delete (mgmt API)
    #               app_get, app_post (slice routes)
    local response
    response=$(api_get "/api/some-endpoint")
    assert_contains "$response" "expected" "Response has expected content"
}

run_test "My feature test" test_my_feature
print_summary
```

### `suite.conf` format

```ini
requires=docker,remote,cloud
# Optional — declares this suite as destructive (assigns to cluster B).
cluster=destructive
```

If omitted, the suite runs on cluster A (parallel, non-destructive).

### Assertions cheat sheet

| Helper | Purpose |
|--------|---------|
| `assert_eq <actual> <expected> <desc>` | strict equality |
| `assert_ne <actual> <unexpected> <desc>` | inequality |
| `assert_ge <actual> <threshold> <desc>` | `>=` integer |
| `assert_gt <actual> <threshold> <desc>` | `>` integer |
| `assert_contains <haystack> <needle> <desc>` | substring |
| `assert_http_status <url> <code> <desc>` | status code check |
| `assert_json_field <json> <field> <expected> <desc>` | single field match |

### Deterministic barriers

| Instead of... | Use... |
|---------------|--------|
| `sleep 10` after a deploy | `await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 30` |
| retry loop around `deploy_blueprint` | single call; preceding barrier guarantees cluster is ready |
| `restart_all_nodes` / `self_heal` in cleanup | `await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 60` |
| polling `cluster_member_count` after a kill | `await_generation_quiesced`, then assert count directly |
| single-shot `cluster_member_count` after `scale_cluster` / `kill_node` | `cluster_node_count_quiesced` — flushes snapshot, returns count |

See [`lib/generation.sh`](lib/generation.sh) for the full helper surface and [`aether/docs/specs/cluster-generation-spec.md`](../../docs/specs/cluster-generation-spec.md) for the semantics.

---

Last updated: 2026-04-18 (v1.0.0-rc1, post ClusterGeneration overhaul).
