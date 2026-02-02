package org.pragmatica.aether.metrics;

import org.pragmatica.aether.metrics.consensus.RabiaMetrics;
import org.pragmatica.aether.metrics.eventloop.EventLoopMetrics;
import org.pragmatica.aether.metrics.gc.GCMetrics;
import org.pragmatica.aether.metrics.network.NetworkMetrics;

import java.util.Map;

/**
 * Comprehensive metrics snapshot aggregating all subsystems.
 * <p>
 * This is the unified view for TTM/LLM analysis, combining:
 * <ul>
 *   <li>JVM metrics (CPU, heap)</li>
 *   <li>GC metrics (pause times, allocation rate)</li>
 *   <li>Event loop health (lag, pending tasks)</li>
 *   <li>Network I/O (bytes, messages, backpressure)</li>
 *   <li>Consensus state (role, decisions, latency)</li>
 *   <li>Invocation metrics (calls, latency)</li>
 *   <li>Custom metrics from slices</li>
 * </ul>
 *
 * @param timestamp            Snapshot timestamp in milliseconds
 * @param cpuUsage             CPU usage ratio (0.0-1.0)
 * @param heapUsed             Heap memory used in bytes
 * @param heapMax              Maximum heap size in bytes
 * @param gc                   GC metrics snapshot
 * @param eventLoop            Event loop metrics snapshot
 * @param network              Network I/O metrics snapshot
 * @param consensus            Rabia consensus metrics snapshot
 * @param totalInvocations     Total method invocations
 * @param successfulInvocations Successful method invocations
 * @param failedInvocations    Failed method invocations
 * @param avgLatencyMs         Average invocation latency in milliseconds
 * @param custom               Custom metrics from slices
 */
public record ComprehensiveSnapshot(long timestamp,
                                    // JVM
double cpuUsage,
                                    long heapUsed,
                                    long heapMax,
                                    // GC
GCMetrics gc,
                                    // Event Loop
EventLoopMetrics eventLoop,
                                    // Network
NetworkMetrics network,
                                    // Consensus
RabiaMetrics consensus,
                                    // Invocation summary
long totalInvocations,
                                    long successfulInvocations,
                                    long failedInvocations,
                                    double avgLatencyMs,
                                    // Custom
Map<String, Double> custom) {
    public static final ComprehensiveSnapshot EMPTY = new ComprehensiveSnapshot(0,
                                                                                0.0,
                                                                                0,
                                                                                0,
                                                                                GCMetrics.EMPTY,
                                                                                EventLoopMetrics.EMPTY,
                                                                                NetworkMetrics.EMPTY,
                                                                                RabiaMetrics.EMPTY,
                                                                                0,
                                                                                0,
                                                                                0,
                                                                                0.0,
                                                                                Map.of());

    /**
     * Heap usage as ratio (0.0-1.0).
     */
    public double heapUsage() {
        if (heapMax <= 0) {
            return 0.0;
        }
        return (double) heapUsed / heapMax;
    }

    /**
     * Success rate as ratio (0.0-1.0).
     */
    public double successRate() {
        if (totalInvocations <= 0) {
            return 1.0;
        }
        return (double) successfulInvocations / totalInvocations;
    }

    /**
     * Error rate as ratio (0.0-1.0).
     */
    public double errorRate() {
        if (totalInvocations <= 0) {
            return 0.0;
        }
        return (double) failedInvocations / totalInvocations;
    }

    /**
     * Event loop health indicator.
     */
    public boolean eventLoopHealthy() {
        return eventLoop.healthy();
    }

    /**
     * Consensus health indicator (has leader and reasonable latency).
     */
    public boolean consensusHealthy() {
        return consensus.hasLeader() && consensus.avgDecisionLatencyMs() < 100.0;
    }

    /**
     * Overall node health indicator.
     */
    public boolean healthy() {
        return eventLoopHealthy() && consensusHealthy() && heapUsage() < 0.9 && errorRate() < 0.1;
    }
}
