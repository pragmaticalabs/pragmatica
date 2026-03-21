package org.pragmatica.aether.api;

import org.pragmatica.aether.api.ClusterEvent.EventType;
import org.pragmatica.aether.api.ClusterEvent.Severity;
import org.pragmatica.aether.controller.ScalingEvent;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.lang.Option;
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
    private final ConcurrentHashMap<String, Long> nodeJoinTimes = new ConcurrentHashMap<>();

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
        nodeJoinTimes.put(event.nodeId()
                               .id(),
                          System.currentTimeMillis());
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

    /// Derive deployment events from NodeArtifactKey state transitions in KV-Store.
    /// This is the authoritative source — visible on ALL nodes via consensus.
    public void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> event) {
        var key = event.cause()
                       .key();
        var value = event.cause()
                         .value();
        var artifact = key.artifact()
                          .asString();
        var nodeId = key.nodeId()
                        .id();
        var state = value.state();
        var trackingKey = artifact + ":" + nodeId;
        switch (state) {
            case LOAD -> handleDeploymentStarted(trackingKey, artifact, nodeId);
            case ACTIVE -> handleDeploymentCompleted(trackingKey, artifact, nodeId);
            case FAILED -> handleDeploymentFailed(trackingKey, artifact, nodeId, value);
            default -> {}
        }
    }

    private void handleDeploymentStarted(String trackingKey, String artifact, String nodeId) {
        deploymentStartTimes.put(trackingKey, System.currentTimeMillis());
        buffer.add(ClusterEvent.clusterEvent(EventType.DEPLOYMENT_STARTED,
                                             Severity.INFO,
                                             "Deploying " + artifact + " to " + nodeId,
                                             Map.of("artifact", artifact, "nodeId", nodeId)));
    }

    private void handleDeploymentCompleted(String trackingKey, String artifact, String nodeId) {
        var durationMs = computeAndRemoveDuration(trackingKey);
        var durationSuffix = durationMs.map(ms -> " in " + formatDuration(ms))
                                       .or("");
        var nodeReadySuffix = buildNodeReadySuffix(nodeId);
        buffer.add(ClusterEvent.clusterEvent(EventType.DEPLOYMENT_COMPLETED,
                                             Severity.INFO,
                                             "Deployed " + artifact + " on " + nodeId + durationSuffix + nodeReadySuffix,
                                             buildCompletedMetadata(artifact, nodeId, durationMs)));
    }

    private void handleDeploymentFailed(String trackingKey,
                                        String artifact,
                                        String nodeId,
                                        NodeArtifactValue value) {
        var durationMs = computeAndRemoveDuration(trackingKey);
        var durationSuffix = durationMs.map(ms -> " after " + formatDuration(ms))
                                       .or("");
        var reason = value.failureReason()
                          .or("unknown");
        buffer.add(ClusterEvent.clusterEvent(EventType.DEPLOYMENT_FAILED,
                                             Severity.WARNING,
                                             "Deployment of " + artifact + " failed on " + nodeId + durationSuffix
                                             + ": " + reason,
                                             buildFailedMetadata(artifact, nodeId, reason, durationMs)));
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

    public void onReconciliationAdjustment(ClusterDeploymentManager.ReconciliationAdjustment event) {
        var direction = event.currentInstances() < event.desiredInstances()
                        ? "up"
                        : "down";
        var eventType = event.currentInstances() < event.desiredInstances()
                        ? EventType.SCALE_UP
                        : EventType.SCALE_DOWN;
        buffer.add(ClusterEvent.clusterEvent(eventType,
                                             Severity.INFO,
                                             "Reconciliation: " + event.artifact()
                                                                       .asString() + " adjusted " + direction + " from " + event.currentInstances()
                                             + " to " + event.desiredInstances() + " instances",
                                             Map.of("artifact",
                                                    event.artifact()
                                                         .asString(),
                                                    "previousInstances",
                                                    String.valueOf(event.currentInstances()),
                                                    "desiredInstances",
                                                    String.valueOf(event.desiredInstances()),
                                                    "trigger",
                                                    "reconciliation")));
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

    private Option<Long> computeAndRemoveDuration(String trackingKey) {
        return Option.option(deploymentStartTimes.remove(trackingKey))
                     .map(startTime -> System.currentTimeMillis() - startTime);
    }

    private String buildNodeReadySuffix(String nodeId) {
        var nodeJoinTime = nodeJoinTimes.remove(nodeId);
        if (nodeJoinTime == null) {
            return "";
        }
        var joinToDeployMs = System.currentTimeMillis() - nodeJoinTime;
        return " (node ready in " + formatDuration(joinToDeployMs) + ")";
    }

    private static Map<String, String> buildCompletedMetadata(String artifact, String nodeId, Option<Long> durationMs) {
        return durationMs.map(ms -> Map.of("artifact",
                                           artifact,
                                           "nodeId",
                                           nodeId,
                                           "durationMs",
                                           String.valueOf(ms)))
                         .or(Map.of("artifact", artifact, "nodeId", nodeId));
    }

    private static Map<String, String> buildFailedMetadata(String artifact,
                                                           String nodeId,
                                                           String reason,
                                                           Option<Long> durationMs) {
        var base = Map.of("artifact", artifact, "nodeId", nodeId, "reason", reason);
        return durationMs.map(ms -> {
                                  var metadata = new java.util.HashMap<>(base);
                                  metadata.put("durationMs",
                                               String.valueOf(ms));
                                  return Map.copyOf(metadata);
                              })
                         .or(base);
    }

    private static String formatDuration(long durationMs) {
        if (durationMs < 1000) {
            return durationMs + "ms";
        }
        return String.format("%.1fs", durationMs / 1000.0);
    }
}
