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
import org.pragmatica.aether.slice.StreamAccess.StreamEvent;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.stream.FrameworkStreamConsumer;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublisher;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;


/// Aggregates cluster lifecycle events and publishes them into the
/// `system:cluster-events:1.0.0` system stream (spec §6.2).
///
/// **Architecture (stream-namespaces rebuild, Stage 4).** Replaces the prior rc1 transitional
/// stack — `ClusterEventLogPublisher` (rate-capped KV writer) + `ClusterEventLogSweeper` (GC) +
/// the in-process `RingBuffer<ClusterEvent>` materialised view fed by a
/// `(ClusterEventLogKey, ClusterEventValue)` KV subscriber. Producer handlers now build a sealed
/// {@link ClusterEvent} record and {@link #emit(ClusterEvent)} it into the framework events
/// stream. Reads (`events()`, `eventsSince(Instant)`) go through a {@link FrameworkStreamConsumer}
/// backed by the same stream; retention is governed by the stream's bounded retention policy, not
/// by an aggregator-local capacity, so the sweeper is gone.
///
/// Publisher/consumer are provided as suppliers because in the AetherNode bootstrap the aggregator
/// is constructed before the local stream stack exists. During the construction window (before the
/// suppliers' targets are bound) any handler that fires falls back to a framework-level log line —
/// best-effort, spec §13.3. The self-referential `STREAM_REGISTERED` loop for
/// `system:cluster-events` itself is prevented by
/// {@link org.pragmatica.aether.slice.stream.StreamLifecycleEventPolicy#shouldEmit}.
///
/// **rc1 substrate divergence from the PR design.** rc1 deleted the node-lifecycle KV atom and the
/// SWIM subscription; NODE_FAILED / NODE_LEFT are re-sourced from `MembershipDecision`
/// (consensus-committed, cluster-wide facts) via {@link #onMembershipDecision}. The PR's
/// `onNodeLifecyclePut` / SWIM-body `onSwimObservation` handlers have no upstream on rc1 and are
/// intentionally absent here; `onSwimObservation` remains a no-op for router-shape compatibility.
/// Quorum events arrive as rc1's renamed {@link ClusterStateNotification} (`ACTIVE` / `PASSIVE`),
/// not the PR's `QuorumStateNotification` (`ESTABLISHED` / `DISAPPEARED`).
@SuppressWarnings("JBCT-RET-01")
public final class ClusterEventAggregator {
    private static final Logger LOG = LoggerFactory.getLogger(ClusterEventAggregator.class);

    private static final IntSupplier UNKNOWN_CLUSTER_SIZE = () -> -1;

    private static final int FETCH_BATCH = 1024;

    private final Supplier<FrameworkStreamPublisher<ClusterEvent>> publisherSupplier;
    private final Supplier<FrameworkStreamConsumer<ClusterEvent>> consumerSupplier;
    private final HlcClock hlcClock;
    private final NodeId selfNode;

    private final AtomicLong quorumSequence = new AtomicLong();

    private final ConcurrentHashMap<String, Long> deploymentStartTimes = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Long> nodeJoinTimes = new ConcurrentHashMap<>();

    private final IntSupplier clusterSizeSupplier;

    private ClusterEventAggregator(Supplier<FrameworkStreamPublisher<ClusterEvent>> publisherSupplier,
                                   Supplier<FrameworkStreamConsumer<ClusterEvent>> consumerSupplier,
                                   NodeId selfNode,
                                   HlcClock hlcClock,
                                   IntSupplier clusterSizeSupplier) {
        this.publisherSupplier = publisherSupplier;
        this.consumerSupplier = consumerSupplier;
        this.selfNode = selfNode;
        this.hlcClock = hlcClock;
        this.clusterSizeSupplier = clusterSizeSupplier;
    }

