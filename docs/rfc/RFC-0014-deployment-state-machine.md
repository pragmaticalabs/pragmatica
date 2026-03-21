---
RFC: 0014
Title: Deployment State Machine
Status: Implemented
Author: Sergiy Yevtushenko
Created: 2026-03-19
Updated: 2026-03-19
Affects: [aether-deployment]
Supersedes: RFC-0004 (deployment sections only)
---

## Summary

Documents the complete deployment state machine governing slice lifecycle in Aether, including the CDM/NDM handoff protocol, schema migration gating, dependency-ordered activation, failure classification and retry, quorum loss/restoration, reconciliation, blueprint atomicity, and drain eviction.

## Motivation

RFC-0004 defined slice packaging but left the runtime deployment protocol underspecified. As the system matured, the deployment flow acquired dependency-gated activation, schema migration gates, blueprint atomicity (ALL_OR_NOTHING), drain eviction, stuck-transitional detection, and quorum-aware suspend/resume. This RFC captures the complete protocol as implemented, superseding the deployment-related sections of RFC-0004.

## Design

### 1. Slice State Machine

#### 1.1 States

| State | Category | Actor | Description |
|-------|----------|-------|-------------|
| `LOAD` | Command | CDM | CDM issues load command via consensus |
| `LOADING` | Transitional | NDM | NDM loading artifact from SliceStore (timeout: 2m) |
| `LOADED` | Stable | NDM | Artifact loaded, awaiting activation |
| `ACTIVATE` | Command | CDM | CDM issues activate command via consensus |
| `ACTIVATING` | Transitional | NDM | NDM activating slice instance (timeout: 90s) |
| `ACTIVE` | Stable | NDM | Slice fully active, serving traffic |
| `DEACTIVATE` | Command | CDM | CDM issues deactivate command via consensus |
| `DEACTIVATING` | Transitional | NDM | NDM deactivating slice (timeout: 30s) |
| `UNLOAD` | Command | CDM | CDM issues unload command via consensus |
| `UNLOADING` | Transitional | NDM | NDM unloading artifact (timeout: 2m) |
| `FAILED` | Terminal | NDM | Operation failed, awaiting UNLOAD |

#### 1.2 State Diagram

```
                         CDM writes via consensus
                        ========================

    +------+         +----------+         +--------+
    | LOAD |-------->| LOADING  |-------->| LOADED |
    +------+         +----------+         +--------+
       ^                  |                  |    |
       |                  | fail             |    |
       |                  v                  |    |
       |             +--------+              |    |
       |             | FAILED |<----+        |    |
       |             +--------+     |        |    |
       |                  |         |        |    |
       |                  | CDM     |        |    |
       |                  v         |        |    |
       |             +--------+    |     +----------+      +--------------+      +--------+
       +-------------|UNLOAD  |    |     | ACTIVATE |----->| ACTIVATING   |----->| ACTIVE |
                     +--------+    |     +----------+      +--------------+      +--------+
                         |         |                            |                     |
                         v         |                            | fail                |
                    +-----------+  |                            |                     |
                    | UNLOADING |  +----------------------------+            +--------------+
                    +-----------+                                            | DEACTIVATE   |
                         |                                                   +--------------+
                         v                                                        |
                     (removed)                                              +--------------+
                                                                            | DEACTIVATING |
                                                                            +--------------+
                                                                                  |
                                                                                  | fail
                                                                                  v
                                                                              +--------+
                                                                              | LOADED |
                                                                              +--------+
```

#### 1.3 Valid Transitions

| From | Valid Targets |
|------|--------------|
| `LOAD` | `LOADING` |
| `LOADING` | `LOADED`, `FAILED` |
| `LOADED` | `ACTIVATE`, `UNLOAD` |
| `ACTIVATE` | `ACTIVATING` |
| `ACTIVATING` | `ACTIVE`, `FAILED` |
| `ACTIVE` | `DEACTIVATE` |
| `DEACTIVATE` | `DEACTIVATING` |
| `DEACTIVATING` | `LOADED`, `FAILED` |
| `FAILED` | `UNLOAD` |
| `UNLOAD` | `UNLOADING` |
| `UNLOADING` | (terminal -- entry removed from KV-Store) |

