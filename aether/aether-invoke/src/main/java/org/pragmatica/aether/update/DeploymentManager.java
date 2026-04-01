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
    /// Start a new deployment for a blueprint.
    ///
    /// Resolves the blueprint from KV-Store, reads current version for each slice,
    /// creates SliceTarget and VersionRouting entries for all slices, and persists
    /// the deployment state — all atomically via a single consensus batch.
    ///
    /// @param blueprintId blueprint to deploy (e.g., "org.example:my-app:1.0")
    /// @param newVersion target version for all slices
    /// @param strategy deployment strategy (CANARY, BLUE_GREEN, ROLLING)
    /// @param config strategy-specific configuration
    /// @param thresholds health thresholds for auto-progression
    /// @param cleanupPolicy how to handle old version cleanup
    /// @param instances target number of new version instances per slice
    /// @return the created deployment, or failure
    Result<Deployment> start(String blueprintId,
                             Version newVersion,
                             DeploymentStrategy strategy,
                             StrategyConfig config,
                             HealthThresholds thresholds,
                             CleanupPolicy cleanupPolicy,
                             int instances);

    /// Promote a deployment to the next stage.
    ///
    /// For CANARY: advance to next traffic stage (update VersionRouting weights for ALL slices).
    /// For BLUE_GREEN: switch all traffic to new version (ALL_NEW for ALL slices).
    /// For ROLLING: shift routing to ALL_NEW for ALL slices.
    ///
    /// @param deploymentId deployment to promote
    /// @return updated deployment, or failure
    Result<Deployment> promote(String deploymentId);

    /// Roll back a deployment to the old version.
    ///
    /// Sets VersionRouting to ALL_OLD for all slices and restores SliceTarget
    /// to old version. All changes applied atomically.
    ///
    /// @param deploymentId deployment to roll back
    /// @return updated deployment, or failure
    Result<Deployment> rollback(String deploymentId);

    /// Complete a deployment after successful promotion.
    ///
    /// Removes VersionRoutingKey entries for all slices (no more dual-version routing),
    /// applies cleanup policy, and transitions to COMPLETED state.
    ///
    /// @param deploymentId deployment to complete
    /// @return updated deployment, or failure
    Result<Deployment> complete(String deploymentId);

    /// Get deployment by ID.
    ///
    /// @param deploymentId deployment to query
    /// @return the deployment if found
    Option<Deployment> status(String deploymentId);

    /// List all active (non-terminal) deployments.
    ///
    /// @return list of active deployments
    List<Deployment> list();

    /// Look up active routing for an artifact.
    ///
    /// Used by SliceInvoker and AppHttpServer for traffic routing decisions
    /// during active deployments.
    ///
    /// @param artifactBase the artifact to query
    /// @return routing info if an active deployment covers this artifact
    Option<ActiveRouting> activeRouting(ArtifactBase artifactBase);

    /// Routing information for an active deployment covering a specific artifact.
    record ActiveRouting(VersionRouting routing, Version oldVersion, Version newVersion){}

    /// Handle leader change — become active or dormant based on leadership.
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01") void onLeaderChange(LeaderChange leaderChange);

    /// Factory method following JBCT naming convention.
    static DeploymentManager deploymentManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                               KVStore<AetherKey, AetherValue> kvStore) {
        return new DeploymentManagerImpl(clusterNode, kvStore);
    }
}