    public static ClusterEventAggregator clusterEventAggregator(Supplier<FrameworkStreamPublisher<ClusterEvent>> publisherSupplier,
                                                                Supplier<FrameworkStreamConsumer<ClusterEvent>> consumerSupplier,
                                                                NodeId selfNode,
                                                                HlcClock hlcClock) {
        return new ClusterEventAggregator(publisherSupplier, consumerSupplier, selfNode, hlcClock, UNKNOWN_CLUSTER_SIZE);
    }

    public static ClusterEventAggregator clusterEventAggregator(Supplier<FrameworkStreamPublisher<ClusterEvent>> publisherSupplier,
                                                                Supplier<FrameworkStreamConsumer<ClusterEvent>> consumerSupplier,
                                                                NodeId selfNode,
                                                                HlcClock hlcClock,
                                                                IntSupplier clusterSizeSupplier) {
        return new ClusterEventAggregator(publisherSupplier, consumerSupplier, selfNode, hlcClock, clusterSizeSupplier);
    }

    /// Read all events currently buffered in the system stream's local partition. Every invocation
    /// re-fetches from offset `0` up to `FETCH_BATCH` — intentional for RC1: the events route is a
    /// low-frequency operator surface and the partition is bounded by the stream's retention policy.
    public Promise<List<ClusterEvent>> events() {
        return consume(consumer -> consumer.fetch(0L, FETCH_BATCH).map(ClusterEventAggregator::extractPayloads));
    }

    /// Read events whose timestamp is strictly after `since`.
    public Promise<List<ClusterEvent>> eventsSince(Instant since) {
        return events().map(events -> filterSince(events, since));
    }

    private static List<ClusterEvent> filterSince(List<ClusterEvent> events, Instant since) {
        long sinceMillis = since.toEpochMilli();
        return events.stream().filter(e -> e.at().physicalMillis() > sinceMillis).toList();
    }

    private static List<ClusterEvent> extractPayloads(List<StreamEvent<ClusterEvent>> raw) {
        return raw.stream().map(StreamEvent::payload).toList();
    }

    private Promise<List<ClusterEvent>> consume(Function<FrameworkStreamConsumer<ClusterEvent>, Promise<List<ClusterEvent>>> fn) {
        var consumer = consumerSupplier.get();
        if (consumer == null) {
            LOG.debug("ClusterEventAggregator consumer not yet bound — returning empty");
            return Promise.success(List.of());
        }
        return fn.apply(consumer);
    }

    /// Fire-and-forget publish into the system stream. If the publisher is not yet bound (bootstrap
    /// window), the event is logged rather than dropped silently — spec §13.3.
    @Contract public void emit(ClusterEvent event) {
        var publisher = publisherSupplier.get();
        if (publisher == null) {
            LOG.info("ClusterEventAggregator publisher not yet bound — event {} dropped (bootstrap window)", event);
            return;
        }
        Promise<?> ignored = publisher.publish(event);
    }

    /// NODE_JOINED represents transport-level visibility ("this node observed a peer connect").
    /// CTM provisions replacements that re-occupy the same node-id slot — `MembershipDecision`
    /// doesn't fire (no `coreMemberIds` delta) but `TransportObservation.PeerJoined` does (fresh
    /// QUIC handshake), so this is the surface tests asserting replacement-NODE_JOINED depend on.
    @Contract public void onPeerJoined(TransportObservation.PeerJoined event) {
        nodeJoinTimes.put(event.nodeId().id(), System.currentTimeMillis());
        emit(new NodeJoined(hlcClock.now(),
                            Severity.INFO,
                            "Node " + event.nodeId().id() + " joined cluster (now " + event.topology().size() + " nodes)",
                            Map.of("nodeId", event.nodeId().id(),
                                   "clusterSize", String.valueOf(event.topology().size()))));
    }

    /// rc1 substrate: NODE_FAILED / NODE_LEFT are re-sourced from `MembershipDecision`, not SWIM.
    /// Retained as a no-op for router-shape compatibility.
    @Contract public void onSwimObservation(@SuppressWarnings("unused") org.pragmatica.swim.SwimObservation observation) {}