#### 1.4 State Classification

- **Command states** (`LOAD`, `ACTIVATE`, `DEACTIVATE`, `UNLOAD`): Written by CDM via consensus. Trigger NDM action.
- **Transitional states** (`LOADING`, `ACTIVATING`, `DEACTIVATING`, `UNLOADING`): Written by NDM. Have configurable timeouts. Indicate work in progress.
- **Stable states** (`LOADED`, `ACTIVE`): Written by NDM. No timeout. System is quiescent.
- **Terminal state** (`FAILED`): Written by NDM. Requires explicit `UNLOAD` to clean up.

### 2. CDM/NDM Handoff Protocol

#### 2.1 Communication Channel

CDM and NDM communicate exclusively through the consensus-backed KV-Store using `NodeArtifactKey` entries. There is no direct RPC or message passing between them.

```
CDM (leader only)                    KV-Store (consensus)                   NDM (every node)
       |                                    |                                     |
       |--- Put(NodeArtifactKey, LOAD) ---->|                                     |
       |                                    |--- ValuePut notification ---------->|
       |                                    |                                     |
       |                                    |<-- Put(NodeArtifactKey, LOADING) ---|
       |<-- ValuePut notification ----------|                                     |
       |                                    |                                     |
       |                                    |<-- Put(NodeArtifactKey, LOADED) ----|
       |<-- ValuePut notification ----------|                                     |
       |                                    |                                     |
       |--- Put(NodeArtifactKey, ACTIVATE)->|                                     |
       |                                    |--- ValuePut notification ---------->|
       |                                    |                                     |
       ...                                 ...                                   ...
```

#### 2.2 Key Types

- **`NodeArtifactKey(artifact, nodeId)`**: The primary communication channel. CDM writes command states; NDM writes transitional/stable/failed states. Both read via KV notifications.
- **`SliceNodeKey(artifact, nodeId)`**: CDM's in-memory tracking key for `sliceStates` map. Derived from `NodeArtifactKey`.

#### 2.3 CDM Responsibilities (Leader Only)

1. Watch `AppBlueprintKey` and `SliceTargetKey` for desired-state changes
2. Expand blueprints into individual slice allocations
3. Write `LOAD` commands to `NodeArtifactKey` via consensus
4. Track state transitions via `NodeArtifactKey` put notifications
5. Issue `ACTIVATE` when slice reaches `LOADED` and dependencies are met
6. Issue `UNLOAD` for scale-down, node removal, or failure cleanup
7. Run periodic reconciliation (default: 30s)
8. Gate activation on schema migration completion

#### 2.4 NDM Responsibilities (Every Node)

1. Watch `NodeArtifactKey` entries addressed to this node (`key.isForNode(self)`)
2. On command state received, execute the corresponding operation:
   - `LOAD`: Write `LOADING`, load artifact from `SliceStore`, write `LOADED`
   - `ACTIVATE`: Write `ACTIVATING`, activate slice, register for invocation, publish endpoints/routes/topics/tasks, write `ACTIVE`
   - `DEACTIVATE`: Write `DEACTIVATING`, unpublish all, deactivate slice, write `LOADED`
   - `UNLOAD`: Write `UNLOADING`, unpublish all, unload from `SliceStore`, delete `NodeArtifactKey`
3. On failure at any step, write `FAILED` with failure reason
4. Ignore transitional states (no-op when receiving `LOADING`, `ACTIVATING`, etc.)

#### 2.5 NDM State Machine (Internal)

NDM itself has two states:

- **`DormantNodeDeploymentState`**: No quorum. Ignores all `NodeArtifactKey` notifications. Optionally holds `SuspendedSlice` list for reactivation.
- **`ActiveNodeDeploymentState`**: Quorum established. Processes all notifications.

Transition: `ESTABLISHED` quorum notification activates NDM; `DISAPPEARED` suspends active slices and goes dormant.

### 3. Schema Migration Gate Protocol

