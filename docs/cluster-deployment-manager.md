# ClusterDeploymentManager Design

> **⚠️ OUTDATED DOCUMENT**: This document contains an older design with a separate AllocationEngine component and
> allocations keys in KV-Store.
>
> **Current simplified design**:
> - No separate AllocationEngine (allocation logic embedded in ClusterDeploymentManager)
> - No allocations keys (ClusterDeploymentManager writes directly to `slices/{node-id}/{artifact}`)
> - See [architecture-overview.md](architecture-overview.md) and [kv-schema-simplified.md](kv-schema-simplified.md) for
    current design
>
> This document is kept for historical reference but should not be used for implementation.

---

## Overview

ClusterDeploymentManager is the cluster-wide orchestration component responsible for managing slice deployments across
the entire Aether cluster. It operates using a leader-follower model where only the leader node actively manages
cluster-wide deployments.

## Architecture

### Component Responsibilities

- **Blueprint Monitoring**: Watch for blueprint changes in consensus KV-Store
- **Allocation Management**: Decide how to distribute slice instances across cluster nodes
- **Reconciliation**: Ensure actual cluster state matches desired state from blueprints
- **Rebalancing**: Handle node failures and topology changes automatically

### Leadership Model

ClusterDeploymentManager integrates with the cluster module's LeaderManager:

```java
// Leadership is managed externally by LeaderManager
// ClusterDeploymentManager reacts to leadership notifications

@MessageReceiver
void onLeaderChange(LeaderNotification.LeaderChange leaderChange) {
    if (leaderChange.localNodeIsLeader()) {
        activateAsLeader();    // Start cluster management duties
    } else {
        deactivateAsFollower(); // Become dormant
    }
}
```

**Leadership Selection**:

- Deterministic: First node in cluster topology becomes leader
- Automatic failover when leader node fails
- No custom election logic needed

### State Model

```java
sealed interface ClusterDeploymentState {
    // Inactive state when not leader
    record DormantClusterManager() implements ClusterDeploymentState {}
    
    // Active state when leader
    record ActiveClusterManager(
        NodeId self,
        BlueprintWatcher blueprintWatcher,
        AllocationEngine allocationEngine, 
        ReconciliationEngine reconciler,
        KVStore kvStore
    ) implements ClusterDeploymentState {}
}
```

## Core Components

### BlueprintWatcher

**Purpose**: Monitor blueprint changes in KV-Store and trigger allocation updates

**Key Behavior**:

- Watches `blueprints/*` keys for ValuePut/ValueRemove events
- Parses blueprint content and validates format
- Triggers allocation engine when blueprints change
- Handles blueprint deletion by cleaning up allocations

**Implementation Pattern**:

```java
@MessageReceiver
void onValuePut(ValuePut<?, ?> valuePut) {
    if (isBlueprintKey(valuePut.key())) {
        Blueprint.parse(valuePut.value())
                 .onSuccess(blueprint -> allocationEngine.updateAllocations(blueprint))
                 .onFailure(cause -> log.warn("Invalid blueprint: {}", cause.message()));
    }
}
```

### AllocationEngine

**Purpose**: Decide how to distribute slice instances across cluster nodes

**Simple Strategy (Phase 1)**:

- Round-robin distribution of instances across available nodes
- Exact instance count matching (no ranges)
- Even distribution when possible

**Allocation Algorithm**:

```java
List<AllocationDecision> allocateInstances(Artifact artifact, int desiredInstances) {
    var availableNodes = topology.getActiveNodes();
    var decisions = new ArrayList<AllocationDecision>();
    
    // Simple round-robin distribution
    for (int i = 0; i < desiredInstances; i++) {
        var targetNode = availableNodes.get(i % availableNodes.size());
        decisions.add(new AllocationDecision(targetNode, 1, "round-robin"));
    }
    
    return decisions;
}
```

**Future Enhancements**:

- AI-based allocation using decision trees
- Resource-aware placement (CPU, memory constraints)
- Affinity/anti-affinity rules
- Load balancing considerations

### ReconciliationEngine

**Purpose**: Ensure cluster state consistency, especially during leader transitions

**Reconciliation Scenarios**:

1. **Leader Activation**: New leader reconciles entire cluster state
2. **Node Failures**: Redistribute instances from failed nodes
3. **Topology Changes**: Rebalance when nodes added/removed
4. **State Drift**: Detect and fix allocation mismatches

**Reconciliation Process**:

```java
Promise<Unit> reconcileClusterState() {
    return getCurrentBlueprints()
        .flatMap(this::getCurrentAllocations)
        .flatMap(this::getCurrentSliceStates)
        .flatMap(this::calculateRequiredChanges)
        .flatMap(this::applyAllocationChanges)
        .onSuccess(_ -> log.info("Reconciliation completed successfully"))
        .onFailure(cause -> log.error("Reconciliation failed: {}", cause.message()));
}
```

