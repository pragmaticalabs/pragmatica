package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CanaryDeploymentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CanaryDeploymentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.utility.KSUID;
import org.pragmatica.lang.concurrent.CancellableTask;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Manages canary deployment operations across the cluster.
///
///
/// Implements a multi-stage progressive traffic shifting model:
/// <ol>
///   - **Deploy stage**: Canary instances deployed with 0% traffic
///   - **Canary stages**: Traffic progressively shifted through configured stages
///   - **Promote stage**: Full traffic shifted to new version
/// </ol>
///
///
/// Health is evaluated periodically. If thresholds are breached, the canary
/// is automatically rolled back. Stages advance automatically when health
/// is sustained beyond the configured observation period.
///
///
/// Canary deployments are orchestrated by the leader node via consensus.
/// All state is stored in the KV-Store for persistence and visibility.
public interface CanaryDeploymentManager {
    /// Start a new canary deployment.
    Promise<CanaryDeployment> startCanary(ArtifactBase artifactBase,
                                          Version newVersion,
                                          int instances,
                                          List<CanaryStage> stages,
                                          HealthThresholds thresholds,
                                          CanaryAnalysisConfig analysisConfig,
                                          CleanupPolicy cleanupPolicy);

    /// Manually promote the canary to full deployment (skip remaining stages).
    Promise<CanaryDeployment> promoteCanary(String canaryId);

    /// Manually promote the canary to 100% and complete immediately.
    Promise<CanaryDeployment> promoteCanaryFull(String canaryId);

    /// Manually rollback the canary.
    Promise<CanaryDeployment> rollbackCanary(String canaryId);

    /// Get canary deployment by ID.
    Option<CanaryDeployment> getCanary(String canaryId);

    /// Get active canary deployment for an artifact.
    Option<CanaryDeployment> getActiveCanary(ArtifactBase artifactBase);

    /// List all active canary deployments.
    List<CanaryDeployment> activeCanaries();

    /// List all canary deployments.
    List<CanaryDeployment> allCanaries();

    /// Get health comparison for a canary deployment.
    Promise<CanaryHealthComparison> getHealthComparison(String canaryId);

    /// Handle leader change (start/stop evaluation loop).
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onLeaderChange(LeaderChange leaderChange);

    /// Handle deployment failure for auto-rollback.
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onDeploymentFailed(DeploymentEvent.DeploymentFailed event);

    /// Default evaluation interval (30 seconds).
    TimeSpan DEFAULT_EVALUATION_INTERVAL = TimeSpan.timeSpan(30)
                                                  .seconds();

    /// Default KV operation timeout.
    TimeSpan DEFAULT_KV_OPERATION_TIMEOUT = TimeSpan.timeSpan(30)
                                                   .seconds();

    /// Default terminal retention (1 hour).
    long DEFAULT_TERMINAL_RETENTION_MS = TimeUnit.HOURS.toMillis(1);

