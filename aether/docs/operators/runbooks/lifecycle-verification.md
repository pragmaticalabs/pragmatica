# Slice Lifecycle Verification Runbook

This runbook verifies the slice lifecycle implementation matches the design in `docs/contributors/slice-lifecycle.md`.

Every management-API path below is versioned (`/api/v1/...`). The prefix is composed at one site —
`ManagementRoute.API_BASE` — and it is mandatory: an unversioned `/api/...` request on port 5150 does
not reach the router at all. It falls through to the static file handler, which answers
`404 File not found: /api/...`, so a missing `/v1/` looks like a missing page rather than a bad route.

## Prerequisites

1. Build the project:
   ```bash
   mvn package -DskipTests
   ```

2. Verify jars exist:
   ```bash
   ls aether/forge/forge-core/target/aether-forge.jar
   ls aether/cli/target/aether.jar
   ```

3. Start from clean simulator state. Forge keeps per-node data under `$AETHER_HOME/forge-data`,
   falling back to `~/.aether/forge-data`. A run that inherits stale or partially-written snapshots
   logs `Snapshot restore failed` per node on boot and may never reach quorum, which presents as
   step 2 hanging rather than as a storage error. Point Forge at a fresh directory instead of
   deleting the default one:

   ```bash
   export AETHER_HOME="$(mktemp -d)"
   ```

   Leader election took **8s** from clean state. On inherited state it took 62s on one run, and on
   another no leader was elected within 125s (`activePeerCount=1, clusterSize=5, quorumSize=3`).
   This is why step 1 polls for readiness rather than sleeping for a fixed interval.

---

## Method 1: Forge (Standalone Simulator)

### 1. Start Forge

Forge is a long-lived foreground server. Background it so the remaining steps can run in the same
shell, then wait for leader election: every route below is leader-bound and answers
`503 No leader elected for leader-bound management route` until election completes.

```bash
java -jar aether/forge/forge-core/target/aether-forge.jar > /tmp/forge.log 2>&1 &

for i in $(seq 1 120); do
  curl -sf http://localhost:5150/api/v1/nodes/status | grep -q '"isLeader":true' && break
  sleep 1
done
```

Expected output in `/tmp/forge.log`:
```
============================================================
    AETHER FORGE
============================================================
  Dashboard: http://localhost:8888
  Cluster size: 5 nodes
============================================================
```

### 2. Verify Cluster Status

```bash
curl -s http://localhost:5150/api/v1/nodes/status
```

Expected: JSON carrying `"status":"running"` and `"isLeader":true` at the top level, plus a `cluster`
object reporting `"nodeCount":5` and `"quorate":true`.

### 3. Check Nodes

```bash
curl -s http://localhost:5150/api/v1/nodes
```

Expected: one **object** per node — `nodeId`, `role`, `isLeader` — for all five simulator nodes, not
a list of bare ID strings:

```json
{"nodes":[{"nodeId":"node-1","role":"CORE","isLeader":true},{"nodeId":"node-2","role":"CORE","isLeader":false}]}
```

(Abridged: the live response carries all five nodes. Ordering is not stable.)

### 4. Deploy a Slice

The request body is a **TOML** blueprint document, not JSON — `SliceRoutes.handleBlueprint` passes the
raw body to `BlueprintParser.parse`, which hands it to `TomlParser`. The blueprint `id` must be a full
artifact coordinate (`group:artifact:version`); a bare name is rejected by `BlueprintId`.

```bash
cat > /tmp/test-blueprint.toml <<'TOML'
id = "org.example:test-blueprint:1.0.0"

[[slices]]
artifact = "org.example:test-slice:1.0.0"
instances = 1
TOML

curl -s -X POST http://localhost:5150/api/v1/blueprints \
  -H "Content-Type: text/plain" \
  --data-binary @/tmp/test-blueprint.toml
```

Expected, when the slice artifact resolves:
```json
{"status":"applied","blueprint":"org.example:test-blueprint:1.0.0","targetInstances":1,"activeInstances":0,"failedInstances":0,"statusUrl":"/api/v1/blueprints/status/org.example%3Atest-blueprint%3A1.0.0"}
```

