package org.pragmatica.aether.forge;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Result.unitResult;


/// Aggregates metrics for the Forge dashboard.
/// Thread-safe and designed for high-frequency updates.
public final class ForgeMetrics {
    private final LongAdder successCount = new LongAdder();

    private final LongAdder failureCount = new LongAdder();

    private final LongAdder totalLatencyNanos = new LongAdder();

    private final LongAdder requestCount = new LongAdder();

    private final AtomicLong totalSuccess = new AtomicLong(0);

    private final AtomicLong totalFailures = new AtomicLong(0);

    private volatile long lastSuccessSnapshot = 0;

    private volatile long lastFailureSnapshot = 0;

    private volatile long lastSnapshotTime = System.currentTimeMillis();

    private static final double EMA_ALPHA = 0.1;

    private volatile double requestsPerSecond = 0;

    private volatile double successRate = 1.0;

    private volatile double avgLatencyMs = 0;

    private ForgeMetrics() {}

    public static ForgeMetrics forgeMetrics() {
        return new ForgeMetrics();
    }

    public Result<Unit> recordSuccess(long latencyNanos) {
        successCount.increment();
        totalSuccess.incrementAndGet();
        totalLatencyNanos.add(latencyNanos);
        requestCount.increment();
        return unitResult();
    }

    public Result<Unit> recordFailure(long latencyNanos) {
        failureCount.increment();
        totalFailures.incrementAndGet();
        totalLatencyNanos.add(latencyNanos);
        requestCount.increment();
        return unitResult();
    }

    public synchronized Result<Unit> snapshot() {
        var now = System.currentTimeMillis();
        var elapsed = Math.max(now - lastSnapshotTime, 1);
        updateRates(elapsed);
        updateLatency();
        resetWindowCounters();
        lastSnapshotTime = now;
        return unitResult();
    }

    private void updateRates(long elapsed) {
        var currentSuccess = totalSuccess.get();
        var currentFailure = totalFailures.get();
        var successDelta = currentSuccess - lastSuccessSnapshot;
        var failureDelta = currentFailure - lastFailureSnapshot;
        var totalDelta = successDelta + failureDelta;
        var instantRps = (totalDelta * 1000.0) / elapsed;
        requestsPerSecond = smoothEma(requestsPerSecond, instantRps);
        if (Verify.Is.positive(totalDelta)) {
            var instantSuccessRate = (double) successDelta / totalDelta;
            successRate = EMA_ALPHA * instantSuccessRate + (1 - EMA_ALPHA) * successRate;
        }
        lastSuccessSnapshot = currentSuccess;
        lastFailureSnapshot = currentFailure;
    }

    private static double smoothEma(double current, double instant) {
        return current == 0
              ? instant
              : EMA_ALPHA * instant + (1 - EMA_ALPHA) * current;
    }

    private void updateLatency() {
        var count = requestCount.sumThenReset();
        var latency = totalLatencyNanos.sumThenReset();
        if (Verify.Is.positive(count)) {
            var instantLatencyMs = (latency / count) / 1_000_000.0;
            avgLatencyMs = smoothEma(avgLatencyMs, instantLatencyMs);
        }
    }

    private void resetWindowCounters() {
        successCount.reset();
        failureCount.reset();
    }

    public synchronized MetricsSnapshot currentMetrics() {
        return MetricsSnapshot.metricsSnapshot(requestsPerSecond,
                                               successRate,
                                               avgLatencyMs,
                                               totalSuccess.get(),
                                               totalFailures.get())
        .unwrap();
    }

    public synchronized Result<Unit> reset() {
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
        successRate = 1.0;
        avgLatencyMs = 0;
        return unitResult();
    }

    public record MetricsSnapshot(double requestsPerSecond,
                                  double successRate,
                                  double avgLatencyMs,
                                  long totalSuccess,
                                  long totalFailures) {
        public static Result<MetricsSnapshot> metricsSnapshot(double requestsPerSecond,
                                                              double successRate,
                                                              double avgLatencyMs,
                                                              long totalSuccess,
                                                              long totalFailures) {
            return success(new MetricsSnapshot(requestsPerSecond, successRate, avgLatencyMs, totalSuccess, totalFailures));
        }

        public long totalRequests() {
            return totalSuccess + totalFailures;
        }
    }
}