#### 3.1 Flow

```
Blueprint publish
       |
       v
SchemaVersionKey written with PENDING status
       |
       v
CDM.onSchemaVersionPut() detects PENDING
       |
       v
SchemaOrchestratorService.migrateIfNeeded()
       |
       +--- acquireLock(datasource)          [SchemaMigrationLockKey via consensus]
       |
       +--- updateStatus(MIGRATING)          [SchemaVersionKey via consensus]
       |
       +--- resolveAndParseMigrations()      [ArtifactStore + BlueprintArtifactParser]
       |
       +--- markCompleted(COMPLETED)         [SchemaVersionKey via consensus]
       |
       +--- releaseLock()                    [Remove SchemaMigrationLockKey]
```

#### 3.2 Status Values

| Status | Meaning |
|--------|---------|
| `PENDING` | Migration needed, not yet started |
| `MIGRATING` | Lock acquired, migration in progress |
| `COMPLETED` | Migration finished successfully |
| `FAILED` | Migration failed (lock released, status updated) |

#### 3.3 Activation Gate

CDM's `tryActivateIfDependenciesReady()` checks that no schema for any dependency has `PENDING` or `MIGRATING` status. When a `SchemaVersionKey` notification arrives with `COMPLETED` status, CDM rechecks all `LOADED` slices for activation eligibility.

#### 3.4 Exclusive Lock

`SchemaMigrationLockKey` ensures only one node runs migrations for a given datasource at a time. TTL is 5 minutes (`LOCK_TTL_MS`). Lock is acquired via consensus `Put` and released via consensus `Remove`.

### 4. Dependency-Gated Activation

#### 4.1 Dependency Map Construction

When an `AppBlueprintValue` is received, CDM calls `buildDependencyMap()` which iterates the `ExpandedBlueprint.loadOrder()` and populates `sliceDependencies: Map<Artifact, Set<Artifact>>` from each `ResolvedSlice.dependencies()`.

#### 4.2 Activation Decision

When a slice reaches `LOADED`:

```
tryActivateIfDependenciesReady(sliceKey):
    dependencies = sliceDependencies.getOrDefault(artifact, {})
    if dependencies.isEmpty():
        issueActivateCommand(sliceKey)       // No dependencies, activate immediately
    else if allDependenciesActive(dependencies):
        issueActivateCommand(sliceKey)       // All deps have >= 1 ACTIVE instance
    else:
        // Wait -- activation will be triggered by activateDependentSlices()
```

#### 4.3 Cascade Activation

When a slice becomes `ACTIVE`, CDM calls `activateDependentSlices(activatedArtifact)`:

```
for each LOADED slice in sliceStates:
    if slice depends on activatedArtifact:
        tryActivateIfDependenciesReady(slice)
```

This creates a cascade: as each dependency activates, its dependents are checked and activated if all their dependencies are now satisfied.

#### 4.4 Load Order

`ExpandedBlueprint.loadOrder()` provides a topologically sorted list. Slices are loaded in dependency order, but activation is gated on actual runtime state, not just load order position.

### 5. Failure Handling

#### 5.1 Failure Classification

NDM writes `FAILED` state with:
- `failureReason`: Human-readable error message
- `fatal`: Boolean flag indicating deterministic (true) vs transient (false) failure

CDM reads these from `NodeArtifactValue` and dispatches accordingly.

#### 5.2 Deterministic (Fatal) Failures

- **Behavior**: Artifact added to `permanentlyFailed` set. No retry. `DeploymentFailed` event emitted.
- **ALL_OR_NOTHING mode**: Triggers `rollbackBlueprintForArtifact()` -- entire blueprint is rolled back.
- **Examples**: Class loading errors, incompatible API versions, missing required dependencies.

#### 5.3 Transient Failures

- **Retry strategy**: Exponential backoff. Delay = `min(2^(attempt-1), 30)` seconds.
- **Max retries**: 5 per (artifact, node) combination.
- **Counter key**: `artifact.asString()` in `retryCounters` map.
- **On max retries exceeded**: `DeploymentFailed` event emitted, no further retries. Reconciliation may re-attempt on a different node.

