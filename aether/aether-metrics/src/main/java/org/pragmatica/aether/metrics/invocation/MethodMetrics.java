package org.pragmatica.aether.metrics.invocation;

import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.pragmatica.lang.Result.unitResult;


/// Aggregated metrics for a single slice method.
///
/// This class is thread-safe and designed for high-frequency updates.
/// All counters use atomic operations for lock-free concurrent access.
///
/// Histogram buckets provide latency distribution:
///
///   - Bucket 0: < 1ms
///   - Bucket 1: 1ms - 10ms
///   - Bucket 2: 10ms - 100ms
///   - Bucket 3: 100ms - 1s
///   - Bucket 4: >= 1s
///
public final class MethodMetrics {
    private static final long BUCKET_1MS = 1_000_000L;

    private static final long BUCKET_10MS = 10_000_000L;

    private static final long BUCKET_100MS = 100_000_000L;

    private static final long BUCKET_1S = 1_000_000_000L;

    private static final int HISTOGRAM_SIZE = 5;

    private static final int LATENCY_BUFFER_SIZE = 1024;

    private final MethodName methodName;

    private final AtomicLong count = new AtomicLong();

    private final AtomicLong successCount = new AtomicLong();

    private final AtomicLong failureCount = new AtomicLong();

    private final AtomicLong totalDurationNs = new AtomicLong();

    private final AtomicInteger[] histogram;

    private final long[] latencySamples = new long[LATENCY_BUFFER_SIZE];

    private final AtomicInteger sampleIndex = new AtomicInteger();

    private final AtomicLong invocationsStarted = new AtomicLong();

    private final AtomicLong invocationsCompleted = new AtomicLong();

    public MethodMetrics(MethodName methodName) {
        this.methodName = methodName;
        this.histogram = IntStream.range(0, HISTOGRAM_SIZE).mapToObj(_ -> new AtomicInteger())
                                        .toArray(AtomicInteger[]::new);
    }

    public Result<Unit> record(long durationNs, boolean success) {
        count.incrementAndGet();
        recordOutcome(success);
        totalDurationNs.addAndGet(durationNs);
        histogram[bucketFor(durationNs)].incrementAndGet();
        int idx = sampleIndex.getAndIncrement() & (LATENCY_BUFFER_SIZE - 1);
        latencySamples[idx] = durationNs;
        return unitResult();
    }

    public Snapshot snapshotAndReset() {
        var snapshotCount = count.getAndSet(0);
        var snapshotSuccess = successCount.getAndSet(0);
        var snapshotFailure = failureCount.getAndSet(0);
        var snapshotDuration = totalDurationNs.getAndSet(0);
        var snapshotHistogram = resetHistogram();
        var snapshotSamples = captureAndResetSamples(snapshotCount);
        return Snapshot.snapshot(methodName,
                                 snapshotCount,
                                 snapshotSuccess,
                                 snapshotFailure,
                                 snapshotDuration,
                                 snapshotHistogram,
                                 snapshotSamples);
    }

    public Snapshot snapshot() {
        var currentCount = count.get();
        var snapshotHistogram = captureHistogram();
        var snapshotSamples = captureSamples(currentCount);
        return Snapshot.snapshot(methodName,
                                 currentCount,
                                 successCount.get(),
                                 failureCount.get(),
                                 totalDurationNs.get(),
                                 snapshotHistogram,
                                 snapshotSamples);
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

    public Result<Unit> recordStart() {
        invocationsStarted.incrementAndGet();
        return unitResult();
    }

    public Result<Unit> recordComplete() {
        invocationsCompleted.incrementAndGet();
        return unitResult();
    }

    public long activeInvocations() {
        return Math.max(0,
                        invocationsStarted.get() - invocationsCompleted.get());
    }

    private void recordOutcome(boolean success) {
        if (success) {successCount.incrementAndGet();} else {failureCount.incrementAndGet();}
    }

    private int[] captureHistogram() {
        var snapshotHistogram = new int[HISTOGRAM_SIZE];
        IntStream.range(0, HISTOGRAM_SIZE).forEach(i -> snapshotHistogram[i] = histogram[i].get());
        return snapshotHistogram;
    }

    private int[] resetHistogram() {
        var snapshotHistogram = new int[HISTOGRAM_SIZE];
        IntStream.range(0, HISTOGRAM_SIZE).forEach(i -> snapshotHistogram[i] = histogram[i].getAndSet(0));
        return snapshotHistogram;
    }

    private long[] captureSamples(long totalCount) {
        int sampleCount = (int) Math.min(totalCount, LATENCY_BUFFER_SIZE);
        var samples = new long[sampleCount];
        System.arraycopy(latencySamples, 0, samples, 0, sampleCount);
        return samples;
    }

    private long[] captureAndResetSamples(long totalCount) {
        var samples = captureSamples(totalCount);
        sampleIndex.set(0);
        Arrays.fill(latencySamples, 0L);
        return samples;
    }

    private static int bucketFor(long durationNs) {
        if (durationNs <BUCKET_1MS) return 0;
        if (durationNs <BUCKET_10MS) return 1;
        if (durationNs <BUCKET_100MS) return 2;
        if (durationNs <BUCKET_1S) return 3;
        return 4;
    }

    public record Snapshot(MethodName methodName,
                           long count,
                           long successCount,
                           long failureCount,
                           long totalDurationNs,
                           int[] histogram,
                           long[] latencySamples) {
        public Snapshot {
            histogram = histogram == null
                       ? new int[HISTOGRAM_SIZE]
                       : histogram.clone();
            latencySamples = latencySamples == null
                            ? new long[0]
                            : latencySamples.clone();
        }

        public static Snapshot snapshot(MethodName methodName,
                                        long count,
                                        long successCount,
                                        long failureCount,
                                        long totalDurationNs,
                                        int[] histogram,
                                        long[] latencySamples) {
            return new Snapshot(methodName,
                                count,
                                successCount,
                                failureCount,
                                totalDurationNs,
                                histogram,
                                latencySamples);
        }

        public long averageLatencyNs() {
            return count > 0
                  ? totalDurationNs / count
                  : 0;
        }

        public double successRate() {
            return count > 0
                  ? (double) successCount / count
                  : 1.0;
        }

        public long percentile(double p) {
            if (latencySamples.length == 0) {return 0;}
            var sorted = latencySamples.clone();
            Arrays.sort(sorted);
            int index = Math.min((int)(p * sorted.length), sorted.length - 1);
            return sorted[index];
        }

        public long p50() {
            return percentile(0.50);
        }

        public long p95() {
            return percentile(0.95);
        }

        public long p99() {
            return percentile(0.99);
        }

        public long estimatePercentileNs(int percentile) {
            if (count == 0) return 0;
            long target = (count * percentile) / 100;
            long cumulative = 0;
            for (int i = 0;i <histogram.length;i++) {
                cumulative += histogram[i];
                if (cumulative >= target) {return bucketUpperBound(i);}
            }
            return BUCKET_1S;
        }

        private static long bucketUpperBound(int bucket) {
            return switch (bucket){
                case 0 -> BUCKET_1MS;
                case 1 -> BUCKET_10MS;
                case 2 -> BUCKET_100MS;
                case 3 -> BUCKET_1S;
                default -> BUCKET_1S * 10;
            };
        }
    }
}
