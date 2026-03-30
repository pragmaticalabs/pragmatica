package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueGreenDeploymentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueGreenDeploymentValue;
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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Manages blue-green deployment operations across the cluster.
///
///
/// Implements a two-environment deployment model with atomic traffic switching:
/// <ol>
///   - **Deploy stage**: Green environment deployed alongside existing blue
///   - **Switch stage**: Traffic atomically switched from blue to green
///   - **Drain stage**: Blue environment drained and cleaned up
/// </ol>
///
///
/// Unlike canary deployments, blue-green uses an all-or-nothing traffic switch
/// rather than progressive traffic shifting. This provides simpler rollback
/// (just switch back) at the cost of requiring double the resources during deployment.
///
///
/// Blue-green deployments are orchestrated by the leader node via consensus.
/// All state is stored in the KV-Store for persistence and visibility.
public interface BlueGreenDeploymentManager {
    /// Deploy green environment alongside existing blue.
    Promise<BlueGreenDeployment> deployGreen(ArtifactBase artifactBase,
                                             Version greenVersion,
                                             int instances,
                                             HealthThresholds thresholds,
                                             long drainTimeoutMs,
                                             CleanupPolicy cleanupPolicy);

    /// Switch traffic atomically from blue to green.
    Promise<BlueGreenDeployment> switchToGreen(String deploymentId);

    /// Switch traffic back to blue (rollback after switch).
    Promise<BlueGreenDeployment> switchBack(String deploymentId);

    /// Complete deployment — drain blue environment and finalize.
    Promise<BlueGreenDeployment> completeDeployment(String deploymentId);

    /// Roll back green environment (pre-switch rollback).
    Promise<BlueGreenDeployment> rollback(String deploymentId);

    /// Get deployment by ID.
    Option<BlueGreenDeployment> getDeployment(String deploymentId);

    /// Get active deployment for an artifact.
    Option<BlueGreenDeployment> getActiveDeployment(ArtifactBase artifactBase);

    /// List all active (non-terminal) blue-green deployments.
    List<BlueGreenDeployment> activeDeployments();

    /// List all blue-green deployments.
    List<BlueGreenDeployment> allDeployments();

    /// Handle leader change (restore state on becoming leader).
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onLeaderChange(LeaderChange leaderChange);

    /// Handle deployment failure for auto-rollback.
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onDeploymentFailed(DeploymentEvent.DeploymentFailed event);

    /// Default KV operation timeout.
    TimeSpan DEFAULT_KV_OPERATION_TIMEOUT = TimeSpan.timeSpan(30)
                                                   .seconds();

    /// Default terminal retention (1 hour).
    long DEFAULT_TERMINAL_RETENTION_MS = TimeUnit.HOURS.toMillis(1);

