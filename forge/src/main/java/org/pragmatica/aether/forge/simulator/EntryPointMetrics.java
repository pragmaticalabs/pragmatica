package org.pragmatica.aether.forge.simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects per-entry-point metrics for the simulator.
 * <p>
 * Provides thread-safe metrics collection with histogram-based latency tracking.
 * Designed for high-throughput concurrent access.
 */
public final class EntryPointMetrics {
    /**
     * Histogram bucket boundaries in milliseconds.
     */
    private static final long[] BUCKET_BOUNDARIES_MS = {1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000};

    private final Map<String, EntryPointStats> stats = new ConcurrentHashMap<>();
    private volatile long lastSnapshotTime = System.currentTimeMillis();

    private EntryPointMetrics() {}

    public static EntryPointMetrics entryPointMetrics() {
        return new EntryPointMetrics();
    }

    /**
     * Record a successful invocation.
     */
    public void recordSuccess(String entryPoint, long latencyNanos) {
        getOrCreate(entryPoint)
        .recordSuccess(latencyNanos);
    }

    /**
     * Record a failed invocation.
     */
    public void recordFailure(String entryPoint, long latencyNanos) {
        getOrCreate(entryPoint)
        .recordFailure(latencyNanos);
    }

    /**
     * Set the current rate for an entry point.
     */
    public void setRate(String entryPoint, int callsPerSecond) {
        getOrCreate(entryPoint).currentRate
        .set(callsPerSecond);
    }

    /**
     * Take a snapshot of all entry points and reset counters.
     */
    public List<EntryPointSnapshot> snapshotAndReset() {
        var now = System.currentTimeMillis();
        var elapsedMs = now - lastSnapshotTime;
        lastSnapshotTime = now;
        var result = new ArrayList<EntryPointSnapshot>();
        stats.forEach((name, stat) -> result.add(stat.snapshotAndReset(name, elapsedMs)));
        return result;
    }

    /**
     * Take a snapshot without resetting.
     */
    public List<EntryPointSnapshot> snapshot() {
        var now = System.currentTimeMillis();
        var elapsedMs = now - lastSnapshotTime;
        var result = new ArrayList<EntryPointSnapshot>();
        stats.forEach((name, stat) -> result.add(stat.snapshot(name, elapsedMs)));
        return result;
    }

    /**
     * Reset all metrics.
     */
    public void reset() {
        stats.values()
             .forEach(EntryPointStats::reset);
        lastSnapshotTime = System.currentTimeMillis();
    }

    private EntryPointStats getOrCreate(String entryPoint) {
        return stats.computeIfAbsent(entryPoint, _ -> new EntryPointStats());
    }

    /**
     * Per-entry-point statistics.
     */
    private static final class EntryPointStats {
        final AtomicLong totalCount = new AtomicLong();
        final AtomicLong successCount = new AtomicLong();
        final AtomicLong failureCount = new AtomicLong();
        final AtomicLong totalLatencyNanos = new AtomicLong();
        final AtomicInteger currentRate = new AtomicInteger();

        // Window counters (for rate calculation)
        final AtomicLong windowCount = new AtomicLong();
        final AtomicLong windowLatencyNanos = new AtomicLong();

        // Histogram buckets
        final AtomicLong[] histogram = new AtomicLong[BUCKET_BOUNDARIES_MS.length + 1];

        EntryPointStats() {
            for (int i = 0; i < histogram.length; i++ ) {
                histogram[i] = new AtomicLong();
            }
        }

        void recordSuccess(long latencyNanos) {
            totalCount.incrementAndGet();
            successCount.incrementAndGet();
            totalLatencyNanos.addAndGet(latencyNanos);
            windowCount.incrementAndGet();
            windowLatencyNanos.addAndGet(latencyNanos);
            recordLatency(latencyNanos);
        }

        void recordFailure(long latencyNanos) {
            totalCount.incrementAndGet();
            failureCount.incrementAndGet();
            totalLatencyNanos.addAndGet(latencyNanos);
            windowCount.incrementAndGet();
            windowLatencyNanos.addAndGet(latencyNanos);
            recordLatency(latencyNanos);
        }

        private void recordLatency(long latencyNanos) {
            var latencyMs = latencyNanos / 1_000_000;
            var bucket = findBucket(latencyMs);
            histogram[bucket].incrementAndGet();
        }

        private int findBucket(long latencyMs) {
            for (int i = 0; i < BUCKET_BOUNDARIES_MS.length; i++ ) {
                if (latencyMs <= BUCKET_BOUNDARIES_MS[i]) {
                    return i;
                }
            }
            return BUCKET_BOUNDARIES_MS.length;
        }

        EntryPointSnapshot snapshotAndReset(String name, long elapsedMs) {
            var snapshot = snapshot(name, elapsedMs);
            windowCount.set(0);
            windowLatencyNanos.set(0);
            return snapshot;
        }

        EntryPointSnapshot snapshot(String name, long elapsedMs) {
            var total = totalCount.get();
            var success = successCount.get();
            var failure = failureCount.get();
            var windowCnt = windowCount.get();
            var windowLatency = windowLatencyNanos.get();
            var rate = currentRate.get();
            var successRate = total > 0
                              ? (success * 100.0 / total)
                              : 100.0;
            var avgLatencyMs = windowCnt > 0
                               ? (windowLatency / windowCnt) / 1_000_000.0
                               : 0.0;
            var rps = elapsedMs > 0
                      ? (windowCnt * 1000.0 / elapsedMs)
                      : 0.0;
            var p50 = estimatePercentile(50, total);
            var p99 = estimatePercentile(99, total);
            return new EntryPointSnapshot(
            name, rate, total, success, failure, successRate, avgLatencyMs, rps, p50, p99);
        }

        private double estimatePercentile(int percentile, long total) {
            if (total == 0) return 0.0;
            var targetCount = (long)(total * percentile / 100.0);
            long cumulative = 0;
            for (int i = 0; i < histogram.length; i++ ) {
                cumulative += histogram[i].get();
                if (cumulative >= targetCount) {
                    // Return bucket upper boundary
                    return i < BUCKET_BOUNDARIES_MS.length
                           ? BUCKET_BOUNDARIES_MS[i]
                           : 10000.0;
                }
            }
            return 10000.0;
        }

        void reset() {
            totalCount.set(0);
            successCount.set(0);
            failureCount.set(0);
            totalLatencyNanos.set(0);
            windowCount.set(0);
            windowLatencyNanos.set(0);
            for (var bucket : histogram) {
                bucket.set(0);
            }
        }
    }

    /**
     * Snapshot of entry point metrics.
     */
    public record EntryPointSnapshot(
    String name,
    int rate,
    long totalCalls,
    long successCalls,
    long failureCalls,
    double successRate,
    double avgLatencyMs,
    double requestsPerSecond,
    double p50LatencyMs,
    double p99LatencyMs) {
        public String toJson() {
            return String.format(
            "{\"name\":\"%s\",\"rate\":%d,\"totalCalls\":%d,\"successCalls\":%d,"
            + "\"failureCalls\":%d,\"successRate\":%.2f,\"avgLatencyMs\":%.2f,"
            + "\"requestsPerSecond\":%.1f,\"p50LatencyMs\":%.1f,\"p99LatencyMs\":%.1f}",
            name,
            rate,
            totalCalls,
            successCalls,
            failureCalls,
            successRate,
            avgLatencyMs,
            requestsPerSecond,
            p50LatencyMs,
            p99LatencyMs);
        }
    }
}
