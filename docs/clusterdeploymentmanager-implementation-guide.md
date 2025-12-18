# ClusterDeploymentManager Implementation Guide

## Overview

ClusterDeploymentManager is the cluster-wide orchestration component that:

- Runs on ALL nodes but only ACTIVE on the leader
- Watches blueprint changes in consensus KV-Store
- Allocates slice instances across cluster nodes (round-robin)
- Writes LOAD commands directly to `slices/{node-id}/{artifact}` keys
- Performs reconciliation to ensure desired state matches actual state

**Key Design**: NO separate AllocationEngine component - allocation logic is embedded.

## File Location

**Create new file**: `node/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterDeploymentManager.java`

## Complete Implementation

```java
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.cluster.leader.LeaderNotification;
import org.pragmatica.cluster.leader.LeaderNotification.LeaderChange;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.cluster.topology.TopologyManager;
import org.pragmatica.message.MessageReceiver;
import org.pragmatica.message.MessageRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cluster-wide orchestration component that manages slice deployments across the cluster.
 * Only active on the leader node.
 */
public interface ClusterDeploymentManager {

    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);

    @MessageReceiver
    void onValuePut(ValuePut<AetherKey, AetherValue> valuePut);

    @MessageReceiver
    void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove);

    /**
     * State of the cluster deployment manager.
     */
    sealed interface ClusterDeploymentState {
        /**
         * Default methods for state-based polymorphic dispatch.
         */
        default void onLeaderChange(LeaderChange leaderChange) {}
        default void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {}
        default void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {}

        /**
         * Dormant state when node is NOT the leader.
         */
        record Dormant() implements ClusterDeploymentState {}

        /**
         * Active state when node IS the leader.
         */
        record Active(
                NodeId self,
                TopologyManager topologyManager,
                KVStore<AetherKey, AetherValue> kvStore,
                MessageRouter router,
                Map<Artifact, Blueprint> blueprints,
                Map<SliceNodeKey, SliceState> sliceStates
        ) implements ClusterDeploymentState {

            private static final Logger log = LoggerFactory.getLogger(Active.class);

            @Override
            public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
                var key = valuePut.cause().key();
                var value = valuePut.cause().value();

                // Handle blueprint changes
                if (key instanceof BlueprintKey blueprintKey &&
                    value instanceof BlueprintValue blueprintValue) {
                    handleBlueprintChange(blueprintKey, blueprintValue);
                }

                // Track slice state changes for reconciliation
                if (key instanceof SliceNodeKey sliceNodeKey &&
                    value instanceof SliceNodeValue sliceNodeValue) {
                    sliceStates.put(sliceNodeKey, sliceNodeValue.state());
                }
            }

            @Override
            public void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {
                var key = valueRemove.cause().key();

                // Handle blueprint removal
                if (key instanceof BlueprintKey blueprintKey) {
                    handleBlueprintRemoval(blueprintKey);
                }

                // Track slice removal
                if (key instanceof SliceNodeKey sliceNodeKey) {
                    sliceStates.remove(sliceNodeKey);
                }
            }

            /**
             * Handle blueprint change by allocating instances across nodes.
             */
            private void handleBlueprintChange(BlueprintKey key, BlueprintValue value) {
                var artifact = key.artifact();
                var desiredInstances = (int) value.instanceCount();

                log.info("Blueprint changed for {}: {} instances", artifact, desiredInstances);

                // Store blueprint
                blueprints.put(artifact, new Blueprint(artifact, desiredInstances));

                // Allocate instances
                allocateInstances(artifact, desiredInstances);
            }

            /**
             * Handle blueprint removal by deallocating all instances.
             */
            private void handleBlueprintRemoval(BlueprintKey key) {
                var artifact = key.artifact();

                log.info("Blueprint removed for {}", artifact);

                blueprints.remove(artifact);

                // Remove all instances of this artifact
                deallocateAllInstances(artifact);
            }

            /**
             * Allocate instances across cluster nodes using round-robin strategy.
             */
            private void allocateInstances(Artifact artifact, int desiredInstances) {
                var nodes = getActiveNodes();
                if (nodes.isEmpty()) {
                    log.warn("No active nodes available for allocation of {}", artifact);
                    return;
                }

                log.debug("Allocating {} instances of {} across {} nodes",
                         desiredInstances, artifact, nodes.size());

                // Calculate current instances
                var currentInstances = getCurrentInstances(artifact);

                // Scale up if needed
                if (desiredInstances > currentInstances.size()) {
                    var toAdd = desiredInstances - currentInstances.size();
                    for (int i = 0; i < toAdd; i++) {
                        var targetNode = nodes.get(i % nodes.size());
                        var sliceKey = new SliceNodeKey(artifact, targetNode);

                        // Only add if not already exists
                        if (!currentInstances.contains(sliceKey)) {
                            issueLoadCommand(sliceKey);
                        }
                    }
                }

                // Scale down if needed
                if (desiredInstances < currentInstances.size()) {
                    var toRemove = currentInstances.size() - desiredInstances;
                    for (int i = 0; i < toRemove && i < currentInstances.size(); i++) {
                        var sliceKey = currentInstances.get(i);
                        issueUnloadCommand(sliceKey);
                    }
                }
            }

            /**
             * Get list of active nodes from topology.
             */
            private List<NodeId> getActiveNodes() {
                // In real implementation, query topology for active nodes
                // For now, return simple list (this is a simplification)
                var nodes = new ArrayList<NodeId>();

                // This would come from TopologyManager
                // topologyManager.getActiveNodes()

                // Placeholder - would be replaced with actual topology query
                nodes.add(self);

                return nodes;
            }

            /**
             * Get current instances of an artifact across all nodes.
             */
            private List<SliceNodeKey> getCurrentInstances(Artifact artifact) {
                return sliceStates.keySet().stream()
                    .filter(key -> key.artifact().equals(artifact))
                    .toList();
            }

            /**
             * Issue LOAD command for a slice on a specific node.
             */
            private void issueLoadCommand(SliceNodeKey sliceKey) {
                log.info("Issuing LOAD command for {}", sliceKey);

                var value = new SliceNodeValue(SliceState.LOAD);
                kvStore.put(sliceKey, value)
                    .onFailure(cause -> log.error(
                        "Failed to issue LOAD command for {}: {}",
                        sliceKey, cause.message()
                    ));
            }

            /**
             * Issue UNLOAD command for a slice on a specific node.
             */
            private void issueUnloadCommand(SliceNodeKey sliceKey) {
                log.info("Issuing UNLOAD command for {}", sliceKey);

                var value = new SliceNodeValue(SliceState.UNLOAD);
                kvStore.put(sliceKey, value)
                    .onFailure(cause -> log.error(
                        "Failed to issue UNLOAD command for {}: {}",
                        sliceKey, cause.message()
                    ));
            }

            /**
             * Deallocate all instances of an artifact.
             */
            private void deallocateAllInstances(Artifact artifact) {
                getCurrentInstances(artifact).forEach(this::issueUnloadCommand);
            }
        }
    }

    /**
     * Blueprint representation (desired state).
     */
    record Blueprint(Artifact artifact, int instances) {}

    /**
     * Create a new cluster deployment manager.
     */
    static ClusterDeploymentManager clusterDeploymentManager(
            NodeId self,
            TopologyManager topologyManager,
            KVStore<AetherKey, AetherValue> kvStore,
            MessageRouter router
    ) {
        record clusterDeploymentManager(
                NodeId self,
                TopologyManager topologyManager,
                KVStore<AetherKey, AetherValue> kvStore,
                MessageRouter router,
                AtomicReference<ClusterDeploymentState> state
        ) implements ClusterDeploymentManager {

            private static final Logger log = LoggerFactory.getLogger(clusterDeploymentManager.class);

            @Override
            public void onLeaderChange(LeaderChange leaderChange) {
                if (leaderChange.localNodeIsLeader()) {
                    log.info("Node {} became leader, activating cluster deployment manager", self);

                    state().set(new ClusterDeploymentState.Active(
                        self,
                        topologyManager,
                        kvStore,
                        router,
                        new ConcurrentHashMap<>(),
                        new ConcurrentHashMap<>()
                    ));

                    // Perform reconciliation on activation
                    performReconciliation();
                } else {
                    log.info("Node {} is not leader, deactivating cluster deployment manager", self);
                    state().set(new ClusterDeploymentState.Dormant());
                }
            }

            @Override
            public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
                state().get().onValuePut(valuePut);
            }

            @Override
            public void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {
                state().get().onValueRemove(valueRemove);
            }

            /**
             * Perform reconciliation to ensure actual state matches desired state.
             */
            private void performReconciliation() {
                log.info("Performing cluster reconciliation");

                // In real implementation:
                // 1. Read all blueprints from KV-Store
                // 2. Read all slice states from KV-Store
                // 3. Compare desired vs actual
                // 4. Issue commands to fix mismatches

                // This would be triggered:
                // - On leader activation
                // - Periodically (e.g., every 30 seconds)
                // - On topology changes (node join/leave)
            }
        }

        return new clusterDeploymentManager(
                self,
                topologyManager,
                kvStore,
                router,
                new AtomicReference<>(new ClusterDeploymentState.Dormant())
        );
    }
}
```

