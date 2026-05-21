// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.controller.ScalingEvent;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterEventLogKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterEventValue;
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
import org.pragmatica.utility.RingBuffer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// RC1 Step 1 — projection over the cluster-scoped replicated event log.
///
/// **Architecture flip (was: node-local RingBuffer fed by direct producer calls; now:
/// node-local RingBuffer fed by `KVStoreNotification.ValuePut<ClusterEventLogKey,
/// ClusterEventValue>` subscriber).**
///
/// Every producer method (e.g., `onPeerJoined`, `onAccessDenied`) delegates to
/// `ClusterEventLogPublisher.publish(...)` — the event flows through Rabia consensus, gets
/// replicated to all nodes, and arrives at every node's `onClusterEventLogPut` listener
/// in identical commit order. The RingBuffer is now a materialised view over that replicated
/// log, not a local stash.
///
/// **`isReplay` semantics.** During cold-boot `KVStore.restoreSnapshot` replays every
/// retained event as a `ValuePut`. The aggregator marks the replay window with
/// `replayActive` so downstream sinks (Slack webhooks, etc.) do NOT re-fire for events
/// already delivered before this node was running. Post-replay, every commit fans out
/// normally.
///
/// **Leader-only producers.** Transport-derived events (`PeerJoined`, `ConnectionEstablished`,
/// `ConnectionFailed`) would N-fold duplicate if every node published them. Per spec §3.6
/// only the leader publishes those — node-derived events (`AccessDenied`, etc.) publish from
/// the originator and the key carries the originator nodeId for cluster-wide dedup.
@SuppressWarnings("JBCT-RET-01")
public final class ClusterEventAggregator {
    private static final Logger log = LoggerFactory.getLogger(ClusterEventAggregator.class);
    private static final IntSupplier UNKNOWN_CLUSTER_SIZE = () -> - 1;
    private static final BooleanSupplier ALWAYS_LEADER = () -> true;

    private final RingBuffer<ClusterEvent> buffer;
    private final AtomicLong quorumSequence = new AtomicLong();
    private final ConcurrentHashMap<String, Long> deploymentStartTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> nodeJoinTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<NodeId, NodeLifecycleState> lastLifecycleState = new ConcurrentHashMap<>();
    private final IntSupplier clusterSizeSupplier;
    private final PublisherShape publisher;

    private final BooleanSupplier isLeaderSupplier;

    /// Per spec §3.6: cold-boot snapshot replay must not fan replayed events out to
    /// downstream sinks (Slack hooks etc. would fire twice on every node restart). The
    /// publisher sets this true while consuming the snapshot replay; once cleared, every
    /// subsequent `ValuePut` is treated as a fresh post-startup commit.
    private volatile boolean replayActive;

    private ClusterEventAggregator(ClusterEventAggregatorConfig config,
                                   IntSupplier clusterSizeSupplier,
                                   PublisherShape publisher,
                                   BooleanSupplier isLeaderSupplier) {
        this.buffer = RingBuffer.ringBuffer(config.maxEvents());
        this.clusterSizeSupplier = clusterSizeSupplier;
        this.publisher = publisher;
        this.isLeaderSupplier = isLeaderSupplier;
        this.replayActive = true;
    }

    /// Test/legacy factory: no publisher wiring (events buffered locally only). Useful for
    /// unit tests that don't want a full consensus harness — pre-RC1 behaviour minus the
    /// guarantees the replicated log provides.
    public static ClusterEventAggregator clusterEventAggregator(ClusterEventAggregatorConfig config) {
        return new ClusterEventAggregator(config, UNKNOWN_CLUSTER_SIZE, NullPublisher.INSTANCE, ALWAYS_LEADER);
    }

    public static ClusterEventAggregator clusterEventAggregator(ClusterEventAggregatorConfig config,
                                                                IntSupplier clusterSizeSupplier) {
        return new ClusterEventAggregator(config, clusterSizeSupplier, NullPublisher.INSTANCE, ALWAYS_LEADER);
    }

