package org.pragmatica.aether.infra.ratelimit;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Result of a rate limit check.
///
/// @param allowed    Whether the request is allowed
/// @param remaining  Number of requests remaining in the window
/// @param retryAfter Time to wait before retrying (if not allowed)
public record RateLimitResult(boolean allowed, int remaining, TimeSpan retryAfter) {
    /// Factory method returning Result.
    public static Result<RateLimitResult> rateLimitResult(boolean allowed, int remaining, TimeSpan retryAfter) {
        return success(new RateLimitResult(allowed, remaining, retryAfter));
    }

    /// Create an allowed result.
    ///
    /// @param remaining Requests remaining
    /// @return Allowed result
    public static RateLimitResult rateLimitResult(int remaining) {
        return rateLimitResult(true, remaining, timeSpan(0).millis()).unwrap();
    }

    /// Create a denied result.
    ///
    /// @param retryAfter Time to wait before retrying
    /// @return Denied result
    public static RateLimitResult rateLimitResult(TimeSpan retryAfter) {
        return rateLimitResult(false, 0, retryAfter).unwrap();
    }
}
