package org.pragmatica.aether.api;

import org.pragmatica.aether.api.ClusterEvent.EventType;
import org.pragmatica.aether.api.ClusterEvent.Severity;
import org.pragmatica.aether.controller.ScalingEvent;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.utility.RingBuffer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/// Aggregates cluster events from MessageRouter fan-out into a bounded ring buffer.
///
/// Listens to topology changes, leader elections, quorum state, deployments,
/// slice failures, and network events. Each handler creates a structured
/// ClusterEvent and stores it for dashboard and API consumption.
@SuppressWarnings("JBCT-RET-01")
public final class ClusterEventAggregator {
    private final RingBuffer<ClusterEvent> buffer;
    private final AtomicLong quorumSequence = new AtomicLong();
    private final ConcurrentHashMap<String, Long> deploymentStartTimes = new ConcurrentHashMap<>();

    private ClusterEventAggregator(ClusterEventAggregatorConfig config) {
        this.buffer = RingBuffer.ringBuffer(config.maxEvents());
    }

    /// Factory method following JBCT naming convention.
    public static ClusterEventAggregator clusterEventAggregator(ClusterEventAggregatorConfig config) {
        return new ClusterEventAggregator(config);
    }

    /// Get all events in order from oldest to newest.
    public List<ClusterEvent> events() {
        return buffer.toList();
    }

    /// Get events that occurred after the given timestamp.
    public List<ClusterEvent> eventsSince(Instant since) {
        return buffer.filter(e -> e.timestamp()
                                   .isAfter(since));
    }

    // --- Message handlers ---
    public void onNodeAdded(TopologyChangeNotification.NodeAdded event) {
        buffer.add(ClusterEvent.clusterEvent(EventType.NODE_JOINED,
                                             Severity.INFO,
                                             "Node " + event.nodeId()
                                                            .id() + " joined cluster (now " + event.topology()
                                                                                                   .size() + " nodes)",
                                             Map.of("nodeId",
                                                    event.nodeId()
                                                         .id(),
                                                    "clusterSize",
                                                    String.valueOf(event.topology()
                                                                        .size()))));
    }

    public void onNodeRemoved(TopologyChangeNotification.NodeRemoved event) {
        buffer.add(ClusterEvent.clusterEvent(EventType.NODE_LEFT,
                                             Severity.INFO,
                                             "Node " + event.nodeId()
                                                            .id() + " left cluster (now " + event.topology()
                                                                                                 .size() + " nodes)",
                                             Map.of("nodeId",
                                                    event.nodeId()
                                                         .id(),
                                                    "clusterSize",
                                                    String.valueOf(event.topology()
                                                                        .size()))));
    }

    public void onNodeDown(TopologyChangeNotification.NodeDown event) {
        buffer.add(ClusterEvent.clusterEvent(EventType.NODE_FAILED,
                                             Severity.CRITICAL,
                                             "Node " + event.nodeId()
                                                           .id() + " failed",
                                             Map.of("nodeId",
                                                    event.nodeId()
                                                         .id())));
    }

    public void onLeaderChange(LeaderNotification.LeaderChange event) {
        event.leaderId()
             .onPresent(leaderId -> buffer.add(ClusterEvent.clusterEvent(EventType.LEADER_ELECTED,
                                                                         Severity.INFO,
                                                                         "Node " + leaderId.id() + " elected as leader",
                                                                         Map.of("leaderId",
                                                                                leaderId.id()))))
             .onEmpty(() -> buffer.add(ClusterEvent.clusterEvent(EventType.LEADER_LOST,
                                                                 Severity.WARNING,
                                                                 "Leadership lost, election in progress",
                                                                 Map.of())));
    }

    public void onQuorumStateChange(QuorumStateNotification event) {
        if (!event.advanceSequence(quorumSequence)) {
            return;
        }
        switch (event.state()) {
            case ESTABLISHED ->
            buffer.add(ClusterEvent.clusterEvent(EventType.QUORUM_ESTABLISHED,
                                                 Severity.INFO,
                                                 "Quorum established",
                                                 Map.of()));
            case DISAPPEARED ->
            buffer.add(ClusterEvent.clusterEvent(EventType.QUORUM_LOST, Severity.CRITICAL, "Quorum lost", Map.of()));
        }
    }

    public void onDeploymentStarted(DeploymentEvent.DeploymentStarted event) {
        var key = event.artifact()
                       .asString() + ":" + event.targetNode()
                                               .id();
        deploymentStartTimes.put(key, event.timestamp());
        buffer.add(ClusterEvent.clusterEvent(EventType.DEPLOYMENT_STARTED,
                                             Severity.INFO,
                                             "Deploying " + event.artifact()
                                                                 .asString() + " to " + event.targetNode()
                                                                                             .id(),
                                             Map.of("artifact",
                                                    event.artifact()
                                                         .asString(),
                                                    "nodeId",
                                                    event.targetNode()
                                                         .id())));
    }

