package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.slice.MethodInterceptor;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.RateLimiter;

/// Method interceptor that applies rate limiting before invocation.
public record RateLimitMethodInterceptor(RateLimiter limiter) implements MethodInterceptor {
    @Override
    public <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method) {
        return request -> limiter.execute(() -> method.apply(request));
    }
}
