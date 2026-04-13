package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;


/// Error types for rate guard violations.
public sealed interface RateGuardError extends Cause {
    /// Rate limit exceeded. Contains metadata for HTTP 429 response headers.
    record LimitExceeded(long retryAfterMs,
                         long limit,
                         long remaining,
                         long resetAtEpochMs) implements RateGuardError {
        public static LimitExceeded limitExceeded(long retryAfterMs, long limit, long remaining, long resetAtEpochMs) {
            return new LimitExceeded(retryAfterMs, limit, remaining, resetAtEpochMs);
        }

        @Override public String message() {
            return "Rate limit exceeded. Retry after " + retryAfterMs + "ms";
        }

        /// Retry-After header value in seconds (rounded up).
        public long retryAfterSeconds() {
            return (retryAfterMs + 999) / 1000;
        }

        /// X-RateLimit-Reset header value (Unix epoch seconds).
        public long resetAtEpochSeconds() {
            return resetAtEpochMs / 1000;
        }
    }
}
