package org.pragmatica.aether.metrics.deployment;

import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsPing;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Scheduler for deployment metrics broadcast that runs on the leader node.
///
///
/// When this node is the leader, periodically sends DeploymentMetricsPing to all nodes.
/// Each node responds with DeploymentMetricsPong containing their deployment metrics.
public interface DeploymentMetricsScheduler {
    /// Default broadcast interval: 5 seconds.
    TimeSpan DEFAULT_INTERVAL = TimeSpan.timeSpan(5)
                                       .seconds();

    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onLeaderChange(LeaderChange leaderChange);

    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onTopologyChange(TopologyChangeNotification topologyChange);

    /// Handle quorum state changes (stop pinging when quorum disappears).
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onQuorumStateChange(QuorumStateNotification notification);

    /// Stop the scheduler (for graceful shutdown).
    @SuppressWarnings("JBCT-RET-01")
    void stop();

    /// Create a new DeploymentMetricsScheduler with default 5-second interval.
    static DeploymentMetricsScheduler deploymentMetricsScheduler(NodeId self,
                                                                 ClusterNetwork network,
                                                                 DeploymentMetricsCollector collector) {
        return deploymentMetricsScheduler(self, network, collector, DEFAULT_INTERVAL);
    }

    /// Create a new DeploymentMetricsScheduler with custom interval.
    static DeploymentMetricsScheduler deploymentMetricsScheduler(NodeId self,
                                                                 ClusterNetwork network,
                                                                 DeploymentMetricsCollector collector,
                                                                 TimeSpan interval) {
        return new DeploymentMetricsSchedulerImpl(self, network, collector, interval);
    }
}

class DeploymentMetricsSchedulerImpl implements DeploymentMetricsScheduler {
    private static final Logger log = LoggerFactory.getLogger(DeploymentMetricsSchedulerImpl.class);

    private final NodeId self;
    private final ClusterNetwork network;
    private final DeploymentMetricsCollector collector;
    private final TimeSpan interval;

    private final AtomicReference<ScheduledFuture<?>> pingTask = new AtomicReference<>();
    private final AtomicReference<List<NodeId>> topology = new AtomicReference<>(List.of());
    private final AtomicLong quorumSequence = new AtomicLong();

    DeploymentMetricsSchedulerImpl(NodeId self,
                                   ClusterNetwork network,
                                   DeploymentMetricsCollector collector,
                                   TimeSpan interval) {
        this.self = self;
        this.network = network;
        this.collector = collector;
        this.interval = interval;
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onLeaderChange(LeaderChange leaderChange) {
        if (leaderChange.localNodeIsLeader()) {
            log.info("Node {} became leader, starting deployment metrics scheduler", self);
            startPinging();
        } else {
            log.info("Node {} is no longer leader, stopping deployment metrics scheduler", self);
            stopPinging();
        }
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onTopologyChange(TopologyChangeNotification topologyChange) {
        switch (topologyChange) {
            case NodeAdded(_, List<NodeId> newTopology) -> topology.set(newTopology);
            case NodeRemoved(_, List<NodeId> newTopology) -> topology.set(newTopology);
            default -> {}
        }
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onQuorumStateChange(QuorumStateNotification notification) {
        if (!notification.advanceSequence(quorumSequence)) {
            log.debug("Ignoring stale QuorumStateNotification: {}", notification);
            return;
        }
        if (notification.state() == QuorumStateNotification.State.DISAPPEARED) {
            log.info("Quorum disappeared, stopping deployment metrics scheduler");
            stopPinging();
        }
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
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

    @SuppressWarnings("JBCT-EX-01")
    private void sendPingsToAllNodes() {
        try{
            var currentTopology = topology.get();
            if (currentTopology.isEmpty()) {
                return;
            }
            var localMetrics = collector.collectLocalEntries();
            var ping = new DeploymentMetricsPing(self, localMetrics);
            currentTopology.stream()
                           .filter(nodeId -> !nodeId.equals(self))
                           .forEach(nodeId -> network.send(nodeId, ping));
            log.trace("Sent DeploymentMetricsPing to {} nodes", currentTopology.size() - 1);
        } catch (Exception e) {
            log.warn("Failed to send deployment metrics ping: {}", e.getMessage());
        }
    }
}
