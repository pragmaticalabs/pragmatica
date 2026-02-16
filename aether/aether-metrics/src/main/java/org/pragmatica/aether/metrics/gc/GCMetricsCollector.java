package org.pragmatica.aether.metrics.gc;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import com.sun.management.GarbageCollectionNotificationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.unitResult;

/// Collects GC metrics using JMX notifications.
///
/// Thread-safe: uses atomic operations for all counters.
///
/// Usage:
/// ```{@code
/// var collector = GCMetricsCollector.gcMetricsCollector();
/// collector.start();
/// // ... later ...
/// var metrics = collector.snapshot();
/// // ... on shutdown ...
/// collector.stop();
/// }```
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

    /// Start collecting GC metrics via JMX notifications.
    @SuppressWarnings("JBCT-EX-01")
    public Result<Unit> start() {
        if (started) {
            return unitResult();
        }
        started = true;
        listener = this::handleGcNotification;
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
        return unitResult();
    }

    /// Stop collecting GC metrics.
    @SuppressWarnings("JBCT-EX-01")
    public Result<Unit> stop() {
        if (!started || listener == null) {
            return unitResult();
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
        return unitResult();
    }

    private void handleGcNotification(Notification notification, Object handback) {
        if (GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
            var gcInfo = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
            processGcEvent(gcInfo);
        }
    }

    private void processGcEvent(GarbageCollectionNotificationInfo gcInfo) {
        var gcAction = gcInfo.getGcAction();
        var gcName = gcInfo.getGcName();
        var duration = gcInfo.getGcInfo()
                             .getDuration();
        long beforeUsed = sumMemoryUsage(gcInfo.getGcInfo()
                                               .getMemoryUsageBeforeGc());
        long afterUsed = sumMemoryUsage(gcInfo.getGcInfo()
                                              .getMemoryUsageAfterGc());
        long reclaimed = Math.max(0, beforeUsed - afterUsed);
        reclaimedBytes.add(reclaimed);
        recordGcByCategory(gcName, duration);
        updateAllocationRate(afterUsed);
        log.trace("GC event: {} ({}) duration={}ms reclaimed={}bytes", gcName, gcAction, duration, reclaimed);
    }

    private long sumMemoryUsage(Map<String, MemoryUsage> usageMap) {
        return usageMap.values()
                       .stream()
                       .mapToLong(MemoryUsage::getUsed)
                       .sum();
    }

    private void recordGcByCategory(String gcName, long duration) {
        if (isYoungGc(gcName)) {
            youngGcCount.increment();
            youngGcPauseMs.add(duration);
        } else {
            oldGcCount.increment();
            oldGcPauseMs.add(duration);
            lastMajorGcTimestamp.set(System.currentTimeMillis());
        }
    }

    private boolean isYoungGc(String gcName) {
        if (Arrays.stream(YOUNG_GC_PATTERNS)
                  .anyMatch(gcName::contains)) {
            return true;
        }
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

    /// Take a snapshot of current metrics.
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

    /// Take a snapshot and reset counters (for delta-based reporting).
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
