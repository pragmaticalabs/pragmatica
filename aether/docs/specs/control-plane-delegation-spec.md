# Control Plane Task Delegation Specification

**Status:** Implementation-Ready
**Issue:** [#102](https://github.com/pragmatica-io/pragmatica/issues/102)
**Branch:** release-1.0.0-rc1
**Last Updated:** 2026-04-04

---

## 1. Overview

### 1.1 Problem

All control plane tasks run on the leader node, creating:
- ~100MB RSS overhead on the leader vs followers (441MB vs ~345MB)
- ~30 extra threads on the leader (88 vs ~57)
- Full control plane disruption on leader failover (all tasks cold-start on the new leader)

### 1.2 Solution

The leader becomes a **coordinator** that delegates task groups to follower nodes via KV-Store assignments. Each node watches for its own assignments and activates/deactivates component groups accordingly.

### 1.3 Design Principles

- **KV-Store is the single source of truth** for task assignments
- **Any node can propose** KV mutations (Rabia has no leader check)
- **State is reconstructible** from KV-Store on activation (same as current leader failover)
- **Dormant/Active pattern** already validated by CDM and LoadBalancerManager --- generalize it
- **Clean break** --- alpha→RC1 transition, no production users. Delegation replaces `onLeaderChange` entirely. No backward-compat toggle.
- **Activation feedback** --- `Promise<Unit>` return type enables failure reporting. Coordinator reassigns failed groups to alternate nodes.

---

## 2. Co-Location Groups

Components are delegated as **groups** (not individually) to avoid splitting tightly-coupled components.

### 2.1 Group Definitions

| ID | Group | Components | Module(s) | Threads | State Weight |
|----|-------|-----------|-----------|---------|-------------|
| `METRICS` | Metrics | MetricsScheduler, DeploymentMetricsScheduler | aether-metrics | 2 | Light |
| `SCALING` | Scaling | ControlLoop, TTMManager, RollbackManager | aether-control, aether-ttm | 3 | Medium-Heavy (ONNX model) |
| `STRATEGIES` | Deployment Strategies | RollingUpdateManager, CanaryDeploymentManager, BlueGreenDeploymentManager, AbTestManager | aether-invoke | 4-8 | Medium |
| `DEPLOYMENT` | Deployment | CDM, SchemaOrchestratorService, ClusterTopologyManager, NodeLifecycleManager, BlueprintService, LoadBalancerManager | aether-deployment | 10+ | Heavy |
| `STORAGE` | Storage | DemotionManager, StorageGarbageCollector | integrations/storage | 1-2 | Medium |
| `STREAMING` | Streaming Coordination | GovernorFailoverHandler, RetentionEnforcer | aether-stream | 1-2 | Medium |

**Total:** 6 groups across 15+ components.

### 2.2 Group Rationale

**METRICS:** Stateless schedulers. MetricsPing/Pong is cluster-wide --- the delegated node has the same metrics view as the leader.

**SCALING:** TTMManager feeds ControlLoop. RollbackManager watches ControlLoop scaling decisions. Breaking this group would require KV-Store indirection between tightly-coupled feedback loops.

**STRATEGIES:** All four strategy managers share `DeploymentStrategyCoordinator` for mutual exclusion. Local locks work only when co-located. Splitting across nodes would require distributed locking via KV-Store --- unnecessary complexity.

**DEPLOYMENT:** CDM directly calls SchemaOrchestratorService, ClusterTopologyManager, and BlueprintService. These share allocation state and reconciliation loops. LoadBalancerManager is included because it reconciles in response to CDM-driven route changes.

**STORAGE:** DemotionManager and StorageGarbageCollector share MetadataStore access. Both already use dormant/active lifecycle. [ASSUMPTION] These components are not yet wired in AetherNode --- delegation will be their first activation mechanism.

**STREAMING:** GovernorFailoverHandler coordinates governor assignments (leader-only responsibility). RetentionEnforcer manages segment expiry (must run on exactly one node). [ASSUMPTION] Neither is currently wired in AetherNode's leader change routing. Delegation provides the activation path.

### 2.3 Components NOT Delegated

| Component | Reason |
|-----------|--------|
| ConfigNotificationManager | Runs on **every** node (dispatches config changes to local slices). Not leader-only. |
| NodeDeploymentManager | Per-node slice lifecycle. Not leader-only. |
| ScheduledTaskManager (ALL mode) | `ALL`-mode timers run on every node. Only `SINGLE`-mode is leader-gated --- handled by its existing dual-mode logic, not by delegation. |
| AppHttpServer | Per-node HTTP serving. Not leader-only. |
| RabiaMetricsCollector | Role annotation only (leader/follower label). Not a task. |

---

## 3. New Types

### 3.1 TaskGroup Enum

```java
package org.pragmatica.aether.delegation;

/// Identifies a co-location group of control plane components.
/// Each group is assigned to exactly one core node at a time.
public enum TaskGroup {
    METRICS,
    SCALING,
    STRATEGIES,
    DEPLOYMENT,
    STORAGE,
    STREAMING
}
```

**Module:** `aether/slice` (shared type, visible to all modules).

### 3.2 KV Types

```java
package org.pragmatica.aether.slice.kvstore;

/// Key for task group assignment in the KV-Store.
record TaskAssignmentKey(TaskGroup taskGroup) implements AetherKey {
    private static final String PREFIX = "task-assignment/";

    @Override public String asString() {
        return PREFIX + taskGroup.name();
    }

    public static TaskAssignmentKey taskAssignmentKey(TaskGroup taskGroup) {
        return new TaskAssignmentKey(taskGroup);
    }

    public static Result<TaskAssignmentKey> taskAssignmentKey(String key) {
        // parse PREFIX + enum name
    }
}
```

```java
/// Value for task group assignment with 3-state lifecycle.
record TaskAssignmentValue(NodeId assignedTo, long assignedAtMs, AssignmentStatus status, String failureReason) implements AetherValue {

    public enum AssignmentStatus { ASSIGNED, ACTIVE, FAILED }

    public static TaskAssignmentValue taskAssignmentValue(NodeId assignedTo) {
        return new TaskAssignmentValue(assignedTo, System.currentTimeMillis(), AssignmentStatus.ASSIGNED, "");
    }

    public TaskAssignmentValue withStatus(AssignmentStatus newStatus) {
        return new TaskAssignmentValue(assignedTo, assignedAtMs, newStatus, failureReason);
    }

    public TaskAssignmentValue withFailure(String reason) {
        return new TaskAssignmentValue(assignedTo, assignedAtMs, AssignmentStatus.FAILED, reason);
    }
}
```

**3-state assignment lifecycle:**
```
Coordinator writes: ASSIGNED → Node activates (Promise<Unit>)
  ↓ success: Node updates → ACTIVE
  ↓ failure: Node updates → FAILED (with reason)
     ↓ Coordinator reconciliation sees FAILED → reassigns to next candidate
```

Both types must be added to the `AetherKey` and `AetherValue` sealed hierarchies. The `@Codec` annotation on the sealed interfaces auto-generates serialization for new permits.

### 3.3 DelegatedComponent Interface

```java
package org.pragmatica.aether.delegation;

/// Generalization of the Dormant/Active pattern for delegated control plane components.
/// Components implementing this interface can be activated/deactivated based on
/// KV-Store task assignments rather than direct leader election.
public interface DelegatedComponent {
    /// Called when this node is assigned the component's task group.
    /// Implementation must:
    /// 1. Reconstruct any in-memory state from KV-Store
    /// 2. Start scheduled tasks / timers
    /// 3. Begin processing notifications
    /// Returns failure if activation cannot proceed (e.g., missing dependencies,
    /// resource unavailable). Coordinator will reassign to another node.
    Promise<Unit> activate();

    /// Called when the task group is re-assigned away from this node.
    /// Implementation must:
    /// 1. Cancel scheduled tasks / timers
    /// 2. Drain in-flight work (best-effort, bounded timeout)
    /// 3. Persist any volatile state to KV-Store
    Promise<Unit> deactivate();

    /// The task group this component belongs to.
    TaskGroup taskGroup();

    /// Whether the component is currently active.
    boolean isActive();
}
```

**Module:** `aether/slice` (interface only; implementations in respective modules).

### 3.4 TaskAssignmentCoordinator

```java
package org.pragmatica.aether.delegation;

/// Leader-side component that manages task group assignments.
/// Writes TaskAssignmentKey/Value pairs to KV-Store.
/// Only active on the leader node (activated by onLeaderChange).
public interface TaskAssignmentCoordinator {
    @MessageReceiver void onLeaderChange(LeaderChange leaderChange);
    @MessageReceiver void onTopologyChange(TopologyChangeNotification topologyChange);

    /// Current assignment map (for observability).
    Map<TaskGroup, NodeId> assignments();

    /// Force re-assignment of a specific group (operator override).
    Result<Unit> reassign(TaskGroup group, NodeId target);
}
```

**Module:** `aether/aether-deployment` (alongside CDM, accesses ClusterNode for proposals).

### 3.5 TaskGroupActivator

```java
package org.pragmatica.aether.delegation;

/// Node-side component that watches TaskAssignmentKey notifications
/// and activates/deactivates DelegatedComponent instances.
/// Runs on EVERY core node.
public interface TaskGroupActivator {
    /// Register a DelegatedComponent for a given task group.
    void register(DelegatedComponent component);

    /// Called by KVNotificationRouter when a TaskAssignmentKey is put.
    void onTaskAssignmentPut(ValuePut<TaskAssignmentKey, TaskAssignmentValue> put);

    /// Called by KVNotificationRouter when a TaskAssignmentKey is removed.
    void onTaskAssignmentRemove(ValueRemove<TaskAssignmentKey, TaskAssignmentValue> remove);

    /// Fallback: if delegation is disabled, activate all registered components.
    void activateAll();

    /// Fallback: deactivate all registered components.
    void deactivateAll();
}
```

**Module:** `aether/aether-deployment` (alongside TaskAssignmentCoordinator).

---

## 4. Assignment Strategy

### 4.1 Round-Robin Assignment

On leader election, the coordinator:

1. Reads existing `TaskAssignmentKey` entries from KV-Store
2. Identifies **orphaned** assignments (assigned to nodes no longer in topology)
3. Identifies **unassigned** groups (no key present)
4. Assigns orphaned and unassigned groups via round-robin across healthy core nodes
5. Does NOT re-assign groups that are assigned to healthy nodes (sticky)

```
Algorithm: assignGroups(healthyCoreNodes, currentAssignments)
  orphaned = { g | g.assignedTo not in healthyCoreNodes }
  unassigned = { g | g not in currentAssignments }
  toAssign = orphaned + unassigned
  candidates = healthyCoreNodes sorted by current load (ascending)
  for each group in toAssign:
    target = candidates[index % candidates.size]
    write TaskAssignmentKey(group) -> TaskAssignmentValue(target)
    index++
```

**Load metric:** Number of groups currently assigned to a node. Ties broken by NodeId (deterministic).

### 4.2 Initial Election

On **first** leader election (fresh cluster, no existing assignments):

1. Leader assigns ALL groups to itself (matches current behavior)
2. In the **next reconciliation cycle** (after all nodes report healthy), leader redistributes groups

This ensures the cluster works immediately --- delegation happens gradually.

### 4.3 Reconciliation Cycle

The coordinator runs a reconciliation loop (same cadence as CDM, default 5s):

1. Read all `TaskAssignmentKey` entries
2. Compare against current healthy topology
3. Re-assign orphaned groups
4. Log warnings for groups with no healthy candidate

### 4.4 Node Departure

When a core node departs (NodeRemoved or NodeDown):

1. Coordinator identifies groups assigned to the departed node
2. Re-assigns to the least-loaded healthy core node
3. If no healthy follower is available, leader absorbs (fallback)

### 4.5 Leader Fallback

When `delegation.enabled = false` (default for backward compatibility):

- TaskAssignmentCoordinator is not instantiated
- TaskGroupActivator receives `onLeaderChange` directly and calls `activateAll()`/`deactivateAll()`
- Behavior is identical to current implementation

---

## 5. Activation Flow

### 5.1 Normal Flow (Delegation Enabled)

```
Leader elected
  |
  v
TaskAssignmentCoordinator.activate()
  |
  v
Read existing TaskAssignment keys from KV-Store
  |
  v
Compute assignment plan (round-robin, sticky)
  |
  v
Write TaskAssignmentKey/Value via consensus
  |
  v
KVNotificationRouter dispatches to ALL nodes
  |
  v
Each node's TaskGroupActivator checks: is this MY NodeId?
  |                                  |
  YES                                NO
  |                                  |
  v                                  v
  Activate DelegatedComponent        Deactivate (if was active)
```

### 5.2 Activation Sequence per Component

When `TaskGroupActivator` activates a component:

1. Call `component.activate()`
2. Component reconstructs state from KV-Store (same as current leader failover path)
3. Component starts timers / begins processing notifications
4. Log: `"Task group {} activated on node {}"` at INFO level

### 5.3 Deactivation Sequence

When `TaskGroupActivator` deactivates a component:

1. Call `component.deactivate()`
2. Component cancels timers, drains in-flight work (bounded 5s timeout)
3. Component persists volatile state to KV-Store (if any)
4. Component enters dormant state (all callbacks become no-ops)
5. Log: `"Task group {} deactivated on node {}"` at INFO level

### 5.4 Race Condition: Assignment During Deactivation

If a node receives a new assignment while deactivation is in progress:

- Deactivation completes first (bounded timeout guarantees this)
- Then activation proceeds
- Enforced by `AtomicReference<State>` with CAS transition (same pattern as CDM)

---

## 6. Component Migration Details

For each component, specify what changes for delegation.

### 6.1 METRICS Group

#### MetricsScheduler

| Aspect | Current | With Delegation |
|--------|---------|----------------|
| Activation | `onLeaderChange(isLeader)` | `DelegatedComponent.activate()` |
| State | Timer handle, node list | Reconstruct node list from TopologyManager (already available) |
| KV Writes | None | None |
| Deactivation | Cancel timer | Cancel timer |

**Changes:** Implement `DelegatedComponent`. Replace `onLeaderChange` body with delegation-aware activation. The existing leader change path becomes the fallback.

#### DeploymentMetricsScheduler

Same pattern as MetricsScheduler. Stateless timer + topology-derived node list.

### 6.2 SCALING Group

#### ControlLoop

| Aspect | Current | With Delegation |
|--------|---------|----------------|
| Activation | `onLeaderChange` --- starts evaluation timer | `DelegatedComponent.activate()` |
| State | Blueprint registry (ConcurrentHashMap), metric snapshots, community snapshots | Reconstruct from KV-Store (SliceTargetKey, NodeArtifactKey) + TopologyManager |
| KV Writes | Blueprint updates (scaling decisions) | Same --- any node can propose |
| Deactivation | Cancel timer, clear state | Cancel timer, clear maps |

**Changes:** Extract activation/deactivation into `DelegatedComponent` methods. The state reconstruction logic in `onLeaderChange` already does the right thing --- factor it into `activate()`.

#### TTMManager

| Aspect | Current | With Delegation |
|--------|---------|----------------|
| Activation | `onLeaderChange` --- loads ONNX model, starts inference timer | `DelegatedComponent.activate()` |
| State | ONNX model (10-20MB), last forecast, aggregator reference | Model loaded on activation (same as now). Aggregator is shared via constructor injection. |
| KV Writes | None (feeds ControlLoop locally) | Same. Co-located with ControlLoop, so local feeding continues. |
| Deactivation | Stop inference timer | Stop timer, release model reference |

**Critical constraint:** TTMManager MUST be co-located with ControlLoop. They communicate via direct method call (`onForecast` callback). The SCALING group ensures this.

#### RollbackManager

| Aspect | Current | With Delegation |
|--------|---------|----------------|
| Activation | `onLeaderChange` --- sets `isLeader` flag | `DelegatedComponent.activate()` |
| State | Previous version map, rollback counts, cooldown timestamps (all ConcurrentHashMaps) | Reconstruct from KV-Store (PreviousVersionKey). Rollback counts and cooldowns are volatile --- lost on transfer, which is acceptable (conservative: resets cooldown). |
| KV Writes | Blueprint rollback updates | Same |
| Deactivation | Clear flag, clear maps | Same |

### 6.3 STRATEGIES Group

#### RollingUpdateManager, CanaryDeploymentManager, BlueGreenDeploymentManager, AbTestManager

All four follow identical patterns:

| Aspect | Current | With Delegation |
|--------|---------|----------------|
| Activation | `onLeaderChange` --- restores in-flight deployments from KV-Store | `DelegatedComponent.activate()` |
| State | Active deployment map (ConcurrentHashMap of `Deployment`) | Reconstructed from `DeploymentKey`/`DeploymentValue` in KV-Store (existing `restoreState()` logic) |
| KV Writes | Deployment progress, version routing updates | Same |
| Deactivation | Clear active deployments map | Same |

**Changes for each:**
1. Implement `DelegatedComponent` returning `TaskGroup.STRATEGIES`
2. Factor `restoreState()` into `activate()`
3. Factor cleanup into `deactivate()`
4. Keep `onLeaderChange` as fallback path (delegates to activate/deactivate when delegation is disabled)

**DeploymentStrategyCoordinator:** No changes. Mutual exclusion via local `ConcurrentHashMap` continues to work because all four managers are co-located in the STRATEGIES group.

### 6.4 DEPLOYMENT Group

#### ClusterDeploymentManager (CDM)

| Aspect | Current | With Delegation |
|--------|---------|----------------|
| Activation | `onLeaderChange` --- creates `Active` state record with full state reconstruction | `DelegatedComponent.activate()` |
| State | Blueprint map, slice states, allocation index, draining nodes, in-flight blueprints, retry counters, transitional timestamps, community governors. All in `Active` record. | Same reconstruction from KV-Store. The `Active` record constructor already handles this. |
| KV Writes | SliceNodeKey, ActivationDirectiveKey, WorkerSliceDirectiveKey, etc. | Same |
| Deactivation | Switch to `Dormant` record | Same --- `Dormant` record's default methods are all no-ops |

**CDM already implements the Dormant/Active pattern.** Changes:
1. Implement `DelegatedComponent` interface (wraps existing state transition)
2. `activate()` = current leader-true path
3. `deactivate()` = current leader-false path
4. Add `taskGroup()` returning `TaskGroup.DEPLOYMENT`

#### SchemaOrchestratorService, ClusterTopologyManager, NodeLifecycleManager, BlueprintService

These are **sub-components of CDM**, activated by CDM's `Active` state --- not by leader change directly.

**No changes needed.** When CDM activates, it creates/activates sub-components as it does today. When CDM deactivates, sub-components are implicitly deactivated.

#### LoadBalancerManager

| Aspect | Current | With Delegation |
|--------|---------|----------------|
| Activation | `onLeaderChange` --- creates `Active` state with reconcile | `DelegatedComponent.activate()` |
| State | Route-to-node map, tracked IPs | Reconstructed from `NodeRoutesKey` in KV-Store (existing `reconcile()` logic) |
| KV Writes | None (calls LoadBalancerProvider SPI) | Same |
| Deactivation | Switch to `Dormant` | Same |

**Already implements Dormant/Active.** Add `DelegatedComponent` interface.

### 6.5 STORAGE Group

#### DemotionManager

| Aspect | Current | With Delegation |
|--------|---------|----------------|
| Activation | `activate()` already exists (dormant/active interface) | Wire through `DelegatedComponent` |
| State | Demotion stats (volatile, acceptable to lose) | No KV-Store reconstruction needed |
| Deactivation | `deactivate()` already exists | Same |

**Already has activate/deactivate.** Wrap in `DelegatedComponent` adapter. [ASSUMPTION] Not yet wired in AetherNode --- delegation provides the wiring mechanism.

#### StorageGarbageCollector

Same pattern as DemotionManager. Already has activate/deactivate. Wrap in `DelegatedComponent` adapter.

### 6.6 STREAMING Group

#### GovernorFailoverHandler

| Aspect | Current | With Delegation |
|--------|---------|----------------|
| Activation | Not currently wired to leader change | `DelegatedComponent.activate()` starts watching for governor departure events |
| State | ReplicaRegistry reference, StreamPartitionRecovery reference | Constructor-injected, stateless operation |
| Deactivation | No-op (stateless) | No-op |

**Changes:** Create a `StreamingCoordinator` component that implements `DelegatedComponent` for `TaskGroup.STREAMING`. This coordinator manages GovernorFailoverHandler lifecycle and RetentionEnforcer scheduling.

#### RetentionEnforcer

| Aspect | Current | With Delegation |
|--------|---------|----------------|
| Activation | `start()` called during node setup | `DelegatedComponent.activate()` calls `start()` |
| State | Timer handle | Timer handle, canceled on deactivation |
| Deactivation | `close()` | `close()` |

**Already has start/close lifecycle.** The `StreamingCoordinator` calls `start()` on activation, `close()` on deactivation.

---

## 7. Configuration

### 7.1 TOML Configuration

```toml
[delegation]
reconcile-interval = 5 # Seconds between assignment reconciliation cycles
```

Delegation is always enabled (clean break from alpha). No toggle needed.

**Module:** `aether/config`

---

## 8. Wiring in AetherNode

### 8.1 Current Wiring (Leader Change Routing)

```java
// Current: direct leader change routing
entries.add(route(LeaderChange.class, clusterDeploymentManager::onLeaderChange));
entries.add(route(LeaderChange.class, metricsScheduler::onLeaderChange));
entries.add(route(LeaderChange.class, controlLoop::onLeaderChange));
// ... etc for each component
```

### 8.2 New Wiring (Delegation-Aware)

```java
var activator = TaskGroupActivator.taskGroupActivator(config.self());

// Register all delegated components
activator.register(clusterDeploymentManager);  // DEPLOYMENT
activator.register(loadBalancerManager);        // DEPLOYMENT
activator.register(metricsScheduler);           // METRICS
activator.register(deploymentMetricsScheduler); // METRICS
activator.register(controlLoop);                // SCALING
activator.register(ttmManager);                 // SCALING
activator.register(rollbackManager);            // SCALING
activator.register(rollingUpdateManager);       // STRATEGIES
activator.register(canaryManager);              // STRATEGIES
activator.register(blueGreenManager);           // STRATEGIES
activator.register(abTestManager);              // STRATEGIES
activator.register(storageDelegation);          // STORAGE (adapter)
activator.register(streamingCoordinator);       // STREAMING

// Delegation is always on (clean break from alpha)
var coordinator = TaskAssignmentCoordinator.taskAssignmentCoordinator(
    config.self(), clusterNode, kvStore, topologyManager);

// Only the coordinator listens to leader change
entries.add(route(LeaderChange.class, coordinator::onLeaderChange));
entries.add(route(TopologyChangeNotification.NodeAdded.class, coordinator::onTopologyChange));
entries.add(route(TopologyChangeNotification.NodeRemoved.class, coordinator::onTopologyChange));
entries.add(route(TopologyChangeNotification.NodeDown.class, coordinator::onTopologyChange));

// Every node watches task assignments via KV notifications
kvRouterBuilder.onPut(TaskAssignmentKey.class, activator::onTaskAssignmentPut);
kvRouterBuilder.onRemove(TaskAssignmentKey.class, activator::onTaskAssignmentRemove);

// Components that still need direct leader change notification regardless
entries.add(route(LeaderChange.class,
    change -> rabiaMetricsCollector.updateRole(change.localNodeIsLeader(),
        change.leaderId().map(NodeId::id))));
```

### 8.3 Topology/KV Notification Pass-Through

Components that receive topology and KV notifications (e.g., CDM receives `onAppBlueprintPut`, ControlLoop receives `onSliceTargetPut`) continue to receive them **on all nodes**. The Dormant/Active pattern ensures these callbacks are no-ops when the component is dormant.

No changes to notification routing are needed --- the existing per-component notification wiring stays as-is.

---

## 9. Observability

### 9.1 REST API

#### GET /api/cluster/tasks

Returns current task group assignments.

**Response:**
```json
{
    "delegationEnabled": true,
    "assignments": [
        {
            "group": "METRICS",
            "assignedTo": "node-2",
            "assignedAt": "2026-04-04T10:30:00Z",
            "active": true
        },
        {
            "group": "SCALING",
            "assignedTo": "node-3",
            "assignedAt": "2026-04-04T10:30:00Z",
            "active": true
        },
        {
            "group": "STRATEGIES",
            "assignedTo": "node-1",
            "assignedAt": "2026-04-04T10:30:01Z",
            "active": true
        },
        {
            "group": "DEPLOYMENT",
            "assignedTo": "node-1",
            "assignedAt": "2026-04-04T10:30:01Z",
            "active": true
        },
        {
            "group": "STORAGE",
            "assignedTo": "node-2",
            "assignedAt": "2026-04-04T10:30:01Z",
            "active": true
        },
        {
            "group": "STREAMING",
            "assignedTo": "node-3",
            "assignedAt": "2026-04-04T10:30:01Z",
            "active": true
        }
    ]
}
```

**When delegation disabled:**
```json
{
    "delegationEnabled": false,
    "assignments": []
}
```

#### PUT /api/cluster/tasks/{group}/reassign

Operator override to force-reassign a task group.

**Request:**
```json
{ "targetNode": "node-2" }
```

**Response:** `200 OK` or `409 Conflict` (if target node is not healthy).

### 9.2 CLI

```
aether cluster tasks              # Show assignment map
aether cluster tasks reassign     # Force re-assignment
  --group SCALING                 # Task group name
  --target node-2                 # Target node ID
```

### 9.3 Dashboard

Add a **Task Delegation** panel to the cluster view:
- Table: Group | Node | Assigned At | Status (Active/Pending/Orphaned)
- Color coding: green = active, yellow = pending activation, red = orphaned

### 9.4 Metrics

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `aether_delegation_assignment_total` | Counter | `group`, `node` | Total assignment events |
| `aether_delegation_activation_duration_ms` | Histogram | `group` | Time from assignment write to component active |
| `aether_delegation_deactivation_duration_ms` | Histogram | `group` | Time from re-assignment to component dormant |
| `aether_delegation_orphaned_total` | Counter | `group` | Orphaned assignments detected |
| `aether_delegation_fallback_total` | Counter | `group` | Leader absorbed group (no healthy follower) |

---

## 10. Failure Scenarios

### 10.1 Follower Hosting a Group Dies

1. SWIM detects node down, fires `NodeDown` notification
2. TaskAssignmentCoordinator receives `onTopologyChange`
3. Coordinator reads assignments, identifies orphaned group(s)
4. Coordinator re-assigns to least-loaded healthy core node
5. New host's TaskGroupActivator receives KV notification, activates component group
6. **Impact:** Only the affected group(s) experience brief downtime (~1-3s)

### 10.2 Leader Dies

1. New leader elected
2. TaskAssignmentCoordinator activates on new leader
3. Coordinator reads existing TaskAssignment keys from KV-Store
4. Non-orphaned groups continue running on their assigned nodes (no disruption)
5. Only the DEPLOYMENT group (if assigned to old leader) needs re-assignment
6. **Impact:** Only groups hosted by the old leader experience downtime

### 10.3 Network Partition (Minority Side)

1. Nodes on minority side lose quorum
2. Quorum loss notification stops processing on minority nodes
3. Majority side elects new leader (if leader was in minority)
4. Coordinator on majority side re-assigns orphaned groups
5. **Impact:** Groups on majority side continue; minority side stalls until quorum restored

### 10.4 All Followers Unhealthy

1. Coordinator detects no healthy followers
2. Coordinator assigns all groups to leader (self)
3. Behavior identical to current (pre-delegation) state
4. When followers recover, coordinator redistributes in next reconciliation cycle

### 10.5 Rapid Leader Flapping

1. Leader assigns groups, loses leadership, regains it
2. New leader reads existing assignments (sticky)
3. If assignments are to healthy nodes, no changes needed
4. **No assignment churn** because of sticky policy

---

## 11. Testing Strategy

### 11.1 Unit Tests

**TaskAssignmentCoordinator:**
- `assignGroups_freshCluster_allToLeader()` --- initial assignment
- `assignGroups_existingAssignments_stickyToHealthyNodes()` --- re-election, no churn
- `assignGroups_deadNode_reassignsOrphaned()` --- node departure
- `assignGroups_noHealthyFollower_leaderAbsorbs()` --- fallback
- `assignGroups_roundRobin_distributesEvenly()` --- load balancing
- `reconcile_orphanedGroup_reassigned()` --- reconciliation cycle

**TaskGroupActivator:**
- `onAssignment_ownNode_activatesComponent()` --- normal activation
- `onAssignment_otherNode_deactivatesComponent()` --- deactivation
- `onAssignment_alreadyActive_noOp()` --- idempotent
- `onRemove_wasActive_deactivates()` --- cleanup
- `activateAll_delegationDisabled_fallback()` --- backward compat

**DelegatedComponent adapters (per component):**
- `activate_reconstructsState()` --- state recovery
- `deactivate_cancelsTimers()` --- cleanup
- `activate_afterDeactivate_worksCorrectly()` --- full cycle

### 11.2 Integration Tests

**Delegation flow (3-node cluster, delegation enabled):**
- Verify assignments written to KV-Store after leader election
- Verify components activate on assigned nodes (check via metrics/API)
- Kill assigned node, verify re-assignment and activation on new node
- Kill leader, verify non-disrupted groups continue on their nodes

**Backward compatibility (delegation disabled):**
- ALL existing integration tests pass unchanged
- Verify `onLeaderChange` fallback activates all groups on leader

### 11.3 Regression

Every existing integration test suite (12 suites, 183+ tests) MUST pass with delegation disabled (default). This is the baseline. Tests with delegation enabled are additive.

---

## 12. Implementation Order

### Layer 1: Foundation Types

**Files:**
- `aether/slice/src/.../delegation/TaskGroup.java` --- enum
- `aether/slice/src/.../delegation/DelegatedComponent.java` --- interface
- `aether/slice/src/.../kvstore/AetherKey.java` --- add `TaskAssignmentKey` permit
- `aether/slice/src/.../kvstore/AetherValue.java` --- add `TaskAssignmentValue` permit
- `aether/config/src/.../DelegationConfig.java` --- config record

**Gate:** Compiles. Unit tests for KV key parsing.

### Layer 2: Coordinator and Activator

**Files:**
- `aether/aether-deployment/src/.../delegation/TaskAssignmentCoordinator.java`
- `aether/aether-deployment/src/.../delegation/TaskGroupActivator.java`

**Gate:** Unit tests for assignment algorithm, activation dispatch.

### Layer 3: Adapt Existing Components (METRICS group first)

**Files:**
- `aether/aether-metrics/src/.../MetricsScheduler.java` --- implement `DelegatedComponent`
- `aether/aether-metrics/src/.../DeploymentMetricsScheduler.java` --- implement `DelegatedComponent`

**Gate:** Unit tests. Build passes. METRICS components activate/deactivate correctly.

### Layer 4: Adapt SCALING Group

**Files:**
- `aether/aether-control/src/.../ControlLoop.java` --- implement `DelegatedComponent`
- `aether/aether-ttm/src/.../TTMManager.java` --- implement `DelegatedComponent`
- `aether/aether-control/src/.../RollbackManager.java` --- implement `DelegatedComponent`

**Gate:** Unit tests. Build passes. Co-location verified (TTM feeds ControlLoop).

### Layer 5: Adapt STRATEGIES Group

**Files:**
- `aether/aether-invoke/src/.../update/RollingUpdateManager.java`
- `aether/aether-invoke/src/.../update/CanaryDeploymentManager.java`
- `aether/aether-invoke/src/.../update/BlueGreenDeploymentManager.java`
- `aether/aether-invoke/src/.../update/AbTestManager.java`

**Gate:** Unit tests. Mutual exclusion via DeploymentStrategyCoordinator still works.

### Layer 6: Adapt DEPLOYMENT Group

**Files:**
- `aether/aether-deployment/src/.../cluster/ClusterDeploymentManager.java`
- `aether/aether-deployment/src/.../loadbalancer/LoadBalancerManager.java`

**Gate:** Unit tests. CDM sub-component activation chain verified.

### Layer 7: Adapt STORAGE Group

**Files:**
- New `DelegatedStorageAdapter` wrapping DemotionManager + StorageGarbageCollector

**Gate:** Unit tests. Dormant/active lifecycle verified.

### Layer 8: Adapt STREAMING Group

**Files:**
- New `StreamingCoordinator` implementing `DelegatedComponent` for `TaskGroup.STREAMING`
- Wraps GovernorFailoverHandler + RetentionEnforcer lifecycle

**Gate:** Unit tests.

### Layer 9: AetherNode Wiring

**Files:**
- `aether/node/src/.../AetherNode.java` --- delegation-aware routing
- `aether/node/src/.../AetherNodeConfig.java` --- add DelegationConfig
- TOML config parsing updates

**Gate:** Full build passes. All 12 existing integration suites pass with delegation DISABLED.

### Layer 10: Observability

**Files:**
- `aether/api/src/.../TaskRoutes.java` --- REST endpoint
- `aether/cli/src/.../AetherCli.java` --- CLI command
- Dashboard panel updates
- `aether/docs/reference/management-api.md` --- API docs
- `aether/docs/reference/cli.md` --- CLI docs

**Gate:** API returns correct data. CLI formats output.

### Layer 11: Integration Tests (Delegation Enabled)

**Files:**
- New integration test suite for delegation flow
- Verify assignment, re-assignment, fallback, observability

**Gate:** All tests pass. Feature complete.

---

## 13. Open Questions

| # | Question | Status |
|---|----------|--------|
| 1 | ~~Should delegation be enabled by default?~~ | [DECIDED: Always on] --- clean break from alpha, no production users. No toggle. |
| 2 | Should `ScheduledTaskManager` SINGLE-mode tasks be delegated separately or stay with their current leader-gated logic? | [DECISION: Keep current logic] --- SINGLE-mode is a per-task flag, not a group-level concern. Delegation adds no value here. |
| 3 | Should the coordinator support priority-based assignment (e.g., DEPLOYMENT group prefers nodes with more memory)? | [DEFERRED] --- Start with round-robin. Add memory-aware assignment in a future iteration if profiling shows benefit. |
| 4 | DemotionManager and StorageGarbageCollector are in `integrations/storage` (not aether). How do they wire to KV-Store for delegation? | [ANSWER: Via adapter in aether-storage module] --- `DelegatedStorageAdapter` in aether wraps the integrations/storage interfaces. |

---

## References

### Internal References

- [Investigation Document](../internal/control-plane-delegation-investigation.md) --- architecture assessment confirming feasibility
- [CDM Source](../../aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterDeploymentManager.java) --- Dormant/Active pattern reference
- [LoadBalancerManager Source](../../aether-deployment/src/main/java/org/pragmatica/aether/deployment/loadbalancer/LoadBalancerManager.java) --- Dormant/Active pattern reference
- [AetherNode Source](../../node/src/main/java/org/pragmatica/aether/node/AetherNode.java) --- current wiring and leader change routing
- [KVNotificationRouter](../../../../integrations/cluster/src/main/java/org/pragmatica/cluster/state/kvstore/KVNotificationRouter.java) --- key-based dispatch mechanism
- [GovernorFailoverHandler](../../aether-stream/src/main/java/org/pragmatica/aether/stream/replication/GovernorFailoverHandler.java) --- streaming governor failover
- [RetentionEnforcer](../../aether-stream/src/main/java/org/pragmatica/aether/stream/segment/RetentionEnforcer.java) --- segment retention enforcement
- [DemotionManager](../../../../integrations/storage/src/main/java/org/pragmatica/storage/DemotionManager.java) --- AHSE tier demotion
- [StorageGarbageCollector](../../../../integrations/storage/src/main/java/org/pragmatica/storage/StorageGarbageCollector.java) --- AHSE garbage collection

### Architecture References

- [Architecture Overview](../architecture/00-overview.md)
- [Consensus Architecture](../architecture/01-consensus.md) --- Rabia protocol, no leader check on proposals
- [Hierarchical Storage Spec](../specs/hierarchical-storage-spec.md) --- AHSE storage tiers
- [Streaming Spec](../specs/streaming-spec.md) --- streaming subsystem
