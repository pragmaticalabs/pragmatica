package org.pragmatica.aether.infra.aspect;

import org.pragmatica.aether.slice.Aspect;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Factory for creating metrics aspects.
 * Wraps slice method invocations with Micrometer metrics collection.
 *
 * <p>Uses PromiseMetrics from pragmatica-lite for proper async handling:
 * timing is measured from invocation to Promise completion, and
 * success/failure is determined by Promise outcome.
 */
public interface MetricsAspectFactory extends Slice {
    /**
     * Create a metrics aspect with the given configuration.
     *
     * @param config Metrics configuration
     * @param <T>    Target type
     * @return Aspect that wraps the target with metrics
     */
    <T> Aspect<T> create(MetricsConfig config);

    /**
     * Create a metrics aspect with default configuration.
     * Note: This method uses unwrap() as it's infrastructure code where
     * the developer provides the metric name - validation failure indicates
     * a programming error rather than a runtime condition.
     *
     * @param name Metric name prefix (must not be blank)
     * @param <T>  Target type
     * @return Aspect that wraps the target with metrics
     */
    default <T> Aspect<T> create(String name) {
        return create(MetricsConfig.metricsConfig(name)
                                   .unwrap());
    }

    /**
     * Enable or disable metrics collection globally.
     *
     * @param enabled Whether metrics collection is enabled
     * @return Unit
     */
    Unit setEnabled(boolean enabled);

    /**
     * Check if metrics collection is enabled.
     *
     * @return true if enabled
     */
    boolean isEnabled();

    /**
     * Get the underlying meter registry.
     *
     * @return MeterRegistry instance
     */
    MeterRegistry registry();

    /**
     * Factory method.
     *
     * @param registry Micrometer registry
     * @return MetricsAspectFactory instance
     */
    static MetricsAspectFactory metricsAspectFactory(MeterRegistry registry) {
        return new DefaultMetricsAspectFactory(registry);
    }

    @Override
    default Promise<Unit> start() {
        return Promise.success(Unit.unit());
    }

    @Override
    default Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }

    @Override
    default List<SliceMethod< ?, ?>> methods() {
        return List.of();
    }
}