    /// Production factory: wires the cluster-scoped publisher and the isLeader gate (for
    /// transport-derived event leader-only emission).
    public static ClusterEventAggregator clusterEventAggregator(ClusterEventAggregatorConfig config,
                                                                IntSupplier clusterSizeSupplier,
                                                                ClusterEventLogPublisher publisher,
                                                                BooleanSupplier isLeaderSupplier) {
        return new ClusterEventAggregator(config, clusterSizeSupplier, new RealPublisher(publisher), isLeaderSupplier);
    }

    public List<ClusterEvent> events() {
        return buffer.toList();
    }

    /// Cursor-based pagination: returns events with `(epoch, seq) > (sinceEpoch, sinceSeq)`
    /// from the materialised view. Returns
    /// commit-order with strict total ordering by the Rabia-assigned key pair.
    ///
    /// The materialised view doesn't surface `(epoch, seq)` to `ClusterEvent` consumers
    /// today; this method filters by inspecting `details.originEpoch` + `details.originSeq`
    /// metadata stamps written by `onClusterEventLogPut`. Callers without `(epoch, seq)`
    /// state should treat `(0, -1)` as "from the beginning".
    public List<ClusterEvent> eventsSince(long sinceEpoch, long sinceSeq) {
        return buffer.filter(e -> isAfter(e, sinceEpoch, sinceSeq));
    }

    private static boolean isAfter(ClusterEvent event, long sinceEpoch, long sinceSeq) {
        var epoch = parseLongOrSentinel(event.details().get("originEpoch"));
        var seq = parseLongOrSentinel(event.details().get("originSeq"));

        if (epoch == Long.MIN_VALUE || seq == Long.MIN_VALUE) {return true;}

        return epoch > sinceEpoch || (epoch == sinceEpoch && seq > sinceSeq);
    }

    /// Returns `Long.MIN_VALUE` on null/unparseable input — the cursor-filter treats that as
    /// "include event" (post-startup safety: never silently drop). Wraps the JDK's exception-
    /// throwing parse in a single-call boundary.
    private static long parseLongOrSentinel(String raw) {
        if (raw == null) {return Long.MIN_VALUE;}
        return org.pragmatica.lang.parse.Number.parseLong(raw)
                                               .or(Long.MIN_VALUE);
    }

    /// Signal that snapshot replay is complete and subsequent commits should be treated as
    /// fresh post-startup events. Called by AetherNode lifecycle wiring once `clusterNode`
    /// reports replay-complete (or after a quiet-window heuristic).
    @Contract
    public void markReplayComplete() {
        if (replayActive) {
            replayActive = false;
            log.info("ClusterEventAggregator: snapshot replay complete, downstream sinks active");
        }
    }

    /// **Materialised-view subscriber.** Called for every committed `ClusterEventLogKey`
    /// `Put`. On cold-boot snapshot replay this runs in bulk before the cluster is fully
    /// started; `replayActive` is true throughout. After `markReplayComplete()` every
    /// arrival is post-startup and may fan out to downstream sinks (TODO: wire those once
    /// the OB1/OB2 integration test confirms the core ordering invariant).
    @Contract
    public void onClusterEventLogPut(ValuePut<ClusterEventLogKey, ClusterEventValue> put) {
        var key = put.cause().key();
        var value = put.cause().value();
        var event = projectToEvent(key, value);
        buffer.add(event);
        // Downstream sink fan-out hook: skip during replay per spec §3.6 isReplay clause.
        // (Sinks not wired yet — gated for the OB1/OB2-validating integration test.)
        if (replayActive) {return;}
    }

    private static ClusterEvent projectToEvent(ClusterEventLogKey key, ClusterEventValue value) {
        var base = ClusterEvent.fromValue(value);
        var details = new java.util.HashMap<>(base.details());
        details.put("originEpoch",
                    Long.toString(key.epoch()));
        details.put("originSeq",
                    Long.toString(key.seq()));
        // RC1 follow-up — per-key originator nodeId. The key carries it for cross-node
        // disambiguation (eliminates `(epoch, seq)` collisions when concurrent writers share
        // a per-node seq counter). `originNodeId` from the VALUE remains the user-facing
        // attribution; `keyNodeId` exposes the keyspace owner for cursor diagnostics.
        details.put("keyNodeId",
                    key.nodeId().id());

        return new ClusterEvent(base.timestamp(), base.type(), base.severity(), base.summary(), Map.copyOf(details));
    }

