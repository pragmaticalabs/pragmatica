package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpRouteValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeAdded;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeDown;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent.DeploymentStarted;
import org.pragmatica.aether.provider.AutoHealConfig;
import org.pragmatica.aether.provider.InstanceType;
import org.pragmatica.aether.provider.NodeProvider;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.utils.SharedScheduler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

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
         *
         * <p>Note: The Map fields ({@code blueprints}, {@code sliceStates}, {@code sliceDependencies})
         * are intentionally mutable ConcurrentHashMaps. While records typically hold immutable data,
         * this state object is long-lived and requires thread-safe mutation for:
         * <ul>
         *   <li>Tracking blueprint changes as they arrive via KV-Store notifications</li>
         *   <li>Maintaining slice state transitions during deployment lifecycle</li>
         *   <li>Building dependency graphs during app blueprint expansion</li>
         * </ul>
         * The ConcurrentHashMap provides thread-safe operations without external synchronization.
         */
        record Active(NodeId self,
                      ClusterNode<KVCommand<AetherKey>> cluster,
                      KVStore<AetherKey, AetherValue> kvStore,
                      MessageRouter router,
                      TopologyManager topologyManager,
                      Option<NodeProvider> nodeProvider,
                      AutoHealConfig autoHealConfig,
                      Map<Artifact, Blueprint> blueprints,
                      Map<SliceNodeKey, SliceState> sliceStates,
                      Map<Artifact, Set<Artifact>> sliceDependencies,
                      Set<ArtifactBase> activeRoutings,
                      AtomicReference<List<NodeId>> activeNodes,
                      AtomicInteger allocationIndex,
                      AtomicBoolean deactivated,
                      AtomicReference<ScheduledFuture<?>> autoHealFuture) implements ClusterDeploymentState {
            private static final Logger log = LoggerFactory.getLogger(Active.class);

            /**
             * Mark this Active state as deactivated, preventing stale scheduled callbacks
             * from executing after the node has transitioned to Dormant.
             */
            void deactivate() {
                deactivated.set(true);
                cancelAutoHeal();
                log.debug("Active state deactivated, stale callbacks will be suppressed");
            }

            /**
             * Rebuild state from KVStore snapshot on leader activation.
             * This ensures the new leader has complete knowledge of desired and actual state.
             */
            void rebuildStateFromKVStore() {
                log.info("Rebuilding cluster deployment state from KVStore");
                kvStore.forEach(AetherKey.class, AetherValue.class, this::processKVEntry);
                log.info("Restored {} blueprints and {} slice states from KVStore",
                         blueprints.size(),
                         sliceStates.size());
                // Trigger activation for any slices stuck in LOADED state
                triggerLoadedSliceActivation();
                // Clean up stale HTTP routes (routes pointing to nodes not in topology)
                cleanupStaleHttpRoutes();
            }

            /**
             * After state rebuild, check all LOADED slices and trigger activation if dependencies are ready.
             * This handles slices that were LOADED when the previous leader died.
             */
            private void triggerLoadedSliceActivation() {
                var loadedSlices = sliceStates.entrySet()
                                              .stream()
                                              .filter(e -> e.getValue() == SliceState.LOADED)
                                              .map(Map.Entry::getKey)
                                              .toList();
                if (!loadedSlices.isEmpty()) {
                    log.info("Found {} slices in LOADED state, checking dependencies for activation",
                             loadedSlices.size());
                    loadedSlices.forEach(this::tryActivateIfDependenciesReady);
                }
            }

            private void processKVEntry(AetherKey key, AetherValue value) {
                switch (key) {
                    case AppBlueprintKey _ when value instanceof AppBlueprintValue appBlueprintValue ->
                    restoreAppBlueprint(appBlueprintValue);
                    case SliceTargetKey sliceTargetKey when value instanceof SliceTargetValue sliceTargetValue ->
                    restoreSliceTarget(sliceTargetKey, sliceTargetValue);
                    case SliceNodeKey sliceNodeKey when value instanceof SliceNodeValue sliceNodeValue ->
                    restoreSliceState(sliceNodeKey, sliceNodeValue);
                    case AetherKey.VersionRoutingKey routingKey -> activeRoutings.add(routingKey.artifactBase());
                    default -> {}
                }
            }

            private void restoreAppBlueprint(AppBlueprintValue appBlueprintValue) {
                var expanded = appBlueprintValue.blueprint();
                log.trace("Restored app blueprint: {} with {} slices",
                          expanded.id()
                                  .asString(),
                          expanded.loadOrder()
                                  .size());
                buildDependencyMap(expanded);
                for (var slice : expanded.loadOrder()) {
                    var artifact = slice.artifact();
                    var nodes = activeNodes.get();
                    var instances = nodes.isEmpty()
                                    ? Math.min(1, slice.instances())
                                    : Math.min(slice.instances(), nodes.size());
                    blueprints.put(artifact, Blueprint.blueprint(artifact, instances));
                }
            }

            private void restoreSliceTarget(SliceTargetKey sliceTargetKey, SliceTargetValue sliceTargetValue) {
                var artifact = sliceTargetKey.artifactBase()
                                             .withVersion(sliceTargetValue.currentVersion());
                var instances = sliceTargetValue.targetInstances();
                blueprints.put(artifact, Blueprint.blueprint(artifact, instances));
                log.trace("Restored slice target: {} with {} instances", artifact, instances);
            }

            private void restoreSliceState(SliceNodeKey sliceNodeKey, SliceNodeValue sliceNodeValue) {
                sliceStates.put(sliceNodeKey, sliceNodeValue.state());
                log.trace("Restored slice state: {} = {}", sliceNodeKey, sliceNodeValue.state());
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
                    case SliceTargetKey sliceTargetKey when value instanceof SliceTargetValue sliceTargetValue ->
                    handleSliceTargetChange(sliceTargetKey, sliceTargetValue);
                    case SliceNodeKey sliceNodeKey when value instanceof SliceNodeValue sliceNodeValue ->
                    trackSliceState(sliceNodeKey, sliceNodeValue.state());
                    case AetherKey.VersionRoutingKey routingKey -> {
                        log.info("Rolling update started for {}", routingKey.artifactBase());
                        activeRoutings.add(routingKey.artifactBase());
                    }
                    default -> {}
                }
            }

            @Override
            public void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {
                var key = valueRemove.cause()
                                     .key();
                switch (key) {
                    case SliceTargetKey sliceTargetKey -> handleSliceTargetRemoval(sliceTargetKey);
                    case AppBlueprintKey appKey -> handleAppBlueprintRemoval(appKey);
                    case SliceNodeKey sliceNodeKey -> sliceStates.remove(sliceNodeKey);
                    case AetherKey.VersionRoutingKey routingKey -> handleRoutingRemoval(routingKey);
                    default -> {}
                }
            }

            private void handleAppBlueprintRemoval(AppBlueprintKey key) {
                log.info("App blueprint '{}' removed",
                         key.blueprintId()
                            .artifact()
                            .asString());
                // Remove all blueprints that were part of this app
                var artifactsToRemove = blueprints.keySet()
                                                  .stream()
                                                  .toList();
                for (var artifact : artifactsToRemove) {
                    blueprints.remove(artifact);
                    deallocateAllInstances(artifact);
                }
            }

            @Override
            public void onTopologyChange(TopologyChangeNotification topologyChange) {
                log.info("Received topology change: {}", topologyChange);
                switch (topologyChange) {
                    case NodeAdded(_, List<NodeId> topology) -> {
                        updateTopology(topology);
                        reconcile();
                        checkAndScheduleAutoHeal();
                    }
                    case NodeRemoved(NodeId removedNode, List<NodeId> topology) -> {
                        updateTopology(topology);
                        handleNodeRemoval(removedNode);
                        reconcile();
                        checkAndScheduleAutoHeal();
                    }
                    case NodeDown(NodeId downNode, List<NodeId> topology) -> {
                        log.warn("Node {} is down, triggering immediate reconciliation", downNode);
                        updateTopology(topology);
                        handleNodeRemoval(downNode);
                        reconcile();
                        checkAndScheduleAutoHeal();
                    }
                    default -> {}
                }
            }

            private void updateTopology(List<NodeId> topology) {
                activeNodes.set(List.copyOf(topology));
            }

            /**
             * Compute cluster deficit: target size minus current active nodes.
             */
            private int computeAutoHealDeficit() {
                return topologyManager.clusterSize() - activeNodes.get().size();
            }

            /**
             * Provision replacement nodes for the given deficit via NodeProvider.
             */
            private void provisionReplacements(int deficit) {
                nodeProvider.onPresent(provider -> {
                    for (int i = 0; i < deficit; i++) {
                        provider.provision(InstanceType.ON_DEMAND)
                                .onFailure(cause -> log.warn("AUTO-HEAL: Provisioning failed: {}", cause.message()));
                    }
                });
            }

            /**
             * Schedule periodic auto-heal recheck if not already scheduled.
             * Uses compareAndSet to avoid race conditions.
             */
            private void scheduleAutoHealRecheck() {
                var future = SharedScheduler.scheduleAtFixedRate(
                    this::autoHealRecheck, autoHealConfig.retryInterval());
                if (!autoHealFuture.compareAndSet(null, future)) {
                    future.cancel(false);
                }
            }

            /**
             * Check if cluster is below target size and schedule auto-healing if a NodeProvider is present.
             * Cancels periodic recheck when cluster reaches target size.
             */
            private void checkAndScheduleAutoHeal() {
                if (nodeProvider.isEmpty()) {
                    return;
                }
                var deficit = computeAutoHealDeficit();
                if (deficit <= 0) {
                    cancelAutoHeal();
                    return;
                }
                var currentSize = activeNodes.get().size();
                log.info("AUTO-HEAL: Cluster size {} below target {}, provisioning {} replacement node(s)",
                         currentSize, topologyManager.clusterSize(), deficit);
                provisionReplacements(deficit);
                scheduleAutoHealRecheck();
            }

            private void autoHealRecheck() {
                if (deactivated.get()) {
                    cancelAutoHeal();
                    return;
                }
                var deficit = computeAutoHealDeficit();
                if (deficit <= 0) {
                    log.info("AUTO-HEAL: Cluster at target size {}, cancelling periodic recheck", topologyManager.clusterSize());
                    cancelAutoHeal();
                    return;
                }
                var currentSize = activeNodes.get().size();
                log.info("AUTO-HEAL: Cluster still below target ({}/{}), provisioning {} node(s)",
                         currentSize, topologyManager.clusterSize(), deficit);
                provisionReplacements(deficit);
            }

            private void cancelAutoHeal() {
                var future = autoHealFuture.getAndSet(null);
                if (future != null) {
                    future.cancel(false);
                    log.debug("AUTO-HEAL: Cancelled periodic recheck");
                }
            }

            private void handleSliceTargetChange(SliceTargetKey key, SliceTargetValue value) {
                var artifactBase = key.artifactBase();
                var newVersion = value.currentVersion();
                var newArtifact = artifactBase.withVersion(newVersion);
                var desiredInstances = value.targetInstances();
                // Only remove old versions if NOT in a rolling update (no active routing)
                if (!activeRoutings.contains(artifactBase)) {
                    var oldVersions = blueprints.keySet()
                                                .stream()
                                                .filter(a -> artifactBase.matches(a) && !a.version()
                                                                                          .equals(newVersion))
                                                .toList();
                    for (var oldArtifact : oldVersions) {
                        log.info("Removing old version {} (new version: {})", oldArtifact, newArtifact);
                        blueprints.remove(oldArtifact);
                        deallocateAllInstances(oldArtifact);
                    }
                }
                log.info("Slice target changed for {}: {} instances", newArtifact, desiredInstances);
                blueprints.put(newArtifact, Blueprint.blueprint(newArtifact, desiredInstances));
                allocateInstances(newArtifact, desiredInstances);
            }

            private void handleAppBlueprintChange(AppBlueprintKey key, AppBlueprintValue value) {
                var expanded = value.blueprint();
                var nodes = activeNodes.get();
                log.info("App blueprint '{}' deployed with {} slices across {} nodes",
                         expanded.id()
                                 .asString(),
                         expanded.loadOrder()
                                 .size(),
                         nodes.size());
                buildDependencyMap(expanded);
                // Use instance count from blueprint, capped at available nodes
                for (var slice : expanded.loadOrder()) {
                    var artifact = slice.artifact();
                    var desiredInstances = Math.min(slice.instances(), nodes.size());
                    log.info("Scheduling {} with {} instances (requested: {})",
                             artifact,
                             desiredInstances,
                             slice.instances());
                    blueprints.put(artifact, Blueprint.blueprint(artifact, desiredInstances));
                    allocateInstances(artifact, desiredInstances);
                }
            }

            /**
             * Build dependency map from ExpandedBlueprint's ResolvedSlice dependencies.
             * Each slice has its actual dependencies from the blueprint expansion.
             */
            private void buildDependencyMap(ExpandedBlueprint expanded) {
                for (var slice : expanded.loadOrder()) {
                    var artifact = slice.artifact();
                    var dependencies = slice.dependencies();
                    sliceDependencies.put(artifact, dependencies);
                    log.debug("buildDependencyMap: Slice {} has {} dependencies: {}",
                              artifact,
                              dependencies.size(),
                              dependencies);
                }
            }

            private void handleSliceTargetRemoval(SliceTargetKey key) {
                // Find and remove blueprints matching this artifact base
                var artifactBase = key.artifactBase();
                var matching = blueprints.keySet()
                                         .stream()
                                         .filter(artifactBase::matches)
                                         .toList();
                for (var artifact : matching) {
                    log.info("Slice target removed for {}", artifact);
                    blueprints.remove(artifact);
                    deallocateAllInstances(artifact);
                }
            }

            private void handleRoutingRemoval(AetherKey.VersionRoutingKey routingKey) {
                var artifactBase = routingKey.artifactBase();
                activeRoutings.remove(artifactBase);
                log.info("Rolling update completed for {}, cleaning up old versions", artifactBase);
                // Get current target version from KVStore
                var targetKey = AetherKey.SliceTargetKey.sliceTargetKey(artifactBase);
                kvStore.get(targetKey)
                       .filter(v -> v instanceof AetherValue.SliceTargetValue)
                       .map(v -> (AetherValue.SliceTargetValue) v)
                       .onPresent(targetValue -> removeNonTargetVersions(artifactBase,
                                                                         targetValue.currentVersion()));
            }

            private void removeNonTargetVersions(ArtifactBase artifactBase, Version currentVersion) {
                var oldVersions = blueprints.keySet()
                                            .stream()
                                            .filter(a -> artifactBase.matches(a) && !a.version()
                                                                                      .equals(currentVersion))
                                            .toList();
                for (var oldArtifact : oldVersions) {
                    log.info("Removing old version {} after rolling update completion", oldArtifact);
                    blueprints.remove(oldArtifact);
                    deallocateAllInstances(oldArtifact);
                }
            }

            private void trackSliceState(SliceNodeKey sliceKey, SliceState state) {
                var previousState = sliceStates.put(sliceKey, state);
                log.debug("Slice {} on {} state: {} -> {}",
                          sliceKey.artifact(),
                          sliceKey.nodeId(),
                          previousState,
                          state);
                // When slice reaches LOADED, check if dependencies are ACTIVE before activating
                if (state == SliceState.LOADED) {
                    tryActivateIfDependenciesReady(sliceKey);
                }
                // When slice becomes ACTIVE, check if any dependent slices can now be activated
                if (state == SliceState.ACTIVE) {
                    activateDependentSlices(sliceKey.artifact());
                }
                // When slice enters FAILED state, remove it and trigger replacement
                if (state == SliceState.FAILED) {
                    log.warn("Slice {} FAILED on {}, removing and scheduling replacement",
                             sliceKey.artifact(),
                             sliceKey.nodeId());
                    sliceStates.remove(sliceKey);
                    // Issue UNLOAD to clean up the failed slice on the node
                    issueUnloadCommand(sliceKey);
                    // Schedule reconciliation to allocate replacement
                    SharedScheduler.schedule(this::reconcile, timeSpan(1).seconds());
                }
            }

            /**
             * Try to activate a slice if all its dependencies are ACTIVE.
             */
            private void tryActivateIfDependenciesReady(SliceNodeKey sliceKey) {
                var artifact = sliceKey.artifact();
                var dependencies = sliceDependencies.getOrDefault(artifact, Set.of());
                if (dependencies.isEmpty()) {
                    log.debug("Slice {} has no dependencies, activating immediately", artifact);
                    issueActivateCommand(sliceKey);
                    return;
                }
                if (allDependenciesActive(dependencies)) {
                    log.debug("All {} dependencies of {} are ACTIVE, activating", dependencies.size(), artifact);
                    issueActivateCommand(sliceKey);
                } else {
                    log.debug("Slice {} waiting for dependencies to become ACTIVE: {}",
                              artifact,
                              dependencies.stream()
                                          .filter(dep -> !isDependencyActive(dep))
                                          .toList());
                }
            }

            /**
             * Check if all dependencies are ACTIVE (at least one instance).
             */
            private boolean allDependenciesActive(Set<Artifact> dependencies) {
                return dependencies.stream()
                                   .allMatch(this::isDependencyActive);
            }

            /**
             * Check if a dependency has at least one ACTIVE instance.
             */
            private boolean isDependencyActive(Artifact dependency) {
                return sliceStates.entrySet()
                                  .stream()
                                  .anyMatch(entry -> entry.getKey()
                                                          .artifact()
                                                          .equals(dependency) && entry.getValue() == SliceState.ACTIVE);
            }

            /**
             * When a slice becomes ACTIVE, check if any LOADED slices that depend on it can now be activated.
             */
            private void activateDependentSlices(Artifact activatedArtifact) {
                sliceStates.entrySet()
                           .stream()
                           .filter(entry -> entry.getValue() == SliceState.LOADED)
                           .map(Map.Entry::getKey)
                           .filter(key -> dependsOn(key.artifact(),
                                                    activatedArtifact))
                           .forEach(this::tryActivateIfDependenciesReady);
            }

            private boolean dependsOn(Artifact dependent, Artifact dependency) {
                return sliceDependencies.getOrDefault(dependent,
                                                      Set.of())
                                        .contains(dependency);
            }

            private void issueActivateCommand(SliceNodeKey sliceKey) {
                log.debug("Issuing ACTIVATE command for {}", sliceKey);
                var value = new SliceNodeValue(SliceState.ACTIVATE);
                var command = new KVCommand.Put<AetherKey, AetherValue>(sliceKey, value);
                cluster.apply(List.of(command))
                       .onFailure(cause -> log.error("Failed to issue ACTIVATE command for {}: {}",
                                                     sliceKey,
                                                     cause.message()));
            }

            private void handleNodeRemoval(NodeId removedNode) {
                // Remove slice state entries for the removed node
                var sliceKeysToRemove = sliceStates.keySet()
                                                   .stream()
                                                   .filter(key -> key.nodeId()
                                                                     .equals(removedNode))
                                                   .toList();
                // Remove from in-memory state immediately
                sliceKeysToRemove.forEach(sliceStates::remove);
                // Find endpoint keys for the removed node by scanning KVStore snapshot
                var endpointKeysToRemove = findEndpointKeysForNode(removedNode);
                // Clean up HTTP routes containing the removed node
                var httpRouteCommands = cleanupHttpRoutesForNode(removedNode);
                // Combine all commands
                List<KVCommand<AetherKey>> allCommands = new ArrayList<>();
                sliceKeysToRemove.stream()
                                 .<KVCommand<AetherKey>> map(KVCommand.Remove::new)
                                 .forEach(allCommands::add);
                endpointKeysToRemove.stream()
                                    .<KVCommand<AetherKey>> map(KVCommand.Remove::new)
                                    .forEach(allCommands::add);
                allCommands.addAll(httpRouteCommands);
                // Remove from KVStore to prevent stale state after leader changes
                if (!allCommands.isEmpty()) {
                    cluster.apply(allCommands)
                           .onFailure(cause -> log.error("Failed to remove keys for departed node {}: {}",
                                                         removedNode,
                                                         cause.message()));
                }
                log.info("Removed {} slice states, {} endpoints, and {} HTTP route updates for departed node {}",
                         sliceKeysToRemove.size(),
                         endpointKeysToRemove.size(),
                         httpRouteCommands.size(),
                         removedNode);
            }

            private List<EndpointKey> findEndpointKeysForNode(NodeId nodeId) {
                var result = new ArrayList<EndpointKey>();
                kvStore.forEach(EndpointKey.class, EndpointValue.class,
                                (key, value) -> collectEndpointKeyForNode(result, key, value, nodeId));
                return result;
            }

            private void collectEndpointKeyForNode(List<EndpointKey> result,
                                                   EndpointKey endpointKey,
                                                   EndpointValue endpointValue,
                                                   NodeId nodeId) {
                if (endpointValue.nodeId().equals(nodeId)) {
                    result.add(endpointKey);
                }
            }

            /**
             * Clean up HTTP routes that reference the removed node.
             * For each HttpRouteValue, remove the node from the node set.
             * If the node set becomes empty, delete the entry.
             */
            private List<KVCommand<AetherKey>> cleanupHttpRoutesForNode(NodeId removedNode) {
                var commands = new java.util.ArrayList<KVCommand<AetherKey>>();
                kvStore.forEach(HttpRouteKey.class, HttpRouteValue.class,
                                (key, value) -> collectRouteCleanupCommand(commands, key, value, removedNode));
                return commands;
            }

            private void collectRouteCleanupCommand(List<KVCommand<AetherKey>> commands,
                                                    HttpRouteKey routeKey,
                                                    HttpRouteValue routeValue,
                                                    NodeId removedNode) {
                if (!routeValue.nodes().contains(removedNode)) {
                    return;
                }
                var updatedValue = routeValue.withoutNode(removedNode);
                if (updatedValue.isEmpty()) {
                    commands.add(new KVCommand.Remove<>(routeKey));
                    log.debug("Removing HTTP route {} (last node {} departed)", routeKey, removedNode);
                } else {
                    commands.add(new KVCommand.Put<>(routeKey, updatedValue));
                    log.debug("Updating HTTP route {} - removed departed node {}, {} nodes remaining",
                              routeKey, removedNode, updatedValue.nodes().size());
                }
            }

            /**
             * Remove HTTP route entries that reference nodes not in the current topology.
             * This handles cases where nodes died before the leader could clean up their routes.
             */
            private void cleanupStaleHttpRoutes() {
                var currentNodes = new HashSet<>(activeNodes.get());
                var commands = new java.util.ArrayList<KVCommand<AetherKey>>();
                kvStore.forEach(HttpRouteKey.class, HttpRouteValue.class,
                                (key, value) -> collectStaleRouteCleanupCommand(commands, key, value, currentNodes));
                if (!commands.isEmpty()) {
                    log.debug("Cleaning up {} stale HTTP route entries", commands.size());
                    cluster.apply(commands)
                           .onFailure(cause -> log.error("Failed to clean up stale HTTP routes: {}",
                                                         cause.message()));
                }
            }

            private void collectStaleRouteCleanupCommand(List<KVCommand<AetherKey>> commands,
                                                         HttpRouteKey routeKey,
                                                         HttpRouteValue routeValue,
                                                         Set<NodeId> currentNodes) {
                var staleNodes = routeValue.nodes()
                                           .stream()
                                           .filter(n -> !currentNodes.contains(n))
                                           .toList();
                if (staleNodes.isEmpty()) {
                    return;
                }
                var updatedValue = routeValue;
                for (var staleNode : staleNodes) {
                    updatedValue = updatedValue.withoutNode(staleNode);
                }
                if (updatedValue.isEmpty()) {
                    commands.add(new KVCommand.Remove<>(routeKey));
                    log.debug("Removing stale HTTP route {} (no valid nodes)", routeKey);
                } else {
                    commands.add(new KVCommand.Put<>(routeKey, updatedValue));
                    log.debug("Cleaning up HTTP route {} - removed {} stale nodes",
                              routeKey, staleNodes.size());
                }
            }

            /**
             * Allocate instances across cluster nodes using round-robin strategy.
             */
            private void allocateInstances(Artifact artifact, int desiredInstances) {
                if (hasNoActiveNodes(artifact)) {
                    return;
                }
                var currentInstances = getCurrentInstances(artifact);
                logAllocationAttempt(artifact, desiredInstances, currentInstances);
                adjustInstanceCount(artifact, desiredInstances, currentInstances);
            }

            /**
             * Check if there are no active nodes available for allocation.
             */
            private boolean hasNoActiveNodes(Artifact artifact) {
                if (activeNodes.get()
                               .isEmpty()) {
                    log.warn("No active nodes available for allocation of {}", artifact);
                    return true;
                }
                return false;
            }

            /**
             * Log the allocation attempt details.
             */
            private void logAllocationAttempt(Artifact artifact,
                                              int desiredInstances,
                                              List<SliceNodeKey> currentInstances) {
                log.debug("Allocating {} instances of {} (current: {}) across {} nodes",
                          desiredInstances,
                          artifact,
                          currentInstances.size(),
                          activeNodes.get()
                                     .size());
            }

            /**
             * Adjust instance count by scaling up or down as needed.
             */
            private void adjustInstanceCount(Artifact artifact,
                                             int desiredInstances,
                                             List<SliceNodeKey> currentInstances) {
                var currentCount = currentInstances.size();
                if (desiredInstances > currentCount) {
                    scaleUp(artifact, desiredInstances - currentCount, currentInstances);
                } else if (desiredInstances < currentCount) {
                    scaleDown(artifact, currentCount - desiredInstances, currentInstances);
                }
            }

            private void scaleUp(Artifact artifact, int toAdd, List<SliceNodeKey> existingInstances) {
                log.debug("scaleUp: artifact={}, toAdd={}, activeNodes={}, activeNodeIds={}",
                          artifact,
                          toAdd,
                          activeNodes.get()
                                     .size(),
                          activeNodes.get());
                var nodesWithInstances = existingInstances.stream()
                                                          .map(SliceNodeKey::nodeId)
                                                          .collect(Collectors.toSet());
                // Phase 1: Allocate to truly empty nodes (nodes with NO slices at all)
                var trulyEmptyNodes = findTrulyEmptyNodes();
                log.debug("scaleUp: found {} truly empty nodes: {}", trulyEmptyNodes.size(), trulyEmptyNodes);
                var allocatedToTrulyEmpty = allocateToSpecificNodes(artifact, toAdd, trulyEmptyNodes);
                log.debug("scaleUp: allocated {} instances to truly empty nodes", allocatedToTrulyEmpty);
                var remaining = toAdd - allocatedToTrulyEmpty;
                if (remaining <= 0) {
                    return;
                }
                // Phase 2: Allocate to nodes without THIS artifact (but may have other slices)
                var allocated = allocateToEmptyNodes(artifact, remaining, nodesWithInstances);
                log.debug("scaleUp: allocated {} instances to nodes without this artifact, remaining={}",
                          allocated,
                          remaining - allocated);
                // Phase 3: Round-robin for any remaining
                allocateRoundRobin(artifact, remaining - allocated);
            }

            /**
             * Find nodes that have absolutely no slices deployed (truly empty).
             * These are ideal candidates for new deployments (e.g., AUTO-HEAL nodes).
             */
            private Set<NodeId> findTrulyEmptyNodes() {
                var nodesWithAnySlice = sliceStates.keySet()
                                                   .stream()
                                                   .map(SliceNodeKey::nodeId)
                                                   .collect(Collectors.toSet());
                return activeNodes.get()
                                  .stream()
                                  .filter(node -> !nodesWithAnySlice.contains(node))
                                  .collect(Collectors.toSet());
            }

            /**
             * Allocate to a specific set of nodes.
             */
            private int allocateToSpecificNodes(Artifact artifact, int toAdd, Set<NodeId> targetNodes) {
                var allocated = 0;
                for (var node : targetNodes) {
                    if (allocated >= toAdd) {
                        break;
                    }
                    if (tryAllocate(artifact, node)) {
                        allocated++;
                    }
                }
                return allocated;
            }

            private int allocateToEmptyNodes(Artifact artifact, int toAdd, Set<NodeId> nodesWithInstances) {
                var nodes = activeNodes.get();
                var nodeCount = nodes.size();
                if (nodeCount == 0) {
                    return 0;
                }
                var allocated = 0;
                for (var i = 0; i < nodeCount && allocated < toAdd; i++) {
                    var nodeIndex = Math.floorMod(allocationIndex.getAndIncrement(), nodeCount);
                    var node = nodes.get(nodeIndex);
                    if (!nodesWithInstances.contains(node) && tryAllocate(artifact, node)) {
                        allocated++;
                    }
                }
                return allocated;
            }

            private boolean tryAllocate(Artifact artifact, NodeId node) {
                var sliceKey = new SliceNodeKey(artifact, node);
                var alreadyExists = sliceStates.containsKey(sliceKey);
                log.debug("tryAllocate: artifact={}, node={}, sliceKey={}, alreadyExists={}",
                          artifact,
                          node,
                          sliceKey,
                          alreadyExists);
                if (!alreadyExists) {
                    issueLoadCommand(sliceKey);
                    return true;
                }
                return false;
            }

            private void allocateRoundRobin(Artifact artifact, int remaining) {
                var nodes = activeNodes.get();
                var allocated = 0;
                var attempts = 0;
                var maxAttempts = nodes.size() * 2;
                // Prevent infinite loop
                while (allocated < remaining && attempts < maxAttempts) {
                    var nodeIndex = Math.floorMod(allocationIndex.getAndIncrement(), nodes.size());
                    var node = nodes.get(nodeIndex);
                    if (tryAllocate(artifact, node)) {
                        allocated++;
                    }
                    attempts++;
                }
                if (allocated < remaining) {
                    log.warn("Could only allocate {} of {} requested instances for {} (not enough nodes without instances)",
                             allocated,
                             remaining,
                             artifact);
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
                var currentNodes = activeNodes.get();
                return sliceStates.keySet()
                                  .stream()
                                  .filter(key -> key.artifact()
                                                    .equals(artifact))
                                  .filter(key -> currentNodes.contains(key.nodeId()))
                                  .toList();
            }

            private void issueLoadCommand(SliceNodeKey sliceKey) {
                log.debug("Issuing LOAD command for {}", sliceKey);
                // Optimistic tracking: add to sliceStates BEFORE consensus to prevent duplicates
                // The actual state will be updated via onValuePut when consensus commits
                sliceStates.put(sliceKey, SliceState.LOAD);
                // Emit deployment started event for metrics via MessageRouter
                var timestamp = System.currentTimeMillis();
                router.route(DeploymentStarted.deploymentStarted(sliceKey.artifact(), sliceKey.nodeId(), timestamp));
                var value = new SliceNodeValue(SliceState.LOAD);
                var command = new KVCommand.Put<AetherKey, AetherValue>(sliceKey, value);
                cluster.apply(List.of(command))
                       .onFailure(cause -> handleLoadCommandFailure(cause, sliceKey));
            }

            private void handleLoadCommandFailure(Cause cause, SliceNodeKey sliceKey) {
                if (deactivated.get()) {
                    log.debug("Suppressing LOAD failure handling for {} - Active state deactivated", sliceKey);
                    return;
                }
                log.error("Failed to issue LOAD command for {}: {}", sliceKey, cause.message());
                // Remove optimistic entry on failure to allow retry
                sliceStates.remove(sliceKey);
                SharedScheduler.schedule(this::reconcile, timeSpan(5).seconds());
            }

            private void issueUnloadCommand(SliceNodeKey sliceKey) {
                log.debug("Issuing UNLOAD command for {}", sliceKey);
                var value = new SliceNodeValue(SliceState.UNLOAD);
                var command = new KVCommand.Put<AetherKey, AetherValue>(sliceKey, value);
                cluster.apply(List.of(command))
                       .onFailure(cause -> log.error("Failed to issue UNLOAD command for {}: {}",
                                                     sliceKey,
                                                     cause.message()));
            }

            private void deallocateAllInstances(Artifact artifact) {
                getCurrentInstances(artifact).forEach(this::issueUnloadCommand);
            }

            /**
             * Reconcile desired state (blueprints) with actual state (slice states).
             * Called on leader activation, topology changes, etc.
             */
            void reconcile() {
                if (deactivated.get()) {
                    log.debug("Suppressing reconciliation - Active state deactivated");
                    return;
                }
                log.debug("Performing cluster reconciliation with {} blueprints and {} active nodes",
                          blueprints.size(),
                          activeNodes.get().size());
                var reconciled = 0;
                for (var blueprint : blueprints.values()) {
                    var artifact = blueprint.artifact();
                    var desiredInstances = blueprint.instances();
                    var currentInstances = getCurrentInstances(artifact);
                    if (currentInstances.size() != desiredInstances) {
                        log.info("Reconciliation: {} has {} instances, desired {} - adjusting",
                                 artifact,
                                 currentInstances.size(),
                                 desiredInstances);
                        allocateInstances(artifact, desiredInstances);
                        reconciled++;
                    }
                }
                log.debug("Reconciliation complete: {} of {} blueprints required adjustment",
                          reconciled,
                          blueprints.size());
            }
        }
    }

    /**
     * Blueprint representation (desired state).
     */
    record Blueprint(Artifact artifact, int instances) {
        static Blueprint blueprint(Artifact artifact, int instances) {
            return new Blueprint(artifact, instances);
        }
    }

    /**
     * Create a new cluster deployment manager.
     *
     * @param self            This node's ID
     * @param cluster         The cluster node for consensus operations
     * @param kvStore         The KV-Store for state persistence
     * @param router          The message router for events
     * @param initialTopology Initial cluster topology (nodes that should exist)
     * @param topologyManager Topology manager for cluster size information
     * @param nodeProvider    Node provider for auto-healing (empty to disable)
     * @param autoHealConfig  Auto-heal retry configuration
     */
    static ClusterDeploymentManager clusterDeploymentManager(NodeId self,
                                                             ClusterNode<KVCommand<AetherKey>> cluster,
                                                             KVStore<AetherKey, AetherValue> kvStore,
                                                             MessageRouter router,
                                                             List<NodeId> initialTopology,
                                                             TopologyManager topologyManager,
                                                             Option<NodeProvider> nodeProvider,
                                                             AutoHealConfig autoHealConfig) {
        record clusterDeploymentManager(NodeId self,
                                        ClusterNode<KVCommand<AetherKey>> cluster,
                                        KVStore<AetherKey, AetherValue> kvStore,
                                        MessageRouter router,
                                        TopologyManager topologyManager,
                                        Option<NodeProvider> nodeProvider,
                                        AutoHealConfig autoHealConfig,
                                        AtomicReference<ClusterDeploymentState> state,
                                        AtomicReference<List<NodeId>> topologyRef) implements ClusterDeploymentManager {
            private static final Logger log = LoggerFactory.getLogger(clusterDeploymentManager.class);

            @Override
            public void onLeaderChange(LeaderChange leaderChange) {
                if (leaderChange.localNodeIsLeader()) {
                    var currentTopology = topologyRef.get();
                    log.info("Node {} became leader, activating cluster deployment manager with {} known nodes",
                             self,
                             currentTopology.size());
                    // Deactivate old Active state to suppress stale scheduled callbacks
                    deactivateCurrentState();
                    // Create active state with current topology
                    var activeNodes = new AtomicReference<>(currentTopology);
                    var activeState = new ClusterDeploymentState.Active(self,
                                                                        cluster,
                                                                        kvStore,
                                                                        router,
                                                                        topologyManager,
                                                                        nodeProvider,
                                                                        autoHealConfig,
                                                                        new ConcurrentHashMap<>(),
                                                                        new ConcurrentHashMap<>(),
                                                                        new ConcurrentHashMap<>(),
                                                                        ConcurrentHashMap.newKeySet(),
                                                                        activeNodes,
                                                                        new AtomicInteger(0),
                                                                        new AtomicBoolean(false),
                                                                        new AtomicReference<>());
                    state.set(activeState);
                    // Rebuild state from KVStore and reconcile
                    activeState.rebuildStateFromKVStore();
                    activeState.reconcile();
                } else {
                    log.info("Node {} is not leader, deactivating cluster deployment manager", self);
                    // Deactivate old Active state to suppress stale scheduled callbacks
                    deactivateCurrentState();
                    state.set(new ClusterDeploymentState.Dormant());
                }
            }

            private void deactivateCurrentState() {
                if (state.get() instanceof ClusterDeploymentState.Active activeState) {
                    activeState.deactivate();
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
                // Always update topology even when dormant, so we have current topology when becoming leader.
                // Use atomic reference swap instead of non-atomic clear+addAll on CopyOnWriteArrayList.
                switch (topologyChange) {
                    case NodeAdded(_, List<NodeId> newTopology) -> topologyRef.set(List.copyOf(newTopology));
                    case NodeRemoved(_, List<NodeId> newTopology) -> topologyRef.set(List.copyOf(newTopology));
                    case NodeDown(_, List<NodeId> newTopology) -> topologyRef.set(List.copyOf(newTopology));
                    default -> {}
                }
                state.get()
                     .onTopologyChange(topologyChange);
            }
        }
        return new clusterDeploymentManager(self,
                                            cluster,
                                            kvStore,
                                            router,
                                            topologyManager,
                                            nodeProvider,
                                            autoHealConfig,
                                            new AtomicReference<>(new ClusterDeploymentState.Dormant()),
                                            new AtomicReference<>(List.copyOf(initialTopology)));
    }
}
