package org.pragmatica.aether.metrics;

import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.cluster.metrics.MetricsMessage.MetricsPing;
import org.pragmatica.cluster.metrics.MetricsMessage.MetricsPong;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.utility.RingBuffer;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/// Collects and manages metrics for a single node.
///
///
/// Responsibilities:
///
///   - Collect JVM metrics (CPU, heap usage)
///   - Track per-method call stats (count, duration)
///   - Store custom metrics from slices
///   - Store received metrics from other nodes
///   - Handle MetricsPing/MetricsPong messages
///
///
///
/// Metrics are stored in-memory with a sliding window for historical data.
public interface MetricsCollector {
    // Standard metric names
    String CPU_USAGE = "cpu.usage";
    String HEAP_USED = "heap.used";
    String HEAP_MAX = "heap.max";
    String HEAP_USAGE = "heap.usage";

    /// Collect current local JVM metrics.
    Map<String, Double> collectLocal();

    /// Record a method call with its duration.
    @SuppressWarnings("JBCT-RET-01")
    void recordCall(MethodName method, long durationMs);

    /// Record a custom metric value from a slice.
    @SuppressWarnings("JBCT-RET-01")
    void recordCustom(String name, double value);

    /// Set the invocation metrics provider for cluster-wide aggregation.
    /// Invocation snapshots are encoded as flat map entries and exchanged via gossip.
    @SuppressWarnings("JBCT-RET-01")
    void setInvocationMetricsProvider(InvocationMetricsCollector provider);

    /// Get all known metrics (local + remote nodes).
    Map<NodeId, Map<String, Double>> allMetrics();

    /// Get metrics for a specific node.
    Map<String, Double> metricsFor(NodeId nodeId);

    /// Get historical metrics within the sliding window (2 hours).
    ///
    /// @return Map of NodeId to list of timestamped snapshots, oldest first
    Map<NodeId, List<MetricsSnapshot>> historicalMetrics();

    /// Immutable metrics snapshot with timestamp.
    record MetricsSnapshot(long timestamp, Map<String, Double> metrics) {}

    /// Remove a node from remote metrics and history.
    /// Called when a node leaves the cluster or is detected as down.
    @SuppressWarnings("JBCT-RET-01")
    void removeNode(NodeId nodeId);

    /// Handle topology changes to clean up metrics for departed nodes.
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onTopologyChange(TopologyChangeNotification topologyChange);

    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onMetricsPing(MetricsPing ping);

    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onMetricsPong(MetricsPong pong);

    /// Create a new MetricsCollector instance.
    static MetricsCollector metricsCollector(NodeId self, ClusterNetwork network) {
        return new MetricsCollectorImpl(self, network);
    }
}

