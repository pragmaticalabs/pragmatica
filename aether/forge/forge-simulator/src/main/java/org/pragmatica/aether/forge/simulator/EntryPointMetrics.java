package org.pragmatica.aether.forge.simulator;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Result.unitResult;

/// Collects per-entry-point metrics for the simulator.
///
/// Provides thread-safe metrics collection with histogram-based latency tracking.
/// Designed for high-throughput concurrent access.
public final class EntryPointMetrics {
    /// Histogram bucket boundaries in milliseconds.
    private static final long[] BUCKET_BOUNDARIES_MS = {1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000};

    private final Map<String, EntryPointStats> stats = new ConcurrentHashMap<>();
    private final AtomicLong lastSnapshotTime = new AtomicLong(System.currentTimeMillis());

    private EntryPointMetrics() {}

    public static EntryPointMetrics entryPointMetrics() {
        return new EntryPointMetrics();
    }

    /// Record a successful invocation.
    public Result<Unit> recordSuccess(String entryPoint, long latencyNanos) {
        getOrCreate(entryPoint).recordSuccess(latencyNanos);
        return unitResult();
    }

    /// Record a failed invocation.
    public Result<Unit> recordFailure(String entryPoint, long latencyNanos) {
        getOrCreate(entryPoint).recordFailure(latencyNanos);
        return unitResult();
    }

    /// Set the current rate for an entry point.
    public Result<Unit> setRate(String entryPoint, int callsPerSecond) {
        getOrCreate(entryPoint).currentRate.set(callsPerSecond);
        return unitResult();
    }

    /// Take a snapshot of all entry points and reset counters.
    public List<EntryPointSnapshot> snapshotAndReset() {
        var now = System.currentTimeMillis();
        var previousTime = lastSnapshotTime.getAndSet(now);
        var elapsedMs = now - previousTime;
        var result = new ArrayList<EntryPointSnapshot>();
        stats.forEach((name, stat) -> result.add(stat.snapshotAndReset(name, elapsedMs)));
        return result;
    }

    /// Take a snapshot without resetting.
    public List<EntryPointSnapshot> snapshot() {
        var now = System.currentTimeMillis();
        var elapsedMs = now - lastSnapshotTime.get();
        var result = new ArrayList<EntryPointSnapshot>();
        stats.forEach((name, stat) -> result.add(stat.snapshot(name, elapsedMs)));
        return result;
    }

    /// Reset all metrics.
    public Result<Unit> reset() {
        stats.values()
             .forEach(EntryPointStats::reset);
        lastSnapshotTime.set(System.currentTimeMillis());
        return unitResult();
    }

    private EntryPointStats getOrCreate(String entryPoint) {
        return stats.computeIfAbsent(entryPoint,
                                     _ -> EntryPointStats.entryPointStats()
                                                         .unwrap());
    }

