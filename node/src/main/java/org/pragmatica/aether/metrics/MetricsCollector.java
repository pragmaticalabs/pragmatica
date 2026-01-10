package org.pragmatica.aether.metrics;

import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.cluster.metrics.MetricsMessage.MetricsPing;
import org.pragmatica.cluster.metrics.MetricsMessage.MetricsPong;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.utility.RingBuffer;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects and manages metrics for a single node.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Collect JVM metrics (CPU, heap usage)</li>
 *   <li>Track per-method call stats (count, duration)</li>
 *   <li>Store custom metrics from slices</li>
 *   <li>Store received metrics from other nodes</li>
 *   <li>Handle MetricsPing/MetricsPong messages</li>
 * </ul>
 *
 * <p>Metrics are stored in-memory with a sliding window for historical data.
 */
public interface MetricsCollector {
    // Standard metric names
    String CPU_USAGE = "cpu.usage";
    String HEAP_USED = "heap.used";
    String HEAP_MAX = "heap.max";
    String HEAP_USAGE = "heap.usage";

    /**
     * Collect current local JVM metrics.
     */
    Map<String, Double> collectLocal();

    /**
     * Record a method call with its duration.
     */
    void recordCall(MethodName method, long durationMs);

    /**
     * Record a custom metric value from a slice.
     */
    void recordCustom(String name, double value);

    /**
     * Get all known metrics (local + remote nodes).
     */
    Map<NodeId, Map<String, Double>> allMetrics();

    /**
     * Get metrics for a specific node.
     */
    Map<String, Double> metricsFor(NodeId nodeId);

    /**
     * Get historical metrics within the sliding window (2 hours).
     *
     * @return Map of NodeId to list of timestamped snapshots, oldest first
     */
    Map<NodeId, java.util.List<MetricsSnapshot>> historicalMetrics();

    /**
     * Immutable metrics snapshot with timestamp.
     */
    record MetricsSnapshot(long timestamp, Map<String, Double> metrics) {}

    @MessageReceiver
    void onMetricsPing(MetricsPing ping);

    @MessageReceiver
    void onMetricsPong(MetricsPong pong);

    /**
     * Create a new MetricsCollector instance.
     */
    static MetricsCollector metricsCollector(NodeId self, ClusterNetwork network) {
        return new MetricsCollectorImpl(self, network);
    }
}

/**
 * Implementation of MetricsCollector.
 */
class MetricsCollectorImpl implements MetricsCollector {
    // Sliding window duration: 2 hours in milliseconds
    private static final long SLIDING_WINDOW_MS = 2 * 60 * 60 * 1000L;

    // Ring buffer capacity: 2 hours at 1 sample/second
    private static final int RING_BUFFER_CAPACITY = (int)(SLIDING_WINDOW_MS / 1000);

    private final NodeId self;
    private final ClusterNetwork network;

    // JVM metrics beans
    private final OperatingSystemMXBean osMxBean;
    private final MemoryMXBean memoryMxBean;

    // Per-method call statistics
    private final ConcurrentHashMap<MethodName, CallStats> callStats = new ConcurrentHashMap<>();

    // Custom metrics from slices
    private final ConcurrentHashMap<String, Double> customMetrics = new ConcurrentHashMap<>();

    // Metrics received from other nodes
    private final ConcurrentHashMap<NodeId, Map<String, Double>> remoteMetrics = new ConcurrentHashMap<>();

    // Ring buffer for historical metrics - fixed capacity, O(1) add, oldest elements auto-evicted
    private final ConcurrentHashMap<NodeId, RingBuffer<MetricsSnapshot>> historicalMetricsMap = new ConcurrentHashMap<>();

    MetricsCollectorImpl(NodeId self, ClusterNetwork network) {
        this.self = self;
        this.network = network;
        this.osMxBean = ManagementFactory.getOperatingSystemMXBean();
        this.memoryMxBean = ManagementFactory.getMemoryMXBean();
    }

