// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.update;

import java.util.Set;


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
        return switch (this) {
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