    /// Per-entry-point statistics.
    private record EntryPointStats(AtomicLong totalCount,
                                   AtomicLong successCount,
                                   AtomicLong failureCount,
                                   AtomicLong totalLatencyNanos,
                                   AtomicInteger currentRate,
                                   AtomicLong windowCount,
                                   AtomicLong windowLatencyNanos,
                                   AtomicLong[] histogram) {
        static Result<EntryPointStats> entryPointStats() {
            var hist = IntStream.range(0, BUCKET_BOUNDARIES_MS.length + 1)
                                .mapToObj(_ -> new AtomicLong())
                                .toArray(AtomicLong[]::new);
            return success(new EntryPointStats(new AtomicLong(),
                                               new AtomicLong(),
                                               new AtomicLong(),
                                               new AtomicLong(),
                                               new AtomicInteger(),
                                               new AtomicLong(),
                                               new AtomicLong(),
                                               hist));
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
            return IntStream.range(0, BUCKET_BOUNDARIES_MS.length)
                            .filter(i -> latencyMs <= BUCKET_BOUNDARIES_MS[i])
                            .findFirst()
                            .orElse(BUCKET_BOUNDARIES_MS.length);
        }

        EntryPointSnapshot snapshotAndReset(String name, long elapsedMs) {
            var snap = snapshot(name, elapsedMs);
            windowCount.set(0);
            windowLatencyNanos.set(0);
            return snap;
        }

        EntryPointSnapshot snapshot(String name, long elapsedMs) {
            var total = totalCount.get();
            var rates = calculateRates(total, windowCount.get(), elapsedMs);
            return buildSnapshot(name, total, rates);
        }

        private EntryPointSnapshot buildSnapshot(String name, long total, SnapshotRates rates) {
            var rate = currentRate.get();
            var successes = successCount.get();
            var failures = failureCount.get();
            var p50 = estimatePercentile(50, total);
            var p99 = estimatePercentile(99, total);
            return EntryPointSnapshot.entryPointSnapshot(name,
                                                         rate,
                                                         total,
                                                         successes,
                                                         failures,
                                                         rates.successRate(),
                                                         rates.avgLatencyMs(),
                                                         rates.rps(),
                                                         p50,
                                                         p99)
                                     .unwrap();
        }

        private SnapshotRates calculateRates(long total, long windowCnt, long elapsedMs) {
            var successRate = calculateSuccessRate(total);
            var avgLatencyMs = calculateAvgLatency(windowCnt);
            var rps = calculateRps(windowCnt, elapsedMs);
            return SnapshotRates.snapshotRates(successRate, avgLatencyMs, rps)
                                .unwrap();
        }

        private double calculateSuccessRate(long total) {
            if (Verify.Is.nonPositive(total)) {
                return 100.0;
            }
            return successCount.get() * 100.0 / total;
        }

        private double calculateAvgLatency(long windowCnt) {
            if (Verify.Is.nonPositive(windowCnt)) {
                return 0.0;
            }
            return (windowLatencyNanos.get() / windowCnt) / 1_000_000.0;
        }

        private static double calculateRps(long windowCnt, long elapsedMs) {
            if (Verify.Is.nonPositive(elapsedMs)) {
                return 0.0;
            }
            return windowCnt * 1000.0 / elapsedMs;
        }

        private double estimatePercentile(int percentile, long total) {
            if (total == 0) {
                return 0.0;
            }
            var targetCount = (long)(total * percentile / 100.0);
            return findBucketForPercentile(targetCount);
        }

        private double findBucketForPercentile(long targetCount) {
            var cumulative = new AtomicLong(0);
            var matchingBucket = IntStream.range(0, histogram.length)
                                          .filter(i -> cumulative.addAndGet(histogram[i].get()) >= targetCount)
                                          .findFirst();
            return matchingBucket.isPresent()
                   ? bucketBoundary(matchingBucket.getAsInt())
                   : 10000.0;
        }

        private static double bucketBoundary(int bucketIndex) {
            return bucketIndex < BUCKET_BOUNDARIES_MS.length
                   ? BUCKET_BOUNDARIES_MS[bucketIndex]
                   : 10000.0;
        }

        void reset() {
            totalCount.set(0);
            successCount.set(0);
            failureCount.set(0);
            totalLatencyNanos.set(0);
            windowCount.set(0);
            windowLatencyNanos.set(0);
            Arrays.stream(histogram)
                  .forEach(bucket -> bucket.set(0));
        }
    }

    /// Intermediate rates for snapshot calculation.
    private record SnapshotRates(double successRate, double avgLatencyMs, double rps) {
        static Result<SnapshotRates> snapshotRates(double successRate, double avgLatencyMs, double rps) {
            return success(new SnapshotRates(successRate, avgLatencyMs, rps));
        }
    }

    /// Snapshot of entry point metrics.
    public record EntryPointSnapshot(String name,
                                     int rate,
                                     long totalCalls,
                                     long successCalls,
                                     long failureCalls,
                                     double successRate,
                                     double avgLatencyMs,
                                     double requestsPerSecond,
                                     double p50LatencyMs,
                                     double p99LatencyMs) {
        public static Result<EntryPointSnapshot> entryPointSnapshot(String name,
                                                                    int rate,
                                                                    long totalCalls,
                                                                    long successCalls,
                                                                    long failureCalls,
                                                                    double successRate,
                                                                    double avgLatencyMs,
                                                                    double requestsPerSecond,
                                                                    double p50LatencyMs,
                                                                    double p99LatencyMs) {
            return success(new EntryPointSnapshot(name,
                                                  rate,
                                                  totalCalls,
                                                  successCalls,
                                                  failureCalls,
                                                  successRate,
                                                  avgLatencyMs,
                                                  requestsPerSecond,
                                                  p50LatencyMs,
                                                  p99LatencyMs));
        }

        public String toJson() {
            return String.format("{\"name\":\"%s\",\"rate\":%d,\"totalCalls\":%d,\"successCalls\":%d,"
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
