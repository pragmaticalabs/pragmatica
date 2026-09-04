// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.metrics.consensus.RabiaMetrics;
import org.pragmatica.aether.metrics.consensus.RabiaMetricsCollector;
import org.pragmatica.aether.metrics.eventloop.EventLoopMetrics;
import org.pragmatica.aether.metrics.eventloop.EventLoopMetricsCollector;
import org.pragmatica.aether.metrics.gc.GCMetrics;
import org.pragmatica.aether.metrics.gc.GCMetricsCollector;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.unitResult;


public final class ComprehensiveSnapshotCollector {
    private static final Logger log = LoggerFactory.getLogger(ComprehensiveSnapshotCollector.class);
    private static final long COLLECTION_INTERVAL_MS = 1000;

    private final GCMetricsCollector gcCollector;
    private final EventLoopMetricsCollector eventLoopCollector;
    private final RabiaMetricsCollector rabiaCollector;
    private final InvocationMetricsCollector invocationCollector;
    private final MinuteAggregator minuteAggregator;
    private final DerivedMetricsCalculator derivedCalculator;
    private final OperatingSystemMXBean osMxBean;
    private final MemoryMXBean memoryMxBean;

    private final AtomicReference<Option<ScheduledFuture<?>>> collectionTask = new AtomicReference<>(Option.none());

    private volatile boolean started = false;

    private ComprehensiveSnapshotCollector(GCMetricsCollector gcCollector,
                                           EventLoopMetricsCollector eventLoopCollector,
                                           RabiaMetricsCollector rabiaCollector,
                                           InvocationMetricsCollector invocationCollector,
                                           MinuteAggregator minuteAggregator,
                                           DerivedMetricsCalculator derivedCalculator) {
        this.gcCollector = gcCollector;
        this.eventLoopCollector = eventLoopCollector;
        this.rabiaCollector = rabiaCollector;
        this.invocationCollector = invocationCollector;
        this.minuteAggregator = minuteAggregator;
        this.derivedCalculator = derivedCalculator;
        this.osMxBean = ManagementFactory.getOperatingSystemMXBean();
        this.memoryMxBean = ManagementFactory.getMemoryMXBean();
    }

    public static ComprehensiveSnapshotCollector comprehensiveSnapshotCollector(GCMetricsCollector gcCollector,
                                                                                EventLoopMetricsCollector eventLoopCollector,
                                                                                RabiaMetricsCollector rabiaCollector,
                                                                                InvocationMetricsCollector invocationCollector,
                                                                                MinuteAggregator minuteAggregator,
                                                                                DerivedMetricsCalculator derivedCalculator) {
        return new ComprehensiveSnapshotCollector(gcCollector,
                                                  eventLoopCollector,
                                                  rabiaCollector,
                                                  invocationCollector,
                                                  minuteAggregator,
                                                  derivedCalculator);
    }

    public static ComprehensiveSnapshotCollector comprehensiveSnapshotCollector(GCMetricsCollector gcCollector,
                                                                                EventLoopMetricsCollector eventLoopCollector,
                                                                                RabiaMetricsCollector rabiaCollector,
                                                                                InvocationMetricsCollector invocationCollector,
                                                                                MinuteAggregator minuteAggregator) {
        return new ComprehensiveSnapshotCollector(gcCollector,
                                                  eventLoopCollector,
                                                  rabiaCollector,
                                                  invocationCollector,
                                                  minuteAggregator,
                                                  DerivedMetricsCalculator.derivedMetricsCalculator());
    }

    public Result<Unit> start() {
        if (started) {
            return unitResult();
        }

        started = true;
        gcCollector.start();
        eventLoopCollector.start();
        collectionTask.set(Option.some(SharedScheduler.scheduleAtFixedRate(this::collectSnapshot,
                                                                           TimeSpan.timeSpan(COLLECTION_INTERVAL_MS).millis())));
        log.info("Comprehensive snapshot collection started (interval: {}ms)", COLLECTION_INTERVAL_MS);

        return unitResult();
    }

    @SuppressWarnings("JBCT-EX-01")
    public Result<Unit> stop() {
        if (!started) {
            return unitResult();
        }

        started = false;
        collectionTask.getAndSet(Option.none()).onPresent(task -> task.cancel(false));
        gcCollector.stop();
        eventLoopCollector.stop();
        log.info("Comprehensive snapshot collection stopped");

        return unitResult();
    }

    public MinuteAggregator minuteAggregator() {
        return minuteAggregator;
    }

    /// LIVE consensus counters for the wire (#674): the comprehensive HTTP response's consensus
    /// block reads this directly rather than a minute aggregate, because the counters are monotonic
    /// totals — a differencing consumer (the coordination-slope instrument's shape) needs raw
    /// totals with its own window, exactly as `/metrics/transport` serves its counter map.
    public RabiaMetrics consensusSnapshot() {
        return rabiaCollector.snapshot();
    }

    public DerivedMetrics derivedMetrics() {
        return derivedCalculator.current();
    }

    @SuppressWarnings("JBCT-EX-01")
    private void collectSnapshot() {
        try {
            var snapshot = buildSnapshot();

            minuteAggregator.addSample(snapshot);
            derivedCalculator.addSample(snapshot);
            log.trace("Collected comprehensive snapshot: cpu={}, heap={}, invocations={}",
                      snapshot.cpuUsage(),
                      snapshot.heapUsage(),
                      snapshot.totalInvocations());
        } catch (Exception e) {
            log.warn("Failed to collect comprehensive snapshot: {}", e.getMessage());
        }
    }

    private ComprehensiveSnapshot buildSnapshot() {
        double cpuUsage = collectCpuUsage();
        var heapUsage = memoryMxBean.getHeapMemoryUsage();
        GCMetrics gc = gcCollector.snapshot();
        EventLoopMetrics eventLoop = eventLoopCollector.snapshot();
        RabiaMetrics consensus = rabiaCollector.snapshot();
        var invocationSnapshots = invocationCollector.snapshot();
        long totalInvocations = 0;
        long successfulInvocations = 0;
        long failedInvocations = 0;
        double totalLatencyMs = 0;

        for (var methodSnapshot : invocationSnapshots) {
            var metrics = methodSnapshot.metrics();

            totalInvocations += metrics.count();
            successfulInvocations += metrics.successCount();
            failedInvocations += metrics.failureCount();
            totalLatencyMs += metrics.totalDurationNs() / 1_000_000.0;
        }

        double avgLatencyMs = totalInvocations > 0
                              ? totalLatencyMs / totalInvocations
                              : 0.0;

        return new ComprehensiveSnapshot(System.currentTimeMillis(),
                                         cpuUsage,
                                         heapUsage.getUsed(),
                                         heapUsage.getMax(),
                                         gc,
                                         eventLoop,
                                         consensus,
                                         totalInvocations,
                                         successfulInvocations,
                                         failedInvocations,
                                         avgLatencyMs,
                                         Map.of());
    }

    private double collectCpuUsage() {
        double systemLoad = osMxBean.getSystemLoadAverage();

        if (systemLoad >= 0) {
            int processors = osMxBean.getAvailableProcessors();

            return Math.min(1.0, systemLoad / processors);
        }

        return 0.0;
    }
}
