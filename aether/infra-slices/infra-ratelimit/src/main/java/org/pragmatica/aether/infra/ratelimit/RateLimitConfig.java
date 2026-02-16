package org.pragmatica.aether.infra.ratelimit;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Verify.ensure;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Configuration for rate limiting.
///
/// @param maxRequests Maximum requests allowed in the window
/// @param window      Time window for rate limiting
/// @param strategy    Rate limiting strategy
public record RateLimitConfig(int maxRequests, TimeSpan window, RateLimitStrategy strategy) {
    /// Create rate limit configuration with fixed window strategy.
    ///
    /// @param maxRequests Maximum requests per window
    /// @param window      Time window
    /// @return Result containing configuration or error
    public static Result<RateLimitConfig> rateLimitConfig(int maxRequests, TimeSpan window) {
        return rateLimitConfig(maxRequests, window, RateLimitStrategy.FIXED_WINDOW);
    }

    /// Create rate limit configuration with custom strategy.
    ///
    /// @param maxRequests Maximum requests per window
    /// @param window      Time window
    /// @param strategy    Rate limiting strategy
    /// @return Result containing configuration or error
    public static Result<RateLimitConfig> rateLimitConfig(int maxRequests,
                                                          TimeSpan window,
                                                          RateLimitStrategy strategy) {
        var validRequests = ensure(maxRequests, Verify.Is::positive);
        var validWindow = ensure(window, Verify.Is::notNull);
        var validStrategy = ensure(strategy, Verify.Is::notNull);
        return Result.all(validRequests, validWindow, validStrategy)
                     .map(RateLimitConfig::new);
    }

    /// Default configuration: 100 requests per minute with fixed window.
    ///
    /// @return Result containing default configuration
    public static Result<RateLimitConfig> rateLimitConfig() {
        return rateLimitConfig(100, timeSpan(1).minutes());
    }
}