    @Contract public void onLeaderChange(LeaderNotification.LeaderChange event) {
        event.leaderId().onPresent(leaderId -> emit(new LeaderElected(hlcClock.now(),
                                                                      Severity.INFO,
                                                                      "Node " + leaderId.id() + " elected as leader",
                                                                      Map.of("leaderId", leaderId.id()))))
             .onEmpty(() -> emit(new LeaderLost(hlcClock.now(),
                                                Severity.WARNING,
                                                "Leadership lost, election in progress",
                                                Map.of())));
    }

    @Contract public void onQuorumStateChange(ClusterStateNotification event) {
        if (!event.advanceSequence(quorumSequence)) {return;}
        switch (event.state()) {
            case ACTIVE -> emit(new QuorumEstablished(hlcClock.now(),
                                                      Severity.INFO,
                                                      "Quorum established",
                                                      Map.of()));
            case PASSIVE -> emit(new QuorumLost(hlcClock.now(),
                                                Severity.CRITICAL,
                                                "Quorum lost",
                                                Map.of()));
        }
    }

    /// Subscriber hook for `MembershipDecision` — re-sources NODE_FAILED / NODE_LEFT that
    /// previously came from the now-deleted node-lifecycle atom (membership-v2 finale).
    /// `NodeRemoved` → NODE_FAILED (CRITICAL); `NodeDecommissioned` / `NodeDraining` → NODE_LEFT
    /// (WARNING). Published from every node: `MembershipDecision` is itself a consensus-committed,
    /// cluster-wide fact.
    @Contract public void onMembershipDecision(MembershipDecision decision) {
        switch (decision) {
            case MembershipDecision.NodeRemoved removed -> emit(new NodeFailed(hlcClock.now(),
                                                                               Severity.CRITICAL,
                                                                               "Node " + removed.nodeId().id() + " removed from membership",
                                                                               Map.of("nodeId", removed.nodeId().id())));
            case MembershipDecision.NodeDecommissioned decommissioned -> emit(new NodeLeft(hlcClock.now(),
                                                                                           Severity.WARNING,
                                                                                           "Node " + decommissioned.nodeId().id() + " decommissioned",
                                                                                           Map.of("nodeId", decommissioned.nodeId().id())));
            case MembershipDecision.NodeDraining draining -> emit(new NodeLeft(hlcClock.now(),
                                                                               Severity.WARNING,
                                                                               "Node " + draining.nodeId().id() + " draining",
                                                                               Map.of("nodeId", draining.nodeId().id())));
            case MembershipDecision.NodeJoined ignored -> {}
            case MembershipDecision.NodeJoining ignored -> {}
            case MembershipDecision.NodeFailedDrain ignored -> {}
            case MembershipDecision.NodeShuttingDown ignored -> {}
        }
    }

