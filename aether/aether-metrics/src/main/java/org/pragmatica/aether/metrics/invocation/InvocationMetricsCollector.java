// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.invocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.worker.metrics.PerMethodMetrics;
import org.pragmatica.aether.worker.metrics.PerSliceMetrics;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.unitResult;


public final class InvocationMetricsCollector {
    public static final int MAX_SLOW_INVOCATIONS_PER_METHOD = 10;

    private final ThresholdStrategy thresholdStrategy;

    private final Map<Artifact, Map<MethodName, MethodMetricsWithSlowCalls>> metricsMap = new ConcurrentHashMap<>();

    /// #1573: cumulative per-(artifact, method) execution outcomes, counted at the slice bridge for every
    /// execution on this node (local and remote callers alike). Shipped on the cluster-sync pong so the
    /// leader's all-instances-failed detector can take per-window deltas. Never reset: a restart starts a
    /// new producer incarnation, which the detector treats as a fresh baseline.
    private final Map<Artifact, Map<String, ExecutionCounters>> executions = new ConcurrentHashMap<>();
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

    /// #1573: one slice execution on this node finished with `outcome`. Failures the slice method
    /// returned itself and execution timeouts are not recorded at all — neither counts either way.
    public Result<Unit> recordExecution(Artifact artifact, String method, ExecutionOutcome outcome) {
        executions.computeIfAbsent(artifact,
                                   _ -> new ConcurrentHashMap<>())
                  .computeIfAbsent(method,
                                   _ -> new ExecutionCounters())
                  .record(outcome);

        return unitResult();
    }

    /// #1573: cumulative execution outcomes per (artifact, method) since this collector was created.
    public List<ExecutionCounts> executionCounts() {
        return executions.entrySet()
                         .stream()
                         .flatMap(artifactEntry -> artifactEntry.getValue()
                                                                .entrySet()
                                                                .stream()
                                                                .map(methodEntry -> methodEntry.getValue()
                                                                                               .counts(artifactEntry.getKey(),
                                                                                                       methodEntry.getKey())))
                         .toList();
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
        return metricsMap.values()
                         .stream()
                         .flatMap(methods -> methods.values()
                                                    .stream())
                         .mapToLong(m -> m.metrics.activeInvocations())
                         .sum();
    }

    /// Per-slice scaling feed (#423). Groups the live per-(artifact, method) gauges into one
    /// [PerSliceMetrics] per artifact carrying the artifact-level aggregate (summed calls and
    /// active invocations, worst-method p95 and error rate) plus the per-method breakdown the
    /// decision path uses to let the worst-sustained method drive the artifact.
    public List<PerSliceMetrics> collectPerSliceMetrics() {
        return metricsMap.entrySet()
                         .stream()
                         .map(InvocationMetricsCollector::buildPerSliceMetrics)
                         .toList();
    }

    private static PerSliceMetrics buildPerSliceMetrics(Map.Entry<Artifact, Map<MethodName, MethodMetricsWithSlowCalls>> entry) {
        var methods = entry.getValue()
                           .values()
                           .stream()
                           .map(InvocationMetricsCollector::buildPerMethodMetrics)
                           .toList();
        var activeInvocations = methods.stream().mapToLong(PerMethodMetrics::activeInvocations).sum();
        var totalCalls = methods.stream().mapToLong(PerMethodMetrics::totalCalls).sum();
        var worstP95 = methods.stream().mapToDouble(PerMethodMetrics::p95LatencyMs).max().orElse(0.0);
        var worstErrorRate = methods.stream().mapToDouble(PerMethodMetrics::errorRate).max().orElse(0.0);

        return PerSliceMetrics.perSliceMetrics(entry.getKey(),
                                               activeInvocations,
                                               worstP95,
                                               worstErrorRate,
                                               totalCalls,
                                               methods);
    }

    private static PerMethodMetrics buildPerMethodMetrics(MethodMetricsWithSlowCalls collector) {
        var snapshot = collector.metrics.snapshot();
        var p95Ms = snapshot.estimatePercentileNs(95) / 1_000_000.0;
        var errorRate = 1.0 - snapshot.successRate();

        return PerMethodMetrics.perMethodMetrics(collector.metrics.methodName().name(),
                                                 collector.metrics.activeInvocations(),
                                                 p95Ms,
                                                 errorRate,
                                                 snapshot.count());
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
        if (!thresholdStrategy.isSlow(method, durationNs)) {
            return;
        }

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

        @Contract
        void addSlowInvocation(SlowInvocation slow) {
            lock.lock();
            try {
                slowBuffer[writeIndex % MAX_SLOW_INVOCATIONS_PER_METHOD] = slow;
                writeIndex++;
                if (count < MAX_SLOW_INVOCATIONS_PER_METHOD) {
                    count++;
                }
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
                for (int i = 0; i < MAX_SLOW_INVOCATIONS_PER_METHOD; i++) {
                    slowBuffer[i] = null;
                }

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

            for (int i = 0; i < currentCount; i++) {
                var idx = (startIdx + i) % MAX_SLOW_INVOCATIONS_PER_METHOD;
                var slow = slowBuffer[idx];

                if (slow != null) {
                    result.add(slow);
                }
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

    /// #1573: how one slice execution ended, as far as the all-instances-failed detector is concerned.
    /// One classification for every ingress; the bridge (`AdmittedSliceBridge`: inter-slice, topic,
    /// scheduled) and the HTTP route recorder (`HttpRoutePublisher.RouteOutcomeRecorder`) both apply it.
    ///
    /// | Execution ends with | Counted as |
    /// |---|---|
    /// | a value (HTTP: any value but a returned `Result.Failure`) | [#SUCCESS] |
    /// | the method threw on the calling thread (`SliceDefect.MethodThrew`) | [#DEFECT] |
    /// | bridge only: request decode / response encode failed (`SliceDefect.CodecFailed`) | [#DEFECT] |
    /// | bridge only: the method does not exist in this build (`SliceDefect.MethodNotFound`) | [#DEFECT] |
    /// | a failure the method RETURNED, whatever status it maps to (4xx or 5xx) | not counted |
    /// | HTTP: a request the router rejects before the slice (bad path/query/body → 4xx, no route → 404) | not counted |
    /// | DRAINING refusal, reply timeout, execution timeout | not counted |
    ///
    /// A returned failure is not a defect because a downstream outage surfaces exactly that way, and
    /// counting it would roll a healthy version back during someone else's incident. An HTTP request body
    /// that fails to decode is the client's input, unlike a bridge request, which another build produced.
    public enum ExecutionOutcome {
        SUCCESS,
        DEFECT
    }

    /// #1573: cumulative execution outcomes for one (artifact, method) on this node.
    public record ExecutionCounts(Artifact artifact, String method, long successes, long defects) {}

    private static final class ExecutionCounters {
        private final AtomicLong successes = new AtomicLong();
        private final AtomicLong defects = new AtomicLong();

        @Contract
        void record(ExecutionOutcome outcome) {
            switch (outcome) {
                case SUCCESS -> successes.incrementAndGet();
                case DEFECT -> defects.incrementAndGet();
            }
        }

        ExecutionCounts counts(Artifact artifact, String method) {
            return new ExecutionCounts(artifact, method, successes.get(), defects.get());
        }
    }
}
