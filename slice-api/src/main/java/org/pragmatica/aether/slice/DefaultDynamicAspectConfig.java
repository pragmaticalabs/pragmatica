package org.pragmatica.aether.slice;

import org.pragmatica.lang.Unit;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.pragmatica.lang.Unit.unit;

/**
 * Default implementation of DynamicAspectConfig.
 * Thread-safe with atomic toggles.
 */
public final class DefaultDynamicAspectConfig implements DynamicAspectConfig {
    private final AtomicBoolean loggingEnabled = new AtomicBoolean(false);
    private final AtomicBoolean metricsEnabled = new AtomicBoolean(false);

    DefaultDynamicAspectConfig() {}

    static DefaultDynamicAspectConfig defaultDynamicAspectConfig() {
        return new DefaultDynamicAspectConfig();
    }

    @Override
    public boolean isLoggingEnabled() {
        return loggingEnabled.get();
    }

    @Override
    public boolean isMetricsEnabled() {
        return metricsEnabled.get();
    }

    @Override
    public <T> Aspect<T> aspectFor(Class<T> dependencyType) {
        return Aspect.identity();
    }

    @Override
    public Unit enableLogging(boolean enabled) {
        loggingEnabled.set(enabled);
        return unit();
    }

    @Override
    public Unit enableMetrics(boolean enabled) {
        metricsEnabled.set(enabled);
        return unit();
    }
}
