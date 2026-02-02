package org.pragmatica.aether.metrics.gc;

import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import com.sun.management.GarbageCollectionNotificationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects GC metrics using JMX notifications.
 * <p>
 * Thread-safe: uses atomic operations for all counters.
 * <p>
 * Usage:
 * <pre>{@code
 * var collector = GCMetricsCollector.gcMetricsCollector();
 * collector.start();
 * // ... later ...
 * var metrics = collector.snapshot();
 * // ... on shutdown ...
 * collector.stop();
 * }</pre>
 */
public final class GCMetricsCollector {
    private static final Logger log = LoggerFactory.getLogger(GCMetricsCollector.class);

    // Young GC collector name patterns (G1, Parallel, Serial, ZGC, Shenandoah)
    private static final String[] YOUNG_GC_PATTERNS = {"G1 Young", "PS Scavenge", "ParNew", "Copy", "ZGC Minor", "Shenandoah Pauses"};

    private final LongAdder youngGcCount = new LongAdder();
    private final LongAdder youngGcPauseMs = new LongAdder();
    private final LongAdder oldGcCount = new LongAdder();
    private final LongAdder oldGcPauseMs = new LongAdder();
    private final LongAdder reclaimedBytes = new LongAdder();
    private final AtomicLong lastMajorGcTimestamp = new AtomicLong(0);

    // For allocation rate calculation
    private final AtomicLong lastHeapUsed = new AtomicLong(0);
    private final AtomicLong lastSampleTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong allocationRateBytesPerSec = new AtomicLong(0);
    private final AtomicLong promotionRateBytesPerSec = new AtomicLong(0);

    private NotificationListener listener;
    private volatile boolean started = false;

    private GCMetricsCollector() {}

    public static GCMetricsCollector gcMetricsCollector() {
        return new GCMetricsCollector();
    }

    /**
     * Start collecting GC metrics via JMX notifications.
     */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        listener = (notification, handback) -> {
            if (GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
                var gcInfo = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
                processGcEvent(gcInfo);
            }
        };
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gcBean instanceof NotificationEmitter emitter) {
                try{
                    emitter.addNotificationListener(listener, null, null);
                    log.debug("Registered GC listener for: {}", gcBean.getName());
                } catch (Exception e) {
                    log.warn("Failed to register GC listener for {}: {}", gcBean.getName(), e.getMessage());
                }
            }
        }
        log.info("GC metrics collection started");
    }

    /**
     * Stop collecting GC metrics.
     */
    public void stop() {
        if (!started || listener == null) {
            return;
        }
        started = false;
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gcBean instanceof NotificationEmitter emitter) {
                try{
                    emitter.removeNotificationListener(listener);
                } catch (Exception e) {
                    log.debug("Failed to remove GC listener: {}", e.getMessage());
                }
            }
        }
        log.info("GC metrics collection stopped");
    }

    private void processGcEvent(GarbageCollectionNotificationInfo gcInfo) {
        var gcAction = gcInfo.getGcAction();
        var gcName = gcInfo.getGcName();
        var duration = gcInfo.getGcInfo()
                             .getDuration();
        // Calculate reclaimed bytes
        long beforeUsed = 0;
        long afterUsed = 0;
        for (MemoryUsage usage : gcInfo.getGcInfo()
                                       .getMemoryUsageBeforeGc()
                                       .values()) {
            beforeUsed += usage.getUsed();
        }
        for (MemoryUsage usage : gcInfo.getGcInfo()
                                       .getMemoryUsageAfterGc()
                                       .values()) {
            afterUsed += usage.getUsed();
        }
        long reclaimed = Math.max(0, beforeUsed - afterUsed);
        reclaimedBytes.add(reclaimed);
        // Categorize as young or old GC
        boolean isYoungGc = isYoungGc(gcName);
        if (isYoungGc) {
            youngGcCount.increment();
            youngGcPauseMs.add(duration);
        } else {
            oldGcCount.increment();
            oldGcPauseMs.add(duration);
            lastMajorGcTimestamp.set(System.currentTimeMillis());
        }
        // Update allocation rate estimate
        updateAllocationRate(afterUsed);
        log.trace("GC event: {} ({}) duration={}ms reclaimed={}bytes", gcName, gcAction, duration, reclaimed);
    }

    private boolean isYoungGc(String gcName) {
        for (String pattern : YOUNG_GC_PATTERNS) {
            if (gcName.contains(pattern)) {
                return true;
            }
        }
        // Default: if name contains "Old", "Major", or "Full", it's not young
        return ! gcName.contains("Old") && !gcName.contains("Major") && !gcName.contains("Full");
    }

    private void updateAllocationRate(long currentHeapUsed) {
        long now = System.currentTimeMillis();
        long lastTime = lastSampleTime.getAndSet(now);
        long lastUsed = lastHeapUsed.getAndSet(currentHeapUsed);
        long elapsed = now - lastTime;
        if (elapsed > 0 && lastUsed > 0) {
            // Allocation rate = bytes allocated since last sample / time
            // This is approximate since we only sample after GC
            long allocatedEstimate = Math.max(0, currentHeapUsed - lastUsed);
            long rate = (allocatedEstimate * 1000) / elapsed;
            allocationRateBytesPerSec.set(rate);
        }
    }

    /**
     * Take a snapshot of current metrics.
     */
    public GCMetrics snapshot() {
        return new GCMetrics(youngGcCount.sum(),
                             youngGcPauseMs.sum(),
                             oldGcCount.sum(),
                             oldGcPauseMs.sum(),
                             reclaimedBytes.sum(),
                             allocationRateBytesPerSec.get(),
                             promotionRateBytesPerSec.get(),
                             lastMajorGcTimestamp.get());
    }

    /**
     * Take a snapshot and reset counters (for delta-based reporting).
     */
    public GCMetrics snapshotAndReset() {
        var snap = new GCMetrics(youngGcCount.sumThenReset(),
                                 youngGcPauseMs.sumThenReset(),
                                 oldGcCount.sumThenReset(),
                                 oldGcPauseMs.sumThenReset(),
                                 reclaimedBytes.sumThenReset(),
                                 allocationRateBytesPerSec.get(),
                                 promotionRateBytesPerSec.get(),
                                 lastMajorGcTimestamp.get());
        return snap;
    }
}
