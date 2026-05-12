// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.api.ClusterEvent.EventType;
import org.pragmatica.aether.api.ClusterEvent.Severity;
import org.pragmatica.aether.controller.ScalingEvent;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.swim.SwimObservation;
import org.pragmatica.utility.RingBuffer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;


@SuppressWarnings("JBCT-RET-01") public final class ClusterEventAggregator {
    private static final IntSupplier UNKNOWN_CLUSTER_SIZE = () -> - 1;

    private final RingBuffer<ClusterEvent> buffer;

    private final AtomicLong quorumSequence = new AtomicLong();

    private final ConcurrentHashMap<String, Long> deploymentStartTimes = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Long> nodeJoinTimes = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<NodeId, NodeLifecycleState> lastLifecycleState = new ConcurrentHashMap<>();

    private final IntSupplier clusterSizeSupplier;

    private ClusterEventAggregator(ClusterEventAggregatorConfig config, IntSupplier clusterSizeSupplier) {
        this.buffer = RingBuffer.ringBuffer(config.maxEvents());
        this.clusterSizeSupplier = clusterSizeSupplier;
    }

    public static ClusterEventAggregator clusterEventAggregator(ClusterEventAggregatorConfig config) {
        return new ClusterEventAggregator(config, UNKNOWN_CLUSTER_SIZE);
    }

    public static ClusterEventAggregator clusterEventAggregator(ClusterEventAggregatorConfig config,
                                                                IntSupplier clusterSizeSupplier) {
        return new ClusterEventAggregator(config, clusterSizeSupplier);
    }

    public List<ClusterEvent> events() {
        return buffer.toList();
    }

    public List<ClusterEvent> eventsSince(Instant since) {
        return buffer.filter(e -> e.timestamp().isAfter(since));
    }

    /// NODE_JOINED in the user-facing event stream represents transport-level visibility
    /// ("this node observed a peer connect") rather than canonical cluster-membership
    /// decisions. This matters because CTM provisions replacements that re-occupy the same
    /// node-id slot — `MembershipDecision.NodeJoined` doesn't fire (no `coreMemberIds`
    /// delta) but `TransportObservation.PeerJoined` does (the new VM completes a fresh
    /// QUIC handshake). Tests asserting NODE_JOINED for replacements depend on the
    /// transport-level visibility.
    ///
    /// For canonical-membership state-machines (CDM, CTM, etc.) that need consensus-driven
    /// decisions, see `MembershipDecision.NodeJoined` consumed elsewhere.
    public void onPeerJoined(TransportObservation.PeerJoined event) {
        nodeJoinTimes.put(event.nodeId().id(),
                          System.currentTimeMillis());
        buffer.add(ClusterEvent.clusterEvent(EventType.NODE_JOINED,
                                             Severity.INFO,
                                             "Node " + event.nodeId().id() + " joined cluster (now " + event.topology()
                                                                                                                     .size() + " nodes)",
                                             Map.of("nodeId",
                                                    event.nodeId().id(),
                                                    "clusterSize",
                                                    String.valueOf(event.topology().size()))));
    }

    @Contract public void onSwimObservation(SwimObservation observation) {
        switch (observation){
            case SwimObservation.FaultyObserved faulty -> bufferNodeFailedEvent(faulty.peer().id(),
                                                                                clusterSizeSupplier.getAsInt(),
                                                                                "swim-observation");
            case SwimObservation.DepartedObserved departed -> bufferNodeLeftEvent(departed.peer().id(),
                                                                                  clusterSizeSupplier.getAsInt(),
                                                                                  "swim-observation");
            default -> {}
        }
    }

