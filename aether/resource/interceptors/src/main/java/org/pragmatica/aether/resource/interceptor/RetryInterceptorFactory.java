package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.MethodInterceptor;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Retry;

/// Factory that provisions a {@link MethodInterceptor} wrapping calls with retry logic.
///
/// Delegates to the core {@link Retry} utility. Each intercepted method invocation
/// is retried according to the configured backoff strategy and max attempts.
public final class RetryInterceptorFactory implements ResourceFactory<MethodInterceptor, RetryConfig> {
    @Override
    public Class<MethodInterceptor> resourceType() {
        return MethodInterceptor.class;
    }

    @Override
    public Class<RetryConfig> configType() {
        return RetryConfig.class;
    }

    @Override
    public Promise<MethodInterceptor> provision(RetryConfig config) {
        return Promise.success(interceptor(config));
    }

    private static MethodInterceptor interceptor(RetryConfig config) {
        var retry = Retry.retry()
                         .attempts(config.maxAttempts())
                         .strategy(config.backoffStrategy());
        return new RetryMethodInterceptor(retry);
    }
}
