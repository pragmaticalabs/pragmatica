# Simplified KV-Store Schema (No Allocations)

## Analysis Summary

After reviewing the slice lifecycle document, we determined that the **allocations key is unnecessary** and creates an
intermediate layer that complicates the architecture without adding value.

## Current Simplified Schema

### Three Key Types Only

1. **Blueprint Keys**: `blueprints/{blueprint-name}`
    - Stores desired cluster configuration
    - Example: `blueprints/production` → `{"slices": [{"artifact": "org.example:slice:1.0.0", "instances": 3}]}`

2. **Slice State Keys**: `slices/{node-id}/{artifact}`
    - Stores slice state on each specific node
    - Example: `slices/node-1/org.example:slice:1.0.0` → `{"state": "ACTIVE", "timestamp": 1234567890}`

3. **Endpoint Keys**: `endpoints/{artifact}/{method-name}:{instance}`
    - Stores endpoint registrations for remote calls
    - Example: `endpoints/org.example:slice:1.0.0/process:1` → `{"node-id": "node-1", "instance": 1, "state": "ACTIVE"}`

## Why No Allocations Key?

### Original (Complex) Design

```
Blueprint → ClusterDeploymentManager
         → allocations/{artifact}/{node-id}
         → NodeDeploymentManager watches allocations
         → Updates slices/{node-id}/{artifact}
```

### Simplified Design

```
Blueprint → ClusterDeploymentManager
         → slices/{node-id}/{artifact} (directly)
         → NodeDeploymentManager watches slices/{node-id}/*
```

### Benefits of Elimination

1. **Fewer Keys**: Reduced KV-Store complexity (3 key types instead of 4)

2. **Fewer Writes**: One write operation instead of two
    - Before: Write to allocations, then write to slices
    - After: Write directly to slices

3. **No Intermediate State**: The allocation decision and the execution command are unified
    - ClusterDeploymentManager decides: "node-2 should run 2 instances of artifact X"
    - It immediately writes: `slices/node-2/org.example:slice:1.0.0` → `{"state": "LOAD", ...}`
    - NodeDeploymentManager on node-2 sees the LOAD command and executes it

4. **Blueprint Already Stores Total**: Blueprint says "3 instances total"
    - ClusterDeploymentManager distributes: node-1 (1 instance), node-2 (2 instances)
    - It writes these decisions directly as LOAD commands

5. **Slice State Captures Everything**: The slice-node-key already records:
    - Which node has the slice (part of the key)
    - What state the slice is in (LOADING, LOADED, ACTIVE, etc.)
    - This is sufficient for both allocation tracking AND lifecycle management

## Implementation Impact

### ClusterDeploymentManager

No separate AllocationEngine component needed. Allocation logic embedded:

```java
// Pseudocode for allocation
class ClusterDeploymentManager {
    void allocateBlueprint(Blueprint blueprint) {
        var artifact = blueprint.artifact();
        var totalInstances = blueprint.instances();

        // Round-robin allocation across available nodes
        var nodes = topology.getActiveNodes();
        for (int i = 0; i < totalInstances; i++) {
            var targetNode = nodes.get(i % nodes.size());
            var key = SliceStateKey.sliceStateKey(targetNode, artifact);
            var value = SliceNodeValue.sliceNodeValue(SliceState.LOAD);

            // Direct write - allocation decision IS the command
            kvStore.put(key, value);
        }
    }
}
```

### NodeDeploymentManager

No changes needed - already watches `slices/{node-id}/*` pattern:

```java
// Already implemented this way
class NodeDeploymentManager {
    void initialize() {
        // Watch for slice state changes on THIS node
        var pattern = SliceStateKey.forNode(nodeId);
        kvStore.watch(pattern, this::onSliceStateChange);
    }
}
```

## What About Reconciliation?

ClusterDeploymentManager can still perform reconciliation by:

1. **Reading current state**: Query all `slices/*` keys
2. **Comparing to blueprint**: Check if actual matches desired
3. **Issuing corrections**: Write LOAD/UNLOAD commands as needed

No need to track "what we allocated" separately - the current slice states ARE the allocations.

## Persistence Layer

**Current status**: No persistence layer exists.

**Ideal state**: No persistence layer needed.

**Rationale**:

- Consensus KV-Store IS the persistent state
- It already persists blueprints, slice states, and endpoints
- Node failures handled by consensus protocol
- Leader changes handled by deterministic leader selection
- State recovery automatic through consensus replay

The only "persistence" needed is:

1. **Consensus log** (already handled by Rabia protocol)
2. **Blueprint definitions** (already in KV-Store)
3. **Metrics sliding window** (in-memory on leader, 1-2 sec data loss acceptable on failover)

## Summary

By eliminating the allocations key:

- **Simpler KV schema**: 3 key types instead of 4
- **Fewer writes**: Direct allocation-to-command
- **No intermediate state**: Allocation decision IS the execution command
- **No separate component**: Allocation logic embedded in ClusterDeploymentManager
- **No persistence layer needed**: Consensus KV-Store is sufficient

This aligns perfectly with the slice lifecycle document, which references only:

- `slice-node-key` (our `slices/{node-id}/{artifact}`)
- `slice-instance-endpoint-key` (our `endpoints/{artifact}/{method-name}:{instance}`)

No allocations mentioned because they're not needed.