    public void onDeploymentCompleted(DeploymentEvent.DeploymentCompleted event) {
        var key = event.artifact()
                       .asString() + ":" + event.nodeId()
                                               .id();
        var startTime = deploymentStartTimes.remove(key);
        var durationMs = startTime != null
                         ? event.timestamp() - startTime
                         : - 1L;
        var durationSuffix = durationMs >= 0
                             ? " in " + formatDuration(durationMs)
                             : "";
        buffer.add(ClusterEvent.clusterEvent(EventType.DEPLOYMENT_COMPLETED,
                                             Severity.INFO,
                                             "Deployed " + event.artifact()
                                                                .asString() + " on " + event.nodeId()
                                                                                            .id() + durationSuffix,
                                             durationMs >= 0
                                             ? Map.of("artifact",
                                                      event.artifact()
                                                           .asString(),
                                                      "nodeId",
                                                      event.nodeId()
                                                           .id(),
                                                      "durationMs",
                                                      String.valueOf(durationMs))
                                             : Map.of("artifact",
                                                      event.artifact()
                                                           .asString(),
                                                      "nodeId",
                                                      event.nodeId()
                                                           .id())));
    }

    public void onDeploymentFailed(DeploymentEvent.DeploymentFailed event) {
        var key = event.artifact()
                       .asString() + ":" + event.nodeId()
                                               .id();
        var startTime = deploymentStartTimes.remove(key);
        var durationMs = startTime != null
                         ? event.timestamp() - startTime
                         : - 1L;
        var durationSuffix = durationMs >= 0
                             ? " after " + formatDuration(durationMs)
                             : "";
        buffer.add(ClusterEvent.clusterEvent(EventType.DEPLOYMENT_FAILED,
                                             Severity.WARNING,
                                             "Deployment of " + event.artifact()
                                                                     .asString() + " failed on " + event.nodeId()
                                                                                                        .id() + " at " + event.failedAt()
                                                                                                                              .name() + durationSuffix,
                                             durationMs >= 0
                                             ? Map.of("artifact",
                                                      event.artifact()
                                                           .asString(),
                                                      "nodeId",
                                                      event.nodeId()
                                                           .id(),
                                                      "failedAt",
                                                      event.failedAt()
                                                           .name(),
                                                      "durationMs",
                                                      String.valueOf(durationMs))
                                             : Map.of("artifact",
                                                      event.artifact()
                                                           .asString(),
                                                      "nodeId",
                                                      event.nodeId()
                                                           .id(),
                                                      "failedAt",
                                                      event.failedAt()
                                                           .name())));
    }

    public void onSliceFailure(SliceFailureEvent.AllInstancesFailed event) {
        buffer.add(ClusterEvent.clusterEvent(EventType.SLICE_FAILURE,
                                             Severity.CRITICAL,
                                             "All instances of " + event.artifact()
                                                                        .asString() + ":" + event.method()
                                                                                                 .name() + " failed",
                                             Map.of("artifact",
                                                    event.artifact()
                                                         .asString(),
                                                    "method",
                                                    event.method()
                                                         .name(),
                                                    "attemptedNodes",
                                                    String.valueOf(event.attemptedNodes()
                                                                        .size()))));
    }

    public void onScaledUp(ScalingEvent.ScaledUp event) {
        buffer.add(ClusterEvent.clusterEvent(EventType.SCALE_UP,
                                             Severity.INFO,
                                             event.artifact()
                                                  .asString() + " scaled up from " + event.previousInstances() + " to " + event.newInstances()
                                             + " instances",
                                             Map.of("artifact",
                                                    event.artifact()
                                                         .asString(),
                                                    "previousInstances",
                                                    String.valueOf(event.previousInstances()),
                                                    "newInstances",
                                                    String.valueOf(event.newInstances()))));
    }

    public void onScaledDown(ScalingEvent.ScaledDown event) {
        buffer.add(ClusterEvent.clusterEvent(EventType.SCALE_DOWN,
                                             Severity.INFO,
                                             event.artifact()
                                                  .asString() + " scaled down from " + event.previousInstances()
                                             + " to " + event.newInstances() + " instances",
                                             Map.of("artifact",
                                                    event.artifact()
                                                         .asString(),
                                                    "previousInstances",
                                                    String.valueOf(event.previousInstances()),
                                                    "newInstances",
                                                    String.valueOf(event.newInstances()))));
    }

    public void onConnectionEstablished(NetworkServiceMessage.ConnectionEstablished event) {
        buffer.add(ClusterEvent.clusterEvent(EventType.CONNECTION_ESTABLISHED,
                                             Severity.INFO,
                                             "Connected to node " + event.nodeId()
                                                                        .id(),
                                             Map.of("nodeId",
                                                    event.nodeId()
                                                         .id())));
    }

    public void onConnectionFailed(NetworkServiceMessage.ConnectionFailed event) {
        buffer.add(ClusterEvent.clusterEvent(EventType.CONNECTION_FAILED,
                                             Severity.WARNING,
                                             "Connection to node " + event.nodeId()
                                                                          .id() + " failed: " + event.cause()
                                                                                                     .message(),
                                             Map.of("nodeId",
                                                    event.nodeId()
                                                         .id(),
                                                    "cause",
                                                    event.cause()
                                                         .message())));
    }

    private static String formatDuration(long durationMs) {
        if (durationMs < 1000) {
            return durationMs + "ms";
        }
        return String.format("%.1fs", durationMs / 1000.0);
    }
}