/// Implementation of MetricsCollector.
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

    // Invocation metrics provider for cluster-wide aggregation
    private volatile InvocationMetricsCollector invocationMetricsProvider;

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
        var metrics = new HashMap<String, Double>();
        collectCpuMetrics(metrics);
        collectHeapMetrics(metrics);
        collectCallStatsMetrics(metrics);
        metrics.putAll(customMetrics);
        collectInvocationMetrics(metrics);
        return metrics;
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void recordCall(MethodName method, long durationMs) {
        callStats.computeIfAbsent(method,
                                  _ -> CallStats.callStats())
                 .record(durationMs);
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void recordCustom(String name, double value) {
        customMetrics.put(name, value);
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void setInvocationMetricsProvider(InvocationMetricsCollector provider) {
        this.invocationMetricsProvider = provider;
    }

    @Override
    public Map<NodeId, Map<String, Double>> allMetrics() {
        var local = collectLocal();
        addToHistory(self, local);
        var result = new ConcurrentHashMap<>(remoteMetrics);
        result.put(self, local);
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
    public Map<NodeId, List<MetricsSnapshot>> historicalMetrics() {
        var cutoff = System.currentTimeMillis() - SLIDING_WINDOW_MS;
        var result = new ConcurrentHashMap<NodeId, List<MetricsSnapshot>>();
        historicalMetricsMap.forEach((nodeId, ringBuffer) -> addFilteredHistory(result, nodeId, ringBuffer, cutoff));
        return result;
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void removeNode(NodeId nodeId) {
        remoteMetrics.remove(nodeId);
        historicalMetricsMap.remove(nodeId);
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onTopologyChange(TopologyChangeNotification topologyChange) {
        switch (topologyChange) {
            case TopologyChangeNotification.NodeRemoved(var removedNode, _) -> removeNode(removedNode);
            case TopologyChangeNotification.NodeDown(var downNode, _) -> removeNode(downNode);
            default -> {}
        }
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onMetricsPing(MetricsPing ping) {
        ping.allMetrics()
            .forEach(this::storeRemoteMetrics);
        network.send(ping.sender(), new MetricsPong(self, collectLocal()));
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onMetricsPong(MetricsPong pong) {
        if (!pong.sender()
                 .equals(self)) {
            remoteMetrics.put(pong.sender(), pong.metrics());
            addToHistory(pong.sender(), pong.metrics());
        }
    }

    private void collectCpuMetrics(Map<String, Double> metrics) {
        double systemLoad = osMxBean.getSystemLoadAverage();
        if (systemLoad >= 0) {
            int processors = osMxBean.getAvailableProcessors();
            metrics.put(CPU_USAGE, Math.min(1.0, systemLoad / processors));
        }
    }

    private void collectHeapMetrics(Map<String, Double> metrics) {
        var heapUsage = memoryMxBean.getHeapMemoryUsage();
        metrics.put(HEAP_USED, (double) heapUsage.getUsed());
        metrics.put(HEAP_MAX, (double) heapUsage.getMax());
        if (heapUsage.getMax() > 0) {
            metrics.put(HEAP_USAGE,
                        (double) heapUsage.getUsed() / heapUsage.getMax());
        }
    }

    private void collectCallStatsMetrics(Map<String, Double> metrics) {
        callStats.forEach((method, stats) -> addMethodStats(metrics, method, stats));
    }

    private void addMethodStats(Map<String, Double> metrics, MethodName method, CallStats stats) {
        var prefix = "method." + method.name() + ".";
        metrics.put(prefix + "calls", (double) stats.count.sum());
        metrics.put(prefix + "duration.total", stats.totalDuration.sum());
        if (stats.count.sum() > 0) {
            metrics.put(prefix + "duration.avg",
                        stats.totalDuration.sum() / stats.count.sum());
        }
    }

    private void collectInvocationMetrics(Map<String, Double> metrics) {
        var invMetrics = invocationMetricsProvider;
        if (invMetrics == null) {
            return;
        }
        invMetrics.snapshot()
                  .forEach(snapshot -> addInvocationSnapshot(metrics, snapshot));
    }

    private void addInvocationSnapshot(Map<String, Double> metrics,
                                       InvocationMetricsCollector.MethodSnapshot snapshot) {
        var prefix = "inv|" + snapshot.artifact()
                                     .asString() + "|" + snapshot.methodName()
                                                                .name() + "|";
        var m = snapshot.metrics();
        metrics.put(prefix + "count", (double) m.count());
        metrics.put(prefix + "success", (double) m.successCount());
        metrics.put(prefix + "failure", (double) m.failureCount());
        metrics.put(prefix + "totalNs", (double) m.totalDurationNs());
        metrics.put(prefix + "p50ns", (double) m.estimatePercentileNs(50));
        metrics.put(prefix + "p95ns", (double) m.estimatePercentileNs(95));
    }

    private void storeRemoteMetrics(NodeId nodeId, Map<String, Double> metrics) {
        if (!nodeId.equals(self)) {
            remoteMetrics.put(nodeId, metrics);
            addToHistory(nodeId, metrics);
        }
    }

    private void addFilteredHistory(Map<NodeId, List<MetricsSnapshot>> result,
                                    NodeId nodeId,
                                    RingBuffer<MetricsSnapshot> ringBuffer,
                                    long cutoff) {
        var filtered = ringBuffer.filter(s -> s.timestamp() >= cutoff);
        if (!filtered.isEmpty()) {
            result.put(nodeId, filtered);
        }
    }

    /// Add metrics snapshot to historical ring buffer.
    /// Old entries are automatically evicted when buffer is full.
    private void addToHistory(NodeId nodeId, Map<String, Double> metrics) {
        var ringBuffer = historicalMetricsMap.computeIfAbsent(nodeId, _ -> RingBuffer.ringBuffer(RING_BUFFER_CAPACITY));
        ringBuffer.add(new MetricsSnapshot(System.currentTimeMillis(), metrics));
    }

    /// Mutable call statistics for a method.
    private record CallStats(LongAdder count, DoubleAdder totalDuration) {
        static CallStats callStats() {
            return new CallStats(new LongAdder(), new DoubleAdder());
        }

        void record(long durationMs) {
            count.increment();
            totalDuration.add(durationMs);
        }
    }
}
