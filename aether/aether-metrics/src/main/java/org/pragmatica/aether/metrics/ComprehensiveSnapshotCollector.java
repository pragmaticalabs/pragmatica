package org.pragmatica.aether.metrics;

import org.pragmatica.aether.metrics.consensus.RabiaMetrics;
import org.pragmatica.aether.metrics.consensus.RabiaMetricsCollector;
import org.pragmatica.aether.metrics.eventloop.EventLoopMetrics;
import org.pragmatica.aether.metrics.eventloop.EventLoopMetricsCollector;
import org.pragmatica.aether.metrics.gc.GCMetrics;
import org.pragmatica.aether.metrics.gc.GCMetricsCollector;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.metrics.network.NetworkMetrics;
import org.pragmatica.aether.metrics.network.NetworkMetricsHandler;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects comprehensive metrics from all subsystems and feeds MinuteAggregator.
 * <p>
 * This is the missing link that connects all subsystem collectors to the TTM pipeline:
 * <pre>
 * GCMetricsCollector ─────────────┐
 * EventLoopMetricsCollector ──────┤
 * NetworkMetricsHandler ──────────┼──► ComprehensiveSnapshotCollector ──► MinuteAggregator ──► TTMManager
 * RabiaMetricsCollector ──────────┤
 * InvocationMetricsCollector ─────┘
 * </pre>
 * <p>
 * Runs on a 1-second interval to collect snapshots.
 */
public final class ComprehensiveSnapshotCollector {
    private static final Logger log = LoggerFactory.getLogger(ComprehensiveSnapshotCollector.class);
    private static final long COLLECTION_INTERVAL_MS = 1000;

    private final GCMetricsCollector gcCollector;
    private final EventLoopMetricsCollector eventLoopCollector;
    private final NetworkMetricsHandler networkHandler;
    private final RabiaMetricsCollector rabiaCollector;
    private final InvocationMetricsCollector invocationCollector;
    private final MinuteAggregator minuteAggregator;
    private final DerivedMetricsCalculator derivedCalculator;

    private final OperatingSystemMXBean osMxBean;
    private final MemoryMXBean memoryMxBean;

    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> collectionTask;
    private volatile boolean started = false;

    private ComprehensiveSnapshotCollector(GCMetricsCollector gcCollector,
                                           EventLoopMetricsCollector eventLoopCollector,
                                           NetworkMetricsHandler networkHandler,
                                           RabiaMetricsCollector rabiaCollector,
                                           InvocationMetricsCollector invocationCollector,
                                           MinuteAggregator minuteAggregator,
                                           DerivedMetricsCalculator derivedCalculator) {
        this.gcCollector = gcCollector;
        this.eventLoopCollector = eventLoopCollector;
        this.networkHandler = networkHandler;
        this.rabiaCollector = rabiaCollector;
        this.invocationCollector = invocationCollector;
        this.minuteAggregator = minuteAggregator;
        this.derivedCalculator = derivedCalculator;
        this.osMxBean = ManagementFactory.getOperatingSystemMXBean();
        this.memoryMxBean = ManagementFactory.getMemoryMXBean();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                                                                        var thread = new Thread(r,
                                                                                                "comprehensive-snapshot-collector");
                                                                        thread.setDaemon(true);
                                                                        return thread;
                                                                    });
    }

    /**
     * Factory method following JBCT naming convention.
     */
    public static ComprehensiveSnapshotCollector comprehensiveSnapshotCollector(GCMetricsCollector gcCollector,
                                                                                EventLoopMetricsCollector eventLoopCollector,
                                                                                NetworkMetricsHandler networkHandler,
                                                                                RabiaMetricsCollector rabiaCollector,
                                                                                InvocationMetricsCollector invocationCollector,
                                                                                MinuteAggregator minuteAggregator,
                                                                                DerivedMetricsCalculator derivedCalculator) {
        return new ComprehensiveSnapshotCollector(gcCollector,
                                                  eventLoopCollector,
                                                  networkHandler,
                                                  rabiaCollector,
                                                  invocationCollector,
                                                  minuteAggregator,
                                                  derivedCalculator);
    }

    /**
     * Factory method with defaults for derived metrics calculator.
     */
    public static ComprehensiveSnapshotCollector comprehensiveSnapshotCollector(GCMetricsCollector gcCollector,
                                                                                EventLoopMetricsCollector eventLoopCollector,
                                                                                NetworkMetricsHandler networkHandler,
                                                                                RabiaMetricsCollector rabiaCollector,
                                                                                InvocationMetricsCollector invocationCollector,
                                                                                MinuteAggregator minuteAggregator) {
        return new ComprehensiveSnapshotCollector(gcCollector,
                                                  eventLoopCollector,
                                                  networkHandler,
                                                  rabiaCollector,
                                                  invocationCollector,
                                                  minuteAggregator,
                                                  DerivedMetricsCalculator.derivedMetricsCalculator());
    }

    /**
     * Start collecting snapshots on 1-second interval.
     */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        // Start GC collector
        gcCollector.start();
        // Start event loop collector with shared scheduler
        // EventLoopGroups are registered by AetherNode after cluster starts via Server.bossGroup()/workerGroup()
        eventLoopCollector.start(scheduler);
        // Schedule snapshot collection
        collectionTask = scheduler.scheduleAtFixedRate(this::collectSnapshot,
                                                       COLLECTION_INTERVAL_MS,
                                                       COLLECTION_INTERVAL_MS,
                                                       TimeUnit.MILLISECONDS);
        log.info("Comprehensive snapshot collection started (interval: {}ms)", COLLECTION_INTERVAL_MS);
    }

    /**
     * Stop collecting snapshots.
     */
    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        if (collectionTask != null) {
            collectionTask.cancel(false);
            collectionTask = null;
        }
        gcCollector.stop();
        eventLoopCollector.stop();
        scheduler.shutdown();
        try{
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread()
                  .interrupt();
        }
        log.info("Comprehensive snapshot collection stopped");
    }

    /**
     * Get the MinuteAggregator (for TTM access).
     */
    public MinuteAggregator minuteAggregator() {
        return minuteAggregator;
    }

    /**
     * Get current derived metrics.
     */
    public DerivedMetrics derivedMetrics() {
        return derivedCalculator.current();
    }

    /**
     * Collect a single comprehensive snapshot from all subsystems.
     */
    private void collectSnapshot() {
        try{
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
        // JVM metrics
        double cpuUsage = collectCpuUsage();
        var heapUsage = memoryMxBean.getHeapMemoryUsage();
        // Subsystem metrics
        GCMetrics gc = gcCollector.snapshot();
        EventLoopMetrics eventLoop = eventLoopCollector.snapshot();
        NetworkMetrics network = networkHandler.snapshot();
        RabiaMetrics consensus = rabiaCollector.snapshot();
        // Invocation metrics aggregation
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
                                         network,
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
