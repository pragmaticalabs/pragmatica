package org.pragmatica.aether.ttm.model;

/**
 * Feature indices matching {@link org.pragmatica.aether.metrics.MinuteAggregate#toFeatureArray()} order.
 * <p>
 * Features: cpu_usage, heap_usage, event_loop_lag_ms, latency_ms, invocations,
 * gc_pause_ms, latency_p50, latency_p95, latency_p99, error_rate, event_count
 */
public sealed interface FeatureIndex {
    int CPU_USAGE = 0;
    int HEAP_USAGE = 1;
    int EVENT_LOOP_LAG_MS = 2;
    int LATENCY_MS = 3;
    int INVOCATIONS = 4;
    int GC_PAUSE_MS = 5;
    int LATENCY_P50 = 6;
    int LATENCY_P95 = 7;
    int LATENCY_P99 = 8;
    int ERROR_RATE = 9;
    int EVENT_COUNT = 10;
    int FEATURE_COUNT = 11;

    /**
     * Feature names in order (matches MinuteAggregate.featureNames()).
     */
    static String[] featureNames() {
        return new String[]{"cpu_usage", "heap_usage", "event_loop_lag_ms", "latency_ms", "invocations",
                            "gc_pause_ms", "latency_p50", "latency_p95", "latency_p99", "error_rate", "event_count"};
    }

    record Unused() implements FeatureIndex {}
}
