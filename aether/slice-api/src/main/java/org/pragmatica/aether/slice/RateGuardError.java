// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;


/// Error types for rate guard violations.
public sealed interface RateGuardError extends Cause {
    record LimitExceeded(long retryAfterMs, long limit, long remaining, long resetAtEpochMs) implements RateGuardError {
        public static LimitExceeded limitExceeded(long retryAfterMs, long limit, long remaining, long resetAtEpochMs) {
            return new LimitExceeded(retryAfterMs, limit, remaining, resetAtEpochMs);
        }

        @Override public String message() {
            return "Rate limit exceeded. Retry after " + retryAfterMs + "ms";
        }

        public long retryAfterSeconds() {
            return (retryAfterMs + 999) / 1000;
        }

        public long resetAtEpochSeconds() {
            return resetAtEpochMs / 1000;
        }
    }
}
