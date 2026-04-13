package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.slice.RateGuard;
import org.pragmatica.aether.slice.RateGuardError;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.RateLimiter;
import org.pragmatica.lang.utils.RateLimiter.RateLimiterError;

import java.util.function.Supplier;


/// Local (per-node) RateGuard implementation backed by the core token bucket RateLimiter.
///
/// Enriches the core LimitExceeded error with HTTP-friendly metadata (limit, remaining, reset time).
record DefaultRateGuard(RateLimiter limiter, int limit) implements RateGuard {
    static DefaultRateGuard defaultRateGuard(RateGuardConfig config) {
        var limiter = RateLimiter.builder()
                                .rate(config.requestsPerSecond())
                                .period(config.window())
                                .burst(config.burst())
                                .withDefaultTimeSource();
        return new DefaultRateGuard(limiter, config.requestsPerSecond());
    }

    @Override public <T> Promise<T> guard(Supplier<Promise<T>> operation) {
        return limiter.execute(operation)
                      .mapError(this::enrichError);
    }

    private RateGuardError enrichError(org.pragmatica.lang.Cause cause) {
        if (cause instanceof RateLimiterError.LimitExceeded exceeded) {
            var retryAfterMs = exceeded.retryAfter().millis();
            var resetAtMs = System.currentTimeMillis() + retryAfterMs;
            return RateGuardError.LimitExceeded.limitExceeded(retryAfterMs, limit, 0, resetAtMs);
        }
        return RateGuardError.LimitExceeded.limitExceeded(1000, limit, 0, System.currentTimeMillis() + 1000);
    }
}
