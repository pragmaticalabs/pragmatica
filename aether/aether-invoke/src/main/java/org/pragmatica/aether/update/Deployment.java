package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;

/// Unified deployment record for blueprint-level version transitions.
///
/// Replaces the per-strategy deployment records (CanaryDeployment, BlueGreenDeployment,
/// RollingUpdate) with a single unified model. Strategy-specific behavior is captured
/// in the StrategyConfig sealed interface.
///
/// Immutable record - state changes create new instances via transition methods.
///
/// @param deploymentId unique identifier for this deployment
/// @param blueprintId blueprint identifier (e.g., "org.example:my-app:1.0")
/// @param oldVersion version being replaced
/// @param newVersion version being deployed
/// @param state current lifecycle state
/// @param strategy which deployment strategy to use
/// @param strategyConfig strategy-specific configuration
/// @param routing current traffic routing between old and new versions
/// @param thresholds health thresholds for auto-progression
/// @param cleanupPolicy how to handle old version cleanup
/// @param artifacts all slices in the blueprint
/// @param newInstances target number of new version instances
/// @param createdAt timestamp when deployment was created
/// @param updatedAt timestamp of last state change
public record Deployment( String deploymentId,
                          String blueprintId,
                          Version oldVersion,
                          Version newVersion,
                          DeploymentState state,
                          DeploymentStrategy strategy,
                          StrategyConfig strategyConfig,
                          VersionRouting routing,
                          HealthThresholds thresholds,
                          CleanupPolicy cleanupPolicy,
                          List<ArtifactBase> artifacts,
                          int newInstances,
                          long createdAt,
                          long updatedAt) {
    private static final Fn1<Cause, String> INVALID_TRANSITION = Causes.forOneValue("Invalid deployment state transition: %s");

    /// Creates a new deployment in PENDING state.
    @SuppressWarnings("JBCT-VO-02")
    public static Deployment deployment(String deploymentId,
                                        String blueprintId,
                                        Version oldVersion,
                                        Version newVersion,
                                        DeploymentStrategy strategy,
                                        StrategyConfig strategyConfig,
                                        HealthThresholds thresholds,
                                        CleanupPolicy cleanupPolicy,
                                        List<ArtifactBase> artifacts,
                                        int newInstances) {
        var now = System.currentTimeMillis();
        return new Deployment(deploymentId,
                              blueprintId,
                              oldVersion,
                              newVersion,
                              DeploymentState.PENDING,
                              strategy,
                              strategyConfig,
                              VersionRouting.ALL_OLD,
                              thresholds,
                              cleanupPolicy,
                              List.copyOf(artifacts),
                              newInstances,
                              now,
                              now);
    }

    /// Transitions to DEPLOYING state.
    public Result<Deployment> deploy() {
        return transitionTo(DeploymentState.DEPLOYING);
    }

    /// Transitions to ROUTING state with updated routing.
    public Result<Deployment> route(VersionRouting newRouting) {
        return transitionTo(DeploymentState.ROUTING).map(d -> d.withRouting(newRouting));
    }

    /// Transitions to PROMOTING state.
    public Result<Deployment> promote() {
        return transitionTo(DeploymentState.PROMOTING);
    }

    /// Transitions to ROLLING_BACK state.
    public Result<Deployment> rollback() {
        return transitionTo(DeploymentState.ROLLING_BACK);
    }

    /// Transitions to COMPLETED state.
    public Result<Deployment> complete() {
        return transitionTo(DeploymentState.COMPLETED);
    }

    /// Transitions to FAILED state.
    public Result<Deployment> fail() {
        return transitionTo(DeploymentState.FAILED);
    }

    /// Checks if this deployment is in a terminal state.
    public boolean isTerminal() {
        return state.isTerminal();
    }

    /// Checks if this deployment is active (not terminal).
    public boolean isActive() {
        return state.isActive();
    }

    /// Returns time since creation in milliseconds.
    public long age() {
        return System.currentTimeMillis() - createdAt;
    }

    /// Returns time since last update in milliseconds.
    public long timeSinceUpdate() {
        return System.currentTimeMillis() - updatedAt;
    }

    /// Transitions to the specified state, validating the transition is legal.
    @SuppressWarnings("JBCT-VO-02")
    private Result<Deployment> transitionTo(DeploymentState newState) {
        if ( !state.validTransitions().contains(newState)) {
        return INVALID_TRANSITION.apply(state + " -> " + newState).result();}
        return Result.success(new Deployment(deploymentId,
                                             blueprintId,
                                             oldVersion,
                                             newVersion,
                                             newState,
                                             strategy,
                                             strategyConfig,
                                             routing,
                                             thresholds,
                                             cleanupPolicy,
                                             artifacts,
                                             newInstances,
                                             createdAt,
                                             System.currentTimeMillis()));
    }

    /// Returns a copy with updated routing configuration.
    @SuppressWarnings("JBCT-VO-02")
    private Deployment withRouting(VersionRouting newRouting) {
        return new Deployment(deploymentId,
                              blueprintId,
                              oldVersion,
                              newVersion,
                              state,
                              strategy,
                              strategyConfig,
                              newRouting,
                              thresholds,
                              cleanupPolicy,
                              artifacts,
                              newInstances,
                              createdAt,
                              updatedAt);
    }
}