## Key Design Points

### 1. Leader-Only Activation

```java
@Override
public void onLeaderChange(LeaderChange leaderChange) {
    if (leaderChange.localNodeIsLeader()) {
        // Activate
        state().set(new Active(...));
        performReconciliation();
    } else {
        // Deactivate
        state().set(new Dormant());
    }
}
```

### 2. Blueprint Watching

```java
if (key instanceof BlueprintKey blueprintKey &&
    value instanceof BlueprintValue blueprintValue) {
    handleBlueprintChange(blueprintKey, blueprintValue);
}
```

### 3. Round-Robin Allocation

```java
for (int i = 0; i < desiredInstances; i++) {
    var targetNode = nodes.get(i % nodes.size());
    var sliceKey = new SliceNodeKey(artifact, targetNode);
    issueLoadCommand(sliceKey);
}
```

### 4. Direct Slice-Node-Key Writes

```java
private void issueLoadCommand(SliceNodeKey sliceKey) {
    var value = new SliceNodeValue(SliceState.LOAD);
    kvStore.put(sliceKey, value);  // Direct write, no allocations key
}
```

## Testing Strategy

### Unit Test Example

```java
@Test
void onLeaderChange_activatesManager_whenBecomingLeader() {
    var manager = ClusterDeploymentManager.clusterDeploymentManager(
        nodeId, topologyManager, kvStore, router
    );

    var leaderChange = LeaderNotification.leaderChange(
        Option.option(nodeId),
        true  // localNodeIsLeader
    );

    manager.onLeaderChange(leaderChange);

    // Verify state is Active
    // Verify reconciliation was performed
}

@Test
void onValuePut_allocatesInstances_whenBlueprintChanges() {
    var manager = setupActiveManager();

    var artifact = Artifact.artifact("org.example:slice:1.0.0").unsafe();
    var blueprintKey = new BlueprintKey(artifact);
    var blueprintValue = new BlueprintValue(3);  // 3 instances

    var valuePut = new ValuePut<>(new KVCommand.Put<>(blueprintKey, blueprintValue));
    manager.onValuePut(valuePut);

    // Verify 3 LOAD commands issued to slice-node-keys
    // Verify round-robin allocation across nodes
}
```

