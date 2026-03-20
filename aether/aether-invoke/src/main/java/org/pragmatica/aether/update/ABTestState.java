package org.pragmatica.aether.update;

import java.util.Set;

/// State machine for A/B testing deployment lifecycle.
///
///
/// Multi-variant deployment model with deterministic traffic routing:
///
///   - **Stage 1 (Deploy)**: PENDING -> DEPLOYING_VARIANTS -> ACTIVE
///   - **Stage 2 (Test)**: ACTIVE -> CONCLUDING -> COMPLETED
///   - **Rollback**: ACTIVE or DEPLOYING_VARIANTS -> ROLLING_BACK -> ROLLED_BACK
///   - **Failure**: Any non-terminal state -> FAILED
///
public enum ABTestState {
    /// A/B test requested but not yet started
    PENDING,
    /// Variant instances being deployed
    DEPLOYING_VARIANTS,
    /// All variants active and receiving traffic per split rule
    ACTIVE,
    /// Test concluded, winning variant being promoted
    CONCLUDING,
    /// Rolling back all variants to baseline
    ROLLING_BACK,
    /// A/B test completed successfully with winning variant promoted
    COMPLETED,
    /// Rollback completed (variant instances removed)
    ROLLED_BACK,
    /// A/B test failed
    FAILED;
    /// Returns valid transitions from this state.
    public Set<ABTestState> validTransitions() {
        return switch (this) {
            case PENDING -> Set.of(DEPLOYING_VARIANTS, FAILED);
            case DEPLOYING_VARIANTS -> Set.of(ACTIVE, ROLLING_BACK, FAILED);
            case ACTIVE -> Set.of(CONCLUDING, ROLLING_BACK, FAILED);
            case CONCLUDING -> Set.of(COMPLETED, FAILED);
            case ROLLING_BACK -> Set.of(ROLLED_BACK, FAILED);
            case COMPLETED, ROLLED_BACK, FAILED -> Set.of();
        };
    }
    /// Checks if this state is a terminal state.
    public boolean isTerminal() {
        return this == COMPLETED || this == ROLLED_BACK || this == FAILED;
    }
    /// Checks if this state allows variant traffic routing.
    public boolean allowsVariantTraffic() {
        return this == ACTIVE || this == CONCLUDING;
    }
    /// Checks if this state requires all variant versions to be running.
    public boolean requiresAllVariants() {
        return this == ACTIVE || this == CONCLUDING;
    }
}
