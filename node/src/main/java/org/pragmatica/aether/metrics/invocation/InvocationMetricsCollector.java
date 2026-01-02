package org.pragmatica.aether.metrics.invocation;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collects per-method invocation metrics with threshold-based slow call capture.
 * <p>
 * This collector implements the dual-track metrics approach:
 * <ul>
 *   <li>Tier 1: Aggregated metrics (count, sum, histogram) for all invocations</li>
 *   <li>Tier 2: Detailed capture of slow invocations in a ring buffer</li>
 * </ul>
 * <p>
 * Thread-safe and designed for high-throughput concurrent access.
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * var collector = InvocationMetricsCollector.invocationMetricsCollector(ThresholdStrategy.adaptive(10, 1000));
 *
 * // Record invocation
 * collector.record(artifact, method, durationNs, success, requestBytes, responseBytes, errorType);
 *
 * // Get snapshot
 * var snapshots = collector.snapshotAndReset();
 * }</pre>
 */
public final class InvocationMetricsCollector {
    /**
     * Maximum slow invocations to retain per method per snapshot interval.
     */
    public static final int MAX_SLOW_INVOCATIONS_PER_METHOD = 10;

    private final ThresholdStrategy thresholdStrategy;

    // Per-method metrics: Map<sliceArtifact, Map<methodName, metrics>>
    private final Map<Artifact, Map<MethodName, MethodMetricsWithSlowCalls>> metricsMap = new ConcurrentHashMap<>();

    private InvocationMetricsCollector(ThresholdStrategy thresholdStrategy) {
        this.thresholdStrategy = thresholdStrategy;
    }

    /**
     * Create a new metrics collector with the given threshold strategy.
     */
    public static InvocationMetricsCollector invocationMetricsCollector(ThresholdStrategy thresholdStrategy) {
        return new InvocationMetricsCollector(thresholdStrategy);
    }

    /**
     * Create with default adaptive threshold (10ms - 1000ms, 3x multiplier).
     */
    public static InvocationMetricsCollector invocationMetricsCollector() {
        return new InvocationMetricsCollector(ThresholdStrategy.adaptive(10, 1000));
    }

    /**
     * Record an invocation.
     *
     * @param artifact      Slice artifact
     * @param method        Method name
     * @param durationNs    Duration in nanoseconds
     * @param success       Whether the invocation succeeded
     * @param requestBytes  Size of serialized request
     * @param responseBytes Size of serialized response (0 for fire-and-forget or failures)
     * @param errorType     Error type class name if failed
     */
    public void record(Artifact artifact,
                       MethodName method,
                       long durationNs,
                       boolean success,
                       int requestBytes,
                       int responseBytes,
                       Option<String> errorType) {
        var methodMetrics = getOrCreateMetrics(artifact, method);
        // Tier 1: Always record to aggregated metrics
        methodMetrics.metrics.record(durationNs, success);
        // Update adaptive threshold
        thresholdStrategy.observe(method, durationNs);
        // Tier 2: Capture if slow
        if (thresholdStrategy.isSlow(method, durationNs)) {
            var slow = success
                       ? SlowInvocation.success(method, System.nanoTime(), durationNs, requestBytes, responseBytes)
                       : SlowInvocation.failure(method,
                                                System.nanoTime(),
                                                durationNs,
                                                requestBytes,
                                                errorType.fold(() -> "Unknown", e -> e));
            methodMetrics.addSlowInvocation(slow);
        }
    }

    /**
     * Convenience method for recording success.
     */
    public void recordSuccess(Artifact artifact,
                              MethodName method,
                              long durationNs,
                              int requestBytes,
                              int responseBytes) {
        record(artifact, method, durationNs, true, requestBytes, responseBytes, Option.empty());
    }

    /**
     * Convenience method for recording failure.
     */
    public void recordFailure(Artifact artifact,
                              MethodName method,
                              long durationNs,
                              int requestBytes,
                              String errorType) {
        record(artifact, method, durationNs, false, requestBytes, 0, Option.option(errorType));
    }

    /**
     * Take snapshot of all metrics and reset counters.
     *
     * @return List of method snapshots with slow invocations
     */
    public List<MethodSnapshot> snapshotAndReset() {
        var result = new ArrayList<MethodSnapshot>();
        metricsMap.forEach((artifact, methods) -> {
                               methods.forEach((method, collector) -> {
                                                   var metricsSnapshot = collector.metrics.snapshotAndReset();
                                                   var slowCalls = collector.drainSlowInvocations();
                                                   var threshold = thresholdStrategy.thresholdNs(method);
                                                   result.add(new MethodSnapshot(artifact,
                                                                                 metricsSnapshot,
                                                                                 slowCalls,
                                                                                 threshold));
                                               });
                           });
        return result;
    }

