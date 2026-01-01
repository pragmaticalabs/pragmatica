package org.pragmatica.aether.forge;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Aggregates metrics for the Forge dashboard.
 * Thread-safe and designed for high-frequency updates.
 */
public final class ForgeMetrics {
    // Rolling window counters (reset every second)
    private final LongAdder successCount = new LongAdder();
    private final LongAdder failureCount = new LongAdder();
    private final LongAdder totalLatencyNanos = new LongAdder();
    private final LongAdder requestCount = new LongAdder();

    // Cumulative counters
    private final AtomicLong totalSuccess = new AtomicLong(0);
    private final AtomicLong totalFailures = new AtomicLong(0);

    // Last snapshot values (for rate calculation)
    private volatile long lastSuccessSnapshot = 0;
    private volatile long lastFailureSnapshot = 0;
    private volatile long lastSnapshotTime = System.currentTimeMillis();

    // Current rates (updated by snapshot)
    private volatile double requestsPerSecond = 0;
    private volatile double successRate = 100.0;
    private volatile double avgLatencyMs = 0;

    private ForgeMetrics() {}

    public static ForgeMetrics forgeMetrics() {
        return new ForgeMetrics();
    }

    /**
     * Record a successful request with latency.
     */
    public void recordSuccess(long latencyNanos) {
        successCount.increment();
        totalSuccess.incrementAndGet();
        totalLatencyNanos.add(latencyNanos);
        requestCount.increment();
    }

    /**
     * Record a failed request.
     */
    public void recordFailure(long latencyNanos) {
        failureCount.increment();
        totalFailures.incrementAndGet();
        totalLatencyNanos.add(latencyNanos);
        requestCount.increment();
    }

    /**
     * Take a snapshot and calculate rates.
     * Should be called periodically (e.g., every 500ms).
     */
    public void snapshot() {
        var now = System.currentTimeMillis();
        var elapsed = now - lastSnapshotTime;
        if (elapsed <= 0) elapsed = 1;
        var currentSuccess = totalSuccess.get();
        var currentFailure = totalFailures.get();
        var successDelta = currentSuccess - lastSuccessSnapshot;
        var failureDelta = currentFailure - lastFailureSnapshot;
        var totalDelta = successDelta + failureDelta;
        // Calculate rates
        requestsPerSecond = (totalDelta * 1000.0) / elapsed;
        if (totalDelta > 0) {
            successRate = (successDelta * 100.0) / totalDelta;
        }else {
            successRate = 100.0;
        }
        // Calculate average latency from recent requests
        var count = requestCount.sumThenReset();
        var latency = totalLatencyNanos.sumThenReset();
        if (count > 0) {
            avgLatencyMs = (latency / count) / 1_000_000.0;
        }
        // Reset window counters
        successCount.reset();
        failureCount.reset();
        // Update snapshot markers
        lastSuccessSnapshot = currentSuccess;
        lastFailureSnapshot = currentFailure;
        lastSnapshotTime = now;
    }

    /**
     * Get current metrics for dashboard.
     */
    public MetricsSnapshot currentMetrics() {
        return new MetricsSnapshot(
        requestsPerSecond, successRate, avgLatencyMs, totalSuccess.get(), totalFailures.get());
    }

    /**
     * Reset all metrics.
     */
    public void reset() {
        successCount.reset();
        failureCount.reset();
        totalLatencyNanos.reset();
        requestCount.reset();
        totalSuccess.set(0);
        totalFailures.set(0);
        lastSuccessSnapshot = 0;
        lastFailureSnapshot = 0;
        lastSnapshotTime = System.currentTimeMillis();
        requestsPerSecond = 0;
        successRate = 100.0;
        avgLatencyMs = 0;
    }

    /**
     * Metrics snapshot for dashboard.
     */
    public record MetricsSnapshot(
    double requestsPerSecond,
    double successRate,
    double avgLatencyMs,
    long totalSuccess,
    long totalFailures) {
        public long totalRequests() {
            return totalSuccess + totalFailures;
        }
    }
}
