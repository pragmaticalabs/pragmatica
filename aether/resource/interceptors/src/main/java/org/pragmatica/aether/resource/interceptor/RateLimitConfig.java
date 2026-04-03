package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.all;
import static org.pragmatica.lang.Verify.ensure;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Configuration for rate limit interceptor.
///
/// @param maxRequests Maximum requests allowed in the window
/// @param window      Time window for rate limiting
/// @param burst       Additional burst capacity above the base rate
public record RateLimitConfig(int maxRequests, TimeSpan window, int burst) {
    public static Result<RateLimitConfig> rateLimitConfig(int maxRequests, TimeSpan window) {
        var validRequests = ensure(maxRequests, Verify.Is::positive);
        var validWindow = ensure(window, Verify.Is::notNull);
        return all(validRequests, validWindow).map((r, w) -> new RateLimitConfig(r, w, 0));
    }

    public static Result<RateLimitConfig> rateLimitConfig(int maxRequests, TimeSpan window, int burst) {
        var validRequests = ensure(maxRequests, Verify.Is::positive);
        var validWindow = ensure(window, Verify.Is::notNull);
        var validBurst = ensure(burst, Verify.Is::nonNegative);
        return all(validRequests, validWindow, validBurst).map(RateLimitConfig::new);
    }

    public static Result<RateLimitConfig> rateLimitConfig() {
        return rateLimitConfig(100, timeSpan(1).minutes());
    }
}
