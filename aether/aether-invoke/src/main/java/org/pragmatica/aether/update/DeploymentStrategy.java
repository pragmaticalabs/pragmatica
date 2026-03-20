package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;

/// Common interface for all deployment strategies (rolling, canary, blue-green, A/B).
///
/// Each strategy manages the transition from one artifact version to another,
/// controlling traffic routing and lifecycle state.
public sealed interface DeploymentStrategy permits RollingUpdate, CanaryDeployment, BlueGreenDeployment, ABTestDeployment {
    /// Unique identifier for this deployment.
    String strategyId();

    /// The artifact being updated (version-agnostic).
    ArtifactBase artifactBase();

    /// Current version being replaced.
    Version oldVersion();

    /// Version being deployed.
    Version newVersion();

    /// Whether deployment is in a terminal state.
    boolean isTerminal();

    /// Whether deployment is active (not terminal).
    default boolean isActive() {
        return ! isTerminal();
    }

    /// Current routing configuration.
    VersionRouting routing();
}
