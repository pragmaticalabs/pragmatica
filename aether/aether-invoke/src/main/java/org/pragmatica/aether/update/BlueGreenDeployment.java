package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;

/// Represents a blue-green deployment operation.
///
///
/// A blue-green deployment maintains two identical environments (blue and green)
/// and performs an atomic traffic switch between them:
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
/// Immutable record - state changes create new instances.
///
/// @param deploymentId unique identifier for this blue-green deployment
/// @param artifactBase the artifact being updated (version-agnostic)
/// @param blueVersion current (blue) version being replaced
/// @param greenVersion new (green) version being deployed
/// @param state current state of the blue-green deployment
/// @param activeEnvironment which environment is currently receiving traffic
/// @param blueInstances number of blue environment instances
/// @param greenInstances number of green environment instances
/// @param drainTimeoutMs maximum time to wait for blue environment drain
/// @param healthThresholds health thresholds for green environment validation
/// @param cleanupPolicy how to handle blue environment cleanup after switch
/// @param routing current traffic routing configuration
/// @param blueprintId optional blueprint identifier for deployment context
/// @param artifacts list of artifacts involved in this deployment
/// @param createdAt timestamp when deployment was created
/// @param updatedAt timestamp of last state change
public record BlueGreenDeployment(String deploymentId,
                                  ArtifactBase artifactBase,
                                  Version blueVersion,
                                  Version greenVersion,
                                  BlueGreenState state,
                                  ActiveEnvironment activeEnvironment,
                                  int blueInstances,
                                  int greenInstances,
                                  long drainTimeoutMs,
                                  HealthThresholds healthThresholds,
                                  CleanupPolicy cleanupPolicy,
                                  VersionRouting routing,
                                  Option<String> blueprintId,
                                  List<ArtifactBase> artifacts,
                                  long createdAt,
                                  long updatedAt) implements DeploymentStrategy {
    private static final Fn1<Cause, String> INVALID_TRANSITION = Causes.forOneValue("Invalid state transition: %s");

    /// Which environment is currently receiving traffic.
    public enum ActiveEnvironment {
        BLUE,
        GREEN
    }

    /// Creates a new blue-green deployment in PENDING state with BLUE active.
    ///
    /// @param deploymentId unique identifier
    /// @param artifactBase artifact being updated
    /// @param blueVersion current (blue) version
    /// @param greenVersion new (green) version
    /// @param blueInstances number of blue instances
    /// @param greenInstances number of green instances
    /// @param drainTimeoutMs drain timeout in milliseconds
    /// @param healthThresholds health thresholds for green validation
    /// @param cleanupPolicy cleanup policy for blue environment
    /// @return new blue-green deployment
    @SuppressWarnings("JBCT-VO-02") // Factory method - validated construction
    public static BlueGreenDeployment blueGreenDeployment(String deploymentId,
                                                          ArtifactBase artifactBase,
                                                          Version blueVersion,
                                                          Version greenVersion,
                                                          int blueInstances,
                                                          int greenInstances,
                                                          long drainTimeoutMs,
                                                          HealthThresholds healthThresholds,
                                                          CleanupPolicy cleanupPolicy) {
        var now = System.currentTimeMillis();
        return new BlueGreenDeployment(deploymentId,
                                       artifactBase,
                                       blueVersion,
                                       greenVersion,
                                       BlueGreenState.PENDING,
                                       ActiveEnvironment.BLUE,
                                       blueInstances,
                                       greenInstances,
                                       drainTimeoutMs,
                                       healthThresholds,
                                       cleanupPolicy,
                                       VersionRouting.ALL_OLD,
                                       Option.none(),
                                       List.of(artifactBase),
                                       now,
                                       now);
    }

    /// Transitions to a new state.
    ///
    /// @param newState the new state
    /// @return updated blue-green deployment, or failure if transition is invalid
    @SuppressWarnings("JBCT-VO-02") // Record copy method with validated state transition
    public Result<BlueGreenDeployment> transitionTo(BlueGreenState newState) {
        if (!state.validTransitions()
                  .contains(newState)) {
            return INVALID_TRANSITION.apply(state + " -> " + newState)
                                     .result();
        }
        return Result.success(new BlueGreenDeployment(deploymentId,
                                                      artifactBase,
                                                      blueVersion,
                                                      greenVersion,
                                                      newState,
                                                      activeEnvironment,
                                                      blueInstances,
                                                      greenInstances,
                                                      drainTimeoutMs,
                                                      healthThresholds,
                                                      cleanupPolicy,
                                                      routing,
                                                      blueprintId,
                                                      artifacts,
                                                      createdAt,
                                                      System.currentTimeMillis()));
    }

    /// Updates the traffic routing.
    ///
    /// @param newRouting the new routing configuration
    /// @return updated blue-green deployment
    @SuppressWarnings("JBCT-VO-02") // Record copy method with known-valid fields
    public BlueGreenDeployment withRouting(VersionRouting newRouting) {
        return new BlueGreenDeployment(deploymentId,
                                       artifactBase,
                                       blueVersion,
                                       greenVersion,
                                       state,
                                       activeEnvironment,
                                       blueInstances,
                                       greenInstances,
                                       drainTimeoutMs,
                                       healthThresholds,
                                       cleanupPolicy,
                                       newRouting,
                                       blueprintId,
                                       artifacts,
                                       createdAt,
                                       System.currentTimeMillis());
    }

    /// Switches traffic to the green environment.
    ///
    /// @return updated deployment with green active and all traffic routed to new version
    @SuppressWarnings("JBCT-VO-02") // Record copy method with known-valid fields
    public BlueGreenDeployment switchToGreen() {
        return new BlueGreenDeployment(deploymentId,
                                       artifactBase,
                                       blueVersion,
                                       greenVersion,
                                       state,
                                       ActiveEnvironment.GREEN,
                                       blueInstances,
                                       greenInstances,
                                       drainTimeoutMs,
                                       healthThresholds,
                                       cleanupPolicy,
                                       VersionRouting.ALL_NEW,
                                       blueprintId,
                                       artifacts,
                                       createdAt,
                                       System.currentTimeMillis());
    }

    /// Switches traffic back to the blue environment (rollback).
    ///
    /// @return updated deployment with blue active and all traffic routed to old version
    @SuppressWarnings("JBCT-VO-02") // Record copy method with known-valid fields
    public BlueGreenDeployment switchBack() {
        return new BlueGreenDeployment(deploymentId,
                                       artifactBase,
                                       blueVersion,
                                       greenVersion,
                                       state,
                                       ActiveEnvironment.BLUE,
                                       blueInstances,
                                       greenInstances,
                                       drainTimeoutMs,
                                       healthThresholds,
                                       cleanupPolicy,
                                       VersionRouting.ALL_OLD,
                                       blueprintId,
                                       artifacts,
                                       createdAt,
                                       System.currentTimeMillis());
    }

    @Override
    public String strategyId() {
        return deploymentId;
    }

    /// Returns the blue (old) version.
    @Override
    public Version oldVersion() {
        return blueVersion;
    }

    /// Returns the green (new) version.
    @Override
    public Version newVersion() {
        return greenVersion;
    }

    /// Checks if this deployment is in a terminal state.
    @Override
    public boolean isTerminal() {
        return state.isTerminal();
    }

    /// Checks if this deployment is active (not terminal).
    @Override
    public boolean isActive() {
        return ! isTerminal();
    }

    /// Returns time since creation in milliseconds.
    public long age() {
        return System.currentTimeMillis() - createdAt;
    }

    /// Returns time since last update in milliseconds.
    public long timeSinceUpdate() {
        return System.currentTimeMillis() - updatedAt;
    }

    /// Sets the blueprint context for this deployment.
    ///
    /// @param newBlueprintId the blueprint identifier
    /// @param newArtifacts the artifacts involved in this deployment
    /// @return updated blue-green deployment with blueprint context
    @SuppressWarnings("JBCT-VO-02") // Record copy method with known-valid fields
    public BlueGreenDeployment withBlueprintContext(String newBlueprintId, List<ArtifactBase> newArtifacts) {
        return new BlueGreenDeployment(deploymentId,
                                       artifactBase,
                                       blueVersion,
                                       greenVersion,
                                       state,
                                       activeEnvironment,
                                       blueInstances,
                                       greenInstances,
                                       drainTimeoutMs,
                                       healthThresholds,
                                       cleanupPolicy,
                                       routing,
                                       Option.some(newBlueprintId),
                                       List.copyOf(newArtifacts),
                                       createdAt,
                                       System.currentTimeMillis());
    }
}
