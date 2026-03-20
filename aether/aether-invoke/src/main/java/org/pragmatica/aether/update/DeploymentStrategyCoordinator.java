package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.lang.Option;

/// Coordinator that wraps all deployment strategy managers and provides
/// unified routing lookup and mutual exclusion.
///
/// Only one deployment strategy per artifact is allowed at a time.
/// Used by AppHttpServer and SliceInvoker for routing decisions.
public interface DeploymentStrategyCoordinator {
    /// Checks all registered managers for an active strategy on this artifact.
    /// Used for mutual exclusion — only one strategy per artifact at a time.
    ///
    /// @param artifactBase the artifact to check
    /// @return the active strategy if found
    Option<DeploymentStrategy> getAnyActiveStrategy(ArtifactBase artifactBase);

    /// Returns the current routing for an active strategy on this artifact.
    /// Used by AppHttpServer and SliceInvoker for traffic routing decisions.
    ///
    /// @param artifactBase the artifact to query
    /// @return the active routing if found
    Option<VersionRouting> getActiveRouting(ArtifactBase artifactBase);

    /// Returns the full active strategy for cases where the caller needs
    /// old/new version info alongside routing. Only returns non-terminal active strategies.
    ///
    /// @param artifactBase the artifact to query
    /// @return the active strategy with routing if found
    Option<DeploymentStrategy> getActiveStrategyWithRouting(ArtifactBase artifactBase);

    /// Factory method with rolling update manager only.
    static DeploymentStrategyCoordinator deploymentStrategyCoordinator(RollingUpdateManager rollingUpdateManager) {
        return deploymentStrategyCoordinator(rollingUpdateManager, Option.none(), Option.none());
    }

    /// Factory method with rolling update and canary deployment managers.
    static DeploymentStrategyCoordinator deploymentStrategyCoordinator(RollingUpdateManager rollingUpdateManager,
                                                                       Option<CanaryDeploymentManager> canaryManager) {
        return deploymentStrategyCoordinator(rollingUpdateManager, canaryManager, Option.none());
    }

    /// Factory method with three deployment strategy managers.
    static DeploymentStrategyCoordinator deploymentStrategyCoordinator(RollingUpdateManager rollingUpdateManager,
                                                                       Option<CanaryDeploymentManager> canaryManager,
                                                                       Option<BlueGreenDeploymentManager> blueGreenManager) {
        return deploymentStrategyCoordinator(rollingUpdateManager, canaryManager, blueGreenManager, Option.none());
    }

    /// Factory method with all four deployment strategy managers.
    static DeploymentStrategyCoordinator deploymentStrategyCoordinator(RollingUpdateManager rollingUpdateManager,
                                                                       Option<CanaryDeploymentManager> canaryManager,
                                                                       Option<BlueGreenDeploymentManager> blueGreenManager,
                                                                       Option<ABTestManager> abTestManager) {
        record deploymentStrategyCoordinator(RollingUpdateManager rollingUpdateManager,
                                             Option<CanaryDeploymentManager> canaryManager,
                                             Option<BlueGreenDeploymentManager> blueGreenManager,
                                             Option<ABTestManager> abTestManager)
        implements DeploymentStrategyCoordinator {
            @Override
            public Option<DeploymentStrategy> getAnyActiveStrategy(ArtifactBase artifactBase) {
                var rolling = rollingUpdateManager.getActiveUpdate(artifactBase)
                                                  .map(DeploymentStrategy.class::cast);
                if (rolling.isPresent()) {
                    return rolling;
                }
                var canary = canaryManager.flatMap(cm -> cm.getActiveCanary(artifactBase))
                                          .map(DeploymentStrategy.class::cast);
                if (canary.isPresent()) {
                    return canary;
                }
                var blueGreen = blueGreenManager.flatMap(bg -> bg.getActiveDeployment(artifactBase))
                                                .map(DeploymentStrategy.class::cast);
                if (blueGreen.isPresent()) {
                    return blueGreen;
                }
                return abTestManager.flatMap(ab -> ab.getActiveTest(artifactBase))
                                    .map(DeploymentStrategy.class::cast);
            }

            @Override
            public Option<VersionRouting> getActiveRouting(ArtifactBase artifactBase) {
                return getAnyActiveStrategy(artifactBase).map(DeploymentStrategy::routing);
            }

            @Override
            public Option<DeploymentStrategy> getActiveStrategyWithRouting(ArtifactBase artifactBase) {
                var rolling = rollingUpdateManager.getActiveUpdate(artifactBase)
                                                  .filter(RollingUpdate::isActive)
                                                  .map(DeploymentStrategy.class::cast);
                if (rolling.isPresent()) {
                    return rolling;
                }
                var canary = canaryManager.flatMap(cm -> cm.getActiveCanary(artifactBase))
                                          .filter(CanaryDeployment::isActive)
                                          .map(DeploymentStrategy.class::cast);
                if (canary.isPresent()) {
                    return canary;
                }
                var blueGreen = blueGreenManager.flatMap(bg -> bg.getActiveDeployment(artifactBase))
                                                .filter(BlueGreenDeployment::isActive)
                                                .map(DeploymentStrategy.class::cast);
                if (blueGreen.isPresent()) {
                    return blueGreen;
                }
                return abTestManager.flatMap(ab -> ab.getActiveTest(artifactBase))
                                    .filter(ABTestDeployment::isActive)
                                    .map(DeploymentStrategy.class::cast);
            }
        }
        return new deploymentStrategyCoordinator(rollingUpdateManager, canaryManager, blueGreenManager, abTestManager);
    }
}
