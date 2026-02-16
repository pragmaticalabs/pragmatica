package org.pragmatica.aether.metrics.network;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.pragmatica.lang.Result.unitResult;

/// Aggregates network metrics from multiple handlers.
///
/// Use when you have multiple Netty pipelines (e.g., management server + cluster network)
/// and want unified metrics.
public final class NetworkMetricsAggregator {
    private final CopyOnWriteArrayList<NetworkMetricsHandler> handlers = new CopyOnWriteArrayList<>();

    private NetworkMetricsAggregator() {}

    public static NetworkMetricsAggregator networkMetricsAggregator() {
        return new NetworkMetricsAggregator();
    }

    /// Register a handler for aggregation.
    public Result<Unit> register(NetworkMetricsHandler handler) {
        handlers.addIfAbsent(handler);
        return unitResult();
    }

    /// Unregister a handler.
    public Result<Unit> unregister(NetworkMetricsHandler handler) {
        handlers.remove(handler);
        return unitResult();
    }

    /// Get aggregated snapshot from all registered handlers.
    public NetworkMetrics snapshot() {
        if (handlers.isEmpty()) {
            return NetworkMetrics.EMPTY;
        }
        return aggregateHandlers(false);
    }

    /// Get aggregated snapshot and reset all handlers.
    public NetworkMetrics snapshotAndReset() {
        if (handlers.isEmpty()) {
            return NetworkMetrics.EMPTY;
        }
        return aggregateHandlers(true);
    }

    private NetworkMetrics aggregateHandlers(boolean reset) {
        long bytesRead = 0;
        long bytesWritten = 0;
        long messagesRead = 0;
        long messagesWritten = 0;
        int activeConnections = 0;
        int backpressureEvents = 0;
        long lastBackpressure = 0;
        for (NetworkMetricsHandler handler : handlers) {
            var metrics = reset
                          ? handler.snapshotAndReset()
                          : handler.snapshot();
            bytesRead += metrics.bytesRead();
            bytesWritten += metrics.bytesWritten();
            messagesRead += metrics.messagesRead();
            messagesWritten += metrics.messagesWritten();
            activeConnections += metrics.activeConnections();
            backpressureEvents += metrics.backpressureEvents();
            lastBackpressure = Math.max(lastBackpressure, metrics.lastBackpressureTimestamp());
        }
        return new NetworkMetrics(bytesRead,
                                  bytesWritten,
                                  messagesRead,
                                  messagesWritten,
                                  activeConnections,
                                  backpressureEvents,
                                  lastBackpressure);
    }
}
