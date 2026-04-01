# Control Plane Task Delegation: Investigation

## Status: Investigation Complete
## Target: Post-GA (1.x optimization)
## Last Updated: 2026-03-31

---

## 1. Problem Statement

All control plane tasks currently run on the leader node, creating:
- ~100MB RSS overhead on leader vs followers (441MB vs ~345MB)
- ~30 extra threads on leader (88 vs ~57)
- Full control plane disruption on leader failover (all tasks cold-start on new leader)

**Proposed change:** Leader becomes a coordinator/scheduler, not an executor. Control plane tasks are delegated to follower nodes via KV-Store assignments.

---

## 2. Component Inventory

### 2.1 Primary Leader-Only Components

| # | Component | Module | Threads | State Weight | KV Writes | Delegatable |
|---|-----------|--------|---------|-------------|-----------|-------------|
| 1 | ClusterDeploymentManager | aether-deployment | 1-2 | Heavy | Yes | WITH CHANGES |
| 2 | ControlLoop | aether-control | 1 | Medium | Yes | YES |
| 3 | MetricsScheduler | aether-metrics | 1 | Light | No | YES |
| 4 | DeploymentMetricsScheduler | aether-metrics | 1 | Light | No | YES |
| 5 | TTMManager | aether-ttm | 1 | Medium | No | YES |
| 6 | RollingUpdateManager | aether-invoke | 1-2 | Medium | Yes | YES |
| 7 | CanaryDeploymentManager | aether-invoke | 1-2 | Medium | Yes | YES |
| 8 | BlueGreenDeploymentManager | aether-invoke | 1-2 | Medium | Yes | YES |
| 9 | AbTestManager | aether-invoke | 1-2 | Medium | Yes | YES |
| 10 | ScheduledTaskManager | aether-invoke | N | Light/task | Yes | YES |
| 11 | RollbackManager | aether-control | 1 | Light | Yes | YES |
| 12 | LoadBalancerManager | aether-deployment | 0-1 | Medium | No | YES |

### 2.2 CDM Sub-Components (activated by CDM, not independently)

- **SchemaOrchestratorService** — migration coordination, distributed locking
- **ClusterTopologyManager** — node provisioning, auto-healing
- **NodeLifecycleManager** — cloud instance lifecycle operations
- **BlueprintService** — blueprint CRUD

### 2.3 Partial Leader-Only

- **ScheduledTaskManager** — dual trigger (leader + quorum). `SINGLE` mode tasks are leader-only, `ALL` mode tasks run on every node. Already validates the delegation pattern.

---

## 3. Architecture Assessment

### 3.1 Delegation Infrastructure Already Exists

The Aether architecture **already supports** the proposed KV-Store delegation pattern. No changes to core infrastructure needed.

| Capability | Status | Details |
|-----------|--------|---------|
| Non-leader KV proposals | ✅ | `RabiaEngine.apply()` has no leader check — any active node can propose |
| Worker → core forwarding | ✅ | `ForwardingClusterNode` forwards mutations from workers to core |
| Typed key-based dispatch | ✅ | `KVNotificationRouter` filters by key type on all nodes |
| Dormant/Active pattern | ✅ | CDM and LoadBalancerManager already use sealed Dormant/Active interface |
| State machine snapshots | ✅ | New nodes receive full KV-Store state on join |

### 3.2 The Refactoring Is Mechanical

The Dormant/Active pattern already used by CDM and LoadBalancerManager is exactly what delegation needs — just change the trigger:

```
Current:  onLeaderChange(isLeader) → if isLeader then activate() else deactivate()
Proposed: onTaskAssignment(taskId, nodeId) → if nodeId == self then activate() else deactivate()
```

### 3.3 No Component Is Inherently Leader-Only

Rabia has no designated proposer — any node can submit proposals through consensus. The "leader" in Aether is a coordination role, not a consensus role. Every component can run on any core node.

---

## 4. Delegatability Classification

### DELEGATABLE (no changes needed)

- **MetricsScheduler** — stateless, reads topology, pings nodes, collects responses
- **DeploymentMetricsScheduler** — same pattern as MetricsScheduler
- **ControlLoop** — reads metrics + KV state, writes scaling decisions through consensus
- **TTMManager** — loads ONNX model, runs inference, feeds ControlLoop
- **RollbackManager** — monitors deployment health, triggers rollback via KV write
- **LoadBalancerManager** — reads routes from KV, calls LoadBalancerProvider SPI

### DELEGATABLE WITH MINOR REFACTORING

