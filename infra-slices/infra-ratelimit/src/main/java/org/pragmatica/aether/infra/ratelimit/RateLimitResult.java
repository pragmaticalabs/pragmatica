package org.pragmatica.aether.infra.ratelimit;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/**
 * Result of a rate limit check.
 *
 * @param allowed    Whether the request is allowed
 * @param remaining  Number of requests remaining in the window
 * @param retryAfter Time to wait before retrying (if not allowed)
 */
public record RateLimitResult(boolean allowed, int remaining, TimeSpan retryAfter) {
    /**
     * Create an allowed result.
     *
     * @param remaining Requests remaining
     * @return Allowed result
     */
    public static RateLimitResult allowed(int remaining) {
        return new RateLimitResult(true, remaining, timeSpan(0)
                                                            .millis());
    }

    /**
     * Create a denied result.
     *
     * @param retryAfter Time to wait before retrying
     * @return Denied result
     */
    public static RateLimitResult denied(TimeSpan retryAfter) {
        return new RateLimitResult(false, 0, retryAfter);
    }
}