    /// Factory method following JBCT naming convention.
    static BlueGreenDeploymentManager blueGreenDeploymentManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                 KVStore<AetherKey, AetherValue> kvStore,
                                                                 InvocationMetricsCollector metricsCollector) {
        return blueGreenDeploymentManager(clusterNode,
                                          kvStore,
                                          metricsCollector,
                                          DEFAULT_KV_OPERATION_TIMEOUT,
                                          DEFAULT_TERMINAL_RETENTION_MS);
    }

    /// Factory method with custom settings.
    @SuppressWarnings({"JBCT-SEQ-01", "JBCT-NAM-01"})
    static BlueGreenDeploymentManager blueGreenDeploymentManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                 KVStore<AetherKey, AetherValue> kvStore,
                                                                 InvocationMetricsCollector metricsCollector,
                                                                 TimeSpan kvOperationTimeout,
                                                                 long terminalRetentionMs) {
        record blueGreenDeploymentManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                          KVStore<AetherKey, AetherValue> kvStore,
                                          InvocationMetricsCollector metricsCollector,
                                          TimeSpan kvOperationTimeout,
                                          long terminalRetentionMs,
                                          Map<String, BlueGreenDeployment> deployments) implements BlueGreenDeploymentManager {
            private static final Logger log = LoggerFactory.getLogger(BlueGreenDeploymentManager.class);

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onLeaderChange(LeaderChange leaderChange) {
                if (leaderChange.localNodeIsLeader()) {
                    log.info("Blue-green deployment manager active (leader)");
                    restoreState();
                } else {
                    log.info("Blue-green deployment manager passive (follower)");
                }
            }

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onDeploymentFailed(DeploymentEvent.DeploymentFailed event) {
                var artifactBase = event.artifact()
                                        .base();
                getActiveDeployment(artifactBase).filter(dep -> dep.greenVersion()
                                                                   .equals(event.artifact()
                                                                                .version()))
                                   .filter(BlueGreenDeployment::isActive)
                                   .onPresent(dep -> triggerAutoRollback(dep, event));
            }

            @SuppressWarnings("JBCT-RET-01") // Side-effect helper — void inherent
            private void triggerAutoRollback(BlueGreenDeployment deployment, DeploymentEvent.DeploymentFailed event) {
                log.warn("Auto-rollback triggered for blue-green {} — green version {} failed on node {}: {}",
                         deployment.deploymentId(),
                         event.artifact(),
                         event.nodeId(),
                         event.errorMessage());
                rollback(deployment.deploymentId())
                .onFailure(cause -> log.error("Auto-rollback failed for blue-green {}: {}",
                                              deployment.deploymentId(),
                                              cause.message()));
            }

            // --- State restoration ---
            @SuppressWarnings("JBCT-RET-01") // Side-effect helper — void inherent
            private void restoreState() {
                int beforeCount = deployments.size();
                kvStore.forEach(BlueGreenDeploymentKey.class,
                                BlueGreenDeploymentValue.class,
                                (key, value) -> restoreDeployment(value));
                int restoredCount = deployments.size() - beforeCount;
                if (restoredCount > 0) {
                    log.info("Restored {} blue-green deployments from KV-Store", restoredCount);
                }
            }

            @SuppressWarnings({"JBCT-VO-02", "JBCT-RET-01"}) // Side-effect helper — void inherent
            private void restoreDeployment(BlueGreenDeploymentValue bgv) {
                var state = BlueGreenState.valueOf(bgv.state());
                var activeEnv = BlueGreenDeployment.ActiveEnvironment.valueOf(bgv.activeEnvironment());
                var routing = new VersionRouting(bgv.newWeight(), bgv.oldWeight());
                var thresholds = new HealthThresholds(bgv.maxErrorRate(),
                                                      bgv.maxLatencyMs(),
                                                      bgv.requireManualApproval());
                var cleanupPolicy = CleanupPolicy.valueOf(bgv.cleanupPolicy());
                var blueprintId = bgv.blueprintId()
                                     .isEmpty()
                                  ? Option.<String>none()
                                  : Option.some(bgv.blueprintId());
                var artifacts = deserializeArtifacts(bgv.artifactsJson());
                var deployment = new BlueGreenDeployment(bgv.deploymentId(),
                                                         bgv.artifactBase(),
                                                         bgv.blueVersion(),
                                                         bgv.greenVersion(),
                                                         state,
                                                         activeEnv,
                                                         bgv.blueInstances(),
                                                         bgv.greenInstances(),
                                                         bgv.drainTimeoutMs(),
                                                         thresholds,
                                                         cleanupPolicy,
                                                         routing,
                                                         blueprintId,
                                                         artifacts,
                                                         bgv.createdAt(),
                                                         bgv.updatedAt());
                deployments.put(deployment.deploymentId(), deployment);
            }

            // --- Core operations ---
            @Override
            public Promise<BlueGreenDeployment> deployGreen(ArtifactBase artifactBase,
                                                            Version greenVersion,
                                                            int instances,
                                                            HealthThresholds thresholds,
                                                            long drainTimeoutMs,
                                                            CleanupPolicy cleanupPolicy) {
                return requireLeader().flatMap(_ -> checkNoActiveDeployment(artifactBase))
                                    .flatMap(_ -> findCurrentVersion(artifactBase))
                                    .flatMap(blueVersion -> createAndDeployGreen(artifactBase,
                                                                                 blueVersion,
                                                                                 greenVersion,
                                                                                 instances,
                                                                                 drainTimeoutMs,
                                                                                 thresholds,
                                                                                 cleanupPolicy));
            }

            @Override
            public Promise<BlueGreenDeployment> switchToGreen(String deploymentId) {
                return requireLeader().flatMap(_ -> findDeployment(deploymentId))
                                    .flatMap(this::validateAndSwitchToGreen);
            }

            @Override
            public Promise<BlueGreenDeployment> switchBack(String deploymentId) {
                return requireLeader().flatMap(_ -> findDeployment(deploymentId))
                                    .flatMap(this::validateAndSwitchBack);
            }

            @Override
            public Promise<BlueGreenDeployment> completeDeployment(String deploymentId) {
                return requireLeader().flatMap(_ -> findDeployment(deploymentId))
                                    .flatMap(this::validateAndComplete);
            }

            @Override
            public Promise<BlueGreenDeployment> rollback(String deploymentId) {
                return requireLeader().flatMap(_ -> findDeployment(deploymentId))
                                    .flatMap(this::validateAndRollback);
            }

            @Override
            public Option<BlueGreenDeployment> getDeployment(String deploymentId) {
                return Option.option(deployments.get(deploymentId));
            }

            @Override
            public Option<BlueGreenDeployment> getActiveDeployment(ArtifactBase artifactBase) {
                return Option.from(deployments.values()
                                              .stream()
                                              .filter(d -> d.artifactBase()
                                                            .equals(artifactBase) && d.isActive())
                                              .findFirst());
            }

            @Override
            public List<BlueGreenDeployment> activeDeployments() {
                return deployments.values()
                                  .stream()
                                  .filter(BlueGreenDeployment::isActive)
                                  .toList();
            }

            @Override
            public List<BlueGreenDeployment> allDeployments() {
                return List.copyOf(deployments.values());
            }

            // --- Private helpers ---
            private Promise<Unit> requireLeader() {
                if (!clusterNode.leaderManager()
                                .isLeader()) {
                    return BlueGreenDeploymentError.NotLeader.INSTANCE.promise();
                }
                return Promise.success(Unit.unit());
            }

            private Promise<Unit> checkNoActiveDeployment(ArtifactBase artifactBase) {
                return getActiveDeployment(artifactBase).isPresent()
                       ? BlueGreenDeploymentError.DeploymentAlreadyExists.deploymentAlreadyExists(artifactBase)
                                                 .promise()
                       : Promise.success(Unit.unit());
            }

            private Promise<BlueGreenDeployment> findDeployment(String deploymentId) {
                return Option.option(deployments.get(deploymentId))
                             .toResult(BlueGreenDeploymentError.DeploymentNotFound.deploymentNotFound(deploymentId))
                             .async();
            }

            private Promise<Version> findCurrentVersion(ArtifactBase artifactBase) {
                var key = SliceTargetKey.sliceTargetKey(artifactBase);
                return kvStore.get(key)
                              .map(value -> ((SliceTargetValue) value).currentVersion())
                              .toResult(BlueGreenDeploymentError.InitialDeployment.initialDeployment(artifactBase))
                              .async();
            }

            private Promise<BlueGreenDeployment> createAndDeployGreen(ArtifactBase artifactBase,
                                                                      Version blueVersion,
                                                                      Version greenVersion,
                                                                      int instances,
                                                                      long drainTimeoutMs,
                                                                      HealthThresholds thresholds,
                                                                      CleanupPolicy cleanupPolicy) {
                var deploymentId = KSUID.ksuid()
                                        .encoded();
                var blueInstances = resolveBlueInstances(artifactBase);
                var deployment = BlueGreenDeployment.blueGreenDeployment(deploymentId,
                                                                         artifactBase,
                                                                         blueVersion,
                                                                         greenVersion,
                                                                         blueInstances,
                                                                         instances,
                                                                         drainTimeoutMs,
                                                                         thresholds,
                                                                         cleanupPolicy);
                log.info("Starting blue-green deployment {} for {} from {} to {}",
                         deploymentId,
                         artifactBase,
                         blueVersion,
                         greenVersion);
                deployments.put(deploymentId, deployment);
                return persistAndTransition(deployment, BlueGreenState.DEPLOYING_GREEN)
                .flatMap(d -> deployGreenVersion(d, instances));
            }

            private int resolveBlueInstances(ArtifactBase artifactBase) {
                var key = SliceTargetKey.sliceTargetKey(artifactBase);
                return kvStore.get(key)
                              .filter(v -> v instanceof SliceTargetValue)
                              .map(v -> ((SliceTargetValue) v).targetInstances())
                              .or(1);
            }

            @SuppressWarnings("unchecked")
            private Promise<BlueGreenDeployment> deployGreenVersion(BlueGreenDeployment deployment, int instances) {
                var artifactBase = deployment.artifactBase();
                var routingKey = new AetherKey.VersionRoutingKey(artifactBase);
                var routingValue = new AetherValue.VersionRoutingValue(deployment.blueVersion(),
                                                                       deployment.greenVersion(),
                                                                       deployment.routing()
                                                                                 .newWeight(),
                                                                       deployment.routing()
                                                                                 .oldWeight(),
                                                                       System.currentTimeMillis());
                var routingCmd = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(routingKey, routingValue);
                var targetKey = SliceTargetKey.sliceTargetKey(artifactBase);
                var existingMinInstances = kvStore.get(targetKey)
                                                  .filter(v -> v instanceof SliceTargetValue)
                                                  .map(v -> ((SliceTargetValue) v).effectiveMinInstances())
                                                  .or(instances);
                var targetValue = new SliceTargetValue(deployment.greenVersion(),
                                                       instances,
                                                       existingMinInstances,
                                                       Option.none(),
                                                       "CORE_ONLY",
                                                       System.currentTimeMillis());
                var targetCmd = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(targetKey, targetValue);
                log.info("Deploying {} green instances of {} (version {})",
                         instances,
                         artifactBase,
                         deployment.greenVersion());
                return clusterNode.<Unit> apply(List.of(routingCmd, targetCmd))
                                  .timeout(kvOperationTimeout)
                                  .flatMap(_ -> persistAndTransition(deployment, BlueGreenState.GREEN_READY));
            }

            private Promise<BlueGreenDeployment> validateAndSwitchToGreen(BlueGreenDeployment deployment) {
                if (deployment.state() != BlueGreenState.GREEN_READY) {
                    return BlueGreenDeploymentError.InvalidDeploymentState.invalidDeploymentState(deployment.state(),
                                                                                                  BlueGreenState.SWITCHED)
                                                   .promise();
                }
                log.info("Switching traffic to green for deployment {}", deployment.deploymentId());
                var switched = deployment.switchToGreen();
                return switched.transitionTo(BlueGreenState.SWITCHED)
                               .async()
                               .flatMap(this::cacheAndPersistDeployment)
                               .flatMap(this::persistRouting);
            }

            private Promise<BlueGreenDeployment> validateAndSwitchBack(BlueGreenDeployment deployment) {
                if (deployment.state() != BlueGreenState.SWITCHED) {
                    return BlueGreenDeploymentError.InvalidDeploymentState.invalidDeploymentState(deployment.state(),
                                                                                                  BlueGreenState.SWITCH_BACK)
                                                   .promise();
                }
                log.info("Switching traffic back to blue for deployment {}", deployment.deploymentId());
                var switchedBack = deployment.switchBack();
                return switchedBack.transitionTo(BlueGreenState.SWITCH_BACK)
                                   .async()
                                   .flatMap(this::cacheAndPersistDeployment)
                                   .flatMap(this::persistRouting)
                                   .flatMap(this::removeNewVersion);
            }

            private Promise<BlueGreenDeployment> validateAndComplete(BlueGreenDeployment deployment) {
                if (deployment.state() != BlueGreenState.SWITCHED) {
                    return BlueGreenDeploymentError.InvalidDeploymentState.invalidDeploymentState(deployment.state(),
                                                                                                  BlueGreenState.DRAINING)
                                                   .promise();
                }
                log.info("Completing blue-green deployment {} — draining blue environment", deployment.deploymentId());
                return persistAndTransition(deployment, BlueGreenState.DRAINING).flatMap(this::cleanupBlueEnvironment);
            }

            private Promise<BlueGreenDeployment> validateAndRollback(BlueGreenDeployment deployment) {
                if (deployment.isTerminal()) {
                    return BlueGreenDeploymentError.InvalidDeploymentState.invalidDeploymentState(deployment.state(),
                                                                                                  BlueGreenState.ROLLING_BACK)
                                                   .promise();
                }
                log.info("Rolling back blue-green deployment {}", deployment.deploymentId());
                var withOldRouting = deployment.switchBack();
                return withOldRouting.transitionTo(BlueGreenState.ROLLING_BACK)
                                     .async()
                                     .flatMap(this::cacheAndPersistDeployment)
                                     .flatMap(this::removeNewVersion);
            }

            private Promise<BlueGreenDeployment> cleanupBlueEnvironment(BlueGreenDeployment deployment) {
                log.info("Cleanup: finalizing slice target for {} to version {}",
                         deployment.artifactBase(),
                         deployment.greenVersion());
                return removeRoutingKey(deployment).flatMap(_ -> persistAndTransition(deployment,
                                                                                      BlueGreenState.COMPLETED));
            }

            private Promise<BlueGreenDeployment> removeNewVersion(BlueGreenDeployment deployment) {
                log.info("Rolling back slice target for {} to version {}",
                         deployment.artifactBase(),
                         deployment.blueVersion());
                return updateSliceTargetVersion(deployment.artifactBase(), deployment.blueVersion())
                    .flatMap(_ -> removeRoutingKey(deployment))
                    .recover(cause -> {
                        log.warn("Rollback cleanup partially failed for {}: {}", deployment.deploymentId(), cause.message());
                        return Unit.unit();
                    })
                    .flatMap(_ -> persistAndTransition(deployment, BlueGreenState.ROLLED_BACK));
            }

            // --- Persistence ---
            private Promise<BlueGreenDeployment> persistAndTransition(BlueGreenDeployment deployment,
                                                                      BlueGreenState newState) {
                return deployment.transitionTo(newState)
                                 .async()
                                 .flatMap(this::cacheAndPersistDeployment);
            }

            @SuppressWarnings("unchecked")
            private Promise<BlueGreenDeployment> cacheAndPersistDeployment(BlueGreenDeployment deployment) {
                deployments.put(deployment.deploymentId(), deployment);
                if (deployment.isTerminal()) {
                    pruneTerminalDeployments();
                }
                var key = new AetherKey.BlueGreenDeploymentKey(deployment.deploymentId());
                var value = buildDeploymentValue(deployment);
                var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);
                return clusterNode.<Unit> apply(List.of(command))
                                  .timeout(kvOperationTimeout)
                                  .map(_ -> deployment);
            }

            private BlueGreenDeploymentValue buildDeploymentValue(BlueGreenDeployment deployment) {
                return BlueGreenDeploymentValue.blueGreenDeploymentValue(deployment.deploymentId(),
                                                                         deployment.artifactBase(),
                                                                         deployment.blueVersion(),
                                                                         deployment.greenVersion(),
                                                                         deployment.state()
                                                                                   .name(),
                                                                         deployment.activeEnvironment()
                                                                                   .name(),
                                                                         deployment.blueInstances(),
                                                                         deployment.greenInstances(),
                                                                         deployment.drainTimeoutMs(),
                                                                         deployment.healthThresholds()
                                                                                   .maxErrorRate(),
                                                                         deployment.healthThresholds()
                                                                                   .maxLatencyMs(),
                                                                         deployment.healthThresholds()
                                                                                   .requireManualApproval(),
                                                                         deployment.cleanupPolicy()
                                                                                   .name(),
                                                                         deployment.routing()
                                                                                   .newWeight(),
                                                                         deployment.routing()
                                                                                   .oldWeight(),
                                                                         deployment.blueprintId()
                                                                                   .or(""),
                                                                         serializeArtifacts(deployment.artifacts()),
                                                                         deployment.createdAt(),
                                                                         System.currentTimeMillis());
            }

            @SuppressWarnings("unchecked")
            private Promise<BlueGreenDeployment> persistRouting(BlueGreenDeployment deployment) {
                var key = new AetherKey.VersionRoutingKey(deployment.artifactBase());
                var value = new AetherValue.VersionRoutingValue(deployment.blueVersion(),
                                                                deployment.greenVersion(),
                                                                deployment.routing()
                                                                          .newWeight(),
                                                                deployment.routing()
                                                                          .oldWeight(),
                                                                System.currentTimeMillis());
                var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);
                return clusterNode.<Unit> apply(List.of(command))
                                  .timeout(kvOperationTimeout)
                                  .map(_ -> deployment);
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
            private Promise<Unit> removeRoutingKey(BlueGreenDeployment deployment) {
                var routingKey = new AetherKey.VersionRoutingKey(deployment.artifactBase());
                var routingCmd = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Remove<>(routingKey);
                return clusterNode.<Unit> apply(List.of(routingCmd))
                                  .timeout(kvOperationTimeout)
                                  .mapToUnit();
            }

            // --- Housekeeping ---
            @SuppressWarnings("JBCT-RET-01") // Side-effect helper — void inherent
            private void pruneTerminalDeployments() {
                var cutoff = System.currentTimeMillis() - terminalRetentionMs;
                var pruned = deployments.entrySet()
                                        .removeIf(entry -> entry.getValue()
                                                                .isTerminal() && entry.getValue()
                                                                                      .updatedAt() < cutoff);
                if (pruned) {
                    log.debug("Pruned terminal blue-green deployments older than retention period");
                }
            }

            // --- Serialization helpers ---
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
        return new blueGreenDeploymentManager(clusterNode,
                                              kvStore,
                                              metricsCollector,
                                              kvOperationTimeout,
                                              terminalRetentionMs,
                                              new ConcurrentHashMap<>());
    }
}
