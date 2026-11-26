// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.pragmatica.config.ConfigurationProvider;

import static org.assertj.core.api.Assertions.assertThat;

/// #3650 part 1: the metric sinks. Each sink is off unless configured; a setting Terra does not implement is refused.
class TerraMetricsTest {
    private static ConfigurationProvider config(Map<String, String> values) {
        return ConfigurationProvider.builder().withDefaults(values).build();
    }

    @Test void settings_noManagementKeys_buildsNoExportSinkButKeepsMetersReadable() {
        var settings = TerraMetrics.Settings.settings(config(Map.of())).unwrap();
        var metrics = TerraMetrics.terraMetrics(settings).unwrap();

        assertThat(settings.prometheus()).isFalse();
        assertThat(settings.otlp().isEmpty()).isTrue();
        metrics.registry().counter("terra.probe").increment();
        assertThat(metrics.registry().find("terra.probe").counter().count())
            .as("the in-memory child keeps a meter readable when no sink is configured").isEqualTo(1.0);
        assertThat(metrics.prometheusScrape().isEmpty()).as("no Prometheus sink unless enabled").isTrue();
        assertThat(metrics.close().isSuccess()).isTrue();
        assertThat(metrics.registry().isClosed()).isTrue();
    }

    @Test void settings_prometheusEnabled_scrapeRendersTheApplicationMeters() {
        var settings = TerraMetrics.Settings.settings(config(Map.of("management.prometheus.metrics.export.enabled", "true"))).unwrap();
        var metrics = TerraMetrics.terraMetrics(settings).unwrap();

        metrics.registry().counter("terra.probe").increment();
        assertThat(metrics.prometheusScrape().unwrap()).contains("terra_probe_total 1.0");
        assertThat(TerraMetrics.PROMETHEUS_CONTENT_TYPE).isEqualTo("text/plain; version=0.0.4; charset=utf-8");
        assertThat(metrics.close().isSuccess()).isTrue();
    }

    @Test void settings_prometheusDisabledExplicitly_buildsNoPrometheusSink() {
        var settings = TerraMetrics.Settings.settings(config(Map.of("management.prometheus.metrics.export.enabled", "FALSE"))).unwrap();

        assertThat(settings.prometheus()).isFalse();
    }

    @Test void settings_otlpUrl_readsStepAndResourceAttributes() {
        var settings = TerraMetrics.Settings.settings(config(Map.of("management.otlp.metrics.export.url", "http://collector:4318/v1/metrics",
                                                                    "management.otlp.metrics.export.step", "30s",
                                                                    "management.opentelemetry.resource-attributes.service.name", "shop",
                                                                    "management.opentelemetry.resource-attributes.region", "eu"))).unwrap();
        var otlp = settings.otlp().unwrap();

        assertThat(otlp.url().uri().toString()).isEqualTo("http://collector:4318/v1/metrics");
        assertThat(otlp.step()).isEqualTo(Duration.ofSeconds(30));
        assertThat(otlp.resourceAttributes()).containsExactlyInAnyOrderEntriesOf(Map.of("service.name", "shop", "region", "eu"));
    }

    @Test void settings_otlpUrlWithoutStep_defaultsToOneMinute() {
        var settings = TerraMetrics.Settings.settings(config(Map.of("management.otlp.metrics.export.url", "http://collector:4318/v1/metrics"))).unwrap();

        assertThat(settings.otlp().unwrap().step()).isEqualTo(Duration.ofMinutes(1));
        assertThat(settings.otlp().unwrap().resourceAttributes()).isEmpty();
    }

