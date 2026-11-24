// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.NullReturn;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.vo.Url;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;


/// Where a Terra application's meters go (#3650). One composite registry is handed to every resource provisioning
/// context, so `@Metrics` interceptors and resource clients record into it. An in-memory child keeps every meter
/// readable (what `/actuator/metrics` reads) when no sink is configured; each export sink is off unless configured.
/// The interceptors decide WHAT is observed; these sinks decide only WHERE it goes.
public final class TerraMetrics {
    /// Prometheus text exposition format 0.0.4, the format [#prometheusScrape()] renders.
    public static final String PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private static final String PROMETHEUS = "management.prometheus.metrics.export.";
    private static final String OTLP = "management.otlp.metrics.export.";
    private static final String RESOURCE_ATTRIBUTES = "management.opentelemetry.resource-attributes.";
    private static final Set<String> SUPPORTED_PROMETHEUS = Set.of(PROMETHEUS + "enabled");
    private static final Set<String> SUPPORTED_OTLP = Set.of(OTLP + "url", OTLP + "step");
    private static final Duration DEFAULT_STEP = Duration.ofMinutes(1);

    private final CompositeMeterRegistry registry;
    private final Option<PrometheusMeterRegistry> prometheus;

    private TerraMetrics(CompositeMeterRegistry registry, Option<PrometheusMeterRegistry> prometheus) {
        this.registry = registry;
        this.prometheus = prometheus;
    }

    /// Build the registries the settings select. Fails when a sink registry cannot be constructed.
    public static Result<TerraMetrics> terraMetrics(Settings settings) {
        return Result.lift(Causes::fromThrowable, () -> assemble(settings));
    }

    // The OTLP registry is constructed last: it is the only child that starts a publishing thread, so a failure in an
    // earlier child never leaves one running.
    private static TerraMetrics assemble(Settings settings) {
        var registry = new CompositeMeterRegistry();
        var prometheus = settings.prometheus()
                         ? Option.some(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT))
                         : Option.<PrometheusMeterRegistry>none();

        registry.add(new SimpleMeterRegistry());
        prometheus.onPresent(registry::add);
        settings.otlp()
                .map(TerraMetrics::otlpRegistry)
                .onPresent(registry::add);