    @Override
    public Map<String, Double> collectLocal() {
        var metrics = new ConcurrentHashMap<String, Double>();
        // CPU usage (system load average normalized by processors)
        double systemLoad = osMxBean.getSystemLoadAverage();
        if (systemLoad >= 0) {
            int processors = osMxBean.getAvailableProcessors();
            metrics.put(CPU_USAGE, Math.min(1.0, systemLoad / processors));
        }
        // Heap memory
        var heapUsage = memoryMxBean.getHeapMemoryUsage();
        metrics.put(HEAP_USED, (double) heapUsage.getUsed());
        metrics.put(HEAP_MAX, (double) heapUsage.getMax());
        if (heapUsage.getMax() > 0) {
            metrics.put(HEAP_USAGE,
                        (double) heapUsage.getUsed() / heapUsage.getMax());
        }
        // Add call stats
        callStats.forEach((method, stats) -> {
                              var prefix = "method." + method.name() + ".";
                              metrics.put(prefix + "calls", (double) stats.count.sum());
                              metrics.put(prefix + "duration.total", stats.totalDuration.sum());
                              if (stats.count.sum() > 0) {
                                  metrics.put(prefix + "duration.avg",
                                              stats.totalDuration.sum() / stats.count.sum());
                              }
                          });
        // Add custom metrics
        metrics.putAll(customMetrics);
        return metrics;
    }

    @Override
    public void recordCall(MethodName method, long durationMs) {
        callStats.computeIfAbsent(method,
                                  _ -> new CallStats())
                 .record(durationMs);
    }

    @Override
    public void recordCustom(String name, double value) {
        customMetrics.put(name, value);
    }

    @Override
    public Map<NodeId, Map<String, Double>> allMetrics() {
        var result = new ConcurrentHashMap<>(remoteMetrics);
        result.put(self, collectLocal());
        return result;
    }

    @Override
    public Map<String, Double> metricsFor(NodeId nodeId) {
        if (nodeId.equals(self)) {
            return collectLocal();
        }
        return remoteMetrics.getOrDefault(nodeId, Map.of());
    }

    @Override
    public Map<NodeId, java.util.List<MetricsSnapshot>> historicalMetrics() {
        var cutoff = System.currentTimeMillis() - SLIDING_WINDOW_MS;
        var result = new ConcurrentHashMap<NodeId, java.util.List<MetricsSnapshot>>();
        historicalMetricsMap.forEach((nodeId, ringBuffer) -> {
                                         // Filter to only include snapshots within the window
        var filtered = ringBuffer.filter(s -> s.timestamp() >= cutoff);
                                         if (!filtered.isEmpty()) {
                                             result.put(nodeId, filtered);
                                         }
                                     });
        return result;
    }

    @Override
    public void onMetricsPing(MetricsPing ping) {
        // Store sender's metrics (but don't overwrite our own)
        if (!ping.sender()
                 .equals(self)) {
            remoteMetrics.put(ping.sender(), ping.metrics());
            addToHistory(ping.sender(), ping.metrics());
        }
        // Respond with our metrics
        network.send(ping.sender(), new MetricsPong(self, collectLocal()));
    }

    @Override
    public void onMetricsPong(MetricsPong pong) {
        // Store responder's metrics (but don't overwrite our own)
        if (!pong.sender()
                 .equals(self)) {
            remoteMetrics.put(pong.sender(), pong.metrics());
            addToHistory(pong.sender(), pong.metrics());
        }
    }

    /**
     * Add metrics snapshot to historical ring buffer.
     * Old entries are automatically evicted when buffer is full.
     */
    private void addToHistory(NodeId nodeId, Map<String, Double> metrics) {
        var ringBuffer = historicalMetricsMap.computeIfAbsent(nodeId, _ -> RingBuffer.ringBuffer(RING_BUFFER_CAPACITY));
        ringBuffer.add(new MetricsSnapshot(System.currentTimeMillis(), metrics));
    }

    /**
     * Mutable call statistics for a method.
     */
    private static class CallStats {
        final LongAdder count = new LongAdder();
        final DoubleAdder totalDuration = new DoubleAdder();

        void record(long durationMs) {
            count.increment();
            totalDuration.add(durationMs);
        }
    }
}