    @Contract public void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> event) {
        var key = event.cause().key();
        var value = event.cause().value();
        var artifact = key.artifact().asString();
        var nodeId = key.nodeId().id();
        var state = value.state();
        var trackingKey = artifact + ":" + nodeId;
        switch (state) {
            case LOAD -> handleDeploymentStarted(trackingKey, artifact, nodeId);
            case ACTIVE -> handleDeploymentCompleted(trackingKey, artifact, nodeId);
            case FAILED -> handleDeploymentFailed(trackingKey, artifact, nodeId, value);
            default -> {}
        }
    }

    @Contract private void handleDeploymentStarted(String trackingKey, String artifact, String nodeId) {
        deploymentStartTimes.put(trackingKey, System.currentTimeMillis());
        emit(new DeploymentStarted(hlcClock.now(),
                                   Severity.INFO,
                                   "Deploying " + artifact + " to " + nodeId,
                                   Map.of("artifact", artifact, "nodeId", nodeId)));
    }

    @Contract private void handleDeploymentCompleted(String trackingKey, String artifact, String nodeId) {
        var durationMs = computeAndRemoveDuration(trackingKey);
        var durationSuffix = durationMs.map(ms -> " in " + formatDuration(ms)).or("");
        var nodeReadySuffix = buildNodeReadySuffix(nodeId);
        emit(new DeploymentCompleted(hlcClock.now(),
                                     Severity.INFO,
                                     "Deployed " + artifact + " on " + nodeId + durationSuffix + nodeReadySuffix,
                                     buildCompletedMetadata(artifact, nodeId, durationMs)));
    }

    @Contract private void handleDeploymentFailed(String trackingKey, String artifact, String nodeId, NodeArtifactValue value) {
        var durationMs = computeAndRemoveDuration(trackingKey);
        var durationSuffix = durationMs.map(ms -> " after " + formatDuration(ms)).or("");
        var reason = value.failureReason().or("unknown");
        emit(new DeploymentFailed(hlcClock.now(),
                                  Severity.WARNING,
                                  "Deployment of " + artifact + " failed on " + nodeId + durationSuffix + ": " + reason,
                                  buildFailedMetadata(artifact, nodeId, reason, durationMs)));
    }

    @Contract public void onSliceFailure(SliceFailureEvent.AllInstancesFailed event) {
        emit(new SliceFailure(hlcClock.now(),
                              Severity.CRITICAL,
                              "All instances of " + event.artifact().asString() + ":" + event.method().name() + " failed",
                              Map.of("artifact", event.artifact().asString(),
                                     "method", event.method().name(),
                                     "attemptedNodes", String.valueOf(event.attemptedNodes().size()))));
    }

    @Contract public void onScaledUp(ScalingEvent.ScaledUp event) {
        emit(new ScaleUp(hlcClock.now(),
                         Severity.INFO,
                         event.artifact().asString() + " scaled up from " + event.previousInstances() + " to " + event.newInstances() + " instances",
                         Map.of("artifact", event.artifact().asString(),
                                "previousInstances", String.valueOf(event.previousInstances()),
                                "newInstances", String.valueOf(event.newInstances()))));
    }

    @Contract public void onScaledDown(ScalingEvent.ScaledDown event) {
        emit(new ScaleDown(hlcClock.now(),
                           Severity.INFO,
                           event.artifact().asString() + " scaled down from " + event.previousInstances() + " to " + event.newInstances() + " instances",
                           Map.of("artifact", event.artifact().asString(),
                                  "previousInstances", String.valueOf(event.previousInstances()),
                                  "newInstances", String.valueOf(event.newInstances()))));
    }

    @Contract public void onReconciliationAdjustment(ClusterDeploymentManager.ReconciliationAdjustment event) {
        var scalingUp = event.currentInstances() < event.desiredInstances();
        var direction = scalingUp ? "up" : "down";
        var summary = "Reconciliation: " + event.artifact().asString() + " adjusted " + direction + " from "
                      + event.currentInstances() + " to " + event.desiredInstances() + " instances";
        var details = Map.of("artifact", event.artifact().asString(),
                             "previousInstances", String.valueOf(event.currentInstances()),
                             "desiredInstances", String.valueOf(event.desiredInstances()),
                             "trigger", "reconciliation");
        var event2 = scalingUp
                     ? new ScaleUp(hlcClock.now(), Severity.INFO, summary, details)
                     : (ClusterEvent) new ScaleDown(hlcClock.now(), Severity.INFO, summary, details);
        emit(event2);
    }

    @Contract public void onConnectionEstablished(NetworkServiceMessage.ConnectionEstablished event) {
        emit(new ConnectionEstablished(hlcClock.now(),
                                       Severity.INFO,
                                       "Connected to node " + event.nodeId().id(),
                                       Map.of("nodeId", event.nodeId().id())));
    }

    @Contract public void onAccessDenied(OperationalEvent.AccessDenied event) {
        emit(new AccessDenied(hlcClock.now(),
                              Severity.WARNING,
                              "Access denied for " + event.principal() + " on " + event.method() + " " + event.path(),
                              Map.of("principal", event.principal(),
                                     "method", event.method(),
                                     "path", event.path(),
                                     "actualRole", event.actualRole(),
                                     "requiredRole", event.requiredRole())));
    }

    @Contract public void onNodeLifecycleChanged(OperationalEvent.NodeLifecycleChanged event) {
        emit(new NodeLifecycleChanged(hlcClock.now(),
                                      Severity.INFO,
                                      "Node " + event.nodeId() + " lifecycle: " + event.transition(),
                                      Map.of("nodeId", event.nodeId(),
                                             "transition", event.transition(),
                                             "requestedBy", event.requestedBy())));
    }

    @Contract public void onConfigChanged(OperationalEvent.ConfigChanged event) {
        emit(new ConfigChanged(hlcClock.now(),
                               Severity.INFO,
                               "Config " + event.action() + ": " + event.key() + " (" + event.scope() + ")",
                               Map.of("key", event.key(),
                                      "scope", event.scope(),
                                      "action", event.action(),
                                      "requestedBy", event.requestedBy())));
    }

    @Contract public void onBackupCreated(OperationalEvent.BackupCreated event) {
        emit(new BackupCreated(hlcClock.now(),
                               Severity.INFO,
                               "Backup created: " + event.commitId(),
                               Map.of("commitId", event.commitId(), "requestedBy", event.requestedBy())));
    }

    @Contract public void onBackupRestored(OperationalEvent.BackupRestored event) {
        emit(new BackupRestored(hlcClock.now(),
                                Severity.WARNING,
                                "Backup restored: " + event.commitId(),
                                Map.of("commitId", event.commitId(), "requestedBy", event.requestedBy())));
    }

    @Contract public void onBlueprintDeployed(OperationalEvent.BlueprintDeployed event) {
        emit(new BlueprintDeployed(hlcClock.now(),
                                   Severity.INFO,
                                   "Blueprint deployed: " + event.artifactCoords(),
                                   Map.of("artifactCoords", event.artifactCoords(), "requestedBy", event.requestedBy())));
    }

    @Contract public void onBlueprintDeleted(OperationalEvent.BlueprintDeleted event) {
        emit(new BlueprintDeleted(hlcClock.now(),
                                  Severity.INFO,
                                  "Blueprint deleted: " + event.artifactId(),
                                  Map.of("artifactId", event.artifactId(), "requestedBy", event.requestedBy())));
    }

    @Contract public void onGenerationChanged(OperationalEvent.GenerationChanged event) {
        emit(new GenerationChanged(hlcClock.now(),
                                   Severity.INFO,
                                   "Generation epoch advanced " + event.oldEpoch() + " -> " + event.newEpoch() + " (" + event.reason() + ")",
                                   Map.of("oldEpoch", event.oldEpoch(),
                                          "newEpoch", event.newEpoch(),
                                          "reason", event.reason())));
    }

    @Contract public void onConnectionFailed(NetworkServiceMessage.ConnectionFailed event) {
        emit(new ConnectionFailed(hlcClock.now(),
                                  Severity.WARNING,
                                  "Connection to node " + event.nodeId().id() + " failed: " + event.cause().message(),
                                  Map.of("nodeId", event.nodeId().id(),
                                         "cause", event.cause().message())));
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
        return durationMs.map(ms -> Map.of("artifact", artifact, "nodeId", nodeId, "durationMs", String.valueOf(ms)))
                         .or(Map.of("artifact", artifact, "nodeId", nodeId));
    }

    private static Map<String, String> buildFailedMetadata(String artifact, String nodeId, String reason, Option<Long> durationMs) {
        var base = Map.of("artifact", artifact, "nodeId", nodeId, "reason", reason);
        return durationMs.map(ms -> {
                             var metadata = new java.util.HashMap<>(base);
                             metadata.put("durationMs", String.valueOf(ms));
                             return Map.copyOf(metadata);
                         })
                         .or(base);
    }

    private static String formatDuration(long durationMs) {
        if (durationMs < 1000) {return durationMs + "ms";}
        return String.format("%.1fs", durationMs / 1000.0);
    }
}
