package org.pragmatica.aether.metrics.invocation;

import org.pragmatica.aether.slice.MethodName;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Aggregated metrics for a single slice method.
 * <p>
 * This class is thread-safe and designed for high-frequency updates.
 * All counters use atomic operations for lock-free concurrent access.
 * <p>
 * Histogram buckets provide latency distribution:
 * <ul>
 *   <li>Bucket 0: &lt; 1ms</li>
 *   <li>Bucket 1: 1ms - 10ms</li>
 *   <li>Bucket 2: 10ms - 100ms</li>
 *   <li>Bucket 3: 100ms - 1s</li>
 *   <li>Bucket 4: &gt;= 1s</li>
 * </ul>
 */
public final class MethodMetrics {
    // Histogram bucket thresholds in nanoseconds
    private static final long BUCKET_1MS = 1_000_000L;
    private static final long BUCKET_10MS = 10_000_000L;
    private static final long BUCKET_100MS = 100_000_000L;
    private static final long BUCKET_1S = 1_000_000_000L;

    private static final int HISTOGRAM_SIZE = 5;

    private final MethodName methodName;
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicLong totalDurationNs = new AtomicLong();
    private final AtomicInteger[] histogram;

    public MethodMetrics(MethodName methodName) {
        this.methodName = methodName;
        this.histogram = new AtomicInteger[HISTOGRAM_SIZE];
        for (int i = 0; i < HISTOGRAM_SIZE; i++ ) {
            histogram[i] = new AtomicInteger();
        }
    }

    /**
     * Record an invocation result.
     *
     * @param durationNs Duration in nanoseconds
     * @param success    Whether the invocation succeeded
     */
    public void record(long durationNs, boolean success) {
        count.incrementAndGet();
        if (success) {
            successCount.incrementAndGet();
        }else {
            failureCount.incrementAndGet();
        }
        totalDurationNs.addAndGet(durationNs);
        histogram[bucketFor(durationNs)]
        .incrementAndGet();
    }

    /**
     * Take a snapshot of current metrics and reset counters.
     *
     * @return Immutable snapshot of current state
     */
    public Snapshot snapshotAndReset() {
        var snapshotCount = count.getAndSet(0);
        var snapshotSuccess = successCount.getAndSet(0);
        var snapshotFailure = failureCount.getAndSet(0);
        var snapshotDuration = totalDurationNs.getAndSet(0);
        var snapshotHistogram = new int[HISTOGRAM_SIZE];
        for (int i = 0; i < HISTOGRAM_SIZE; i++ ) {
            snapshotHistogram[i] = histogram[i].getAndSet(0);
        }
        return new Snapshot(
        methodName, snapshotCount, snapshotSuccess, snapshotFailure, snapshotDuration, snapshotHistogram);
    }

    /**
     * Take a snapshot without resetting (for monitoring).
     */
    public Snapshot snapshot() {
        var snapshotHistogram = new int[HISTOGRAM_SIZE];
        for (int i = 0; i < HISTOGRAM_SIZE; i++ ) {
            snapshotHistogram[i] = histogram[i].get();
        }
        return new Snapshot(
        methodName, count.get(), successCount.get(), failureCount.get(), totalDurationNs.get(), snapshotHistogram);
    }

    public MethodName methodName() {
        return methodName;
    }

    public long count() {
        return count.get();
    }

    public long totalDurationNs() {
        return totalDurationNs.get();
    }

    private static int bucketFor(long durationNs) {
        if (durationNs < BUCKET_1MS) return 0;
        if (durationNs < BUCKET_10MS) return 1;
        if (durationNs < BUCKET_100MS) return 2;
        if (durationNs < BUCKET_1S) return 3;
        return 4;
    }

    /**
     * Immutable snapshot of method metrics.
     */
    public record Snapshot(
    MethodName methodName,
    long count,
    long successCount,
    long failureCount,
    long totalDurationNs,
    int[] histogram) {
        /**
         * Calculate average latency in nanoseconds.
         */
        public long averageLatencyNs() {
            return count > 0
                   ? totalDurationNs / count
                   : 0;
        }

        /**
         * Calculate success rate (0.0 to 1.0).
         */
        public double successRate() {
            return count > 0
                   ? (double) successCount / count
                   : 1.0;
        }

        /**
         * Estimate percentile from histogram.
         * This is an approximation based on bucket boundaries.
         *
         * @param percentile Value between 0 and 100 (e.g., 95 for p95)
         * @return Estimated latency in nanoseconds
         */
        public long estimatePercentileNs(int percentile) {
            if (count == 0) return 0;
            long target = (count * percentile) / 100;
            long cumulative = 0;
            for (int i = 0; i < histogram.length; i++ ) {
                cumulative += histogram[i];
                if (cumulative >= target) {
                    return bucketUpperBound(i);
                }
            }
            return BUCKET_1S;
        }

        private static long bucketUpperBound(int bucket) {
            return switch (bucket) {
                case 0 -> BUCKET_1MS;
                case 1 -> BUCKET_10MS;
                case 2 -> BUCKET_100MS;
                case 3 -> BUCKET_1S;
                default -> BUCKET_1S * 10;
            };
        }
    }
}
