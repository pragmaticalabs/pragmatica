package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeAdded;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent.DeploymentStarted;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.cluster.state.kvstore.KVStore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cluster-wide orchestration component that manages slice deployments across the cluster.
 * Only active on the leader node.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Watch for blueprint changes (desired state)</li>
 *   <li>Allocate slice instances across nodes (round-robin)</li>
 *   <li>Write LOAD commands directly to slice-node-keys</li>
 *   <li>Perform reconciliation to ensure actual state matches desired state</li>
 * </ul>
 *
 * <p>Design notes:
 * <ul>
 *   <li>NO separate allocations key - writes directly to slice-node-keys</li>
 *   <li>NO separate AllocationEngine - allocation logic embedded here</li>
 *   <li>Reconciliation handles topology changes and leader failover</li>
 * </ul>
 */
public interface ClusterDeploymentManager {
    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);

    @MessageReceiver
    void onValuePut(ValuePut<AetherKey, AetherValue> valuePut);

    @MessageReceiver
    void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove);

    @MessageReceiver
    void onTopologyChange(TopologyChangeNotification topologyChange);

    /**
     * State of the cluster deployment manager.
     */
    sealed interface ClusterDeploymentState {
        default void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {}

        default void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {}

        default void onTopologyChange(TopologyChangeNotification topologyChange) {}

        /**
         * Dormant state when node is NOT the leader.
         */
        record Dormant() implements ClusterDeploymentState {}

        /**
         * Active state when node IS the leader.
         */
        record Active(
        NodeId self,
        ClusterNode<KVCommand<AetherKey>> cluster,
        KVStore<AetherKey, AetherValue> kvStore,
        MessageRouter router,
        Map<Artifact, Blueprint> blueprints,
        Map<SliceNodeKey, SliceState> sliceStates,
        List<NodeId> activeNodes) implements ClusterDeploymentState {
            private static final Logger log = LoggerFactory.getLogger(Active.class);

            /**
             * Rebuild state from KVStore snapshot on leader activation.
             * This ensures the new leader has complete knowledge of desired and actual state.
             */
            void rebuildStateFromKVStore() {
                log.info("Rebuilding cluster deployment state from KVStore");
                kvStore.snapshot()
                       .forEach((key, value) -> {
                                    switch (key) {
                    case BlueprintKey blueprintKey when value instanceof BlueprintValue blueprintValue -> {
                                    var artifact = blueprintKey.artifact();
                                    var instances = (int) blueprintValue.instanceCount();
                                    blueprints.put(artifact,
                                                   new Blueprint(artifact, instances));
                                    log.debug("Restored blueprint: {} with {} instances", artifact, instances);
                                }
                    case SliceNodeKey sliceNodeKey when value instanceof SliceNodeValue sliceNodeValue -> {
                                    sliceStates.put(sliceNodeKey,
                                                    sliceNodeValue.state());
                                    log.debug("Restored slice state: {} = {}",
                                              sliceNodeKey,
                                              sliceNodeValue.state());
                                }
                    default -> {}
                }
                                });
                log.info("Restored {} blueprints and {} slice states from KVStore",
                         blueprints.size(),
                         sliceStates.size());
            }

            @Override
            public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
                var key = valuePut.cause()
                                  .key();
                var value = valuePut.cause()
                                    .value();
                switch (key) {
                    case BlueprintKey blueprintKey when value instanceof BlueprintValue blueprintValue ->
                    handleBlueprintChange(blueprintKey, blueprintValue);
                    case SliceNodeKey sliceNodeKey when value instanceof SliceNodeValue sliceNodeValue ->
                    trackSliceState(sliceNodeKey, sliceNodeValue.state());
                    default -> {}
                }
            }

            @Override
            public void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {
                var key = valueRemove.cause()
                                     .key();
                switch (key) {
                    case BlueprintKey blueprintKey -> handleBlueprintRemoval(blueprintKey);
                    case SliceNodeKey sliceNodeKey -> sliceStates.remove(sliceNodeKey);
                    default -> {}
                }
            }

            @Override
            public void onTopologyChange(TopologyChangeNotification topologyChange) {
                switch (topologyChange) {
                    case NodeAdded(_, List<NodeId> topology) -> {
                        updateTopology(topology);
                        reconcile();
                    }
                    case NodeRemoved(NodeId removedNode, List<NodeId> topology) -> {
                        updateTopology(topology);
                        handleNodeRemoval(removedNode);
                        reconcile();
                    }
                    default -> {}
                }
            }

            private void updateTopology(List<NodeId> topology) {
                activeNodes.clear();
                activeNodes.addAll(topology);
            }

            private void handleBlueprintChange(BlueprintKey key, BlueprintValue value) {
                var artifact = key.artifact();
                var desiredInstances = (int) value.instanceCount();
                log.info("Blueprint changed for {}: {} instances", artifact, desiredInstances);
                blueprints.put(artifact, new Blueprint(artifact, desiredInstances));
                allocateInstances(artifact, desiredInstances);
            }

            private void handleBlueprintRemoval(BlueprintKey key) {
                var artifact = key.artifact();
                log.info("Blueprint removed for {}", artifact);
                blueprints.remove(artifact);
                deallocateAllInstances(artifact);
            }

            private void trackSliceState(SliceNodeKey sliceKey, SliceState state) {
                sliceStates.put(sliceKey, state);
            }

            private void handleNodeRemoval(NodeId removedNode) {
                // Remove state entries for the removed node
                var keysToRemove = sliceStates.keySet()
                                              .stream()
                                              .filter(key -> key.nodeId()
                                                                .equals(removedNode))
                                              .toList();
                keysToRemove.forEach(sliceStates::remove);
                log.info("Removed {} slice states for departed node {}", keysToRemove.size(), removedNode);
            }

            /**
             * Allocate instances across cluster nodes using round-robin strategy.
             */
            private void allocateInstances(Artifact artifact, int desiredInstances) {
                if (activeNodes.isEmpty()) {
                    log.warn("No active nodes available for allocation of {}", artifact);
                    return;
                }
                var currentInstances = getCurrentInstances(artifact);
                var currentCount = currentInstances.size();
                log.debug("Allocating {} instances of {} (current: {}) across {} nodes",
                          desiredInstances,
                          artifact,
                          currentCount,
                          activeNodes.size());
                // Scale up if needed
                if (desiredInstances > currentCount) {
                    scaleUp(artifact, desiredInstances - currentCount, currentInstances);
                }
                // Scale down if needed
                if (desiredInstances < currentCount) {
                    scaleDown(artifact, currentCount - desiredInstances, currentInstances);
                }
            }

            private void scaleUp(Artifact artifact, int toAdd, List<SliceNodeKey> existingInstances) {
                // Find nodes without instances first, then allow duplicates if needed
                var nodesWithInstances = existingInstances.stream()
                                                          .map(SliceNodeKey::nodeId)
                                                          .toList();
                var added = 0;
                // First pass: allocate to nodes without instances
                for (var node : activeNodes) {
                    if (added >= toAdd) {
                        break;
                    }
                    if (!nodesWithInstances.contains(node)) {
                        var sliceKey = new SliceNodeKey(artifact, node);
                        if (!sliceStates.containsKey(sliceKey)) {
                            issueLoadCommand(sliceKey);
                            added++ ;
                        }
                    }
                }
                // Second pass: if still need more, allocate additional instances (round-robin)
                // This handles cases where instances > nodes
                var nodeIndex = 0;
                while (added < toAdd) {
                    var targetNode = activeNodes.get(nodeIndex % activeNodes.size());
                    var sliceKey = new SliceNodeKey(artifact, targetNode);
                    // Note: In current design, one slice per node per artifact
                    // For multiple instances per node, we'd need instance numbering
                    // For now, just issue the command (will be idempotent if already exists)
                    issueLoadCommand(sliceKey);
                    added++ ;
                    nodeIndex++ ;
                }
            }

            private void scaleDown(Artifact artifact, int toRemove, List<SliceNodeKey> existingInstances) {
                // Remove from the end (LIFO to maintain round-robin balance)
                var toRemoveKeys = existingInstances.stream()
                                                    .skip(Math.max(0,
                                                                   existingInstances.size() - toRemove))
                                                    .toList();
                toRemoveKeys.forEach(this::issueUnloadCommand);
            }

            private List<SliceNodeKey> getCurrentInstances(Artifact artifact) {
                return sliceStates.keySet()
                                  .stream()
                                  .filter(key -> key.artifact()
                                                    .equals(artifact))
                                  .toList();
            }

            private void issueLoadCommand(SliceNodeKey sliceKey) {
                log.info("Issuing LOAD command for {}", sliceKey);
                // Emit deployment started event for metrics via MessageRouter
                var timestamp = System.currentTimeMillis();
                router.route(new DeploymentStarted(sliceKey.artifact(), sliceKey.nodeId(), timestamp));
                var value = new SliceNodeValue(SliceState.LOAD);
                var command = new KVCommand.Put<AetherKey, AetherValue>(sliceKey, value);
                cluster.apply(List.of(command))
                       .onFailure(cause -> log.error("Failed to issue LOAD command for {}: {}",
                                                     sliceKey,
                                                     cause.message()));
            }

            private void issueUnloadCommand(SliceNodeKey sliceKey) {
                log.info("Issuing UNLOAD command for {}", sliceKey);
                var value = new SliceNodeValue(SliceState.UNLOAD);
                var command = new KVCommand.Put<AetherKey, AetherValue>(sliceKey, value);
                cluster.apply(List.of(command))
                       .onFailure(cause -> log.error("Failed to issue UNLOAD command for {}: {}",
                                                     sliceKey,
                                                     cause.message()));
            }

            private void deallocateAllInstances(Artifact artifact) {
                getCurrentInstances(artifact)
                .forEach(this::issueUnloadCommand);
            }

            /**
             * Reconcile desired state (blueprints) with actual state (slice states).
             * Called on leader activation, topology changes, etc.
             */
            void reconcile() {
                log.info("Performing cluster reconciliation");
                for (var blueprint : blueprints.values()) {
                    var artifact = blueprint.artifact();
                    var desiredInstances = blueprint.instances();
                    var currentInstances = getCurrentInstances(artifact);
                    if (currentInstances.size() != desiredInstances) {
                        log.info("Reconciliation: {} has {} instances, desired {}",
                                 artifact,
                                 currentInstances.size(),
                                 desiredInstances);
                        allocateInstances(artifact, desiredInstances);
                    }
                }
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
    static ClusterDeploymentManager clusterDeploymentManager(NodeId self,
                                                             ClusterNode<KVCommand<AetherKey>> cluster,
                                                             KVStore<AetherKey, AetherValue> kvStore,
                                                             MessageRouter router) {
        record clusterDeploymentManager(
        NodeId self,
        ClusterNode<KVCommand<AetherKey>> cluster,
        KVStore<AetherKey, AetherValue> kvStore,
        MessageRouter router,
        AtomicReference<ClusterDeploymentState> state) implements ClusterDeploymentManager {
            private static final Logger log = LoggerFactory.getLogger(clusterDeploymentManager.class);

            @Override
            public void onLeaderChange(LeaderChange leaderChange) {
                if (leaderChange.localNodeIsLeader()) {
                    log.info("Node {} became leader, activating cluster deployment manager", self);
                    var activeState = new ClusterDeploymentState.Active(
                    self,
                    cluster,
                    kvStore,
                    router,
                    new ConcurrentHashMap<>(),
                    new ConcurrentHashMap<>(),
                    new CopyOnWriteArrayList<>());
                    state.set(activeState);
                    // Rebuild state from KVStore and reconcile
                    activeState.rebuildStateFromKVStore();
                    activeState.reconcile();
                }else {
                    log.info("Node {} is not leader, deactivating cluster deployment manager", self);
                    state.set(new ClusterDeploymentState.Dormant());
                }
            }

            @Override
            public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
                state.get()
                     .onValuePut(valuePut);
            }

            @Override
            public void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {
                state.get()
                     .onValueRemove(valueRemove);
            }

            @Override
            public void onTopologyChange(TopologyChangeNotification topologyChange) {
                state.get()
                     .onTopologyChange(topologyChange);
            }
        }
        return new clusterDeploymentManager(
        self, cluster, kvStore, router, new AtomicReference<>(new ClusterDeploymentState.Dormant()));
    }
}
