// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;


public sealed interface RateGuardError extends Cause {
    record LimitExceeded(long retryAfterMs, long limit, long remaining, long resetAtEpochMs) implements RateGuardError, Cause.Transient {
        public static LimitExceeded limitExceeded(long retryAfterMs, long limit, long remaining, long resetAtEpochMs) {
            return new LimitExceeded(retryAfterMs, limit, remaining, resetAtEpochMs);
        }

        @Override
        public String message() {
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
