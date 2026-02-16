package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.MethodInterceptor;
import org.pragmatica.lang.Promise;

/// Factory that provisions a {@link MethodInterceptor} adding entry/exit logging.
///
/// Uses SLF4J for logging method invocations. Configurable log level,
/// argument logging, result logging, and duration logging.
public final class LoggingInterceptorFactory implements ResourceFactory<MethodInterceptor, LogConfig> {
    @Override
    public Class<MethodInterceptor> resourceType() {
        return MethodInterceptor.class;
    }

    @Override
    public Class<LogConfig> configType() {
        return LogConfig.class;
    }

    @Override
    public Promise<MethodInterceptor> provision(LogConfig config) {
        return Promise.success(new LoggingMethodInterceptor(config));
    }
}
