package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.Map;

/// Represents an A/B testing deployment operation.
///
///
/// An A/B test deploys multiple variant versions alongside a baseline and routes
/// traffic deterministically using a configurable split rule. Unlike canary deployments,
/// A/B tests run until manually concluded with a declared winning variant.
///
///
/// Immutable record - state changes create new instances.
///
/// @param testId unique identifier for this A/B test
/// @param artifactBase the artifact being tested (version-agnostic)
/// @param baselineVersion current baseline version
/// @param variantVersions mapping of variant names to their versions
/// @param state current state of the A/B test
/// @param splitRule traffic routing rule for variant selection
/// @param routing current traffic routing configuration
/// @param blueprintId optional blueprint identifier for deployment context
/// @param artifacts list of artifacts involved in this deployment
/// @param createdAt timestamp when deployment was created
/// @param updatedAt timestamp of last state change
public record AbTestDeployment( String testId,
                                ArtifactBase artifactBase,
                                Version baselineVersion,
                                Map<String, Version> variantVersions,
                                AbTestState state,
                                SplitRule splitRule,
                                VersionRouting routing,
                                Option<String> blueprintId,
                                List<ArtifactBase> artifacts,
                                long createdAt,
                                long updatedAt) {
    // TODO: A/B testing uses its own mechanism separate from the unified Deployment model.
    //       The old DeploymentStrategy sealed interface has been replaced by DeploymentStrategy enum.
    private static final Fn1<Cause, String> INVALID_TRANSITION = Causes.forOneValue("Invalid A/B test state transition: %s");

    /// Creates a new A/B test deployment in PENDING state.
    ///
    /// @param testId unique identifier
    /// @param artifactBase artifact being tested
    /// @param baselineVersion current baseline version
    /// @param variantVersions variant name to version mapping
    /// @param splitRule traffic routing rule
    /// @return new A/B test deployment
    @SuppressWarnings("JBCT-VO-02") // Factory method - validated construction
    public static// Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    // Factory method - validated construction
    AbTestDeployment abTestDeployment(String testId,
                                      ArtifactBase artifactBase,
                                      Version baselineVersion,
                                      Map<String, Version> variantVersions,
                                      SplitRule splitRule) {
        var now = System.currentTimeMillis();
        return new AbTestDeployment(testId,
                                    artifactBase,
                                    baselineVersion,
                                    Map.copyOf(variantVersions),
                                    AbTestState.PENDING,
                                    splitRule,
                                    VersionRouting.ALL_OLD,
                                    Option.none(),
                                    List.of(artifactBase),
                                    now,
                                    now);
    }

    /// Transitions to a new state.
    ///
    /// @param newState the new state
    /// @return updated A/B test deployment, or failure if transition is invalid
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
    Result<AbTestDeployment> transitionTo(AbTestState newState) {
        if ( !state.validTransitions().contains(newState)) {
        return INVALID_TRANSITION.apply(state + " -> " + newState).result();}
        return Result.success(new AbTestDeployment(testId,
                                                   artifactBase,
                                                   baselineVersion,
                                                   variantVersions,
                                                   newState,
                                                   splitRule,
                                                   routing,
                                                   blueprintId,
                                                   artifacts,
                                                   createdAt,
                                                   System.currentTimeMillis()));
    }

    /// Updates the traffic routing.
    ///
    /// @param newRouting the new routing configuration
    /// @return updated A/B test deployment
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
    AbTestDeployment withRouting(VersionRouting newRouting) {
        return new AbTestDeployment(testId,
                                    artifactBase,
                                    baselineVersion,
                                    variantVersions,
                                    state,
                                    splitRule,
                                    newRouting,
                                    blueprintId,
                                    artifacts,
                                    createdAt,
                                    System.currentTimeMillis());
    }

    /// Sets the blueprint context for this deployment.
    ///
    /// @param newBlueprintId the blueprint identifier
    /// @param newArtifacts the artifacts involved in this deployment
    /// @return updated A/B test deployment with blueprint context
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
    AbTestDeployment withBlueprintContext(String newBlueprintId, List<ArtifactBase> newArtifacts) {
        return new AbTestDeployment(testId,
                                    artifactBase,
                                    baselineVersion,
                                    variantVersions,
                                    state,
                                    splitRule,
                                    routing,
                                    Option.some(newBlueprintId),
                                    List.copyOf(newArtifacts),
                                    createdAt,
                                    System.currentTimeMillis());
    }

    public String strategyId() {
        return testId;
    }

    /// Returns the baseline version (old version being compared against).
    public Version oldVersion() {
        return baselineVersion;
    }

    /// Returns the first variant version, or baseline if no variants exist.
    public Version newVersion() {
        return Option.from(variantVersions.values().stream()
                                                 .findFirst()).or(baselineVersion);
    }

    /// Checks if this deployment is in a terminal state.
    public boolean isTerminal() {
        return state.isTerminal();
    }

    /// Checks if this deployment is active (not terminal).
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
}
