// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.cluster.metrics.CommunityReport;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.metrics.PeerObservationBuffer;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.lang.Contract;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.utility.RingBuffer;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface ClusterSyncCollector {
    String CPU_USAGE = "cpu.usage";

    String HEAP_USED = "heap.used";

    String HEAP_MAX = "heap.max";

    String HEAP_USAGE = "heap.usage";

    Map<String, Double> collectLocal();
    @Contract void recordCall(MethodName method, long durationMs);
    @Contract void recordCustom(String name, double value);
    @Contract void setInvocationMetricsProvider(InvocationMetricsCollector provider);
    Map<NodeId, Map<String, Double>> allMetrics();
    Map<String, Double> metricsFor(NodeId nodeId);
    Map<NodeId, List<MetricsSnapshot>> historicalMetrics();

    record MetricsSnapshot(long timestamp, Map<String, Double> metrics){}

    @Contract void removeNode(NodeId nodeId);
    @MessageReceiver@Contract void onMembershipDecision(MembershipDecision decision);
    @MessageReceiver@Contract void onClusterSyncPing(ClusterSyncPing ping);
    @MessageReceiver@Contract void onClusterSyncPong(ClusterSyncPong pong);
    long observedRabiaTerm();
    Epoch observedEpoch();
    String currentLifecycleState();
    List<CommunityReport> collectCommunityReports();
    @Contract void setLifecycleStateSupplier(Supplier<String> supplier);
    @Contract void setCommunityReportSupplier(Supplier<List<CommunityReport>> supplier);
    @Contract void addPongListener(Consumer<ClusterSyncPong> listener);

    long DEFAULT_slidingWindowMs = 2 * 60 * 60 * 1000L;

    @Contract void setPongSignalFan(ClusterSyncPongSignalFan fan);
    @Contract void setPeerObservationBuffer(PeerObservationBuffer buffer);

    static ClusterSyncCollector clusterSyncCollector(NodeId self, ClusterNetwork network) {
        return new ClusterSyncCollectorImpl(self, network, DEFAULT_slidingWindowMs);
    }

    static ClusterSyncCollector clusterSyncCollector(NodeId self, ClusterNetwork network, long slidingWindowMs) {
        return new ClusterSyncCollectorImpl(self, network, slidingWindowMs);
    }
}

class ClusterSyncCollectorImpl implements ClusterSyncCollector {
    private static final Logger log = LoggerFactory.getLogger(ClusterSyncCollectorImpl.class);

    private final long slidingWindowMs;
    private final int ringBufferCapacity;
    private final NodeId self;
    private final ClusterNetwork network;
    private final OperatingSystemMXBean osMxBean;
    private final MemoryMXBean memoryMxBean;

    private final ConcurrentHashMap<MethodName, CallStats> callStats = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Double> customMetrics = new ConcurrentHashMap<>();

    private volatile InvocationMetricsCollector invocationMetricsProvider;

    private final ConcurrentHashMap<NodeId, Map<String, Double>> remoteMetrics = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<NodeId, RingBuffer<MetricsSnapshot>> historicalMetricsMap = new ConcurrentHashMap<>();

    private final AtomicLong observedRabiaTerm = new AtomicLong();

    private final AtomicReference<Epoch> observedEpoch = new AtomicReference<>(Epoch.ZERO);

    private final AtomicReference<Supplier<String>> lifecycleStateSupplier = new AtomicReference<>(() -> "ON_DUTY");

    private final AtomicReference<Supplier<List<CommunityReport>>> communityReportSupplier = new AtomicReference<>(List::of);

    private final CopyOnWriteArrayList<Consumer<ClusterSyncPong>> pongListeners = new CopyOnWriteArrayList<>();

    private final AtomicReference<ClusterSyncPongSignalFan> pongSignalFan = new AtomicReference<>(_ -> {});

    private final AtomicReference<PeerObservationBuffer> peerObservationBuffer = new AtomicReference<>(PeerObservationBuffer.NOOP);

