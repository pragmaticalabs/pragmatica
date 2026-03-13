package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.dht.MapSubscription;
import org.pragmatica.aether.dht.ReplicatedMap;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.config.PlacementPolicy;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.WorkerSliceDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpNodeRouteValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkerSliceDirectiveValue;
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
import org.pragmatica.aether.metrics.deployment.DeploymentEvent.DeploymentFailed;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent.DeploymentStarted;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.lang.io.TimeSpan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

/// Cluster-wide orchestration component that manages slice deployments across the cluster.
/// Only active on the leader node.
///
///
/// Key responsibilities:
///
///   - Watch for blueprint changes (desired state)
///   - Allocate slice instances across nodes (round-robin)
///   - Write LOAD commands directly to slice-node-keys
///   - Perform reconciliation to ensure actual state matches desired state
///
///
///
/// Design notes:
///
///   - NO separate allocations key - writes directly to slice-node-keys
///   - NO separate AllocationEngine - allocation logic embedded here
///   - Reconciliation handles topology changes and leader failover
///
@SuppressWarnings("JBCT-RET-01") // MessageReceiver callbacks — void required by messaging framework
public interface ClusterDeploymentManager {
    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);

    @MessageReceiver
    void onAppBlueprintPut(ValuePut<AppBlueprintKey, AppBlueprintValue> valuePut);

    @MessageReceiver
    void onSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut);

    @MessageReceiver
    void onSliceNodePut(ValuePut<SliceNodeKey, SliceNodeValue> valuePut);

    @MessageReceiver
    void onVersionRoutingPut(ValuePut<VersionRoutingKey, VersionRoutingValue> valuePut);

    @MessageReceiver
    void onAppBlueprintRemove(ValueRemove<AppBlueprintKey, AppBlueprintValue> valueRemove);

    @MessageReceiver
    void onSliceTargetRemove(ValueRemove<SliceTargetKey, SliceTargetValue> valueRemove);

    @MessageReceiver
    void onSliceNodeRemove(ValueRemove<SliceNodeKey, SliceNodeValue> valueRemove);

    @MessageReceiver
    void onVersionRoutingRemove(ValueRemove<VersionRoutingKey, VersionRoutingValue> valueRemove);

    @MessageReceiver
    void onTopologyChange(TopologyChangeNotification topologyChange);

    @MessageReceiver
    void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> valuePut);

    @MessageReceiver
    void onActivationDirectivePut(ValuePut<ActivationDirectiveKey, ActivationDirectiveValue> valuePut);

    @MessageReceiver
    void onActivationDirectiveRemove(ValueRemove<ActivationDirectiveKey, ActivationDirectiveValue> valueRemove);

    @MessageReceiver
    void onGovernorAnnouncementPut(ValuePut<GovernorAnnouncementKey, GovernorAnnouncementValue> valuePut);

    @MessageReceiver
    void onGovernorAnnouncementRemove(ValueRemove<GovernorAnnouncementKey, GovernorAnnouncementValue> valueRemove);

    /// Create a MapSubscription adapter for DHT slice-node events.
    default MapSubscription<SliceNodeKey, SliceNodeValue> asSliceNodeSubscription() {
        return new MapSubscription<>() {
            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onPut(SliceNodeKey key, SliceNodeValue value) {
                onSliceNodePut(new ValuePut<>(new KVCommand.Put<>(key, value), Option.none()));
            }

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onRemove(SliceNodeKey key) {
                onSliceNodeRemove(new ValueRemove<>(new KVCommand.Remove<>(key), Option.none()));
            }
        };
    }

    /// Controls whether multi-slice blueprint deployments are atomic.
    enum DeploymentAtomicity {
        /// Each slice deploys independently; failures don't affect siblings.
        BEST_EFFORT,
        /// Default: all slices in a blueprint must succeed; deterministic failure of any slice
        /// rolls back the entire blueprint.
        ALL_OR_NOTHING;
        /// Parse from TOML config string (case-insensitive, supports kebab-case).
        public static DeploymentAtomicity parse(String value) {
            if (value == null || value.isBlank()) {
                return ALL_OR_NOTHING;
            }
            return switch (value.trim()
                                .toLowerCase()
                                .replace("-", "_")) {
                case "best_effort" -> BEST_EFFORT;
                default -> ALL_OR_NOTHING;
            };
        }
    }

    /// State of the cluster deployment manager.
    sealed interface ClusterDeploymentState {
        default void onAppBlueprintPut(ValuePut<AppBlueprintKey, AppBlueprintValue> valuePut) {}

        default void onSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut) {}

        default void onSliceNodePut(ValuePut<SliceNodeKey, SliceNodeValue> valuePut) {}

        default void onVersionRoutingPut(ValuePut<VersionRoutingKey, VersionRoutingValue> valuePut) {}

        default void onAppBlueprintRemove(ValueRemove<AppBlueprintKey, AppBlueprintValue> valueRemove) {}

        default void onSliceTargetRemove(ValueRemove<SliceTargetKey, SliceTargetValue> valueRemove) {}

        default void onSliceNodeRemove(ValueRemove<SliceNodeKey, SliceNodeValue> valueRemove) {}

        default void onVersionRoutingRemove(ValueRemove<VersionRoutingKey, VersionRoutingValue> valueRemove) {}

        default void onTopologyChange(TopologyChangeNotification topologyChange) {}

        default void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> valuePut) {}

        default void onActivationDirectivePut(ValuePut<ActivationDirectiveKey, ActivationDirectiveValue> valuePut) {}

        default void onActivationDirectiveRemove(ValueRemove<ActivationDirectiveKey, ActivationDirectiveValue> valueRemove) {}

        default void onGovernorAnnouncementPut(ValuePut<GovernorAnnouncementKey, GovernorAnnouncementValue> valuePut) {}

        default void onGovernorAnnouncementRemove(ValueRemove<GovernorAnnouncementKey, GovernorAnnouncementValue> valueRemove) {}

        /// Dormant state when node is NOT the leader.
        record Dormant() implements ClusterDeploymentState {}

        /// Active state when node IS the leader.
        ///
        ///
        /// Note: The Map fields (`blueprints`, `sliceStates`, `sliceDependencies`)
        /// are intentionally mutable ConcurrentHashMaps. While records typically hold immutable data,
        /// this state object is long-lived and requires thread-safe mutation for:
        ///
        ///   - Tracking blueprint changes as they arrive via KV-Store notifications
        ///   - Maintaining slice state transitions during deployment lifecycle
        ///   - Building dependency graphs during app blueprint expansion
        ///
        /// The ConcurrentHashMap provides thread-safe operations without external synchronization.
        record Active(NodeId self,
                      ClusterNode<KVCommand<AetherKey>> cluster,
                      KVStore<AetherKey, AetherValue> kvStore,
                      MessageRouter router,
                      TopologyManager topologyManager,
                      Option<ComputeProvider> computeProvider,
                      AutoHealConfig autoHealConfig,
                      Map<Artifact, Blueprint> blueprints,
                      Map<SliceNodeKey, SliceState> sliceStates,
                      Map<Artifact, Set<Artifact>> sliceDependencies,
                      Set<ArtifactBase> activeRoutings,
                      AtomicReference<List<NodeId>> activeNodes,
                      AtomicInteger allocationIndex,
                      AtomicBoolean deactivated,
                      AtomicReference<ScheduledFuture<?>> autoHealFuture,
                      AtomicBoolean cooldownActive,
                      Set<NodeId> drainingNodes,
                      Map<String, Integer> retryCounters,
                      Map<BlueprintId, InFlightBlueprint> inFlightBlueprints,
                      Set<BlueprintId> restoringBlueprints,
                      Set<Artifact> permanentlyFailed,
                      DeploymentAtomicity atomicity,
                      int coreMax,
                      Set<NodeId> seedNodes,
                      Set<NodeId> workerNodes,
                      Map<String, GovernorAnnouncementValue> communityGovernors,
                      Map<SliceNodeKey, Long> transitionalStateTimestamps,
                      ReplicatedMap<SliceNodeKey, SliceNodeValue> sliceNodeMap,
                      AtomicReference<ScheduledFuture<?>> reconcileTimer) implements ClusterDeploymentState {
            private static final Logger log = LoggerFactory.getLogger(Active.class);
            private static final int MAX_RETRIES = 5;
            private static final long MAX_RETRY_DELAY_SECONDS = 30;
            private static final int STUCK_TIMEOUT_MULTIPLIER = 2;

            /// Tracks an in-flight blueprint deployment for atomicity enforcement.
            record InFlightBlueprint(BlueprintId id,
                                     ExpandedBlueprint expanded,
                                     Set<Artifact> pendingSlices,
                                     Set<Artifact> activeSlices,
                                     Option<ExpandedBlueprint> previousBlueprint) {
                static InFlightBlueprint inFlightBlueprint(BlueprintId id,
                                                           ExpandedBlueprint expanded,
                                                           Option<ExpandedBlueprint> previousBlueprint) {
                    Set<Artifact> pending = ConcurrentHashMap.newKeySet();
                    expanded.loadOrder()
                            .forEach(slice -> pending.add(slice.artifact()));
                    return new InFlightBlueprint(id, expanded, pending, ConcurrentHashMap.newKeySet(), previousBlueprint);
                }
            }

            /// Mark this Active state as deactivated, preventing stale scheduled callbacks
            /// from executing after the node has transitioned to Dormant.
            private static final TimeSpan RECONCILE_INTERVAL = timeSpan(30).seconds();

            void deactivate() {
                deactivated.set(true);
                cooldownActive.set(false);
                cancelAutoHeal();
                cancelReconcileTimer();
                log.trace("Active state deactivated, stale callbacks will be suppressed");
            }

            /// Start periodic reconciliation to detect and remediate stuck transitional states.
            void startReconcileTimer() {
                var future = SharedScheduler.scheduleAtFixedRate(() -> {
                                                                     if (!deactivated.get()) {
                                                                         reconcile();
                                                                     }
                                                                 },
                                                                 RECONCILE_INTERVAL);
                reconcileTimer.set(future);
            }

            private void cancelReconcileTimer() {
                var future = reconcileTimer.getAndSet(null);
                if (future != null) {
                    future.cancel(false);
                }
            }

            /// Start auto-heal cooldown for initial cluster formation.
            /// During cooldown, provisioning is suppressed to allow all nodes time to join.
            void startAutoHealCooldown() {
                cooldownActive.set(true);
                log.info("AUTO-HEAL: Starting {}ms cooldown for initial cluster formation",
                         autoHealConfig.startupCooldown()
                                       .millis());
                SharedScheduler.schedule(() -> {
                                             if (deactivated.get()) {
                                                 return;
                                             }
                                             cooldownActive.set(false);
                                             log.info("AUTO-HEAL: Cooldown expired, checking cluster health");
                                             checkAndScheduleAutoHeal();
                                         },
                                         autoHealConfig.startupCooldown());
            }

            /// Rebuild state from KVStore snapshot on leader activation.
            /// This ensures the new leader has complete knowledge of desired and actual state.
            void rebuildStateFromKVStore() {
                log.info("Rebuilding cluster deployment state from KVStore");
                kvStore.forEach(AetherKey.class, AetherValue.class, this::processKVEntry);
                log.info("Restored {} blueprints, {} slice states, and {} worker nodes from KVStore",
                         blueprints.size(),
                         sliceStates.size(),
                         workerNodes.size());
                // Trigger activation for any slices stuck in LOADED state
                triggerLoadedSliceActivation();
                // Clean up stale entries pointing to nodes not in topology
                cleanupStaleHttpRoutes();
                cleanupStaleSliceEntries();
                cleanupStaleEndpointEntries();
                // Clean up orphaned slice entries with no matching blueprint
                cleanupOrphanedSliceEntries();
                // Resume drain evictions for any nodes that were mid-drain
                resumeDrainEvictions();
            }

            /// Resume drain evictions for nodes that were DRAINING when the previous leader died.
            private void resumeDrainEvictions() {
                if (drainingNodes.isEmpty()) {
                    return;
                }
                log.info("Resuming drain evictions for {} nodes", drainingNodes.size());
                drainingNodes.forEach(this::evictNextSliceFromNode);
            }

            /// After state rebuild, check all LOADED slices and trigger activation if dependencies are ready.
            /// This handles slices that were LOADED when the previous leader died.
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
                    case NodeLifecycleKey lifecycleKey when value instanceof NodeLifecycleValue lifecycleValue ->
                    restoreDrainingNode(lifecycleKey, lifecycleValue);
                    case ActivationDirectiveKey activationKey when value instanceof ActivationDirectiveValue activationValue ->
                    restoreWorkerNode(activationKey, activationValue);
                    default -> {}
                }
            }

            private void restoreDrainingNode(NodeLifecycleKey key, NodeLifecycleValue value) {
                if (value.state() == NodeLifecycleState.DRAINING) {
                    drainingNodes.add(key.nodeId());
                    log.info("Restored draining node: {}", key.nodeId());
                }
            }

            private void restoreWorkerNode(ActivationDirectiveKey key, ActivationDirectiveValue value) {
                if (ActivationDirectiveValue.WORKER.equals(value.role())) {
                    workerNodes.add(key.nodeId());
                    log.trace("Restored worker node: {}", key.nodeId());
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
                    // Store original requested count — reconcile() handles actual allocation
                    blueprints.put(artifact,
                                   Blueprint.blueprint(artifact,
                                                       slice.instances(),
                                                       slice.minAvailable(),
                                                       Option.some(expanded.id())));
                }
            }

            private void restoreSliceTarget(SliceTargetKey sliceTargetKey, SliceTargetValue sliceTargetValue) {
                var artifact = sliceTargetKey.artifactBase()
                                             .withVersion(sliceTargetValue.currentVersion());
                var instances = sliceTargetValue.targetInstances();
                var minInstances = sliceTargetValue.effectiveMinInstances();
                blueprints.put(artifact,
                               Blueprint.blueprint(artifact, instances, minInstances, sliceTargetValue.owningBlueprint()));
                log.trace("Restored slice target: {} with {} instances (min: {})", artifact, instances, minInstances);
            }

            private void restoreSliceState(SliceNodeKey sliceNodeKey, SliceNodeValue sliceNodeValue) {
                sliceStates.put(sliceNodeKey, sliceNodeValue.state());
                updateTransitionalTimestamp(sliceNodeKey, sliceNodeValue.state());
                log.trace("Restored slice state: {} = {}", sliceNodeKey, sliceNodeValue.state());
            }

            @Override
            public void onAppBlueprintPut(ValuePut<AppBlueprintKey, AppBlueprintValue> valuePut) {
                handleAppBlueprintChange(valuePut.cause()
                                                 .key(),
                                         valuePut.cause()
                                                 .value());
            }

            @Override
            public void onSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut) {
                handleSliceTargetChange(valuePut.cause()
                                                .key(),
                                        valuePut.cause()
                                                .value());
            }

            @Override
            public void onSliceNodePut(ValuePut<SliceNodeKey, SliceNodeValue> valuePut) {
                trackSliceState(valuePut.cause()
                                        .key(),
                                valuePut.cause()
                                        .value());
            }

            @Override
            public void onVersionRoutingPut(ValuePut<VersionRoutingKey, VersionRoutingValue> valuePut) {
                var routingKey = valuePut.cause()
                                         .key();
                log.info("Rolling update started for {}", routingKey.artifactBase());
                activeRoutings.add(routingKey.artifactBase());
            }

            @Override
            public void onAppBlueprintRemove(ValueRemove<AppBlueprintKey, AppBlueprintValue> valueRemove) {
                handleAppBlueprintRemoval(valueRemove.cause()
                                                     .key());
            }

            @Override
            public void onSliceNodeRemove(ValueRemove<SliceNodeKey, SliceNodeValue> valueRemove) {
                handleSliceNodeRemoval(valueRemove.cause()
                                                  .key());
            }

            @Override
            public void onVersionRoutingRemove(ValueRemove<VersionRoutingKey, VersionRoutingValue> valueRemove) {
                handleRoutingRemoval(valueRemove.cause()
                                                .key());
            }

            private void handleSliceNodeRemoval(SliceNodeKey sliceNodeKey) {
                sliceStates.remove(sliceNodeKey);
                transitionalStateTimestamps.remove(sliceNodeKey);
                if (permanentlyFailed.contains(sliceNodeKey.artifact())) {
                    return;
                }
                SharedScheduler.schedule(this::reconcile, timeSpan(1).seconds());
            }

            private void handleAppBlueprintRemoval(AppBlueprintKey key) {
                // Guard: reject deletion if any of this blueprint's artifacts have active rolling updates
                var removedBlueprintId = key.blueprintId();
                var rollingUpdateArtifacts = blueprints.entrySet()
                                                       .stream()
                                                       .filter(e -> e.getValue()
                                                                     .owner()
                                                                     .equals(Option.some(removedBlueprintId)))
                                                       .map(java.util.Map.Entry::getKey)
                                                       .filter(a -> activeRoutings.contains(a.base()))
                                                       .toList();
                if (!rollingUpdateArtifacts.isEmpty()) {
                    log.warn("Cannot delete blueprint '{}' — artifacts {} have active rolling updates",
                             removedBlueprintId.artifact()
                                               .asString(),
                             rollingUpdateArtifacts);
                    return;
                }
                log.info("App blueprint '{}' removed",
                         removedBlueprintId.artifact()
                                           .asString());
                // Remove only blueprints owned by the removed app blueprint
                var artifactsToRemove = blueprints.entrySet()
                                                  .stream()
                                                  .filter(e -> e.getValue()
                                                                .owner()
                                                                .equals(Option.some(removedBlueprintId)))
                                                  .map(java.util.Map.Entry::getKey)
                                                  .toList();
                var consensusCommands = new ArrayList<KVCommand<AetherKey>>();
                for (var artifact : artifactsToRemove) {
                    blueprints.remove(artifact);
                    issueDeallocationCommands(artifact);
                    // Remove SliceTargetKey to clean up desired-state tracking
                    consensusCommands.add(new KVCommand.Remove<>(SliceTargetKey.sliceTargetKey(artifact.base())));
                }
                submitBatch(consensusCommands);
                // Schedule reconciliation to clean up any stuck UNLOADING entries
                SharedScheduler.schedule(this::reconcile, timeSpan(5).seconds());
            }

            @Override
            public void onTopologyChange(TopologyChangeNotification topologyChange) {
                log.info("Received topology change: {}", topologyChange);
                switch (topologyChange) {
                    case NodeAdded(NodeId addedNode, List<NodeId> topology) -> {
                        updateTopology(topology);
                        if (!seedNodes.contains(addedNode)) {
                            assignNodeRole(addedNode);
                        }
                        reconcile();
                    }
                    case NodeRemoved(NodeId removedNode, List<NodeId> topology) -> {
                        updateTopology(topology);
                        handleNodeRemoval(removedNode);
                        reconcile();
                    }
                    case NodeDown(NodeId downNode, List<NodeId> topology) -> {
                        log.warn("Node {} is down, triggering immediate reconciliation", downNode);
                        updateTopology(topology);
                        handleNodeRemoval(downNode);
                        reconcile();
                    }
                    default -> {}
                }
            }

            /// Assign a role to a newly joined non-seed node.
            /// If below coreMax (or unlimited), promote to core consensus participant.
            /// Otherwise, assign as a passive worker.
            /// Submits through consensus KV-Store so ALL nodes see the committed directive.
            private void assignNodeRole(NodeId addedNode) {
                var currentCoreCount = activeNodes.get()
                                                  .size();
                if (shouldPromoteToCore(currentCoreCount)) {
                    log.info("Promoting node {} to core consensus participant (core count: {}/{})",
                             addedNode,
                             currentCoreCount,
                             coreMax == 0
                             ? "unlimited"
                             : coreMax);
                    submitActivationDirective(addedNode, AetherValue.ActivationDirectiveValue.core());
                } else {
                    log.info("Assigning node {} as worker (core count at max: {})", addedNode, coreMax);
                    submitActivationDirective(addedNode, AetherValue.ActivationDirectiveValue.worker());
                }
            }

            private void submitActivationDirective(NodeId targetNode, AetherValue.ActivationDirectiveValue directive) {
                var command = new KVCommand.Put<AetherKey, AetherValue>(AetherKey.ActivationDirectiveKey.activationDirectiveKey(targetNode),
                                                                        directive);
                cluster.apply(List.of(command))
                       .onFailure(cause -> log.error("Failed to submit activation directive for {}: {}",
                                                     targetNode,
                                                     cause.message()));
            }

            private boolean shouldPromoteToCore(int currentCoreCount) {
                return coreMax == 0 || currentCoreCount < coreMax;
            }

            private void updateTopology(List<NodeId> topology) {
                activeNodes.set(List.copyOf(topology));
            }

            /// Filter active nodes to only those with ON_DUTY lifecycle state.
            /// Nodes without a lifecycle key are treated as NOT allocatable (conservative default).
            private List<NodeId> allocatableNodes() {
                return activeNodes.get()
                                  .stream()
                                  .filter(this::isNodeOnDuty)
                                  .toList();
            }

            /// Build an AllocationPool combining core nodes and worker nodes.
            /// Used for policy-based placement decisions.
            AllocationPool buildAllocationPool() {
                var communityWorkers = buildCommunityWorkerMap();
                return AllocationPool.allocationPool(allocatableNodes(), List.copyOf(workerNodes), communityWorkers);
            }

            private Map<String, List<NodeId>> buildCommunityWorkerMap() {
                if (communityGovernors.isEmpty()) {
                    return Map.of();
                }
                var result = new HashMap<String, List<NodeId>>();
                communityGovernors.forEach((communityId, announcement) -> result.put(communityId,
                                                                                     announcement.members()
                                                                                                 .isEmpty()
                                                                                     ? List.of(announcement.governorId())
                                                                                     : announcement.members()));
                return Map.copyOf(result);
            }

            /// Returns a snapshot of active community governor announcements.
            private Map<String, GovernorAnnouncementValue> activeCommunities() {
                return Map.copyOf(communityGovernors);
            }

            /// Check if a node has ON_DUTY lifecycle state in KV store.
            private boolean isNodeOnDuty(NodeId nodeId) {
                return kvStore.get(NodeLifecycleKey.nodeLifecycleKey(nodeId))
                              .filter(v -> v instanceof NodeLifecycleValue)
                              .map(v -> (NodeLifecycleValue) v)
                              .filter(v -> v.state() == NodeLifecycleState.ON_DUTY)
                              .isPresent();
            }

            @Override
            public void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> valuePut) {
                var nodeId = valuePut.cause()
                                     .key()
                                     .nodeId();
                var state = valuePut.cause()
                                    .value()
                                    .state();
                handleNodeLifecycleChange(nodeId, state);
            }

            private void handleNodeLifecycleChange(NodeId nodeId, NodeLifecycleState state) {
                switch (state) {
                    case DRAINING -> startDrainEviction(nodeId);
                    case ON_DUTY -> {
                        cancelDrainEviction(nodeId);
                        reconcile();
                    }
                    default -> {}
                }
            }

            @Override
            public void onActivationDirectivePut(ValuePut<ActivationDirectiveKey, ActivationDirectiveValue> valuePut) {
                var nodeId = valuePut.cause()
                                     .key()
                                     .nodeId();
                var role = valuePut.cause()
                                   .value()
                                   .role();
                handleActivationDirectivePut(nodeId, role);
            }

            private void handleActivationDirectivePut(NodeId nodeId, String role) {
                if (ActivationDirectiveValue.WORKER.equals(role)) {
                    if (workerNodes.add(nodeId)) {
                        log.info("Worker node {} registered, total workers: {}", nodeId, workerNodes.size());
                        reconcile();
                    }
                } else {
                    workerNodes.remove(nodeId);
                }
            }

            @Override
            public void onActivationDirectiveRemove(ValueRemove<ActivationDirectiveKey, ActivationDirectiveValue> valueRemove) {
                var nodeId = valueRemove.cause()
                                        .key()
                                        .nodeId();
                if (workerNodes.remove(nodeId)) {
                    log.info("Worker node {} deregistered, total workers: {}", nodeId, workerNodes.size());
                    reconcile();
                }
            }

            @Override
            public void onGovernorAnnouncementPut(ValuePut<GovernorAnnouncementKey, GovernorAnnouncementValue> valuePut) {
                var communityId = valuePut.cause()
                                          .key()
                                          .communityId();
                var announcement = valuePut.cause()
                                           .value();
                communityGovernors.put(communityId, announcement);
                log.info("Governor announced for community '{}': {} with {} members",
                         communityId,
                         announcement.governorId(),
                         announcement.memberCount());
            }

            @Override
            public void onGovernorAnnouncementRemove(ValueRemove<GovernorAnnouncementKey, GovernorAnnouncementValue> valueRemove) {
                var communityId = valueRemove.cause()
                                             .key()
                                             .communityId();
                communityGovernors.remove(communityId);
                log.info("Governor departed for community '{}'", communityId);
            }

            /// Start drain eviction: for each slice on the draining node, deploy replacement
            /// on another allocatable node, then issue UNLOAD on the draining node.
            private void startDrainEviction(NodeId drainingNode) {
                if (!drainingNodes.add(drainingNode)) {
                    log.debug("Drain eviction already in progress for {}", drainingNode);
                    return;
                }
                log.info("Starting drain eviction for node {}", drainingNode);
                evictNextSliceFromNode(drainingNode);
            }

            /// Cancel any ongoing drain eviction for a node (e.g., when it goes back to ON_DUTY).
            private void cancelDrainEviction(NodeId nodeId) {
                if (drainingNodes.remove(nodeId)) {
                    log.info("Cancelled drain eviction for node {} (returned to ON_DUTY)", nodeId);
                }
            }

            /// Evict one slice at a time from a draining node.
            /// When the replacement is ACTIVE, issue UNLOAD and schedule next eviction.
            private void evictNextSliceFromNode(NodeId drainingNode) {
                if (deactivated.get() || !drainingNodes.contains(drainingNode)) {
                    return;
                }
                var slicesOnNode = sliceStates.keySet()
                                              .stream()
                                              .filter(key -> key.nodeId()
                                                                .equals(drainingNode))
                                              .filter(key -> isLiveState(sliceStates.getOrDefault(key, SliceState.FAILED)))
                                              .toList();
                if (slicesOnNode.isEmpty()) {
                    completeDrain(drainingNode);
                    return;
                }
                var sliceKey = slicesOnNode.getFirst();
                log.info("Drain eviction: deploying replacement for {} from node {}", sliceKey.artifact(), drainingNode);
                deployReplacementForDrain(sliceKey);
            }

            /// Deploy a replacement instance for a slice being drained, then schedule
            /// the UNLOAD of the original once the replacement is verified ACTIVE.
            private void deployReplacementForDrain(SliceNodeKey originalKey) {
                var artifact = originalKey.artifact();
                var drainingNode = originalKey.nodeId();
                var targetNodes = allocatableNodes().stream()
                                                  .filter(n -> !n.equals(drainingNode))
                                                  .collect(Collectors.toSet());
                var allocated = issueAllocationsForNodes(artifact, 1, targetNodes);
                if (allocated == 0) {
                    log.warn("Drain eviction: no allocatable node for replacement of {} (will retry)", artifact);
                    SharedScheduler.schedule(() -> evictNextSliceFromNode(drainingNode), timeSpan(5).seconds());
                    return;
                }
                // Schedule check: once replacement is ACTIVE, unload from draining node
                SharedScheduler.schedule(() -> checkReplacementAndUnload(originalKey), timeSpan(3).seconds());
            }

            /// Check if a replacement for the drained slice is active, and if so, unload the original.
            private void checkReplacementAndUnload(SliceNodeKey originalKey) {
                if (deactivated.get() || !drainingNodes.contains(originalKey.nodeId())) {
                    return;
                }
                var artifact = originalKey.artifact();
                var drainingNode = originalKey.nodeId();
                // Count ACTIVE instances on non-draining nodes
                var hasActiveReplacement = sliceStates.entrySet()
                                                      .stream()
                                                      .filter(e -> e.getKey()
                                                                    .artifact()
                                                                    .equals(artifact))
                                                      .filter(e -> !e.getKey()
                                                                     .nodeId()
                                                                     .equals(drainingNode))
                                                      .anyMatch(e -> e.getValue() == SliceState.ACTIVE);
                if (hasActiveReplacement) {
                    log.info("Drain eviction: replacement ACTIVE for {}, unloading from {}", artifact, drainingNode);
                    issueUnloadCommand(originalKey);
                    SharedScheduler.schedule(() -> evictNextSliceFromNode(drainingNode), timeSpan(2).seconds());
                } else {
                    log.debug("Drain eviction: replacement not yet ACTIVE for {}, rechecking", artifact);
                    SharedScheduler.schedule(() -> checkReplacementAndUnload(originalKey), timeSpan(3).seconds());
                }
            }

            /// Complete drain: all slices evacuated. Write DECOMMISSIONED lifecycle state.
            private void completeDrain(NodeId drainingNode) {
                drainingNodes.remove(drainingNode);
                log.info("Drain complete for node {}, writing DECOMMISSIONED state", drainingNode);
                var command = new KVCommand.Put<AetherKey, AetherValue>(NodeLifecycleKey.nodeLifecycleKey(drainingNode),
                                                                        NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DECOMMISSIONED));
                cluster.apply(List.of(command))
                       .onFailure(cause -> log.error("Failed to write DECOMMISSIONED for {}: {}",
                                                     drainingNode,
                                                     cause.message()));
            }

            /// Compute cluster deficit: target size minus currently connected active nodes.
            /// Uses activeNodes (real-time network view from topology notifications) rather than
            /// topologyManager state, which retains killed nodes in SUSPECTED state during backoff.
            /// Passive nodes are already excluded from activeNodes by the network layer (Hello handshake
            /// carries NodeRole, and currentView() filters out passive peers).
            private int computeAutoHealDeficit() {
                return topologyManager.clusterSize() - activeNodes.get()
                                                                 .size();
            }

            /// Provision replacement nodes for the given deficit via ComputeProvider.
            private void provisionReplacements(int deficit) {
                computeProvider.onPresent(provider -> {
                                              for (int i = 0; i < deficit; i++) {
                                                  provider.provision(InstanceType.ON_DEMAND)
                                                          .onFailure(cause -> log.warn("AUTO-HEAL: Provisioning failed: {}",
                                                                                       cause.message()));
                                              }
                                          });
            }

            /// Schedule periodic auto-heal recheck if not already scheduled.
            /// Uses compareAndSet to avoid race conditions.
            private void scheduleAutoHealRecheck() {
                var future = SharedScheduler.scheduleAtFixedRate(this::autoHealRecheck, autoHealConfig.retryInterval());
                if (!autoHealFuture.compareAndSet(null, future)) {
                    future.cancel(false);
                }
            }

            /// Check if cluster is below target size and schedule auto-healing if a ComputeProvider is present.
            /// Cancels periodic recheck when cluster reaches target size.
            void checkAndScheduleAutoHeal() {
                if (computeProvider.isEmpty()) {
                    return;
                }
                var deficit = computeAutoHealDeficit();
                if (deficit <= 0) {
                    cancelAutoHeal();
                    return;
                }
                // During startup cooldown, suppress provisioning — nodes may still be joining
                if (cooldownActive.get()) {
                    log.trace("AUTO-HEAL: Cooldown active, deferring provisioning ({} node deficit)", deficit);
                    return;
                }
                var currentActiveSize = topologyManager.clusterSize() - deficit;
                log.info("AUTO-HEAL: Cluster size {} below target {}, provisioning {} replacement node(s)",
                         currentActiveSize,
                         topologyManager.clusterSize(),
                         deficit);
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
                    log.info("AUTO-HEAL: Cluster at target size {}, cancelling periodic recheck",
                             topologyManager.clusterSize());
                    cancelAutoHeal();
                    return;
                }
                var currentActiveSize = topologyManager.clusterSize() - deficit;
                log.info("AUTO-HEAL: Cluster still below target ({}/{}), provisioning {} node(s)",
                         currentActiveSize,
                         topologyManager.clusterSize(),
                         deficit);
                provisionReplacements(deficit);
            }

            private void cancelAutoHeal() {
                var future = autoHealFuture.getAndSet(null);
                if (future != null) {
                    future.cancel(false);
                    log.trace("AUTO-HEAL: Cancelled periodic recheck");
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
                        issueDeallocationCommands(oldArtifact);
                    }
                }
                var minInstances = value.effectiveMinInstances();
                log.info("Slice target changed for {}: {} instances (min: {})",
                         newArtifact,
                         desiredInstances,
                         minInstances);
                blueprints.put(newArtifact,
                               Blueprint.blueprint(newArtifact, desiredInstances, minInstances, value.owningBlueprint()));
                issueAllocationCommandsWithPlacement(newArtifact, desiredInstances, value.effectivePlacement());
            }

            private void handleAppBlueprintChange(AppBlueprintKey key, AppBlueprintValue value) {
                var expanded = value.blueprint();
                var nodes = allocatableNodes();
                log.info("App blueprint '{}' deployed with {} slices across {} allocatable nodes",
                         expanded.id()
                                 .asString(),
                         expanded.loadOrder()
                                 .size(),
                         nodes.size());
                // Capture previous expanded blueprint for atomicity rollback (before we overwrite blueprints map)
                var previousExpanded = capturePreviousBlueprint(expanded);
                buildDependencyMap(expanded);
                // Artifact exclusivity: reject if any artifact is already owned by a different blueprint
                for (var slice : expanded.loadOrder()) {
                    var artifactBase = slice.artifact()
                                            .base();
                    for (var bp : blueprints.values()) {
                        if (!artifactBase.equals(bp.artifact()
                                                   .base())) {
                            continue;
                        }
                        var conflict = bp.owner()
                                         .filter(o -> !o.equals(expanded.id()));
                        if (!conflict.isEmpty()) {
                            log.error("Blueprint '{}' rejected — artifact {} already owned by blueprint '{}'. "
                                      + "Deploy shared services as independent blueprints.",
                                      expanded.id()
                                              .asString(),
                                      slice.artifact(),
                                      conflict.fold(() -> "", BlueprintId::asString));
                            return;
                        }
                    }
                }
                var consensusCommands = new ArrayList<KVCommand<AetherKey>>();
                // Store the ORIGINAL requested instance count — not capped at available nodes.
                // Allocation is naturally limited by allocatableNodes(); reconcile() fills the gap
                // when more nodes register ON_DUTY lifecycle state.
                for (var slice : expanded.loadOrder()) {
                    var artifact = slice.artifact();
                    var requestedInstances = slice.instances();
                    log.info("Scheduling {} with {} requested instances ({} allocatable nodes)",
                             artifact,
                             requestedInstances,
                             nodes.size());
                    permanentlyFailed.remove(artifact);
                    blueprints.put(artifact,
                                   Blueprint.blueprint(artifact,
                                                       requestedInstances,
                                                       slice.minAvailable(),
                                                       Option.some(expanded.id())));
                    // Create SliceTargetKey so rolling updates can find the current version
                    consensusCommands.add(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(artifact.base()),
                                                              SliceTargetValue.sliceTargetValue(artifact.version(),
                                                                                                requestedInstances,
                                                                                                slice.minAvailable(),
                                                                                                Option.some(expanded.id()))));
                    issueAllocationCommandsWithPlacement(artifact, requestedInstances, lookupPlacement(artifact));
                }
                submitBatch(consensusCommands);
                // Track in-flight blueprint for atomicity enforcement
                trackInFlightBlueprint(expanded, previousExpanded);
            }

            private Option<ExpandedBlueprint> capturePreviousBlueprint(ExpandedBlueprint expanded) {
                if (atomicity != DeploymentAtomicity.ALL_OR_NOTHING || restoringBlueprints.contains(expanded.id())) {
                    return Option.empty();
                }
                return Option.option(inFlightBlueprints.get(expanded.id()))
                             .map(InFlightBlueprint::expanded);
            }

            private void trackInFlightBlueprint(ExpandedBlueprint expanded,
                                                Option<ExpandedBlueprint> previousExpanded) {
                if (atomicity == DeploymentAtomicity.ALL_OR_NOTHING && !restoringBlueprints.contains(expanded.id())) {
                    inFlightBlueprints.put(expanded.id(),
                                           InFlightBlueprint.inFlightBlueprint(expanded.id(), expanded, previousExpanded));
                }
            }

            /// Build dependency map from ExpandedBlueprint's ResolvedSlice dependencies.
            /// Each slice has its actual dependencies from the blueprint expansion.
            private void buildDependencyMap(ExpandedBlueprint expanded) {
                for (var slice : expanded.loadOrder()) {
                    var artifact = slice.artifact();
                    var dependencies = slice.dependencies();
                    sliceDependencies.put(artifact, dependencies);
                    log.trace("buildDependencyMap: Slice {} has {} dependencies: {}",
                              artifact,
                              dependencies.size(),
                              dependencies);
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
                    issueDeallocationCommands(oldArtifact);
                }
            }

            private void trackSliceState(SliceNodeKey sliceKey, SliceNodeValue sliceNodeValue) {
                var state = sliceNodeValue.state();
                var previousState = sliceStates.put(sliceKey, state);
                updateTransitionalTimestamp(sliceKey, state);
                log.trace("Slice {} on {} state: {} -> {}",
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
                    retryCounters.remove(sliceKey.artifact()
                                                 .asString());
                    activateDependentSlices(sliceKey.artifact());
                    // Track active slices for in-flight blueprints
                    trackBlueprintSliceActive(sliceKey.artifact());
                }
                // When slice enters FAILED state, classify and handle
                if (state == SliceState.FAILED) {
                    handleSliceFailure(sliceKey, sliceNodeValue);
                }
            }

            private void handleSliceFailure(SliceNodeKey sliceKey, SliceNodeValue sliceNodeValue) {
                var failureReason = sliceNodeValue.failureReason()
                                                  .or("Unknown failure");
                sliceStates.remove(sliceKey);
                transitionalStateTimestamps.remove(sliceKey);
                issueUnloadCommand(sliceKey);
                if (sliceNodeValue.fatal()) {
                    handleDeterministicFailure(sliceKey, failureReason);
                } else {
                    handleTransientFailure(sliceKey, failureReason);
                }
            }

            private void handleDeterministicFailure(SliceNodeKey sliceKey, String failureReason) {
                var artifact = sliceKey.artifact();
                if (permanentlyFailed.contains(artifact)) {
                    return;
                }
                permanentlyFailed.add(artifact);
                log.error("Deterministic failure for {} on {}: {} — will NOT retry",
                          artifact,
                          sliceKey.nodeId(),
                          failureReason);
                router.route(DeploymentFailed.deploymentFailed(artifact,
                                                               sliceKey.nodeId(),
                                                               SliceState.FAILED,
                                                               failureReason,
                                                               System.currentTimeMillis()));
                if (atomicity == DeploymentAtomicity.ALL_OR_NOTHING) {
                    rollbackBlueprintForArtifact(artifact);
                }
            }

            private void handleTransientFailure(SliceNodeKey sliceKey, String failureReason) {
                var retryCount = retryCounters.merge(sliceKey.artifact()
                                                             .asString(),
                                                     1,
                                                     Integer::sum);
                if (retryCount > MAX_RETRIES) {
                    log.error("Max retries ({}) exceeded for {} on {}: {} — giving up",
                              MAX_RETRIES,
                              sliceKey.artifact(),
                              sliceKey.nodeId(),
                              failureReason);
                    retryCounters.remove(sliceKey.artifact()
                                                 .asString());
                    router.route(DeploymentFailed.deploymentFailed(sliceKey.artifact(),
                                                                   sliceKey.nodeId(),
                                                                   SliceState.FAILED,
                                                                   failureReason,
                                                                   System.currentTimeMillis()));
                    return;
                }
                var delaySeconds = Math.min(1L<< (retryCount - 1), MAX_RETRY_DELAY_SECONDS);
                log.warn("Transient failure for {} on {} (attempt {}/{}): {} — retrying in {}s",
                         sliceKey.artifact(),
                         sliceKey.nodeId(),
                         retryCount,
                         MAX_RETRIES,
                         failureReason,
                         delaySeconds);
                SharedScheduler.schedule(this::reconcile, timeSpan(delaySeconds).seconds());
            }

            /// Try to activate a slice if all its dependencies are ACTIVE.
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

            /// Check if all dependencies are ACTIVE (at least one instance).
            private boolean allDependenciesActive(Set<Artifact> dependencies) {
                return dependencies.stream()
                                   .allMatch(this::isDependencyActive);
            }

            /// Check if a dependency has at least one ACTIVE instance.
            private boolean isDependencyActive(Artifact dependency) {
                return sliceStates.entrySet()
                                  .stream()
                                  .anyMatch(entry -> entry.getKey()
                                                          .artifact()
                                                          .equals(dependency) && entry.getValue() == SliceState.ACTIVE);
            }

            /// When a slice becomes ACTIVE, check if any LOADED slices that depend on it can now be activated.
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
                var value = SliceNodeValue.sliceNodeValue(SliceState.ACTIVATE);
                sliceNodeMap.put(sliceKey, value)
                            .onFailure(cause -> log.error("Failed to issue ACTIVATE command for {}: {}",
                                                          sliceKey,
                                                          cause.message()));
            }

            @SuppressWarnings("JBCT-RET-01") // DHT fire-and-forget write
            private void issueLoadCommand(SliceNodeKey sliceKey) {
                log.debug("Issuing LOAD command for {}", sliceKey);
                sliceStates.put(sliceKey, SliceState.LOAD);
                var timestamp = System.currentTimeMillis();
                router.route(DeploymentStarted.deploymentStarted(sliceKey.artifact(), sliceKey.nodeId(), timestamp));
                sliceNodeMap.put(sliceKey,
                                 SliceNodeValue.sliceNodeValue(SliceState.LOAD))
                            .onFailure(cause -> handleSliceNodeWriteFailure(sliceKey, cause));
            }

            @SuppressWarnings("JBCT-RET-01") // DHT fire-and-forget write
            private void issueUnloadCommand(SliceNodeKey sliceKey) {
                log.debug("Issuing UNLOAD command for {}", sliceKey);
                sliceNodeMap.put(sliceKey,
                                 SliceNodeValue.sliceNodeValue(SliceState.UNLOAD))
                            .onFailure(cause -> log.error("Failed to issue UNLOAD command for {}: {}",
                                                          sliceKey,
                                                          cause.message()));
            }

            @SuppressWarnings("JBCT-RET-01") // DHT fire-and-forget write
            private void removeSliceNodeKey(SliceNodeKey sliceKey) {
                sliceNodeMap.remove(sliceKey)
                            .onFailure(cause -> log.error("Failed to remove slice-node-key {}: {}",
                                                          sliceKey,
                                                          cause.message()));
            }

            private void submitBatch(List<KVCommand<AetherKey>> commands) {
                if (commands.isEmpty()) {
                    return;
                }
                cluster.apply(commands)
                       .onFailure(cause -> handleBatchFailure(cause, commands));
            }

            private void handleBatchFailure(Cause cause, List<KVCommand<AetherKey>> commands) {
                if (deactivated.get()) {
                    log.debug("Suppressing batch failure handling - Active state deactivated");
                    return;
                }
                log.error("Batch consensus write failed ({} commands): {}", commands.size(), cause.message());
                SharedScheduler.schedule(this::reconcile, timeSpan(5).seconds());
            }

            private void handleSliceNodeWriteFailure(SliceNodeKey sliceKey, Cause cause) {
                log.error("DHT write failed for {}: {}", sliceKey, cause.message());
                sliceStates.remove(sliceKey);
                SharedScheduler.schedule(this::reconcile, timeSpan(5).seconds());
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
                sliceKeysToRemove.forEach(transitionalStateTimestamps::remove);
                // Remove slice-node keys via DHT
                sliceKeysToRemove.forEach(this::removeSliceNodeKey);
                // Find endpoint keys for the removed node by scanning KVStore snapshot
                var endpointKeysToRemove = findEndpointKeysForNode(removedNode);
                // Clean up HTTP routes containing the removed node
                var httpRouteCommands = cleanupHttpRoutesForNode(removedNode);
                // Combine consensus-only commands (endpoints, HTTP routes, lifecycle)
                List<KVCommand<AetherKey>> consensusCommands = new ArrayList<>();
                endpointKeysToRemove.stream()
                                    .<KVCommand<AetherKey>> map(KVCommand.Remove::new)
                                    .forEach(consensusCommands::add);
                consensusCommands.addAll(httpRouteCommands);
                // Remove lifecycle key for departed node
                consensusCommands.add(new KVCommand.Remove<>(NodeLifecycleKey.nodeLifecycleKey(removedNode)));
                // Remove from KVStore to prevent stale state after leader changes
                if (!consensusCommands.isEmpty()) {
                    cluster.apply(consensusCommands)
                           .onFailure(cause -> log.error("Failed to remove keys for departed node {}: {}",
                                                         removedNode,
                                                         cause.message()));
                }
                // Remove from worker set if this was a worker
                workerNodes.remove(removedNode);
                log.info("Removed {} slice states, {} endpoints, and {} HTTP route updates for departed node {}",
                         sliceKeysToRemove.size(),
                         endpointKeysToRemove.size(),
                         httpRouteCommands.size(),
                         removedNode);
            }

            private List<EndpointKey> findEndpointKeysForNode(NodeId nodeId) {
                var result = new ArrayList<EndpointKey>();
                kvStore.forEach(EndpointKey.class,
                                EndpointValue.class,
                                (key, value) -> collectEndpointKeyForNode(result, key, value, nodeId));
                return result;
            }

            private void collectEndpointKeyForNode(List<EndpointKey> result,
                                                   EndpointKey endpointKey,
                                                   EndpointValue endpointValue,
                                                   NodeId nodeId) {
                if (endpointValue.nodeId()
                                 .equals(nodeId)) {
                    result.add(endpointKey);
                }
            }

            /// Clean up HTTP routes that reference the removed node.
            /// Each node has independent route keys — just remove the departed node's keys.
            private List<KVCommand<AetherKey>> cleanupHttpRoutesForNode(NodeId removedNode) {
                var commands = new java.util.ArrayList<KVCommand<AetherKey>>();
                kvStore.forEach(HttpNodeRouteKey.class,
                                HttpNodeRouteValue.class,
                                (key, value) -> collectRouteKeyForNode(commands, key, removedNode));
                return commands;
            }

            private void collectRouteKeyForNode(List<KVCommand<AetherKey>> commands,
                                                HttpNodeRouteKey key,
                                                NodeId removedNode) {
                if (key.nodeId()
                       .equals(removedNode)) {
                    commands.add(new KVCommand.Remove<>(key));
                }
            }

            /// Remove HTTP route entries that reference nodes not in the current topology.
            /// This handles cases where nodes died before the leader could clean up their routes.
            private void cleanupStaleHttpRoutes() {
                var currentNodes = new HashSet<>(activeNodes.get());
                var commands = new java.util.ArrayList<KVCommand<AetherKey>>();
                kvStore.forEach(HttpNodeRouteKey.class,
                                HttpNodeRouteValue.class,
                                (key, _) -> collectStaleRouteKey(commands, key, currentNodes));
                if (!commands.isEmpty()) {
                    log.debug("Cleaning up {} stale HTTP node route entries", commands.size());
                    cluster.apply(commands)
                           .onFailure(cause -> log.error("Failed to clean up stale HTTP routes: {}",
                                                         cause.message()));
                }
            }

            private void collectStaleRouteKey(List<KVCommand<AetherKey>> commands,
                                              HttpNodeRouteKey key,
                                              Set<NodeId> currentNodes) {
                if (!currentNodes.contains(key.nodeId())) {
                    commands.add(new KVCommand.Remove<>(key));
                }
            }

            /// Remove slice state entries for nodes not in the current topology.
            /// This handles cases where nodes died before the leader could clean up their slice entries.
            private void cleanupStaleSliceEntries() {
                var currentNodes = new HashSet<>(activeNodes.get());
                var staleKeys = sliceStates.keySet()
                                           .stream()
                                           .filter(key -> !currentNodes.contains(key.nodeId()))
                                           .toList();
                if (staleKeys.isEmpty()) {
                    return;
                }
                staleKeys.forEach(sliceStates::remove);
                List<KVCommand<AetherKey>> commands = staleKeys.stream()
                                                               .<KVCommand<AetherKey>> map(KVCommand.Remove::new)
                                                               .toList();
                log.info("Cleaning up {} stale slice entries", staleKeys.size());
                cluster.apply(commands)
                       .onFailure(cause -> log.error("Failed to clean up stale slice entries: {}",
                                                     cause.message()));
            }

            /// Remove endpoint entries for nodes not in the current topology.
            /// This handles cases where nodes died before the leader could clean up their endpoint entries.
            private void cleanupStaleEndpointEntries() {
                var currentNodes = new HashSet<>(activeNodes.get());
                var staleKeys = new ArrayList<EndpointKey>();
                kvStore.forEach(EndpointKey.class,
                                EndpointValue.class,
                                (key, value) -> collectStaleEndpointKey(staleKeys, key, value, currentNodes));
                if (staleKeys.isEmpty()) {
                    return;
                }
                List<KVCommand<AetherKey>> commands = staleKeys.stream()
                                                               .<KVCommand<AetherKey>> map(KVCommand.Remove::new)
                                                               .toList();
                log.info("Cleaning up {} stale endpoint entries", staleKeys.size());
                cluster.apply(commands)
                       .onFailure(cause -> log.error("Failed to clean up stale endpoint entries: {}",
                                                     cause.message()));
            }

            private void collectStaleEndpointKey(List<EndpointKey> result,
                                                 EndpointKey endpointKey,
                                                 EndpointValue endpointValue,
                                                 Set<NodeId> currentNodes) {
                if (!currentNodes.contains(endpointValue.nodeId())) {
                    result.add(endpointKey);
                }
            }

            /// Remove slice state entries whose artifact has no matching blueprint.
            /// These are orphaned entries from incomplete undeploy operations where
            /// the SliceTargetKey was removed but the SliceNodeKey cleanup didn't complete
            /// before a leader change.
            private void cleanupOrphanedSliceEntries() {
                var orphanedEntries = sliceStates.entrySet()
                                                 .stream()
                                                 .filter(entry -> !blueprints.containsKey(entry.getKey()
                                                                                               .artifact()))
                                                 .toList();
                if (orphanedEntries.isEmpty()) {
                    return;
                }
                for (var entry : orphanedEntries) {
                    var key = entry.getKey();
                    var state = entry.getValue();
                    sliceStates.remove(key);
                    if (state == SliceState.UNLOAD || state == SliceState.UNLOADING) {
                        // Already in teardown, just remove the DHT entry
                        removeSliceNodeKey(key);
                    } else {
                        // Issue UNLOAD to trigger proper teardown on the node
                        issueUnloadCommand(key);
                    }
                }
                log.info("Cleaning up {} orphaned slice entries (no matching blueprint)", orphanedEntries.size());
            }

            /// Issue allocation commands across cluster nodes using round-robin strategy via DHT.
            @SuppressWarnings("JBCT-RET-01") // DHT fire-and-forget writes
            private void issueAllocationCommands(Artifact artifact, int desiredInstances) {
                if (hasNoAllocatableNodes(artifact)) {
                    return;
                }
                var currentInstances = getCurrentInstances(artifact);
                logAllocationAttempt(artifact, desiredInstances, currentInstances);
                issueAdjustmentCommands(artifact, desiredInstances, currentInstances);
            }

            /// Check if there are no allocatable nodes available for allocation.
            private boolean hasNoAllocatableNodes(Artifact artifact) {
                if (allocatableNodes().isEmpty()) {
                    log.warn("No allocatable nodes available for allocation of {}", artifact);
                    return true;
                }
                return false;
            }

            /// Log the allocation attempt details.
            private void logAllocationAttempt(Artifact artifact,
                                              int desiredInstances,
                                              List<SliceNodeKey> currentInstances) {
                log.debug("Allocating {} instances of {} (current: {}) across {} allocatable nodes",
                          desiredInstances,
                          artifact,
                          currentInstances.size(),
                          allocatableNodes().size());
            }

            /// Issue adjustment commands by scaling up or down as needed via DHT.
            @SuppressWarnings("JBCT-RET-01") // DHT fire-and-forget writes
            private void issueAdjustmentCommands(Artifact artifact,
                                                 int desiredInstances,
                                                 List<SliceNodeKey> currentInstances) {
                var currentCount = currentInstances.size();
                if (desiredInstances > currentCount) {
                    issueScaleUpCommands(artifact, desiredInstances - currentCount, currentInstances);
                } else if (desiredInstances < currentCount) {
                    issueScaleDownCommands(artifact, currentCount - desiredInstances, currentInstances);
                }
            }

            @SuppressWarnings("JBCT-RET-01") // DHT fire-and-forget writes
            private void issueScaleUpCommands(Artifact artifact,
                                              int toAdd,
                                              List<SliceNodeKey> existingInstances) {
                var nodes = allocatableNodes();
                log.debug("issueScaleUpCommands: artifact={}, toAdd={}, allocatableNodes={}, nodeIds={}",
                          artifact,
                          toAdd,
                          nodes.size(),
                          nodes);
                var nodesWithInstances = existingInstances.stream()
                                                          .map(SliceNodeKey::nodeId)
                                                          .collect(Collectors.toSet());
                var allocated = 0;
                // Phase 1: Allocate to truly empty nodes (nodes with NO slices at all)
                var trulyEmptyNodes = findTrulyEmptyNodes();
                log.debug("issueScaleUpCommands: found {} truly empty nodes: {}",
                          trulyEmptyNodes.size(),
                          trulyEmptyNodes);
                var emptyNodeCount = issueAllocationsForNodes(artifact, toAdd, trulyEmptyNodes);
                allocated += emptyNodeCount;
                log.debug("issueScaleUpCommands: allocated {} instances to truly empty nodes", emptyNodeCount);
                var remaining = toAdd - allocated;
                if (remaining <= 0) {
                    return;
                }
                // Phase 2: Allocate to nodes without THIS artifact (but may have other slices)
                var emptyForArtifactCount = issueAllocationsForEmptyNodes(artifact, remaining, nodesWithInstances);
                allocated += emptyForArtifactCount;
                log.debug("issueScaleUpCommands: allocated {} instances to nodes without this artifact, remaining={}",
                          emptyForArtifactCount,
                          remaining - emptyForArtifactCount);
                // Phase 3: Round-robin for any remaining
                issueRoundRobinAllocations(artifact, toAdd - allocated);
            }

            /// Find allocatable nodes that have absolutely no slices deployed (truly empty).
            /// These are ideal candidates for new deployments (e.g., AUTO-HEAL nodes).
            private Set<NodeId> findTrulyEmptyNodes() {
                var nodesWithAnySlice = sliceStates.keySet()
                                                   .stream()
                                                   .map(SliceNodeKey::nodeId)
                                                   .collect(Collectors.toSet());
                return allocatableNodes().stream()
                                       .filter(node -> !nodesWithAnySlice.contains(node))
                                       .collect(Collectors.toSet());
            }

            /// Issue allocation commands for a specific set of nodes via DHT.
            private int issueAllocationsForNodes(Artifact artifact,
                                                 int toAdd,
                                                 Set<NodeId> targetNodes) {
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

            private int issueAllocationsForEmptyNodes(Artifact artifact,
                                                      int toAdd,
                                                      Set<NodeId> nodesWithInstances) {
                var nodes = allocatableNodes();
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

            @SuppressWarnings("JBCT-RET-01") // DHT fire-and-forget writes
            private void issueRoundRobinAllocations(Artifact artifact, int remaining) {
                if (remaining <= 0) {
                    return;
                }
                var nodes = allocatableNodes();
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

            @SuppressWarnings("JBCT-RET-01") // DHT fire-and-forget writes
            private void issueScaleDownCommands(Artifact artifact,
                                                int toRemove,
                                                List<SliceNodeKey> existingInstances) {
                var blueprint = blueprints.get(artifact);
                var minInstances = blueprint != null
                                   ? blueprint.minInstances()
                                   : 1;
                var activeCount = existingInstances.size();
                // Enforce budget: do not scale below minInstances
                var maxRemovable = Math.max(0, activeCount - minInstances);
                var actualRemove = Math.min(toRemove, maxRemovable);
                if (actualRemove < toRemove) {
                    log.info("Budget enforcement: capping scale-down of {} from {} to {} (min: {}, active: {})",
                             artifact,
                             toRemove,
                             actualRemove,
                             minInstances,
                             activeCount);
                }
                if (actualRemove == 0) {
                    return;
                }
                // Remove from the end (LIFO to maintain round-robin balance)
                existingInstances.stream()
                                 .skip(Math.max(0, activeCount - actualRemove))
                                 .forEach(this::issueUnloadCommand);
            }

            private List<SliceNodeKey> getCurrentInstances(Artifact artifact) {
                var currentNodes = activeNodes.get();
                return sliceStates.entrySet()
                                  .stream()
                                  .filter(entry -> entry.getKey()
                                                        .artifact()
                                                        .equals(artifact))
                                  .filter(entry -> currentNodes.contains(entry.getKey()
                                                                              .nodeId()))
                                  .filter(entry -> isLiveState(entry.getValue()))
                                  .map(Map.Entry::getKey)
                                  .toList();
            }

            private boolean isLiveState(SliceState state) {
                return state != SliceState.FAILED && state != SliceState.UNLOAD && state != SliceState.UNLOADING;
            }

            /// Record or clear the timestamp when a slice enters or leaves a transitional state.
            private void updateTransitionalTimestamp(SliceNodeKey sliceKey, SliceState state) {
                if (state.isTransitional()) {
                    transitionalStateTimestamps.putIfAbsent(sliceKey, System.currentTimeMillis());
                } else {
                    transitionalStateTimestamps.remove(sliceKey);
                }
            }

            /// Detect slices stuck in transitional states (LOADING, ACTIVATING, DEACTIVATING, UNLOADING)
            /// longer than 2x their configured timeout. For stuck LOADING/ACTIVATING, issue UNLOAD to reset
            /// and let normal reconciliation re-deploy. For stuck DEACTIVATING/UNLOADING, force-remove
            /// from KV store to clean up.
            private void detectStuckTransitionalStates() {
                var now = System.currentTimeMillis();
                var stuckEntries = transitionalStateTimestamps.entrySet()
                                                              .stream()
                                                              .filter(entry -> isStuckTransitional(entry.getKey(),
                                                                                                   entry.getValue(),
                                                                                                   now))
                                                              .map(Map.Entry::getKey)
                                                              .toList();
                if (stuckEntries.isEmpty()) {
                    return;
                }
                log.warn("Detected {} slices stuck in transitional states", stuckEntries.size());
                stuckEntries.forEach(this::issueStuckRemediationCommand);
            }

            /// Check whether a slice has exceeded 2x its transitional state timeout.
            private boolean isStuckTransitional(SliceNodeKey sliceKey, long enteredAt, long now) {
                var state = sliceStates.get(sliceKey);
                if (state == null || !state.isTransitional()) {
                    return false;
                }
                return state.timeout()
                            .filter(timeout -> (now - enteredAt) > timeout.millis() * STUCK_TIMEOUT_MULTIPLIER)
                            .isPresent();
            }

            /// Issue the appropriate remediation command for a stuck slice via DHT.
            /// LOADING/ACTIVATING: issue UNLOAD to reset, reconciliation will re-deploy.
            /// DEACTIVATING/UNLOADING: force-remove from DHT to clean up.
            @SuppressWarnings("JBCT-RET-01") // DHT fire-and-forget writes
            private void issueStuckRemediationCommand(SliceNodeKey sliceKey) {
                var state = sliceStates.get(sliceKey);
                if (state == null) {
                    return;
                }
                transitionalStateTimestamps.remove(sliceKey);
                switch (state) {
                    case LOADING, ACTIVATING -> {
                        log.warn("Force-resetting stuck {} slice {} on {} — issuing UNLOAD",
                                 state,
                                 sliceKey.artifact(),
                                 sliceKey.nodeId());
                        sliceStates.remove(sliceKey);
                        issueUnloadCommand(sliceKey);
                    }
                    case DEACTIVATING, UNLOADING -> {
                        log.warn("Force-removing stuck {} slice {} on {} from DHT",
                                 state,
                                 sliceKey.artifact(),
                                 sliceKey.nodeId());
                        sliceStates.remove(sliceKey);
                        removeSliceNodeKey(sliceKey);
                    }
                    default -> {}
                }
            }

            @SuppressWarnings("JBCT-RET-01") // DHT fire-and-forget writes
            private void issueDeallocationCommands(Artifact artifact) {
                getCurrentInstances(artifact).forEach(this::issueUnloadCommand);
                // Also remove worker directive if it exists
                removeWorkerDirective(artifact);
            }

            /// Issue allocation commands, considering placement policy.
            /// For policies that target workers, writes WorkerSliceDirectiveKey/Value to consensus.
            /// When communities exist, distributes instances proportionally across communities.
            @SuppressWarnings("JBCT-RET-01")
            private void issueAllocationCommandsWithPlacement(Artifact artifact,
                                                              int desiredInstances,
                                                              String placement) {
                var policy = PlacementPolicy.valueOf(placement);
                var pool = buildAllocationPool();
                var targetNodes = pool.nodesForPolicy(policy);
                if (targetNodes.isEmpty()) {
                    log.warn("No nodes available for placement {} of {}, falling back to core", placement, artifact);
                    issueAllocationCommands(artifact, desiredInstances);
                    return;
                }
                // If policy targets workers (and workers are available), write worker directive(s)
                if (policy != PlacementPolicy.CORE_ONLY && pool.hasWorkers()) {
                    if (pool.hasCommunities()) {
                        distributeToCommunities(artifact, desiredInstances, placement);
                    } else {
                        writeWorkerDirective(artifact, desiredInstances, placement);
                    }
                }
                // For CORE_ONLY or fallback, use existing core allocation
                if (policy == PlacementPolicy.CORE_ONLY || (policy == PlacementPolicy.WORKERS_PREFERRED && !pool.hasWorkers())) {
                    issueAllocationCommands(artifact, desiredInstances);
                }
                // For ALL, allocate on both core and workers
                if (policy == PlacementPolicy.ALL) {
                    issueAllocationCommands(artifact, desiredInstances);
                }
            }

            @SuppressWarnings("JBCT-RET-01")
            private void writeWorkerDirective(Artifact artifact, int targetInstances, String placement) {
                var key = WorkerSliceDirectiveKey.workerSliceDirectiveKey(artifact);
                var value = WorkerSliceDirectiveValue.workerSliceDirectiveValue(artifact, targetInstances, placement);
                var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);
                cluster.apply(List.of(command))
                       .onSuccess(_ -> log.info("Written worker directive for {} with {} instances",
                                                artifact,
                                                targetInstances))
                       .onFailure(cause -> log.error("Failed to write worker directive for {}: {}",
                                                     artifact,
                                                     cause.message()));
            }

            @SuppressWarnings("JBCT-RET-01")
            private void writeWorkerDirective(Artifact artifact,
                                              int targetInstances,
                                              String placement,
                                              String communityId) {
                var key = WorkerSliceDirectiveKey.workerSliceDirectiveKey(artifact, communityId);
                var value = WorkerSliceDirectiveValue.workerSliceDirectiveValue(artifact,
                                                                                targetInstances,
                                                                                placement,
                                                                                communityId);
                var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);
                cluster.apply(List.of(command))
                       .onSuccess(_ -> log.info("Written worker directive for {} community '{}' with {} instances",
                                                artifact,
                                                communityId,
                                                targetInstances))
                       .onFailure(cause -> log.error("Failed to write worker directive for {} community '{}': {}",
                                                     artifact,
                                                     communityId,
                                                     cause.message()));
            }

            /// Distribute instances proportionally across communities by member count.
            private void distributeToCommunities(Artifact artifact, int desiredInstances, String placement) {
                var communities = activeCommunities();
                var totalMembers = communities.values()
                                              .stream()
                                              .mapToInt(GovernorAnnouncementValue::memberCount)
                                              .sum();
                if (totalMembers == 0) {
                    writeWorkerDirective(artifact, desiredInstances, placement);
                    return;
                }
                var sorted = new ArrayList<>(communities.entrySet());
                sorted.sort(Comparator.<Map.Entry<String, GovernorAnnouncementValue>> comparingInt(e -> e.getValue()
                                                                                                         .memberCount())
                                      .reversed());
                var remaining = desiredInstances;
                for (var i = 0; i < sorted.size(); i++) {
                    var share = computeCommunityShare(i, sorted, desiredInstances, totalMembers, remaining);
                    if (share > 0) {
                        writeWorkerDirective(artifact,
                                             share,
                                             placement,
                                             sorted.get(i)
                                                   .getKey());
                        remaining -= share;
                    }
                }
                assignRemainder(artifact, remaining, placement, sorted);
            }

            /// Compute instance share for a community: largest gets remainder, others get proportional.
            private int computeCommunityShare(int index,
                                              List<Map.Entry<String, GovernorAnnouncementValue>> sorted,
                                              int desiredInstances,
                                              int totalMembers,
                                              int remaining) {
                if (index == 0) {
                    return computeLargestCommunityShare(sorted, desiredInstances, totalMembers, remaining);
                }
                var memberCount = sorted.get(index)
                                        .getValue()
                                        .memberCount();
                var proportional = Math.max(1, Math.round((float) desiredInstances * memberCount / totalMembers));
                return Math.min(proportional, remaining);
            }

            /// Largest community gets what remains after proportional shares for smaller communities.
            private int computeLargestCommunityShare(List<Map.Entry<String, GovernorAnnouncementValue>> sorted,
                                                     int desiredInstances,
                                                     int totalMembers,
                                                     int remaining) {
                var share = remaining;
                for (var j = 1; j < sorted.size(); j++) {
                    var otherCount = sorted.get(j)
                                           .getValue()
                                           .memberCount();
                    share -= Math.max(1, Math.round((float) desiredInstances * otherCount / totalMembers));
                }
                return Math.min(Math.max(1, share), remaining);
            }

            /// Assign any remaining instances (from rounding) to the largest community.
            private void assignRemainder(Artifact artifact,
                                         int remaining,
                                         String placement,
                                         List<Map.Entry<String, GovernorAnnouncementValue>> sorted) {
                if (remaining > 0) {
                    writeWorkerDirective(artifact,
                                         remaining,
                                         placement,
                                         sorted.getFirst()
                                               .getKey());
                }
            }

            @SuppressWarnings("JBCT-RET-01")
            private void removeWorkerDirective(Artifact artifact) {
                var commands = new ArrayList<KVCommand<AetherKey>>();
                commands.add(new KVCommand.Remove<>(WorkerSliceDirectiveKey.workerSliceDirectiveKey(artifact)));
                for (var communityId : communityGovernors.keySet()) {
                    commands.add(new KVCommand.Remove<>(WorkerSliceDirectiveKey.workerSliceDirectiveKey(artifact,
                                                                                                        communityId)));
                }
                cluster.apply(commands)
                       .onFailure(cause -> log.debug("No worker directive to remove for {}: {}",
                                                     artifact,
                                                     cause.message()));
            }

            /// Look up placement for an artifact from its SliceTargetValue in KVStore.
            private String lookupPlacement(Artifact artifact) {
                return kvStore.get(SliceTargetKey.sliceTargetKey(artifact.base()))
                              .filter(v -> v instanceof SliceTargetValue)
                              .map(v -> ((SliceTargetValue) v).effectivePlacement())
                              .or("CORE_ONLY");
            }

            /// Reconcile desired state (blueprints) with actual state (slice states).
            /// Called on leader activation, topology changes, etc.
            void reconcile() {
                if (deactivated.get()) {
                    log.debug("Suppressing reconciliation - Active state deactivated");
                    return;
                }
                log.debug("Performing cluster reconciliation with {} blueprints and {} active nodes",
                          blueprints.size(),
                          activeNodes.get()
                                     .size());
                var reconciled = 0;
                for (var blueprint : blueprints.values()) {
                    var artifact = blueprint.artifact();
                    if (permanentlyFailed.contains(artifact)) {
                        continue;
                    }
                    var desiredInstances = blueprint.instances();
                    var currentInstances = getCurrentInstances(artifact);
                    if (currentInstances.size() != desiredInstances) {
                        log.info("Reconciliation: {} has {} instances, desired {} - adjusting",
                                 artifact,
                                 currentInstances.size(),
                                 desiredInstances);
                        issueAllocationCommands(artifact, desiredInstances);
                        reconciled++;
                    }
                }
                log.debug("Reconciliation complete: {} of {} blueprints required adjustment",
                          reconciled,
                          blueprints.size());
                cleanupOrphanedSliceEntries();
                detectStuckTransitionalStates();
            }

            /// Track a slice becoming ACTIVE in its parent blueprint.
            /// When all slices are active, remove from in-flight tracking.
            private void trackBlueprintSliceActive(Artifact artifact) {
                for (var entry : inFlightBlueprints.entrySet()) {
                    var inflight = entry.getValue();
                    if (inflight.pendingSlices()
                                .remove(artifact)) {
                        inflight.activeSlices()
                                .add(artifact);
                        if (inflight.pendingSlices()
                                    .isEmpty()) {
                            log.info("Blueprint {} fully deployed — all {} slices active",
                                     entry.getKey()
                                          .asString(),
                                     inflight.activeSlices()
                                             .size());
                            inFlightBlueprints.remove(entry.getKey());
                        }
                    }
                }
            }

            /// Roll back the blueprint containing the failed artifact.
            @SuppressWarnings("JBCT-RET-01")
            private void rollbackBlueprintForArtifact(Artifact failedArtifact) {
                for (var entry : inFlightBlueprints.entrySet()) {
                    var blueprintId = entry.getKey();
                    var inflight = entry.getValue();
                    if (!inflight.pendingSlices()
                                 .contains(failedArtifact) && !inflight.activeSlices()
                                                                       .contains(failedArtifact)) {
                        continue;
                    }
                    // Skip if artifact is in an active rolling update — rolling update manager handles those
                    if (activeRoutings.contains(failedArtifact.base())) {
                        log.info("Skipping blueprint rollback for {} — artifact {} is in active rolling update",
                                 blueprintId.asString(),
                                 failedArtifact);
                        continue;
                    }
                    log.warn("ALL_OR_NOTHING: Deterministic failure of {} triggers rollback of blueprint {}",
                             failedArtifact,
                             blueprintId.asString());
                    inFlightBlueprints.remove(blueprintId);
                    inflight.previousBlueprint()
                            .apply(() -> unloadBlueprintSlices(inflight),
                                   previous -> restorePreviousBlueprint(blueprintId, previous));
                    break;
                }
            }

            /// Unload all slices from a failed new blueprint.
            private void unloadBlueprintSlices(InFlightBlueprint inflight) {
                var allSlices = new HashSet<>(inflight.pendingSlices());
                allSlices.addAll(inflight.activeSlices());
                var consensusCommands = new ArrayList<KVCommand<AetherKey>>();
                for (var artifact : allSlices) {
                    blueprints.remove(artifact);
                    issueDeallocationCommands(artifact);
                    consensusCommands.add(new KVCommand.Remove<>(SliceTargetKey.sliceTargetKey(artifact.base())));
                }
                // Remove the app blueprint from KV-Store
                var bpKey = new AppBlueprintKey(inflight.id());
                consensusCommands.add(new KVCommand.Remove<>(bpKey));
                log.info("ALL_OR_NOTHING: Unloading {} slices from failed blueprint {}",
                         allSlices.size(),
                         inflight.id()
                                 .asString());
                submitBatch(consensusCommands);
            }

            /// Restore the previous blueprint version by re-submitting it via KV-Store.
            private void restorePreviousBlueprint(BlueprintId blueprintId, ExpandedBlueprint previous) {
                restoringBlueprints.add(blueprintId);
                log.info("ALL_OR_NOTHING: Restoring previous blueprint {} with {} slices",
                         blueprintId.asString(),
                         previous.loadOrder()
                                 .size());
                var bpKey = new AppBlueprintKey(blueprintId);
                var bpValue = new AppBlueprintValue(previous);
                var command = new KVCommand.Put<AetherKey, AetherValue>(bpKey, bpValue);
                cluster.apply(List.of(command))
                       .onSuccess(_ -> SharedScheduler.schedule(() -> restoringBlueprints.remove(blueprintId),
                                                                timeSpan(5).seconds()))
                       .onFailure(cause -> handleBlueprintRestoreFailure(blueprintId, cause));
            }

            private void handleBlueprintRestoreFailure(BlueprintId blueprintId, Cause cause) {
                log.error("ALL_OR_NOTHING: Failed to restore previous blueprint {}: {}",
                          blueprintId.asString(),
                          cause.message());
                restoringBlueprints.remove(blueprintId);
            }
        }
    }

    /// Blueprint representation (desired state).
    /// @param artifact the artifact to deploy
    /// @param instances current desired instance count
    /// @param minInstances minimum instance count (hard floor for scale-down)
    /// @param owner the owning app blueprint ID, if this artifact was deployed via an app blueprint
    record Blueprint(Artifact artifact, int instances, int minInstances, Option<BlueprintId> owner) {
        static Blueprint blueprint(Artifact artifact, int instances, int minInstances, Option<BlueprintId> owner) {
            return new Blueprint(artifact, instances, minInstances, owner);
        }

        static Blueprint blueprint(Artifact artifact, int instances, int minInstances) {
            return new Blueprint(artifact, instances, minInstances, Option.empty());
        }

        static Blueprint blueprint(Artifact artifact, int instances) {
            return new Blueprint(artifact, instances, 1, Option.empty());
        }
    }

    /// Create a new cluster deployment manager.
    ///
    /// @param self            This node's ID
    /// @param cluster         The cluster node for consensus operations
    /// @param kvStore         The KV-Store for state persistence
    /// @param router          The message router for events
    /// @param initialTopology Initial cluster topology (nodes that should exist)
    /// @param topologyManager Topology manager for cluster size information
    /// @param computeProvider Compute provider for auto-healing (empty to disable)
    /// @param autoHealConfig  Auto-heal retry configuration
    /// @param atomicity       Blueprint deployment atomicity mode
    /// @param workerRegistry  Worker endpoint registry for pool-aware allocation (empty to disable)
    static ClusterDeploymentManager clusterDeploymentManager(NodeId self,
                                                             ClusterNode<KVCommand<AetherKey>> cluster,
                                                             KVStore<AetherKey, AetherValue> kvStore,
                                                             MessageRouter router,
                                                             List<NodeId> initialTopology,
                                                             TopologyManager topologyManager,
                                                             Option<ComputeProvider> computeProvider,
                                                             AutoHealConfig autoHealConfig,
                                                             DeploymentAtomicity atomicity,
                                                             int coreMax,
                                                             ReplicatedMap<SliceNodeKey, SliceNodeValue> sliceNodeMap) {
        record clusterDeploymentManager(NodeId self,
                                        ClusterNode<KVCommand<AetherKey>> cluster,
                                        KVStore<AetherKey, AetherValue> kvStore,
                                        MessageRouter router,
                                        TopologyManager topologyManager,
                                        Option<ComputeProvider> computeProvider,
                                        AutoHealConfig autoHealConfig,
                                        DeploymentAtomicity atomicity,
                                        int coreMax,
                                        Set<NodeId> seedNodes,
                                        AtomicReference<ClusterDeploymentState> state,
                                        AtomicReference<List<NodeId>> topologyRef,
                                        ReplicatedMap<SliceNodeKey, SliceNodeValue> sliceNodeMap) implements ClusterDeploymentManager {
            private static final Logger log = LoggerFactory.getLogger(clusterDeploymentManager.class);

            @Override
            public void onLeaderChange(LeaderChange leaderChange) {
                if (leaderChange.localNodeIsLeader()) {
                    // Deactivate old Active state to suppress stale scheduled callbacks
                    deactivateCurrentState();
                    // Create active state — topology will be refreshed after state swap
                    var activeNodes = new AtomicReference<>(topologyRef.get());
                    var activeState = new ClusterDeploymentState.Active(self,
                                                                        cluster,
                                                                        kvStore,
                                                                        router,
                                                                        topologyManager,
                                                                        computeProvider,
                                                                        autoHealConfig,
                                                                        new ConcurrentHashMap<>(),
                                                                        new ConcurrentHashMap<>(),
                                                                        new ConcurrentHashMap<>(),
                                                                        ConcurrentHashMap.newKeySet(),
                                                                        activeNodes,
                                                                        new AtomicInteger(0),
                                                                        new AtomicBoolean(false),
                                                                        new AtomicReference<>(),
                                                                        new AtomicBoolean(false),
                                                                        ConcurrentHashMap.newKeySet(),
                                                                        new ConcurrentHashMap<>(),
                                                                        new ConcurrentHashMap<>(),
                                                                        ConcurrentHashMap.newKeySet(),
                                                                        ConcurrentHashMap.newKeySet(),
                                                                        atomicity,
                                                                        coreMax,
                                                                        seedNodes,
                                                                        ConcurrentHashMap.newKeySet(),
                                                                        new ConcurrentHashMap<>(),
                                                                        new ConcurrentHashMap<>(),
                                                                        sliceNodeMap,
                                                                        new AtomicReference<>());
                    // Swap to Active FIRST — ensures topology changes arriving on other threads
                    // are dispatched to Active.onTopologyChange() instead of being lost in Dormant.
                    state.set(activeState);
                    // Re-read topology to catch NodeRemoved events that arrived between the
                    // initial topologyRef.get() and state.set(). During that window, topology
                    // changes were dispatched to the Dormant state (no-op) but topologyRef was
                    // still updated. Re-reading ensures the Active state has the current view.
                    activeNodes.set(topologyRef.get());
                    log.info("Node {} became leader, activating cluster deployment manager with {} known nodes",
                             self,
                             activeNodes.get()
                                        .size());
                    // Rebuild state from KVStore and reconcile
                    activeState.rebuildStateFromKVStore();
                    activeState.reconcile();
                    activeState.startReconcileTimer();
                    // Use startup cooldown for initial formation (no blueprints yet),
                    // immediate auto-heal for leader failover (blueprints restored from KVStore)
                    if (activeState.blueprints()
                                   .isEmpty()) {
                        activeState.startAutoHealCooldown();
                    } else {
                        activeState.checkAndScheduleAutoHeal();
                    }
                    // Defense in depth: schedule a deferred recheck to catch any topology events
                    // still in-flight during the activation window.
                    SharedScheduler.schedule(() -> {
                                                 if (!activeState.deactivated()
                                                                 .get()) {
                                                     activeNodes.set(topologyRef.get());
                                                     activeState.checkAndScheduleAutoHeal();
                                                 }
                                             },
                                             timeSpan(2).seconds());
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
            public void onAppBlueprintPut(ValuePut<AppBlueprintKey, AppBlueprintValue> valuePut) {
                state.get()
                     .onAppBlueprintPut(valuePut);
            }

            @Override
            public void onSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut) {
                state.get()
                     .onSliceTargetPut(valuePut);
            }

            @Override
            public void onSliceNodePut(ValuePut<SliceNodeKey, SliceNodeValue> valuePut) {
                state.get()
                     .onSliceNodePut(valuePut);
            }

            @Override
            public void onVersionRoutingPut(ValuePut<VersionRoutingKey, VersionRoutingValue> valuePut) {
                state.get()
                     .onVersionRoutingPut(valuePut);
            }

            @Override
            public void onAppBlueprintRemove(ValueRemove<AppBlueprintKey, AppBlueprintValue> valueRemove) {
                state.get()
                     .onAppBlueprintRemove(valueRemove);
            }

            @Override
            public void onSliceTargetRemove(ValueRemove<SliceTargetKey, SliceTargetValue> valueRemove) {
                state.get()
                     .onSliceTargetRemove(valueRemove);
            }

            @Override
            public void onSliceNodeRemove(ValueRemove<SliceNodeKey, SliceNodeValue> valueRemove) {
                state.get()
                     .onSliceNodeRemove(valueRemove);
            }

            @Override
            public void onVersionRoutingRemove(ValueRemove<VersionRoutingKey, VersionRoutingValue> valueRemove) {
                state.get()
                     .onVersionRoutingRemove(valueRemove);
            }

            @Override
            public void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> valuePut) {
                state.get()
                     .onNodeLifecyclePut(valuePut);
            }

            @Override
            public void onActivationDirectivePut(ValuePut<ActivationDirectiveKey, ActivationDirectiveValue> valuePut) {
                state.get()
                     .onActivationDirectivePut(valuePut);
            }

            @Override
            public void onActivationDirectiveRemove(ValueRemove<ActivationDirectiveKey, ActivationDirectiveValue> valueRemove) {
                state.get()
                     .onActivationDirectiveRemove(valueRemove);
            }

            @Override
            public void onGovernorAnnouncementPut(ValuePut<GovernorAnnouncementKey, GovernorAnnouncementValue> valuePut) {
                state.get()
                     .onGovernorAnnouncementPut(valuePut);
            }

            @Override
            public void onGovernorAnnouncementRemove(ValueRemove<GovernorAnnouncementKey, GovernorAnnouncementValue> valueRemove) {
                state.get()
                     .onGovernorAnnouncementRemove(valueRemove);
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
                // Check auto-heal unconditionally after any topology change
                if (state.get() instanceof ClusterDeploymentState.Active activeState) {
                    activeState.checkAndScheduleAutoHeal();
                }
            }
        }
        return new clusterDeploymentManager(self,
                                            cluster,
                                            kvStore,
                                            router,
                                            topologyManager,
                                            computeProvider,
                                            autoHealConfig,
                                            atomicity,
                                            coreMax,
                                            new HashSet<>(initialTopology),
                                            new AtomicReference<>(new ClusterDeploymentState.Dormant()),
                                            new AtomicReference<>(List.copyOf(initialTopology)),
                                            sliceNodeMap);
    }
}
