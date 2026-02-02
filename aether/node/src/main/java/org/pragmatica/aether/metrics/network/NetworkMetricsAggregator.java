package org.pragmatica.aether.metrics.network;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Aggregates network metrics from multiple handlers.
 * <p>
 * Use when you have multiple Netty pipelines (e.g., management server + cluster network)
 * and want unified metrics.
 */
public final class NetworkMetricsAggregator {
    private final List<NetworkMetricsHandler> handlers = new CopyOnWriteArrayList<>();

    private NetworkMetricsAggregator() {}

    public static NetworkMetricsAggregator networkMetricsAggregator() {
        return new NetworkMetricsAggregator();
    }

    /**
     * Register a handler for aggregation.
     */
    public void register(NetworkMetricsHandler handler) {
        if (handler != null && !handlers.contains(handler)) {
            handlers.add(handler);
        }
    }

    /**
     * Unregister a handler.
     */
    public void unregister(NetworkMetricsHandler handler) {
        handlers.remove(handler);
    }

    /**
     * Get aggregated snapshot from all registered handlers.
     */
    public NetworkMetrics snapshot() {
        if (handlers.isEmpty()) {
            return NetworkMetrics.EMPTY;
        }
        long bytesRead = 0;
        long bytesWritten = 0;
        long messagesRead = 0;
        long messagesWritten = 0;
        int activeConnections = 0;
        int backpressureEvents = 0;
        long lastBackpressure = 0;
        for (NetworkMetricsHandler handler : handlers) {
            var metrics = handler.snapshot();
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

    /**
     * Get aggregated snapshot and reset all handlers.
     */
    public NetworkMetrics snapshotAndReset() {
        if (handlers.isEmpty()) {
            return NetworkMetrics.EMPTY;
        }
        long bytesRead = 0;
        long bytesWritten = 0;
        long messagesRead = 0;
        long messagesWritten = 0;
        int activeConnections = 0;
        int backpressureEvents = 0;
        long lastBackpressure = 0;
        for (NetworkMetricsHandler handler : handlers) {
            var metrics = handler.snapshotAndReset();
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
