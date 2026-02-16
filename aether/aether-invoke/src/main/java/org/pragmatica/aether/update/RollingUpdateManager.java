package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.RollingUpdateKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.RollingUpdateValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.utility.KSUID;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Manages rolling update operations across the cluster.
///
///
/// Implements a two-stage rolling update model:
/// <ol>
///   - **Deploy stage**: New version instances deployed with 0% traffic
///   - **Route stage**: Traffic gradually shifted to new version
/// </ol>
///
///
/// Rolling updates are orchestrated by the leader node via consensus.
/// All state is stored in the KV-Store for persistence and visibility.
///
///
/// Usage:
/// ```{@code
/// // Start rolling update (deploys new version with 0% traffic)
/// manager.startUpdate(artifactBase, newVersion, 3, HealthThresholds.DEFAULT, CleanupPolicy.GRACE_PERIOD)
///     .await()
///     .onSuccess(update -> {
///         // Gradually shift traffic
///         manager.adjustRouting(update.updateId(), VersionRouting.versionRouting("1:3")).await();
///         manager.adjustRouting(update.updateId(), VersionRouting.versionRouting("1:1")).await();
///         manager.adjustRouting(update.updateId(), VersionRouting.versionRouting("1:0")).await();
///
///         // Complete and cleanup
///         manager.completeUpdate(update.updateId()).await();
///     });
/// }```
public interface RollingUpdateManager {
    /// Starts a new rolling update.
    ///
    ///
    /// This initiates the deploy stage:
    ///
    ///   - Creates update record in KV-Store
    ///   - Deploys new version instances (0% traffic)
    ///   - Waits for new instances to become healthy
    ///   - Transitions to DEPLOYED state
    ///
    ///
    /// @param artifactBase the artifact to update (version-agnostic)
    /// @param newVersion the new version to deploy
    /// @param instances number of new version instances
    /// @param thresholds health thresholds for auto-progression
    /// @param cleanupPolicy how to handle old version cleanup
    /// @return the created rolling update
    Promise<RollingUpdate> startUpdate(ArtifactBase artifactBase,
                                       Version newVersion,
                                       int instances,
                                       HealthThresholds thresholds,
                                       CleanupPolicy cleanupPolicy);

    /// Adjusts traffic routing between versions.
    ///
    ///
    /// Can only be called when update is in DEPLOYED or ROUTING state.
    /// The routing ratio is scaled to available instances.
    ///
    /// @param updateId the update to adjust
    /// @param newRouting the new routing configuration
    /// @return updated rolling update
    Promise<RollingUpdate> adjustRouting(String updateId, VersionRouting newRouting);

    /// Completes the rolling update.
    ///
    ///
    /// Should only be called when all traffic is routed to new version (1:0).
    /// Initiates cleanup of old version according to cleanup policy.
    ///
    /// @param updateId the update to complete
    /// @return updated rolling update
    Promise<RollingUpdate> completeUpdate(String updateId);

    /// Rolls back the update to the old version.
    ///
    ///
    /// Can be called at any non-terminal state. Routes all traffic back to
    /// old version and removes new version instances.
    ///
    /// @param updateId the update to rollback
    /// @return updated rolling update
    Promise<RollingUpdate> rollback(String updateId);

    /// Gets the current state of a rolling update.
    ///
    /// @param updateId the update to query
    /// @return the update if found
    Option<RollingUpdate> getUpdate(String updateId);

    /// Gets the active update for an artifact (if any).
    ///
    /// @param artifactBase the artifact to query
    /// @return the active update if found
    Option<RollingUpdate> getActiveUpdate(ArtifactBase artifactBase);

    /// Lists all active (non-terminal) rolling updates.
    ///
    /// @return list of active updates
    List<RollingUpdate> activeUpdates();

    /// Lists all rolling updates (including completed ones).
    ///
    /// @return list of all updates
    List<RollingUpdate> allUpdates();

    /// Gets health metrics for an update's versions.
    ///
    /// @param updateId the update to query
    /// @return health metrics for old and new versions
    Promise<VersionHealthMetrics> getHealthMetrics(String updateId);

    /// Health metrics for comparing old and new version performance.
    record VersionHealthMetrics(String updateId,
                                VersionMetrics oldVersion,
                                VersionMetrics newVersion,
                                long collectedAt) {
        public boolean isNewVersionHealthy(HealthThresholds thresholds) {
            return thresholds.isHealthy(newVersion.errorRate, newVersion.p99LatencyMs);
        }
    }

