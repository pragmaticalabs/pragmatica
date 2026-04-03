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
    PENDING,
    DEPLOYING,
    DEPLOYED,
    ROUTING,
    PROMOTING,
    DRAINING,
    COMPLETED,
    ROLLING_BACK,
    ROLLED_BACK,
    FAILED;
    public Set<DeploymentState> validTransitions() {
        return switch (this){
            case PENDING -> Set.of(DEPLOYING, FAILED);
            case DEPLOYING -> Set.of(DEPLOYED, ROLLING_BACK, FAILED);
            case DEPLOYED -> Set.of(ROUTING, ROLLING_BACK, FAILED);
            case ROUTING -> Set.of(ROUTING, PROMOTING, ROLLING_BACK, FAILED);
            case PROMOTING -> Set.of(DRAINING, ROLLING_BACK, FAILED);
            case DRAINING -> Set.of(COMPLETED, FAILED);
            case ROLLING_BACK -> Set.of(ROLLED_BACK, FAILED);
            case COMPLETED, ROLLED_BACK, FAILED -> Set.of();
        };
    }
    public boolean isTerminal() {
        return this == COMPLETED || this == ROLLED_BACK || this == FAILED;
    }
    public boolean isActive() {
        return ! isTerminal();
    }
}
