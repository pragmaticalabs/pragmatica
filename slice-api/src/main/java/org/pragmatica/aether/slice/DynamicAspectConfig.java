package org.pragmatica.aether.slice;

import org.pragmatica.lang.Unit;

/**
 * Runtime-configurable aspect configuration.
 * Enables toggling logging/metrics at runtime without restart.
 */
public interface DynamicAspectConfig {
    boolean isLoggingEnabled();
    boolean isMetricsEnabled();

    <T> Aspect<T> aspectFor(Class<T> dependencyType);

    Unit enableLogging(boolean enabled);
    Unit enableMetrics(boolean enabled);

    static DynamicAspectConfig dynamicAspectConfig() {
        return DefaultDynamicAspectConfig.defaultDynamicAspectConfig();
    }
}
