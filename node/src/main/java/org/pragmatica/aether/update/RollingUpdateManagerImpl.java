package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.KSUID;

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

    private final RabiaNode<KVCommand<AetherKey>> clusterNode;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final InvocationMetricsCollector metricsCollector;

    // Local cache of rolling updates (authoritative source is KV-Store)
    private final Map<String, RollingUpdate> updates = new ConcurrentHashMap<>();

    private RollingUpdateManagerImpl(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                     KVStore<AetherKey, AetherValue> kvStore,
                                     InvocationMetricsCollector metricsCollector) {
        this.clusterNode = clusterNode;
        this.kvStore = kvStore;
        this.metricsCollector = metricsCollector;
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
        // Check for existing active update
        var existing = getActiveUpdate(artifactBase);
        if (existing.isPresent()) {
            return new RollingUpdateError.UpdateAlreadyExists(artifactBase).promise();
        }
        // Find current version from KV-Store
        return findCurrentVersion(artifactBase)
               .flatMap(oldVersion -> {
                            var updateId = KSUID.ksuid()
                                                .encoded();
                            var update = RollingUpdate.create(
        updateId, artifactBase, oldVersion, newVersion, instances, thresholds, cleanupPolicy);
                            log.info("Starting rolling update {} for {} from {} to {}",
                                     updateId,
                                     artifactBase,
                                     oldVersion,
                                     newVersion);
                            // Store update and transition to DEPLOYING
        updates.put(updateId, update);
                            return persistAndTransition(update, RollingUpdateState.DEPLOYING)
                                   .flatMap(u -> deployNewVersion(u, instances));
                        });
    }

    @Override
    public Promise<RollingUpdate> adjustRouting(String updateId, VersionRouting newRouting) {
        var update = updates.get(updateId);
        if (update == null) {
            return new RollingUpdateError.UpdateNotFound(updateId).promise();
        }
        if (!update.state()
                   .allowsNewVersionTraffic() && update.state() != RollingUpdateState.DEPLOYED) {
            return new RollingUpdateError.InvalidStateTransition(
            update.state(), RollingUpdateState.ROUTING).promise();
        }
        log.info("Adjusting routing for {} to {}", updateId, newRouting);
        var withRouting = update.withRouting(newRouting);
        var updated = update.state() == RollingUpdateState.DEPLOYED
                      ? withRouting.transitionTo(RollingUpdateState.ROUTING)
                      : withRouting;
        updates.put(updateId, updated);
        return persistRouting(updated)
               .map(_ -> updated);
    }

    @Override
    public Promise<RollingUpdate> approveRouting(String updateId) {
        var update = updates.get(updateId);
        if (update == null) {
            return new RollingUpdateError.UpdateNotFound(updateId).promise();
        }
        if (update.state() != RollingUpdateState.VALIDATING) {
            return new RollingUpdateError.InvalidStateTransition(
            update.state(), RollingUpdateState.VALIDATING).promise();
        }
        log.info("Manual approval for routing on {}", updateId);
        return Promise.success(update);
    }

    @Override
    public Promise<RollingUpdate> completeUpdate(String updateId) {
        var update = updates.get(updateId);
        if (update == null) {
            return new RollingUpdateError.UpdateNotFound(updateId).promise();
        }
        if (!update.routing()
                   .isAllNew()) {
            return new RollingUpdateError.InvalidStateTransition(
            update.state(), RollingUpdateState.COMPLETING).promise();
        }
        log.info("Completing rolling update {}", updateId);
        return persistAndTransition(update, RollingUpdateState.COMPLETING)
               .flatMap(this::cleanupOldVersion);
    }

    @Override
    public Promise<RollingUpdate> rollback(String updateId) {
        var update = updates.get(updateId);
        if (update == null) {
            return new RollingUpdateError.UpdateNotFound(updateId).promise();
        }
        if (update.isTerminal()) {
            return new RollingUpdateError.InvalidStateTransition(
            update.state(), RollingUpdateState.ROLLING_BACK).promise();
        }
        log.info("Rolling back update {}", updateId);
        return persistAndTransition(update, RollingUpdateState.ROLLING_BACK)
               .flatMap(this::removeNewVersion);
    }

    @Override
    public Option<RollingUpdate> getUpdate(String updateId) {
        return Option.option(updates.get(updateId));
    }

    @Override
    public Option<RollingUpdate> getActiveUpdate(ArtifactBase artifactBase) {
        return Option.option(
        updates.values()
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
        var update = updates.get(updateId);
        if (update == null) {
            return new RollingUpdateError.UpdateNotFound(updateId).promise();
        }
        // Collect metrics from InvocationMetricsCollector
        var snapshots = metricsCollector.snapshot();
        // Filter for old and new version metrics
        long oldRequests = 0, oldErrors = 0, oldTotalLatency = 0;
        long newRequests = 0, newErrors = 0, newTotalLatency = 0;
        var oldArtifact = update.artifactBase()
                                .withVersion(update.oldVersion());
        var newArtifact = update.artifactBase()
                                .withVersion(update.newVersion());
        for (var snapshot : snapshots) {
            if (snapshot.artifact()
                        .equals(oldArtifact)) {
                oldRequests += snapshot.metrics()
                                       .count();
                oldErrors += snapshot.metrics()
                                     .failureCount();
                oldTotalLatency += snapshot.metrics()
                                           .totalDurationNs() / 1_000_000;
            }else if (snapshot.artifact()
                              .equals(newArtifact)) {
                newRequests += snapshot.metrics()
                                       .count();
                newErrors += snapshot.metrics()
                                     .failureCount();
                newTotalLatency += snapshot.metrics()
                                           .totalDurationNs() / 1_000_000;
            }
        }
        var oldMetrics = new VersionMetrics(
        update.oldVersion(),
        oldRequests,
        oldErrors,
        oldRequests > 0
        ? (double) oldErrors / oldRequests
        : 0.0,
        oldRequests > 0
        ? oldTotalLatency / oldRequests
        : 0,
        oldRequests > 0
        ? oldTotalLatency / oldRequests
        : 0);
        var newMetrics = new VersionMetrics(
        update.newVersion(),
        newRequests,
        newErrors,
        newRequests > 0
        ? (double) newErrors / newRequests
        : 0.0,
        newRequests > 0
        ? newTotalLatency / newRequests
        : 0,
        newRequests > 0
        ? newTotalLatency / newRequests
        : 0);
        return Promise.success(new VersionHealthMetrics(
        updateId, oldMetrics, newMetrics, System.currentTimeMillis()));
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
        // Create a fallback version for artifacts not yet deployed
        return Version.version("0.0.0")
                      .fold(cause -> new RollingUpdateError.VersionNotFound(artifactBase, null).promise(),
                            version -> new RollingUpdateError.VersionNotFound(artifactBase, version).promise());
    }

    @SuppressWarnings("unchecked")
    private Promise<RollingUpdate> persistAndTransition(RollingUpdate update,
                                                        RollingUpdateState newState) {
        var transitioned = update.transitionTo(newState);
        updates.put(update.updateId(), transitioned);
        // Store in KV-Store
        var key = new AetherKey.RollingUpdateKey(update.updateId());
        var value = new AetherValue.RollingUpdateValue(
        transitioned.updateId(),
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
               .map(_ -> transitioned);
    }

    @SuppressWarnings("unchecked")
    private Promise<RollingUpdate> persistRouting(RollingUpdate update) {
        var key = new AetherKey.VersionRoutingKey(update.artifactBase());
        var value = new AetherValue.VersionRoutingValue(
        update.oldVersion(),
        update.newVersion(),
        update.routing()
              .newWeight(),
        update.routing()
              .oldWeight(),
        System.currentTimeMillis());
        var command = (KVCommand<AetherKey>)(KVCommand< ? >) new KVCommand.Put<>(key, value);
        return clusterNode.<Unit> apply(List.of(command))
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
               .flatMap(_ -> persistAndTransition(update, RollingUpdateState.DEPLOYED));
    }

    @SuppressWarnings("unchecked")
    private Promise<RollingUpdate> cleanupOldVersion(RollingUpdate update) {
        var oldArtifact = update.artifactBase()
                                .withVersion(update.oldVersion());
        var key = new AetherKey.BlueprintKey(oldArtifact);
        var command = (KVCommand<AetherKey>)(KVCommand< ? >) new KVCommand.Remove<>(key);
        log.info("Removing old version {}", oldArtifact);
        return clusterNode.<Unit> apply(List.of(command))
               .flatMap(_ -> {
                            // Also remove routing key
        var routingKey = new AetherKey.VersionRoutingKey(update.artifactBase());
                            var routingCmd = (KVCommand<AetherKey>)(KVCommand< ? >) new KVCommand.Remove<>(routingKey);
                            return clusterNode.<Unit>apply(List.of(routingCmd));
                        })
               .flatMap(_ -> persistAndTransition(update, RollingUpdateState.COMPLETED));
    }

    @SuppressWarnings("unchecked")
    private Promise<RollingUpdate> removeNewVersion(RollingUpdate update) {
        var newArtifact = update.artifactBase()
                                .withVersion(update.newVersion());
        var key = new AetherKey.BlueprintKey(newArtifact);
        var command = (KVCommand<AetherKey>)(KVCommand< ? >) new KVCommand.Remove<>(key);
        log.info("Removing new version {} during rollback", newArtifact);
        return clusterNode.<Unit> apply(List.of(command))
               .flatMap(_ -> {
                            var routingKey = new AetherKey.VersionRoutingKey(update.artifactBase());
                            var routingCmd = (KVCommand<AetherKey>)(KVCommand< ? >) new KVCommand.Remove<>(routingKey);
                            return clusterNode.<Unit>apply(List.of(routingCmd));
                        })
               .flatMap(_ -> persistAndTransition(update, RollingUpdateState.ROLLED_BACK));
    }
}
