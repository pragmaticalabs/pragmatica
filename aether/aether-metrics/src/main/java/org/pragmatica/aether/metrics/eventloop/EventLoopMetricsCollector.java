package org.pragmatica.aether.metrics.eventloop;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.concurrent.EventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.unitResult;

/// Collects event loop metrics by injecting probe tasks.
///
/// Measures event loop lag by scheduling a task and measuring the time
/// from scheduling to execution. This captures how backlogged the event loop is.
///
/// Thread-safe: uses atomic operations for all metrics.
public final class EventLoopMetricsCollector {
    private static final Logger log = LoggerFactory.getLogger(EventLoopMetricsCollector.class);

    private static final long DEFAULT_PROBE_INTERVAL_MS = 100;
    private static final long HEALTH_THRESHOLD_NS = EventLoopMetrics.DEFAULT_HEALTH_THRESHOLD_NS;

    private final long probeIntervalMs;
    private final CopyOnWriteArrayList<EventLoopGroup> eventLoopGroups = new CopyOnWriteArrayList<>();
    private final AtomicLong maxLagNanos = new AtomicLong(0);
    private final AtomicInteger totalPendingTasks = new AtomicInteger(0);
    private final AtomicInteger totalActiveChannels = new AtomicInteger(0);

    private final AtomicReference<Option<ScheduledFuture<?>>> probeFuture = new AtomicReference<>(none());
    private volatile boolean started = false;

    private EventLoopMetricsCollector(long probeIntervalMs) {
        this.probeIntervalMs = probeIntervalMs;
    }

    public static EventLoopMetricsCollector eventLoopMetricsCollector() {
        return new EventLoopMetricsCollector(DEFAULT_PROBE_INTERVAL_MS);
    }

    public static EventLoopMetricsCollector eventLoopMetricsCollector(long probeIntervalMs) {
        return new EventLoopMetricsCollector(probeIntervalMs);
    }

    /// Register an EventLoopGroup to monitor.
    ///
    /// @param group the event loop group, must not be null
    public Result<Unit> register(EventLoopGroup group) {
        if ( eventLoopGroups.addIfAbsent(group)) {
        log.debug("Registered EventLoopGroup for monitoring: {}",
                  group.getClass().getSimpleName());}
        return unitResult();
    }

    /// Start collecting metrics using SharedScheduler.
    public Result<Unit> start() {
        if ( started) {
        return unitResult();}
        started = true;
        probeFuture.set(some(SharedScheduler.scheduleAtFixedRate(this::probe,
                                                                 TimeSpan.timeSpan(probeIntervalMs).millis())));
        log.info("Event loop metrics collection started");
        return unitResult();
    }

    /// Stop collecting metrics.
    public Result<Unit> stop() {
        if ( !started) {
        return unitResult();}
        started = false;
        probeFuture.getAndSet(none()).onPresent(future -> future.cancel(false));
        log.info("Event loop metrics collection stopped");
        return unitResult();
    }

    @SuppressWarnings("JBCT-EX-01")
    private void probe() {
        if ( eventLoopGroups.isEmpty()) {
        return;}
        int totalPending = 0;
        int totalChannels = 0;
        for ( EventLoopGroup group : eventLoopGroups) {
        for ( EventExecutor executor : group) {
        if ( executor instanceof EventLoop eventLoop) {
            submitLagProbe(eventLoop);
            if ( eventLoop instanceof SingleThreadEventLoop stEventLoop) {
                totalPending += stEventLoop.pendingTasks();
                totalChannels += stEventLoop.registeredChannels();
            }
        }}}
        totalPendingTasks.set(totalPending);
        totalActiveChannels.set(totalChannels);
    }

    private void submitLagProbe(EventLoop eventLoop) {
        long submitTime = System.nanoTime();
        try {
            eventLoop.execute(() -> updateMaxLag(System.nanoTime() - submitTime));
        }




        catch (Exception e) {
            log.trace("Failed to probe event loop: {}", e.getMessage());
        }
    }

    private void updateMaxLag(long lag) {
        long current;
        do {
            current = maxLagNanos.get();
            if ( lag <= current) {
            return;}
        } while ( !maxLagNanos.compareAndSet(current, lag));
    }

    /// Take a snapshot of current metrics.
    public EventLoopMetrics snapshot() {
        long lag = maxLagNanos.get();
        return new EventLoopMetrics(lag, totalPendingTasks.get(), totalActiveChannels.get(), lag < HEALTH_THRESHOLD_NS);
    }

    /// Take a snapshot and reset max lag (for per-interval reporting).
    public EventLoopMetrics snapshotAndReset() {
        long lag = maxLagNanos.getAndSet(0);
        return new EventLoopMetrics(lag, totalPendingTasks.get(), totalActiveChannels.get(), lag < HEALTH_THRESHOLD_NS);
    }
}
