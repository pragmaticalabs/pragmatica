// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.observability;

import java.util.function.Supplier;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.metrics.PromiseMetrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import static org.pragmatica.lang.Result.unitResult;


public interface ObservabilityRegistry {
    MeterRegistry registry();
    String scrape();
    PromiseMetrics timer(String name, String... tags);
    PromiseMetrics combined(String name, String... tags);
    <T extends Number> Gauge gauge(String name, T number, String... tags);
    Gauge gauge(String name, Supplier<Number> supplier, String... tags);
    Counter counter(String name, String... tags);
    Result<Unit> registerNodeCount(Supplier<Number> nodeCountSupplier);
    Result<Unit> registerSliceCount(Supplier<Number> sliceCountSupplier);
    Result<Unit> registerTransportMetrics(Supplier<java.util.Map<String, Number>> metricsSupplier);

    static ObservabilityRegistry prometheus() {
        var prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

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
        public Result<Unit> registerNodeCount(Supplier<Number> nodeCountSupplier) {
            Gauge.builder("aether.cluster.nodes",
                          () -> nodeCountSupplier.get()
                                                 .doubleValue())
                 .description("Number of nodes in the cluster")
                 .register(prometheusRegistry);

            return unitResult();
        }

        @Override
        public Result<Unit> registerSliceCount(Supplier<Number> sliceCountSupplier) {
            Gauge.builder("aether.slices.active",
                          () -> sliceCountSupplier.get()
                                                  .doubleValue())
                 .description("Number of active slice instances")
                 .register(prometheusRegistry);

            return unitResult();
        }

        @Override
        @SuppressWarnings("JBCT-PAT-01")
        public Result<Unit> registerTransportMetrics(Supplier<java.util.Map<String, Number>> metricsSupplier) {
            registerTransportGauge("quic_active_connections", "Active QUIC peer connections", metricsSupplier);
            registerTransportGauge("quic_handshake_total", "Total QUIC handshakes completed", metricsSupplier);
            registerTransportGauge("quic_handshake_failures_total", "Failed QUIC handshakes", metricsSupplier);
            registerTransportGauge("quic_messages_sent_total", "Messages sent over QUIC", metricsSupplier);
            registerTransportGauge("quic_messages_received_total", "Messages received over QUIC", metricsSupplier);
            registerTransportGauge("quic_write_failures_total", "QUIC write failures", metricsSupplier);
            registerTransportGauge("quic_backpressure_drops_total", "QUIC backpressure drops", metricsSupplier);

            return unitResult();
        }

        private void registerTransportGauge(String name,
                                            String description,
                                            Supplier<java.util.Map<String, Number>> metricsSupplier) {
            Gauge.builder(name,
                          () -> metricsSupplier.get()
                                               .getOrDefault(name, 0)
                                               .doubleValue())
                 .description(description)
                 .register(prometheusRegistry);
        }
    }
}