    /// Factory method following JBCT naming convention.
    static CanaryDeploymentManager canaryDeploymentManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                           KVStore<AetherKey, AetherValue> kvStore,
                                                           InvocationMetricsCollector metricsCollector) {
        return canaryDeploymentManager(clusterNode,
                                       kvStore,
                                       metricsCollector,
                                       DEFAULT_KV_OPERATION_TIMEOUT,
                                       DEFAULT_TERMINAL_RETENTION_MS,
                                       DEFAULT_EVALUATION_INTERVAL);
    }

    /// Factory method with custom settings.
    @SuppressWarnings({"JBCT-SEQ-01", "JBCT-NAM-01"})
    static CanaryDeploymentManager canaryDeploymentManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                           KVStore<AetherKey, AetherValue> kvStore,
                                                           InvocationMetricsCollector metricsCollector,
                                                           TimeSpan kvOperationTimeout,
                                                           long terminalRetentionMs,
                                                           TimeSpan evaluationInterval) {
        record canaryDeploymentManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                       KVStore<AetherKey, AetherValue> kvStore,
                                       InvocationMetricsCollector metricsCollector,
                                       TimeSpan kvOperationTimeout,
                                       long terminalRetentionMs,
                                       TimeSpan evaluationInterval,
                                       Map<String, CanaryDeployment> canaries,
                                       CancellableTask evaluationFuture) implements CanaryDeploymentManager {
            private static final Logger log = LoggerFactory.getLogger(CanaryDeploymentManager.class);

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onLeaderChange(LeaderChange leaderChange) {
                if (leaderChange.localNodeIsLeader()) {
                    log.info("Canary deployment manager active (leader)");
                    restoreState();
                    startEvaluationLoop();
                } else {
                    log.info("Canary deployment manager passive (follower)");
                    stopEvaluationLoop();
                }
            }

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onDeploymentFailed(DeploymentEvent.DeploymentFailed event) {
                var artifactBase = event.artifact()
                                        .base();
                getActiveCanary(artifactBase).filter(canary -> canary.newVersion()
                                                                     .equals(event.artifact()
                                                                                  .version()))
                               .filter(CanaryDeployment::isActive)
                               .onPresent(canary -> triggerAutoRollback(canary, event));
            }

            @SuppressWarnings("JBCT-RET-01") // Side-effect helper — void inherent
            private void triggerAutoRollback(CanaryDeployment canary, DeploymentEvent.DeploymentFailed event) {
                log.warn("Auto-rollback triggered for canary {} — new version {} failed on node {}: {}",
                         canary.canaryId(),
                         event.artifact(),
                         event.nodeId(),
                         event.errorMessage());
                rollbackCanary(canary.canaryId())
                .onFailure(cause -> log.error("Auto-rollback failed for canary {}: {}",
                                              canary.canaryId(),
                                              cause.message()));
            }

            // --- Evaluation loop (SharedScheduler + CancellableTask pattern from ControlLoop) ---
            @SuppressWarnings("JBCT-RET-01") // Side-effect helper — void inherent
            private void startEvaluationLoop() {
                evaluationFuture.set(SharedScheduler.scheduleAtFixedRate(this::evaluateCanaries, evaluationInterval));
                log.info("Canary evaluation loop started (interval: {}ms)", evaluationInterval.millis());
            }

            @SuppressWarnings("JBCT-RET-01") // Side-effect helper — void inherent
            private void stopEvaluationLoop() {
                evaluationFuture.cancel();
            }

            @SuppressWarnings("JBCT-RET-01")
            private void evaluateCanaries() {
                canaries.values()
                        .stream()
                        .filter(c -> c.state() == CanaryState.CANARY_ACTIVE)
                        .forEach(this::evaluateSingleCanary);
            }

            @SuppressWarnings("JBCT-RET-01")
            private void evaluateSingleCanary(CanaryDeployment canary) {
                var comparison = collectHealthComparison(canary);
                logEvaluation(canary, comparison);
                if (comparison.shouldRollback()) {
                    handleAutoRollback(canary, comparison);
                    return;
                }
                if (comparison.verdict() == CanaryHealthComparison.Verdict.HEALTHY && canary.hasExceededObservation()) {
                    handleStageAdvancement(canary);
                }
            }

            @SuppressWarnings("JBCT-RET-01") // Side-effect helper — void inherent
            private void logEvaluation(CanaryDeployment canary, CanaryHealthComparison comparison) {
                log.debug("Canary {} stage {}/{} verdict={} (canary: err={} p99={}ms, baseline: err={} p99={}ms)",
                          canary.canaryId(),
                          canary.currentStageIndex() + 1,
                          canary.stages()
                                .size(),
                          comparison.verdict(),
                          comparison.canaryMetrics()
                                    .errorRate(),
                          comparison.canaryMetrics()
                                    .p99LatencyMs(),
                          comparison.baselineMetrics()
                                    .errorRate(),
                          comparison.baselineMetrics()
                                    .p99LatencyMs());
            }

            @SuppressWarnings("JBCT-RET-01")
            private void handleAutoRollback(CanaryDeployment canary, CanaryHealthComparison comparison) {
                log.warn("Auto-rollback for canary {} — verdict: {}", canary.canaryId(), comparison.verdict());
                canary.transitionTo(CanaryState.AUTO_ROLLBACK)
                      .async()
                      .flatMap(this::cacheAndPersistCanary)
                      .flatMap(this::removeNewVersion)
                      .onFailure(cause -> log.error("Auto-rollback failed for canary {}: {}",
                                                    canary.canaryId(),
                                                    cause.message()));
            }

            @SuppressWarnings("JBCT-RET-01")
            private void handleStageAdvancement(CanaryDeployment canary) {
                if (canary.isLastStage()) {
                    handlePromotion(canary);
                    return;
                }
                advanceCanaryStage(canary);
            }

            @SuppressWarnings("JBCT-RET-01")
            private void handlePromotion(CanaryDeployment canary) {
                log.info("Canary {} passed final stage — promoting", canary.canaryId());
                canary.transitionTo(CanaryState.PROMOTING)
                      .async()
                      .flatMap(this::cacheAndPersistCanary)
                      .flatMap(this::completePromotion)
                      .onFailure(cause -> log.error("Promotion failed for canary {}: {}",
                                                    canary.canaryId(),
                                                    cause.message()));
            }

            @SuppressWarnings("JBCT-RET-01")
            private void advanceCanaryStage(CanaryDeployment canary) {
                canary.advanceStage()
                      .async()
                      .flatMap(this::cacheAndPersistCanary)
                      .flatMap(this::persistRouting)
                      .onSuccess(advanced -> log.info("Canary {} advanced to stage {}/{} ({}% traffic)",
                                                      advanced.canaryId(),
                                                      advanced.currentStageIndex() + 1,
                                                      advanced.stages()
                                                              .size(),
                                                      advanced.routing()
                                                              .newVersionPercentage()))
                      .onFailure(cause -> log.error("Stage advancement failed for canary {}: {}",
                                                    canary.canaryId(),
                                                    cause.message()));
            }

            // --- Core operations ---
            @Override
            public Promise<CanaryDeployment> startCanary(ArtifactBase artifactBase,
                                                         Version newVersion,
                                                         int instances,
                                                         List<CanaryStage> stages,
                                                         HealthThresholds thresholds,
                                                         CanaryAnalysisConfig analysisConfig,
                                                         CleanupPolicy cleanupPolicy) {
                return requireLeader().flatMap(_ -> checkNoActiveCanary(artifactBase))
                                    .flatMap(_ -> findCurrentVersion(artifactBase))
                                    .flatMap(oldVersion -> createAndDeployCanary(artifactBase,
                                                                                 oldVersion,
                                                                                 newVersion,
                                                                                 instances,
                                                                                 stages,
                                                                                 thresholds,
                                                                                 analysisConfig,
                                                                                 cleanupPolicy));
            }

            @Override
            public Promise<CanaryDeployment> promoteCanary(String canaryId) {
                return requireLeader().flatMap(_ -> findCanary(canaryId))
                                    .flatMap(this::validateAndPromote);
            }

            @Override
            public Promise<CanaryDeployment> promoteCanaryFull(String canaryId) {
                return requireLeader().flatMap(_ -> findCanary(canaryId))
                                    .flatMap(this::validateAndPromoteFull);
            }

            @Override
            public Promise<CanaryDeployment> rollbackCanary(String canaryId) {
                return requireLeader().flatMap(_ -> findCanary(canaryId))
                                    .flatMap(this::validateAndRollback);
            }

            @Override
            public Option<CanaryDeployment> getCanary(String canaryId) {
                return Option.option(canaries.get(canaryId));
            }

            @Override
            public Option<CanaryDeployment> getActiveCanary(ArtifactBase artifactBase) {
                return Option.from(canaries.values()
                                           .stream()
                                           .filter(c -> c.artifactBase()
                                                         .equals(artifactBase) && c.isActive())
                                           .findFirst());
            }

            @Override
            public List<CanaryDeployment> activeCanaries() {
                return canaries.values()
                               .stream()
                               .filter(CanaryDeployment::isActive)
                               .toList();
            }

            @Override
            public List<CanaryDeployment> allCanaries() {
                return List.copyOf(canaries.values());
            }

            @Override
            public Promise<CanaryHealthComparison> getHealthComparison(String canaryId) {
                return Option.option(canaries.get(canaryId))
                             .toResult(CanaryDeploymentError.CanaryNotFound.canaryNotFound(canaryId))
                             .async()
                             .map(this::collectHealthComparison);
            }

            // --- Private helpers ---
            private Promise<Unit> requireLeader() {
                if (!clusterNode.leaderManager()
                                .isLeader()) {
                    return CanaryDeploymentError.NotLeader.INSTANCE.promise();
                }
                return Promise.success(Unit.unit());
            }

            private Promise<Unit> checkNoActiveCanary(ArtifactBase artifactBase) {
                return getActiveCanary(artifactBase).isPresent()
                       ? CanaryDeploymentError.CanaryAlreadyExists.canaryAlreadyExists(artifactBase)
                                              .promise()
                       : Promise.success(Unit.unit());
            }

            private Promise<CanaryDeployment> findCanary(String canaryId) {
                return Option.option(canaries.get(canaryId))
                             .toResult(CanaryDeploymentError.CanaryNotFound.canaryNotFound(canaryId))
                             .async();
            }

            private Promise<Version> findCurrentVersion(ArtifactBase artifactBase) {
                var key = SliceTargetKey.sliceTargetKey(artifactBase);
                return kvStore.get(key)
                              .map(value -> ((SliceTargetValue) value).currentVersion())
                              .toResult(CanaryDeploymentError.InitialDeployment.initialDeployment(artifactBase))
                              .async();
            }

            private Promise<CanaryDeployment> createAndDeployCanary(ArtifactBase artifactBase,
                                                                    Version oldVersion,
                                                                    Version newVersion,
                                                                    int instances,
                                                                    List<CanaryStage> stages,
                                                                    HealthThresholds thresholds,
                                                                    CanaryAnalysisConfig analysisConfig,
                                                                    CleanupPolicy cleanupPolicy) {
                var canaryId = KSUID.ksuid()
                                    .encoded();
                var canary = CanaryDeployment.canaryDeployment(canaryId,
                                                               artifactBase,
                                                               oldVersion,
                                                               newVersion,
                                                               instances,
                                                               stages,
                                                               thresholds,
                                                               analysisConfig,
                                                               cleanupPolicy);
                log.info("Starting canary deployment {} for {} from {} to {} ({} stages)",
                         canaryId,
                         artifactBase,
                         oldVersion,
                         newVersion,
                         stages.size());
                canaries.put(canaryId, canary);
                return persistAndTransition(canary, CanaryState.DEPLOYING)
                .flatMap(deployed -> deployNewVersion(deployed, instances));
            }

            @SuppressWarnings("unchecked")
            private Promise<CanaryDeployment> deployNewVersion(CanaryDeployment canary, int instances) {
                var artifactBase = canary.artifactBase();
                var routingKey = new AetherKey.VersionRoutingKey(artifactBase);
                var routingValue = new AetherValue.VersionRoutingValue(canary.oldVersion(),
                                                                       canary.newVersion(),
                                                                       canary.routing()
                                                                             .newWeight(),
                                                                       canary.routing()
                                                                             .oldWeight(),
                                                                       System.currentTimeMillis());
                var routingCmd = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(routingKey, routingValue);
                var targetKey = SliceTargetKey.sliceTargetKey(artifactBase);
                var existingMinInstances = kvStore.get(targetKey)
                                                  .filter(v -> v instanceof SliceTargetValue)
                                                  .map(v -> ((SliceTargetValue) v).effectiveMinInstances())
                                                  .or(instances);
                var targetValue = new SliceTargetValue(canary.newVersion(),
                                                       instances,
                                                       existingMinInstances,
                                                       Option.none(),
                                                       "CORE_ONLY",
                                                       System.currentTimeMillis());
                var targetCmd = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(targetKey, targetValue);
                log.info("Deploying {} canary instances of {} (version {})",
                         instances,
                         artifactBase,
                         canary.newVersion());
                return clusterNode.<Unit> apply(List.of(routingCmd, targetCmd))
                                  .timeout(kvOperationTimeout)
                                  .flatMap(_ -> transitionToCanaryActive(canary));
            }

            private Promise<CanaryDeployment> transitionToCanaryActive(CanaryDeployment canary) {
                return persistAndTransition(canary, CanaryState.CANARY_ACTIVE).flatMap(this::applyFirstStageRouting);
            }

            private Promise<CanaryDeployment> applyFirstStageRouting(CanaryDeployment canary) {
                var firstStageRouting = canary.stages()
                                              .getFirst()
                                              .toRouting();
                var withRouting = canary.withRouting(firstStageRouting);
                canaries.put(canary.canaryId(), withRouting);
                return persistRouting(withRouting);
            }

            private Promise<CanaryDeployment> validateAndPromote(CanaryDeployment canary) {
                if (canary.state() != CanaryState.CANARY_ACTIVE) {
                    return CanaryDeploymentError.InvalidCanaryState.invalidCanaryState(canary.state(),
                                                                                       CanaryState.PROMOTING)
                                                .promise();
                }
                log.info("Manual promotion for canary {}", canary.canaryId());
                return canary.transitionTo(CanaryState.PROMOTING)
                             .async()
                             .flatMap(this::cacheAndPersistCanary)
                             .flatMap(this::completePromotion);
            }

            private Promise<CanaryDeployment> validateAndPromoteFull(CanaryDeployment canary) {
                if (canary.state() != CanaryState.CANARY_ACTIVE) {
                    return CanaryDeploymentError.InvalidCanaryState.invalidCanaryState(canary.state(),
                                                                                       CanaryState.PROMOTING)
                                                .promise();
                }
                log.info("Full promotion for canary {}", canary.canaryId());
                var withFullRouting = canary.withRouting(VersionRouting.ALL_NEW);
                return withFullRouting.transitionTo(CanaryState.PROMOTING)
                                      .async()
                                      .flatMap(this::cacheAndPersistCanary)
                                      .flatMap(this::completePromotion);
            }

            private Promise<CanaryDeployment> completePromotion(CanaryDeployment canary) {
                var withFullRouting = canary.withRouting(VersionRouting.ALL_NEW);
                canaries.put(canary.canaryId(), withFullRouting);
                return persistRouting(withFullRouting).flatMap(this::cleanupOldVersion);
            }

            private Promise<CanaryDeployment> cleanupOldVersion(CanaryDeployment canary) {
                log.info("Cleanup: finalizing slice target for {} to version {}",
                         canary.artifactBase(),
                         canary.newVersion());
                return removeRoutingKey(canary).flatMap(_ -> persistAndTransition(canary, CanaryState.PROMOTED));
            }

            private Promise<CanaryDeployment> validateAndRollback(CanaryDeployment canary) {
                if (canary.isTerminal()) {
                    return CanaryDeploymentError.InvalidCanaryState.invalidCanaryState(canary.state(),
                                                                                       CanaryState.ROLLING_BACK)
                                                .promise();
                }
                log.info("Rolling back canary {}", canary.canaryId());
                var withOldRouting = canary.withRouting(VersionRouting.ALL_OLD);
                return withOldRouting.transitionTo(CanaryState.ROLLING_BACK)
                                     .async()
                                     .flatMap(this::cacheAndPersistCanary)
                                     .flatMap(this::removeNewVersion);
            }

            private Promise<CanaryDeployment> removeNewVersion(CanaryDeployment canary) {
                log.info("Rolling back slice target for {} to version {}", canary.artifactBase(), canary.oldVersion());
                return updateSliceTargetVersion(canary.artifactBase(),
                                                canary.oldVersion()).flatMap(_ -> removeRoutingKey(canary))
                                               .flatMap(_ -> persistAndTransition(canary, CanaryState.ROLLED_BACK));
            }

            // --- Persistence ---
            private Promise<CanaryDeployment> persistAndTransition(CanaryDeployment canary, CanaryState newState) {
                return canary.transitionTo(newState)
                             .async()
                             .flatMap(this::cacheAndPersistCanary);
            }

            @SuppressWarnings("unchecked")
            private Promise<CanaryDeployment> cacheAndPersistCanary(CanaryDeployment canary) {
                canaries.put(canary.canaryId(), canary);
                if (canary.isTerminal()) {
                    pruneTerminalCanaries();
                }
                var key = new AetherKey.CanaryDeploymentKey(canary.canaryId());
                var value = buildCanaryValue(canary);
                var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);
                return clusterNode.<Unit> apply(List.of(command))
                                  .timeout(kvOperationTimeout)
                                  .map(_ -> canary);
            }

            private CanaryDeploymentValue buildCanaryValue(CanaryDeployment canary) {
                return new CanaryDeploymentValue(canary.canaryId(),
                                                 canary.artifactBase(),
                                                 canary.oldVersion(),
                                                 canary.newVersion(),
                                                 canary.state()
                                                       .name(),
                                                 serializeStages(canary.stages()),
                                                 canary.currentStageIndex(),
                                                 canary.stageEnteredAt(),
                                                 canary.routing()
                                                       .newWeight(),
                                                 canary.routing()
                                                       .oldWeight(),
                                                 canary.newInstances(),
                                                 canary.thresholds()
                                                       .maxErrorRate(),
                                                 canary.thresholds()
                                                       .maxLatencyMs(),
                                                 canary.thresholds()
                                                       .requireManualApproval(),
                                                 canary.analysisConfig()
                                                       .mode()
                                                       .name(),
                                                 canary.analysisConfig()
                                                       .relativeThresholdPercent(),
                                                 canary.cleanupPolicy()
                                                       .name(),
                                                 canary.blueprintId()
                                                       .or(""),
                                                 serializeArtifacts(canary.artifacts()),
                                                 canary.createdAt(),
                                                 System.currentTimeMillis());
            }

            @SuppressWarnings("unchecked")
            private Promise<CanaryDeployment> persistRouting(CanaryDeployment canary) {
                var key = new AetherKey.VersionRoutingKey(canary.artifactBase());
                var value = new AetherValue.VersionRoutingValue(canary.oldVersion(),
                                                                canary.newVersion(),
                                                                canary.routing()
                                                                      .newWeight(),
                                                                canary.routing()
                                                                      .oldWeight(),
                                                                System.currentTimeMillis());
                var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);
                return clusterNode.<Unit> apply(List.of(command))
                                  .timeout(kvOperationTimeout)
                                  .map(_ -> canary);
            }

            @SuppressWarnings("unchecked")
            private Promise<Unit> updateSliceTargetVersion(ArtifactBase artifactBase, Version version) {
                var key = SliceTargetKey.sliceTargetKey(artifactBase);
                var existing = kvStore.get(key)
                                      .filter(v -> v instanceof SliceTargetValue)
                                      .map(v -> (SliceTargetValue) v);
                var instances = existing.map(SliceTargetValue::targetInstances)
                                        .or(1);
                var minInstances = existing.map(SliceTargetValue::effectiveMinInstances)
                                           .or(instances);
                var value = new SliceTargetValue(version,
                                                 instances,
                                                 minInstances,
                                                 Option.none(),
                                                 "CORE_ONLY",
                                                 System.currentTimeMillis());
                var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);
                return clusterNode.<Unit> apply(List.of(command))
                                  .timeout(kvOperationTimeout)
                                  .mapToUnit();
            }

            @SuppressWarnings("unchecked")
            private Promise<Unit> removeRoutingKey(CanaryDeployment canary) {
                var routingKey = new AetherKey.VersionRoutingKey(canary.artifactBase());
                var routingCmd = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Remove<>(routingKey);
                return clusterNode.<Unit> apply(List.of(routingCmd))
                                  .timeout(kvOperationTimeout)
                                  .mapToUnit();
            }

            // --- State restoration ---
            @SuppressWarnings("JBCT-RET-01") // Side-effect helper — void inherent
            private void restoreState() {
                int beforeCount = canaries.size();
                kvStore.forEach(CanaryDeploymentKey.class,
                                CanaryDeploymentValue.class,
                                (key, value) -> restoreCanary(value));
                int restoredCount = canaries.size() - beforeCount;
                if (restoredCount > 0) {
                    log.info("Restored {} canary deployments from KV-Store", restoredCount);
                }
            }

            @SuppressWarnings({"JBCT-VO-02", "JBCT-RET-01"}) // Side-effect helper — void inherent
            private void restoreCanary(CanaryDeploymentValue cdv) {
                var state = CanaryState.valueOf(cdv.state());
                var routing = new VersionRouting(cdv.newWeight(), cdv.oldWeight());
                var thresholds = new HealthThresholds(cdv.maxErrorRate(),
                                                      cdv.maxLatencyMs(),
                                                      cdv.requireManualApproval());
                var analysisMode = CanaryAnalysisConfig.ComparisonMode.valueOf(cdv.analysisMode());
                var analysisConfig = new CanaryAnalysisConfig(analysisMode, cdv.relativeThresholdPercent());
                var cleanupPolicy = CleanupPolicy.valueOf(cdv.cleanupPolicy());
                var stages = deserializeStages(cdv.stagesJson());
                var blueprintId = cdv.blueprintId()
                                     .isEmpty()
                                  ? Option.<String>none()
                                  : Option.some(cdv.blueprintId());
                var artifacts = deserializeArtifacts(cdv.artifactsJson());
                var canary = new CanaryDeployment(cdv.canaryId(),
                                                  cdv.artifactBase(),
                                                  cdv.oldVersion(),
                                                  cdv.newVersion(),
                                                  state,
                                                  stages,
                                                  cdv.currentStageIndex(),
                                                  cdv.stageEnteredAt(),
                                                  thresholds,
                                                  analysisConfig,
                                                  cdv.newInstances(),
                                                  cleanupPolicy,
                                                  routing,
                                                  blueprintId,
                                                  artifacts,
                                                  cdv.createdAt(),
                                                  cdv.updatedAt());
                canaries.put(canary.canaryId(), canary);
            }

            // --- Metrics collection ---
            private CanaryHealthComparison collectHealthComparison(CanaryDeployment canary) {
                var snapshots = metricsCollector.snapshot();
                var oldArtifact = canary.artifactBase()
                                        .withVersion(canary.oldVersion());
                var newArtifact = canary.artifactBase()
                                        .withVersion(canary.newVersion());
                var baselineMetrics = accumulateMetricsFor(snapshots, oldArtifact)
                .toVersionMetrics(canary.oldVersion());
                var canaryMetrics = accumulateMetricsFor(snapshots, newArtifact).toVersionMetrics(canary.newVersion());
                return CanaryHealthComparison.evaluate(canary.canaryId(),
                                                       canary.oldVersion(),
                                                       canary.newVersion(),
                                                       baselineMetrics,
                                                       canaryMetrics,
                                                       canary.thresholds(),
                                                       canary.analysisConfig());
            }

            private AccumulatedMetrics accumulateMetricsFor(List<InvocationMetricsCollector.MethodSnapshot> snapshots,
                                                            Artifact artifact) {
                return snapshots.stream()
                                .filter(snapshot -> snapshot.artifact()
                                                            .equals(artifact))
                                .reduce(AccumulatedMetrics.empty(),
                                        AccumulatedMetrics::accumulate,
                                        (a, _) -> a);
            }

            // --- Housekeeping ---
            @SuppressWarnings("JBCT-RET-01") // Side-effect helper — void inherent
            private void pruneTerminalCanaries() {
                var cutoff = System.currentTimeMillis() - terminalRetentionMs;
                var pruned = canaries.entrySet()
                                     .removeIf(entry -> entry.getValue()
                                                             .isTerminal() && entry.getValue()
                                                                                   .updatedAt() < cutoff);
                if (pruned) {
                    log.debug("Pruned terminal canary deployments older than retention period");
                }
            }

            // --- Serialization helpers ---
            private static String serializeStages(List<CanaryStage> stages) {
                return stages.stream()
                             .map(s -> s.trafficPercent() + ":" + s.observationMinutes())
                             .collect(Collectors.joining(","));
            }

            private static List<CanaryStage> deserializeStages(String stagesJson) {
                // Serializer guarantees non-null (writes "" for empty)
                if (stagesJson.isEmpty()) {
                    return List.of();
                }
                return Arrays.stream(stagesJson.split(","))
                             .map(CanaryDeploymentManager::parseStageEntry)
                             .toList();
            }

            private static String serializeArtifacts(List<ArtifactBase> artifacts) {
                return artifacts.stream()
                                .map(ArtifactBase::asString)
                                .collect(Collectors.joining(","));
            }

            private static List<ArtifactBase> deserializeArtifacts(String artifactsJson) {
                // Serializer guarantees non-null (writes "" for empty)
                if (artifactsJson.isEmpty()) {
                    return List.of();
                }
                return Arrays.stream(artifactsJson.split(","))
                             .map(ArtifactBase::artifactBase)
                             .flatMap(result -> result.option()
                                                      .stream())
                             .toList();
            }
        }
        return new canaryDeploymentManager(clusterNode,
                                           kvStore,
                                           metricsCollector,
                                           kvOperationTimeout,
                                           terminalRetentionMs,
                                           evaluationInterval,
                                           new ConcurrentHashMap<>(),
                                           CancellableTask.cancellableTask());
    }

    /// Parse a single stage entry from serialized format.
    @SuppressWarnings("JBCT-VO-02")
    private static CanaryStage parseStageEntry(String entry) {
        var parts = entry.split(":");
        return new CanaryStage(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    /// Helper record for accumulating metrics across snapshots.
    record AccumulatedMetrics(long requests, long errors, long totalLatencyMs, long maxP99Ms) {
        static AccumulatedMetrics empty() {
            return new AccumulatedMetrics(0, 0, 0, 0);
        }

        AccumulatedMetrics accumulate(InvocationMetricsCollector.MethodSnapshot snapshot) {
            var metrics = snapshot.metrics();
            long p99Ms = metrics.estimatePercentileNs(99) / 1_000_000;
            return new AccumulatedMetrics(requests + metrics.count(),
                                          errors + metrics.failureCount(),
                                          totalLatencyMs + metrics.totalDurationNs() / 1_000_000,
                                          Math.max(maxP99Ms, p99Ms));
        }

        CanaryHealthComparison.VersionMetrics toVersionMetrics(Version version) {
            double errorRate = requests > 0
                               ? (double) errors / requests
                               : 0.0;
            long avgLatency = requests > 0
                              ? totalLatencyMs / requests
                              : 0;
            return CanaryHealthComparison.VersionMetrics.versionMetrics(version,
                                                                        requests,
                                                                        errors,
                                                                        errorRate,
                                                                        maxP99Ms,
                                                                        avgLatency);
        }
    }
}
