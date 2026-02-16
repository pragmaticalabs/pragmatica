package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.MethodInterceptor;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.RateLimiter;

/// Factory that provisions a {@link MethodInterceptor} wrapping calls with rate limiting.
///
/// Delegates to the core {@link RateLimiter} (token bucket). When the rate limit is
/// exhausted, calls fail immediately with a rate-limit-exceeded cause.
public final class RateLimitInterceptorFactory implements ResourceFactory<MethodInterceptor, RateLimitConfig> {
    @Override
    public Class<MethodInterceptor> resourceType() {
        return MethodInterceptor.class;
    }

    @Override
    public Class<RateLimitConfig> configType() {
        return RateLimitConfig.class;
    }

    @Override
    public Promise<MethodInterceptor> provision(RateLimitConfig config) {
        return Promise.success(interceptor(config));
    }

    private static MethodInterceptor interceptor(RateLimitConfig config) {
        var limiter = RateLimiter.builder()
                                 .rate(config.maxRequests())
                                 .period(config.window())
                                 .burst(config.burst())
                                 .withDefaultTimeSource();
        return new RateLimitMethodInterceptor(limiter);
    }
}