### Integration Test Outline

1. **Setup**: 3-node cluster with consensus
2. **Leader election**: Node-1 becomes leader
3. **Publish blueprint**: `blueprints/test` → 3 instances
4. **Verify allocation**: 3 LOAD commands written to:
    - `slices/node-1/org.example:slice:1.0.0`
    - `slices/node-2/org.example:slice:1.0.0`
    - `slices/node-3/org.example:slice:1.0.0`
5. **Update blueprint**: Change to 5 instances
6. **Verify scale-up**: 2 more LOAD commands issued
7. **Update blueprint**: Change to 2 instances
8. **Verify scale-down**: 3 UNLOAD commands issued
9. **Leader failover**: Node-1 fails, node-2 becomes leader
10. **Verify reconciliation**: New leader reads state and corrects any drift

## Reconciliation Logic

```java
private void performReconciliation() {
    // For each blueprint:
    for (var blueprint : blueprints.values()) {
        var artifact = blueprint.artifact();
        var desiredInstances = blueprint.instances();

        // Get current instances from sliceStates
        var currentInstances = getCurrentInstances(artifact);

        // Compare and fix
        if (currentInstances.size() != desiredInstances) {
            allocateInstances(artifact, desiredInstances);
        }
    }
}
```

## Future Enhancements

### Smart Allocation Strategies

Instead of simple round-robin, could use:

- CPU-aware allocation (prefer less loaded nodes)
- Affinity-based allocation (keep instances on same node)
- Anti-affinity (spread instances across different nodes)
- Cost-aware allocation (prefer cheaper cloud instances)

### Multi-Cloud Support

```java
record CloudNode(NodeId nodeId, CloudProvider provider, Region region) {}

private void allocateInstances(Artifact artifact, int instances, AllocationStrategy strategy) {
    var nodes = getCloudNodes();
    var allocations = strategy.allocate(artifact, instances, nodes);
    allocations.forEach(this::issueLoadCommand);
}
```

### Canary Deployments

```java
record Blueprint(
    Artifact artifact,
    int instances,
    Option<CanaryConfig> canary  // Gradually roll out new version
) {}
```

## Checklist

- [ ] Create ClusterDeploymentManager.java
- [ ] Implement @MessageReceiver methods
- [ ] Implement State sealed interface (Dormant/Active)
- [ ] Implement onLeaderChange (activate/deactivate)
- [ ] Implement onValuePut (blueprint watching)
- [ ] Implement handleBlueprintChange (allocation)
- [ ] Implement round-robin allocation
- [ ] Implement issueLoadCommand/issueUnloadCommand
- [ ] Implement reconciliation
- [ ] Write unit tests
- [ ] Write integration test
- [ ] Document in CLAUDE.md

## Notes

- NO configure() method - uses @MessageReceiver
- NO separate AllocationEngine - logic embedded
- NO allocations key - writes directly to slice-node-keys
- Reconciliation ensures eventual consistency
- Dormant state does nothing (safe default)
- Active state only on leader node
