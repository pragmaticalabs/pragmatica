package org.pragmatica.aether.forge;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/// Aggregates metrics for the Forge dashboard.
/// Thread-safe and designed for high-frequency updates.
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

    // EMA smoothing factor: 0.1 gives ~5s effective window at 500ms snapshots
    private static final double EMA_ALPHA = 0.1;

    // Current rates (updated by snapshot)
    private volatile double requestsPerSecond = 0;
    private volatile double successRate = 100.0;
    private volatile double avgLatencyMs = 0;

    private ForgeMetrics() {}

    public static ForgeMetrics forgeMetrics() {
        return new ForgeMetrics();
    }

    /// Record a successful request with latency.
    public void recordSuccess(long latencyNanos) {
        successCount.increment();
        totalSuccess.incrementAndGet();
        totalLatencyNanos.add(latencyNanos);
        requestCount.increment();
    }

    /// Record a failed request.
    public void recordFailure(long latencyNanos) {
        failureCount.increment();
        totalFailures.incrementAndGet();
        totalLatencyNanos.add(latencyNanos);
        requestCount.increment();
    }

    /// Take a snapshot and calculate rates.
    /// Should be called periodically (e.g., every 500ms).
    public synchronized void snapshot() {
        var now = System.currentTimeMillis();
        var elapsed = now - lastSnapshotTime;
        if (elapsed <= 0) elapsed = 1;
        var currentSuccess = totalSuccess.get();
        var currentFailure = totalFailures.get();
        var successDelta = currentSuccess - lastSuccessSnapshot;
        var failureDelta = currentFailure - lastFailureSnapshot;
        var totalDelta = successDelta + failureDelta;
        // Calculate rates with EMA smoothing (~5s effective window)
        var instantRps = (totalDelta * 1000.0) / elapsed;
        requestsPerSecond = requestsPerSecond == 0
                            ? instantRps
                            : EMA_ALPHA * instantRps + (1 - EMA_ALPHA) * requestsPerSecond;
        if (totalDelta > 0) {
            var instantSuccessRate = (successDelta * 100.0) / totalDelta;
            successRate = EMA_ALPHA * instantSuccessRate + (1 - EMA_ALPHA) * successRate;
        }
        // Calculate average latency with EMA smoothing
        var count = requestCount.sumThenReset();
        var latency = totalLatencyNanos.sumThenReset();
        if (count > 0) {
            var instantLatencyMs = (latency / count) / 1_000_000.0;
            avgLatencyMs = avgLatencyMs == 0
                           ? instantLatencyMs
                           : EMA_ALPHA * instantLatencyMs + (1 - EMA_ALPHA) * avgLatencyMs;
        }
        // Reset window counters
        successCount.reset();
        failureCount.reset();
        // Update snapshot markers
        lastSuccessSnapshot = currentSuccess;
        lastFailureSnapshot = currentFailure;
        lastSnapshotTime = now;
    }

    /// Get current metrics for dashboard.
    /// Synchronized to match snapshot() for consistent reads across all volatile fields.
    public synchronized MetricsSnapshot currentMetrics() {
        return MetricsSnapshot.metricsSnapshot(requestsPerSecond,
                                               successRate,
                                               avgLatencyMs,
                                               totalSuccess.get(),
                                               totalFailures.get());
    }

    /// Reset all metrics.
    public synchronized void reset() {
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

    /// Metrics snapshot for dashboard.
    public record MetricsSnapshot(double requestsPerSecond,
                                  double successRate,
                                  double avgLatencyMs,
                                  long totalSuccess,
                                  long totalFailures) {
        public static MetricsSnapshot metricsSnapshot(double requestsPerSecond,
                                                      double successRate,
                                                      double avgLatencyMs,
                                                      long totalSuccess,
                                                      long totalFailures) {
            return new MetricsSnapshot(requestsPerSecond, successRate, avgLatencyMs, totalSuccess, totalFailures);
        }

        public long totalRequests() {
            return totalSuccess + totalFailures;
        }
    }
}
