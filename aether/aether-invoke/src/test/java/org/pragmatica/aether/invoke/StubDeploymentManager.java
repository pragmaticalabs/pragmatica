package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.update.CleanupPolicy;
import org.pragmatica.aether.update.Deployment;
import org.pragmatica.aether.update.DeploymentError;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.aether.update.DeploymentStrategy;
import org.pragmatica.aether.update.HealthThresholds;
import org.pragmatica.aether.update.StrategyConfig;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.List;

/// Minimal stub for DeploymentManager used in unit tests.
@SuppressWarnings("JBCT-RET-01")
class StubDeploymentManager implements DeploymentManager {
    @Override
    public Result<Deployment> start(String blueprintId,
                                    Version newVersion,
                                    DeploymentStrategy strategy,
                                    StrategyConfig config,
                                    HealthThresholds thresholds,
                                    CleanupPolicy cleanupPolicy,
                                    int instances) {
        return DeploymentError.General.NOT_LEADER.result();
    }

    @Override
    public Result<Deployment> promote(String deploymentId) {
        return DeploymentError.General.NOT_LEADER.result();
    }

    @Override
    public Result<Deployment> rollback(String deploymentId) {
        return DeploymentError.General.NOT_LEADER.result();
    }

    @Override
    public Result<Deployment> complete(String deploymentId) {
        return DeploymentError.General.NOT_LEADER.result();
    }

    @Override
    public Option<Deployment> status(String deploymentId) {
        return Option.none();
    }

    @Override
    public List<Deployment> list() {
        return List.of();
    }

    @Override
    public Option<ActiveRouting> activeRouting(ArtifactBase artifactBase) {
        return Option.none();
    }

    @Override
    public void onLeaderChange(LeaderChange leaderChange) {}
}