        return new TerraMetrics(registry, prometheus);
    }

    private static OtlpMeterRegistry otlpRegistry(Otlp otlp) {
        return new OtlpMeterRegistry(OtlpView.otlpView(otlp), Clock.SYSTEM);
    }

    /// The application registry every provisioning context carries.
    public MeterRegistry registry() {
        return registry;
    }

    /// The Prometheus text exposition of every meter; empty when the Prometheus sink is not enabled.
    public Option<String> prometheusScrape() {
        return prometheus.map(PrometheusMeterRegistry::scrape);
    }

    /// Close every sink; the OTLP sink pushes its last batch before it stops.
    public Result<Unit> close() {
        return Result.lift(Causes::fromThrowable, registry::close);
    }

    /// Metric sink settings, read from the `management.*` keys (Spring Boot property names).
    ///
    /// @param prometheus whether the Prometheus registry is built (`management.prometheus.metrics.export.enabled`)
    /// @param otlp       the OTLP push settings; present when `management.otlp.metrics.export.url` is set
    public record Settings(boolean prometheus, Option<Otlp> otlp) {
        public static Settings settings(boolean prometheus, Option<Otlp> otlp) {
            return new Settings(prometheus, otlp);
        }

        /// No export sink: meters are kept in memory only.
        public static Settings disabled() {
            return settings(false, Option.none());
        }

        /// Parse the metric sink keys. A key under `management.prometheus.metrics.export.` or
        /// `management.otlp.metrics.export.` that Terra does not implement is refused rather than ignored, and so is an
        /// OTLP step without an OTLP url. Resource attributes without an OTLP url are kept unused: the trace exporter
        /// (#3650 part 2) reads the same keys.
        public static Result<Settings> settings(ConfigurationProvider config) {
            return refuseUnknownKeys(config).flatMap(_ -> Result.all(prometheusEnabled(config),
                                                                     otlpSettings(config))
                                                                .map(Settings::settings));
        }
    }

    /// OTLP push settings.
    ///
    /// @param url                the collector's metrics endpoint (`management.otlp.metrics.export.url`)
    /// @param step               the push interval (`management.otlp.metrics.export.step`, default 1m)
    /// @param resourceAttributes the resource attributes (`management.opentelemetry.resource-attributes.<key>`)
    public record Otlp(Url url, Duration step, Map<String, String> resourceAttributes) {
        public static Otlp otlp(Url url, Duration step, Map<String, String> resourceAttributes) {
            return new Otlp(url, step, Map.copyOf(resourceAttributes));
        }
    }

    // Micrometer reads OTLP settings through this view. Every other property keeps Micrometer's default.
    private record OtlpView(Otlp otlp) implements OtlpConfig {
        static OtlpView otlpView(Otlp otlp) {
            return new OtlpView(otlp);
        }

        @NullReturn
        @Override
        public String get(String key) {
            return null;
        }

        @Override
        public String url() {
            return otlp.url()
                       .uri()
                       .toString();
        }

        @Override
        public Duration step() {
            return otlp.step();
        }

        // Configured attributes override the ones Micrometer derives from OTEL_RESOURCE_ATTRIBUTES/OTEL_SERVICE_NAME.
        @Override
        public Map<String, String> resourceAttributes() {
            var attributes = new HashMap<>(OtlpConfig.super.resourceAttributes());

            attributes.putAll(otlp.resourceAttributes());

            return Map.copyOf(attributes);
        }
    }

    private static Result<Unit> refuseUnknownKeys(ConfigurationProvider config) {
        return Option.from(config.keys()
                                 .stream()
                                 .filter(TerraMetrics::isUnknownSinkKey)
                                 .sorted()
                                 .findFirst())
                     .map(TerraMetrics::unsupportedKey)
                     .or(Result.unitResult());
    }

    private static boolean isUnknownSinkKey(String key) {
        return key.startsWith(PROMETHEUS)
               ? !SUPPORTED_PROMETHEUS.contains(key)
               : key.startsWith(OTLP) && !SUPPORTED_OTLP.contains(key);
    }

    private static Result<Unit> unsupportedKey(String key) {
        return new TerraError.InvalidConfiguration("Unsupported metric sink setting: " + key).result();
    }

    private static Result<Boolean> prometheusEnabled(ConfigurationProvider config) {
        return config.getString(PROMETHEUS + "enabled")
                     .map(value -> flag(PROMETHEUS + "enabled", value))
                     .or(Result.success(false));
    }

    private static Result<Boolean> flag(String key, String value) {
        return switch (value.trim()
                            .toLowerCase(Locale.ROOT)) {
            case "true" -> Result.success(true);
            case "false" -> Result.success(false);
            default -> new TerraError.InvalidConfiguration(key + " must be true or false: " + value).result();
        };
    }

    private static Result<Option<Otlp>> otlpSettings(ConfigurationProvider config) {
        return config.getString(OTLP + "url")
                     .fold(() -> refuseOrphanedStep(config),
                           url -> otlp(url, config).map(Option::some));
    }

    private static Result<Option<Otlp>> refuseOrphanedStep(ConfigurationProvider config) {
        return config.getString(OTLP + "step")
                     .map(_ -> new TerraError.InvalidConfiguration(OTLP + "step is set but " + OTLP
                                                                   + "url is not").<Option<Otlp>>result())
                     .or(Result.success(Option.none()));
    }

    private static Result<Otlp> otlp(String url, ConfigurationProvider config) {
        return Result.all(Url.url(url),
                          step(config))
                     .map((endpoint, step) -> Otlp.otlp(endpoint, step, resourceAttributes(config)));
    }

    private static Result<Duration> step(ConfigurationProvider config) {
        return config.getString(OTLP + "step")
                     .map(TerraMetrics::positiveStep)
                     .or(Result.success(DEFAULT_STEP));
    }

    private static Result<Duration> positiveStep(String text) {
        return TimeSpan.timeSpan(text)
                       .map(TimeSpan::duration)
                       .filter(new TerraError.InvalidConfiguration(OTLP + "step must be positive: " + text),
                               Duration::isPositive);
    }

    private static Map<String, String> resourceAttributes(ConfigurationProvider config) {
        return config.keys()
                     .stream()
                     .filter(key -> key.startsWith(RESOURCE_ATTRIBUTES))
                     .collect(Collectors.toMap(key -> key.substring(RESOURCE_ATTRIBUTES.length()),
                                               key -> config.getString(key)
                                                            .or("")));
    }
}
