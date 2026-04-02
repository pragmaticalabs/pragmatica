package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.KSUID;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.update.DeploymentError.BlueprintNotFound.blueprintNotFound;
import static org.pragmatica.aether.update.DeploymentError.DeploymentAlreadyExists.deploymentAlreadyExists;
import static org.pragmatica.aether.update.DeploymentError.DeploymentNotFound.deploymentNotFound;
import static org.pragmatica.aether.update.DeploymentError.NoCurrentVersion.noCurrentVersion;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;

/// Unified deployment manager implementation that operates at blueprint level.
///
/// All deployment operations are synchronous (return Result) because they
/// execute consensus commands and wait for them to be applied. State is
/// maintained in an in-memory cache and reconstructed from KV-Store on
/// leader election.
///
/// Factory method: [DeploymentManager#deploymentManager]
final class DeploymentManagerImpl implements DeploymentManager {
    private static final Logger log = LoggerFactory.getLogger(DeploymentManager.class);

    private final RabiaNode<KVCommand<AetherKey>> clusterNode;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final ConcurrentHashMap<String, Deployment> activeDeployments = new ConcurrentHashMap<>();
    private volatile boolean leader;

    DeploymentManagerImpl(RabiaNode<KVCommand<AetherKey>> clusterNode,
                          KVStore<AetherKey, AetherValue> kvStore) {
        this.clusterNode = clusterNode;
        this.kvStore = kvStore;
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onLeaderChange(LeaderChange leaderChange) {
        if ( leaderChange.localNodeIsLeader()) {
            log.info("Deployment manager active (leader)");
            leader = true;
            restoreState();
        } else

























        {
            log.info("Deployment manager passive (follower)");
            leader = false;
            activeDeployments.clear();
        }
    }

    @Override public Result<Deployment> start(String blueprintId,
                                              Version newVersion,
                                              DeploymentStrategy strategy,
                                              StrategyConfig config,
                                              HealthThresholds thresholds,
                                              CleanupPolicy cleanupPolicy,
                                              int instances) {
        return requireLeader().flatMap(_ -> checkNoActiveDeployment(blueprintId))
                            .flatMap(_ -> resolveBlueprint(blueprintId))
                            .flatMap(blueprint -> resolveSlicesAndCurrentVersion(blueprint, newVersion))
                            .flatMap(context -> createDeployment(context,
                                                                 blueprintId,
                                                                 newVersion,
                                                                 strategy,
                                                                 config,
                                                                 thresholds,
                                                                 cleanupPolicy,
                                                                 instances));
    }

    @Override public Result<Deployment> promote(String deploymentId) {
        return requireLeader().flatMap(_ -> findDeployment(deploymentId))
                            .flatMap(this::applyPromoteRouting);
    }

    @Override public Result<Deployment> rollback(String deploymentId) {
        return requireLeader().flatMap(_ -> findDeployment(deploymentId))
                            .flatMap(Deployment::rollback)
                            .flatMap(this::applyRollbackRouting);
    }

    @Override public Result<Deployment> complete(String deploymentId) {
        return requireLeader().flatMap(_ -> findDeployment(deploymentId))
                            .flatMap(Deployment::complete)
                            .flatMap(this::applyCompleteRouting);
    }

    @Override public Option<Deployment> status(String deploymentId) {
        return option(activeDeployments.get(deploymentId));
    }

    @Override public List<Deployment> list() {
        return activeDeployments.values().stream()
                                       .filter(Deployment::isActive)
                                       .toList();
    }

    @Override public Option<ActiveRouting> activeRouting(ArtifactBase artifactBase) {
        return activeDeployments.values().stream()
                                       .filter(Deployment::isActive)
                                       .filter(d -> containsArtifact(d, artifactBase))
                                       .findFirst()
                                       .map(d -> new ActiveRouting(d.routing(),
                                                                   d.oldVersion(),
                                                                   d.newVersion()))
                                       .map(Option::some)
                                       .orElse(none());
    }

    // --- Leader check ---
    private Result<Unit> requireLeader() {
        if ( !leader) {
        return DeploymentError.General.NOT_LEADER.result();}
        return Result.unitResult();
    }

    // --- Blueprint resolution ---
    private Result<ExpandedBlueprint> resolveBlueprint(String blueprintId) {
        return BlueprintId.blueprintId(blueprintId).flatMap(this::lookupBlueprint);
    }

    private Result<ExpandedBlueprint> lookupBlueprint(BlueprintId id) {
        var key = AppBlueprintKey.appBlueprintKey(id);
        return kvStore.get(key).filter(AppBlueprintValue.class::isInstance)
                          .map(AppBlueprintValue.class::cast)
                          .map(AppBlueprintValue::blueprint)
                          .toResult(blueprintNotFound(id.asString()));
    }

    // --- Duplicate check ---
    private Result<Unit> checkNoActiveDeployment(String blueprintId) {
        var existing = activeDeployments.values().stream()
                                               .filter(Deployment::isActive)
                                               .anyMatch(d -> d.blueprintId().equals(blueprintId));
        if ( existing) {
        return deploymentAlreadyExists(blueprintId).result();}
        return Result.unitResult();
    }

    // --- Slice resolution ---
    private Result<SliceContext> resolveSlicesAndCurrentVersion(ExpandedBlueprint blueprint, Version newVersion) {
        var artifacts = new ArrayList<ArtifactBase>();
        Version currentVersion = null;
        for ( var slice : blueprint.loadOrder()) {
            var base = slice.artifact().base();
            artifacts.add(base);
            if ( currentVersion == null) {
                var target = kvStore.get(SliceTargetKey.sliceTargetKey(base)).filter(SliceTargetValue.class::isInstance)
                                        .map(SliceTargetValue.class::cast);
                if ( target.isEmpty()) {
                return noCurrentVersion(base.asString()).result();}
                currentVersion = target.unwrap().currentVersion();
            }
        }
        if ( currentVersion == null) {
        return blueprintNotFound("empty blueprint").result();}
        return Result.success(new SliceContext(artifacts, currentVersion));
    }

    private record SliceContext(List<ArtifactBase> artifacts, Version currentVersion){}

    // --- Deployment creation ---
    @SuppressWarnings("JBCT-SEQ-01")
    private Result<Deployment> createDeployment(SliceContext context,
                                                String blueprintId,
                                                Version newVersion,
                                                DeploymentStrategy strategy,
                                                StrategyConfig config,
                                                HealthThresholds thresholds,
                                                CleanupPolicy cleanupPolicy,
                                                int instances) {
        var deploymentId = KSUID.ksuid().encoded();
        var deployment = Deployment.deployment(deploymentId,
                                               blueprintId,
                                               context.currentVersion(),
                                               newVersion,
                                               strategy,
                                               config,
                                               thresholds,
                                               cleanupPolicy,
                                               context.artifacts(),
                                               instances);
        var commands = buildStartCommands(deployment, context);
        return applyConsensus(commands).map(_ -> cacheDeployment(deployment));
    }

    private List<KVCommand<AetherKey>> buildStartCommands(Deployment deployment, SliceContext context) {
        var commands = new ArrayList<KVCommand<AetherKey>>();
        for ( var base : context.artifacts()) {
            addSliceTargetCommand(commands, base, deployment.newVersion(), deployment.newInstances());
            addVersionRoutingCommand(commands, base, deployment.oldVersion(), deployment.newVersion());
        }
        addDeploymentCommand(commands, deployment);
        return commands;
    }

    // --- Promote routing ---
    private Result<Deployment> applyPromoteRouting(Deployment deployment) {
        var newRouting = computePromoteRouting(deployment);
        return transitionForPromote(deployment, newRouting)
        .flatMap(updated -> applyRoutingAndPersist(updated, newRouting));
    }

    private Result<Deployment> transitionForPromote(Deployment deployment, VersionRouting newRouting) {
        if ( newRouting.isAllNew()) {
        return deployment.promote();}
        return deployment.route(newRouting);
    }

    private Result<Deployment> applyRoutingAndPersist(Deployment updated, VersionRouting newRouting) {
        var commands = buildRoutingCommands(updated);
        return applyConsensus(commands).map(_ -> cacheDeployment(updated));
    }

    private VersionRouting computePromoteRouting(Deployment deployment) {
        return switch (deployment.strategy()) {case CANARY -> computeCanaryPromoteRouting(deployment);case BLUE_GREEN -> VersionRouting.ALL_NEW;case ROLLING -> VersionRouting.ALL_NEW;};
    }

    private VersionRouting computeCanaryPromoteRouting(Deployment deployment) {
        if ( deployment.strategyConfig() instanceof StrategyConfig.CanaryConfig canaryConfig) {
            var stages = canaryConfig.stages();
            var currentRouting = deployment.routing();
            return advanceCanaryStage(stages, currentRouting);
        }
        return VersionRouting.ALL_NEW;
    }

    private VersionRouting advanceCanaryStage(List<CanaryStage> stages, VersionRouting current) {
        for ( int i = 0; i < stages.size(); i++) {
            var stageRouting = stageToRouting(stages.get(i));
            if ( isBeforeOrEqual(current, stageRouting) && i + 1 < stages.size()) {
            return stageToRouting(stages.get(i + 1));}
        }
        return VersionRouting.ALL_NEW;
    }

    private VersionRouting stageToRouting(CanaryStage stage) {
        int pct = stage.trafficPercent();
        if ( pct <= 0) {
        return VersionRouting.ALL_OLD;}
        if ( pct >= 100) {
        return VersionRouting.ALL_NEW;}
        return VersionRouting.versionRouting(pct, 100 - pct).unwrap();
    }

    private boolean isBeforeOrEqual(VersionRouting current, VersionRouting stageRouting) {
        return current.newVersionPercentage() <= stageRouting.newVersionPercentage();
    }

    // --- Rollback routing ---
    private Result<Deployment> applyRollbackRouting(Deployment deployment) {
        var commands = new ArrayList<KVCommand<AetherKey>>();
        for ( var base : deployment.artifacts()) {
            addVersionRoutingAllOld(commands, base, deployment.oldVersion(), deployment.newVersion());
            addSliceTargetCommand(commands, base, deployment.oldVersion(), deployment.newInstances());
        }
        addDeploymentCommand(commands, deployment);
        return applyConsensus(commands).map(_ -> cacheDeployment(deployment));
    }

    // --- Complete routing ---
    private Result<Deployment> applyCompleteRouting(Deployment deployment) {
        var commands = new ArrayList<KVCommand<AetherKey>>();
        for ( var base : deployment.artifacts()) {
        commands.add(new KVCommand.Remove<>(VersionRoutingKey.versionRoutingKey(base)));}
        addDeploymentCommand(commands, deployment);
        return applyConsensus(commands).map(_ -> cacheDeployment(deployment));
    }

    // --- Routing commands for promote ---
    private List<KVCommand<AetherKey>> buildRoutingCommands(Deployment deployment) {
        var commands = new ArrayList<KVCommand<AetherKey>>();
        for ( var base : deployment.artifacts()) {
        addVersionRoutingWeighted(commands, base, deployment);}
        addDeploymentCommand(commands, deployment);
        return commands;
    }

    // --- KV command builders ---
    private void addSliceTargetCommand(List<KVCommand<AetherKey>> commands,
                                       ArtifactBase base,
                                       Version version,
                                       int instances) {
        var key = SliceTargetKey.sliceTargetKey(base);
        var value = SliceTargetValue.sliceTargetValue(version, instances);
        commands.add(new KVCommand.Put<>(key, value));
    }

    private void addVersionRoutingCommand(List<KVCommand<AetherKey>> commands,
                                          ArtifactBase base,
                                          Version oldVersion,
                                          Version newVersion) {
        var key = VersionRoutingKey.versionRoutingKey(base);
        var value = VersionRoutingValue.versionRoutingValue(oldVersion, newVersion);
        commands.add(new KVCommand.Put<>(key, value));
    }

    private void addVersionRoutingAllOld(List<KVCommand<AetherKey>> commands,
                                         ArtifactBase base,
                                         Version oldVersion,
                                         Version newVersion) {
        var key = VersionRoutingKey.versionRoutingKey(base);
        var value = VersionRoutingValue.versionRoutingValue(oldVersion, newVersion);
        commands.add(new KVCommand.Put<>(key, value));
    }

    private void addVersionRoutingWeighted(List<KVCommand<AetherKey>> commands,
                                           ArtifactBase base,
                                           Deployment deployment) {
        var key = VersionRoutingKey.versionRoutingKey(base);
        var routing = deployment.routing();
        var value = VersionRoutingValue.versionRoutingValue(deployment.oldVersion(), deployment.newVersion())
        .withRouting(routing.newWeight(), routing.oldWeight());
        commands.add(new KVCommand.Put<>(key, value));
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private void addDeploymentCommand(List<KVCommand<AetherKey>> commands, Deployment deployment) {
        var key = DeploymentKey.deploymentKey(deployment.deploymentId());
        var artifactsStr = deployment.artifacts().stream()
                                               .map(ArtifactBase::asString)
                                               .reduce((a, b) -> a + "," + b)
                                               .orElse("");
        var value = DeploymentValue.deploymentValue(deployment.deploymentId(),
                                                    deployment.blueprintId(),
                                                    deployment.oldVersion().toString(),
                                                    deployment.newVersion().toString(),
                                                    deployment.strategy().name(),
                                                    deployment.state().name(),
                                                    deployment.routing().toString(),
                                                    deployment.strategyConfig().toString(),
                                                    serializeThresholds(deployment.thresholds()),
                                                    deployment.cleanupPolicy().name(),
                                                    artifactsStr,
                                                    deployment.newInstances(),
                                                    deployment.createdAt(),
                                                    deployment.updatedAt());
        commands.add(new KVCommand.Put<>(key, value));
    }

    private String serializeThresholds(HealthThresholds t) {
        return t.maxErrorRate() + ":" + t.maxLatencyMs() + ":" + t.requireManualApproval();
    }

    // --- Consensus ---
    @SuppressWarnings("JBCT-RET-01")
    private Result<Unit> applyConsensus(List<KVCommand<AetherKey>> commands) {
        if ( commands.isEmpty()) {
        return Result.unitResult();}
        return clusterNode.apply(commands).await()
                                .mapToUnit()
                                .mapError(DeploymentError.ConsensusFailure::consensusFailure);
    }

    // --- Persistence and caching ---
    private Deployment cacheDeployment(Deployment deployment) {
        activeDeployments.put(deployment.deploymentId(), deployment);
        log.info("Deployment {} for blueprint {} in state {}",
                 deployment.deploymentId(),
                 deployment.blueprintId(),
                 deployment.state());
        return deployment;
    }

    private Result<Deployment> findDeployment(String deploymentId) {
        return option(activeDeployments.get(deploymentId)).toResult(deploymentNotFound(deploymentId));
    }

    // --- State restoration ---
    @SuppressWarnings("JBCT-RET-01")
    private void restoreState() {
        var beforeCount = activeDeployments.size();
        kvStore.forEach(DeploymentKey.class, DeploymentValue.class, (_, value) -> restoreDeployment(value));
        var restoredCount = activeDeployments.size() - beforeCount;
        if ( restoredCount > 0) {
        log.info("Restored {} deployments from KV-Store", restoredCount);}
    }

    @SuppressWarnings({"JBCT-VO-02", "JBCT-RET-01"})
    private void restoreDeployment(DeploymentValue dv) {
        var state = DeploymentState.valueOf(dv.state());
        if ( state.isTerminal()) {
        return;}
        var strategy = DeploymentStrategy.valueOf(dv.strategy());
        var routing = VersionRouting.versionRouting(dv.routing());
        var version = Version.version(dv.oldVersion());
        var newVersion = Version.version(dv.newVersion());
        if ( routing.isFailure() || version.isFailure() || newVersion.isFailure()) {
            log.warn("Failed to restore deployment {}: invalid stored data", dv.deploymentId());
            return;
        }
        var artifacts = parseArtifacts(dv.artifacts());
        var thresholds = parseThresholds(dv.thresholds());
        var deployment = new Deployment(dv.deploymentId(),
                                        dv.blueprintId(),
                                        version.unwrap(),
                                        newVersion.unwrap(),
                                        state,
                                        strategy,
                                        parseStrategyConfig(strategy, dv.strategyConfig()),
                                        routing.unwrap(),
                                        thresholds,
                                        CleanupPolicy.valueOf(dv.cleanupPolicy()),
                                        artifacts,
                                        dv.newInstances(),
                                        dv.createdAt(),
                                        dv.updatedAt());
        activeDeployments.put(deployment.deploymentId(), deployment);
    }

    private List<ArtifactBase> parseArtifacts(String artifactsStr) {
        if ( artifactsStr == null || artifactsStr.isEmpty()) {
        return List.of();}
        var results = new ArrayList<ArtifactBase>();
        for ( var part : artifactsStr.split(",")) {
        ArtifactBase.artifactBase(part.trim()).onSuccess(results::add)
                                 .onFailure(cause -> log.warn("Failed to parse artifact '{}': {}",
                                                              part,
                                                              cause.message()));}
        return List.copyOf(results);
    }

    private HealthThresholds parseThresholds(String thresholdsStr) {
        if ( thresholdsStr == null || thresholdsStr.isEmpty()) {
        return HealthThresholds.DEFAULT;}
        var parts = thresholdsStr.split(":");
        if ( parts.length != 3) {
        return HealthThresholds.DEFAULT;}
        return HealthThresholds.healthThresholds(Double.parseDouble(parts[0]),
                                                 Long.parseLong(parts[1]),
                                                 Boolean.parseBoolean(parts[2]))
        .or(HealthThresholds.DEFAULT);
    }

    private StrategyConfig parseStrategyConfig(DeploymentStrategy strategy, String configStr) {
        return switch (strategy) {case CANARY -> new StrategyConfig.CanaryConfig(CanaryStage.defaultStages(),
                                                                                 CanaryAnalysisConfig.DEFAULT);case BLUE_GREEN -> new StrategyConfig.BlueGreenConfig(30_000L);case ROLLING -> new StrategyConfig.RollingConfig(false);};
    }

    // --- Utility ---
    private boolean containsArtifact(Deployment deployment, ArtifactBase artifactBase) {
        return deployment.artifacts().stream()
                                   .anyMatch(a -> a.equals(artifactBase));
    }
}
