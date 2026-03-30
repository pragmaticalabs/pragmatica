# Storage Management API Specification

## Addendum to: [Hierarchical Storage Spec](hierarchical-storage-spec.md)
## Status: Implementation-Ready
## Target Release: v0.25.0+

---

## 1. Per-Node Routes (StorageRoutes.java)

New file: `aether/node/src/main/java/org/pragmatica/aether/api/routes/StorageRoutes.java`

Implements `RouteSource`. Receives `Supplier<AetherNode>` (same pattern as `StatusRoutes`, `StreamRoutes`). AetherNode must expose `Map<String, StorageFactory.StorageSetup>` via a new accessor.

### GET /api/storage

List all storage instances on this node.

**Response: `StorageListResponse`**

```json
{
  "instances": [
    {
      "name": "artifacts",
      "tiers": [
        { "level": "MEMORY", "usedBytes": 104857600, "maxBytes": 268435456, "utilizationPct": 39.1 },
        { "level": "LOCAL_DISK", "usedBytes": 524288000, "maxBytes": 1073741824, "utilizationPct": 48.8 }
      ],
      "readiness": {
        "state": "READY",
        "isReadReady": true,
        "isWriteReady": true
      }
    }
  ]
}
```

### GET /api/storage/{name}

Detailed status of one instance.

**Path parameter:** `name` -- storage instance name (e.g., `artifacts`, `stream-events`)

**Response: `StorageDetailResponse`**

```json
{
  "name": "artifacts",
  "tiers": [
    { "level": "MEMORY", "usedBytes": 104857600, "maxBytes": 268435456, "utilizationPct": 39.1 },
    { "level": "LOCAL_DISK", "usedBytes": 524288000, "maxBytes": 1073741824, "utilizationPct": 48.8 }
  ],
  "snapshot": {
    "lastEpoch": 42,
    "lastTimestampMs": 1711756800000
  },
  "readiness": {
    "state": "READY",
    "isReadReady": true,
    "isWriteReady": true
  }
}
```

**Error:** 404 if instance name not found.

### POST /api/storage/{name}/snapshot

Force a metadata snapshot on this node.

**Path parameter:** `name` -- storage instance name

**Response: `SnapshotResponse`**

```json
{
  "name": "artifacts",
  "epoch": 43,
  "timestampMs": 1711756860000
}
```

**Error:** 404 if instance name not found.

---

## 2. Per-Cluster Routes

These reuse the existing `fetchFromNode` fan-out pattern used by `/api/cluster/*` routes. The connected node broadcasts to all peers, collects responses, and merges.

### GET /api/cluster/storage

All storage instances across all nodes, aggregated.

**Response: `ClusterStorageListResponse`**

```json
{
  "instances": [
    {
      "name": "artifacts",
      "nodeCount": 3,
      "totalUsedBytes": 1572864000,
      "totalMaxBytes": 4026531840,
      "nodes": [
        {
          "nodeId": "node-1",
          "tiers": [
            { "level": "MEMORY", "usedBytes": 104857600, "maxBytes": 268435456, "utilizationPct": 39.1 },
            { "level": "LOCAL_DISK", "usedBytes": 524288000, "maxBytes": 1073741824, "utilizationPct": 48.8 }
          ],
          "readiness": { "state": "READY", "isReadReady": true, "isWriteReady": true }
        }
      ]
    }
  ]
}
```

### GET /api/cluster/storage/{name}

One instance across all nodes.

**Response: `ClusterStorageDetailResponse`**

```json
{
  "name": "artifacts",
  "nodeCount": 3,
  "totalUsedBytes": 1572864000,
  "totalMaxBytes": 4026531840,
  "nodes": [
    {
      "nodeId": "node-1",
      "tiers": [
        { "level": "MEMORY", "usedBytes": 104857600, "maxBytes": 268435456, "utilizationPct": 39.1 },
        { "level": "LOCAL_DISK", "usedBytes": 524288000, "maxBytes": 1073741824, "utilizationPct": 48.8 }
      ],
      "snapshot": { "lastEpoch": 42, "lastTimestampMs": 1711756800000 },
      "readiness": { "state": "READY", "isReadReady": true, "isWriteReady": true }
    }
  ]
}
```

---

## 3. Response Records

All records live inside `StorageRoutes.java` (same pattern as `StreamRoutes` inner records).

```java
// Shared building blocks
record TierDetail(TierLevel level, long usedBytes, long maxBytes, double utilizationPct) {}
record ReadinessDetail(ReadinessState state, boolean isReadReady, boolean isWriteReady) {}
record SnapshotDetail(long lastEpoch, long lastTimestampMs) {}

// Per-node responses
record StorageInstanceSummary(String name, List<TierDetail> tiers, ReadinessDetail readiness) {}
record StorageListResponse(List<StorageInstanceSummary> instances) {}
record StorageDetailResponse(String name, List<TierDetail> tiers, SnapshotDetail snapshot, ReadinessDetail readiness) {}
record SnapshotResponse(String name, long epoch, long timestampMs) {}

// Per-cluster responses
record NodeStorageSummary(String nodeId, List<TierDetail> tiers, ReadinessDetail readiness) {}
record NodeStorageDetail(String nodeId, List<TierDetail> tiers, SnapshotDetail snapshot, ReadinessDetail readiness) {}
record ClusterStorageInstanceSummary(String name, int nodeCount, long totalUsedBytes, long totalMaxBytes, List<NodeStorageSummary> nodes) {}
record ClusterStorageListResponse(List<ClusterStorageInstanceSummary> instances) {}
record ClusterStorageDetailResponse(String name, int nodeCount, long totalUsedBytes, long totalMaxBytes, List<NodeStorageDetail> nodes) {}
```