#### 5.4 CDM Failure Response

```
handleSliceFailure(sliceKey, sliceNodeValue):
    1. Remove from sliceStates and transitionalStateTimestamps
    2. Issue UNLOAD command (cleanup)
    3. If fatal:
         - Add to permanentlyFailed
         - Emit DeploymentFailed event
         - If ALL_OR_NOTHING: rollback blueprint
    4. If transient:
         - Increment retry counter
         - If retries <= MAX_RETRIES: schedule reconcile after backoff delay
         - If retries > MAX_RETRIES: emit DeploymentFailed, stop retrying
```

#### 5.5 Stuck Transitional Detection

CDM tracks `transitionalStateTimestamps: Map<SliceNodeKey, Long>`. During reconciliation, `detectStuckTransitionalStates()` checks:

```
isStuck = (now - enteredAt) > state.timeout * STUCK_TIMEOUT_MULTIPLIER    // multiplier = 2
```

Remediation by state:

| Stuck State | Action |
|-------------|--------|
| `LOADING` | Issue `UNLOAD`, reconciliation re-deploys |
| `ACTIVATING` | Issue `UNLOAD`, reconciliation re-deploys |
| `DEACTIVATING` | Force-remove `NodeArtifactKey` from KV-Store |
| `UNLOADING` | Force-remove `NodeArtifactKey` from KV-Store |

#### 5.6 NDM Consensus Write Retry

When NDM fails to write a state transition to consensus (timeout), it retries up to `CONSENSUS_MAX_RETRIES` (2) times with `CONSENSUS_OPERATION_TIMEOUT` (30s) per attempt. If all retries fail, the slice may become stuck and will be caught by CDM's stuck-transitional detection.

When NDM fails to write `FAILED` state specifically, it retries up to `MAX_TRANSITION_RETRIES` (5) times with `transitionRetryDelay` (2s) between attempts.

### 6. Quorum Loss and Restoration

#### 6.1 NDM Behavior on Quorum Loss (`DISAPPEARED`)

1. `ActiveNodeDeploymentState.suspendSlices()`:
   - For each `ACTIVE` deployment: unpublish HTTP routes (local), unregister from invocation handler
   - Does **NOT** unload slices from `SliceStore` (kept in memory)
   - Collects `SuspendedSlice` list
2. Transition to `DormantNodeDeploymentState(suspendedSlices)`
3. All subsequent `NodeArtifactKey` notifications are silently ignored

#### 6.2 NDM Behavior on Quorum Restoration (`ESTABLISHED`)

1. Create new `ActiveNodeDeploymentState` with fresh `deployments` map
2. Register `ON_DUTY` lifecycle state via consensus
3. Process pending `LOAD` commands from KV-Store snapshot (catches commands issued before NDM activated)
4. Reactivate suspended slices:
   - Re-register with invocation handler
   - Re-publish endpoints, HTTP routes, topic subscriptions, scheduled tasks
   - On reactivation failure: cleanup and remove from deployments

#### 6.3 CDM Behavior on Leader Loss

1. `Active.deactivate()`: Sets `deactivated` flag, cancels auto-heal timer, cancels reconcile timer
2. State transitions to `Dormant`
3. All stale scheduled callbacks check `deactivated` flag and no-op

#### 6.4 CDM Behavior on Leader Election

1. Deactivate previous `Active` state (if any)
2. Create new `Active` state with fresh maps
3. `rebuildStateFromKVStore()`:
   - Scan all KV entries, restore blueprints, slice targets, draining nodes, worker nodes
   - Rebuild slice states from `NodeArtifactKey` entries
   - Trigger activation for `LOADED` slices
   - Cleanup: stale node-routes, stale slice entries, stale node-artifact entries, orphaned slices
   - Resume drain evictions for nodes that were mid-drain
4. Run initial reconciliation
5. Start reconcile timer (30s periodic)
6. Start auto-heal (immediate if blueprints exist, cooldown if fresh cluster)

