package org.pragmatica.aether.infra.aspect;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import java.util.List;

/**
 * Configuration for metrics aspect.
 *
 * @param name         Metric name prefix
 * @param recordTiming Record timing information
 * @param recordCounts Record success/failure counts
 * @param tags         Additional tags for metrics (key-value pairs)
 */
public record MetricsConfig(String name,
                            boolean recordTiming,
                            boolean recordCounts,
                            List<String> tags) {
    /**
     * Create metrics configuration with validation.
     *
     * @param name Metric name prefix
     * @return Result containing configuration or error
     */
    public static Result<MetricsConfig> metricsConfig(String name) {
        return Verify.ensure(name, Verify.Is::notBlank)
                     .map(n -> new MetricsConfig(n,
                                                 true,
                                                 true,
                                                 List.of()));
    }

    /**
     * Create metrics configuration with timing and counts.
     *
     * @param name         Metric name prefix
     * @param recordTiming Record timing information
     * @param recordCounts Record success/failure counts
     * @return Result containing configuration or error
     */
    public static Result<MetricsConfig> metricsConfig(String name, boolean recordTiming, boolean recordCounts) {
        return Verify.ensure(name, Verify.Is::notBlank)
                     .map(n -> new MetricsConfig(n,
                                                 recordTiming,
                                                 recordCounts,
                                                 List.of()));
    }

    /**
     * Create configuration with additional tags.
     */
    public MetricsConfig withTags(String... tags) {
        return new MetricsConfig(name, recordTiming, recordCounts, List.of(tags));
    }
}
