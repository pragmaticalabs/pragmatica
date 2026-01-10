package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.utility.KSUID;
import org.pragmatica.lang.io.TimeSpan;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link RollingUpdateManager}.
 *
 * <p>Orchestrates rolling updates using KV-Store for persistence and consensus for coordination.
 */
public final class RollingUpdateManagerImpl implements RollingUpdateManager {
    private static final Logger log = LoggerFactory.getLogger(RollingUpdateManagerImpl.class);
    private static final TimeSpan KV_OPERATION_TIMEOUT = TimeSpan.timeSpan(30)
                                                                .seconds();

    private final RabiaNode<KVCommand<AetherKey>> clusterNode;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final InvocationMetricsCollector metricsCollector;

    // Local cache of rolling updates (authoritative source is KV-Store)
    private final Map<String, RollingUpdate> updates = new ConcurrentHashMap<>();

    // Leader tracking for write operations
    private volatile boolean isLeader = false;

    private RollingUpdateManagerImpl(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                     KVStore<AetherKey, AetherValue> kvStore,
                                     InvocationMetricsCollector metricsCollector) {
        this.clusterNode = clusterNode;
        this.kvStore = kvStore;
        this.metricsCollector = metricsCollector;
    }

    /**
     * Handle leader change notifications.
     */
    @MessageReceiver
    public void onLeaderChange(LeaderChange leaderChange) {
        isLeader = leaderChange.localNodeIsLeader();
        if (isLeader) {
            log.info("Rolling update manager active (leader)");
            restoreState();
        } else {
            log.info("Rolling update manager passive (follower)");
        }
    }

    /**
     * Restore rolling update state from KV-Store on startup or leader change.
     */
    private void restoreState() {
        var snapshot = kvStore.snapshot();
        int restoredCount = 0;
        for (var entry : snapshot.entrySet()) {
            if (entry.getKey() instanceof AetherKey.RollingUpdateKey ruk && entry.getValue() instanceof AetherValue.RollingUpdateValue ruv) {
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
                restoredCount++;
            }
        }
        if (restoredCount > 0) {
            log.info("Restored {} rolling updates from KV-Store", restoredCount);
        }
    }

    private Promise<Unit> requireLeader() {
        if (!isLeader) {
            return RollingUpdateError.NotLeader.INSTANCE.promise();
        }
        return Promise.success(Unit.unit());
    }

