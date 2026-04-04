package org.pragmatica.aether.metrics.invocation;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.unitResult;


/// Collects per-method invocation metrics with threshold-based slow call capture.
///
/// This collector implements the dual-track metrics approach:
///
///   - Tier 1: Aggregated metrics (count, sum, histogram) for all invocations
///   - Tier 2: Detailed capture of slow invocations in a ring buffer
///
///
/// Thread-safe and designed for high-throughput concurrent access.
///
/// <h3>Usage:</h3>
/// ```{@code
/// var collector = InvocationMetricsCollector.invocationMetricsCollector(ThresholdStrategy.adaptive(10, 1000));
///
/// // Record invocation
/// collector.record(artifact, method, durationNs, success, requestBytes, responseBytes, errorType);
///
/// // Get snapshot
/// var snapshots = collector.snapshotAndReset();
/// }```
public final class InvocationMetricsCollector {
    public static final int MAX_SLOW_INVOCATIONS_PER_METHOD = 10;

    private final ThresholdStrategy thresholdStrategy;

    private final Map<Artifact, Map<MethodName, MethodMetricsWithSlowCalls>> metricsMap = new ConcurrentHashMap<>();

    private final AtomicLong totalSerializationNs = new AtomicLong();

    private final AtomicLong serializationCount = new AtomicLong();

    private InvocationMetricsCollector(ThresholdStrategy thresholdStrategy) {
        this.thresholdStrategy = thresholdStrategy;
    }

    public static InvocationMetricsCollector invocationMetricsCollector(ThresholdStrategy thresholdStrategy) {
        return new InvocationMetricsCollector(thresholdStrategy);
    }

    public static InvocationMetricsCollector invocationMetricsCollector() {
        return new InvocationMetricsCollector(ThresholdStrategy.adaptive(10, 1000));
    }

    public Result<Unit> record(Artifact artifact,
                               MethodName method,
                               long durationNs,
                               boolean success,
                               int requestBytes,
                               int responseBytes,
                               Option<String> errorType) {
        var methodMetrics = getOrCreateMetrics(artifact, method);
        methodMetrics.metrics.record(durationNs, success);
        thresholdStrategy.observe(method, durationNs);
        captureIfSlow(methodMetrics, method, durationNs, success, requestBytes, responseBytes, errorType);
        return unitResult();
    }

    public Result<Unit> recordSuccess(Artifact artifact,
                                      MethodName method,
                                      long durationNs,
                                      int requestBytes,
                                      int responseBytes) {
        return record(artifact, method, durationNs, true, requestBytes, responseBytes, Option.empty());
    }

    public Result<Unit> recordStart(Artifact artifact, MethodName method) {
        getOrCreateMetrics(artifact, method).metrics.recordStart();
        return unitResult();
    }

    public Result<Unit> recordComplete(Artifact artifact, MethodName method) {
        getOrCreateMetrics(artifact, method).metrics.recordComplete();
        return unitResult();
    }

    public Result<Unit> recordSerialization(long durationNs) {
        totalSerializationNs.addAndGet(durationNs);
        serializationCount.incrementAndGet();
        return unitResult();
    }

    public long averageSerializationNs() {
        long count = serializationCount.get();
        return count > 0
              ? totalSerializationNs.get() / count
              : 0;
    }

    public long totalActiveInvocations() {
        return metricsMap.values().stream()
                                .flatMap(methods -> methods.values().stream())
                                .mapToLong(m -> m.metrics.activeInvocations())
                                .sum();
    }

    public Result<Unit> recordFailure(Artifact artifact,
                                      MethodName method,
                                      long durationNs,
                                      int requestBytes,
                                      String errorType) {
        return record(artifact, method, durationNs, false, requestBytes, 0, option(errorType));
    }

    public List<MethodSnapshot> snapshotAndReset() {
        var result = new ArrayList<MethodSnapshot>();
        metricsMap.forEach((artifact, methods) -> methods.forEach((method, collector) -> result.add(buildSnapshot(artifact,
                                                                                                                  collector,
                                                                                                                  true))));
        return result;
    }

    public List<MethodSnapshot> snapshot() {
        var result = new ArrayList<MethodSnapshot>();
        metricsMap.forEach((artifact, methods) -> methods.forEach((method, collector) -> result.add(buildSnapshot(artifact,
                                                                                                                  collector,
                                                                                                                  false))));
        return result;
    }

