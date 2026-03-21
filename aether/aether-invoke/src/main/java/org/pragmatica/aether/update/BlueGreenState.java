package org.pragmatica.aether.update;

import java.util.Set;

/// State machine for blue-green deployment lifecycle.
///
///
/// Two-environment model with atomic traffic switch:
///
///   - **Stage 1 (Deploy)**: PENDING -> DEPLOYING_GREEN -> GREEN_READY
///   - **Stage 2 (Switch)**: GREEN_READY -> SWITCHED -> DRAINING -> COMPLETED
///   - **Rollback (pre-drain)**: SWITCHED -> SWITCH_BACK -> ROLLED_BACK
///   - **Rollback (deploy)**: DEPLOYING_GREEN or GREEN_READY -> ROLLING_BACK -> ROLLED_BACK
///   - **Failure**: Any non-terminal state -> FAILED
///
public enum BlueGreenState {
    /// Blue-green deployment requested but not yet started
    PENDING,
    /// Green environment instances being deployed
    DEPLOYING_GREEN,
    /// Green environment healthy and ready to receive traffic
    GREEN_READY,
    /// Traffic switched to green environment
    SWITCHED,
    /// Switching traffic back to blue environment (rollback after switch)
    SWITCH_BACK,
    /// Rolling back green environment (pre-switch rollback)
    ROLLING_BACK,
    /// Old (blue) environment being drained after successful switch
    DRAINING,
    /// Blue-green deployment completed successfully
    COMPLETED,
    /// Rollback completed (green environment removed)
    ROLLED_BACK,
    /// Blue-green deployment failed
    FAILED;
    /// Returns valid transitions from this state.
    public Set<BlueGreenState> validTransitions() {
        return switch (this) {
            case PENDING -> Set.of(DEPLOYING_GREEN, FAILED);
            case DEPLOYING_GREEN -> Set.of(GREEN_READY, ROLLING_BACK, FAILED);
            case GREEN_READY -> Set.of(SWITCHED, ROLLING_BACK, FAILED);
            case SWITCHED -> Set.of(DRAINING, SWITCH_BACK, FAILED);
            case SWITCH_BACK -> Set.of(ROLLED_BACK, FAILED);
            case ROLLING_BACK -> Set.of(ROLLED_BACK, FAILED);
            case DRAINING -> Set.of(COMPLETED, FAILED);
            case COMPLETED, ROLLED_BACK, FAILED -> Set.of();
        };
    }
    /// Checks if this state is a terminal state.
    public boolean isTerminal() {
        return this == COMPLETED || this == ROLLED_BACK || this == FAILED;
    }
    /// Checks if this state allows traffic to the green environment.
    public boolean allowsGreenTraffic() {
        return this == SWITCHED || this == DRAINING;
    }
    /// Checks if this state requires both blue and green environments to be running.
    public boolean requiresBothEnvironments() {
        return this == GREEN_READY || this == SWITCHED || this == DRAINING;
    }
}
