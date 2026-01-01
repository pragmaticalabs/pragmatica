package org.pragmatica.aether.metrics.deployment;

import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsPing;
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
 * Scheduler for deployment metrics broadcast that runs on the leader node.
 *
 * <p>When this node is the leader, periodically sends DeploymentMetricsPing to all nodes.
 * Each node responds with DeploymentMetricsPong containing their deployment metrics.
 */
public interface DeploymentMetricsScheduler {
    /**
     * Default broadcast interval: 5 seconds.
     */
    long DEFAULT_INTERVAL_MS = 5000;

    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);

    @MessageReceiver
    void onTopologyChange(TopologyChangeNotification topologyChange);

    /**
     * Stop the scheduler (for graceful shutdown).
     */
    void stop();

    /**
     * Create a new DeploymentMetricsScheduler with default 5-second interval.
     */
    static DeploymentMetricsScheduler deploymentMetricsScheduler(NodeId self,
                                                                 ClusterNetwork network,
                                                                 DeploymentMetricsCollector collector) {
        return deploymentMetricsScheduler(self, network, collector, DEFAULT_INTERVAL_MS);
    }

    /**
     * Create a new DeploymentMetricsScheduler with custom interval.
     */
    static DeploymentMetricsScheduler deploymentMetricsScheduler(NodeId self,
                                                                 ClusterNetwork network,
                                                                 DeploymentMetricsCollector collector,
                                                                 long intervalMs) {
        return new DeploymentMetricsSchedulerImpl(self, network, collector, intervalMs);
    }
}

class DeploymentMetricsSchedulerImpl implements DeploymentMetricsScheduler {
    private static final Logger log = LoggerFactory.getLogger(DeploymentMetricsSchedulerImpl.class);

    private final NodeId self;
    private final ClusterNetwork network;
    private final DeploymentMetricsCollector collector;
    private final long intervalMs;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                                                                                                      var thread = new Thread(r,
                                                                                                                              "deployment-metrics-scheduler");
                                                                                                      thread.setDaemon(true);
                                                                                                      return thread;
                                                                                                  });

    private final AtomicReference<ScheduledFuture< ? >> pingTask = new AtomicReference<>();
    private final AtomicReference<List<NodeId>> topology = new AtomicReference<>(List.of());

    DeploymentMetricsSchedulerImpl(NodeId self,
                                   ClusterNetwork network,
                                   DeploymentMetricsCollector collector,
                                   long intervalMs) {
        this.self = self;
        this.network = network;
        this.collector = collector;
        this.intervalMs = intervalMs;
    }

    @Override
    public void onLeaderChange(LeaderChange leaderChange) {
        if (leaderChange.localNodeIsLeader()) {
            log.info("Node {} became leader, starting deployment metrics scheduler", self);
            startPinging();
        }else {
            log.info("Node {} is no longer leader, stopping deployment metrics scheduler", self);
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
            var localMetrics = collector.collectLocalEntries();
            var ping = new DeploymentMetricsPing(self, localMetrics);
            for (var nodeId : currentTopology) {
                if (!nodeId.equals(self)) {
                    network.send(nodeId, ping);
                }
            }
            log.trace("Sent DeploymentMetricsPing to {} nodes", currentTopology.size() - 1);
        } catch (Exception e) {
            log.warn("Failed to send deployment metrics ping: {}", e.getMessage());
        }
    }
}
