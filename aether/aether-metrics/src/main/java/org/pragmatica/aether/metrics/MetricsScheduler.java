package org.pragmatica.aether.metrics;

import org.pragmatica.aether.slice.delegation.DelegatedComponent;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.cluster.metrics.MetricsMessage.MetricsPing;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Scheduler for metrics collection that runs on the leader node.
///
///
/// When this node is the leader, periodically sends MetricsPing to all nodes.
/// Each node responds with MetricsPong containing their metrics.
public interface MetricsScheduler extends DelegatedComponent {
    @MessageReceiver@Contract void onTopologyChange(TopologyChangeNotification topologyChange);
    @MessageReceiver@Contract void onQuorumStateChange(QuorumStateNotification notification);
    @Contract void stop();

    static MetricsScheduler metricsScheduler(NodeId self,
                                             ClusterNetwork network,
                                             MetricsCollector metricsCollector,
                                             TimeSpan interval) {
        return new MetricsSchedulerImpl(self, network, metricsCollector, interval);
    }

    static MetricsScheduler metricsScheduler(NodeId self, ClusterNetwork network, MetricsCollector metricsCollector) {
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

    private final AtomicBoolean active = new AtomicBoolean(false);

    MetricsSchedulerImpl(NodeId self, ClusterNetwork network, MetricsCollector metricsCollector, TimeSpan interval) {
        this.self = self;
        this.network = network;
        this.metricsCollector = metricsCollector;
        this.interval = interval;
    }

    @Override public Promise<Unit> activate() {
        log.debug("Node {} activating metrics scheduler", self);
        active.set(true);
        startPinging();
        return Promise.unitPromise();
    }

    @Override public Promise<Unit> deactivate() {
        log.info("Node {} deactivating metrics scheduler", self);
        active.set(false);
        stopPinging();
        return Promise.unitPromise();
    }

    @Override public TaskGroup taskGroup() {
        return TaskGroup.METRICS;
    }

    @Override public boolean isActive() {
        return active.get();
    }

    @Override@Contract public void onTopologyChange(TopologyChangeNotification topologyChange) {
        switch (topologyChange){
            case NodeAdded(_, List<NodeId> newTopology) -> topology.set(newTopology);
            case NodeRemoved(_, List<NodeId> newTopology) -> topology.set(newTopology);
            default -> {}
        }
    }

    @Override@Contract public void onQuorumStateChange(QuorumStateNotification notification) {
        if (!notification.advanceSequence(quorumSequence)) {
            log.debug("Ignoring stale QuorumStateNotification: {}", notification);
            return;
        }
        if (notification.state() == QuorumStateNotification.State.DISAPPEARED) {
            log.info("Quorum disappeared, stopping metrics scheduler");
            stopPinging();
        }
    }

    @Override@Contract public void stop() {
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
            if (currentTopology.isEmpty()) {return;}
            var ping = new MetricsPing(self, metricsCollector.allMetrics());
            currentTopology.stream().filter(nodeId -> !nodeId.equals(self))
                                  .forEach(nodeId -> network.send(nodeId, ping));
            log.trace("Sent MetricsPing to {} nodes", currentTopology.size() - 1);
        } catch (Exception e) {
            log.warn("Failed to send metrics ping: {}", e.getMessage());
        }
    }
}