`"applied"` means **accepted, not deployed** — it is written before allocation runs and is never
updated with the outcome. Poll `statusUrl` for progress. See
[management-api.md](../../reference/management-api.md#post-apiv1blueprints).

`org.example:test-slice:1.0.0` is a placeholder. Publishing resolves the slice artifact from the
local repository **synchronously**, so a coordinate that is not installed is refused at this step
rather than being accepted and failing later in the lifecycle:

```json
{"title":"Internal Server Error","status":500,"detail":"Artifact not found in local repository: org.example:test-slice:1.0.0 at /Users/<you>/.m2/repository/org/example/test-slice/1.0.0/test-slice-1.0.0.jar"}
```

To exercise steps 5 and 6, substitute a slice artifact that is installed in the local repository.
Note the lookup uses the default `~/.m2/repository` — the Forge JVM does not read `.mvn/maven.config`,
so a per-tree `maven.repo.local` does not apply here.

Two further failure shapes worth recognising, both `500`:

| Body / value | `detail` |
|---|---|
| Bare `id` such as `test-blueprint` | `Invalid blueprint ID format: test-blueprint` |
| A JSON body | `TOML parse error: TOML syntax error at line 1: {...}` |

### 5. Verify Lifecycle States

For a resolvable artifact the state sequence is:

```
LOAD → LOADING → LOADED → ACTIVATE → ACTIVATING → ACTIVE
```

and, when a load fails, `LOAD → LOADING → FAILED`.

Poll the status endpoint — it reports per-instance state and is the reliable surface for this:

```bash
curl -s http://localhost:5150/api/v1/slices/status
```

**There is no per-state log line.** `NodeDeploymentState.Active.transitionTo` writes each state to the
KV store without logging, so LOADING/LOADED/ACTIVATING are observable through the status endpoint
only. Two lines do accompany the lifecycle, and neither is visible at the default INFO threshold
alone:

| Log line | Level | Source |
|---|---|---|
| `Issuing LOAD command for slices/<nodeId>/<artifact>` | `DEBUG` | `ClusterDeploymentState` |
| `Slice <artifact> entered FAILED state` | `WARN` | `NodeDeploymentState.Active.handleFailed` |

### 6. Check Slice Status

```bash
curl -s http://localhost:5150/api/v1/slices/status
```

Expected, with a slice deployed:
```json
{"slices":[{"artifact":"org.example:my-slice:1.0.0","state":"ACTIVE","instances":[{"nodeId":"node-1","state":"ACTIVE","health":"HEALTHY"}]}]}
```

With nothing deployed — including after an `ALL_OR_NOTHING` rollback — the response is `{}`. An empty
response is therefore not evidence that a deploy never happened; see
[failure-almanac.md](../../reference/failure-almanac.md#per-node-deployment-failure-under-all_or_nothing-rollback).

### 7. Cleanup

Stop Forge with Ctrl+C or:
```bash
pkill -f "aether-forge.jar"
```

`pkill` returns before the JVM has exited. Confirm termination rather than assuming it:
```bash
for i in $(seq 1 30); do pgrep -f "aether-forge.jar" >/dev/null || break; sleep 1; done
pgrep -f "aether-forge.jar" || echo "stopped"
```

---

## Method 2: AetherCli

This method drives the same lifecycle through the CLI instead of `curl`.

The CLI assembles every request path from the same `ManagementRoute` enum the server routes with, so
it carries the `/api/v1` prefix automatically — no manual prefixing is needed or possible. The CLI
defaults to `localhost:8080`, while Forge's node-1 management API listens on 5150, so pass
`--connect` on every invocation.

### 1. Start Forge (as cluster backend)

```bash
java -jar aether/forge/forge-core/target/aether-forge.jar > /tmp/forge.log 2>&1 &

for i in $(seq 1 120); do
  curl -sf http://localhost:5150/api/v1/nodes/status | grep -q '"isLeader":true' && break
  sleep 1
done
```

### 2. Check Status via CLI

```bash
# Cluster status
java -jar aether/cli/target/aether.jar --connect localhost:5150 status

# Nodes
java -jar aether/cli/target/aether.jar --connect localhost:5150 nodes

# Slices (cluster-wide view)
java -jar aether/cli/target/aether.jar --connect localhost:5150 slices

# Slices (per-node flat list)
java -jar aether/cli/target/aether.jar --connect localhost:5150 nodes slices

# Health
java -jar aether/cli/target/aether.jar --connect localhost:5150 health
```

The CLI pretty-prints the same documents the endpoints in Method 1 return.

### 3. Deploy via CLI

`blueprints apply` takes a path to a `.toml` blueprint and POSTs its contents — the same
`POST /api/v1/blueprints` call as Method 1 step 4, with the same body rules.

```bash
cat > /tmp/test-blueprint.toml <<'TOML'
id = "org.example:test-blueprint:1.0.0"

[[slices]]
artifact = "org.example:test-slice:1.0.0"
instances = 1
TOML

java -jar aether/cli/target/aether.jar --connect localhost:5150 \
  blueprints apply /tmp/test-blueprint.toml
```

Expected: the `"applied"` document from Method 1 step 4.

### 4. Monitor Lifecycle

```bash
# Poll slice status. `watch` is not installed by default on macOS, so loop instead.
while true; do
  java -jar aether/cli/target/aether.jar --connect localhost:5150 slices status
  sleep 2
done
```

```bash
# Check logs for the two lifecycle lines that exist (see Method 1 step 5)
grep -E "Issuing LOAD command|entered FAILED state" /tmp/forge.log
```

Do not grep for `LOAD|LOADING|LOADED|ACTIVATE|ACTIVATING|ACTIVE|FAILED`. That pattern matches
node-lifecycle transitions (`NodeLifecycle: JOINING -> ACTIVE`), consensus lines
(`ConsensusBridge: ACTIVE for ...`), snapshot states (`LOADING_SNAPSHOT`) and stream-create
warnings — on a measured run it returned 51 matches, **none** of which referenced a slice key.

### 5. Undeploy

```bash
java -jar aether/cli/target/aether.jar --connect localhost:5150 \
  blueprints delete org.example:test-blueprint:1.0.0 --force
```

Expected: `Deleted blueprint: org.example:test-blueprint:1.0.0`

`--force` skips the interactive `(y/N)` confirmation, which otherwise blocks a scripted run.

The equivalent direct call — note `id` is a **path segment**, not a query parameter:
```bash
curl -s -X DELETE http://localhost:5150/api/v1/blueprints/org.example:test-blueprint:1.0.0
```
Expected: `{"status":"deleted","id":"org.example:test-blueprint:1.0.0"}`

(`DELETE /api/v1/blueprints?id=...` answers `404 File not found: /api/v1/blueprints` — the route
matcher strips the query string before matching, so the query form matches no DELETE route.)

Expected lifecycle: `DEACTIVATE → DEACTIVATING → LOADED → UNLOAD → UNLOADING → (deleted)`

### 6. Cleanup

As Method 1 step 7.

---

## Verification Checklist

### State Transitions

| Transition | Expected Behavior | Verification |
|------------|-------------------|--------------|
| LOAD → LOADING | Write LOADING to KV before starting load | `GET /api/v1/slices/status` reports LOADING |
| LOADING → LOADED | On success after load completes | Status shows LOADED |
| LOADING → FAILED | On error during load | Logs show "entered FAILED state" (WARN) |
| LOADED → (no auto-activate) | Requires explicit ACTIVATE | Verify slice stays in LOADED |
| ACTIVATE → ACTIVATING | Write ACTIVATING before activation | Status shows ACTIVATING |
| ACTIVATING → ACTIVE | After start + register + publish | Status shows ACTIVE |
| DEACTIVATE → DEACTIVATING | Write DEACTIVATING + remove endpoints | Status shows DEACTIVATING |
| DEACTIVATING → LOADED | After stop completes | Status shows LOADED |
| UNLOAD → UNLOADING | Write UNLOADING before unload | Status shows UNLOADING |
| UNLOADING → (deleted) | Delete KV key after unload | Slice no longer in status |

### Key Fixes Verified

The slice-lifecycle handlers live on `NodeDeploymentState.Active` (in
`aether/aether-deployment/.../deployment/node/fsm/NodeDeploymentState.java`), dispatched from its
state-machine switch — **not** on `NodeDeploymentManager`, which owns quorum/membership
reconciliation instead.

1. **sliceStore** (record): Uses `ConcurrentHashMap<Artifact, Promise<LoadedSliceEntry>>` with `computeIfAbsent` for atomic loading
2. **`NodeDeploymentState.Active.handleLoading`**: Writes LOADING state before calling `SliceStore.loadSlice()`
3. **`NodeDeploymentState.Active.handleLoaded`**: No auto-activation (requires explicit ACTIVATE from ClusterDeploymentManager)
4. **`NodeDeploymentState.Active.handleActivating`**: Calls `SliceStore.activateSlice()`, then registers invocation and publishes topic/stream/scheduled-task/route entries before transitioning to ACTIVE (endpoint publication follows the ACTIVE write)
5. **`NodeDeploymentState.Active.handleFailed`**: Logs "entered FAILED state" message
6. **`NodeDeploymentState.Active.handleUnloading`**: Writes UNLOADING, calls `SliceStore.unloadSlice()`, then deletes KV key

---

## Troubleshooting

### Step 2 never returns / `503 No leader elected`
- The management routes are leader-bound; wait for election rather than retrying immediately
- Check for inherited simulator state: `Snapshot restore failed` on boot, or
  `activePeerCount=1, clusterSize=5, quorumSize=3` in the log, means the cluster is not reaching
  quorum. Restart with a fresh `AETHER_HOME` (see Prerequisites step 3)

### Slice stuck in LOAD state
- Check if artifact exists in repository
- Check logs for errors during load
- Verify SliceStore is processing the request

### No state transitions visible
- There is no per-state log line — read `GET /api/v1/slices/status`, not the log (see Method 1 step 5)
- `Issuing LOAD command` is logged at **DEBUG**, so it is absent at the default INFO threshold
- Check correct port (5150 for node-1 management API)

### `404 File not found: /api/...`
- The path is missing the version prefix. Every management route is under `/api/v1/`
- An unversioned `/api/...` request is served by the static file handler, not the router, which is
  why the body is `File not found:` rather than a JSON problem-detail document

### CLI cannot connect
- The CLI defaults to `localhost:8080`; Forge's node-1 management API is on 5150. Pass
  `--connect localhost:5150`
- The CLI composes `/api/v1` itself from `ManagementRoute` — do not add a prefix to CLI arguments

### Dashboard not loading
- Dashboard is at http://localhost:8888
- Management API is at http://localhost:5150

### Dashboard not showing slice status
- Fixed in release 0.19.0: Dashboard now queries KV store directly via ForgeCluster.slicesStatus()
- Slices should appear on both Overview page (node cards) and Cluster page (slices table)
- Verify slice state by checking KV store entries for `slices/{nodeId}/{artifact}` keys
