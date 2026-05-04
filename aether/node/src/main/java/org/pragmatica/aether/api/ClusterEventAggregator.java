// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.api.ClusterEvent.AccessDenied;
import org.pragmatica.aether.api.ClusterEvent.BackupCreated;
import org.pragmatica.aether.api.ClusterEvent.BackupRestored;
import org.pragmatica.aether.api.ClusterEvent.BlueprintDeleted;
import org.pragmatica.aether.api.ClusterEvent.BlueprintDeployed;
import org.pragmatica.aether.api.ClusterEvent.ConfigChanged;
import org.pragmatica.aether.api.ClusterEvent.ConnectionEstablished;
import org.pragmatica.aether.api.ClusterEvent.ConnectionFailed;
import org.pragmatica.aether.api.ClusterEvent.DeploymentCompleted;
import org.pragmatica.aether.api.ClusterEvent.DeploymentFailed;
import org.pragmatica.aether.api.ClusterEvent.DeploymentStarted;
import org.pragmatica.aether.api.ClusterEvent.GenerationChanged;
import org.pragmatica.aether.api.ClusterEvent.LeaderElected;
import org.pragmatica.aether.api.ClusterEvent.LeaderLost;
import org.pragmatica.aether.api.ClusterEvent.NodeFailed;
import org.pragmatica.aether.api.ClusterEvent.NodeJoined;
import org.pragmatica.aether.api.ClusterEvent.NodeLeft;
import org.pragmatica.aether.api.ClusterEvent.NodeLifecycleChanged;
import org.pragmatica.aether.api.ClusterEvent.QuorumEstablished;
import org.pragmatica.aether.api.ClusterEvent.QuorumLost;
import org.pragmatica.aether.api.ClusterEvent.ScaleDown;
import org.pragmatica.aether.api.ClusterEvent.ScaleUp;
import org.pragmatica.aether.api.ClusterEvent.Severity;
import org.pragmatica.aether.api.ClusterEvent.SliceFailure;
import org.pragmatica.aether.controller.ScalingEvent;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.invoke.SliceFailureEvent;
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
    private final EventIdAllocator eventIdAllocator;
    private final NodeId selfNode;

    private final AtomicLong quorumSequence = new AtomicLong();

    private final ConcurrentHashMap<String, Long> deploymentStartTimes = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Long> nodeJoinTimes = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<NodeId, NodeLifecycleState> lastLifecycleState = new ConcurrentHashMap<>();

    private final IntSupplier clusterSizeSupplier;

    private ClusterEventAggregator(ClusterEventAggregatorConfig config,
                                   NodeId selfNode,
                                   EventIdAllocator eventIdAllocator,
                                   IntSupplier clusterSizeSupplier) {
        this.buffer = RingBuffer.ringBuffer(config.maxEvents());
        this.selfNode = selfNode;
        this.eventIdAllocator = eventIdAllocator;
        this.clusterSizeSupplier = clusterSizeSupplier;
    }

    public static ClusterEventAggregator clusterEventAggregator(ClusterEventAggregatorConfig config,
                                                                NodeId selfNode,
                                                                EventIdAllocator eventIdAllocator) {
        return new ClusterEventAggregator(config, selfNode, eventIdAllocator, UNKNOWN_CLUSTER_SIZE);
    }

    public static ClusterEventAggregator clusterEventAggregator(ClusterEventAggregatorConfig config,
                                                                NodeId selfNode,
                                                                EventIdAllocator eventIdAllocator,
                                                                IntSupplier clusterSizeSupplier) {
        return new ClusterEventAggregator(config, selfNode, eventIdAllocator, clusterSizeSupplier);
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
        buffer.add(new NodeJoined(eventIdAllocator.next(),
                                  Instant.now(),
                                  selfNode,
                                  Severity.INFO,
                                  "Node " + event.nodeId().id() + " joined cluster (now " + event.topology().size() + " nodes)",
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
        var nodeId = put.cause().key().nodeId();
        var newState = put.cause().value().state();
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
        buffer.add(new NodeLeft(eventIdAllocator.next(),
                                Instant.now(),
                                selfNode,
                                Severity.INFO,
                                "Node " + nodeId + " left cluster (now " + clusterSize + " nodes)",
                                Map.of("nodeId", nodeId,
                                       "clusterSize", String.valueOf(clusterSize),
                                       "source", source)));
    }

    @Contract private void bufferNodeFailedEvent(String nodeId, int clusterSize, String source) {
        buffer.add(new NodeFailed(eventIdAllocator.next(),
                                  Instant.now(),
                                  selfNode,
                                  Severity.CRITICAL,
                                  "Node " + nodeId + " failed (cluster size " + clusterSize + ")",
                                  Map.of("nodeId", nodeId,
                                         "clusterSize", String.valueOf(clusterSize),
                                         "source", source)));
    }

    @Contract private void bufferNodeLifecycleChangedEvent(String nodeId,
                                                           NodeLifecycleState prior,
                                                           NodeLifecycleState next) {
        var transition = (prior == null
                          ? "NONE"
                          : prior.name()) + "->" + next.name();
        buffer.add(new NodeLifecycleChanged(eventIdAllocator.next(),
                                            Instant.now(),
                                            selfNode,
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
        event.leaderId().onPresent(leaderId -> buffer.add(new LeaderElected(eventIdAllocator.next(),
                                                                            Instant.now(),
                                                                            selfNode,
                                                                            Severity.INFO,
                                                                            "Node " + leaderId.id() + " elected as leader",
                                                                            Map.of("leaderId", leaderId.id()))))
                      .onEmpty(() -> buffer.add(new LeaderLost(eventIdAllocator.next(),
                                                               Instant.now(),
                                                               selfNode,
                                                               Severity.WARNING,
                                                               "Leadership lost, election in progress",
                                                               Map.of())));
    }

    public void onQuorumStateChange(QuorumStateNotification event) {
        if (!event.advanceSequence(quorumSequence)) {return;}
        switch (event.state()){
            case ESTABLISHED -> buffer.add(new QuorumEstablished(eventIdAllocator.next(),
                                                                 Instant.now(),
                                                                 selfNode,
                                                                 Severity.INFO,
                                                                 "Quorum established",
                                                                 Map.of()));
            case DISAPPEARED -> buffer.add(new QuorumLost(eventIdAllocator.next(),
                                                          Instant.now(),
                                                          selfNode,
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
        buffer.add(new DeploymentStarted(eventIdAllocator.next(),
                                         Instant.now(),
                                         selfNode,
                                         Severity.INFO,
                                         "Deploying " + artifact + " to " + nodeId,
                                         Map.of("artifact", artifact, "nodeId", nodeId)));
    }

    private void handleDeploymentCompleted(String trackingKey, String artifact, String nodeId) {
        var durationMs = computeAndRemoveDuration(trackingKey);
        var durationSuffix = durationMs.map(ms -> " in " + formatDuration(ms)).or("");
        var nodeReadySuffix = buildNodeReadySuffix(nodeId);
        buffer.add(new DeploymentCompleted(eventIdAllocator.next(),
                                           Instant.now(),
                                           selfNode,
                                           Severity.INFO,
                                           "Deployed " + artifact + " on " + nodeId + durationSuffix + nodeReadySuffix,
                                           buildCompletedMetadata(artifact, nodeId, durationMs)));
    }

    private void handleDeploymentFailed(String trackingKey, String artifact, String nodeId, NodeArtifactValue value) {
        var durationMs = computeAndRemoveDuration(trackingKey);
        var durationSuffix = durationMs.map(ms -> " after " + formatDuration(ms)).or("");
        var reason = value.failureReason().or("unknown");
        buffer.add(new DeploymentFailed(eventIdAllocator.next(),
                                        Instant.now(),
                                        selfNode,
                                        Severity.WARNING,
                                        "Deployment of " + artifact + " failed on " + nodeId + durationSuffix + ": " + reason,
                                        buildFailedMetadata(artifact, nodeId, reason, durationMs)));
    }

    public void onSliceFailure(SliceFailureEvent.AllInstancesFailed event) {
        buffer.add(new SliceFailure(eventIdAllocator.next(),
                                    Instant.now(),
                                    selfNode,
                                    Severity.CRITICAL,
                                    "All instances of " + event.artifact().asString() + ":" + event.method().name() + " failed",
                                    Map.of("artifact",
                                           event.artifact().asString(),
                                           "method",
                                           event.method().name(),
                                           "attemptedNodes",
                                           String.valueOf(event.attemptedNodes().size()))));
    }

    public void onScaledUp(ScalingEvent.ScaledUp event) {
        buffer.add(new ScaleUp(eventIdAllocator.next(),
                               Instant.now(),
                               selfNode,
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
        buffer.add(new ScaleDown(eventIdAllocator.next(),
                                 Instant.now(),
                                 selfNode,
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
        var direction = event.currentInstances() < event.desiredInstances()
                       ? "up"
                       : "down";
        var summary = "Reconciliation: " + event.artifact().asString() + " adjusted " + direction + " from "
                      + event.currentInstances() + " to " + event.desiredInstances() + " instances";
        var details = Map.of("artifact",
                             event.artifact().asString(),
                             "previousInstances",
                             String.valueOf(event.currentInstances()),
                             "desiredInstances",
                             String.valueOf(event.desiredInstances()),
                             "trigger",
                             "reconciliation");
        var event2 = event.currentInstances() < event.desiredInstances()
                     ? new ScaleUp(eventIdAllocator.next(), Instant.now(), selfNode, Severity.INFO, summary, details)
                     : (ClusterEvent) new ScaleDown(eventIdAllocator.next(), Instant.now(), selfNode, Severity.INFO, summary, details);
        buffer.add(event2);
    }

    public void onConnectionEstablished(NetworkServiceMessage.ConnectionEstablished event) {
        buffer.add(new ConnectionEstablished(eventIdAllocator.next(),
                                             Instant.now(),
                                             selfNode,
                                             Severity.INFO,
                                             "Connected to node " + event.nodeId().id(),
                                             Map.of("nodeId", event.nodeId().id())));
    }

    public void onAccessDenied(OperationalEvent.AccessDenied event) {
        buffer.add(new AccessDenied(eventIdAllocator.next(),
                                    Instant.now(),
                                    selfNode,
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
        buffer.add(new NodeLifecycleChanged(eventIdAllocator.next(),
                                            Instant.now(),
                                            selfNode,
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
        buffer.add(new ConfigChanged(eventIdAllocator.next(),
                                     Instant.now(),
                                     selfNode,
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
        buffer.add(new BackupCreated(eventIdAllocator.next(),
                                     Instant.now(),
                                     selfNode,
                                     Severity.INFO,
                                     "Backup created: " + event.commitId(),
                                     Map.of("commitId", event.commitId(), "requestedBy", event.requestedBy())));
    }

    public void onBackupRestored(OperationalEvent.BackupRestored event) {
        buffer.add(new BackupRestored(eventIdAllocator.next(),
                                      Instant.now(),
                                      selfNode,
                                      Severity.WARNING,
                                      "Backup restored: " + event.commitId(),
                                      Map.of("commitId", event.commitId(), "requestedBy", event.requestedBy())));
    }

    public void onBlueprintDeployed(OperationalEvent.BlueprintDeployed event) {
        buffer.add(new BlueprintDeployed(eventIdAllocator.next(),
                                         Instant.now(),
                                         selfNode,
                                         Severity.INFO,
                                         "Blueprint deployed: " + event.artifactCoords(),
                                         Map.of("artifactCoords",
                                                event.artifactCoords(),
                                                "requestedBy",
                                                event.requestedBy())));
    }

    public void onBlueprintDeleted(OperationalEvent.BlueprintDeleted event) {
        buffer.add(new BlueprintDeleted(eventIdAllocator.next(),
                                        Instant.now(),
                                        selfNode,
                                        Severity.INFO,
                                        "Blueprint deleted: " + event.artifactId(),
                                        Map.of("artifactId", event.artifactId(), "requestedBy", event.requestedBy())));
    }

    public void onGenerationChanged(OperationalEvent.GenerationChanged event) {
        buffer.add(new GenerationChanged(eventIdAllocator.next(),
                                         Instant.now(),
                                         selfNode,
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
        buffer.add(new ConnectionFailed(eventIdAllocator.next(),
                                        Instant.now(),
                                        selfNode,
                                        Severity.WARNING,
                                        "Connection to node " + event.nodeId().id() + " failed: " + event.cause().message(),
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
