package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.CircuitBreaker;

/// Factory that provisions a {@link CircuitBreakerMethodInterceptor} wrapping calls with circuit breaker logic.
///
/// Delegates to the core {@link CircuitBreaker} utility. When the failure threshold is reached,
/// subsequent calls are rejected immediately until the reset timeout expires.
public final class CircuitBreakerInterceptorFactory implements ResourceFactory<CircuitBreakerMethodInterceptor, CircuitBreakerConfig> {
    @Override
    public Class<CircuitBreakerMethodInterceptor> resourceType() {
        return CircuitBreakerMethodInterceptor.class;
    }

    @Override
    public Class<CircuitBreakerConfig> configType() {
        return CircuitBreakerConfig.class;
    }

    @Override
    public Promise<CircuitBreakerMethodInterceptor> provision(CircuitBreakerConfig config) {
        return Promise.success(interceptor(config));
    }

    private static CircuitBreakerMethodInterceptor interceptor(CircuitBreakerConfig config) {
        var breaker = CircuitBreaker.builder()
                                    .failureThreshold(config.failureThreshold())
                                    .resetTimeout(config.resetTimeout())
                                    .testAttempts(config.testAttempts())
                                    .withDefaultShouldTrip()
                                    .withDefaultTimeSource();
        return new CircuitBreakerMethodInterceptor(breaker);
    }
}
