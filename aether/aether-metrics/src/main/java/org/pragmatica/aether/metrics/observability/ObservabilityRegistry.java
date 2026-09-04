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
    Result<Unit> registerConsensusMetrics(Supplier<java.util.Map<String, Number>> metricsSupplier);

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
            registerTransportGauge("quic_bytes_sent_total",
                                   "#726: QUIC payload bytes sent at the lane boundary — not a wire-byte or bandwidth figure",
                                   metricsSupplier);
            registerTransportGauge("quic_bytes_received_total",
                                   "#726: QUIC payload bytes received at the lane boundary — not a wire-byte or bandwidth figure",
                                   metricsSupplier);
            registerTransportGauge("quic_write_failures_total", "QUIC write failures", metricsSupplier);
            registerTransportGauge("quic_backpressure_drops_total", "QUIC backpressure drops", metricsSupplier);

            return unitResult();
        }

        /// #674: the consensus-load counters, served from the same supplier-map shape — and the same
        /// key vocabulary (`RabiaMetrics.counterMap()`) — as the HTTP consensus block, so Prometheus
        /// and the management API can never disagree about a counter's name or meaning. All are
        /// monotonic totals except `consensus_pending_batches`, a level.
        @Override
        @SuppressWarnings("JBCT-PAT-01")
        public Result<Unit> registerConsensusMetrics(Supplier<java.util.Map<String, Number>> metricsSupplier) {
            registerTransportGauge("consensus_decisions_total", "Rabia decisions applied", metricsSupplier);
            registerTransportGauge("consensus_proposals_total", "Rabia proposals submitted", metricsSupplier);
            registerTransportGauge("consensus_vote_round1_total", "Rabia round-1 votes processed", metricsSupplier);
            registerTransportGauge("consensus_vote_round2_total", "Rabia round-2 votes processed", metricsSupplier);
            registerTransportGauge("consensus_fast_path_total", "Rabia fast-path agreements", metricsSupplier);
            registerTransportGauge("consensus_sync_success_total", "Rabia sync rounds succeeded", metricsSupplier);
            registerTransportGauge("consensus_sync_failure_total", "Rabia sync rounds failed", metricsSupplier);
            registerTransportGauge("consensus_pending_batches",
                                   "Rabia batches awaiting decision (level)",
                                   metricsSupplier);

            return unitResult();
        }

        /// Name notwithstanding, this is the generic supplied-map gauge binder — the consensus
        /// registration reuses it verbatim (#674); only the historical name is transport-flavored.
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