    /// Metrics for a single version.
    record VersionMetrics(Version version,
                          long requestCount,
                          long errorCount,
                          double errorRate,
                          long p99LatencyMs,
                          long avgLatencyMs) {}

    /// Handle leader change notifications.
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01") // MessageReceiver callback — void required by messaging framework
    void onLeaderChange(LeaderChange leaderChange);

    /// Factory method following JBCT naming convention.
    static RollingUpdateManager rollingUpdateManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                     KVStore<AetherKey, AetherValue> kvStore,
                                                     InvocationMetricsCollector metricsCollector) {
        record rollingUpdateManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                    KVStore<AetherKey, AetherValue> kvStore,
                                    InvocationMetricsCollector metricsCollector,
                                    Map<String, RollingUpdate> updates) implements RollingUpdateManager {
            private static final Logger log = LoggerFactory.getLogger(RollingUpdateManager.class);
            private static final TimeSpan KV_OPERATION_TIMEOUT = TimeSpan.timeSpan(30)
                                                                        .seconds();

            /// Retention period for terminal-state updates before pruning from in-memory map.
            /// Updates in COMPLETED, ROLLED_BACK, or FAILED state are pruned after this period.
            private static final long TERMINAL_RETENTION_MS = TimeUnit.HOURS.toMillis(1);

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onLeaderChange(LeaderChange leaderChange) {
                if (leaderChange.localNodeIsLeader()) {
                    log.info("Rolling update manager active (leader)");
                    restoreState();
                } else {
                    log.info("Rolling update manager passive (follower)");
                }
            }

            private void restoreState() {
                int beforeCount = updates.size();
                kvStore.forEach(RollingUpdateKey.class, RollingUpdateValue.class, (key, value) -> restoreUpdate(value));
                int restoredCount = updates.size() - beforeCount;
                if (restoredCount > 0) {
                    log.info("Restored {} rolling updates from KV-Store", restoredCount);
                }
            }

            private void restoreUpdate(RollingUpdateValue ruv) {
                var state = RollingUpdateState.valueOf(ruv.state());
                var routing = new VersionRouting(ruv.newWeight(), ruv.oldWeight());
                var thresholds = new HealthThresholds(ruv.maxErrorRate(),
                                                      ruv.maxLatencyMs(),
                                                      ruv.requireManualApproval());
                var cleanupPolicy = CleanupPolicy.valueOf(ruv.cleanupPolicy());
                var update = new RollingUpdate(ruv.updateId(),
                                               ruv.artifactBase(),
                                               ruv.oldVersion(),
                                               ruv.newVersion(),
                                               state,
                                               routing,
                                               thresholds,
                                               cleanupPolicy,
                                               ruv.newInstances(),
                                               ruv.createdAt(),
                                               ruv.updatedAt());
                updates.put(update.updateId(), update);
            }

            private Promise<Unit> requireLeader() {
                // Query LeaderManager directly to avoid race condition with callback-based state
                if (!clusterNode.leaderManager()
                                .isLeader()) {
                    return RollingUpdateError.NotLeader.INSTANCE.promise();
                }
                return Promise.success(Unit.unit());
            }

            @Override
            public Promise<RollingUpdate> startUpdate(ArtifactBase artifactBase,
                                                      Version newVersion,
                                                      int instances,
                                                      HealthThresholds thresholds,
                                                      CleanupPolicy cleanupPolicy) {
                return requireLeader().flatMap(_ -> checkNoActiveUpdate(artifactBase))
                                    .flatMap(_ -> findCurrentVersion(artifactBase))
                                    .flatMap(oldVersion -> createAndDeployUpdate(artifactBase,
                                                                                 oldVersion,
                                                                                 newVersion,
                                                                                 instances,
                                                                                 thresholds,
                                                                                 cleanupPolicy));
            }

            private Promise<Unit> checkNoActiveUpdate(ArtifactBase artifactBase) {
                return getActiveUpdate(artifactBase).isPresent()
                       ? RollingUpdateError.UpdateAlreadyExists.updateAlreadyExists(artifactBase)
                                           .promise()
                       : Promise.success(Unit.unit());
            }

            private Promise<RollingUpdate> createAndDeployUpdate(ArtifactBase artifactBase,
                                                                 Version oldVersion,
                                                                 Version newVersion,
                                                                 int instances,
                                                                 HealthThresholds thresholds,
                                                                 CleanupPolicy cleanupPolicy) {
                var updateId = KSUID.ksuid()
                                    .encoded();
                var update = RollingUpdate.rollingUpdate(updateId,
                                                         artifactBase,
                                                         oldVersion,
                                                         newVersion,
                                                         instances,
                                                         thresholds,
                                                         cleanupPolicy);
                log.info("Starting rolling update {} for {} from {} to {}",
                         updateId,
                         artifactBase,
                         oldVersion,
                         newVersion);
                updates.put(updateId, update);
                return persistAndTransition(update, RollingUpdateState.DEPLOYING).flatMap(u -> deployNewVersion(u,
                                                                                                                instances));
            }

            @Override
            public Promise<RollingUpdate> adjustRouting(String updateId, VersionRouting newRouting) {
                return requireLeader().flatMap(_ -> findUpdate(updateId))
                                    .flatMap(update -> validateRoutingAdjustment(update, newRouting));
            }

            private Promise<RollingUpdate> findUpdate(String updateId) {
                return Option.option(updates.get(updateId))
                             .toResult(RollingUpdateError.UpdateNotFound.updateNotFound(updateId))
                             .async();
            }

            private Promise<RollingUpdate> validateRoutingAdjustment(RollingUpdate update, VersionRouting newRouting) {
                if (!update.state()
                           .allowsNewVersionTraffic() && update.state() != RollingUpdateState.DEPLOYED) {
                    return RollingUpdateError.InvalidStateTransition.invalidStateTransition(update.state(),
                                                                                            RollingUpdateState.ROUTING)
                                             .promise();
                }
                log.info("Adjusting routing for {} to {}", update.updateId(), newRouting);
                return applyRoutingChange(update, newRouting);
            }

            private Promise<RollingUpdate> applyRoutingChange(RollingUpdate update, VersionRouting newRouting) {
                var withRouting = update.withRouting(newRouting);
                if (update.state() == RollingUpdateState.DEPLOYED) {
                    return transitionToRouting(withRouting);
                }
                updates.put(update.updateId(), withRouting);
                return persistRouting(withRouting).map(_ -> withRouting);
            }

            private Promise<RollingUpdate> transitionToRouting(RollingUpdate update) {
                return update.transitionTo(RollingUpdateState.ROUTING)
                             .async()
                             .flatMap(this::cacheAndPersistRouting);
            }

            private Promise<RollingUpdate> cacheAndPersistRouting(RollingUpdate transitioned) {
                updates.put(transitioned.updateId(), transitioned);
                return persistRouting(transitioned).map(_ -> transitioned);
            }

            @Override
            public Promise<RollingUpdate> completeUpdate(String updateId) {
                return requireLeader().flatMap(_ -> findUpdate(updateId))
                                    .flatMap(this::validateAndComplete);
            }

            private Promise<RollingUpdate> validateAndComplete(RollingUpdate update) {
                if (!update.routing()
                           .isAllNew()) {
                    return RollingUpdateError.InvalidStateTransition.invalidStateTransition(update.state(),
                                                                                            RollingUpdateState.COMPLETING)
                                             .promise();
                }
                log.info("Completing rolling update {}", update.updateId());
                return persistAndTransition(update, RollingUpdateState.COMPLETING).flatMap(this::cleanupOldVersion);
            }

            @Override
            public Promise<RollingUpdate> rollback(String updateId) {
                return requireLeader().flatMap(_ -> findUpdate(updateId))
                                    .flatMap(this::validateAndRollback);
            }

            private Promise<RollingUpdate> validateAndRollback(RollingUpdate update) {
                if (update.isTerminal()) {
                    return RollingUpdateError.InvalidStateTransition.invalidStateTransition(update.state(),
                                                                                            RollingUpdateState.ROLLING_BACK)
                                             .promise();
                }
                log.info("Rolling back update {}", update.updateId());
                return persistAndTransition(update, RollingUpdateState.ROLLING_BACK).flatMap(this::removeNewVersion);
            }

            @Override
            public Option<RollingUpdate> getUpdate(String updateId) {
                return Option.option(updates.get(updateId));
            }

            @Override
            public Option<RollingUpdate> getActiveUpdate(ArtifactBase artifactBase) {
                return Option.from(updates.values()
                                          .stream()
                                          .filter(u -> u.artifactBase()
                                                        .equals(artifactBase) && u.isActive())
                                          .findFirst());
            }

            @Override
            public List<RollingUpdate> activeUpdates() {
                return updates.values()
                              .stream()
                              .filter(RollingUpdate::isActive)
                              .toList();
            }

            @Override
            public List<RollingUpdate> allUpdates() {
                return List.copyOf(updates.values());
            }

            @Override
            public Promise<VersionHealthMetrics> getHealthMetrics(String updateId) {
                return Option.option(updates.get(updateId))
                             .toResult(RollingUpdateError.UpdateNotFound.updateNotFound(updateId))
                             .async()
                             .map(this::collectHealthMetrics);
            }

            private VersionHealthMetrics collectHealthMetrics(RollingUpdate update) {
                var snapshots = metricsCollector.snapshot();
                var oldArtifact = update.artifactBase()
                                        .withVersion(update.oldVersion());
                var newArtifact = update.artifactBase()
                                        .withVersion(update.newVersion());
                var oldAccumulated = accumulateMetricsFor(snapshots, oldArtifact);
                var newAccumulated = accumulateMetricsFor(snapshots, newArtifact);
                return new VersionHealthMetrics(update.updateId(),
                                                oldAccumulated.toVersionMetrics(update.oldVersion()),
                                                newAccumulated.toVersionMetrics(update.newVersion()),
                                                System.currentTimeMillis());
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

            private Promise<Version> findCurrentVersion(ArtifactBase artifactBase) {
                var key = SliceTargetKey.sliceTargetKey(artifactBase);
                return kvStore.get(key)
                              .map(value -> ((SliceTargetValue) value).currentVersion())
                              .toResult(RollingUpdateError.InitialDeployment.initialDeployment(artifactBase))
                              .async();
            }

            @SuppressWarnings("unchecked")
            private Promise<RollingUpdate> persistAndTransition(RollingUpdate update, RollingUpdateState newState) {
                return update.transitionTo(newState)
                             .async()
                             .flatMap(transitioned -> cacheAndPersistUpdate(update.updateId(),
                                                                            transitioned));
            }

            @SuppressWarnings("unchecked")
            private Promise<RollingUpdate> cacheAndPersistUpdate(String updateId, RollingUpdate transitioned) {
                updates.put(updateId, transitioned);
                // Prune terminal-state updates older than retention period
                if (transitioned.isTerminal()) {
                    pruneTerminalUpdates();
                }
                var key = new AetherKey.RollingUpdateKey(updateId);
                var value = new AetherValue.RollingUpdateValue(transitioned.updateId(),
                                                               transitioned.artifactBase(),
                                                               transitioned.oldVersion(),
                                                               transitioned.newVersion(),
                                                               transitioned.state()
                                                                           .name(),
                                                               transitioned.routing()
                                                                           .newWeight(),
                                                               transitioned.routing()
                                                                           .oldWeight(),
                                                               transitioned.newInstances(),
                                                               transitioned.thresholds()
                                                                           .maxErrorRate(),
                                                               transitioned.thresholds()
                                                                           .maxLatencyMs(),
                                                               transitioned.thresholds()
                                                                           .requireManualApproval(),
                                                               transitioned.cleanupPolicy()
                                                                           .name(),
                                                               transitioned.createdAt(),
                                                               System.currentTimeMillis());
                var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);
                return clusterNode.<Unit> apply(List.of(command))
                                  .timeout(KV_OPERATION_TIMEOUT)
                                  .map(_ -> transitioned);
            }

            /// Prune terminal-state updates that have exceeded the retention period.
            /// This prevents unbounded growth of the in-memory updates map.
            private void pruneTerminalUpdates() {
                var cutoff = System.currentTimeMillis() - TERMINAL_RETENTION_MS;
                var pruned = updates.entrySet()
                                    .removeIf(entry -> {
                                                  var update = entry.getValue();
                                                  return update.isTerminal() && update.updatedAt() < cutoff;
                                              });
                if (pruned) {
                    log.debug("Pruned terminal rolling updates older than retention period");
                }
            }

            @SuppressWarnings("unchecked")
            private Promise<RollingUpdate> persistRouting(RollingUpdate update) {
                var key = new AetherKey.VersionRoutingKey(update.artifactBase());
                var value = new AetherValue.VersionRoutingValue(update.oldVersion(),
                                                                update.newVersion(),
                                                                update.routing()
                                                                      .newWeight(),
                                                                update.routing()
                                                                      .oldWeight(),
                                                                System.currentTimeMillis());
                var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);
                return clusterNode.<Unit> apply(List.of(command))
                                  .timeout(KV_OPERATION_TIMEOUT)
                                  .map(_ -> update);
            }

            @SuppressWarnings("unchecked")
            private Promise<RollingUpdate> deployNewVersion(RollingUpdate update, int instances) {
                var artifactBase = update.artifactBase();
                // Build routing command
                var routingKey = new AetherKey.VersionRoutingKey(update.artifactBase());
                var routingValue = new AetherValue.VersionRoutingValue(update.oldVersion(),
                                                                       update.newVersion(),
                                                                       update.routing()
                                                                             .newWeight(),
                                                                       update.routing()
                                                                             .oldWeight(),
                                                                       System.currentTimeMillis());
                var routingCmd = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(routingKey, routingValue);
                // Build target command
                var targetKey = AetherKey.SliceTargetKey.sliceTargetKey(artifactBase);
                var targetValue = AetherValue.SliceTargetValue.sliceTargetValue(update.newVersion(), instances);
                var targetCmd = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(targetKey, targetValue);
                log.info("Deploying {} instances of {} (version {})", instances, artifactBase, update.newVersion());
                // Send routing AND target in single batch to ensure atomic processing
                // Routing must be applied before target so old version is preserved
                return clusterNode.<Unit> apply(List.of(routingCmd, targetCmd))
                                  .timeout(KV_OPERATION_TIMEOUT)
                                  .flatMap(_ -> persistAndTransition(update, RollingUpdateState.DEPLOYED));
            }

            private Promise<RollingUpdate> cleanupOldVersion(RollingUpdate update) {
                if (update.cleanupPolicy()
                          .isManual()) {
                    return cleanupManualPolicy(update);
                }
                return cleanupAutomaticPolicy(update);
            }

            private Promise<RollingUpdate> cleanupManualPolicy(RollingUpdate update) {
                log.info("Manual cleanup policy - old version {} kept for manual removal",
                         update.artifactBase()
                               .withVersion(update.oldVersion()));
                return removeRoutingKey(update).flatMap(_ -> persistAndTransition(update, RollingUpdateState.COMPLETED));
            }

            private Promise<RollingUpdate> cleanupAutomaticPolicy(RollingUpdate update) {
                log.info("Cleanup: finalizing slice target for {} to version {}",
                         update.artifactBase(),
                         update.newVersion());
                return removeRoutingKey(update).flatMap(_ -> persistAndTransition(update, RollingUpdateState.COMPLETED));
            }

            private Promise<RollingUpdate> removeNewVersion(RollingUpdate update) {
                log.info("Rolling back slice target for {} to version {}", update.artifactBase(), update.oldVersion());
                return updateSliceTargetVersion(update.artifactBase(),
                                                update.oldVersion()).flatMap(_ -> removeRoutingKey(update))
                                               .flatMap(_ -> persistAndTransition(update, RollingUpdateState.ROLLED_BACK));
            }

            @SuppressWarnings("unchecked")
            private Promise<Unit> updateSliceTargetVersion(ArtifactBase artifactBase, Version version) {
                var key = SliceTargetKey.sliceTargetKey(artifactBase);
                var instances = kvStore.get(key)
                                       .map(value -> ((SliceTargetValue) value).targetInstances())
                                       .or(1);
                var value = SliceTargetValue.sliceTargetValue(version, instances);
                var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);
                return clusterNode.<Unit> apply(List.of(command))
                                  .timeout(KV_OPERATION_TIMEOUT)
                                  .mapToUnit();
            }

            @SuppressWarnings("unchecked")
            private Promise<Unit> removeRoutingKey(RollingUpdate update) {
                var routingKey = new AetherKey.VersionRoutingKey(update.artifactBase());
                var routingCmd = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Remove<>(routingKey);
                return clusterNode.<Unit> apply(List.of(routingCmd))
                                  .timeout(KV_OPERATION_TIMEOUT)
                                  .mapToUnit();
            }
        }
        return new rollingUpdateManager(clusterNode, kvStore, metricsCollector, new ConcurrentHashMap<>());
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

        VersionMetrics toVersionMetrics(Version version) {
            double errorRate = requests > 0
                               ? (double) errors / requests
                               : 0.0;
            long avgLatency = requests > 0
                              ? totalLatencyMs / requests
                              : 0;
            return new VersionMetrics(version, requests, errors, errorRate, maxP99Ms, avgLatency);
        }
    }
}