    /**
     * Take snapshot without resetting (for monitoring).
     */
    public List<MethodSnapshot> snapshot() {
        var result = new ArrayList<MethodSnapshot>();
        metricsMap.forEach((artifact, methods) -> {
                               methods.forEach((method, collector) -> {
                                                   var metricsSnapshot = collector.metrics.snapshot();
                                                   var slowCalls = collector.copySlowInvocations();
                                                   var threshold = thresholdStrategy.thresholdNs(method);
                                                   result.add(new MethodSnapshot(artifact,
                                                                                 metricsSnapshot,
                                                                                 slowCalls,
                                                                                 threshold));
                                               });
                           });
        return result;
    }

    /**
     * Get current threshold for a method.
     */
    public long thresholdNsFor(MethodName method) {
        return thresholdStrategy.thresholdNs(method);
    }

    /**
     * Get the threshold strategy (for reconfiguration).
     */
    public ThresholdStrategy thresholdStrategy() {
        return thresholdStrategy;
    }

    /**
     * Set a new threshold strategy at runtime.
     */
    public void setThresholdStrategy(ThresholdStrategy strategy) {
        // Note: This class uses final field, so we need a different approach
        // For now, this is a no-op placeholder - actual implementation would need
        // to use AtomicReference or similar. The current strategy is effectively immutable.
        throw new UnsupportedOperationException("Strategy change at runtime requires collector recreation");
    }

    private MethodMetricsWithSlowCalls getOrCreateMetrics(Artifact artifact, MethodName method) {
        return metricsMap.computeIfAbsent(artifact,
                                          _ -> new ConcurrentHashMap<>())
                         .computeIfAbsent(method, MethodMetricsWithSlowCalls::new);
    }

    /**
     * Combines MethodMetrics with a ring buffer for slow invocations.
     */
    private static final class MethodMetricsWithSlowCalls {
        final MethodMetrics metrics;
        final SlowInvocation[] slowBuffer = new SlowInvocation[MAX_SLOW_INVOCATIONS_PER_METHOD];
        final AtomicInteger writeIndex = new AtomicInteger(0);
        volatile int count = 0;

        MethodMetricsWithSlowCalls(MethodName methodName) {
            this.metrics = new MethodMetrics(methodName);
        }

        void addSlowInvocation(SlowInvocation slow) {
            var idx = writeIndex.getAndIncrement() % MAX_SLOW_INVOCATIONS_PER_METHOD;
            slowBuffer[idx] = slow;
            if (count < MAX_SLOW_INVOCATIONS_PER_METHOD) {
                count++ ;
            }
        }

        List<SlowInvocation> drainSlowInvocations() {
            var result = copySlowInvocations();
            // Reset
            writeIndex.set(0);
            count = 0;
            for (int i = 0; i < MAX_SLOW_INVOCATIONS_PER_METHOD; i++ ) {
                slowBuffer[i] = null;
            }
            return result;
        }

        List<SlowInvocation> copySlowInvocations() {
            var result = new ArrayList<SlowInvocation>(count);
            var currentCount = Math.min(count, MAX_SLOW_INVOCATIONS_PER_METHOD);
            var startIdx = writeIndex.get() >= MAX_SLOW_INVOCATIONS_PER_METHOD
                           ? writeIndex.get() % MAX_SLOW_INVOCATIONS_PER_METHOD
                           : 0;
            for (int i = 0; i < currentCount; i++ ) {
                var idx = (startIdx + i) % MAX_SLOW_INVOCATIONS_PER_METHOD;
                var slow = slowBuffer[idx];
                if (slow != null) {
                    result.add(slow);
                }
            }
            return result;
        }
    }

    /**
     * Combined snapshot of method metrics and slow invocations.
     */
    public record MethodSnapshot(
    Artifact artifact,
    MethodMetrics.Snapshot metrics,
    List<SlowInvocation> slowInvocations,
    long currentThresholdNs) {
        /**
         * Method name from the metrics.
         */
        public MethodName methodName() {
            return metrics.methodName();
        }

        /**
         * Current threshold in milliseconds.
         */
        public double currentThresholdMs() {
            return currentThresholdNs / 1_000_000.0;
        }
    }
}
