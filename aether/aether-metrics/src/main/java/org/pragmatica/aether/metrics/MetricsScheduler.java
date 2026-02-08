package org.pragmatica.aether.metrics;

import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.cluster.metrics.MetricsMessage.MetricsPing;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeAdded;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.consensus.topology.QuorumStateNotification;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
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
     * Handle quorum state changes (stop pinging when quorum disappears).
     */
    @MessageReceiver
    void onQuorumStateChange(QuorumStateNotification notification);

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
     * @param interval         Ping interval
     */
    static MetricsScheduler metricsScheduler(NodeId self,
                                             ClusterNetwork network,
                                             MetricsCollector metricsCollector,
                                             TimeSpan interval) {
        return new MetricsSchedulerImpl(self, network, metricsCollector, interval);
    }

    /**
     * Create with default 1-second interval.
     */
    static MetricsScheduler metricsScheduler(NodeId self,
                                             ClusterNetwork network,
                                             MetricsCollector metricsCollector) {
        return metricsScheduler(self,
                                network,
                                metricsCollector,
                                TimeSpan.timeSpan(1)
                                        .seconds());
    }
}

class MetricsSchedulerImpl implements MetricsScheduler {
    private static final Logger log = LoggerFactory.getLogger(MetricsSchedulerImpl.class);

    private final NodeId self;
    private final ClusterNetwork network;
    private final MetricsCollector metricsCollector;
    private final TimeSpan interval;

    private final AtomicReference<ScheduledFuture<?>> pingTask = new AtomicReference<>();
    private final AtomicReference<List<NodeId>> topology = new AtomicReference<>(List.of());

    MetricsSchedulerImpl(NodeId self,
                         ClusterNetwork network,
                         MetricsCollector metricsCollector,
                         TimeSpan interval) {
        this.self = self;
        this.network = network;
        this.metricsCollector = metricsCollector;
        this.interval = interval;
    }

    @Override
    public void onLeaderChange(LeaderChange leaderChange) {
        if (leaderChange.localNodeIsLeader()) {
            log.info("Node {} became leader, starting metrics scheduler", self);
            startPinging();
        } else {
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
    public void onQuorumStateChange(QuorumStateNotification notification) {
        if (notification == QuorumStateNotification.DISAPPEARED) {
            log.info("Quorum disappeared, stopping metrics scheduler");
            stopPinging();
        }
    }

    @Override
    public void stop() {
        stopPinging();
    }

    private void startPinging() {
        stopPinging();
        var task = SharedScheduler.scheduleAtFixedRate(this::sendPingsToAllNodes, interval);
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
            var ping = new MetricsPing(self, metricsCollector.allMetrics());
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