### 7. Reconciliation Algorithm

#### 7.1 Triggers

- **Periodic timer**: Every `reconcileInterval` (default 30s)
- **Topology changes**: `NodeAdded`, `NodeRemoved`, `NodeDown`
- **Slice removal**: After `handleSliceNodeRemoval()` (1s delay)
- **Transient failure retry**: After backoff delay
- **Batch consensus failure**: After 5s delay
- **DHT write failure**: After 5s delay
- **Blueprint removal**: After 5s delay
- **Worker node registration**: Immediately after `handleActivationDirectivePut()`

#### 7.2 Algorithm

```
reconcile():
    if deactivated: return

    for each blueprint in blueprints:
        artifact = blueprint.artifact
        if artifact in permanentlyFailed: skip

        desired = blueprint.instances
        current = getCurrentInstances(artifact)    // live states on active nodes

        if current.size != desired:
            emit ReconciliationAdjustment event
            issueAllocationCommands(artifact, desired)

    cleanupOrphanedSliceEntries()     // slices with no matching blueprint
    cleanupStaleNodeRoutes()          // routes for nodes not in topology
    detectStuckTransitionalStates()   // transitional states exceeding 2x timeout
```

#### 7.3 getCurrentInstances Filter

An instance is counted if:
1. Its `nodeId` is in the current active topology
2. Its state is a "live" state: anything except `FAILED`, `UNLOAD`, `UNLOADING`

#### 7.4 Orphaned Entry Cleanup

Slices whose artifact has no matching blueprint entry:
- If in `UNLOAD` or `UNLOADING`: just remove the `NodeArtifactKey`
- Otherwise: issue `UNLOAD` to trigger proper teardown

### 8. Blueprint Atomicity

#### 8.1 Modes

| Mode | Behavior |
|------|----------|
| `ALL_OR_NOTHING` (default) | Deterministic failure of any slice triggers rollback of entire blueprint |
| `BEST_EFFORT` | Each slice is independent; failures do not affect siblings |

Configured via `DeploymentAtomicity` enum, parsed from TOML config (case-insensitive, supports kebab-case).

#### 8.2 InFlightBlueprint Tracking

```
InFlightBlueprint:
    id: BlueprintId
    expanded: ExpandedBlueprint
    pendingSlices: Set<Artifact>      // slices not yet ACTIVE
    activeSlices: Set<Artifact>       // slices that reached ACTIVE
    previousBlueprint: Option<ExpandedBlueprint>   // for rollback
```

- Created when `AppBlueprintValue` is received (unless `BEST_EFFORT` or restoring)
- `pendingSlices` initialized from all slices in `expanded.loadOrder()`
- As slices reach `ACTIVE`, moved from `pendingSlices` to `activeSlices`
- When `pendingSlices` is empty: blueprint fully deployed, removed from tracking

#### 8.3 Rollback Protocol

On deterministic failure of any artifact in an in-flight blueprint:

1. **Skip if rolling update active**: Artifacts in `activeRoutings` are managed by rolling update, not atomicity
2. **No previous blueprint**: Unload all slices (pending + active), remove `AppBlueprintKey` and all `SliceTargetKey` entries via consensus
3. **Previous blueprint exists**: Re-submit previous `ExpandedBlueprint` via `AppBlueprintKey` consensus write. Mark `blueprintId` in `restoringBlueprints` set to suppress re-tracking. Clear after 5s.

### 9. Drain Eviction Protocol

#### 9.1 Trigger

Node lifecycle state changes to `DRAINING` (via `NodeLifecycleKey` put notification).

#### 9.2 Algorithm

```
startDrainEviction(drainingNode):
    add to drainingNodes set
    evictNextSliceFromNode(drainingNode)

evictNextSliceFromNode(drainingNode):
    if deactivated or not in drainingNodes: return
    slicesOnNode = live slices on drainingNode
    if empty:
        completeDrain(drainingNode)
        return
    pick first slice
    deployReplacementForDrain(sliceKey)

deployReplacementForDrain(originalKey):
    targetNodes = allocatableNodes excluding drainingNode
    allocate 1 instance on targetNodes
    if no node available: retry in 5s
    schedule checkReplacementAndUnload in 3s

checkReplacementAndUnload(originalKey):
    if replacement is ACTIVE on non-draining node:
        issue UNLOAD on originalKey
        schedule evictNextSliceFromNode in 2s
    else:
        recheck in 3s
```