    ClusterSyncCollectorImpl(NodeId self, ClusterNetwork network, long slidingWindowMs) {
        this.self = self;
        this.network = network;
        this.slidingWindowMs = slidingWindowMs;
        this.ringBufferCapacity = (int)(slidingWindowMs / 1000);
        this.osMxBean = ManagementFactory.getOperatingSystemMXBean();
        this.memoryMxBean = ManagementFactory.getMemoryMXBean();
    }

    @Override public Map<String, Double> collectLocal() {
        var metrics = new HashMap<String, Double>();
        collectCpuMetrics(metrics);
        collectHeapMetrics(metrics);
        collectCallStatsMetrics(metrics);
        metrics.putAll(customMetrics);
        collectInvocationMetrics(metrics);
        return metrics;
    }

    @Override@Contract public void recordCall(MethodName method, long durationMs) {
        callStats.computeIfAbsent(method, _ -> CallStats.callStats()).record(durationMs);
    }

    @Override@Contract public void recordCustom(String name, double value) {
        customMetrics.put(name, value);
    }

    @Override@Contract public void setInvocationMetricsProvider(InvocationMetricsCollector provider) {
        this.invocationMetricsProvider = provider;
    }

    @Override public Map<NodeId, Map<String, Double>> allMetrics() {
        var local = collectLocal();
        addToHistory(self, local);
        var result = new ConcurrentHashMap<>(remoteMetrics);
        result.put(self, local);
        return result;
    }

    @Override public Map<String, Double> metricsFor(NodeId nodeId) {
        if (nodeId.equals(self)) {return collectLocal();}
        return remoteMetrics.getOrDefault(nodeId, Map.of());
    }

    @Override public Map<NodeId, List<MetricsSnapshot>> historicalMetrics() {
        var cutoff = System.currentTimeMillis() - slidingWindowMs;
        var result = new ConcurrentHashMap<NodeId, List<MetricsSnapshot>>();
        historicalMetricsMap.forEach((nodeId, ringBuffer) -> addFilteredHistory(result, nodeId, ringBuffer, cutoff));
        return result;
    }

    @Override@Contract public void removeNode(NodeId nodeId) {
        remoteMetrics.remove(nodeId);
        historicalMetricsMap.remove(nodeId);
    }

    @Override@Contract public void onMembershipDecision(MembershipDecision decision) {
        switch (decision){
            case MembershipDecision.NodeRemoved(var removedNode, _) -> removeNode(removedNode);
            case MembershipDecision.NodeDecommissioned(var decommissioned, _) -> removeNode(decommissioned);
            default -> {}
        }
    }

    @Override@Contract public void onClusterSyncPing(ClusterSyncPing ping) {
        log.debug("ClusterSync: received PING from {} (rabiaTerm={}, epoch={}:{})",
                  ping.sender(),
                  ping.rabiaTerm(),
                  ping.epochTerm(),
                  ping.epochCounter());
        if (!acceptPingFencing(ping)) {
            log.warn("ClusterSync: PING from {} rejected by fencing (rabiaTerm={} < observed={})",
                     ping.sender(),
                     ping.rabiaTerm(),
                     observedRabiaTerm.get());
            return;
        }
        ping.allMetrics().forEach(this::storeRemoteMetrics);
        var incomingEpoch = Epoch.epoch(ping.epochTerm(), ping.epochCounter());
        advanceObservedEpoch(incomingEpoch);
        var pong = buildPong();
        log.debug("ClusterSync: sending PONG to {} (epoch={}:{})",
                  ping.sender(),
                  pong.observedEpochTerm(),
                  pong.observedEpochCounter());
        network.send(ping.sender(), pong);
    }

    @Override@Contract public void onClusterSyncPong(ClusterSyncPong pong) {
        log.debug("ClusterSync: received PONG from {} (epoch={}:{})",
                  pong.sender(),
                  pong.observedEpochTerm(),
                  pong.observedEpochCounter());
        if (!pong.sender().equals(self)) {
            remoteMetrics.put(pong.sender(), pong.metrics());
            addToHistory(pong.sender(), pong.metrics());
        }
        pongSignalFan.get().fan(pong);
        pongListeners.forEach(listener -> listener.accept(pong));
    }