    @Contract public void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> put) {
        var nodeId = put.cause().key()
                              .nodeId();
        var newState = put.cause().value()
                                .state();
        var prior = lastLifecycleState.put(nodeId, newState);
        if (prior == newState) {return;}
        switch (newState){
            case DECOMMISSIONED -> emitDecommissionEvent(nodeId.id(), prior);
            case DRAINING -> bufferNodeLifecycleChangedEvent(nodeId.id(), prior, newState);
            default -> {}
        }
    }

    @Contract private void emitDecommissionEvent(String nodeId, NodeLifecycleState prior) {
        if (prior == NodeLifecycleState.DRAINING) {bufferNodeLeftEvent(nodeId,
                                                                       clusterSizeSupplier.getAsInt(),
                                                                       "lifecycle-kv");} else {bufferNodeFailedEvent(nodeId,
                                                                                                                     clusterSizeSupplier.getAsInt(),
                                                                                                                     "lifecycle-kv");}
    }

    @Contract private void bufferNodeLeftEvent(String nodeId, int clusterSize, String source) {
        buffer.add(ClusterEvent.clusterEvent(EventType.NODE_LEFT,
                                             Severity.INFO,
                                             "Node " + nodeId + " left cluster (now " + clusterSize + " nodes)",
                                             Map.of("nodeId",
                                                    nodeId,
                                                    "clusterSize",
                                                    String.valueOf(clusterSize),
                                                    "source",
                                                    source)));
    }

    @Contract private void bufferNodeFailedEvent(String nodeId, int clusterSize, String source) {
        buffer.add(ClusterEvent.clusterEvent(EventType.NODE_FAILED,
                                             Severity.CRITICAL,
                                             "Node " + nodeId + " failed (cluster size " + clusterSize + ")",
                                             Map.of("nodeId",
                                                    nodeId,
                                                    "clusterSize",
                                                    String.valueOf(clusterSize),
                                                    "source",
                                                    source)));
    }

    @Contract private void bufferNodeLifecycleChangedEvent(String nodeId,
                                                           NodeLifecycleState prior,
                                                           NodeLifecycleState next) {
        var transition = (prior == null
                          ? "NONE"
                          : prior.name()) + "->" + next.name();
        buffer.add(ClusterEvent.clusterEvent(EventType.NODE_LIFECYCLE_CHANGED,
                                             Severity.INFO,
                                             "Node " + nodeId + " lifecycle: " + transition,
                                             Map.of("nodeId",
                                                    nodeId,
                                                    "transition",
                                                    transition,
                                                    "requestedBy",
                                                    "MembershipFsm")));
    }

    public void onLeaderChange(LeaderNotification.LeaderChange event) {
        event.leaderId().onPresent(leaderId -> buffer.add(ClusterEvent.clusterEvent(EventType.LEADER_ELECTED,
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
        if (!event.advanceSequence(quorumSequence)) {return;}
        switch (event.state()){
            case ESTABLISHED -> buffer.add(ClusterEvent.clusterEvent(EventType.QUORUM_ESTABLISHED,
                                                                     Severity.INFO,
                                                                     "Quorum established",
                                                                     Map.of()));
            case DISAPPEARED -> buffer.add(ClusterEvent.clusterEvent(EventType.QUORUM_LOST,
                                                                     Severity.CRITICAL,
                                                                     "Quorum lost",
                                                                     Map.of()));
        }
    }

    public void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> event) {
        var key = event.cause().key();
        var value = event.cause().value();
        var artifact = key.artifact().asString();
        var nodeId = key.nodeId().id();
        var state = value.state();
        var trackingKey = artifact + ":" + nodeId;
        switch (state){
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
        var durationSuffix = durationMs.map(ms -> " in " + formatDuration(ms)).or("");
        var nodeReadySuffix = buildNodeReadySuffix(nodeId);
        buffer.add(ClusterEvent.clusterEvent(EventType.DEPLOYMENT_COMPLETED,
                                             Severity.INFO,
                                             "Deployed " + artifact + " on " + nodeId + durationSuffix + nodeReadySuffix,
                                             buildCompletedMetadata(artifact, nodeId, durationMs)));
    }

    private void handleDeploymentFailed(String trackingKey, String artifact, String nodeId, NodeArtifactValue value) {
        var durationMs = computeAndRemoveDuration(trackingKey);
        var durationSuffix = durationMs.map(ms -> " after " + formatDuration(ms)).or("");
        var reason = value.failureReason().or("unknown");
        buffer.add(ClusterEvent.clusterEvent(EventType.DEPLOYMENT_FAILED,
                                             Severity.WARNING,
                                             "Deployment of " + artifact + " failed on " + nodeId + durationSuffix + ": " + reason,
                                             buildFailedMetadata(artifact, nodeId, reason, durationMs)));
    }

    public void onSliceFailure(SliceFailureEvent.AllInstancesFailed event) {
        buffer.add(ClusterEvent.clusterEvent(EventType.SLICE_FAILURE,
                                             Severity.CRITICAL,
                                             "All instances of " + event.artifact().asString() + ":" + event.method()
                                                                                                                   .name() + " failed",
                                             Map.of("artifact",
                                                    event.artifact().asString(),
                                                    "method",
                                                    event.method().name(),
                                                    "attemptedNodes",
                                                    String.valueOf(event.attemptedNodes().size()))));
    }

    public void onScaledUp(ScalingEvent.ScaledUp event) {
        buffer.add(ClusterEvent.clusterEvent(EventType.SCALE_UP,
                                             Severity.INFO,
                                             event.artifact().asString() + " scaled up from " + event.previousInstances() + " to " + event.newInstances() + " instances",
                                             Map.of("artifact",
                                                    event.artifact().asString(),
                                                    "previousInstances",
                                                    String.valueOf(event.previousInstances()),
                                                    "newInstances",
                                                    String.valueOf(event.newInstances()))));
    }

    public void onScaledDown(ScalingEvent.ScaledDown event) {
        buffer.add(ClusterEvent.clusterEvent(EventType.SCALE_DOWN,
                                             Severity.INFO,
                                             event.artifact().asString() + " scaled down from " + event.previousInstances() + " to " + event.newInstances() + " instances",
                                             Map.of("artifact",
                                                    event.artifact().asString(),
                                                    "previousInstances",
                                                    String.valueOf(event.previousInstances()),
                                                    "newInstances",
                                                    String.valueOf(event.newInstances()))));
    }

    public void onReconciliationAdjustment(ClusterDeploymentManager.ReconciliationAdjustment event) {
        var direction = event.currentInstances() <event.desiredInstances()
                       ? "up"
                       : "down";
        var eventType = event.currentInstances() <event.desiredInstances()
                       ? EventType.SCALE_UP
                       : EventType.SCALE_DOWN;
        buffer.add(ClusterEvent.clusterEvent(eventType,
                                             Severity.INFO,
                                             "Reconciliation: " + event.artifact().asString() + " adjusted " + direction + " from " + event.currentInstances() + " to " + event.desiredInstances() + " instances",
                                             Map.of("artifact",
                                                    event.artifact().asString(),
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
                                             "Connected to node " + event.nodeId().id(),
                                             Map.of("nodeId",
                                                    event.nodeId().id())));
    }

    public void onAccessDenied(OperationalEvent.AccessDenied event) {
        buffer.add(ClusterEvent.clusterEvent(EventType.ACCESS_DENIED,
                                             Severity.WARNING,
                                             "Access denied for " + event.principal() + " on " + event.method() + " " + event.path(),
                                             Map.of("principal",
                                                    event.principal(),
                                                    "method",
                                                    event.method(),
                                                    "path",
                                                    event.path(),
                                                    "actualRole",
                                                    event.actualRole(),
                                                    "requiredRole",
                                                    event.requiredRole())));
    }

    public void onNodeLifecycleChanged(OperationalEvent.NodeLifecycleChanged event) {
        buffer.add(ClusterEvent.clusterEvent(EventType.NODE_LIFECYCLE_CHANGED,
                                             Severity.INFO,
                                             "Node " + event.nodeId() + " lifecycle: " + event.transition(),
                                             Map.of("nodeId",
                                                    event.nodeId(),
                                                    "transition",
                                                    event.transition(),
                                                    "requestedBy",
                                                    event.requestedBy())));
    }

    public void onConfigChanged(OperationalEvent.ConfigChanged event) {
        buffer.add(ClusterEvent.clusterEvent(EventType.CONFIG_CHANGED,
                                             Severity.INFO,
                                             "Config " + event.action() + ": " + event.key() + " (" + event.scope() + ")",
                                             Map.of("key",
                                                    event.key(),
                                                    "scope",
                                                    event.scope(),
                                                    "action",
                                                    event.action(),
                                                    "requestedBy",
                                                    event.requestedBy())));
    }

    public void onBackupCreated(OperationalEvent.BackupCreated event) {
        buffer.add(ClusterEvent.clusterEvent(EventType.BACKUP_CREATED,
                                             Severity.INFO,
                                             "Backup created: " + event.commitId(),
                                             Map.of("commitId", event.commitId(), "requestedBy", event.requestedBy())));
    }

    public void onBackupRestored(OperationalEvent.BackupRestored event) {
        buffer.add(ClusterEvent.clusterEvent(EventType.BACKUP_RESTORED,
                                             Severity.WARNING,
                                             "Backup restored: " + event.commitId(),
                                             Map.of("commitId", event.commitId(), "requestedBy", event.requestedBy())));
    }

    public void onBlueprintDeployed(OperationalEvent.BlueprintDeployed event) {
        buffer.add(ClusterEvent.clusterEvent(EventType.BLUEPRINT_DEPLOYED,
                                             Severity.INFO,
                                             "Blueprint deployed: " + event.artifactCoords(),
                                             Map.of("artifactCoords",
                                                    event.artifactCoords(),
                                                    "requestedBy",
                                                    event.requestedBy())));
    }

    public void onBlueprintDeleted(OperationalEvent.BlueprintDeleted event) {
        buffer.add(ClusterEvent.clusterEvent(EventType.BLUEPRINT_DELETED,
                                             Severity.INFO,
                                             "Blueprint deleted: " + event.artifactId(),
                                             Map.of("artifactId", event.artifactId(), "requestedBy", event.requestedBy())));
    }

    public void onGenerationChanged(OperationalEvent.GenerationChanged event) {
        buffer.add(ClusterEvent.clusterEvent(EventType.GENERATION_CHANGED,
                                             Severity.INFO,
                                             "Generation epoch advanced " + event.oldEpoch() + " -> " + event.newEpoch() + " (" + event.reason() + ")",
                                             Map.of("oldEpoch",
                                                    event.oldEpoch(),
                                                    "newEpoch",
                                                    event.newEpoch(),
                                                    "reason",
                                                    event.reason())));
    }

    public void onConnectionFailed(NetworkServiceMessage.ConnectionFailed event) {
        buffer.add(ClusterEvent.clusterEvent(EventType.CONNECTION_FAILED,
                                             Severity.WARNING,
                                             "Connection to node " + event.nodeId().id() + " failed: " + event.cause()
                                                                                                                    .message(),
                                             Map.of("nodeId",
                                                    event.nodeId().id(),
                                                    "cause",
                                                    event.cause().message())));
    }

    private Option<Long> computeAndRemoveDuration(String trackingKey) {
        return Option.option(deploymentStartTimes.remove(trackingKey))
                            .map(startTime -> System.currentTimeMillis() - startTime);
    }

    private String buildNodeReadySuffix(String nodeId) {
        var nodeJoinTime = nodeJoinTimes.remove(nodeId);
        if (nodeJoinTime == null) {return "";}
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
        if (durationMs <1000) {return durationMs + "ms";}
        return String.format("%.1fs", durationMs / 1000.0);
    }
}