    /**
     * Factory method following JBCT naming convention.
     */
    public static RollingUpdateManagerImpl rollingUpdateManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                KVStore<AetherKey, AetherValue> kvStore,
                                                                InvocationMetricsCollector metricsCollector) {
        return new RollingUpdateManagerImpl(clusterNode, kvStore, metricsCollector);
    }

    @Override
    public Promise<RollingUpdate> startUpdate(ArtifactBase artifactBase,
                                              Version newVersion,
                                              int instances,
                                              HealthThresholds thresholds,
                                              CleanupPolicy cleanupPolicy) {
        return requireLeader()
                            .flatMap(_ -> checkNoActiveUpdate(artifactBase))
                            .flatMap(_ -> findCurrentVersion(artifactBase))
                            .flatMap(oldVersion -> createAndDeployUpdate(artifactBase,
                                                                         oldVersion,
                                                                         newVersion,
                                                                         instances,
                                                                         thresholds,
                                                                         cleanupPolicy));
    }

    private Promise<Unit> checkNoActiveUpdate(ArtifactBase artifactBase) {
        return getActiveUpdate(artifactBase)
                              .isPresent()
               ? new RollingUpdateError.UpdateAlreadyExists(artifactBase).promise()
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
        log.info("Starting rolling update {} for {} from {} to {}", updateId, artifactBase, oldVersion, newVersion);
        updates.put(updateId, update);
        return persistAndTransition(update, RollingUpdateState.DEPLOYING)
                                   .flatMap(u -> deployNewVersion(u, instances));
    }

    @Override
    public Promise<RollingUpdate> adjustRouting(String updateId, VersionRouting newRouting) {
        return requireLeader()
                            .flatMap(_ -> findUpdate(updateId))
                            .flatMap(update -> validateRoutingAdjustment(update, newRouting));
    }

    private Promise<RollingUpdate> findUpdate(String updateId) {
        return Option.option(updates.get(updateId))
                     .toResult(new RollingUpdateError.UpdateNotFound(updateId))
                     .async();
    }

    private Promise<RollingUpdate> validateRoutingAdjustment(RollingUpdate update, VersionRouting newRouting) {
        if (!update.state()
                   .allowsNewVersionTraffic() && update.state() != RollingUpdateState.DEPLOYED) {
            return new RollingUpdateError.InvalidStateTransition(update.state(), RollingUpdateState.ROUTING).promise();
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
        return persistRouting(withRouting)
                             .map(_ -> withRouting);
    }

    private Promise<RollingUpdate> transitionToRouting(RollingUpdate update) {
        return update.transitionTo(RollingUpdateState.ROUTING)
                     .async()
                     .flatMap(this::cacheAndPersistRouting);
    }

    private Promise<RollingUpdate> cacheAndPersistRouting(RollingUpdate transitioned) {
        updates.put(transitioned.updateId(), transitioned);
        return persistRouting(transitioned)
                             .map(_ -> transitioned);
    }

    @Override
    public Promise<RollingUpdate> completeUpdate(String updateId) {
        return requireLeader()
                            .flatMap(_ -> findUpdate(updateId))
                            .flatMap(this::validateAndComplete);
    }

    private Promise<RollingUpdate> validateAndComplete(RollingUpdate update) {
        if (!update.routing()
                   .isAllNew()) {
            return new RollingUpdateError.InvalidStateTransition(update.state(), RollingUpdateState.COMPLETING).promise();
        }
        log.info("Completing rolling update {}", update.updateId());
        return persistAndTransition(update, RollingUpdateState.COMPLETING)
                                   .flatMap(this::cleanupOldVersion);
    }

    @Override
    public Promise<RollingUpdate> rollback(String updateId) {
        return requireLeader()
                            .flatMap(_ -> findUpdate(updateId))
                            .flatMap(this::validateAndRollback);
    }

    private Promise<RollingUpdate> validateAndRollback(RollingUpdate update) {
        if (update.isTerminal()) {
            return new RollingUpdateError.InvalidStateTransition(update.state(), RollingUpdateState.ROLLING_BACK).promise();
        }
        log.info("Rolling back update {}", update.updateId());
        return persistAndTransition(update, RollingUpdateState.ROLLING_BACK)
                                   .flatMap(this::removeNewVersion);
    }

    @Override
    public Option<RollingUpdate> getUpdate(String updateId) {
        return Option.option(updates.get(updateId));
    }

    @Override
    public Option<RollingUpdate> getActiveUpdate(ArtifactBase artifactBase) {
        return Option.option(updates.values()
                                    .stream()
                                    .filter(u -> u.artifactBase()
                                                  .equals(artifactBase) && u.isActive())
                                    .findFirst()
                                    .orElse(null));
    }

    @Override
    public List<RollingUpdate> activeUpdates() {
        return updates.values()
                      .stream()
                      .filter(RollingUpdate::isActive)
                      .collect(Collectors.toList());
    }

    @Override
    public List<RollingUpdate> allUpdates() {
        return List.copyOf(updates.values());
    }

    @Override
    public Promise<VersionHealthMetrics> getHealthMetrics(String updateId) {
        return Option.option(updates.get(updateId))
                     .toResult(new RollingUpdateError.UpdateNotFound(updateId))
                     .async()
                     .map(this::collectHealthMetrics);
    }

    private record AccumulatedMetrics(long requests, long errors, long totalLatencyMs, long maxP99Ms) {
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
                                                    org.pragmatica.aether.artifact.Artifact artifact) {
        return snapshots.stream()
                        .filter(snapshot -> snapshot.artifact()
                                                    .equals(artifact))
                        .reduce(AccumulatedMetrics.empty(),
                                AccumulatedMetrics::accumulate,
                                (a, b) -> a);
    }

    // ===== Private helpers =====
    private Promise<Version> findCurrentVersion(ArtifactBase artifactBase) {
        // Look for existing blueprint in KV-Store by iterating over snapshot
        var snapshot = kvStore.snapshot();
        for (var entry : snapshot.entrySet()) {
            if (entry.getKey() instanceof AetherKey.BlueprintKey bk && artifactBase.matches(bk.artifact())) {
                return Promise.success(bk.artifact()
                                         .version());
            }
        }
        // Create a fallback version for artifacts not yet deployed (initial deployment)
        return Version.version("0.0.0")
                      .mapError(_ -> new RollingUpdateError.VersionNotFound(artifactBase, null))
                      .async();
    }

    @SuppressWarnings("unchecked")
    private Promise<RollingUpdate> persistAndTransition(RollingUpdate update,
                                                        RollingUpdateState newState) {
        return update.transitionTo(newState)
                     .async()
                     .flatMap(transitioned -> cacheAndPersistUpdate(update.updateId(),
                                                                    transitioned));
    }

    @SuppressWarnings("unchecked")
    private Promise<RollingUpdate> cacheAndPersistUpdate(String updateId, RollingUpdate transitioned) {
        updates.put(updateId, transitioned);
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
        var command = (KVCommand<AetherKey>)(KVCommand< ? >) new KVCommand.Put<>(key, value);
        return clusterNode.<Unit> apply(List.of(command))
                          .timeout(KV_OPERATION_TIMEOUT)
                          .map(_ -> transitioned);
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
        var command = (KVCommand<AetherKey>)(KVCommand< ? >) new KVCommand.Put<>(key, value);
        return clusterNode.<Unit> apply(List.of(command))
                          .timeout(KV_OPERATION_TIMEOUT)
                          .map(_ -> update);
    }

    @SuppressWarnings("unchecked")
    private Promise<RollingUpdate> deployNewVersion(RollingUpdate update, int instances) {
        var newArtifact = update.artifactBase()
                                .withVersion(update.newVersion());
        var key = new AetherKey.BlueprintKey(newArtifact);
        var value = new AetherValue.BlueprintValue(instances);
        var command = (KVCommand<AetherKey>)(KVCommand< ? >) new KVCommand.Put<>(key, value);
        log.info("Deploying {} instances of {}", instances, newArtifact);
        return clusterNode.<Unit> apply(List.of(command))
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
        return removeRoutingKey(update)
                               .flatMap(_ -> persistAndTransition(update, RollingUpdateState.COMPLETED));
    }

    private Promise<RollingUpdate> cleanupAutomaticPolicy(RollingUpdate update) {
        // IMMEDIATE or GRACE_PERIOD: remove old version
        // Note: GRACE_PERIOD delay not yet implemented - behaves like IMMEDIATE
        var oldArtifact = update.artifactBase()
                                .withVersion(update.oldVersion());
        log.info("Removing old version {}", oldArtifact);
        return removeBlueprintKey(oldArtifact)
                                 .flatMap(_ -> removeRoutingKey(update))
                                 .flatMap(_ -> persistAndTransition(update, RollingUpdateState.COMPLETED));
    }

    private Promise<RollingUpdate> removeNewVersion(RollingUpdate update) {
        var newArtifact = update.artifactBase()
                                .withVersion(update.newVersion());
        log.info("Removing new version {} during rollback", newArtifact);
        return removeBlueprintKey(newArtifact)
                                 .flatMap(_ -> removeRoutingKey(update))
                                 .flatMap(_ -> persistAndTransition(update, RollingUpdateState.ROLLED_BACK));
    }

    @SuppressWarnings("unchecked")
    private Promise<Unit> removeBlueprintKey(org.pragmatica.aether.artifact.Artifact artifact) {
        var key = new AetherKey.BlueprintKey(artifact);
        var command = (KVCommand<AetherKey>)(KVCommand< ? >) new KVCommand.Remove<>(key);
        return clusterNode.<Unit> apply(List.of(command))
                          .timeout(KV_OPERATION_TIMEOUT)
                          .mapToUnit();
    }

    @SuppressWarnings("unchecked")
    private Promise<Unit> removeRoutingKey(RollingUpdate update) {
        var routingKey = new AetherKey.VersionRoutingKey(update.artifactBase());
        var routingCmd = (KVCommand<AetherKey>)(KVCommand< ? >) new KVCommand.Remove<>(routingKey);
        return clusterNode.<Unit> apply(List.of(routingCmd))
                          .timeout(KV_OPERATION_TIMEOUT)
                          .mapToUnit();
    }
}
