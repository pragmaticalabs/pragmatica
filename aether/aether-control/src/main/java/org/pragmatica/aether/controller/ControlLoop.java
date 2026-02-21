package org.pragmatica.aether.controller;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ClusterController.BlueprintChange;
import org.pragmatica.aether.controller.ClusterController.ControlContext;
import org.pragmatica.aether.controller.CompositeLoadFactor.LoadFactorResult;
import org.pragmatica.aether.metrics.MetricsCollector;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeAdded;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.consensus.topology.QuorumStateNotification;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Control loop that runs the ClusterController periodically on the leader node.
///
///
/// Responsibilities:
///
///   - Run only on leader node
///   - Periodically evaluate controller with current metrics
///   - Apply scaling decisions by updating blueprints in KVStore
///
@SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03"}) // MessageReceiver callbacks + framework lifecycle methods
public interface ControlLoop {
    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);

    @MessageReceiver
    void onTopologyChange(TopologyChangeNotification topologyChange);

    /// Handle slice target creation/update from KVStore.
    @MessageReceiver
    void onSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut);

    /// Handle slice node state change from KVStore.
    @MessageReceiver
    void onSliceNodePut(ValuePut<SliceNodeKey, SliceNodeValue> valuePut);

    /// Handle slice target removal from KVStore.
    @MessageReceiver
    void onSliceTargetRemove(ValueRemove<SliceTargetKey, SliceTargetValue> valueRemove);

    /// Handle slice node removal from KVStore.
    @MessageReceiver
    void onSliceNodeRemove(ValueRemove<SliceNodeKey, SliceNodeValue> valueRemove);

    /// Handle quorum state changes (stop evaluation when quorum disappears).
    @MessageReceiver
    void onQuorumStateChange(QuorumStateNotification notification);

    /// Register a blueprint for controller management.
    void registerBlueprint(Artifact artifact, int instances);

    /// Unregister a blueprint from controller management.
    void unregisterBlueprint(Artifact artifact);

    /// Get current controller configuration.
    ControllerConfig configuration();

    /// Update controller configuration at runtime.
    void updateConfiguration(ControllerConfig config);

    /// Stop the control loop.
    void stop();

    static ControlLoop controlLoop(NodeId self,
                                   ClusterController controller,
                                   MetricsCollector metricsCollector,
                                   Option<InvocationMetricsCollector> invocationMetricsCollector,
                                   ClusterNode<KVCommand<AetherKey>> cluster,
                                   TimeSpan interval,
                                   ControllerConfig config) {
        record controlLoop(NodeId self,
                           ClusterController controller,
                           MetricsCollector metricsCollector,
                           Option<InvocationMetricsCollector> invocationMetricsCollector,
                           ClusterNode<KVCommand<AetherKey>> cluster,
                           TimeSpan interval,
                           AtomicReference<ControllerConfig> configRef,
                           CompositeLoadFactor compositeLoadFactor,
                           AtomicReference<ScheduledFuture<?>> evaluationTask,
                           AtomicReference<List<NodeId>> topology,
                           ConcurrentHashMap<Artifact, ClusterController.Blueprint> blueprints,
                           ConcurrentHashMap<SliceNodeKey, SliceState> sliceStates,
                           AtomicReference<Long> activationTime,
                           ConcurrentHashMap<Artifact, Long> sliceActivationTimes) implements ControlLoop {
            private static final Logger log = LoggerFactory.getLogger(ControlLoop.class);

            @Override
            public void onLeaderChange(LeaderChange leaderChange) {
                if (leaderChange.localNodeIsLeader()) {
                    log.info("Node {} became leader, starting control loop", self);
                    resetProtectionState();
                    startEvaluation();
                } else {
                    log.info("Node {} is no longer leader, stopping control loop", self);
                    stopEvaluation();
                    clearProtectionState();
                }
            }

            @Override
            public void onTopologyChange(TopologyChangeNotification topologyChange) {
                switch (topologyChange) {
                    case NodeAdded(_, List<NodeId> newTopology) -> topology.set(newTopology);
                    case NodeRemoved(_, List<NodeId> newTopology) -> topology.set(newTopology);
                    default -> {}
                }
            }

            @Override
            public void onQuorumStateChange(QuorumStateNotification notification) {
                if (notification == QuorumStateNotification.DISAPPEARED) {
                    log.info("Quorum disappeared, stopping control loop evaluation");
                    stopEvaluation();
                }
            }

            @Override
            public void onSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut) {
                var artifactBase = valuePut.cause()
                                           .key()
                                           .artifactBase();
                var sliceTargetValue = valuePut.cause()
                                               .value();
                registerBlueprint(artifactBase.withVersion(sliceTargetValue.currentVersion()),
                                  sliceTargetValue.targetInstances());
            }

            @Override
            public void onSliceNodePut(ValuePut<SliceNodeKey, SliceNodeValue> valuePut) {
                handleSliceStateChange(valuePut.cause()
                                               .key(),
                                       valuePut.cause()
                                               .value()
                                               .state());
            }

            @Override
            public void onSliceTargetRemove(ValueRemove<SliceTargetKey, SliceTargetValue> valueRemove) {
                var artifactBase = valueRemove.cause()
                                              .key()
                                              .artifactBase();
                blueprints.keySet()
                          .stream()
                          .filter(artifactBase::matches)
                          .findFirst()
                          .ifPresent(this::unregisterBlueprint);
            }

            @Override
            public void onSliceNodeRemove(ValueRemove<SliceNodeKey, SliceNodeValue> valueRemove) {
                var sliceNodeKey = valueRemove.cause()
                                              .key();
                sliceStates.remove(sliceNodeKey);
                log.debug("Removed slice state tracking for {}", sliceNodeKey);
            }

            @Override
            public void registerBlueprint(Artifact artifact, int instances) {
                blueprints.put(artifact, new ClusterController.Blueprint(artifact, instances));
                log.info("Registered blueprint: {} with {} instances", artifact, instances);
            }

            @Override
            public void unregisterBlueprint(Artifact artifact) {
                blueprints.remove(artifact);
                log.info("Unregistered blueprint: {}", artifact);
            }

            @Override
            public ControllerConfig configuration() {
                return configRef.get();
            }

            @Override
            public void updateConfiguration(ControllerConfig config) {
                configRef.set(config);
            }

            @Override
            public void stop() {
                stopEvaluation();
            }

            private void startEvaluation() {
                stopEvaluation();
                var task = SharedScheduler.scheduleAtFixedRate(this::runEvaluation, interval);
                evaluationTask.set(task);
            }

            private void stopEvaluation() {
                Option.option(evaluationTask.getAndSet(null))
                      .onPresent(existing -> existing.cancel(false));
            }

            private void runEvaluation() {
                // Scheduler boundary - generic catch prevents scheduler thread death
                try{
                    if (blueprints.isEmpty()) {
                        log.trace("No blueprints registered, skipping evaluation");
                        return;
                    }
                    var currentMetrics = sampleAllMetrics();
                    recordMetricSamples(currentMetrics);
                    evaluateScalingDecisions(currentMetrics);
                } catch (Exception e) {
                    // Scheduler boundary - generic catch prevents scheduler thread death
                    log.error("Control loop error: {}", e.getMessage(), e);
                }
            }

            private Map<ScalingMetric, Double> sampleAllMetrics() {
                return sampleCurrentMetrics();
            }

            private void recordMetricSamples(Map<ScalingMetric, Double> metrics) {
                metrics.forEach(compositeLoadFactor::recordSample);
            }

            private void evaluateScalingDecisions(Map<ScalingMetric, Double> currentMetrics) {
                var loadFactorResult = computeLoadFactorWithCurrentValues(currentMetrics);
                cleanupExpiredCooldowns();
                var guardResult = checkGuardRails(loadFactorResult);
                if (guardResult.isPresent()) {
                    guardResult.onPresent(reason -> log.debug("Auto-scaling blocked: {}", reason));
                    return;
                }
                log.debug("Composite load factor: score={}, components={}",
                          loadFactorResult.compositeScore(),
                          loadFactorResult.components());
                var context = new ControlContext(metricsCollector.allMetrics(), Map.copyOf(blueprints), topology.get());
                controller.evaluate(context)
                          .onSuccess(decisions -> applyDecisionsWithGuards(decisions, loadFactorResult))
                          .onFailure(cause -> log.error("Failed to evaluate controller: {}",
                                                        cause.message()));
            }

            private Option<String> checkGuardRails(LoadFactorResult loadFactorResult) {
                if (!loadFactorResult.canScale()) {
                    return Option.some("not enough metric samples (windows not full)");
                }
                var sliceInProgress = checkSliceInProgress();
                if (sliceInProgress.isPresent()) {
                    return sliceInProgress;
                }
                return checkLegacyScalingBlocked();
            }

            private Map<ScalingMetric, Double> sampleCurrentMetrics() {
                var metrics = new EnumMap<ScalingMetric, Double>(ScalingMetric.class);
                var allNodeMetrics = metricsCollector.allMetrics();
                var cpuAvg = allNodeMetrics.values()
                                           .stream()
                                           .map(this::extractCpuUsage)
                                           .filter(Objects::nonNull)
                                           .mapToDouble(Double::doubleValue)
                                           .average()
                                           .orElse(0.0);
                metrics.put(ScalingMetric.CPU, cpuAvg);
                var activeInvocations = invocationMetricsCollector.map(InvocationMetricsCollector::totalActiveInvocations)
                                                                  .or(0L);
                metrics.put(ScalingMetric.ACTIVE_INVOCATIONS, activeInvocations.doubleValue());
                var p95Latency = invocationMetricsCollector.map(this::calculateP95LatencyMs)
                                                           .or(0.0);
                metrics.put(ScalingMetric.P95_LATENCY, p95Latency);
                var errorRate = invocationMetricsCollector.map(this::calculateAverageErrorRate)
                                                          .or(0.0);
                metrics.put(ScalingMetric.ERROR_RATE, errorRate);
                return metrics;
            }

            private Double extractCpuUsage(Map<String, Double> nodeMetrics) {
                return nodeMetrics.get(MetricsCollector.CPU_USAGE);
            }

            private double calculateP95LatencyMs(InvocationMetricsCollector imc) {
                return imc.snapshot()
                          .stream()
                          .mapToLong(this::extractP95LatencyNs)
                          .average()
                          .orElse(0.0) / 1_000_000.0;
            }

            private long extractP95LatencyNs(InvocationMetricsCollector.MethodSnapshot snapshot) {
                return snapshot.metrics()
                               .estimatePercentileNs(95);
            }

            private double calculateAverageErrorRate(InvocationMetricsCollector imc) {
                var snapshots = imc.snapshot();
                if (snapshots.isEmpty()) {
                    return 0.0;
                }
                return snapshots.stream()
                                .mapToDouble(this::extractErrorRate)
                                .average()
                                .orElse(0.0);
            }

            private double extractErrorRate(InvocationMetricsCollector.MethodSnapshot snapshot) {
                return 1.0 - snapshot.metrics()
                                    .successRate();
            }

            private LoadFactorResult computeLoadFactorWithCurrentValues(Map<ScalingMetric, Double> currentMetrics) {
                if (compositeLoadFactor instanceof CompositeLoadFactor.State state) {
                    return state.computeWithCurrentValues(currentMetrics);
                }
                return compositeLoadFactor.compute();
            }

            private void applyDecisionsWithGuards(ClusterController.ControlDecisions decisions,
                                                  LoadFactorResult loadFactorResult) {
                if (decisions.changes()
                             .isEmpty()) {
                    log.trace("No scaling decisions");
                    return;
                }
                var scalingConfig = configRef.get()
                                             .scalingConfig();
                var errorRateHigh = compositeLoadFactor.isErrorRateHigh();
                var commands = new ArrayList<KVCommand<AetherKey>>();
                for (var change : decisions.changes()) {
                    var shouldApply = shouldApplyScalingDecision(change, loadFactorResult, scalingConfig, errorRateHigh);
                    if (shouldApply) {
                        prepareChange(change).onPresent(commands::add);
                    } else {
                        logBlockedDecision(change, loadFactorResult, scalingConfig);
                    }
                }
                if (!commands.isEmpty()) {
                    cluster.apply(commands)
                           .onFailure(cause -> log.error("Failed to apply blueprint changes: {}",
                                                         cause.message()));
                }
            }

            private boolean shouldApplyScalingDecision(BlueprintChange change,
                                                       LoadFactorResult loadFactorResult,
                                                       ScalingConfig scalingConfig,
                                                       boolean errorRateHigh) {
                return switch (change) {
                    case BlueprintChange.ScaleUp _ -> shouldApplyScaleUp(loadFactorResult, scalingConfig, errorRateHigh);
                    case BlueprintChange.ScaleDown _ -> shouldApplyScaleDown(loadFactorResult, scalingConfig);
                };
            }

            private boolean shouldApplyScaleUp(LoadFactorResult loadFactorResult,
                                               ScalingConfig scalingConfig,
                                               boolean errorRateHigh) {
                if (errorRateHigh) {
                    log.debug("Scale-up blocked: error rate exceeds threshold");
                    return false;
                }
                return loadFactorResult.compositeScore() >= scalingConfig.scaleUpThreshold();
            }

            private boolean shouldApplyScaleDown(LoadFactorResult loadFactorResult, ScalingConfig scalingConfig) {
                return loadFactorResult.compositeScore() <= scalingConfig.scaleDownThreshold();
            }

            private void logBlockedDecision(BlueprintChange change,
                                            LoadFactorResult loadFactorResult,
                                            ScalingConfig scalingConfig) {
                log.debug("Scaling decision {} blocked by composite score {} (up threshold: {}, down threshold: {})",
                          change.getClass()
                                .getSimpleName(),
                          loadFactorResult.compositeScore(),
                          scalingConfig.scaleUpThreshold(),
                          scalingConfig.scaleDownThreshold());
            }

            private Option<String> checkSliceInProgress() {
                return Option.from(sliceStates.entrySet()
                                              .stream()
                                              .filter(this::isSliceInProgress)
                                              .findFirst()
                                              .map(this::formatSliceInProgressMessage));
            }

            private boolean isSliceInProgress(Map.Entry<SliceNodeKey, SliceState> entry) {
                return entry.getValue()
                            .isInProgress();
            }

            private String formatSliceInProgressMessage(Map.Entry<SliceNodeKey, SliceState> entry) {
                return "Slice " + entry.getKey()
                                      .artifact() + " in progress state: " + entry.getValue();
            }

            private void cleanupExpiredCooldowns() {
                var now = System.currentTimeMillis();
                sliceActivationTimes.entrySet()
                                    .removeIf(entry -> isSliceCooldownExpired(entry, now));
            }

            private Option<String> checkLegacyScalingBlocked() {
                var now = System.currentTimeMillis();
                var activation = activationTime.get();
                var currentConfig = configRef.get();
                if (activation != null && (now - activation) < currentConfig.warmUpPeriodMs()) {
                    var remaining = currentConfig.warmUpPeriodMs() - (now - activation);
                    return Option.some("Warm-up period active (" + remaining + "ms remaining)");
                }
                for (var entry : sliceActivationTimes.entrySet()) {
                    var elapsed = now - entry.getValue();
                    if (elapsed < currentConfig.sliceCooldownMs()) {
                        var remaining = currentConfig.sliceCooldownMs() - elapsed;
                        return Option.some("Slice " + entry.getKey() + " in cooldown (" + remaining + "ms remaining)");
                    }
                }
                return Option.none();
            }

            private boolean isSliceCooldownExpired(Map.Entry<Artifact, Long> entry, long now) {
                return (now - entry.getValue()) >= configRef.get()
                                                            .sliceCooldownMs();
            }

            private Option<KVCommand<AetherKey>> prepareChange(BlueprintChange change) {
                var artifact = change.artifact();
                var blueprint = blueprints.get(artifact);
                if (blueprint == null) {
                    log.warn("Blueprint not found for {}, skipping change", artifact);
                    return Option.none();
                }
                return prepareChangeToBlueprint(change, artifact, blueprint);
            }

            private Option<KVCommand<AetherKey>> prepareChangeToBlueprint(BlueprintChange change,
                                                                          Artifact artifact,
                                                                          ClusterController.Blueprint currentBlueprint) {
                var requestedInstances = switch (change) {
                    case BlueprintChange.ScaleUp(_, int additional) -> currentBlueprint.instances() + additional;
                    case BlueprintChange.ScaleDown(_, int reduceBy) -> Math.max(1,
                                                                                currentBlueprint.instances() - reduceBy);
                };
                var clusterSize = topology.get()
                                          .size();
                if (clusterSize == 0) {
                    log.debug("Scaling {} capped at 1 (cluster size is 0)", artifact);
                    clusterSize = 1;
                }
                var newInstances = requestedInstances;
                if (requestedInstances > clusterSize) {
                    log.debug("Scaling {} capped at cluster size {} (requested {})",
                              artifact,
                              clusterSize,
                              requestedInstances);
                    newInstances = clusterSize;
                }
                if (newInstances == currentBlueprint.instances()) {
                    return Option.none();
                }
                log.info("Applying scaling decision: {} from {} to {} instances",
                         artifact,
                         currentBlueprint.instances(),
                         newInstances);
                blueprints.put(artifact, new ClusterController.Blueprint(artifact, newInstances));
                var key = SliceTargetKey.sliceTargetKey(artifact.base());
                var value = SliceTargetValue.sliceTargetValue(artifact.version(), newInstances);
                return Option.some(new KVCommand.Put<>(key, value));
            }

            private void resetProtectionState() {
                activationTime.set(System.currentTimeMillis());
                sliceStates.clear();
                sliceActivationTimes.clear();
                log.debug("Protection state reset, warm-up period started");
            }

            private void clearProtectionState() {
                activationTime.set(null);
                sliceStates.clear();
                sliceActivationTimes.clear();
            }

            private void handleSliceStateChange(SliceNodeKey sliceNodeKey, SliceState newState) {
                var previousState = sliceStates.put(sliceNodeKey, newState);
                if (newState == SliceState.ACTIVE && previousState != SliceState.ACTIVE) {
                    sliceActivationTimes.put(sliceNodeKey.artifact(), System.currentTimeMillis());
                    log.debug("Slice {} reached ACTIVE, cooldown started", sliceNodeKey.artifact());
                }
                if (newState.isInProgress()) {
                    log.debug("Slice {} in progress state: {}", sliceNodeKey, newState);
                }
            }
        }
        return new controlLoop(self,
                               controller,
                               metricsCollector,
                               invocationMetricsCollector,
                               cluster,
                               interval,
                               new AtomicReference<>(config),
                               CompositeLoadFactor.compositeLoadFactor(config.scalingConfig()),
                               new AtomicReference<>(),
                               new AtomicReference<>(List.of()),
                               new ConcurrentHashMap<>(),
                               new ConcurrentHashMap<>(),
                               new AtomicReference<>(null),
                               new ConcurrentHashMap<>());
    }
}
