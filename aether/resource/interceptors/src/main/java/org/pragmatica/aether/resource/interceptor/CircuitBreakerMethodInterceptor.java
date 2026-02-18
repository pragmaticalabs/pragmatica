package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.slice.MethodInterceptor;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.CircuitBreaker;

/// Method interceptor that wraps invocations with a {@link CircuitBreaker}.
record CircuitBreakerMethodInterceptor(CircuitBreaker breaker) implements MethodInterceptor {
    @Override
    public <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method) {
        return request -> breaker.execute(() -> method.apply(request));
    }
}
