// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.PlacementHint;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSource;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.NodeHealth;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeAdded;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeDown;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.lang.concurrent.CancellableTask;

import java.net.SocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.consensus.net.NodeInfo.LABEL_HOSTNAME;
import static org.pragmatica.consensus.net.NodeInfo.LABEL_INSTANCE_TYPE;
import static org.pragmatica.consensus.net.NodeInfo.LABEL_ZONE;
import static org.pragmatica.lang.Unit.unit;


/// Implementation of ClusterTopologyManager that delegates read-only operations to
/// TopologyObserver and reads all membership-size state from
/// [GenerationSnapshotSource.currentMembershipView()]. No local shadow caches of
/// configured/desired sizes; see clustersync-refactor-spec §"Commit 3".
///
/// Scale operations propagate through exactly one path:
/// `setDesiredSize` writes `ClusterConfigValue` atom → snapshot publishes the new
/// `desiredCoreSize` → CTM reconcile reads from snapshot → takes action.
///
/// Snapshot-delta-driven: deficit detection is wired to `GenerationSnapshotSource` so a
/// changed snapshot (term advance) triggers `reconcile()` directly. A single safety-net
/// timer at `AutoHealConfig.retryInterval` polls for missed deltas; there is no per-deficit
/// timer chain and no provisioning hysteresis — `handleDeficit` provisions immediately.
/// The hysteresis previously used to absorb transient flaps is now provided by snapshot
/// `healthHint` transitions on the leader (see cluster-generation-spec §15.3).
///
/// @SuppressWarnings: void callbacks required by TopologyManager/ClusterTopologyManager interfaces
@SuppressWarnings("JBCT-RET-01") record ClusterTopologyManagerRecord(TopologyObserver observer,
                                                                     NodeLifecycleManager lifecycleManager,
                                                                     AutoHealConfig autoHealConfig,
                                                                     DeploymentMap deploymentMap,
                                                                     GenerationSnapshotSource snapshotSource,
                                                                     Supplier<Option<ClusterConfigValue>> clusterConfigReader,
                                                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                                                     AtomicReference<NodeReconcilerState> stateRef,
                                                                     AtomicBoolean active,
                                                                     ConcurrentHashMap<NodeId, Instant> nodeJoinTimes,
                                                                     CancellableTask safetyNetTimer) implements ClusterTopologyManager {
    private static final Logger log = LoggerFactory.getLogger(ClusterTopologyManager.class);

    private static final int MINIMUM_CLUSTER_SIZE = 3;

    private static final int MAX_WAVE_SIZE = 5;

    static ClusterTopologyManagerRecord clusterTopologyManagerRecord(TopologyObserver observer,
                                                                     NodeLifecycleManager lifecycleManager,
                                                                     AutoHealConfig config,
                                                                     DeploymentMap deploymentMap,
                                                                     GenerationSnapshotSource snapshotSource,
                                                                     Supplier<Option<ClusterConfigValue>> clusterConfigReader,
                                                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier) {
        return new ClusterTopologyManagerRecord(observer,
                                                lifecycleManager,
                                                config,
                                                deploymentMap,
                                                snapshotSource,
                                                clusterConfigReader,
                                                commandApplier,
                                                new AtomicReference<>(new NodeReconcilerState.Inactive("not yet activated")),
                                                new AtomicBoolean(false),
                                                new ConcurrentHashMap<>(),
                                                CancellableTask.cancellableTask());
    }

    @Override public NodeReconcilerState reconcilerState() {
        return stateRef.get();
    }

    @Override public Result<Unit> setDesiredSize(int size) {
        if (size <MINIMUM_CLUSTER_SIZE) {return Causes.cause("Cluster size cannot be below " + MINIMUM_CLUSTER_SIZE + " (quorum requirement)")
                                                            .result();}
        return writeDesiredCoreCount(size);
    }

    private Result<Unit> writeDesiredCoreCount(int size) {
        var existing = clusterConfigReader.get();
        if (existing.isEmpty()) {return Causes.cause("ClusterConfigValue atom missing — bootstrap must seed it before scale operations are accepted")
                                                    .result();}
        var updated = withCoreCount(existing.unwrap(), size);
        @SuppressWarnings("unchecked") var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<AetherKey, AetherValue>(ClusterConfigKey.CURRENT,
                                                                                                                                    updated);
        commandApplier.apply(List.of(command)).onFailure(cause -> log.warn("CTM: failed to write ClusterConfigValue coreCount={}: {}",
                                                                           size,
                                                                           cause.message()))
                            .onSuccess(_ -> log.info("CTM: wrote ClusterConfigValue coreCount={} (configVersion={})",
                                                     size,
                                                     updated.configVersion()));
        return Result.success(unit());
    }

    private static ClusterConfigValue withCoreCount(ClusterConfigValue existing, int newCoreCount) {
        return new ClusterConfigValue(existing.tomlContent(),
                                      existing.clusterName(),
                                      existing.version(),
                                      newCoreCount,
                                      existing.coreMin(),
                                      existing.coreMax(),
                                      existing.deploymentType(),
                                      existing.configVersion() + 1,
                                      System.currentTimeMillis());
    }

    @Override public int desiredSize() {
        return snapshotDesiredCoreSize();
    }

    @Override public int configuredSize() {
        return snapshotDesiredCoreSize();
    }

    private int snapshotDesiredCoreSize() {
        return snapshotSource.currentMembershipView().map(MembershipView::desiredCoreSize)
                                                   .or(0);
    }

    private int snapshotHealthyOnDutyCount() {
        return snapshotSource.currentMembershipView().map(MembershipView::healthyOnDutyCount)
                                                   .or(0);
    }

    @Override public void onNodeReady(NodeId nodeId) {
        if (stateRef.get() instanceof NodeReconcilerState.Reconciling) {
            log.info("Node {} reached ON_DUTY, checking reconciliation progress", nodeId);
            reconcile();
        }
    }

    @Override@SuppressWarnings("JBCT-RET-01") public void onTopologyChange(TopologyChangeNotification topologyChange) {
        if (!active.get()) {return;}
        switch (topologyChange){
            case NodeAdded added -> handleNodeAdded(added);
            case NodeRemoved removed -> handleNodeRemoved(removed);
            case NodeDown down -> handleNodeDown(down);
            default -> {}
        }
    }

    private void handleNodeAdded(NodeAdded added) {
        nodeJoinTimes.putIfAbsent(added.nodeId(), Instant.now());
        log.info("CTM: Node {} added, triggering reconciliation", added.nodeId());
        reconcile();
    }

    private void handleNodeRemoved(NodeRemoved removed) {
        nodeJoinTimes.remove(removed.nodeId());
        log.info("CTM: Node {} removed, triggering reconciliation", removed.nodeId());
        reconcile();
    }

    private void handleNodeDown(NodeDown down) {
        log.warn("CTM: Node {} is down, triggering immediate reconciliation", down.nodeId());
        reconcile();
    }

    @Override public void activate() {
        if (!active.compareAndSet(false, true)) {return;}
        seedJoinTimesForExistingNodes();
        activateWithCurrentTopology();
        scheduleSafetyNetPoll();
    }

    private void seedJoinTimesForExistingNodes() {
        for (var nodeId : observer.topology()) {nodeJoinTimes.putIfAbsent(nodeId, Instant.now());}
    }

    private void activateWithCurrentTopology() {
        var actual = observer.healthyActiveNodeCount();
        var desired = activationDesiredSize();
        var readyCount = observer.readyNodeCount();
        var effectiveActual = Math.max(actual, readyCount);
        var clusterWasFormed = readyCount > 0;
        log.info("CTM: Activated, desired={}, active={}, ready={}", desired, actual, readyCount);
        if (desired == 0) {
            transitionTo(new NodeReconcilerState.Converged());
            log.info("CTM: Activated without desiredCoreSize (snapshot not yet projected); awaiting snapshot bump");
            return;
        }
        if (effectiveActual >= desired) {
            transitionTo(new NodeReconcilerState.Converged());
            log.info("CTM: Cluster at target size, skipping formation");
        } else if (clusterWasFormed && effectiveActual >= desired - 1) {activateWithLeaderFailover(effectiveActual,
                                                                                                   desired);} else {activateWithFormation();}
    }

    private int activationDesiredSize() {
        var fromSnapshot = snapshotDesiredCoreSize();
        return fromSnapshot > 0
              ? fromSnapshot
              : observer.healthyActiveNodeCount();
    }

    private void activateWithLeaderFailover(int effectiveActual, int desired) {
        transitionTo(new NodeReconcilerState.Converged());
        log.info("CTM: Leader failover detected ({}/{}), enabling immediate reconciliation", effectiveActual, desired);
        handleDeficit(effectiveActual, desired);
    }

    private void activateWithFormation() {
        transitionTo(new NodeReconcilerState.Forming(Instant.now()));
        SharedScheduler.schedule(this::checkFormationComplete, autoHealConfig.startupCooldown());
    }

    @Override public void deactivate() {
        if (!active.compareAndSet(true, false)) {return;}
        cancelSafetyNetPoll();
        transitionTo(new NodeReconcilerState.Inactive("deactivated (not leader)"));
        log.info("CTM: Deactivated");
    }

    @Override public TopologyObserver observer() {
        return observer;
    }

    @Override public NodeInfo self() {
        return observer.self();
    }

    @Override public Option<NodeInfo> get(NodeId id) {
        return observer.get(id);
    }

    @Override public int clusterSize() {
        return observer.clusterSize();
    }

    @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
        return observer.reverseLookup(socketAddress);
    }

    @Override public Promise<Unit> start() {
        return observer.start();
    }

    @Override public Promise<Unit> stop() {
        deactivate();
        return observer.stop();
    }

    @Override public TimeSpan pingInterval() {
        return observer.pingInterval();
    }

    @Override public TimeSpan helloTimeout() {
        return observer.helloTimeout();
    }

    @Override public Option<TlsConfig> tls() {
        return observer.tls();
    }

    @Override public Option<NodeState> getState(NodeId id) {
        return observer.getState(id);
    }

    @Override public List<NodeId> topology() {
        return observer.topology();
    }

    private void transitionTo(NodeReconcilerState newState) {
        var previous = stateRef.getAndSet(newState);
        log.info("CTM state: {} -> {}",
                 stateName(previous),
                 stateName(newState));
    }

    private void checkFormationComplete() {
        if (!active.get()) {return;}
        if (! (stateRef.get() instanceof NodeReconcilerState.Forming)) {return;}
        var actual = observer.healthyActiveNodeCount();
        var desired = snapshotDesiredCoreSize();
        if (desired == 0) {return;}
        if (actual >= desired) {
            transitionTo(new NodeReconcilerState.Converged());
            log.info("CTM: Cluster formation complete ({}/{})", actual, desired);
        } else {handleFormationCooldownExpired(actual, desired);}
    }

    private void handleFormationCooldownExpired(int actual, int desired) {
        log.info("CTM: Formation cooldown expired, cluster at {}/{}, enabling reconciliation", actual, desired);
        transitionTo(new NodeReconcilerState.Converged());
        handleDeficit(actual, desired);
    }

    private void reconcile() {
        if (!active.get()) {return;}
        var currentState = stateRef.get();
        if (currentState instanceof NodeReconcilerState.Inactive) {return;}
        if (currentState instanceof NodeReconcilerState.Forming) {
            reconcileForming();
            return;
        }
        reconcileActive(currentState);
    }

    private void reconcileForming() {
        var actual = observer.healthyActiveNodeCount();
        var configured = snapshotDesiredCoreSize();
        if (configured == 0) {return;}
        if (actual >= configured) {
            transitionTo(new NodeReconcilerState.Converged());
            log.info("CTM: Cluster formation complete ({}/{})", actual, configured);
        }
    }

    private void reconcileActive(NodeReconcilerState currentState) {
        var snapshot = snapshotSource.currentMembershipView();
        if (snapshot.isEmpty()) {return;}
        var configured = snapshot.unwrap().desiredCoreSize();
        if (configured == 0) {return;}
        var actual = snapshotHealthyOnDutyCount();
        var effectiveState = currentState;
        if (effectiveState instanceof NodeReconcilerState.Reconciling reconciling && reconciling.targetSize() != configured) {
            log.info("CTM: reconcile target changed during Reconciling ({} → {}), resetting to Converged for re-dispatch",
                     reconciling.targetSize(),
                     configured);
            transitionTo(new NodeReconcilerState.Converged());
            effectiveState = stateRef.get();
        }
        if (actual == configured) {
            if (! (effectiveState instanceof NodeReconcilerState.Converged)) {transitionTo(new NodeReconcilerState.Converged());}
            return;
        }
        if (actual <configured) {handleDeficit(actual, configured);} else {handleSurplus(actual, configured);}
    }

    private void handleDeficit(int actual, int desired) {
        var current = stateRef.get();
        if (current instanceof NodeReconcilerState.Reconciling) {
            log.debug("CTM: Already reconciling, waiting for in-flight provisions to complete");
            return;
        }
        var deficit = desired - actual;
        if (!lifecycleManager.isCloudManaged()) {
            var next = new NodeReconcilerState.Reconciling(desired, actual, List.of(), List.of(), Instant.now());
            if (!stateRef.compareAndSet(current, next)) {
                log.warn("CTM: state CAS lost (deficit, no-cloud) — observed={}, expected={}, next={}",
                         stateRef.get(),
                         current,
                         next);
                return;
            }
            log.debug("CTM: Cluster deficit of {} but no ComputeProvider, cannot auto-provision", deficit);
            return;
        }
        var batchSize = provisionBatchSize(deficit);
        var next = new NodeReconcilerState.Reconciling(desired,
                                                       actual,
                                                       buildInFlightList(batchSize),
                                                       List.of(),
                                                       Instant.now());
        if (!stateRef.compareAndSet(current, next)) {
            log.warn("CTM: state CAS lost (deficit, provision) — observed={}, expected={}, next={}",
                     stateRef.get(),
                     current,
                     next);
            return;
        }
        log.info("CTM: Cluster at {}/{}, provisioning {} replacement(s)", actual, desired, batchSize);
        provisionNodes(batchSize);
    }

    private void handleSurplus(int actual, int configured) {
        var current = stateRef.get();
        if (current instanceof NodeReconcilerState.Reconciling) {
            log.debug("CTM: Already reconciling, waiting for in-flight terminations to complete");
            return;
        }
        var surplus = actual - configured;
        if (!lifecycleManager.isCloudManaged()) {
            log.info("CTM: Cluster has {} surplus nodes but no ComputeProvider, cannot auto-terminate", surplus);
            transitionTo(new NodeReconcilerState.Converged());
            return;
        }
        var nodesToTerminate = selectNodesForTermination(surplus);
        if (nodesToTerminate.isEmpty()) {
            log.warn("CTM: {} surplus nodes but no candidates for termination", surplus);
            return;
        }
        var next = new NodeReconcilerState.Reconciling(configured, actual, List.of(), nodesToTerminate, Instant.now());
        if (!stateRef.compareAndSet(current, next)) {
            log.warn("CTM: state CAS lost (surplus, terminate) — observed={}, expected={}, next={}",
                     stateRef.get(),
                     current,
                     next);
            return;
        }
        log.info("CTM: Cluster at {}/{}, terminating {} surplus node(s): {}",
                 actual,
                 configured,
                 nodesToTerminate.size(),
                 nodesToTerminate);
        terminateNodes(nodesToTerminate);
    }

    private List<NodeId> selectNodesForTermination(int count) {
        var selfId = observer.self().id();
        var ctmOwned = ctmProvisionedNodeIds();
        var activeNodes = observer.topology().stream()
                                           .filter(id -> !id.equals(selfId))
                                           .filter(ctmOwned::contains)
                                           .toList();
        var emptyNodes = snapshotNodesWithoutSlices(activeNodes);
        var hostCounts = buildHostCounts(activeNodes);
        var sortedCandidates = activeNodes.stream().sorted(surplusNodeComparator(emptyNodes, hostCounts, ctmOwned))
                                                 .toList();
        return sortedCandidates.stream().limit(Math.min(count, MAX_WAVE_SIZE))
                                      .toList();
    }

    private Set<NodeId> ctmProvisionedNodeIds() {
        return snapshotSource.currentMembershipView().map(MembershipView::ctmProvisionedNodeIds)
                                                   .or(Set.of());
    }

    private Set<NodeId> snapshotNodesWithoutSlices(List<NodeId> activeNodes) {
        var fromSnapshot = snapshotSource.currentMembershipView().map(MembershipView::nodesWithoutSlices)
                                                               .or(Set.of());
        return activeNodes.stream().filter(fromSnapshot::contains)
                                 .collect(Collectors.toUnmodifiableSet());
    }

    private Map<String, Long> buildHostCounts(List<NodeId> activeNodes) {
        return activeNodes.stream().map(this::hostnameLabel)
                                 .collect(Collectors.groupingBy(h -> h,
                                                                Collectors.counting()));
    }

    private String hostnameLabel(NodeId nodeId) {
        return observer.get(nodeId).map(info -> info.labels().getOrDefault(LABEL_HOSTNAME, ""))
                           .or("");
    }

    private boolean isSpotInstance(NodeId nodeId) {
        return observer.get(nodeId).map(info -> "spot".equals(info.labels().getOrDefault(LABEL_INSTANCE_TYPE, "")))
                           .or(false);
    }

    private long hostCount(NodeId nodeId, Map<String, Long> hostCounts) {
        var hostname = hostnameLabel(nodeId);
        return hostname.isEmpty()
              ? 0L
              : hostCounts.getOrDefault(hostname, 0L);
    }

    private Comparator<NodeId> surplusNodeComparator(Set<NodeId> emptyNodes,
                                                     Map<String, Long> hostCounts,
                                                     Set<NodeId> ctmOwned) {
        return Comparator.<NodeId, Boolean>comparing(id -> !ctmOwned.contains(id))
                         .thenComparing(id -> !isSpotInstance(id))
                         .thenComparing(id -> hostCount(id, hostCounts),
                                        Comparator.reverseOrder())
                         .thenComparing(id -> !emptyNodes.contains(id))
                         .thenComparing(id -> nodeJoinTimes.getOrDefault(id, Instant.EPOCH),
                                        Comparator.reverseOrder());
    }

    private void terminateNodes(List<NodeId> nodes) {
        for (var nodeId : nodes) {terminateSingleNode(nodeId);}
    }

    private void terminateSingleNode(NodeId nodeId) {
        lifecycleManager.terminateNode(nodeId).onSuccess(_ -> handleTerminationSuccess(nodeId))
                                      .onFailure(cause -> log.warn("CTM: Node {} termination failed: {}",
                                                                   nodeId,
                                                                   cause.message()));
    }

    private void handleTerminationSuccess(NodeId nodeId) {
        nodeJoinTimes.remove(nodeId);
        log.info("CTM: Node {} terminated successfully", nodeId);
        writeDecommissionedAtom(nodeId);
        reconcile();
    }

    private void writeDecommissionedAtom(NodeId nodeId) {
        var value = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DECOMMISSIONED,
                                                          "",
                                                          0,
                                                          ProvisioningSource.CTM);
        @SuppressWarnings("unchecked") var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<AetherKey, AetherValue>(NodeLifecycleKey.nodeLifecycleKey(nodeId),
                                                                                                                                    value);
        commandApplier.apply(List.of(command))
                            .onFailure(cause -> log.warn("CTM: failed to write DECOMMISSIONED atom for {}: {}",
                                                         nodeId,
                                                         cause.message()));
    }

    private void provisionNodes(int count) {
        for (var i = 0;i <count;i++) {provisionSingleNode();}
    }

    private void provisionSingleNode() {
        var baseSpec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND,
                                                   "default",
                                                   "core",
                                                   buildProvisionTags())
        .unwrap();
        var spec = computePlacementHint().map(baseSpec::withPlacement).or(baseSpec);
        lifecycleManager.provisionNode(spec).onSuccess(_ -> log.info("CTM: Node provisioning succeeded"))
                                      .onFailure(cause -> log.warn("CTM: Node provisioning failed: {}",
                                                                   cause.message()));
    }

    private Map<String, String> buildProvisionTags() {
        var peers = observer.topology().stream()
                                     .filter(this::isHealthyPeer)
                                     .flatMap(nodeId -> observer.get(nodeId).stream())
                                     .map(ClusterTopologyManagerRecord::formatPeerEntry)
                                     .collect(Collectors.joining(","));
        return Map.of("aether.peers",
                      peers,
                      "aether.core-max",
                      String.valueOf(snapshotDesiredCoreSize()),
                      "aether.provisioned-by",
                      "ctm");
    }

    private boolean isHealthyPeer(NodeId nodeId) {
        return observer.getState(nodeId).map(state -> state.health() == NodeHealth.HEALTHY)
                                .or(false);
    }

    private static String formatPeerEntry(NodeInfo info) {
        var hostname = info.address().host();
        return info.id().id() + ":" + hostname + ":" + info.address().port();
    }

    private Option<PlacementHint> computePlacementHint() {
        var zoneCounts = observer.topology().stream()
                                          .map(this::zoneLabel)
                                          .filter(z -> !z.isEmpty())
                                          .collect(Collectors.groupingBy(z -> z,
                                                                         Collectors.counting()));
        if (zoneCounts.isEmpty()) {return Option.empty();}
        var minCount = zoneCounts.values().stream()
                                        .mapToLong(Long::longValue)
                                        .min()
                                        .orElse(0L);
        var underRepresented = zoneCounts.entrySet().stream()
                                                  .filter(e -> e.getValue() == minCount)
                                                  .map(Map.Entry::getKey)
                                                  .toList();
        if (underRepresented.size() == 1) {return Option.some(PlacementHint.zoneHint(underRepresented.getFirst()));}
        var overRepresented = zoneCounts.entrySet().stream()
                                                 .filter(e -> e.getValue() > minCount)
                                                 .map(Map.Entry::getKey)
                                                 .collect(Collectors.toSet());
        if (overRepresented.isEmpty()) {return Option.empty();}
        return Option.some(PlacementHint.antiAffinityHint(overRepresented));
    }

    private String zoneLabel(NodeId nodeId) {
        return observer.get(nodeId).map(info -> info.labels().getOrDefault(LABEL_ZONE, ""))
                           .or("");
    }

    private void scheduleSafetyNetPoll() {
        safetyNetTimer.set(SharedScheduler.scheduleAtFixedRate(this::reconcile, autoHealConfig.retryInterval()));
    }

    private void cancelSafetyNetPoll() {
        safetyNetTimer.cancel();
    }

    private static int provisionBatchSize(int deficit) {
        return switch (deficit){
            case 1 -> 1;
            case 2, 3 -> deficit;
            default -> Math.min(deficit, MAX_WAVE_SIZE);
        };
    }

    private static List<NodeReconcilerState.ProvisionAttempt> buildInFlightList(int count) {
        var now = Instant.now();
        var list = new ArrayList<NodeReconcilerState.ProvisionAttempt>(count);
        for (var i = 0;i <count;i++) {list.add(new NodeReconcilerState.ProvisionAttempt(now, 1));}
        return List.copyOf(list);
    }

    private static String stateName(NodeReconcilerState state) {
        return switch (state){
            case NodeReconcilerState.Inactive inactive -> "Inactive(" + inactive.reason() + ")";
            case NodeReconcilerState.Forming _ -> "Forming";
            case NodeReconcilerState.Converged _ -> "Converged";
            case NodeReconcilerState.Reconciling r -> "Reconciling(" + r.currentSize() + "/" + r.targetSize() + ")";
        };
    }
}
