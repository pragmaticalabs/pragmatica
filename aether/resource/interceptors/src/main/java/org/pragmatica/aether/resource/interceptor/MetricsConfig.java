package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;

import static org.pragmatica.lang.Result.all;
import static org.pragmatica.lang.Verify.ensure;

/// Configuration for metrics interceptor.
///
/// @param name         Metric name prefix
/// @param registry     Micrometer meter registry for recording metrics
/// @param recordTiming Record timing information
/// @param recordCounts Record success/failure counts
/// @param tags         Additional tags for metrics (alternating key-value pairs)
public record MetricsConfig(String name,
                            MeterRegistry registry,
                            boolean recordTiming,
                            boolean recordCounts,
                            List<String> tags) {
    /// Create metrics configuration with defaults (timing + counts enabled, no tags).
    ///
    /// @param name     Metric name prefix
    /// @param registry Micrometer registry
    /// @return Result containing configuration or error
    public static Result<MetricsConfig> metricsConfig(String name, MeterRegistry registry) {
        var validName = ensure(name, Verify.Is::notBlank);
        var validRegistry = ensure(registry, Verify.Is::notNull);
        return all(validName, validRegistry).map((n, r) -> new MetricsConfig(n, r, true, true, List.of()));
    }

    /// Create metrics configuration with specific options.
    ///
    /// @param name         Metric name prefix
    /// @param registry     Micrometer registry
    /// @param recordTiming Record timing information
    /// @param recordCounts Record success/failure counts
    /// @return Result containing configuration or error
    public static Result<MetricsConfig> metricsConfig(String name,
                                                      MeterRegistry registry,
                                                      boolean recordTiming,
                                                      boolean recordCounts) {
        var validName = ensure(name, Verify.Is::notBlank);
        var validRegistry = ensure(registry, Verify.Is::notNull);
        return all(validName, validRegistry).map((n, r) -> new MetricsConfig(n, r, recordTiming, recordCounts, List.of()));
    }

    /// Create a copy with additional tags.
    @SuppressWarnings("JBCT-VO-02")
    public MetricsConfig withTags(String... tags) {
        return new MetricsConfig(name, registry, recordTiming, recordCounts, List.of(tags));
    }
}