**Reconciliation Strategies**:

- **Conservative**: Only fix clear mismatches, minimal disruption
- **Aggressive**: Full rebalancing, optimal distribution
- **Configurable**: Strategy selection via configuration

## KV-Store Integration

### Blueprint Keys

```
blueprints/{blueprint-name} → Blueprint JSON
```

### Allocation Keys

```
allocations/{artifact}/{node-id} → AllocationValue JSON
```

**Allocation Workflow**:

1. ClusterDeploymentManager reads blueprint
2. AllocationEngine calculates desired distribution
3. Allocation decisions written to `allocations/*` keys
4. NodeDeploymentManager on each node reads allocation changes
5. NodeDeploymentManager triggers slice lifecycle operations

## Error Handling

### Node Failure Scenarios

**Leader Node Fails**:

- LeaderManager automatically selects new leader
- New leader performs full reconciliation
- Minimal disruption to running slices

**Worker Node Fails**:

- Leader detects node failure via topology notifications
- Instances from failed node redistributed to healthy nodes
- Automatic rebalancing maintains desired instance counts

### Blueprint Validation

```java
Result<Blueprint> validateBlueprint(Blueprint blueprint) {
    // Validate artifact format
    // Check instance counts > 0
    // Verify no duplicate artifacts
    // Validate against cluster capacity (future)
}
```

### Timeout Handling

- Blueprint processing timeouts
- Allocation operation timeouts
- Reconciliation timeouts with configurable backoff

## Integration Points

### NodeDeploymentManager Integration

ClusterDeploymentManager → Allocation Keys → NodeDeploymentManager

```java
// ClusterDeploymentManager writes:
allocations/org.example:slice:1.0.0/node-1 → {"assigned": 2}

// NodeDeploymentManager reads and creates:  
slices/node-1/org.example:slice:1.0.0 → {"state": "LOADING"}
```

### LeaderManager Integration

```java
static ClusterDeploymentManager create(NodeId self, MessageRouter router, KVStore kvStore) {
    var manager = new ClusterDeploymentManagerImpl(self, kvStore);
    
    // Register for LeaderManager notifications
    var mutableRouter = (MessageRouter.MutableRouter) router;
    mutableRouter.addRoute(LeaderNotification.LeaderChange.class, manager::onLeaderChange);
    mutableRouter.addRoute(ValuePut.class, manager::onValuePut);
    mutableRouter.addRoute(ValueRemove.class, manager::onValueRemove);
    
    return manager;
}
```

## Configuration

```java
record ClusterDeploymentConfiguration(
    ReconciliationStrategy reconciliationStrategy,
    AllocationStrategy allocationStrategy,
    Duration blueprintProcessingTimeout,
    Duration reconciliationInterval,
    boolean enableAutoRebalancing
) {
    static ClusterDeploymentConfiguration defaultConfiguration() {
        return new ClusterDeploymentConfiguration(
            ReconciliationStrategy.CONSERVATIVE,
            AllocationStrategy.ROUND_ROBIN,
            Duration.ofMinutes(2),
            Duration.ofMinutes(5),
            true
        );
    }
}
```

## Testing Strategy

### Unit Tests

- AllocationEngine algorithm testing
- Blueprint parsing and validation
- Reconciliation logic verification
- Error handling scenarios

### Integration Tests

- Leader activation/deactivation
- Blueprint change handling
- Node failure scenarios
- Full reconciliation cycles

### Test Infrastructure

```java
// Mock LeaderManager for controlled leadership testing
// In-memory KV-Store for state verification
// Controlled topology changes for rebalancing tests
```

## Implementation Phases

### Phase 1: Basic Infrastructure

- ClusterDeploymentManager skeleton
- LeaderNotification integration
- Basic blueprint watching
- Simple round-robin allocation

### Phase 2: Core Functionality

- Complete reconciliation engine
- Node failure handling
- Blueprint validation
- Error recovery

### Phase 3: Advanced Features

- AI-based allocation strategies
- Resource-aware placement
- Advanced reconciliation policies
- Comprehensive monitoring

## Open Questions

1. **Reconciliation Frequency**: How often should leader perform reconciliation checks?

2. **Allocation Granularity**: Should we support instance ranges (min/max) in addition to exact counts?

3. **Resource Constraints**: When should we add CPU/memory limits to allocation decisions?

4. **Conflict Resolution**: How should we handle concurrent blueprint updates?

5. **Monitoring Integration**: What metrics should ClusterDeploymentManager expose?