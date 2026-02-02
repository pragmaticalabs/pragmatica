package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

/**
 * Represents a rolling update operation.
 *
 * <p>A rolling update transitions an artifact from one version to another
 * using a two-stage model:
 * <ol>
 *   <li><b>Deploy stage</b>: New version instances deployed with 0% traffic</li>
 *   <li><b>Route stage</b>: Traffic gradually shifted to new version</li>
 * </ol>
 *
 * <p>Immutable record - state changes create new instances.
 *
 * @param updateId unique identifier for this update
 * @param artifactBase the artifact being updated (version-agnostic)
 * @param oldVersion current version being replaced
 * @param newVersion new version being deployed
 * @param state current state of the update
 * @param routing current traffic routing configuration
 * @param thresholds health thresholds for auto-progression
 * @param cleanupPolicy how to handle old version cleanup
 * @param newInstances target number of new version instances
 * @param createdAt timestamp when update was created
 * @param updatedAt timestamp of last state change
 */
public record RollingUpdate(String updateId,
                            ArtifactBase artifactBase,
                            Version oldVersion,
                            Version newVersion,
                            RollingUpdateState state,
                            VersionRouting routing,
                            HealthThresholds thresholds,
                            CleanupPolicy cleanupPolicy,
                            int newInstances,
                            long createdAt,
                            long updatedAt) {
    private static final Fn1<Cause, String> INVALID_TRANSITION = Causes.forOneValue("Invalid state transition: {}");

    /**
     * Creates a new rolling update in PENDING state.
     *
     * @param updateId unique identifier
     * @param artifactBase artifact being updated
     * @param oldVersion current version
     * @param newVersion new version
     * @param newInstances target instance count for new version
     * @param thresholds health thresholds
     * @param cleanupPolicy cleanup policy
     * @return new rolling update
     */
    public static RollingUpdate rollingUpdate(String updateId,
                                              ArtifactBase artifactBase,
                                              Version oldVersion,
                                              Version newVersion,
                                              int newInstances,
                                              HealthThresholds thresholds,
                                              CleanupPolicy cleanupPolicy) {
        var now = System.currentTimeMillis();
        return new RollingUpdate(updateId,
                                 artifactBase,
                                 oldVersion,
                                 newVersion,
                                 RollingUpdateState.PENDING,
                                 VersionRouting.ALL_OLD,
                                 thresholds,
                                 cleanupPolicy,
                                 newInstances,
                                 now,
                                 now);
    }

    /**
     * Transitions to a new state.
     *
     * @param newState the new state
     * @return updated rolling update, or failure if transition is invalid
     */
    public Result<RollingUpdate> transitionTo(RollingUpdateState newState) {
        if (!state.validTransitions()
                  .contains(newState)) {
            return INVALID_TRANSITION.apply(state + " -> " + newState)
                                     .result();
        }
        return Result.success(new RollingUpdate(updateId,
                                                artifactBase,
                                                oldVersion,
                                                newVersion,
                                                newState,
                                                routing,
                                                thresholds,
                                                cleanupPolicy,
                                                newInstances,
                                                createdAt,
                                                System.currentTimeMillis()));
    }

    /**
     * Updates the traffic routing.
     *
     * @param newRouting the new routing configuration
     * @return updated rolling update
     */
    public RollingUpdate withRouting(VersionRouting newRouting) {
        return new RollingUpdate(updateId,
                                 artifactBase,
                                 oldVersion,
                                 newVersion,
                                 state,
                                 newRouting,
                                 thresholds,
                                 cleanupPolicy,
                                 newInstances,
                                 createdAt,
                                 System.currentTimeMillis());
    }

    /**
     * Checks if this update is in a terminal state.
     */
    public boolean isTerminal() {
        return state.isTerminal();
    }

    /**
     * Checks if this update is active (not terminal).
     */
    public boolean isActive() {
        return ! isTerminal();
    }

    /**
     * Checks if new version is receiving traffic.
     */
    public boolean hasNewVersionTraffic() {
        return state.allowsNewVersionTraffic() && !routing.isAllOld();
    }

    /**
     * Returns time since creation in milliseconds.
     */
    public long age() {
        return System.currentTimeMillis() - createdAt;
    }

    /**
     * Returns time since last update in milliseconds.
     */
    public long timeSinceUpdate() {
        return System.currentTimeMillis() - updatedAt;
    }
}
