// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ClusterController;
import org.pragmatica.aether.controller.ClusterController.ArtifactLoad;
import org.pragmatica.aether.controller.ClusterController.BlueprintChange;
import org.pragmatica.aether.controller.ClusterController.ControlContext;
import org.pragmatica.aether.controller.CompositeLoadFactor;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.controller.ScalingEvent;
import org.pragmatica.aether.controller.ScalingMetric;
import org.pragmatica.aether.controller.fsm.ScalingDecisionRecord.Guard;
import org.pragmatica.aether.controller.fsm.ScalingDecisionRecord.Outcome;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshot;
import org.pragmatica.aether.worker.metrics.PerSliceMetrics;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.Fsm;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class ControlLoopContext {
    private static final Logger log = LoggerFactory.getLogger(ControlLoopContext.class);

    private final Fsm<ControlLoopState, ClusterFsmEvent> fsm;
    private final NodeId self;
    private final ClusterController controller;
    private final ClusterSyncCollector metricsCollector;
    private final Option<InvocationMetricsCollector> invocationMetricsCollector;
    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final TimeSpan interval;
    private final Consumer<ScalingEvent> eventPublisher;
    private final LongSupplier clock;
    private final ControlLoopState.Dormant dormant;
    private final ControlLoopState.Stopped stopped;
    private final AtomicReference<ControllerConfig> configRef;
    private final AtomicReference<List<NodeId>> topology = new AtomicReference<>(List.of());

    private final ConcurrentHashMap<Artifact, ClusterController.Blueprint> blueprints = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<SliceNodeKey, SliceState> sliceStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Artifact, Long> sliceActivationTimes = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, CommunityMetricsSnapshot> communitySnapshotStore = new ConcurrentHashMap<>();

    // Per-artifact scaling state (#423). Windows are lazily created per artifact and pruned to the
    // registered blueprint set, so state is bounded by artifact count. `perNodeSliceMetrics` holds
    // the latest snapshot each remote node reported per artifact; the leader folds its own
    // InvocationMetricsCollector directly during evaluation.
    private final ConcurrentHashMap<Artifact, CompositeLoadFactor> artifactLoadFactors = new ConcurrentHashMap<>();

    // Per-artifact decision snapshot (#425). One record per artifact per evaluation cycle capturing
    // the terminal outcome, the guard that shaped it, the driving load factor, and the instance
    // arithmetic. Pruned to the registered blueprint set like `artifactLoadFactors`; snapshot-read
    // only via `scalingDecisions()`, so the sole hot-path cost is a map put per evaluation.
    private final ConcurrentHashMap<Artifact, ScalingDecisionRecord> lastDecisions = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Artifact, ConcurrentHashMap<NodeId, PerSliceMetrics>> perNodeSliceMetrics = new ConcurrentHashMap<>();

    private final AtomicLong quorumSequence = new AtomicLong(0);

    public ControlLoopContext(Fsm<ControlLoopState, ClusterFsmEvent> fsm,
                              NodeId self,
                              ClusterController controller,
                              ClusterSyncCollector metricsCollector,
                              Option<InvocationMetricsCollector> invocationMetricsCollector,
                              ClusterNode<KVCommand<AetherKey>> cluster,
                              KVStore<AetherKey, AetherValue> kvStore,
                              TimeSpan interval,
                              ControllerConfig config,
                              Consumer<ScalingEvent> eventPublisher) {
        this(fsm,
             self,
             controller,
             metricsCollector,
             invocationMetricsCollector,
             cluster,
             kvStore,
             interval,
             config,
             eventPublisher,
             System::currentTimeMillis);
    }

    public ControlLoopContext(Fsm<ControlLoopState, ClusterFsmEvent> fsm,
                              NodeId self,
                              ClusterController controller,
                              ClusterSyncCollector metricsCollector,
                              Option<InvocationMetricsCollector> invocationMetricsCollector,
                              ClusterNode<KVCommand<AetherKey>> cluster,
                              KVStore<AetherKey, AetherValue> kvStore,
                              TimeSpan interval,
                              ControllerConfig config,
                              Consumer<ScalingEvent> eventPublisher,
                              LongSupplier clock) {
        this.fsm = fsm;
        this.self = self;
        this.controller = controller;
        this.metricsCollector = metricsCollector;
        this.invocationMetricsCollector = invocationMetricsCollector;
        this.cluster = cluster;
        this.kvStore = kvStore;
        this.interval = interval;
        this.eventPublisher = eventPublisher;
        this.configRef = new AtomicReference<>(config);
        this.clock = clock;
        this.dormant = new ControlLoopState.Dormant(this);
        this.stopped = new ControlLoopState.Stopped(this);
    }

    public long nowMs() {
        return clock.getAsLong();
    }

    public Fsm<ControlLoopState, ClusterFsmEvent> fsm() {
        return fsm;
    }

    @Contract
    public void dispatch(ClusterFsmEvent event) {
        fsm.dispatch(event);
    }

    public ControlLoopState.Dormant dormant() {
        return dormant;
    }

    public ControlLoopState.Stopped stopped() {
        return stopped;
    }

    public NodeId self() {
        return self;
    }

    public TimeSpan interval() {
        return interval;
    }

    public ControllerConfig config() {
        return configRef.get();
    }

    @Contract
    public void setConfig(ControllerConfig config) {
        configRef.set(config);
    }

    public List<NodeId> topology() {
        return topology.get();
    }

    @Contract
    public void setTopology(List<NodeId> newTopology) {
        topology.set(newTopology);
    }

    /// Evict a departed node's contribution to the scaling signal (review MAJOR). Without this the
    /// node's last per-artifact metrics stay frozen in `perNodeSliceMetrics` and are re-summed every
    /// cycle, permanently inflating the composite so a scaled-up slice can never scale back down
    /// after a host leaves. Called on the FSM terminal-departure edge (NodeRemoved/NodeDecommissioned).
    @Contract
    public void onNodeDeparted(NodeId departed, List<NodeId> newTopology) {
        topology.set(newTopology);
        perNodeSliceMetrics.values().forEach(byNode -> byNode.remove(departed));
        communitySnapshotStore.remove(departed.id());
    }

    public Map<Artifact, ClusterController.Blueprint> blueprintsSnapshot() {
        return Map.copyOf(blueprints);
    }

    public Option<ClusterController.Blueprint> blueprint(Artifact artifact) {
        return Option.option(blueprints.get(artifact));
    }

    @Contract
    public void putBlueprint(Artifact artifact, int instances, int minInstances) {
        putBlueprint(artifact, instances, minInstances, Option.none(), Option.none(), Option.none());
    }

    @Contract
    public void putBlueprint(Artifact artifact,
                             int instances,
                             int minInstances,
                             Option<Integer> maxInstances,
                             Option<Double> scaleUpThreshold,
                             Option<Double> scaleDownThreshold) {
        blueprints.put(artifact,
                       new ClusterController.Blueprint(artifact,
                                                       instances,
                                                       minInstances,
                                                       maxInstances,
                                                       scaleUpThreshold,
                                                       scaleDownThreshold));
    }

    @Contract
    public void removeBlueprint(Artifact artifact) {
        blueprints.remove(artifact);
    }

    @Contract
    public void removeBlueprintMatching(SliceTargetKey key) {
        var artifactBase = key.artifactBase();

        Option.from(blueprints.keySet().stream().filter(artifactBase::matches).findFirst()).onPresent(blueprints::remove);
    }

    public boolean blueprintsEmpty() {
        return blueprints.isEmpty();
    }

    @Contract
    public void recordSliceState(SliceNodeKey key, SliceState newState) {
        var previous = sliceStates.put(key, newState);

        if (newState == SliceState.ACTIVE && previous != SliceState.ACTIVE) {
            markSliceCooldown(key.artifact());
        }

        if (newState.isInProgress()) {
            log.debug("Slice {} in progress state: {}", key, newState);
        }
    }

    @Contract
    public void clearSliceState(SliceNodeKey key) {
        sliceStates.remove(key);
        log.debug("Removed slice state tracking for NodeArtifactKey {}", key);
    }

    public boolean anySliceInProgress() {
        return sliceStates.values()
                          .stream()
                          .anyMatch(SliceState::isInProgress);
    }

    public Option<String> describeInProgressSlice() {
        return Option.from(sliceStates.entrySet()
                                      .stream()
                                      .filter(e -> e.getValue()
                                                    .isInProgress())
                                      .findFirst()
                                      .map(e -> "Slice " + e.getKey()
                                                            .artifact() + " in progress state: " + e.getValue()));
    }

    private Option<Artifact> inProgressArtifact() {
        return Option.from(sliceStates.entrySet()
                                      .stream()
                                      .filter(e -> e.getValue()
                                                    .isInProgress())
                                      .findFirst()
                                      .map(e -> e.getKey()
                                                 .artifact()));
    }

    public Map<Artifact, Long> sliceCooldownsSnapshot() {
        return Map.copyOf(sliceActivationTimes);
    }

    @Contract
    public void resetSliceProtectionState() {
        sliceStates.clear();
        sliceActivationTimes.clear();
    }

    @Contract
    public void cleanupExpiredCooldowns(long now) {
        sliceActivationTimes.entrySet().removeIf(entry -> (now - entry.getValue()) >= configRef.get()
                                                                                               .sliceCooldown()
                                                                                               .millis());
    }

    public boolean allCooldownsExpired(long now) {
        var cooldownMs = configRef.get().sliceCooldown().millis();

        return sliceActivationTimes.values()
                                   .stream()
                                   .noneMatch(ts -> (now - ts) < cooldownMs);
    }

    @Contract
    public void storeCommunitySnapshot(CommunityMetricsSnapshot snapshot) {
        communitySnapshotStore.put(snapshot.governorId().id(),
                                   snapshot);
        ingestSliceMetrics(snapshot);
        log.debug("Stored community metrics snapshot from {} ({} slices)",
                  snapshot.governorId().id(),
                  snapshot.sliceMetrics().size());
    }

    private void ingestSliceMetrics(CommunityMetricsSnapshot snapshot) {
        var source = snapshot.governorId();

        if (source.equals(self)) {
            return;
        }

        snapshot.sliceMetrics().forEach(metrics -> recordRemoteSliceMetrics(source, metrics));
    }

    private void recordRemoteSliceMetrics(NodeId source, PerSliceMetrics metrics) {
        perNodeSliceMetrics.computeIfAbsent(metrics.artifact(), _ -> new ConcurrentHashMap<>()).put(source, metrics);
    }

    public Map<String, CommunityMetricsSnapshot> communitySnapshots() {
        return Map.copyOf(communitySnapshotStore);
    }

    public AtomicLong quorumSequence() {
        return quorumSequence;
    }

    @Contract
    public void runEvaluationCycle() {
        if (blueprints.isEmpty()) {
            log.trace("No blueprints registered, skipping evaluation");

            return;
        }

        cleanupExpiredCooldowns(nowMs());
        var sliceInProgress = describeInProgressSlice();

        if (sliceInProgress.isPresent()) {
            sliceInProgress.onPresent(reason -> log.debug("Auto-scaling paused: {}", reason));
            inProgressArtifact().onPresent(this::recordSliceInProgress);

            return;
        }

        var loads = computeArtifactLoads();
        var context = new ControlContext(loads, blueprintsSnapshot(), topology.get());

        controller.evaluate(context).onSuccess(this::applyDecisions).onFailure(cause -> log.error("Failed to evaluate controller: {}",
                                                                                                  cause.message()));
    }

    @Contract
    public void publishScalingEvent(BlueprintChange change,
                                    Artifact artifact,
                                    int previousInstances,
                                    int newInstances) {
        var event = switch (change) {
            case BlueprintChange.ScaleUp _ -> ScalingEvent.ScaledUp.scaledUp(artifact, previousInstances, newInstances);
            case BlueprintChange.ScaleDown _ -> ScalingEvent.ScaledDown.scaledDown(artifact,
                                                                                   previousInstances,
                                                                                   newInstances);
        };

        eventPublisher.accept(event);
    }

    private void markSliceCooldown(Artifact artifact) {
        var timestamp = nowMs();

        sliceActivationTimes.put(artifact, timestamp);
        log.debug("Slice {} reached ACTIVE, cooldown started", artifact);
        fsm.dispatch(new ControlLoopEvents.CooldownRequested(artifact, timestamp));
    }

    private Map<Artifact, ArtifactLoad> computeArtifactLoads() {
        artifactLoadFactors.keySet().retainAll(blueprints.keySet());
        perNodeSliceMetrics.keySet().retainAll(blueprints.keySet());
        lastDecisions.keySet().retainAll(blueprints.keySet());
        var loads = new HashMap<Artifact, ArtifactLoad>();

        blueprints.keySet().forEach(artifact -> loads.put(artifact, computeArtifactLoad(artifact)));

        return loads;
    }

    private ArtifactLoad computeArtifactLoad(Artifact artifact) {
        var sample = sampleArtifactMetrics(artifact);
        var loadFactor = artifactLoadFactors.computeIfAbsent(artifact, _ -> newLoadFactor());

        sample.forEach(loadFactor::recordSample);
        var result = loadFactor.computeWithCurrentValues(sample);

        recordBaseline(artifact, result.compositeScore(), result.canScale(), loadFactor.isErrorRateHigh());

        return ArtifactLoad.artifactLoad(result.compositeScore(),
                                         result.canScale(),
                                         loadFactor.isErrorRateHigh(),
                                         result.components());
    }

    private CompositeLoadFactor newLoadFactor() {
        return CompositeLoadFactor.compositeLoadFactor(configRef.get().scalingConfig());
    }

    private Map<ScalingMetric, Double> sampleArtifactMetrics(Artifact artifact) {
        var sources = collectSliceSources(artifact);
        var metrics = new EnumMap<ScalingMetric, Double>(ScalingMetric.class);

        metrics.put(ScalingMetric.CPU, 0.0);
        metrics.put(ScalingMetric.ACTIVE_INVOCATIONS,
                    (double) sources.stream().mapToLong(PerSliceMetrics::activeInvocations).sum());
        metrics.put(ScalingMetric.P95_LATENCY,
                    sources.stream().mapToDouble(PerSliceMetrics::p95LatencyMs).max().orElse(0.0));
        metrics.put(ScalingMetric.ERROR_RATE,
                    sources.stream().mapToDouble(PerSliceMetrics::errorRate).max().orElse(0.0));

        return metrics;
    }

    private List<PerSliceMetrics> collectSliceSources(Artifact artifact) {
        var sources = new ArrayList<>(ownSliceMetrics(artifact));

        Option.option(perNodeSliceMetrics.get(artifact)).onPresent(remote -> sources.addAll(remote.values()));

        return sources;
    }

    private List<PerSliceMetrics> ownSliceMetrics(Artifact artifact) {
        return invocationMetricsCollector.map(InvocationMetricsCollector::collectPerSliceMetrics)
                                         .or(List.<PerSliceMetrics> of())
                                         .stream()
                                         .filter(metrics -> metrics.artifact()
                                                                   .equals(artifact))
                                         .toList();
    }

    private void applyDecisions(ClusterController.ControlDecisions decisions) {
        if (decisions.changes().isEmpty()) {
            log.trace("No scaling decisions");

            return;
        }

        var commands = new ArrayList<KVCommand<AetherKey>>();

        decisions.changes().forEach(change -> prepareGuardedChange(change).onPresent(commands::add));
        if (!commands.isEmpty()) {
            cluster.apply(commands).onFailure(cause -> log.error("Failed to apply blueprint changes: {}",
                                                                 cause.message()));
        }
    }

    private Option<KVCommand<AetherKey>> prepareGuardedChange(BlueprintChange change) {
        var artifact = change.artifact();

        if (isArtifactInCooldown(artifact)) {
            log.debug("Scaling decision for {} blocked: cooldown active", artifact);
            recordBlocked(artifact);

            return Option.none();
        }

        return prepareChange(change);
    }

    private boolean isArtifactInCooldown(Artifact artifact) {
        return Option.option(sliceActivationTimes.get(artifact))
                     .map(activation -> (nowMs() - activation) < configRef.get()
                                                                          .sliceCooldown()
                                                                          .millis())
                     .or(false);
    }

    private Option<KVCommand<AetherKey>> prepareChange(BlueprintChange change) {
        var artifact = change.artifact();

        return blueprint(artifact).flatMap(current -> prepareChangeToBlueprint(change, artifact, current));
    }

    private Option<KVCommand<AetherKey>> prepareChangeToBlueprint(BlueprintChange change,
                                                                  Artifact artifact,
                                                                  ClusterController.Blueprint currentBlueprint) {
        var requestedInstances = computeRequestedInstances(change, currentBlueprint);
        var clusterSize = effectiveClusterSize();
        var capResult = applyCap(requestedInstances, currentBlueprint.maxInstances(), clusterSize);
        var newInstances = capResult.value();
        var capped = capResult.reason().isPresent();

        capResult.reason().onPresent(reason -> emitCapped(artifact,
                                                          currentBlueprint,
                                                          requestedInstances,
                                                          newInstances,
                                                          reason));
        if (newInstances == currentBlueprint.instances()) {
            return Option.none();
        }

        return applyScaling(change, artifact, currentBlueprint, requestedInstances, newInstances, capped);
    }

    private Option<KVCommand<AetherKey>> applyScaling(BlueprintChange change,
                                                      Artifact artifact,
                                                      ClusterController.Blueprint currentBlueprint,
                                                      int requestedInstances,
                                                      int newInstances,
                                                      boolean capped) {
        log.info("Applying scaling decision: {} from {} to {} instances",
                 artifact,
                 currentBlueprint.instances(),
                 newInstances);
        putBlueprint(artifact,
                     newInstances,
                     currentBlueprint.minInstances(),
                     currentBlueprint.maxInstances(),
                     currentBlueprint.scaleUpThreshold(),
                     currentBlueprint.scaleDownThreshold());
        publishScalingEvent(change, artifact, currentBlueprint.instances(), newInstances);
        recordScaled(change, artifact, currentBlueprint, requestedInstances, newInstances, capped);
        var key = SliceTargetKey.sliceTargetKey(artifact.base());
        var value = SliceTargetValue.sliceTargetValue(artifact.version(),
                                                      newInstances,
                                                      newInstances,
                                                      Option.none(),
                                                      currentBlueprint.maxInstances(),
                                                      currentBlueprint.scaleUpThreshold(),
                                                      currentBlueprint.scaleDownThreshold());

        return Option.some(new KVCommand.Put<>(key, value));
    }

    private static int computeRequestedInstances(BlueprintChange change, ClusterController.Blueprint currentBlueprint) {
        return switch (change) {
            case BlueprintChange.ScaleUp(_, int additional) -> currentBlueprint.instances() + additional;
            case BlueprintChange.ScaleDown(_, int reduceBy) -> Math.max(currentBlueprint.minInstances(),
                                                                        currentBlueprint.instances() - reduceBy);
        };
    }

    private int effectiveClusterSize() {
        var size = topology.get().size();

        return size == 0
               ? 1
               : size;
    }

    /// Cap the requested instance count to the tighter of the blueprint's `maxInstances` bound and
    /// the cluster size. The result value never exceeds `requestedInstances` (the arithmetic matches
    /// #424). `reason` is present only when a real reduction happened: `CLUSTER_CAP` when the cluster
    /// size is the strictly-tighter bound, otherwise `MAX_INSTANCES` (a tie is attributed to the
    /// explicitly-configured max).
    private static CapResult applyCap(int requestedInstances, Option<Integer> maxInstances, int clusterSize) {
        var maxBound = maxInstances.map(max -> Math.min(requestedInstances, max)).or(requestedInstances);
        var value = Math.min(maxBound, clusterSize);

        if (value >= requestedInstances) {
            return new CapResult(requestedInstances, Option.none());
        }

        var reason = clusterSize < maxBound
                     ? CapReason.CLUSTER_CAP
                     : CapReason.MAX_INSTANCES;

        return new CapResult(value, Option.some(reason));
    }

    private record CapResult(int value, Option<CapReason> reason) {}

    private enum CapReason {
        MAX_INSTANCES("max-instances", Guard.MAX_INSTANCES),
        CLUSTER_CAP("cluster-cap", Guard.CLUSTER_CAP);
        private final String label;
        private final Guard guard;
        CapReason(String label, Guard guard) {
            this.label = label;
            this.guard = guard;
        }
        String label() {
            return label;
        }
        Guard guard() {
            return guard;
        }
    }

    private void emitCapped(Artifact artifact,
                            ClusterController.Blueprint currentBlueprint,
                            int requestedInstances,
                            int cappedInstances,
                            CapReason reason) {
        log.debug("Scaling {} capped ({}) from requested {} to {}",
                  artifact,
                  reason.label(),
                  requestedInstances,
                  cappedInstances);
        eventPublisher.accept(ScalingEvent.ScaleCapped.scaleCapped(artifact,
                                                                   requestedInstances,
                                                                   cappedInstances,
                                                                   reason.label()));
        recordDecision(artifact,
                       Outcome.CAPPED,
                       reason.guard(),
                       priorLoadFactor(artifact),
                       currentBlueprint.instances(),
                       requestedInstances,
                       cappedInstances);
    }

    private void recordScaled(BlueprintChange change,
                              Artifact artifact,
                              ClusterController.Blueprint currentBlueprint,
                              int requestedInstances,
                              int newInstances,
                              boolean capped) {
        if (capped) {
            return;
        }

        recordDecision(artifact,
                       scaledOutcome(change),
                       Guard.NONE,
                       priorLoadFactor(artifact),
                       currentBlueprint.instances(),
                       requestedInstances,
                       newInstances);
    }

    private static Outcome scaledOutcome(BlueprintChange change) {
        return switch (change) {
            case BlueprintChange.ScaleUp _ -> Outcome.SCALED_UP;
            case BlueprintChange.ScaleDown _ -> Outcome.SCALED_DOWN;
        };
    }

    private void recordBaseline(Artifact artifact, double compositeScore, boolean canScale, boolean errorRateHigh) {
        var instances = artifactInstances(artifact);

        recordDecision(artifact,
                       Outcome.HELD,
                       baselineGuard(canScale, errorRateHigh),
                       compositeScore,
                       instances,
                       instances,
                       instances);
    }

    private static Guard baselineGuard(boolean canScale, boolean errorRateHigh) {
        if (!canScale) {
            return Guard.WINDOW_NOT_FULL;
        }

        return errorRateHigh
               ? Guard.ERROR_BLOCK
               : Guard.NONE;
    }

    private void recordSliceInProgress(Artifact artifact) {
        var instances = artifactInstances(artifact);

        recordDecision(artifact, Outcome.HELD, Guard.SLICE_IN_PROGRESS, 0.0, instances, instances, instances);
    }

    private void recordBlocked(Artifact artifact) {
        var instances = artifactInstances(artifact);

        recordDecision(artifact,
                       Outcome.BLOCKED,
                       Guard.COOLDOWN,
                       priorLoadFactor(artifact),
                       instances,
                       instances,
                       instances);
    }

    private void recordDecision(Artifact artifact,
                                Outcome outcome,
                                Guard guard,
                                double loadFactor,
                                int currentInstances,
                                int requestedInstances,
                                int cappedInstances) {
        lastDecisions.put(artifact,
                          ScalingDecisionRecord.scalingDecisionRecord(artifact,
                                                                      outcome,
                                                                      guard,
                                                                      loadFactor,
                                                                      currentInstances,
                                                                      requestedInstances,
                                                                      cappedInstances,
                                                                      nowMs()));
    }

    private int artifactInstances(Artifact artifact) {
        return blueprint(artifact).map(ClusterController.Blueprint::instances)
                        .or(0);
    }

    private double priorLoadFactor(Artifact artifact) {
        return Option.option(lastDecisions.get(artifact))
                     .map(ScalingDecisionRecord::loadFactor)
                     .or(0.0);
    }

    /// Snapshot of the latest per-artifact decision, bounded by the registered blueprint set.
    public Map<Artifact, ScalingDecisionRecord> scalingDecisions() {
        return Map.copyOf(lastDecisions);
    }

    /// Cluster-average CPU usage, surfaced alongside the decision snapshot as honest node-capacity
    /// context (never acted on by the autoscaler — the per-artifact composite load is the sole
    /// scaling driver). Averages the `cpu.usage` metric across every node the metrics collector
    /// currently sees; `0.0` when no node reports CPU.
    public double clusterCpuContext() {
        return metricsCollector.allMetrics()
                               .values()
                               .stream()
                               .filter(metrics -> metrics.containsKey(ClusterSyncCollector.CPU_USAGE))
                               .mapToDouble(metrics -> metrics.get(ClusterSyncCollector.CPU_USAGE))
                               .average()
                               .orElse(0.0);
    }
}
