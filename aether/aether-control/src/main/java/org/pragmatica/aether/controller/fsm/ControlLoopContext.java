// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ClusterController;
import org.pragmatica.aether.controller.ClusterController.BlueprintChange;
import org.pragmatica.aether.controller.ClusterController.ControlContext;
import org.pragmatica.aether.controller.CompositeLoadFactor;
import org.pragmatica.aether.controller.CompositeLoadFactor.LoadFactorResult;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.controller.ScalingConfig;
import org.pragmatica.aether.controller.ScalingEvent;
import org.pragmatica.aether.controller.ScalingMetric;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshot;
import org.pragmatica.aether.worker.metrics.CommunityScalingRequest;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.Fsm;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class ControlLoopContext {
    public static final String COOLDOWN_KEY_PREFIX = "scaling-cooldown/";

    public static final long STALE_EVIDENCE_MS = 30_000L;

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
    private final CompositeLoadFactor compositeLoadFactor;
    private final LongSupplier clock;
    private final ControlLoopState.Dormant dormant;
    private final ControlLoopState.Stopped stopped;
    private final AtomicReference<ControllerConfig> configRef;

    private final AtomicReference<List<NodeId>> topology = new AtomicReference<>(List.of());

    private final ConcurrentHashMap<Artifact, ClusterController.Blueprint> blueprints = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<SliceNodeKey, SliceState> sliceStates = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Artifact, Long> sliceActivationTimes = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, CommunityMetricsSnapshot> communitySnapshotStore = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Long> communityScalingCooldowns = new ConcurrentHashMap<>();

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
        this.compositeLoadFactor = CompositeLoadFactor.compositeLoadFactor(config.scalingConfig());
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

    @Contract public void dispatch(ClusterFsmEvent event) {
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

    @Contract public void setConfig(ControllerConfig config) {
        configRef.set(config);
    }

    public List<NodeId> topology() {
        return topology.get();
    }

    @Contract public void setTopology(List<NodeId> newTopology) {
        topology.set(newTopology);
    }

    public Map<Artifact, ClusterController.Blueprint> blueprintsSnapshot() {
        return Map.copyOf(blueprints);
    }

    public Option<ClusterController.Blueprint> blueprint(Artifact artifact) {
        return Option.option(blueprints.get(artifact));
    }

    @Contract public void putBlueprint(Artifact artifact, int instances, int minInstances) {
        blueprints.put(artifact, new ClusterController.Blueprint(artifact, instances, minInstances));
    }

    @Contract public void removeBlueprint(Artifact artifact) {
        blueprints.remove(artifact);
    }

    @Contract public void removeBlueprintMatching(SliceTargetKey key) {
        var artifactBase = key.artifactBase();
        Option.from(blueprints.keySet().stream()
                                     .filter(artifactBase::matches)
                                     .findFirst())
        .onPresent(blueprints::remove);
    }

    public boolean blueprintsEmpty() {
        return blueprints.isEmpty();
    }

    @Contract public void recordSliceState(SliceNodeKey key, SliceState newState) {
        var previous = sliceStates.put(key, newState);
        if (newState == SliceState.ACTIVE && previous != SliceState.ACTIVE) {markSliceCooldown(key.artifact());}
        if (newState.isInProgress()) {log.debug("Slice {} in progress state: {}", key, newState);}
    }

    @Contract public void clearSliceState(SliceNodeKey key) {
        sliceStates.remove(key);
        log.debug("Removed slice state tracking for NodeArtifactKey {}", key);
    }

    public boolean anySliceInProgress() {
        return sliceStates.values().stream()
                                 .anyMatch(SliceState::isInProgress);
    }

    public Option<String> describeInProgressSlice() {
        return Option.from(sliceStates.entrySet().stream()
                                               .filter(e -> e.getValue().isInProgress())
                                               .findFirst()
                                               .map(e -> "Slice " + e.getKey().artifact() + " in progress state: " + e.getValue()));
    }

    public Map<Artifact, Long> sliceCooldownsSnapshot() {
        return Map.copyOf(sliceActivationTimes);
    }

    @Contract public void resetSliceProtectionState() {
        sliceStates.clear();
        sliceActivationTimes.clear();
    }

    @Contract public void cleanupExpiredCooldowns(long now) {
        sliceActivationTimes.entrySet().removeIf(entry -> (now - entry.getValue()) >= configRef.get().sliceCooldown().millis());
    }

    public boolean allCooldownsExpired(long now) {
        var cooldownMs = configRef.get().sliceCooldown().millis();
        return sliceActivationTimes.values().stream()
                                          .noneMatch(ts -> (now - ts) <cooldownMs);
    }

    @Contract public void storeCommunitySnapshot(CommunityMetricsSnapshot snapshot) {
        communitySnapshotStore.put(snapshot.communityId(), snapshot);
        log.debug("Stored community metrics snapshot for '{}' from {}",
                  snapshot.communityId(),
                  snapshot.governorId().id());
    }

    public Map<String, CommunityMetricsSnapshot> communitySnapshots() {
        return Map.copyOf(communitySnapshotStore);
    }

    public AtomicLong quorumSequence() {
        return quorumSequence;
    }

    @Contract public void runEvaluationCycle() {
        if (blueprints.isEmpty()) {
            log.trace("No blueprints registered, skipping evaluation");
            return;
        }
        var metrics = sampleCurrentMetrics();
        metrics.forEach(compositeLoadFactor::recordSample);
        evaluateScalingDecisions(metrics);
    }

    @Contract public void handleCommunityScalingRequest(CommunityScalingRequest request) {
        if (isStaleEvidence(request)) {return;}
        if (isInCommunityCooldown(request)) {return;}
        blueprint(request.artifact()).onEmpty(() -> log.info("No blueprint found for community scaling request: {}",
                                                             request.artifact()))
                 .onPresent(blueprint -> applyCommunityScaling(request, blueprint));
    }

    @Contract public void publishScalingEvent(BlueprintChange change,
                                              Artifact artifact,
                                              int previousInstances,
                                              int newInstances) {
        var event = switch (change){
            case BlueprintChange.ScaleUp _ -> ScalingEvent.ScaledUp.scaledUp(artifact, previousInstances, newInstances);
            case BlueprintChange.ScaleDown _ -> ScalingEvent.ScaledDown.scaledDown(artifact,
                                                                                   previousInstances,
                                                                                   newInstances);
        };
        eventPublisher.accept(event);
    }

    @Contract public void restoreCooldownsFromKvStore() {
        kvStore.forEach(ConfigKey.class, ConfigValue.class, this::restoreCooldownEntry);
    }

    private void markSliceCooldown(Artifact artifact) {
        var timestamp = nowMs();
        sliceActivationTimes.put(artifact, timestamp);
        persistCooldown(artifact.asString(), timestamp);
        log.debug("Slice {} reached ACTIVE, cooldown started", artifact);
        fsm.dispatch(new ControlLoopEvents.CooldownRequested(artifact, timestamp));
    }

    private Map<ScalingMetric, Double> sampleCurrentMetrics() {
        var metrics = new EnumMap<ScalingMetric, Double>(ScalingMetric.class);
        var allNodeMetrics = metricsCollector.allMetrics();
        var cpuAvg = allNodeMetrics.values().stream()
                                          .map(ControlLoopContext::extractCpuUsage)
                                          .filter(Objects::nonNull)
                                          .mapToDouble(Double::doubleValue)
                                          .average()
                                          .orElse(0.0);
        metrics.put(ScalingMetric.CPU, cpuAvg);
        var activeInvocations = invocationMetricsCollector.map(InvocationMetricsCollector::totalActiveInvocations)
                                                              .or(0L);
        metrics.put(ScalingMetric.ACTIVE_INVOCATIONS, activeInvocations.doubleValue());
        var p95Latency = invocationMetricsCollector.map(ControlLoopContext::calculateP95LatencyMs).or(0.0);
        metrics.put(ScalingMetric.P95_LATENCY, p95Latency);
        var errorRate = invocationMetricsCollector.map(ControlLoopContext::calculateAverageErrorRate).or(0.0);
        metrics.put(ScalingMetric.ERROR_RATE, errorRate);
        return metrics;
    }

    private static Double extractCpuUsage(Map<String, Double> nodeMetrics) {
        return nodeMetrics.get(ClusterSyncCollector.CPU_USAGE);
    }

    private static double calculateP95LatencyMs(InvocationMetricsCollector imc) {
        return imc.snapshot().stream()
                           .mapToLong(snapshot -> snapshot.metrics().estimatePercentileNs(95))
                           .average()
                           .orElse(0.0) / 1_000_000.0;
    }

    private static double calculateAverageErrorRate(InvocationMetricsCollector imc) {
        var snapshots = imc.snapshot();
        if (snapshots.isEmpty()) {return 0.0;}
        return snapshots.stream().mapToDouble(snapshot -> 1.0 - snapshot.metrics().successRate())
                               .average()
                               .orElse(0.0);
    }

    private void evaluateScalingDecisions(Map<ScalingMetric, Double> currentMetrics) {
        var loadFactorResult = computeLoadFactorWithCurrentValues(currentMetrics);
        cleanupExpiredCooldowns(nowMs());
        var guard = checkGuardRails(loadFactorResult);
        if (guard.isPresent()) {
            guard.onPresent(reason -> log.debug("Auto-scaling blocked: {}", reason));
            return;
        }
        log.debug("Composite load factor: score={}, components={}",
                  loadFactorResult.compositeScore(),
                  loadFactorResult.components());
        var context = new ControlContext(metricsCollector.allMetrics(), blueprintsSnapshot(), topology.get());
        controller.evaluate(context).onSuccess(decisions -> applyDecisionsWithGuards(decisions, loadFactorResult))
                           .onFailure(cause -> log.error("Failed to evaluate controller: {}",
                                                         cause.message()));
    }

    private LoadFactorResult computeLoadFactorWithCurrentValues(Map<ScalingMetric, Double> currentMetrics) {
        if (compositeLoadFactor instanceof CompositeLoadFactor.State state) {return state.computeWithCurrentValues(currentMetrics);}
        return compositeLoadFactor.compute();
    }

    private Option<String> checkGuardRails(LoadFactorResult loadFactorResult) {
        if (!loadFactorResult.canScale()) {return Option.some("not enough metric samples (windows not full)");}
        var sliceInProgress = describeInProgressSlice();
        if (sliceInProgress.isPresent()) {return sliceInProgress;}
        return checkActiveCooldowns();
    }

    private Option<String> checkActiveCooldowns() {
        var now = nowMs();
        var currentConfig = configRef.get();
        for (var entry : sliceActivationTimes.entrySet()) {
            var elapsed = now - entry.getValue();
            if (elapsed <currentConfig.sliceCooldown().millis()) {
                var remaining = currentConfig.sliceCooldown().millis() - elapsed;
                return Option.some("Slice " + entry.getKey() + " in cooldown (" + remaining + "ms remaining)");
            }
        }
        return Option.none();
    }

    private void applyDecisionsWithGuards(ClusterController.ControlDecisions decisions,
                                          LoadFactorResult loadFactorResult) {
        if (decisions.changes().isEmpty()) {
            log.trace("No scaling decisions");
            return;
        }
        var scalingConfig = configRef.get().scalingConfig();
        var errorRateHigh = compositeLoadFactor.isErrorRateHigh();
        var commands = new ArrayList<KVCommand<AetherKey>>();
        for (var change : decisions.changes()) {if (shouldApplyScalingDecision(change,
                                                                               loadFactorResult,
                                                                               scalingConfig,
                                                                               errorRateHigh)) {prepareChange(change).onPresent(commands::add);} else {logBlockedDecision(change,
                                                                                                                                                                          loadFactorResult,
                                                                                                                                                                          scalingConfig);}}
        if (!commands.isEmpty()) {cluster.apply(commands)
                                               .onFailure(cause -> log.error("Failed to apply blueprint changes: {}",
                                                                             cause.message()));}
    }

    private static boolean shouldApplyScalingDecision(BlueprintChange change,
                                                      LoadFactorResult loadFactorResult,
                                                      ScalingConfig scalingConfig,
                                                      boolean errorRateHigh) {
        return switch (change){
            case BlueprintChange.ScaleUp _ -> shouldApplyScaleUp(loadFactorResult, scalingConfig, errorRateHigh);
            case BlueprintChange.ScaleDown _ -> shouldApplyScaleDown(loadFactorResult, scalingConfig);
        };
    }

    private static boolean shouldApplyScaleUp(LoadFactorResult loadFactorResult,
                                              ScalingConfig scalingConfig,
                                              boolean errorRateHigh) {
        if (errorRateHigh) {
            log.debug("Scale-up blocked: error rate exceeds threshold");
            return false;
        }
        return loadFactorResult.compositeScore() >= scalingConfig.scaleUpThreshold();
    }

    private static boolean shouldApplyScaleDown(LoadFactorResult loadFactorResult, ScalingConfig scalingConfig) {
        return loadFactorResult.compositeScore() <= scalingConfig.scaleDownThreshold();
    }

    private static void logBlockedDecision(BlueprintChange change,
                                           LoadFactorResult loadFactorResult,
                                           ScalingConfig scalingConfig) {
        log.debug("Scaling decision {} blocked by composite score {} (up threshold: {}, down threshold: {})",
                  change.getClass().getSimpleName(),
                  loadFactorResult.compositeScore(),
                  scalingConfig.scaleUpThreshold(),
                  scalingConfig.scaleDownThreshold());
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
        var newInstances = cap(artifact, requestedInstances, clusterSize);
        if (newInstances == currentBlueprint.instances()) {return Option.none();}
        log.info("Applying scaling decision: {} from {} to {} instances",
                 artifact,
                 currentBlueprint.instances(),
                 newInstances);
        putBlueprint(artifact, newInstances, currentBlueprint.minInstances());
        publishScalingEvent(change, artifact, currentBlueprint.instances(), newInstances);
        var key = SliceTargetKey.sliceTargetKey(artifact.base());
        var value = SliceTargetValue.sliceTargetValue(artifact.version(), newInstances);
        return Option.some(new KVCommand.Put<>(key, value));
    }

    private static int computeRequestedInstances(BlueprintChange change, ClusterController.Blueprint currentBlueprint) {
        return switch (change){
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

    private static int cap(Artifact artifact, int requestedInstances, int clusterSize) {
        if (requestedInstances > clusterSize) {
            log.debug("Scaling {} capped at cluster size {} (requested {})", artifact, clusterSize, requestedInstances);
            return clusterSize;
        }
        return requestedInstances;
    }

    private boolean isStaleEvidence(CommunityScalingRequest request) {
        var age = nowMs() - request.evidence().timestampMs();
        if (age > STALE_EVIDENCE_MS) {
            log.info("Rejecting stale community scaling request from {} (age: {}ms)", request.communityId(), age);
            return true;
        }
        return false;
    }

    private boolean isInCommunityCooldown(CommunityScalingRequest request) {
        var artifactKey = request.artifact().asString();
        var lastScaling = communityScalingCooldowns.get(artifactKey);
        if (lastScaling != null && (nowMs() - lastScaling) <configRef.get().sliceCooldown().millis()) {
            log.debug("Community scaling request for {} in cooldown", artifactKey);
            return true;
        }
        return false;
    }

    private void applyCommunityScaling(CommunityScalingRequest request, ClusterController.Blueprint blueprint) {
        var artifactKey = request.artifact().asString();
        var clusterSize = effectiveClusterSize();
        var capped = Math.min(request.requestedInstances(), clusterSize);
        var newInstances = Math.max(capped, blueprint.minInstances());
        if (newInstances == blueprint.instances()) {
            log.debug("Community scaling request for {} results in no change", artifactKey);
            return;
        }
        log.info("Applying community scaling {} for {}: {} -> {} (requested by {})",
                 request.direction(),
                 request.artifact(),
                 blueprint.instances(),
                 newInstances,
                 request.governorId().id());
        putBlueprint(request.artifact(), newInstances, blueprint.minInstances());
        var cooldownTimestamp = nowMs();
        communityScalingCooldowns.put(artifactKey, cooldownTimestamp);
        persistCooldown(artifactKey, cooldownTimestamp);
        var key = SliceTargetKey.sliceTargetKey(request.artifact().base());
        var value = SliceTargetValue.sliceTargetValue(request.artifact().version(),
                                                      newInstances);
        cluster.apply(List.of(new KVCommand.Put<>(key, value)))
                     .onFailure(cause -> log.error("Failed to apply community scaling: {}",
                                                   cause.message()));
        publishCommunityScalingEvent(request, blueprint.instances(), newInstances);
    }

    private void publishCommunityScalingEvent(CommunityScalingRequest request,
                                              int previousInstances,
                                              int newInstances) {
        var scalingEvent = "UP".equals(request.direction())
                          ? ScalingEvent.ScaledUp.scaledUp(request.artifact(), previousInstances, newInstances)
                          : ScalingEvent.ScaledDown.scaledDown(request.artifact(), previousInstances, newInstances);
        eventPublisher.accept(scalingEvent);
    }

    private void persistCooldown(String artifactKey, long timestamp) {
        var configKey = ConfigKey.forKey(COOLDOWN_KEY_PREFIX + artifactKey);
        var configValue = ConfigValue.configValue(COOLDOWN_KEY_PREFIX + artifactKey, Long.toString(timestamp));
        cluster.apply(List.of(new KVCommand.Put<>(configKey, configValue)))
                     .onFailure(cause -> log.warn("Failed to persist cooldown for {}: {}",
                                                  artifactKey,
                                                  cause.message()));
    }

    @Contract private void restoreCooldownEntry(ConfigKey key, ConfigValue value) {
        if (!key.key().startsWith(COOLDOWN_KEY_PREFIX)) {return;}
        var artifactKey = key.key().substring(COOLDOWN_KEY_PREFIX.length());
        var timestamp = parseCooldownTimestamp(value.value());
        if (timestamp <= 0) {return;}
        var now = nowMs();
        var cooldownMs = configRef.get().sliceCooldown().millis();
        if ((now - timestamp) <cooldownMs) {
            communityScalingCooldowns.put(artifactKey, timestamp);
            log.debug("Restored cooldown for {} ({}ms remaining)", artifactKey, cooldownMs - (now - timestamp));
        }
    }

    private static long parseCooldownTimestamp(String value) {
        return Option.option(value).flatMap(v -> Number.parseLong(v).option())
                            .or(- 1L);
    }
}
