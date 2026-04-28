// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.migration;

import org.pragmatica.lang.Cause;


public sealed interface MigrationError extends Cause {
    static StepFailed stepFailed(String stepDescription, Cause cause) {
        return new StepFailed(stepDescription, cause);
    }

    static InvalidRequest invalidRequest(String detail) {
        return new InvalidRequest(detail);
    }

    enum General implements MigrationError {
        NO_COMPUTE_PROVIDER("No compute provider available for target environment"),
        NO_DNS_PROVIDER("No DNS provider available for DNS update"),
        EMPTY_TOPOLOGY("Current cluster topology is empty — nothing to migrate"),
        PLAN_ALREADY_EXECUTING("A migration plan is already executing");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override public String message() {
            return message;
        }
    }

    record StepFailed(String stepDescription, Cause cause) implements MigrationError {
        @Override public String message() {
            return "Migration step failed: " + stepDescription + " — " + cause.message();
        }
    }

    record InvalidRequest(String detail) implements MigrationError {
        @Override public String message() {
            return "Invalid migration request: " + detail;
        }
    }
}
