package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.lang.Promise;

/// Factory that provisions a {@link MetricsMethodInterceptor} adding Micrometer timing and counting.
///
/// Records method execution duration and success/failure counts using the
/// {@link io.micrometer.core.instrument.MeterRegistry} provided in the config.
public final class MetricsInterceptorFactory implements ResourceFactory<MetricsMethodInterceptor, MetricsConfig> {
    @Override
    public Class<MetricsMethodInterceptor> resourceType() {
        return MetricsMethodInterceptor.class;
    }

    @Override
    public Class<MetricsConfig> configType() {
        return MetricsConfig.class;
    }

    @Override
    public Promise<MetricsMethodInterceptor> provision(MetricsConfig config) {
        return Promise.success(new MetricsMethodInterceptor(config));
    }
}
