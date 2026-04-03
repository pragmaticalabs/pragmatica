package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.messaging.MessageReceiver;

import java.util.List;


/// Unified deployment manager that operates at blueprint level.
///
/// Replaces the per-strategy managers (RollingUpdateManager, CanaryDeploymentManager,
/// BlueGreenDeploymentManager) and DeploymentStrategyCoordinator with a single interface.
///
/// All deployments operate on blueprints — when a blueprint is deployed with a new version,
/// ALL slices in the blueprint transition atomically through the configured strategy.
///
/// State is persisted in KV-Store via DeploymentKey/DeploymentValue, VersionRoutingKey/Value,
/// and SliceTargetKey/Value entries. All mutations go through consensus for cluster-wide consistency.
public interface DeploymentManager {
    Result<Deployment> start(String blueprintId,
                             Version newVersion,
                             DeploymentStrategy strategy,
                             StrategyConfig config,
                             HealthThresholds thresholds,
                             CleanupPolicy cleanupPolicy,
                             int instances);
    Result<Deployment> promote(String deploymentId);
    Result<Deployment> rollback(String deploymentId);
    Result<Deployment> complete(String deploymentId);
    Option<Deployment> status(String deploymentId);
    List<Deployment> list();
    Option<ActiveRouting> activeRouting(ArtifactBase artifactBase);

    record ActiveRouting(VersionRouting routing, Version oldVersion, Version newVersion){}

    @MessageReceiver@SuppressWarnings("JBCT-RET-01") void onLeaderChange(LeaderChange leaderChange);

    static DeploymentManager deploymentManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                               KVStore<AetherKey, AetherValue> kvStore) {
        return new DeploymentManagerImpl(clusterNode, kvStore);
    }
}
