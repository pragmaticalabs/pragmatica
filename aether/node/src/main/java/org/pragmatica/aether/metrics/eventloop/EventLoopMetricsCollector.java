package org.pragmatica.aether.metrics.eventloop;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.concurrent.EventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects event loop metrics by injecting probe tasks.
 * <p>
 * Measures event loop lag by scheduling a task and measuring the time
 * from scheduling to execution. This captures how backlogged the event loop is.
 * <p>
 * Thread-safe: uses atomic operations for all metrics.
 */
public final class EventLoopMetricsCollector {
    private static final Logger log = LoggerFactory.getLogger(EventLoopMetricsCollector.class);

    private static final long PROBE_INTERVAL_MS = 100;

    // Probe every 100ms
    private static final long HEALTH_THRESHOLD_NS = EventLoopMetrics.DEFAULT_HEALTH_THRESHOLD_NS;

    private final List<EventLoopGroup> eventLoopGroups = new CopyOnWriteArrayList<>();
    private final AtomicLong maxLagNanos = new AtomicLong(0);
    private final AtomicInteger totalPendingTasks = new AtomicInteger(0);
    private final AtomicInteger totalActiveChannels = new AtomicInteger(0);

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> probeFuture;
    private volatile boolean started = false;

    private EventLoopMetricsCollector() {}

    public static EventLoopMetricsCollector eventLoopMetricsCollector() {
        return new EventLoopMetricsCollector();
    }

    /**
     * Register an EventLoopGroup to monitor.
     */
    public void register(EventLoopGroup group) {
        if (group != null && !eventLoopGroups.contains(group)) {
            eventLoopGroups.add(group);
            log.debug("Registered EventLoopGroup for monitoring: {}",
                      group.getClass()
                           .getSimpleName());
        }
    }

    /**
     * Start collecting metrics with external scheduler.
     */
    public void start(ScheduledExecutorService scheduler) {
        if (started) {
            return;
        }
        this.scheduler = scheduler;
        started = true;
        probeFuture = scheduler.scheduleAtFixedRate(this::probe,
                                                    PROBE_INTERVAL_MS,
                                                    PROBE_INTERVAL_MS,
                                                    TimeUnit.MILLISECONDS);
        log.info("Event loop metrics collection started");
    }

    /**
     * Stop collecting metrics.
     */
    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        if (probeFuture != null) {
            probeFuture.cancel(false);
            probeFuture = null;
        }
        log.info("Event loop metrics collection stopped");
    }

    private void probe() {
        if (eventLoopGroups.isEmpty()) {
            return;
        }
        long worstLag = 0;
        int totalPending = 0;
        int totalChannels = 0;
        for (EventLoopGroup group : eventLoopGroups) {
            for (EventExecutor executor : group) {
                if (executor instanceof EventLoop eventLoop) {
                    // Measure lag by scheduling a probe task
                    long submitTime = System.nanoTime();
                    try{
                        eventLoop.execute(() -> {
                            long lag = System.nanoTime() - submitTime;
                            updateMaxLag(lag);
                        });
                    } catch (Exception e) {
                        log.trace("Failed to probe event loop: {}", e.getMessage());
                    }
                    // Count pending tasks if available
                    if (eventLoop instanceof SingleThreadEventLoop stEventLoop) {
                        totalPending += stEventLoop.pendingTasks();
                    }
                }
            }
        }
        // Update counters (lag is updated asynchronously by probe tasks)
        totalPendingTasks.set(totalPending);
        totalActiveChannels.set(totalChannels);
    }

    private void updateMaxLag(long lag) {
        long current;
        do{
            current = maxLagNanos.get();
            if (lag <= current) {
                return;
            }
        } while (!maxLagNanos.compareAndSet(current, lag));
    }

    /**
     * Take a snapshot of current metrics.
     */
    public EventLoopMetrics snapshot() {
        long lag = maxLagNanos.get();
        return new EventLoopMetrics(lag, totalPendingTasks.get(), totalActiveChannels.get(), lag < HEALTH_THRESHOLD_NS);
    }

    /**
     * Take a snapshot and reset max lag (for per-interval reporting).
     */
    public EventLoopMetrics snapshotAndReset() {
        long lag = maxLagNanos.getAndSet(0);
        return new EventLoopMetrics(lag, totalPendingTasks.get(), totalActiveChannels.get(), lag < HEALTH_THRESHOLD_NS);
    }
}
