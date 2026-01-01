package org.pragmatica.aether.metrics.observability;

import org.pragmatica.metrics.PromiseMetrics;

import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * Central registry for observability metrics using Micrometer.
 *
 * <p>Provides:
 * <ul>
 *   <li>JVM metrics (memory, GC, threads, classloaders)</li>
 *   <li>Process metrics (CPU)</li>
 *   <li>Custom Aether metrics (slice invocations, consensus, deployments)</li>
 *   <li>Prometheus scrape endpoint</li>
 * </ul>
 *
 * <p>Uses pragmatica-lite's PromiseMetrics for wrapping async operations.
 */
public interface ObservabilityRegistry {
    /**
     * Get the underlying Micrometer registry.
     */
    MeterRegistry registry();

    /**
     * Get Prometheus-formatted metrics for scraping.
     */
    String scrape();

    /**
     * Create a timer-based PromiseMetrics wrapper.
     */
    PromiseMetrics timer(String name, String... tags);

    /**
     * Create a combined timer+counter PromiseMetrics wrapper.
     */
    PromiseMetrics combined(String name, String... tags);

    /**
     * Register a gauge that tracks a value.
     */
    <T extends Number> Gauge gauge(String name, T number, String... tags);

    /**
     * Register a gauge backed by a supplier.
     */
    Gauge gauge(String name, Supplier<Number> supplier, String... tags);

    /**
     * Get or create a raw counter.
     */
    Counter counter(String name, String... tags);

    /**
     * Register cluster node count gauge.
     */
    void registerNodeCount(Supplier<Number> nodeCountSupplier);

    /**
     * Register active slice count gauge.
     */
    void registerSliceCount(Supplier<Number> sliceCountSupplier);

    /**
     * Create an observability registry with Prometheus backend.
     */
    static ObservabilityRegistry prometheus() {
        var prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        // Register JVM metrics
        new ClassLoaderMetrics().bindTo(prometheusRegistry);
        new JvmMemoryMetrics().bindTo(prometheusRegistry);
        new JvmGcMetrics().bindTo(prometheusRegistry);
        new JvmThreadMetrics().bindTo(prometheusRegistry);
        new ProcessorMetrics().bindTo(prometheusRegistry);
        return new PrometheusObservabilityRegistry(prometheusRegistry);
    }

    record PrometheusObservabilityRegistry(PrometheusMeterRegistry prometheusRegistry) implements ObservabilityRegistry {
        @Override
        public MeterRegistry registry() {
            return prometheusRegistry;
        }

        @Override
        public String scrape() {
            return prometheusRegistry.scrape();
        }

        @Override
        public PromiseMetrics timer(String name, String... tags) {
            return PromiseMetrics.timer(name)
                                 .registry(prometheusRegistry)
                                 .tags(tags)
                                 .build();
        }

        @Override
        public PromiseMetrics combined(String name, String... tags) {
            return PromiseMetrics.combined(name)
                                 .registry(prometheusRegistry)
                                 .tags(tags)
                                 .build();
        }

        @Override
        public <T extends Number> Gauge gauge(String name, T number, String... tags) {
            return Gauge.builder(name, number, Number::doubleValue)
                        .tags(tags)
                        .register(prometheusRegistry);
        }

        @Override
        public Gauge gauge(String name, Supplier<Number> supplier, String... tags) {
            return Gauge.builder(name,
                                 () -> supplier.get()
                                               .doubleValue())
                        .tags(tags)
                        .register(prometheusRegistry);
        }

        @Override
        public Counter counter(String name, String... tags) {
            return prometheusRegistry.counter(name, tags);
        }

        @Override
        public void registerNodeCount(Supplier<Number> nodeCountSupplier) {
            Gauge.builder("aether.cluster.nodes",
                          () -> nodeCountSupplier.get()
                                                 .doubleValue())
                 .description("Number of nodes in the cluster")
                 .register(prometheusRegistry);
        }

        @Override
        public void registerSliceCount(Supplier<Number> sliceCountSupplier) {
            Gauge.builder("aether.slices.active",
                          () -> sliceCountSupplier.get()
                                                  .doubleValue())
                 .description("Number of active slice instances")
                 .register(prometheusRegistry);
        }
    }
}
