// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.lang.Cause;


public sealed interface FailoverRecoveryError extends Cause {
    enum General implements FailoverRecoveryError {
        NO_REPLICAS_AVAILABLE("No replicas available for catch-up");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override public String message() {
            return message;
        }
    }

    record CatchupFailed(String streamName, int partition, Cause underlying) implements FailoverRecoveryError {
        @Override public String message() {
            return "Catch-up failed for %s[%d]: %s".formatted(streamName, partition, underlying.message());
        }
    }
}
