package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.slice.MethodInterceptor;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Retry;

/// Method interceptor that retries failed invocations using core {@link Retry}.
record RetryMethodInterceptor(Retry retry) implements MethodInterceptor {
    @Override
    public <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method) {
        return request -> retry.execute(() -> method.apply(request));
    }
}