- **RollingUpdateManager** — tracks in-flight updates in ConcurrentHashMap. On transfer, new owner reconstructs from KV-Store (same as current leader failover, scoped to one component).
- **CanaryDeploymentManager** — same pattern
- **BlueGreenDeploymentManager** — same pattern
- **AbTestManager** — same pattern
- **ScheduledTaskManager** — timer lifecycle management. SINGLE-mode timers must be re-created on activation.

### DELEGATABLE WITH SIGNIFICANT REFACTORING

- **ClusterDeploymentManager** — heaviest component, most coupled. Directly calls SchemaOrchestratorService, ClusterTopologyManager, BlueprintService. Holds deployment allocation state, reconciliation loop, topology tracking. Delegation requires either co-locating all sub-components or making sub-component calls go through KV-Store/messaging.

---

## 5. Co-Location Groups

Some components interact heavily and should be delegated as a unit:

| Group | Components | Reason |
|-------|-----------|--------|
| **Deployment** | CDM + SchemaOrchestrator + TopologyManager + BlueprintService | Direct method calls, shared state |
| **Scaling** | ControlLoop + TTMManager + RollbackManager | TTM feeds ControlLoop, RollbackManager watches ControlLoop |
| **Deployment Strategies** | RollingUpdate + Canary + BlueGreen + AbTest | Mutual exclusion via DeploymentStrategyCoordinator |
| **Metrics** | MetricsScheduler + DeploymentMetricsScheduler | Independent, splitting is low-value |
| **Storage** | DemotionManager + StorageGarbageCollector | Shared MetadataStore access, demotion/GC coordination |

**Recommendation:** Delegate at group granularity (5 groups), not individual components. Avoids splitting tightly-coupled components and reduces assignment coordination overhead.

**Note (v0.25.0):** Storage group components already use dormant/active lifecycle and persistence abstractions (WatermarkStore, TombstoneStore, ReplicaAssignmentStore). These are injection points for KV-Store wiring — delegation will be mechanical.

---

## 6. Memory Attribution Estimate

| Category | Estimate | Source |
|----------|----------|--------|
| Thread stacks | ~30MB | ~30 threads × 1MB default stack |
| CDM data structures | ~20-30MB | Deployment maps, allocation tracking, topology caches |
| ONNX model (TTMManager) | ~10-20MB | ML model loaded in memory |
| Metrics aggregation | ~5-10MB | Cluster-wide metric snapshots |
| Update managers (4×) | ~5-10MB each | In-flight deployment tracking |
| Remaining | ~10-20MB | Miscellaneous caches, buffers |

Thread stacks are likely the largest contributor. Delegating 15-20 threads to other nodes saves ~15-20MB RSS immediately.

**Note:** These are code-analysis estimates, not profiling data. Actual profiling on a running leader node is recommended before implementation.

---

## 7. Design Considerations

### 7.1 Assignment Mechanism

```java
// New KV types
record TaskAssignmentKey(String taskGroupId) implements AetherKey { }
record TaskAssignmentValue(NodeId assignedTo, Instant assignedAt) implements AetherValue { }
```

- Leader writes assignments on election and when nodes join/leave
- Every node watches `TaskAssignmentKey` for its own node ID
- Activation: node sees its ID → activates component group
- Deactivation: assignment changes → deactivates component group
- Leader death: new leader reads existing assignments, re-assigns only dead node's orphaned tasks
- Fallback: if no healthy node available, leader absorbs (current behavior)

### 7.2 Assignment Strategy

- **Round-robin by group** — simplest, distributes 4 groups across core nodes
- **Memory-aware** — assign to node with lowest RSS. Goal is memory distribution, not CPU.
- **Sticky** — prefer keeping assignments stable across leader elections to avoid unnecessary churn

Recommendation: start with round-robin, add memory-awareness later.

### 7.3 Failure Handling

| Scenario | Current Behavior | With Delegation |
|----------|-----------------|----------------|
| Leader dies | All tasks cold-start on new leader | New leader re-assigns only orphaned tasks (1-2 KV writes). Non-orphaned tasks continue uninterrupted. |
| Follower dies | No impact on control plane | Leader re-assigns dead node's tasks to another follower. Only that group restarts. |
| Network partition | Leader-only tasks stall if leader isolated | Delegated tasks on majority side continue. Only orphaned assignments need recovery. |

### 7.4 Observability

- `GET /api/cluster/tasks` — show assignment map (group → node)
- `aether cluster tasks` — CLI equivalent
- Dashboard: task assignment visualization in cluster view
- Metrics: per-group activation count, delegation latency

