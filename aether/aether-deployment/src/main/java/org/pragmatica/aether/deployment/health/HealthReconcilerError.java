// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.health;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;


public sealed interface HealthReconcilerError extends Cause {
    enum General implements HealthReconcilerError {
        ALREADY_STARTED("HealthReconciler is already started"),
        NOT_STARTED("HealthReconciler has not been started"),
        UNKNOWN_PEER_NO_PRIOR_LIFECYCLE("Cannot transition peer without a prior NodeLifecycleValue");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override public String message() {
            return message;
        }
    }

    record ProposalRejected(NodeId target, Cause cause) implements HealthReconcilerError {
        @Override public String message() {
            return "HealthReconciler consensus proposal rejected for " + target + ": " + cause.message();
        }
    }

    record CooldownActive(NodeId target, long remainingMs) implements HealthReconcilerError {
        @Override public String message() {
            return "HealthReconciler cooldown active for " + target + " (remaining " + remainingMs + " ms)";
        }
    }
}