    @Test void settings_resourceAttributesWithoutOtlpUrl_areAcceptedAndBuildNoOtlpSink() {
        var settings = TerraMetrics.Settings.settings(config(Map.of("management.opentelemetry.resource-attributes.service.name", "shop"))).unwrap();

        assertThat(settings.otlp().isEmpty()).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "management.prometheus.metrics.export.enabled|maybe|must be true or false",
        "management.prometheus.metrics.export.step|10s|Unsupported metric sink setting: management.prometheus.metrics.export.step",
        "management.otlp.metrics.export.headers.authorization|secret|Unsupported metric sink setting: management.otlp.metrics.export.headers.authorization",
        "management.otlp.metrics.export.step|30s|step is set but management.otlp.metrics.export.url is not"})
    void settings_unsupportedOrInvalidKey_isRefusedNotIgnored(String key, String value, String message) {
        var result = TerraMetrics.Settings.settings(config(Map.of(key, value)));

        assertThat(result.<String>fold(cause -> cause.message(), _ -> "unexpected success")).contains(message);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"not a url|30s", "http://collector:4318/v1/metrics|0s", "http://collector:4318/v1/metrics|soon"})
    void settings_invalidOtlpUrlOrStep_isRefused(String url, String step) {
        var result = TerraMetrics.Settings.settings(config(Map.of("management.otlp.metrics.export.url", url,
                                                                  "management.otlp.metrics.export.step", step)));

        assertThat(result.isFailure()).as(url + " / " + step).isTrue();
    }

    @Test void otlp_configured_collectorReceivesTheMetersWithResourceAttributes() throws Exception {
        var received = new CompletableFuture<byte[]>();
        var collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        collector.createContext("/v1/metrics", exchange -> receive(exchange, received));
        collector.start();
        try {
            var url = "http://127.0.0.1:" + collector.getAddress().getPort() + "/v1/metrics";
            // A one-hour step: the only push in this test is the final one close() performs.
            var settings = TerraMetrics.Settings.settings(config(Map.of("management.otlp.metrics.export.url", url,
                                                                        "management.otlp.metrics.export.step", "1h",
                                                                        "management.opentelemetry.resource-attributes.service.name", "terra-probe-service"))).unwrap();
            var metrics = TerraMetrics.terraMetrics(settings).unwrap();

            metrics.registry().counter("terra.otlp.probe").increment();
            assertThat(metrics.close().isSuccess()).isTrue();

            var body = new String(received.get(10, TimeUnit.SECONDS), StandardCharsets.ISO_8859_1);

            assertThat(body).contains("terra.otlp.probe", "service.name", "terra-probe-service");
        } finally {
            collector.stop(0);
        }
    }

    @Test void otlp_shortStep_pushesWithoutCloseAtTheConfiguredInterval() throws Exception {
        var received = new CompletableFuture<byte[]>();
        var collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        collector.createContext("/v1/metrics", exchange -> receiveContaining(exchange, "terra.otlp.step", received));
        collector.start();
        var metrics = TerraMetrics.terraMetrics(TerraMetrics.Settings.settings(config(Map.of("management.otlp.metrics.export.url",
                                                                                              "http://127.0.0.1:" + collector.getAddress().getPort() + "/v1/metrics",
                                                                                              "management.otlp.metrics.export.step", "1s"))).unwrap()).unwrap();
        try {
            // The default step is one minute: a push within 20 s, with no close(), proves the configured step is the one in force.
            var increments = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

            increments.scheduleAtFixedRate(() -> metrics.registry().counter("terra.otlp.step").increment(), 0, 200, TimeUnit.MILLISECONDS);
            try {
                assertThat(new String(received.get(20, TimeUnit.SECONDS), StandardCharsets.ISO_8859_1)).contains("terra.otlp.step");
            } finally {
                increments.shutdownNow();
            }
        } finally {
            metrics.close();
            collector.stop(0);
        }
    }

    private static void receiveContaining(HttpExchange exchange, String marker, CompletableFuture<byte[]> received) throws java.io.IOException {
        try (exchange) {
            var body = exchange.getRequestBody().readAllBytes();

            if (new String(body, StandardCharsets.ISO_8859_1).contains(marker)) {
                received.complete(body);
            }
            exchange.sendResponseHeaders(200, -1);
        }
    }

    private static void receive(HttpExchange exchange, CompletableFuture<byte[]> received) throws java.io.IOException {
        try (exchange) {
            received.complete(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(200, -1);
        }
    }
}
