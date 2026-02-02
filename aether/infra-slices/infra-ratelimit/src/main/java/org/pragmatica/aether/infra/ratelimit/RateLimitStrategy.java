package org.pragmatica.aether.infra.ratelimit;
/**
 * Strategy for rate limiting calculation.
 */
public enum RateLimitStrategy {
    /**
     * Fixed window - resets at fixed intervals.
     * Simple but can allow 2x burst at window boundaries.
     */
    FIXED_WINDOW,
    /**
     * Sliding window - smooths out bursts.
     * More accurate but slightly more complex.
     */
    SLIDING_WINDOW,
    /**
     * Token bucket - allows controlled bursts.
     * Tokens replenish at a fixed rate.
     */
    TOKEN_BUCKET
}
