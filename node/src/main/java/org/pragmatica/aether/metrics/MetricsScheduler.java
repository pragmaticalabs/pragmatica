package org.pragmatica.aether.metrics;

import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.cluster.metrics.MetricsMessage.MetricsPing;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeAdded;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.messaging.MessageReceiver;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduler for metrics collection that runs on the leader node.
 *
 * <p>When this node is the leader, periodically sends MetricsPing to all nodes.
 * Each node responds with MetricsPong containing their metrics.
 */
public interface MetricsScheduler {
    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);

    @MessageReceiver
    void onTopologyChange(TopologyChangeNotification topologyChange);

    /**
     * Stop the scheduler (for graceful shutdown).
     */
    void stop();

    /**
     * Create a new MetricsScheduler.
     *
     * @param self             This node's ID
     * @param network          Network for sending messages
     * @param metricsCollector Collector for local metrics
     * @param intervalMs       Ping interval in milliseconds
     */
    static MetricsScheduler metricsScheduler(NodeId self,
                                             ClusterNetwork network,
                                             MetricsCollector metricsCollector,
                                             long intervalMs) {
        return new MetricsSchedulerImpl(self, network, metricsCollector, intervalMs);
    }

    /**
     * Create with default 1-second interval.
     */
    static MetricsScheduler metricsScheduler(NodeId self,
                                             ClusterNetwork network,
                                             MetricsCollector metricsCollector) {
        return metricsScheduler(self, network, metricsCollector, 1000);
    }
}

class MetricsSchedulerImpl implements MetricsScheduler {
    private static final Logger log = LoggerFactory.getLogger(MetricsSchedulerImpl.class);

    private final NodeId self;
    private final ClusterNetwork network;
    private final MetricsCollector metricsCollector;
    private final long intervalMs;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                                                                                                      var thread = new Thread(r,
                                                                                                                              "metrics-scheduler");
                                                                                                      thread.setDaemon(true);
                                                                                                      return thread;
                                                                                                  });

    private final AtomicReference<ScheduledFuture< ? >> pingTask = new AtomicReference<>();
    private final AtomicReference<List<NodeId>> topology = new AtomicReference<>(List.of());

    MetricsSchedulerImpl(NodeId self,
                         ClusterNetwork network,
                         MetricsCollector metricsCollector,
                         long intervalMs) {
        this.self = self;
        this.network = network;
        this.metricsCollector = metricsCollector;
        this.intervalMs = intervalMs;
    }

    @Override
    public void onLeaderChange(LeaderChange leaderChange) {
        if (leaderChange.localNodeIsLeader()) {
            log.info("Node {} became leader, starting metrics scheduler", self);
            startPinging();
        }else {
            log.info("Node {} is no longer leader, stopping metrics scheduler", self);
            stopPinging();
        }
    }

    @Override
    public void onTopologyChange(TopologyChangeNotification topologyChange) {
        switch (topologyChange) {
            case NodeAdded(_, List<NodeId> newTopology) -> topology.set(newTopology);
            case NodeRemoved(_, List<NodeId> newTopology) -> topology.set(newTopology);
            default -> {}
        }
    }

    @Override
    public void stop() {
        stopPinging();
        scheduler.shutdown();
        try{
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread()
                  .interrupt();
        }
    }

    private void startPinging() {
        stopPinging();
        // Cancel any existing task
        var task = scheduler.scheduleAtFixedRate(
        this::sendPingsToAllNodes, 0, intervalMs, TimeUnit.MILLISECONDS);
        pingTask.set(task);
    }

    private void stopPinging() {
        var existing = pingTask.getAndSet(null);
        if (existing != null) {
            existing.cancel(false);
        }
    }

    private void sendPingsToAllNodes() {
        try{
            var currentTopology = topology.get();
            if (currentTopology.isEmpty()) {
                return;
            }
            var localMetrics = metricsCollector.collectLocal();
            var ping = new MetricsPing(self, localMetrics);
            for (var nodeId : currentTopology) {
                if (!nodeId.equals(self)) {
                    network.send(nodeId, ping);
                }
            }
            log.trace("Sent MetricsPing to {} nodes", currentTopology.size() - 1);
        } catch (Exception e) {
            log.warn("Failed to send metrics ping: {}", e.getMessage());
        }
    }
}
