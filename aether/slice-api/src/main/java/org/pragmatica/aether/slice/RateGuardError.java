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
