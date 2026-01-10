package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
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
        record Active(NodeId self,
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
                       .forEach(this::processKVEntry);
                log.info("Restored {} blueprints and {} slice states from KVStore",
                         blueprints.size(),
                         sliceStates.size());
            }

            private void processKVEntry(AetherKey key, AetherValue value) {
                switch (key) {
                    case AppBlueprintKey _ when value instanceof AppBlueprintValue appBlueprintValue ->
                    restoreAppBlueprint(appBlueprintValue);
                    case BlueprintKey blueprintKey when value instanceof BlueprintValue blueprintValue ->
                    restoreBlueprint(blueprintKey, blueprintValue);
                    case SliceNodeKey sliceNodeKey when value instanceof SliceNodeValue sliceNodeValue ->
                    restoreSliceState(sliceNodeKey, sliceNodeValue);
                    default -> {}
                }
            }

            private void restoreAppBlueprint(AppBlueprintValue appBlueprintValue) {
                var expanded = appBlueprintValue.blueprint();
                log.debug("Restored app blueprint: {} with {} slices",
                          expanded.id()
                                  .name(),
                          expanded.loadOrder()
                                  .size());
                for (var slice : expanded.loadOrder()) {
                    var artifact = slice.artifact();
                    var instances = activeNodes.isEmpty()
                                    ? Math.min(1, slice.instances())
                                    : Math.min(slice.instances(), activeNodes.size());
                    blueprints.put(artifact, new Blueprint(artifact, instances));
                }
            }

            private void restoreBlueprint(BlueprintKey blueprintKey, BlueprintValue blueprintValue) {
                var artifact = blueprintKey.artifact();
                var instances = (int) blueprintValue.instanceCount();
                blueprints.put(artifact, new Blueprint(artifact, instances));
                log.debug("Restored blueprint: {} with {} instances", artifact, instances);
            }

            private void restoreSliceState(SliceNodeKey sliceNodeKey, SliceNodeValue sliceNodeValue) {
                sliceStates.put(sliceNodeKey, sliceNodeValue.state());
                log.debug("Restored slice state: {} = {}", sliceNodeKey, sliceNodeValue.state());
            }

            @Override
            public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
                var key = valuePut.cause()
                                  .key();
                var value = valuePut.cause()
                                    .value();
                switch (key) {
                    case AppBlueprintKey appBlueprintKey when value instanceof AppBlueprintValue appBlueprintValue ->
                    handleAppBlueprintChange(appBlueprintKey, appBlueprintValue);
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

            private void handleAppBlueprintChange(AppBlueprintKey key, AppBlueprintValue value) {
                var expanded = value.blueprint();
                log.info("App blueprint '{}' deployed with {} slices across {} nodes",
                         expanded.id()
                                 .name(),
                         expanded.loadOrder()
                                 .size(),
                         activeNodes.size());
                // Use instance count from blueprint, capped at available nodes
                for (var slice : expanded.loadOrder()) {
                    var artifact = slice.artifact();
                    var desiredInstances = Math.min(slice.instances(), activeNodes.size());
                    log.info("Scheduling {} with {} instances (requested: {})",
                             artifact,
                             desiredInstances,
                             slice.instances());
                    blueprints.put(artifact, new Blueprint(artifact, desiredInstances));
                    allocateInstances(artifact, desiredInstances);
                }
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
                var nodesWithInstances = existingInstances.stream()
                                                          .map(SliceNodeKey::nodeId)
                                                          .collect(java.util.stream.Collectors.toSet());
                var allocated = allocateToEmptyNodes(artifact, toAdd, nodesWithInstances);
                allocateRoundRobin(artifact, toAdd - allocated);
            }

            private int allocateToEmptyNodes(Artifact artifact, int toAdd, java.util.Set<NodeId> nodesWithInstances) {
                return (int) activeNodes.stream()
                                       .filter(node -> !nodesWithInstances.contains(node))
                                       .limit(toAdd)
                                       .filter(node -> tryAllocate(artifact, node))
                                       .count();
            }

            private boolean tryAllocate(Artifact artifact, NodeId node) {
                var sliceKey = new SliceNodeKey(artifact, node);
                if (!sliceStates.containsKey(sliceKey)) {
                    issueLoadCommand(sliceKey);
                    return true;
                }
                return false;
            }

            private void allocateRoundRobin(Artifact artifact, int remaining) {
                java.util.stream.IntStream.range(0, remaining)
                    .mapToObj(i -> activeNodes.get(i % activeNodes.size()))
                    .map(node -> new SliceNodeKey(artifact, node))
                    .forEach(this::issueLoadCommand);
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
     *
     * @param self            This node's ID
     * @param cluster         The cluster node for consensus operations
     * @param kvStore         The KV-Store for state persistence
     * @param router          The message router for events
     * @param initialTopology Initial cluster topology (nodes that should exist)
     */
    static ClusterDeploymentManager clusterDeploymentManager(NodeId self,
                                                             ClusterNode<KVCommand<AetherKey>> cluster,
                                                             KVStore<AetherKey, AetherValue> kvStore,
                                                             MessageRouter router,
                                                             List<NodeId> initialTopology) {
        record clusterDeploymentManager(NodeId self,
                                        ClusterNode<KVCommand<AetherKey>> cluster,
                                        KVStore<AetherKey, AetherValue> kvStore,
                                        MessageRouter router,
                                        AtomicReference<ClusterDeploymentState> state,
                                        List<NodeId> topology) implements ClusterDeploymentManager {
            private static final Logger log = LoggerFactory.getLogger(clusterDeploymentManager.class);

            @Override
            public void onLeaderChange(LeaderChange leaderChange) {
                if (leaderChange.localNodeIsLeader()) {
                    log.info("Node {} became leader, activating cluster deployment manager with {} known nodes",
                             self,
                             topology.size());
                    // Create active state with current topology
                    var activeNodes = new CopyOnWriteArrayList<>(topology);
                    var activeState = new ClusterDeploymentState.Active(self,
                                                                        cluster,
                                                                        kvStore,
                                                                        router,
                                                                        new ConcurrentHashMap<>(),
                                                                        new ConcurrentHashMap<>(),
                                                                        activeNodes);
                    state.set(activeState);
                    // Rebuild state from KVStore and reconcile
                    activeState.rebuildStateFromKVStore();
                    activeState.reconcile();
                } else {
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
        var initialNodes = new CopyOnWriteArrayList<>(initialTopology);
        return new clusterDeploymentManager(self,
                                            cluster,
                                            kvStore,
                                            router,
                                            new AtomicReference<>(new ClusterDeploymentState.Dormant()),
                                            initialNodes);
    }
}
