package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;

/// Represents a canary deployment operation.
///
///
/// A canary deployment transitions an artifact from one version to another
/// using a multi-stage progressive traffic shifting model:
/// <ol>
///   - **Deploy stage**: Canary instances deployed with 0% traffic
///   - **Canary stages**: Traffic progressively shifted through configured stages
///   - **Promote stage**: Full traffic shifted to new version
/// </ol>
///
///
/// Each stage defines a traffic percentage and observation period. The canary
/// advances through stages automatically if health thresholds are met, or
/// rolls back automatically if metrics degrade beyond configured thresholds.
///
///
/// Immutable record - state changes create new instances.
///
/// @param canaryId unique identifier for this canary deployment
/// @param artifactBase the artifact being updated (version-agnostic)
/// @param oldVersion current version being replaced
/// @param newVersion new version being deployed
/// @param state current state of the canary deployment
/// @param stages ordered list of canary stages defining traffic progression
/// @param currentStageIndex index of the current stage in the stages list
/// @param stageEnteredAt timestamp when the current stage was entered
/// @param thresholds health thresholds for auto-progression and auto-rollback
/// @param analysisConfig canary analysis configuration for health evaluation
/// @param newInstances target number of new version instances
/// @param cleanupPolicy how to handle old version cleanup
/// @param routing current traffic routing configuration
/// @param blueprintId optional blueprint identifier for deployment context
/// @param artifacts list of artifacts involved in this deployment
/// @param createdAt timestamp when deployment was created
/// @param updatedAt timestamp of last state change
public record CanaryDeployment( String canaryId,
                                ArtifactBase artifactBase,
                                Version oldVersion,
                                Version newVersion,
                                CanaryState state,
                                List<CanaryStage> stages,
                                int currentStageIndex,
                                long stageEnteredAt,
                                HealthThresholds thresholds,
                                CanaryAnalysisConfig analysisConfig,
                                int newInstances,
                                CleanupPolicy cleanupPolicy,
                                VersionRouting routing,
                                Option<String> blueprintId,
                                List<ArtifactBase> artifacts,
                                long createdAt,
                                long updatedAt) implements DeploymentStrategy {
    private static final Fn1<Cause, String> INVALID_TRANSITION = Causes.forOneValue("Invalid state transition: %s");
    private static final Cause NO_MORE_STAGES = Causes.cause("No more stages to advance to");

    /// Creates a new canary deployment in PENDING state.
    ///
    /// @param canaryId unique identifier
    /// @param artifactBase artifact being updated
    /// @param oldVersion current version
    /// @param newVersion new version
    /// @param newInstances target instance count for canary version
    /// @param stages ordered list of canary stages
    /// @param thresholds health thresholds
    /// @param analysisConfig canary analysis configuration
    /// @param cleanupPolicy cleanup policy
    /// @return new canary deployment
    @SuppressWarnings("JBCT-VO-02") // Factory method — validated construction
    public static// Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    // Factory method — validated construction
    CanaryDeployment canaryDeployment(String canaryId,
                                      ArtifactBase artifactBase,
                                      Version oldVersion,
                                      Version newVersion,
                                      int newInstances,
                                      List<CanaryStage> stages,
                                      HealthThresholds thresholds,
                                      CanaryAnalysisConfig analysisConfig,
                                      CleanupPolicy cleanupPolicy) {
        var now = System.currentTimeMillis();
        return new CanaryDeployment(canaryId,
                                    artifactBase,
                                    oldVersion,
                                    newVersion,
                                    CanaryState.PENDING,
                                    List.copyOf(stages),
                                    0,
                                    now,
                                    thresholds,
                                    analysisConfig,
                                    newInstances,
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
    /// @return updated canary deployment, or failure if transition is invalid
    @SuppressWarnings("JBCT-VO-02") // Record copy method with validated state transition
    public// Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    // Record copy method with validated state transition
    Result<CanaryDeployment> transitionTo(CanaryState newState) {
        if ( !state.validTransitions().contains(newState)) {
        return INVALID_TRANSITION.apply(state + " -> " + newState).result();}
        return Result.success(new CanaryDeployment(canaryId,
                                                   artifactBase,
                                                   oldVersion,
                                                   newVersion,
                                                   newState,
                                                   stages,
                                                   currentStageIndex,
                                                   stageEnteredAt,
                                                   thresholds,
                                                   analysisConfig,
                                                   newInstances,
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
    /// @return updated canary deployment
    @SuppressWarnings("JBCT-VO-02") // Record copy method with known-valid fields
    public// Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    CanaryDeployment withRouting(VersionRouting newRouting) {
        return new CanaryDeployment(canaryId,
                                    artifactBase,
                                    oldVersion,
                                    newVersion,
                                    state,
                                    stages,
                                    currentStageIndex,
                                    stageEnteredAt,
                                    thresholds,
                                    analysisConfig,
                                    newInstances,
                                    cleanupPolicy,
                                    newRouting,
                                    blueprintId,
                                    artifacts,
                                    createdAt,
                                    System.currentTimeMillis());
    }

    /// Advances to the next canary stage, updating routing and resetting observation timer.
    ///
    /// @return updated canary deployment at the next stage, or failure if no more stages
    @SuppressWarnings("JBCT-VO-02") // Record copy method with validated stage advancement
    public// Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    // Record copy method with validated stage advancement
    Result<CanaryDeployment> advanceStage() {
        if ( isLastStage()) {
        return NO_MORE_STAGES.result();}
        var nextIndex = currentStageIndex + 1;
        var nextRouting = stages.get(nextIndex).toRouting();
        return Result.success(new CanaryDeployment(canaryId,
                                                   artifactBase,
                                                   oldVersion,
                                                   newVersion,
                                                   state,
                                                   stages,
                                                   nextIndex,
                                                   System.currentTimeMillis(),
                                                   thresholds,
                                                   analysisConfig,
                                                   newInstances,
                                                   cleanupPolicy,
                                                   nextRouting,
                                                   blueprintId,
                                                   artifacts,
                                                   createdAt,
                                                   System.currentTimeMillis()));
    }

    /// Returns the current canary stage, if the index is valid.
    public Option<CanaryStage> currentStage() {
        if ( currentStageIndex >= 0 && currentStageIndex < stages.size()) {
        return Option.some(stages.get(currentStageIndex));}
        return Option.none();
    }

    @Override public String strategyId() {
        return canaryId;
    }

    /// Checks if this deployment is in a terminal state.
    @Override public boolean isTerminal() {
        return state.isTerminal();
    }

    /// Checks if this deployment is active (not terminal).
    @Override public boolean isActive() {
        return ! isTerminal();
    }

    /// Checks if the current stage observation period has been exceeded.
    public boolean hasExceededObservation() {
        return currentStage().map(stage -> System.currentTimeMillis() - stageEnteredAt > stage.observationMinutes() * 60_000L)
                           .or(false);
    }

    /// Checks if the current stage is the last stage.
    public boolean isLastStage() {
        return currentStageIndex >= stages.size() - 1;
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
    /// @return updated canary deployment with blueprint context
    @SuppressWarnings("JBCT-VO-02") // Record copy method with known-valid fields
    public// Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    // Record copy method with known-valid fields
    CanaryDeployment withBlueprintContext(String newBlueprintId, List<ArtifactBase> newArtifacts) {
        return new CanaryDeployment(canaryId,
                                    artifactBase,
                                    oldVersion,
                                    newVersion,
                                    state,
                                    stages,
                                    currentStageIndex,
                                    stageEnteredAt,
                                    thresholds,
                                    analysisConfig,
                                    newInstances,
                                    cleanupPolicy,
                                    routing,
                                    Option.some(newBlueprintId),
                                    List.copyOf(newArtifacts),
                                    createdAt,
                                    System.currentTimeMillis());
    }
}
