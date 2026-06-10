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
import org.pragmatica.aether.api.ClusterEvent.StreamMemoryExceeded;
import org.pragmatica.aether.controller.ScalingEvent;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.slice.StreamAccess.PartitionInfo;
import org.pragmatica.aether.slice.StreamAccess.StreamEvent;
import org.pragmatica.aether.slice.StreamAccess.StreamMetadata;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.stream.FrameworkStreamConsumer;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublisher;
import org.pragmatica.aether.stream.StreamPartitionManager.Exhaustion;
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

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Aggregates cluster lifecycle events and publishes them into the
/// `system:cluster-events:1.0.0` system stream (spec §6.2).
///
/// **Architecture (stream-namespaces rebuild, B5b — replicated partition transport).** The events
/// stream is now a REAL single-partition stream managed by the `StreamPartitionManager`: created at
/// node boot via `SystemStreamFactories`, replicated at `systemReplicationFactor(N)`, and bounded by
/// the stream's production [org.pragmatica.aether.slice.RetentionPolicy] (count + byte + age caps).
/// The prior in-heap `ClusterEventStreamBuffer`/`ClusterEventStreamWiring` object-ring is gone — its
/// publisher/consumer suppliers now resolve to the `PartitionedStreamAccess`-backed framework SPIs
/// (`FrameworkStreamPublisher`/`FrameworkStreamConsumer`) wired from `SystemStreamFactories`. Producer
/// handlers build a sealed {@link ClusterEvent} and {@link #emit(ClusterEvent)} it; reads
/// (`events()`, `eventsSince(Instant)`) go through the same partition transport — a replica reads
/// locally, a non-replica read-forwards (automatic via `PartitionedStreamAccess` once the replica
/// registry is populated by the `ReplicaSetController`).
///
/// **Owner-gated emit (folds B3).** The owner observes the same consensus-derived facts on every
/// node, so an un-gated `emit` would write the same event once per node → duplicated, racy log. The
/// aggregator therefore publishes ONLY when this node is the OWNER of
/// (`system:cluster-events:1.0.0`, partition 0) under the current HRW placement — injected as a
/// `BooleanSupplier`. The single authoritative log is then replicated to all replicas. **Bootstrap
/// window:** before ownership can be determined (no quorum / topology observer reports no members /
/// the stream isn't created yet) the owner-check returns false and the event is dropped with a log
/// line — consistent with the existing publisher-not-bound bootstrap drop (spec §13.3). A
/// steady-state single-node cluster IS the owner (`systemReplicationFactor(1)=1`, `place` ranks the
/// lone node first) and DOES emit.
///
/// Publisher/consumer are provided as suppliers because in the AetherNode bootstrap the aggregator
/// is constructed before the local stream stack exists. During the construction window (before the
/// suppliers' targets are bound) any handler that fires falls back to a framework-level log line —
/// best-effort, spec §13.3. Stream lifecycle events (`STREAM_REGISTERED` / `STREAM_DELETED`) are
/// NOT emitted yet — lifecycle-event emission is deferred to RC2 (Wave-5B scope); no caller
/// produces them today. When emission lands, the self-referential `STREAM_REGISTERED` loop for
/// `system:cluster-events` itself must be gated by
/// {@link org.pragmatica.aether.slice.stream.StreamLifecycleEventPolicy#shouldEmit}, which is the
/// guard in place ahead of that work.
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
    private static final IntSupplier UNKNOWN_CLUSTER_SIZE = () -> - 1;
    /// Default owner-check used by the legacy factory overloads (tests / call sites that don't gate):
    /// always-owner, preserving prior unconditional-emit behaviour for those callers.
    private static final BooleanSupplier ALWAYS_OWNER = () -> true;

    /// Default replay-check for the legacy factories: never replaying, so emit is never suppressed on
    /// this account — preserves the prior unconditional-emit behaviour for test / non-gated callers.
    private static final BooleanSupplier NEVER_REPLAYING = () -> false;

    /// Default leader-check used by the legacy factory overloads (tests / call sites that don't gate
    /// on leadership): always-leader, preserving prior unconditional-emit behaviour through
    /// {@link #emitAsLeader} for those callers. The production factory used by `AetherNode` supplies
    /// the real leader check.
    private static final BooleanSupplier LEADER_ALWAYS = () -> true;
    /// Read window for `events()`. Must stay >= the stream's retention `maxCount` so a single fetch
    /// always covers the full retained window (no newest-event truncation) — the B5a/#239 fix. The
    /// stream's retention is now config-driven in [org.pragmatica.aether.node.AetherNode]; this is the
    /// upper bound the read path requests. Kept at the historical 10_000 default (the default
    /// retention maxCount); a larger configured maxCount would need this raised in lock-step.
    public static final int MAX_RETAINED_EVENTS = 10_000;
    private static final int FETCH_BATCH = MAX_RETAINED_EVENTS;

    private final Supplier<FrameworkStreamPublisher<ClusterEvent>> publisherSupplier;
    private final Supplier<FrameworkStreamConsumer<ClusterEvent>> consumerSupplier;
    private final BooleanSupplier ownerCheck;
    private final BooleanSupplier replayingCheck;
    private final BooleanSupplier leaderCheck;
    private final HlcClock hlcClock;
    private final NodeId selfNode;
    private final AtomicLong quorumSequence = new AtomicLong();
    private final ConcurrentHashMap<String, Long> deploymentStartTimes = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Long> nodeJoinTimes = new ConcurrentHashMap<>();

    /// Per-`(streamName, phase)` last-emit timestamp (HLC physical millis) for the budget-exhaustion
    /// rate-limiter (spec §4.5c / reconciliation #15). A saturated growing stream fires exhaustion on
    /// every append; this throttles to at most one `StreamMemoryExceeded` event per key per
    /// {@link #STREAM_MEMORY_EVENT_THROTTLE_MS}. Create-phase exhaustion is naturally infrequent but
    /// shares the same key space (keyed by phase), so it is never starved by growth-phase noise.
    private final ConcurrentHashMap<String, Long> streamMemoryEventThrottle = new ConcurrentHashMap<>();

    /// Throttle window for {@link #onStreamMemoryExceeded}: 60s per `(streamName, phase)` (spec §4.5c).
    private static final long STREAM_MEMORY_EVENT_THROTTLE_MS = 60_000L;

    private final IntSupplier clusterSizeSupplier;

    private ClusterEventAggregator(Supplier<FrameworkStreamPublisher<ClusterEvent>> publisherSupplier,
                                   Supplier<FrameworkStreamConsumer<ClusterEvent>> consumerSupplier,
                                   BooleanSupplier ownerCheck,
                                   NodeId selfNode,
                                   HlcClock hlcClock,
                                   IntSupplier clusterSizeSupplier,
                                   BooleanSupplier replayingCheck,
                                   BooleanSupplier leaderCheck) {
        this.publisherSupplier = publisherSupplier;
        this.consumerSupplier = consumerSupplier;
        this.ownerCheck = ownerCheck;
        this.selfNode = selfNode;
        this.hlcClock = hlcClock;
        this.clusterSizeSupplier = clusterSizeSupplier;
        this.replayingCheck = replayingCheck;
        this.leaderCheck = leaderCheck;
    }

    public static ClusterEventAggregator clusterEventAggregator(Supplier<FrameworkStreamPublisher<ClusterEvent>> publisherSupplier,
                                                                Supplier<FrameworkStreamConsumer<ClusterEvent>> consumerSupplier,
                                                                NodeId selfNode,
                                                                HlcClock hlcClock) {
        return new ClusterEventAggregator(publisherSupplier,
                                          consumerSupplier,
                                          ALWAYS_OWNER,
                                          selfNode,
                                          hlcClock,
                                          UNKNOWN_CLUSTER_SIZE,
                                          NEVER_REPLAYING,
                                          LEADER_ALWAYS);
    }

    public static ClusterEventAggregator clusterEventAggregator(Supplier<FrameworkStreamPublisher<ClusterEvent>> publisherSupplier,
                                                                Supplier<FrameworkStreamConsumer<ClusterEvent>> consumerSupplier,
                                                                NodeId selfNode,
                                                                HlcClock hlcClock,
                                                                IntSupplier clusterSizeSupplier) {
        return new ClusterEventAggregator(publisherSupplier,
                                          consumerSupplier,
                                          ALWAYS_OWNER,
                                          selfNode,
                                          hlcClock,
                                          clusterSizeSupplier,
                                          NEVER_REPLAYING,
                                          LEADER_ALWAYS);
    }

    /// Legacy production factory (pre-leader-gate): owner-gated emit + replay-gate, with the
    /// leader-gate defaulted to `LEADER_ALWAYS`. Retained for call sites / tests that do not exercise
    /// the {@link #emitAsLeader} departure path. New production wiring should use the
    /// `leaderCheck`-carrying overload below.
    public static ClusterEventAggregator clusterEventAggregator(Supplier<FrameworkStreamPublisher<ClusterEvent>> publisherSupplier,
                                                                Supplier<FrameworkStreamConsumer<ClusterEvent>> consumerSupplier,
                                                                BooleanSupplier ownerCheck,
                                                                NodeId selfNode,
                                                                HlcClock hlcClock,
                                                                IntSupplier clusterSizeSupplier,
                                                                BooleanSupplier replayingCheck) {
        return new ClusterEventAggregator(publisherSupplier,
                                          consumerSupplier,
                                          ownerCheck,
                                          selfNode,
                                          hlcClock,
                                          clusterSizeSupplier,
                                          replayingCheck,
                                          LEADER_ALWAYS);
    }

    /// Production factory (B5b): emit is gated by `ownerCheck` — only the owner of
    /// (`system:cluster-events:1.0.0`, partition 0) publishes; non-owners suppress. See class doc for
    /// the owner-gating rationale and bootstrap-window behaviour. `replayingCheck` (7b) additionally
    /// suppresses emit while this node is re-applying a snapshot/resync (e.g. `KVStore::isReplaying`),
    /// so replaying committed history does not re-publish historical cluster-events or re-fire
    /// outbound replication. `leaderCheck` gates {@link #emitAsLeader} — used for consensus-committed
    /// membership-DEPARTURE facts whose authoritative emitter is the cluster leader, decoupling those
    /// emits from partition-0 HRW ownership (which can name the just-removed node during the deferred
    /// reconcile window).
    public static ClusterEventAggregator clusterEventAggregator(Supplier<FrameworkStreamPublisher<ClusterEvent>> publisherSupplier,
                                                                Supplier<FrameworkStreamConsumer<ClusterEvent>> consumerSupplier,
                                                                BooleanSupplier ownerCheck,
                                                                NodeId selfNode,
                                                                HlcClock hlcClock,
                                                                IntSupplier clusterSizeSupplier,
                                                                BooleanSupplier replayingCheck,
                                                                BooleanSupplier leaderCheck) {
        return new ClusterEventAggregator(publisherSupplier,
                                          consumerSupplier,
                                          ownerCheck,
                                          selfNode,
                                          hlcClock,
                                          clusterSizeSupplier,
                                          replayingCheck,
                                          leaderCheck);
    }

    /// Read all events currently retained in the system stream's partition.
    ///
    /// **Read from the retained tail, not a fixed `0` (B5b).** The partition transport is now a
    /// bounded ring whose retention evicts the oldest events; once eviction advances the tail past
    /// offset 0, a `fetch(0, ...)` would hit `CursorExpired` (the cursor points before the oldest
    /// retained event) and yield nothing. So `events()` first resolves the partition's current
    /// `tailOffset` (the oldest still-retained offset) from metadata and fetches `FETCH_BATCH` from
    /// there. `FETCH_BATCH` >= the retention `maxCount`, so a single fetch always covers the full
    /// retained window (the B5a/#239 fix). An empty partition (`tailOffset < 0`) reads from 0 and
    /// returns nothing. On any node: a replica reads locally; a non-replica read-forwards
    /// automatically (PartitionedStreamAccess routing) once the replica registry is populated.
    public Promise<List<ClusterEvent>> events() {
        return consume(consumer -> consumer.metadata()
                                           .map(ClusterEventAggregator::retainedTailOffset)
                                           .flatMap(fromOffset -> consumer.fetch(fromOffset, FETCH_BATCH))
                                           .map(ClusterEventAggregator::extractPayloads));
    }

    /// Oldest still-retained offset for partition 0. Empty/absent partition → 0 (fetch from start).
    private static long retainedTailOffset(StreamMetadata metadata) {
        return metadata.partitions()
                       .stream()
                       .filter(p -> p.partition() == 0)
                       .mapToLong(PartitionInfo::tailOffset)
                       .filter(tail -> tail >= 0)
                       .findFirst()
                       .orElse(0L);
    }

    /// Read events whose timestamp is strictly after `since`.
    public Promise<List<ClusterEvent>> eventsSince(Instant since) {
        return events().map(events -> filterSince(events, since));
    }

    private static List<ClusterEvent> filterSince(List<ClusterEvent> events, Instant since) {
        long sinceMillis = since.toEpochMilli();

        return events.stream()
                     .filter(e -> e.at()
                                   .physicalMillis() > sinceMillis)
                     .toList();
    }

    private static List<ClusterEvent> extractPayloads(List<StreamEvent<ClusterEvent>> raw) {
        return raw.stream()
                  .map(StreamEvent::payload)
                  .toList();
    }

    private Promise<List<ClusterEvent>> consume(Function<FrameworkStreamConsumer<ClusterEvent>, Promise<List<ClusterEvent>>> fn) {
        return Option.option(consumerSupplier.get()).fold(() -> {
                                                              LOG.debug("ClusterEventAggregator consumer not yet bound — returning empty");

                                                              return Promise.success(List.of());
                                                          },
                                                          fn::apply);
    }

    /// Fire-and-forget publish into the system stream. Owner-gated (B5b): only the OWNER of
    /// (`system:cluster-events:1.0.0`, partition 0) publishes — non-owners suppress so the
    /// consensus-derived event is logged exactly once and replicated to all replicas. If ownership
    /// cannot yet be determined (bootstrap window) the owner-check returns false and the event is
    /// dropped+logged. If the publisher is not yet bound (bootstrap window) the event is likewise
    /// logged rather than dropped silently — spec §13.3.
    @Contract
    public void emit(ClusterEvent event) {
        if (replayingCheck.getAsBoolean()) {
            LOG.debug("ClusterEventAggregator: snapshot/resync replay in progress — suppressing side-effect emit of {}",
                      event);

            return;
        }

        if (!ownerCheck.getAsBoolean()) {
            LOG.debug("ClusterEventAggregator: not owner of cluster-events partition — suppressing emit of {}", event);

            return;
        }

        Option.option(publisherSupplier.get()).onPresent(publisher -> {
            Promise<?> ignored = publisher.publish(event);
        }).onEmpty(() -> LOG.info("ClusterEventAggregator publisher not yet bound — event {} dropped (bootstrap window)",
                                  event));
    }

    /// Leader-gated emit for consensus-committed membership-DEPARTURE facts (NODE_FAILED / NODE_LEFT).
    /// Identical to {@link #emit} except it consults `leaderCheck` instead of `ownerCheck`. A committed
    /// membership decision's authoritative emitter is the cluster LEADER: the leader is never the
    /// just-removed node for its own committed decision, and is unaffected by partition-0 HRW churn —
    /// whereas the owner-gate sees the PRE-removal placement (the snapshot-updating reconcile is
    /// deferred to an executor while this emit runs synchronously on the same dispatch), so when the
    /// pre-removal owner is the removed node every survivor would suppress and the event is lost. Same
    /// replay-gate and publisher-not-bound bootstrap-drop behaviour as {@link #emit}.
    @Contract
    public void emitAsLeader(ClusterEvent event) {
        if (replayingCheck.getAsBoolean()) {
            LOG.debug("ClusterEventAggregator: snapshot/resync replay in progress — suppressing side-effect emit of {}",
                      event);

            return;
        }

        if (!leaderCheck.getAsBoolean()) {
            LOG.debug("ClusterEventAggregator: not leader — suppressing emit of {}", event);

            return;
        }

        Option.option(publisherSupplier.get()).onPresent(publisher -> {
            Promise<?> ignored = publisher.publish(event);
        }).onEmpty(() -> LOG.info("ClusterEventAggregator publisher not yet bound — event {} dropped (bootstrap window)",
                                  event));
    }

    /// Owner-gate-bypassing emit for per-node facts (spec §4.5c). Identical to {@link #emit} except it
    /// does NOT consult `ownerCheck`: budget exhaustion is a per-node truth (each node has its own
    /// off-heap budget), so every node must report its own — mirroring the `SelfDrainInitiated`
    /// not-leader-gated contract. The replay gate and publisher-bound bootstrap drop still apply.
    @Contract
    public void emitLocal(ClusterEvent event) {
        if (replayingCheck.getAsBoolean()) {
            LOG.debug("ClusterEventAggregator: snapshot/resync replay in progress — suppressing local emit of {}", event);

            return;
        }

        Option.option(publisherSupplier.get()).onPresent(publisher -> {
            Promise<?> ignored = publisher.publish(event);
        }).onEmpty(() -> LOG.info("ClusterEventAggregator publisher not yet bound — local event {} dropped (bootstrap window)",
                                  event));
    }

    /// Budget-exhaustion sink entry point (spec §4.5c / reconciliation #13). Bound into the
    /// `StreamPartitionManager` by `AetherNode` (reconciliation #14). Stamps THIS node's id, builds a
    /// `StreamMemoryExceeded` event, and emits it through the un-gated {@link #emitLocal} path
    /// (per-node fact). Rate-limited per `(streamName, phase)` to one event per
    /// {@link #STREAM_MEMORY_EVENT_THROTTLE_MS} so a saturated growing stream cannot flood the log.
    @Contract
    public void onStreamMemoryExceeded(Exhaustion exhaustion) {
        if (!shouldEmitStreamMemoryEvent(exhaustion)) {
            LOG.debug("ClusterEventAggregator: suppressing throttled StreamMemoryExceeded for {}",
                      exhaustion.streamName());

            return;
        }

        emitLocal(new StreamMemoryExceeded(hlcClock.now(),
                                           Severity.WARNING,
                                           exhaustion.summary(),
                                           withNodeId(exhaustion.details())));
    }

    /// Throttle decision: emit iff no event for this `(streamName, phase)` key fired within the window.
    /// The window check + timestamp update run atomically inside `compute` (the remapping function holds
    /// the bin lock), so concurrent growth-phase appends from multiple partitions cannot both pass the
    /// gate within the same window. The admit decision is captured in a thread-confined holder set
    /// inside the remapping function — robust even when two calls land on the same physical millisecond.
    private boolean shouldEmitStreamMemoryEvent(Exhaustion exhaustion) {
        var key = exhaustion.streamName() + ":" + exhaustion.phase().name();
        var now = hlcClock.now().physicalMillis();
        var admitted = new boolean[1];

        streamMemoryEventThrottle.compute(key, (_, previous) -> advanceWindow(previous, now, admitted));

        return admitted[0];
    }

    /// Advance the throttle window for one key: when the previous emit is absent or older than the
    /// window, stamp `now` and record admission; otherwise keep the previous stamp and suppress.
    private static long advanceWindow(Long previous, long now, boolean[] admitted) {
        if (previous != null && now - previous < STREAM_MEMORY_EVENT_THROTTLE_MS) {
            admitted[0] = false;

            return previous;
        }

        admitted[0] = true;

        return now;
    }

    private Map<String, String> withNodeId(Map<String, String> details) {
        var enriched = new HashMap<>(details);

        enriched.put("nodeId", selfNode.id());

        return Map.copyOf(enriched);
    }

    /// NODE_JOINED represents transport-level visibility ("this node observed a peer connect").
    /// CTM provisions replacements that re-occupy the same node-id slot — `MembershipDecision`
    /// doesn't fire (no `coreMemberIds` delta) but `TransportObservation.PeerJoined` does (fresh
    /// QUIC handshake), so this is the surface tests asserting replacement-NODE_JOINED depend on.
    @Contract
    public void onPeerJoined(TransportObservation.PeerJoined event) {
        nodeJoinTimes.put(event.nodeId().id(),
                          System.currentTimeMillis());
        emitAsLeader(new NodeJoined(hlcClock.now(),
                                    Severity.INFO,
                                    "Node " + event.nodeId().id()
                                   + " joined cluster (now " + event.topology().size()
                                   + " nodes)",
                                    Map.of("nodeId",
                                           event.nodeId().id(),
                                           "clusterSize",
                                           String.valueOf(event.topology().size()))));
    }

    /// rc1 substrate: NODE_FAILED / NODE_LEFT are re-sourced from `MembershipDecision`, not SWIM.
    /// Retained as a no-op for router-shape compatibility.
    @Contract
    public void onSwimObservation(@SuppressWarnings("unused") org.pragmatica.swim.SwimObservation observation) {}

    @Contract
    public void onLeaderChange(LeaderNotification.LeaderChange event) {
        event.leaderId().onPresent(leaderId -> emitAsLeader(new LeaderElected(hlcClock.now(),
                                                                              Severity.INFO,
                                                                              "Node " + leaderId.id()
                                                                             + " elected as leader",
                                                                              Map.of("leaderId", leaderId.id())))).onEmpty(() -> emitAsLeader(new LeaderLost(hlcClock.now(),
                                                                                                                                                             Severity.WARNING,
                                                                                                                                                             "Leadership lost, election in progress",
                                                                                                                                                             Map.of())));
    }

    @Contract
    public void onQuorumStateChange(ClusterStateNotification event) {
        if (!event.advanceSequence(quorumSequence)) {
            return;
        }

        switch (event.state()) {
            case ACTIVE -> emitAsLeader(new QuorumEstablished(hlcClock.now(),
                                                              Severity.INFO,
                                                              "Quorum established",
                                                              Map.of()));
            case PASSIVE -> emitAsLeader(new QuorumLost(hlcClock.now(), Severity.CRITICAL, "Quorum lost", Map.of()));
        }
    }

    /// Subscriber hook for `MembershipDecision` — re-sources NODE_FAILED / NODE_LEFT that
    /// previously came from the now-deleted node-lifecycle atom (membership-v2 finale).
    /// `NodeRemoved` → NODE_FAILED (CRITICAL); `NodeDecommissioned` / `NodeDraining` → NODE_LEFT
    /// (WARNING). LEADER-gated via {@link #emitAsLeader}: a committed membership departure's
    /// authoritative emitter is the cluster leader. The owner-gate cannot be used here — for a
    /// DEPARTURE the trigger IS the membership change, and the snapshot-updating reconcile is deferred
    /// to an executor while this emit runs synchronously on the same `NodeRemoved` dispatch, so the
    /// owner-check sees the PRE-removal placement; when the pre-removal owner is the removed node every
    /// survivor suppresses and the event is lost with no replay.
    @Contract
    public void onMembershipDecision(MembershipDecision decision) {
        switch (decision) {
            case MembershipDecision.NodeRemoved removed -> emitAsLeader(new NodeFailed(hlcClock.now(),
                                                                                       Severity.CRITICAL,
                                                                                       "Node " + removed.nodeId().id() + " removed from membership",
                                                                                       Map.of("nodeId",
                                                                                              removed.nodeId().id())));
            case MembershipDecision.NodeDecommissioned decommissioned -> emitAsLeader(new NodeLeft(hlcClock.now(),
                                                                                                   Severity.WARNING,
                                                                                                   "Node " + decommissioned.nodeId().id() + " decommissioned",
                                                                                                   Map.of("nodeId",
                                                                                                          decommissioned.nodeId().id())));
            case MembershipDecision.NodeDraining draining -> emitAsLeader(new NodeLeft(hlcClock.now(),
                                                                                       Severity.WARNING,
                                                                                       "Node " + draining.nodeId().id() + " draining",
                                                                                       Map.of("nodeId",
                                                                                              draining.nodeId().id())));
            // NODE_JOINED is NOT sourced from the membership delta: a JOINING replacement is not yet
            // a counted core member, so publishCoreMembershipDelta emits no `added` edge for it. The
            // authoritative join surface is the transport `PeerJoined` handshake (onPeerJoined), now
            // LEADER-gated (the leader dials every core member, so it observes the replacement's
            // handshake). Departures differ — the delta DOES fire on the dead node leaving the counted
            // set — which is why NodeRemoved/Decommissioned/Draining above emit from here.
            case MembershipDecision.NodeJoined ignored -> {}
            case MembershipDecision.NodeJoining ignored -> {}
            case MembershipDecision.NodeFailedDrain ignored -> {}
            case MembershipDecision.NodeShuttingDown ignored -> {}
        }
    }

    @Contract
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

    @Contract
    private void handleDeploymentStarted(String trackingKey, String artifact, String nodeId) {
        deploymentStartTimes.put(trackingKey, System.currentTimeMillis());
        emit(new DeploymentStarted(hlcClock.now(),
                                   Severity.INFO,
                                   "Deploying " + artifact + " to " + nodeId,
                                   Map.of("artifact", artifact, "nodeId", nodeId)));
    }

    @Contract
    private void handleDeploymentCompleted(String trackingKey, String artifact, String nodeId) {
        var durationMs = computeAndRemoveDuration(trackingKey);
        var durationSuffix = durationMs.map(ms -> " in " + formatDuration(ms)).or("");
        var nodeReadySuffix = buildNodeReadySuffix(nodeId);

        emit(new DeploymentCompleted(hlcClock.now(),
                                     Severity.INFO,
                                     "Deployed " + artifact + " on " + nodeId + durationSuffix + nodeReadySuffix,
                                     buildCompletedMetadata(artifact, nodeId, durationMs)));
    }

    @Contract
    private void handleDeploymentFailed(String trackingKey, String artifact, String nodeId, NodeArtifactValue value) {
        var durationMs = computeAndRemoveDuration(trackingKey);
        var durationSuffix = durationMs.map(ms -> " after " + formatDuration(ms)).or("");
        var reason = value.failureReason().or("unknown");

        emit(new DeploymentFailed(hlcClock.now(),
                                  Severity.WARNING,
                                  "Deployment of " + artifact + " failed on " + nodeId + durationSuffix + ": " + reason,
                                  buildFailedMetadata(artifact, nodeId, reason, durationMs)));
    }

    @Contract
    public void onSliceFailure(SliceFailureEvent.AllInstancesFailed event) {
        emit(new SliceFailure(hlcClock.now(),
                              Severity.CRITICAL,
                              "All instances of " + event.artifact().asString()
                             + ":" + event.method().name()
                             + " failed",
                              Map.of("artifact",
                                     event.artifact().asString(),
                                     "method",
                                     event.method().name(),
                                     "attemptedNodes",
                                     String.valueOf(event.attemptedNodes().size()))));
    }

    @Contract
    public void onScaledUp(ScalingEvent.ScaledUp event) {
        emit(new ScaleUp(hlcClock.now(),
                         Severity.INFO,
                         event.artifact().asString()
                        + " scaled up from " + event.previousInstances()
                        + " to " + event.newInstances()
                        + " instances",
                         Map.of("artifact",
                                event.artifact().asString(),
                                "previousInstances",
                                String.valueOf(event.previousInstances()),
                                "newInstances",
                                String.valueOf(event.newInstances()))));
    }

    @Contract
    public void onScaledDown(ScalingEvent.ScaledDown event) {
        emit(new ScaleDown(hlcClock.now(),
                           Severity.INFO,
                           event.artifact().asString()
                          + " scaled down from " + event.previousInstances()
                          + " to " + event.newInstances()
                          + " instances",
                           Map.of("artifact",
                                  event.artifact().asString(),
                                  "previousInstances",
                                  String.valueOf(event.previousInstances()),
                                  "newInstances",
                                  String.valueOf(event.newInstances()))));
    }

    @Contract
    public void onReconciliationAdjustment(ClusterDeploymentManager.ReconciliationAdjustment event) {
        var scalingUp = event.currentInstances() < event.desiredInstances();
        var direction = scalingUp
                        ? "up"
                        : "down";
        var summary = "Reconciliation: " + event.artifact().asString()
                    + " adjusted " + direction
                    + " from " + event.currentInstances()
                    + " to " + event.desiredInstances()
                    + " instances";
        var details = Map.of("artifact",
                             event.artifact().asString(),
                             "previousInstances",
                             String.valueOf(event.currentInstances()),
                             "desiredInstances",
                             String.valueOf(event.desiredInstances()),
                             "trigger",
                             "reconciliation");
        var event2 = scalingUp
                     ? new ScaleUp(hlcClock.now(), Severity.INFO, summary, details)
                     : (ClusterEvent) new ScaleDown(hlcClock.now(), Severity.INFO, summary, details);

        emit(event2);
    }

    @Contract
    public void onConnectionEstablished(NetworkServiceMessage.ConnectionEstablished event) {
        emit(new ConnectionEstablished(hlcClock.now(),
                                       Severity.INFO,
                                       "Connected to node " + event.nodeId().id(),
                                       Map.of("nodeId",
                                              event.nodeId().id())));
    }

    @Contract
    public void onAccessDenied(OperationalEvent.AccessDenied event) {
        emit(new AccessDenied(hlcClock.now(),
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

    @Contract
    public void onNodeLifecycleChanged(OperationalEvent.NodeLifecycleChanged event) {
        emitAsLeader(new NodeLifecycleChanged(hlcClock.now(),
                                              Severity.INFO,
                                              "Node " + event.nodeId() + " lifecycle: " + event.transition(),
                                              Map.of("nodeId",
                                                     event.nodeId(),
                                                     "transition",
                                                     event.transition(),
                                                     "requestedBy",
                                                     event.requestedBy())));
    }

    @Contract
    public void onConfigChanged(OperationalEvent.ConfigChanged event) {
        emit(new ConfigChanged(hlcClock.now(),
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

    @Contract
    public void onBackupCreated(OperationalEvent.BackupCreated event) {
        emit(new BackupCreated(hlcClock.now(),
                               Severity.INFO,
                               "Backup created: " + event.commitId(),
                               Map.of("commitId", event.commitId(), "requestedBy", event.requestedBy())));
    }

    @Contract
    public void onBackupRestored(OperationalEvent.BackupRestored event) {
        emit(new BackupRestored(hlcClock.now(),
                                Severity.WARNING,
                                "Backup restored: " + event.commitId(),
                                Map.of("commitId", event.commitId(), "requestedBy", event.requestedBy())));
    }

    @Contract
    public void onBlueprintDeployed(OperationalEvent.BlueprintDeployed event) {
        emit(new BlueprintDeployed(hlcClock.now(),
                                   Severity.INFO,
                                   "Blueprint deployed: " + event.artifactCoords(),
                                   Map.of("artifactCoords", event.artifactCoords(), "requestedBy", event.requestedBy())));
    }

    @Contract
    public void onBlueprintDeleted(OperationalEvent.BlueprintDeleted event) {
        emit(new BlueprintDeleted(hlcClock.now(),
                                  Severity.INFO,
                                  "Blueprint deleted: " + event.artifactId(),
                                  Map.of("artifactId", event.artifactId(), "requestedBy", event.requestedBy())));
    }

    @Contract
    public void onGenerationChanged(OperationalEvent.GenerationChanged event) {
        emitAsLeader(new GenerationChanged(hlcClock.now(),
                                           Severity.INFO,
                                           "Generation epoch advanced " + event.oldEpoch()
                                          + " -> " + event.newEpoch()
                                          + " (" + event.reason()
                                          + ")",
                                           Map.of("oldEpoch",
                                                  event.oldEpoch(),
                                                  "newEpoch",
                                                  event.newEpoch(),
                                                  "reason",
                                                  event.reason())));
    }

    @Contract
    public void onConnectionFailed(NetworkServiceMessage.ConnectionFailed event) {
        emit(new ConnectionFailed(hlcClock.now(),
                                  Severity.WARNING,
                                  "Connection to node " + event.nodeId().id() + " failed: " + event.cause().message(),
                                  Map.of("nodeId",
                                         event.nodeId().id(),
                                         "cause",
                                         event.cause().message())));
    }

    private Option<Long> computeAndRemoveDuration(String trackingKey) {
        return Option.option(deploymentStartTimes.remove(trackingKey)).map(startTime -> System.currentTimeMillis() - startTime);
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

        return durationMs.map(ms -> withDuration(base, ms))
                         .or(base);
    }

    private static Map<String, String> withDuration(Map<String, String> base, long durationMs) {
        var metadata = new HashMap<>(base);

        metadata.put("durationMs", String.valueOf(durationMs));

        return Map.copyOf(metadata);
    }

    private static String formatDuration(long durationMs) {
        if (durationMs < 1000) {
            return durationMs + "ms";
        }

        return String.format("%.1fs", durationMs / 1000.0);
    }
}
