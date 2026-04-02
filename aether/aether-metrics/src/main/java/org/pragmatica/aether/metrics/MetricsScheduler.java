package org.pragmatica.aether.metrics;

import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.cluster.metrics.MetricsMessage.MetricsPing;
import org.pragmatica.lang.Contract;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeAdded;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.lang.concurrent.CancellableTask;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Scheduler for metrics collection that runs on the leader node.
///
///
/// When this node is the leader, periodically sends MetricsPing to all nodes.
/// Each node responds with MetricsPong containing their metrics.
public interface MetricsScheduler {
    @MessageReceiver
    @Contract void onLeaderChange(LeaderChange leaderChange);

    @MessageReceiver
    @Contract void onTopologyChange(TopologyChangeNotification topologyChange);

    /// Handle quorum state changes (stop pinging when quorum disappears).
    @MessageReceiver
    @Contract void onQuorumStateChange(QuorumStateNotification notification);

    /// Stop the scheduler (for graceful shutdown).
    @Contract void stop();

    /// Create a new MetricsScheduler.
    ///
    /// @param self             This node's ID
    /// @param network          Network for sending messages
    /// @param metricsCollector Collector for local metrics
    /// @param interval         Ping interval
    static MetricsScheduler metricsScheduler(NodeId self,
                                             ClusterNetwork network,
                                             MetricsCollector metricsCollector,
                                             TimeSpan interval) {
        return new MetricsSchedulerImpl(self, network, metricsCollector, interval);
    }

    /// Create with default 1-second interval.
    static MetricsScheduler metricsScheduler(NodeId self,
                                             ClusterNetwork network,
                                             MetricsCollector metricsCollector) {
        return metricsScheduler(self,
                                network,
                                metricsCollector,
                                TimeSpan.timeSpan(1).seconds());
    }
}

class MetricsSchedulerImpl implements MetricsScheduler {
    private static final Logger log = LoggerFactory.getLogger(MetricsSchedulerImpl.class);

    private final NodeId self;
    private final ClusterNetwork network;
    private final MetricsCollector metricsCollector;
    private final TimeSpan interval;

    private final CancellableTask pingTask = CancellableTask.cancellableTask();
    private final AtomicReference<List<NodeId>> topology = new AtomicReference<>(List.of());
    private final AtomicLong quorumSequence = new AtomicLong();

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
    @Contract public void onLeaderChange(LeaderChange leaderChange) {
        if ( leaderChange.localNodeIsLeader()) {
            log.debug("Node {} became leader, starting metrics scheduler", self);
            startPinging();
        } else






























        {
            log.info("Node {} is no longer leader, stopping metrics scheduler", self);
            stopPinging();
        }
    }

    @Override
    @Contract public void onTopologyChange(TopologyChangeNotification topologyChange) {
        switch ( topologyChange) {
            case NodeAdded(_, List<NodeId> newTopology) -> topology.set(newTopology);
            case NodeRemoved(_, List<NodeId> newTopology) -> topology.set(newTopology);
            default -> {}
        }
    }

    @Override
    @Contract public void onQuorumStateChange(QuorumStateNotification notification) {
        if ( !notification.advanceSequence(quorumSequence)) {
            log.debug("Ignoring stale QuorumStateNotification: {}", notification);
            return;
        }
        if ( notification.state() == QuorumStateNotification.State.DISAPPEARED) {
            log.info("Quorum disappeared, stopping metrics scheduler");
            stopPinging();
        }
    }

    @Override
    @Contract public void stop() {
        stopPinging();
    }

    private void startPinging() {
        pingTask.set(SharedScheduler.scheduleAtFixedRate(this::sendPingsToAllNodes, interval));
    }

    private void stopPinging() {
        pingTask.cancel();
    }

    private void sendPingsToAllNodes() {
        try {
            var currentTopology = topology.get();
            if ( currentTopology.isEmpty()) {
            return;}
            var ping = new MetricsPing(self, metricsCollector.allMetrics());
            currentTopology.stream().filter(nodeId -> !nodeId.equals(self))
                                  .forEach(nodeId -> network.send(nodeId, ping));
            log.trace("Sent MetricsPing to {} nodes", currentTopology.size() - 1);
        }






























        catch (Exception e) {
            log.warn("Failed to send metrics ping: {}", e.getMessage());
        }
    }
}