### 7.5 Things to Watch Out For

1. **Metrics broadcast already reaches all nodes** — MetricsPing/Pong is cluster-wide. ControlLoop on a follower has the same metrics as on the leader. No additional forwarding needed.

2. **DeploymentStrategyCoordinator mutual exclusion** — Rolling/Canary/BlueGreen/AbTest use a coordinator for mutual exclusion. If split across nodes, coordination must go through KV-Store instead of local locks. Keeping all 4 in one group avoids this.

3. **Initial election race** — On fresh cluster start, leader must run locally first. Delegation happens in second reconciliation cycle once all nodes are healthy.

4. **CDM reconciliation ordering** — CDM reads multiple KV keys, computes diffs, writes assignments. Running on a non-leader node adds no extra latency because KV reads are local (all nodes have the full KV-Store state via consensus replication).

---

## 8. Pre-GA Recommendations

These changes cost nothing today but keep the delegation door open:

### 8.1 Convention: Dormant/Active Pattern for All New Leader Components

All new leader-activated components should use the Dormant/Active sealed interface pattern (or a generalized `DelegatedComponent` interface). This makes retrofitting delegation mechanical.

**Current components using this pattern:** CDM, LoadBalancerManager, DemotionManager, StorageGarbageCollector.
**Components that should adopt it:** ControlLoop, MetricsScheduler, DeploymentMetricsScheduler, TTMManager, RollbackManager, all deployment strategy managers.

**New components already following this pattern (v0.25.0):**
- `DemotionManager` — dormant/active lifecycle, demote() no-ops when dormant
- `StorageGarbageCollector` — dormant/active lifecycle, collectGarbage() no-ops when dormant
- `ReplicaRegistry` — persistence abstractions (WatermarkStore, ReplicaAssignmentStore) for KV-Store reconstruction
- `TombstoneRegistry` — TombstoneStore abstraction for KV-Store persistence
- `SegmentIndex` — rebuildFromRefs() for reconstruction from storage metadata

### 8.2 No New Direct Cross-Component Method Calls

Avoid adding new direct method calls between leader-only components. Prefer KV-Store notifications for inter-component communication. This makes future separation trivial.

### 8.3 State Must Be Reconstructible from KV-Store

Any in-memory state held by leader-only components must be reconstructible from KV-Store on activation. This is already true for leader failover — delegation doesn't add new requirements, just scopes reconstruction to individual components.

---

## 9. Proposed Delegation Order (1.x)

| Phase | Groups | Effort | Impact |
|-------|--------|--------|--------|
| 1 | Metrics (MetricsScheduler + DeploymentMetricsScheduler) | Small | ~2 threads, validates pattern |
| 2 | Scaling (ControlLoop + TTMManager + RollbackManager) | Medium | ~3 threads, ~20-30MB (ONNX model) |
| 3 | Deployment Strategies (Rolling + Canary + BlueGreen + AbTest) | Medium | ~4-8 threads, ~20MB |
| 4 | Deployment (CDM + sub-components) | Large | ~10+ threads, ~30MB. Highest value but most complex. |

---

## 10. Persistence Abstraction Pattern (v0.25.0)

New components use `@FunctionalInterface` persistence abstractions with NOOP defaults, wired to KV-Store in the node module:

```java
// Pattern: functional interface with NOOP default
@FunctionalInterface
public interface WatermarkStore {
    void persistWatermark(String streamName, int partition, NodeId nodeId, long confirmedOffset);
    WatermarkStore NOOP = (_, _, _, _) -> {};
}
```

| Abstraction | Component | Purpose |
|-------------|-----------|---------|
| `WatermarkStore` | ReplicaRegistry | Persist replica confirmed offsets on every ack |
| `ReplicaAssignmentStore` | ReplicaRegistry | Persist replica-to-partition assignments |
| `TombstoneStore` | TombstoneRegistry | Persist GC tombstones for grace period tracking |

These abstractions make the components KV-Store-ready without coupling them to the KV-Store implementation. Node module provides the actual KV-Store-backed implementations at wiring time.

---

## References

- [Passive Worker Pools Spec](../specs/passive-worker-pools-spec.md) — two-layer topology, governor architecture
- [Architecture Overview](../architecture/00-overview.md) — cluster architecture
- [Consensus Architecture](../architecture/01-consensus.md) — Rabia protocol, KV-Store
- [Hierarchical Storage Spec](../specs/hierarchical-storage-spec.md) — AHSE, storage tiers, metadata store