#### 9.3 Eviction Properties

- **One slice at a time**: Prevents thundering herd on replacement nodes
- **Replacement verified**: Original is only unloaded after replacement reaches `ACTIVE`
- **Excluded from reconciliation**: `getCurrentInstances()` counts all live states, but draining-node slices are evicted outside the reconciliation loop
- **Cancellable**: If node returns to `ON_DUTY`, drain eviction is cancelled
- **Resumable**: On leader failover, `resumeDrainEvictions()` restarts eviction for nodes still in `DRAINING` state

#### 9.4 Drain Completion

When all slices are evacuated:
1. Write `DECOMMISSIONED` lifecycle state via consensus
2. If `ComputeProvider` is available: terminate the cloud instance

### 10. NDM Activation Chain

The activation sequence in NDM for a single slice follows a strict pipeline:

```
transitionTo(ACTIVATING)
    |
    v
activateSliceWithTimeout()          [SliceStore.activateSlice, configurable timeout]
    |
    v
registerSliceForInvocation()        [InvocationHandler registration]
    |
    v
publishTopicSubscriptions()         [TopicSubscriptionKey via consensus]
    |
    v
publishScheduledTasks()             [ScheduledTaskKey via consensus]
    |
    v
transitionTo(ACTIVE)
    |
    v
publishEndpointsAndRoutes()         [NodeArtifactKey + HttpRoutePublisher, parallel]
    |
    +-- overall chain timeout: activationChainTimeout (default 2m)
```

Endpoints and HTTP routes are published **after** `ACTIVE` transition to prevent traffic routing to a node still activating (avoids cold-start thundering herd).

On activation failure, cleanup reverses all registrations and publications, then transitions to `FAILED`.

### 11. KV-Store Key Reference

| Key Type | Written By | Purpose |
|----------|-----------|---------|
| `AppBlueprintKey` | CLI/API | Desired blueprint (expanded) |
| `SliceTargetKey` | CDM | Per-artifact target instance count |
| `NodeArtifactKey` | CDM + NDM | Primary CDM/NDM communication channel |
| `NodeLifecycleKey` | NDM + CDM | Node lifecycle state (ON_DUTY, DRAINING, DECOMMISSIONED, SHUTTING_DOWN) |
| `SchemaVersionKey` | Blueprint publish | Schema migration status per datasource |
| `SchemaMigrationLockKey` | SchemaOrchestratorService | Exclusive migration lock |
| `EndpointKey` | NDM | Published slice endpoints |
| `TopicSubscriptionKey` | NDM | Published topic subscriptions |
| `ScheduledTaskKey` | NDM | Published scheduled tasks |
| `NodeRoutesKey` | NDM | Published HTTP routes per node |
| `ActivationDirectiveKey` | CDM | Node role assignment (core/worker) |
| `WorkerSliceDirectiveKey` | CDM | Worker placement directives |
| `VersionRoutingKey` | Rolling update manager | Active rolling update marker |
| `GovernorAnnouncementKey` | Community governors | Community membership announcements |

## Code References

| Component | File |
|-----------|------|
| `SliceState` enum | `aether/slice/src/main/java/.../SliceState.java` |
| `ClusterDeploymentManager` | `aether/aether-deployment/src/main/java/.../cluster/ClusterDeploymentManager.java` |
| `NodeDeploymentManager` | `aether/aether-deployment/src/main/java/.../node/NodeDeploymentManager.java` |
| `SchemaOrchestratorService` | `aether/aether-deployment/src/main/java/.../schema/SchemaOrchestratorService.java` |

## References

- RFC-0004: Slice Packaging (superseded: deployment sections)
- RFC-0002: Dependency Protocol
- RFC-0005: Blueprint Format