    /// NODE_JOINED in the user-facing event stream represents transport-level visibility
    /// ("this node observed a peer connect") rather than canonical cluster-membership
    /// decisions. RC1 Step 1: only the LEADER publishes — otherwise every node would emit
    /// the same `PeerJoined` (same physical event) N times into the replicated log.
    public void onPeerJoined(TransportObservation.PeerJoined event) {
        nodeJoinTimes.put(event.nodeId().id(),
                          System.currentTimeMillis());

        if (!isLeaderSupplier.getAsBoolean()) {return;}

        publisher.publish(ClusterEventValue.EventType.NODE_JOINED,
                          ClusterEventValue.Severity.INFO,
                          "Node " + event.nodeId().id() + " joined cluster (now " + event.topology().size() + " nodes)",
                          Map.of("nodeId",
                                 event.nodeId().id(),
                                 "clusterSize",
                                 String.valueOf(event.topology().size())));
    }

    @Contract
    public void onSwimObservation(@SuppressWarnings("unused") org.pragmatica.swim.SwimObservation observation) {}

    @Contract
    public void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> put) {
        // Membership KV-puts replicated to every node; only leader publishes the derived NODE_FAILED event to avoid cross-cluster fan-out + leader-bound applier failures during transitions.
        if (!isLeaderSupplier.getAsBoolean()) {return;}

        var nodeId = put.cause().key().nodeId();
        var newState = put.cause().value().state();
        var prior = lastLifecycleState.put(nodeId, newState);

        if (prior == newState) {return;}
        switch (newState) {
            case DECOMMISSIONED -> emitDecommissionEvent(nodeId.id(), prior);
            case DRAINING -> emitNodeLifecycleChangedEvent(nodeId.id(), prior, newState);
            default -> {}
        }
    }

    private void emitDecommissionEvent(String nodeId, NodeLifecycleState prior) {
        if (prior == NodeLifecycleState.DRAINING) {
            emitNodeLeftEvent(nodeId, clusterSizeSupplier.getAsInt(), "lifecycle-kv");
        } else {
            emitNodeFailedEvent(nodeId, clusterSizeSupplier.getAsInt(), "lifecycle-kv");
        }
    }

    private void emitNodeLeftEvent(String nodeId, int clusterSize, String source) {
        publisher.publish(ClusterEventValue.EventType.NODE_LEFT,
                          ClusterEventValue.Severity.INFO,
                          "Node " + nodeId + " left cluster (now " + clusterSize + " nodes)",
                          Map.of("nodeId", nodeId, "clusterSize", String.valueOf(clusterSize), "source", source));
    }

    private void emitNodeFailedEvent(String nodeId, int clusterSize, String source) {
        publisher.publish(ClusterEventValue.EventType.NODE_FAILED,
                          ClusterEventValue.Severity.CRITICAL,
                          "Node " + nodeId + " failed (cluster size " + clusterSize + ")",
                          Map.of("nodeId", nodeId, "clusterSize", String.valueOf(clusterSize), "source", source));
    }

    private void emitNodeLifecycleChangedEvent(String nodeId, NodeLifecycleState prior, NodeLifecycleState next) {
        var transition = (prior == null
                          ? "NONE"
                          : prior.name()) + "->" + next.name();
        publisher.publish(ClusterEventValue.EventType.NODE_LIFECYCLE_CHANGED,
                          ClusterEventValue.Severity.INFO,
                          "Node " + nodeId + " lifecycle: " + transition,
                          Map.of("nodeId", nodeId, "transition", transition, "requestedBy", "MembershipFsm"));
    }

    public void onLeaderChange(LeaderNotification.LeaderChange event) {
        if (!isLeaderSupplier.getAsBoolean()) {return;}
        event.leaderId().onPresent(leaderId -> publisher.publish(ClusterEventValue.EventType.LEADER_ELECTED,
                                                                 ClusterEventValue.Severity.INFO,
                                                                 "Node " + leaderId.id() + " elected as leader",
                                                                 Map.of("leaderId", leaderId.id()))).onEmpty(() -> publisher.publish(ClusterEventValue.EventType.LEADER_LOST,
                                                                                                                                     ClusterEventValue.Severity.WARNING,
                                                                                                                                     "Leadership lost, election in progress",
                                                                                                                                     Map.of()));
    }

    public void onQuorumStateChange(QuorumStateNotification event) {
        if (!event.advanceSequence(quorumSequence)) {return;}
        if (!isLeaderSupplier.getAsBoolean()) {return;}
        switch (event.state()) {
            case ESTABLISHED -> publisher.publish(ClusterEventValue.EventType.QUORUM_ESTABLISHED,
                                                  ClusterEventValue.Severity.INFO,
                                                  "Quorum established",
                                                  Map.of());
            case DISAPPEARED -> publisher.publish(ClusterEventValue.EventType.QUORUM_LOST,
                                                  ClusterEventValue.Severity.CRITICAL,
                                                  "Quorum lost",
                                                  Map.of());
        }
    }

    public void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> event) {
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

    private void handleDeploymentStarted(String trackingKey, String artifact, String nodeId) {
        deploymentStartTimes.put(trackingKey, System.currentTimeMillis());
        publisher.publish(ClusterEventValue.EventType.DEPLOYMENT_STARTED,
                          ClusterEventValue.Severity.INFO,
                          "Deploying " + artifact + " to " + nodeId,
                          Map.of("artifact", artifact, "nodeId", nodeId));
    }

    private void handleDeploymentCompleted(String trackingKey, String artifact, String nodeId) {
        var durationMs = computeAndRemoveDuration(trackingKey);
        var durationSuffix = durationMs.map(ms -> " in " + formatDuration(ms)).or("");
        var nodeReadySuffix = buildNodeReadySuffix(nodeId);
        publisher.publish(ClusterEventValue.EventType.DEPLOYMENT_COMPLETED,
                          ClusterEventValue.Severity.INFO,
                          "Deployed " + artifact + " on " + nodeId + durationSuffix + nodeReadySuffix,
                          buildCompletedMetadata(artifact, nodeId, durationMs));
    }

    private void handleDeploymentFailed(String trackingKey, String artifact, String nodeId, NodeArtifactValue value) {
        var durationMs = computeAndRemoveDuration(trackingKey);
        var durationSuffix = durationMs.map(ms -> " after " + formatDuration(ms)).or("");
        var reason = value.failureReason().or("unknown");
        publisher.publish(ClusterEventValue.EventType.DEPLOYMENT_FAILED,
                          ClusterEventValue.Severity.WARNING,
                          "Deployment of " + artifact + " failed on " + nodeId + durationSuffix + ": " + reason,
                          buildFailedMetadata(artifact, nodeId, reason, durationMs));
    }

    public void onSliceFailure(SliceFailureEvent.AllInstancesFailed event) {
        publisher.publish(ClusterEventValue.EventType.SLICE_FAILURE,
                          ClusterEventValue.Severity.CRITICAL,
                          "All instances of " + event.artifact().asString() + ":" + event.method().name() + " failed",
                          Map.of("artifact",
                                 event.artifact().asString(),
                                 "method",
                                 event.method().name(),
                                 "attemptedNodes",
                                 String.valueOf(event.attemptedNodes().size())));
    }

    public void onScaledUp(ScalingEvent.ScaledUp event) {
        publisher.publish(ClusterEventValue.EventType.SCALE_UP,
                          ClusterEventValue.Severity.INFO,
                          event.artifact().asString()
                         + " scaled up from " + event.previousInstances()
                         + " to " + event.newInstances()
                         + " instances",
                          Map.of("artifact",
                                 event.artifact().asString(),
                                 "previousInstances",
                                 String.valueOf(event.previousInstances()),
                                 "newInstances",
                                 String.valueOf(event.newInstances())));
    }

    public void onScaledDown(ScalingEvent.ScaledDown event) {
        publisher.publish(ClusterEventValue.EventType.SCALE_DOWN,
                          ClusterEventValue.Severity.INFO,
                          event.artifact().asString()
                         + " scaled down from " + event.previousInstances()
                         + " to " + event.newInstances()
                         + " instances",
                          Map.of("artifact",
                                 event.artifact().asString(),
                                 "previousInstances",
                                 String.valueOf(event.previousInstances()),
                                 "newInstances",
                                 String.valueOf(event.newInstances())));
    }

    public void onReconciliationAdjustment(ClusterDeploymentManager.ReconciliationAdjustment event) {
        var scalingUp = event.currentInstances() <event.desiredInstances();
        var direction = scalingUp
                        ? "up"
                        : "down";
        var eventType = scalingUp
                        ? ClusterEventValue.EventType.SCALE_UP
                        : ClusterEventValue.EventType.SCALE_DOWN;
        publisher.publish(eventType,
                          ClusterEventValue.Severity.INFO,
                          "Reconciliation: " + event.artifact().asString()
                         + " adjusted " + direction
                         + " from " + event.currentInstances()
                         + " to " + event.desiredInstances()
                         + " instances",
                          Map.of("artifact",
                                 event.artifact().asString(),
                                 "previousInstances",
                                 String.valueOf(event.currentInstances()),
                                 "desiredInstances",
                                 String.valueOf(event.desiredInstances()),
                                 "trigger",
                                 "reconciliation"));
    }

    public void onConnectionEstablished(NetworkServiceMessage.ConnectionEstablished event) {
        if (!isLeaderSupplier.getAsBoolean()) {return;}
        publisher.publish(ClusterEventValue.EventType.CONNECTION_ESTABLISHED,
                          ClusterEventValue.Severity.INFO,
                          "Connected to node " + event.nodeId().id(),
                          Map.of("nodeId",
                                 event.nodeId().id()));
    }

    public void onAccessDenied(OperationalEvent.AccessDenied event) {
        publisher.publish(ClusterEventValue.EventType.ACCESS_DENIED,
                          ClusterEventValue.Severity.WARNING,
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
                                 event.requiredRole()));
    }

    public void onNodeLifecycleChanged(OperationalEvent.NodeLifecycleChanged event) {
        publisher.publish(ClusterEventValue.EventType.NODE_LIFECYCLE_CHANGED,
                          ClusterEventValue.Severity.INFO,
                          "Node " + event.nodeId() + " lifecycle: " + event.transition(),
                          Map.of("nodeId",
                                 event.nodeId(),
                                 "transition",
                                 event.transition(),
                                 "requestedBy",
                                 event.requestedBy()));
    }

    public void onConfigChanged(OperationalEvent.ConfigChanged event) {
        publisher.publish(ClusterEventValue.EventType.CONFIG_CHANGED,
                          ClusterEventValue.Severity.INFO,
                          "Config " + event.action() + ": " + event.key() + " (" + event.scope() + ")",
                          Map.of("key",
                                 event.key(),
                                 "scope",
                                 event.scope(),
                                 "action",
                                 event.action(),
                                 "requestedBy",
                                 event.requestedBy()));
    }

    public void onBackupCreated(OperationalEvent.BackupCreated event) {
        publisher.publish(ClusterEventValue.EventType.BACKUP_CREATED,
                          ClusterEventValue.Severity.INFO,
                          "Backup created: " + event.commitId(),
                          Map.of("commitId", event.commitId(), "requestedBy", event.requestedBy()));
    }

    public void onBackupRestored(OperationalEvent.BackupRestored event) {
        publisher.publish(ClusterEventValue.EventType.BACKUP_RESTORED,
                          ClusterEventValue.Severity.WARNING,
                          "Backup restored: " + event.commitId(),
                          Map.of("commitId", event.commitId(), "requestedBy", event.requestedBy()));
    }

    public void onBlueprintDeployed(OperationalEvent.BlueprintDeployed event) {
        publisher.publish(ClusterEventValue.EventType.BLUEPRINT_DEPLOYED,
                          ClusterEventValue.Severity.INFO,
                          "Blueprint deployed: " + event.artifactCoords(),
                          Map.of("artifactCoords", event.artifactCoords(), "requestedBy", event.requestedBy()));
    }

    public void onBlueprintDeleted(OperationalEvent.BlueprintDeleted event) {
        publisher.publish(ClusterEventValue.EventType.BLUEPRINT_DELETED,
                          ClusterEventValue.Severity.INFO,
                          "Blueprint deleted: " + event.artifactId(),
                          Map.of("artifactId", event.artifactId(), "requestedBy", event.requestedBy()));
    }

    public void onGenerationChanged(OperationalEvent.GenerationChanged event) {
        publisher.publish(ClusterEventValue.EventType.GENERATION_CHANGED,
                          ClusterEventValue.Severity.INFO,
                          "Generation epoch advanced " + event.oldEpoch()
                         + " -> " + event.newEpoch()
                         + " (" + event.reason()
                         + ")",
                          Map.of("oldEpoch", event.oldEpoch(), "newEpoch", event.newEpoch(), "reason", event.reason()));
    }

    public void onConnectionFailed(NetworkServiceMessage.ConnectionFailed event) {
        if (!isLeaderSupplier.getAsBoolean()) {return;}
        publisher.publish(ClusterEventValue.EventType.CONNECTION_FAILED,
                          ClusterEventValue.Severity.WARNING,
                          "Connection to node " + event.nodeId().id() + " failed: " + event.cause().message(),
                          Map.of("nodeId",
                                 event.nodeId().id(),
                                 "cause",
                                 event.cause().message()));
    }

    /// Subscriber hook for `MembershipDecision` — kept as a no-op so route entries continue
    /// to compile if callers wire it; the canonical event source for membership-driven
    /// NODE_FAILED / NODE_LEFT is `onNodeLifecyclePut` per the existing RC1 audit comment.
    @Contract
    public void onMembershipDecision(@SuppressWarnings("unused") MembershipDecision decision) {}

    private Option<Long> computeAndRemoveDuration(String trackingKey) {
        return Option.option(deploymentStartTimes.remove(trackingKey)).map(startTime -> System.currentTimeMillis() - startTime);
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

    /// Null-object publisher used by legacy/test factories that don't want a real replicated
    /// log (events go nowhere; the local RingBuffer stays empty unless tests directly call
    /// `onClusterEventLogPut`). Wired only via the deprecated two-arg factory.
    private enum NullPublisher implements PublisherShape {
        INSTANCE;
        @Override
        public org.pragmatica.lang.Promise<org.pragmatica.lang.Unit> publish(ClusterEventValue.EventType type,
                                                                             ClusterEventValue.Severity severity,
                                                                             String message,
                                                                             Map<String, String> metadata) {
            return org.pragmatica.lang.Promise.success(org.pragmatica.lang.Unit.unit());
        }
    }

    /// Minimal shape the aggregator needs from the publisher — kept narrow so the null
    /// publisher above can implement it without depending on `ClusterEventLogPublisher`'s
    /// rate-cap or HLC machinery.
    private sealed interface PublisherShape permits NullPublisher, RealPublisher {
        org.pragmatica.lang.Promise<org.pragmatica.lang.Unit> publish(ClusterEventValue.EventType type,
                                                                      ClusterEventValue.Severity severity,
                                                                      String message,
                                                                      Map<String, String> metadata);
    }

    /// Thin wrapper around the production `ClusterEventLogPublisher` so the aggregator stays
    /// loose-coupled to the publisher's full type surface.
    private record RealPublisher(ClusterEventLogPublisher inner) implements PublisherShape {
        @Override
        public org.pragmatica.lang.Promise<org.pragmatica.lang.Unit> publish(ClusterEventValue.EventType type,
                                                                             ClusterEventValue.Severity severity,
                                                                             String message,
                                                                             Map<String, String> metadata) {
            return inner.publish(type, severity, message, metadata);
        }
    }
}
