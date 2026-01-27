# Slice Lifecycle Verification Runbook

This runbook verifies the slice lifecycle implementation matches the design in `docs/contributors/slice-lifecycle.md`.

## Prerequisites

1. Build the project:
   ```bash
   mvn package -DskipTests
   ```

2. Verify jars exist:
   ```bash
   ls forge/target/aether-forge.jar
   ls cli/target/aether.jar
   ```

---

## Method 1: Forge (Standalone Simulator)

### 1. Start Forge

```bash
java -jar forge/target/aether-forge.jar
```

Expected output:
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
curl -s http://localhost:5150/api/status
```

Expected: JSON with `status: "running"`, `isLeader: true`

### 3. Check Nodes

```bash
curl -s http://localhost:5150/api/nodes
```

Expected: `{"nodes":["node-1"]}`

### 4. Deploy a Slice

```bash
curl -s -X POST http://localhost:5150/api/deploy \
  -H "Content-Type: application/json" \
  -d '{"artifact": "org.example:test-slice:1.0.0", "instances": 1}'
```

Expected: `{"status":"deployed","artifact":"org.example:test-slice:1.0.0","instances":1}`

### 5. Verify Lifecycle States in Logs

Watch the Forge logs for state transitions:

```
LOAD → LOADING → FAILED (artifact not found)
```

Expected log messages:
```
Issuing LOAD command for slices/node-1/org.example:test-slice:1.0.0
ValuePut received for key: slices/node-1/org.example:test-slice:1.0.0, state: LOAD
ValuePut received for key: slices/node-1/org.example:test-slice:1.0.0, state: LOADING
ValuePut received for key: slices/node-1/org.example:test-slice:1.0.0, state: FAILED
Slice org.example:test-slice:1.0.0 entered FAILED state
```

### 6. Check Slice Status

```bash
curl -s http://localhost:5150/api/slices/status
```

Expected: Slice in FAILED state

### 7. Cleanup

Stop Forge with Ctrl+C or:
```bash
pkill -f "aether-forge.jar"
```

---

## Method 2: AetherCli

### 1. Start Forge (as cluster backend)

```bash
java -jar forge/target/aether-forge.jar > /tmp/forge.log 2>&1 &
sleep 8
```

### 2. Check Status via CLI

**Note:** CLI endpoints need `/api/` prefix. Current CLI has a path mismatch issue.

Direct API access:
```bash
# Status
curl -s http://localhost:5150/api/status

# Nodes
curl -s http://localhost:5150/api/nodes

# Slices
curl -s http://localhost:5150/api/slices

# Health
curl -s http://localhost:5150/api/health
```

### 3. Deploy via API

```bash
curl -s -X POST http://localhost:5150/api/deploy \
  -H "Content-Type: application/json" \
  -d '{"artifact": "org.pragmatica-lite.aether.demo:inventory-service:0.1.0", "instances": 1}'
```

### 4. Monitor Lifecycle

```bash
# Watch slice status
watch -n 1 'curl -s http://localhost:5150/api/slices/status'

# Check logs for state transitions
grep -E "LOAD|LOADING|LOADED|ACTIVATE|ACTIVATING|ACTIVE|FAILED" /tmp/forge.log
```

### 5. Undeploy

```bash
curl -s -X POST http://localhost:5150/api/undeploy \
  -H "Content-Type: application/json" \
  -d '{"artifact": "org.example:test-slice:1.0.0"}'
```

Expected lifecycle: `DEACTIVATE → DEACTIVATING → LOADED → UNLOAD → UNLOADING → (deleted)`

---

## Verification Checklist

### State Transitions

| Transition | Expected Behavior | Verification |
|------------|-------------------|--------------|
| LOAD → LOADING | Write LOADING to KV before starting load | Check logs for LOADING state write |
| LOADING → LOADED | On success after load completes | Check slice status shows LOADED |
| LOADING → FAILED | On error during load | Check logs for "entered FAILED state" |
| LOADED → (no auto-activate) | Requires explicit ACTIVATE | Verify slice stays in LOADED |
| ACTIVATE → ACTIVATING | Write ACTIVATING before activation | Check logs |
| ACTIVATING → ACTIVE | After start + register + publish | Check slice status shows ACTIVE |
| DEACTIVATE → DEACTIVATING | Write DEACTIVATING + remove endpoints | Check logs |
| DEACTIVATING → LOADED | After stop completes | Check slice status |
| UNLOAD → UNLOADING | Write UNLOADING before unload | Check logs |
| UNLOADING → (deleted) | Delete KV key after unload | Check slice no longer in status |

### Key Fixes Verified

1. **SliceStoreImpl**: Uses `ConcurrentHashMap<Artifact, Promise<LoadedSliceEntry>>` with `computeIfAbsent` for atomic loading
2. **handleLoading**: Writes LOADING state before starting load operation
3. **handleLoaded**: No auto-activation (requires explicit ACTIVATE from ClusterDeploymentManager)
4. **handleActivating**: Registers + publishes BEFORE transitioning to ACTIVE
5. **handleFailed**: Logs "entered FAILED state" message
6. **handleUnloading**: Writes UNLOADING + deletes KV key

---

## Troubleshooting

### Slice stuck in LOAD state
- Check if artifact exists in repository
- Check logs for errors during load
- Verify SliceStore is processing the request

### No state transitions visible
- Ensure logging is at INFO level
- Check correct port (5150 for node-1 management API)

### CLI returns 404
- CLI currently doesn't add `/api/` prefix
- Use curl with `/api/` prefix directly

### Dashboard not loading
- Dashboard is at http://localhost:8888
- Management API is at http://localhost:5150

### Dashboard not showing slice status
- Fixed in release 0.7.3: Dashboard now queries KV store directly via ForgeCluster.slicesStatus()
- Slices should appear on both Overview page (node cards) and Cluster page (slices table)
- Verify slice state by checking KV store entries for `slices/{nodeId}/{artifact}` keys