    public long thresholdNsFor(MethodName method) {
        return thresholdStrategy.thresholdNs(method);
    }

    public ThresholdStrategy thresholdStrategy() {
        return thresholdStrategy;
    }

    public Result<Unit> setThresholdStrategy(ThresholdStrategy strategy) {
        return MetricsError.StrategyChangeNotSupported.INSTANCE.result();
    }

    private void captureIfSlow(MethodMetricsWithSlowCalls methodMetrics,
                               MethodName method,
                               long durationNs,
                               boolean success,
                               int requestBytes,
                               int responseBytes,
                               Option<String> errorType) {
        if (!thresholdStrategy.isSlow(method, durationNs)) {return;}
        var slow = success
                  ? SlowInvocation.slowInvocation(method, System.nanoTime(), durationNs, requestBytes, responseBytes)
                  : SlowInvocation.slowInvocation(method,
                                                  System.nanoTime(),
                                                  durationNs,
                                                  requestBytes,
                                                  errorType.or("Unknown"));
        methodMetrics.addSlowInvocation(slow);
    }

    private MethodSnapshot buildSnapshot(Artifact artifact, MethodMetricsWithSlowCalls collector, boolean reset) {
        var metricsSnapshot = reset
                             ? collector.metrics.snapshotAndReset()
                             : collector.metrics.snapshot();
        var slowCalls = reset
                       ? collector.drainSlowInvocations()
                       : collector.copySlowInvocations();
        var threshold = thresholdStrategy.thresholdNs(collector.metrics.methodName());
        return new MethodSnapshot(artifact, metricsSnapshot, slowCalls, threshold);
    }

    private MethodMetricsWithSlowCalls getOrCreateMetrics(Artifact artifact, MethodName method) {
        return metricsMap.computeIfAbsent(artifact,
                                          _ -> new ConcurrentHashMap<>())
        .computeIfAbsent(method, MethodMetricsWithSlowCalls::new);
    }

    private static final class MethodMetricsWithSlowCalls {
        final MethodMetrics metrics;

        final SlowInvocation[] slowBuffer = new SlowInvocation[MAX_SLOW_INVOCATIONS_PER_METHOD];

        final ReentrantLock lock = new ReentrantLock();

        int writeIndex = 0;

        int count = 0;

        MethodMetricsWithSlowCalls(MethodName methodName) {
            this.metrics = new MethodMetrics(methodName);
        }

        void addSlowInvocation(SlowInvocation slow) {
            lock.lock();
            try {
                slowBuffer[writeIndex % MAX_SLOW_INVOCATIONS_PER_METHOD] = slow;
                writeIndex++;
                if (count <MAX_SLOW_INVOCATIONS_PER_METHOD) {count++;}
            } finally {
                lock.unlock();
            }
        }

        List<SlowInvocation> drainSlowInvocations() {
            lock.lock();
            try {
                var result = copySlowInvocationsUnlocked();
                writeIndex = 0;
                count = 0;
                for (int i = 0;i <MAX_SLOW_INVOCATIONS_PER_METHOD;i++) {slowBuffer[i] = null;}
                return result;
            } finally {
                lock.unlock();
            }
        }

        List<SlowInvocation> copySlowInvocations() {
            lock.lock();
            try {
                return copySlowInvocationsUnlocked();
            } finally {
                lock.unlock();
            }
        }

        private List<SlowInvocation> copySlowInvocationsUnlocked() {
            var result = new ArrayList<SlowInvocation>(count);
            var currentCount = Math.min(count, MAX_SLOW_INVOCATIONS_PER_METHOD);
            var startIdx = writeIndex >= MAX_SLOW_INVOCATIONS_PER_METHOD
                          ? writeIndex % MAX_SLOW_INVOCATIONS_PER_METHOD
                          : 0;
            for (int i = 0;i <currentCount;i++) {
                var idx = (startIdx + i) % MAX_SLOW_INVOCATIONS_PER_METHOD;
                var slow = slowBuffer[idx];
                if (slow != null) {result.add(slow);}
            }
            return result;
        }
    }

    public record MethodSnapshot(Artifact artifact,
                                 MethodMetrics.Snapshot metrics,
                                 List<SlowInvocation> slowInvocations,
                                 long currentThresholdNs) {
        public MethodName methodName() {
            return metrics.methodName();
        }

        public double currentThresholdMs() {
            return currentThresholdNs / 1_000_000.0;
        }
    }
}
