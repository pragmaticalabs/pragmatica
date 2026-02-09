package org.pragmatica.aether.update;

import java.util.Set;

/// State machine for rolling update lifecycle.
///
///
/// Two-stage model:
///
///   - **Stage 1 (Deploy)**: PENDING → DEPLOYING → DEPLOYED
///   - **Stage 2 (Route)**: DEPLOYED → ROUTING → COMPLETING → COMPLETED
///   - **Rollback**: Any state → ROLLING_BACK → ROLLED_BACK
///   - **Failure**: Any state → FAILED
///
public enum RollingUpdateState {
    /// Update requested but not yet started */
    PENDING,
    /// New version instances being deployed (0% traffic) */
    DEPLOYING,
    /// New version deployed and healthy (0% traffic, ready for routing) */
    DEPLOYED,
    /// Traffic being shifted according to routing ratio */
    ROUTING,
    /// Completing update (removing old version instances) */
    COMPLETING,
    /// Update successfully completed (old version removed) */
    COMPLETED,
    /// Rolling back to old version */
    ROLLING_BACK,
    /// Rollback completed (new version removed) */
    ROLLED_BACK,
    /// Update failed */
    FAILED;
    /// Returns valid transitions from this state.
    public Set<RollingUpdateState> validTransitions() {
        return switch (this) {
            case PENDING -> Set.of(DEPLOYING, FAILED);
            case DEPLOYING -> Set.of(DEPLOYED, ROLLING_BACK, FAILED);
            case DEPLOYED -> Set.of(ROUTING, ROLLING_BACK, FAILED);
            case ROUTING -> Set.of(ROUTING, COMPLETING, ROLLING_BACK, FAILED);
            case COMPLETING -> Set.of(COMPLETED, ROLLING_BACK, FAILED);
            case COMPLETED, ROLLED_BACK, FAILED -> Set.of();
            // Terminal states
            case ROLLING_BACK -> Set.of(ROLLED_BACK, FAILED);
        };
    }
    /// Checks if this state is a terminal state.
    public boolean isTerminal() {
        return this == COMPLETED || this == ROLLED_BACK || this == FAILED;
    }
    /// Checks if this state allows traffic to new version.
    public boolean allowsNewVersionTraffic() {
        return this == ROUTING || this == COMPLETING;
    }
    /// Checks if this state requires both versions to be running.
    public boolean requiresBothVersions() {
        return this == DEPLOYED || this == ROUTING;
    }
}