    @Override@Contract public void setPongSignalFan(ClusterSyncPongSignalFan fan) {
        pongSignalFan.set(fan);
    }

    @Override@Contract public void setPeerObservationBuffer(PeerObservationBuffer buffer) {
        peerObservationBuffer.set(buffer == null
                                  ? PeerObservationBuffer.NOOP
                                  : buffer);
    }

    @Override public long observedRabiaTerm() {
        return observedRabiaTerm.get();
    }

    @Override public Epoch observedEpoch() {
        return observedEpoch.get();
    }

    @Override public String currentLifecycleState() {
        return lifecycleStateSupplier.get().get();
    }

    @Override public List<CommunityReport> collectCommunityReports() {
        return communityReportSupplier.get().get();
    }

    @Override@Contract public void setLifecycleStateSupplier(Supplier<String> supplier) {
        lifecycleStateSupplier.set(supplier);
    }

    @Override@Contract public void setCommunityReportSupplier(Supplier<List<CommunityReport>> supplier) {
        communityReportSupplier.set(supplier);
    }

    @Override@Contract public void addPongListener(Consumer<ClusterSyncPong> listener) {
        pongListeners.add(listener);
    }

    private boolean acceptPingFencing(ClusterSyncPing ping) {
        var currentTerm = observedRabiaTerm.get();
        if (ping.rabiaTerm() <currentTerm) {return false;}
        if (ping.rabiaTerm() > currentTerm) {observedRabiaTerm.set(ping.rabiaTerm());}
        return true;
    }

    private void advanceObservedEpoch(Epoch incomingEpoch) {
        observedEpoch.updateAndGet(prev -> incomingEpoch.isStrictlyAfter(prev)
                                          ? incomingEpoch
                                          : prev);
    }

    private ClusterSyncPong buildPong() {
        var epoch = observedEpoch.get();
        var buffer = peerObservationBuffer.get();
        return new ClusterSyncPong(self,
                                   collectLocal(),
                                   observedRabiaTerm.get(),
                                   epoch.rabiaTerm(),
                                   epoch.localCounter(),
                                   currentLifecycleState(),
                                   collectCommunityReports(),
                                   buffer.drainHealth(),
                                   buffer.drainConnectivity());
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
        if (heapUsage.getMax() > 0) {metrics.put(HEAP_USAGE,
                                                 (double) heapUsage.getUsed() / heapUsage.getMax());}
    }

    private void collectCallStatsMetrics(Map<String, Double> metrics) {
        callStats.forEach((method, stats) -> addMethodStats(metrics, method, stats));
    }

    private void addMethodStats(Map<String, Double> metrics, MethodName method, CallStats stats) {
        var prefix = "method." + method.name() + ".";
        metrics.put(prefix + "calls", (double) stats.count.sum());
        metrics.put(prefix + "duration.total", stats.totalDuration.sum());
        if (stats.count.sum() > 0) {metrics.put(prefix + "duration.avg",
                                                stats.totalDuration.sum() / stats.count.sum());}
    }

    private void collectInvocationMetrics(Map<String, Double> metrics) {
        var invMetrics = invocationMetricsProvider;
        if (invMetrics == null) {return;}
        invMetrics.snapshot().forEach(snapshot -> addInvocationSnapshot(metrics, snapshot));
    }

    private void addInvocationSnapshot(Map<String, Double> metrics,
                                       InvocationMetricsCollector.MethodSnapshot snapshot) {
        var prefix = "inv|" + snapshot.artifact().asString() + "|" + snapshot.methodName().name() + "|";
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
        if (!filtered.isEmpty()) {result.put(nodeId, filtered);}
    }

    private void addToHistory(NodeId nodeId, Map<String, Double> metrics) {
        var ringBuffer = historicalMetricsMap.computeIfAbsent(nodeId, _ -> RingBuffer.ringBuffer(ringBufferCapacity));
        ringBuffer.add(new MetricsSnapshot(System.currentTimeMillis(), metrics));
    }

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
