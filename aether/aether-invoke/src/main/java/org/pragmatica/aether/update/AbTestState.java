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
public enum AbTestState {
    PENDING,
    DEPLOYING_VARIANTS,
    ACTIVE,
    CONCLUDING,
    ROLLING_BACK,
    COMPLETED,
    ROLLED_BACK,
    FAILED;
    public Set<AbTestState> validTransitions() {
        return switch (this){
            case PENDING -> Set.of(DEPLOYING_VARIANTS, FAILED);
            case DEPLOYING_VARIANTS -> Set.of(ACTIVE, ROLLING_BACK, FAILED);
            case ACTIVE -> Set.of(CONCLUDING, ROLLING_BACK, FAILED);
            case CONCLUDING -> Set.of(COMPLETED, FAILED);
            case ROLLING_BACK -> Set.of(ROLLED_BACK, FAILED);
            case COMPLETED, ROLLED_BACK, FAILED -> Set.of();
        };
    }
    public boolean isTerminal() {
        return this == COMPLETED || this == ROLLED_BACK || this == FAILED;
    }
    public boolean allowsVariantTraffic() {
        return this == ACTIVE || this == CONCLUDING;
    }
    public boolean requiresAllVariants() {
        return this == ACTIVE || this == CONCLUDING;
    }
}