**Computation:** `utilizationPct` = `(usedBytes * 100.0) / maxBytes`, rounded to 1 decimal. `totalUsedBytes`/`totalMaxBytes` = sum across all nodes for matching tier levels.

---

## 4. CLI Commands

Add `StorageCommand` to `AetherCli.java` following the `StreamCommand` pattern (picocli `@Command` with subcommands).

```
aether storage list                           # GET /api/cluster/storage
aether storage list --node <nodeId>           # GET /api/storage (on specified node)
aether storage status <name>                  # GET /api/cluster/storage/{name}
aether storage status <name> --node <nodeId>  # GET /api/storage/{name} (on specified node)
aether storage snapshot <name>                # POST /api/storage/{name}/snapshot (on connected node)
aether storage snapshot <name> --node <nodeId># POST on specified node
```

**`--node` behavior:** When omitted, `list` and `status` use the cluster-wide endpoint. When present, they hit the per-node endpoint on the specified node (requires the CLI to connect to that node, or the management server to proxy -- use the same approach as existing per-node commands like `node-slices`).

### CLI Output Formats

**`storage list` (cluster-wide):**
```
Storage Instances (cluster-wide):
  artifacts    3 nodes  MEMORY: 300MB/768MB (39%)  LOCAL_DISK: 1.5GB/3GB (49%)
  kv-snapshots 3 nodes  LOCAL_DISK: 50MB/1GB (5%)
```

**`storage status <name>` (cluster-wide):**
```
Storage: artifacts (3 nodes)
  node-1  READY  MEMORY: 100MB/256MB (39%)  LOCAL_DISK: 500MB/1GB (49%)  snapshot: epoch=42
  node-2  READY  MEMORY: 100MB/256MB (39%)  LOCAL_DISK: 500MB/1GB (49%)  snapshot: epoch=42
  node-3  READY  MEMORY: 100MB/256MB (39%)  LOCAL_DISK: 500MB/1GB (49%)  snapshot: epoch=42
```

**`storage snapshot <name>`:**
```
Snapshot triggered: artifacts epoch=43
```

---

## 5. Implementation Checklist

### AetherNode Changes

- [ ] Expose `Map<String, StorageFactory.StorageSetup>` from `AetherNode` (currently local variable in init). Add field + accessor.

### New File: StorageRoutes.java

- [ ] `aether/node/src/main/java/org/pragmatica/aether/api/routes/StorageRoutes.java`
- [ ] Implements `RouteSource`, receives `Supplier<AetherNode>`
- [ ] Per-node routes: GET `/api/storage`, GET `/api/storage/{name}`, POST `/api/storage/{name}/snapshot`
- [ ] Cluster routes: GET `/api/cluster/storage`, GET `/api/cluster/storage/{name}` -- fan out to all nodes via existing broadcast mechanism, merge results

### ManagementServer Wiring

- [ ] Add `StorageRoutes` to `ManagementRouter.managementRouter(...)` call in `ManagementServerImpl`
- [ ] Import `StorageRoutes`

### CLI Changes

- [ ] Add `StorageCommand` class in `AetherCli.java` with subcommands: `ListCommand`, `StatusCommand`, `SnapshotCommand`
- [ ] Register in top-level `@Command(subcommands = {...})` list
- [ ] `--node` option on `ListCommand` and `StatusCommand` to toggle per-node vs cluster-wide

### RBAC Permissions

| Route | Minimum Role |
|-------|-------------|
| GET /api/storage | VIEWER |
| GET /api/storage/{name} | VIEWER |
| POST /api/storage/{name}/snapshot | OPERATOR |
| GET /api/cluster/storage | VIEWER |
| GET /api/cluster/storage/{name} | VIEWER |

Register in `RoutePermissionRegistry`.

### Documentation Updates

- [ ] `aether/docs/reference/management-api.md` -- add Storage section
- [ ] `aether/docs/reference/cli.md` -- add `storage` command section
- [ ] `aether/docs/reference/feature-catalog.md` -- update AHSE entry

---

## 6. References

### Internal
- [Hierarchical Storage Spec](hierarchical-storage-spec.md) -- parent spec
- `aether/aether-storage/src/main/java/org/pragmatica/aether/storage/StorageInstance.java` -- `tierInfo()`, `TierInfo` record
- `aether/aether-storage/src/main/java/org/pragmatica/aether/storage/StorageReadinessGate.java` -- `ReadinessState`, `isReadReady`, `isWriteReady`
- `aether/aether-storage/src/main/java/org/pragmatica/aether/storage/SnapshotManager.java` -- `forceSnapshot()`, `lastSnapshotEpoch()`
- `aether/node/src/main/java/org/pragmatica/aether/node/StorageFactory.java` -- `StorageSetup` record
- `aether/node/src/main/java/org/pragmatica/aether/api/routes/StreamRoutes.java` -- reference pattern for route structure
- `aether/node/src/main/java/org/pragmatica/aether/api/routes/ClusterConfigRoutes.java` -- reference pattern for cluster broadcast routes
