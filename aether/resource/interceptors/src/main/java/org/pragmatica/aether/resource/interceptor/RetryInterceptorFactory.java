package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Retry;

/// Factory that provisions a {@link RetryMethodInterceptor} wrapping calls with retry logic.
///
/// Delegates to the core {@link Retry} utility. Each intercepted method invocation
/// is retried according to the configured backoff strategy and max attempts.
public final class RetryInterceptorFactory implements ResourceFactory<RetryMethodInterceptor, RetryConfig> {
    @Override
    public Class<RetryMethodInterceptor> resourceType() {
        return RetryMethodInterceptor.class;
    }

    @Override
    public Class<RetryConfig> configType() {
        return RetryConfig.class;
    }

    @Override
    public Promise<RetryMethodInterceptor> provision(RetryConfig config) {
        return Promise.success(interceptor(config));
    }

    private static RetryMethodInterceptor interceptor(RetryConfig config) {
        var retry = Retry.retry()
                         .attempts(config.maxAttempts())
                         .strategy(config.backoffStrategy());
        return new RetryMethodInterceptor(retry);
    }
}
