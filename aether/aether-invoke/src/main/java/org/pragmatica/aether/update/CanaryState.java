package org.pragmatica.aether.update;

import java.util.Set;

/// State machine for canary deployment lifecycle.
///
///
/// Three-stage model:
///
///   - **Stage 1 (Deploy)**: PENDING -> DEPLOYING -> CANARY_ACTIVE
///   - **Stage 2 (Canary)**: CANARY_ACTIVE -> CANARY_ACTIVE (stage advance) -> PROMOTING -> PROMOTED
///   - **Auto-rollback**: CANARY_ACTIVE -> AUTO_ROLLBACK -> ROLLED_BACK
///   - **Manual rollback**: Any non-terminal state -> ROLLING_BACK -> ROLLED_BACK
///   - **Failure**: Any non-terminal state -> FAILED
///
public enum CanaryState {
    /// Canary deployment requested but not yet started
    PENDING,
    /// Canary instances being deployed (0% traffic)
    DEPLOYING,
    /// Canary instances receiving traffic at configured percentage
    CANARY_ACTIVE,
    /// Canary validated, promoting to full deployment
    PROMOTING,
    /// Metrics-triggered automatic rollback in progress
    AUTO_ROLLBACK,
    /// Manual rollback in progress
    ROLLING_BACK,
    /// Canary successfully promoted to full deployment
    PROMOTED,
    /// Rollback completed (canary instances removed)
    ROLLED_BACK,
    /// Canary deployment failed
    FAILED;
    /// Returns valid transitions from this state.
    public Set<CanaryState> validTransitions() {
        return switch (this) {case PENDING -> Set.of(DEPLOYING, FAILED);case DEPLOYING -> Set.of(CANARY_ACTIVE,
                                                                                                 ROLLING_BACK,
                                                                                                 FAILED);case CANARY_ACTIVE -> Set.of(CANARY_ACTIVE,
                                                                                                                                      PROMOTING,
                                                                                                                                      AUTO_ROLLBACK,
                                                                                                                                      ROLLING_BACK,
                                                                                                                                      FAILED);case PROMOTING -> Set.of(PROMOTED,
                                                                                                                                                                       ROLLING_BACK,
                                                                                                                                                                       FAILED);case AUTO_ROLLBACK -> Set.of(ROLLED_BACK,
                                                                                                                                                                                                            FAILED);case PROMOTED, ROLLED_BACK, FAILED -> Set.of();case ROLLING_BACK -> Set.of(ROLLED_BACK,
                                                                                                                                                                                                                                                                                               FAILED);};
    }
    /// Checks if this state is a terminal state.
    public boolean isTerminal() {
        return this == PROMOTED || this == ROLLED_BACK || this == FAILED;
    }
    /// Checks if this state allows canary traffic.
    public boolean allowsCanaryTraffic() {
        return this == CANARY_ACTIVE || this == PROMOTING;
    }
    /// Checks if this state requires both versions to be running.
    public boolean requiresBothVersions() {
        return this == CANARY_ACTIVE || this == PROMOTING;
    }
}
