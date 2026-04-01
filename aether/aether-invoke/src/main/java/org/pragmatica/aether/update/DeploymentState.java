package org.pragmatica.aether.update;

import java.util.Set;

/// Unified state machine for all deployment strategy lifecycles.
///
/// Covers the full lifecycle from initial request through completion or failure:
///
///   - **Deploy phase**: PENDING -> DEPLOYING -> DEPLOYED
///   - **Route phase**: DEPLOYED -> ROUTING -> PROMOTING -> DRAINING -> COMPLETED
///   - **Rollback**: Any non-terminal -> ROLLING_BACK -> ROLLED_BACK
///   - **Failure**: Any non-terminal -> FAILED
///
public enum DeploymentState {
    /// Deployment requested but not yet started
    PENDING,
    /// New version instances being deployed (0% traffic)
    DEPLOYING,
    /// New version deployed and healthy (0% traffic, ready for routing)
    DEPLOYED,
    /// Traffic being shifted to new version according to strategy
    ROUTING,
    /// Strategy validated, promoting to full deployment
    PROMOTING,
    /// Old version instances being drained after successful promotion
    DRAINING,
    /// Deployment completed successfully
    COMPLETED,
    /// Rolling back to old version
    ROLLING_BACK,
    /// Rollback completed
    ROLLED_BACK,
    /// Deployment failed
    FAILED;
    /// Returns valid transitions from this state.
    public Set<DeploymentState> validTransitions() {
        return switch (this) {case PENDING -> Set.of(DEPLOYING, FAILED);case DEPLOYING -> Set.of(DEPLOYED,
                                                                                                 ROLLING_BACK,
                                                                                                 FAILED);case DEPLOYED -> Set.of(ROUTING,
                                                                                                                                 ROLLING_BACK,
                                                                                                                                 FAILED);case ROUTING -> Set.of(ROUTING,
                                                                                                                                                                PROMOTING,
                                                                                                                                                                ROLLING_BACK,
                                                                                                                                                                FAILED);case PROMOTING -> Set.of(DRAINING,
                                                                                                                                                                                                 ROLLING_BACK,
                                                                                                                                                                                                 FAILED);case DRAINING -> Set.of(COMPLETED,
                                                                                                                                                                                                                                 FAILED);case ROLLING_BACK -> Set.of(ROLLED_BACK,
                                                                                                                                                                                                                                                                     FAILED);case COMPLETED, ROLLED_BACK, FAILED -> Set.of();};
    }
    /// Checks if this state is terminal (no further transitions possible).
    public boolean isTerminal() {
        return this == COMPLETED || this == ROLLED_BACK || this == FAILED;
    }
    /// Checks if this state is active (not terminal).
    public boolean isActive() {
        return ! isTerminal();
    }
}
