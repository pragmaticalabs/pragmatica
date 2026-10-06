// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.time.Instant;
import java.util.HashMap;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.api.ClusterEvent.AccessDenied;
import org.pragmatica.aether.api.ClusterEvent.AutoRollback;
import org.pragmatica.aether.api.ClusterEvent.BlueprintDeleted;
import org.pragmatica.aether.api.ClusterEvent.BlueprintDeployed;
import org.pragmatica.aether.api.ClusterEvent.ConfigChanged;
import org.pragmatica.aether.api.ClusterEvent.ConnectionEstablished;
import org.pragmatica.aether.api.ClusterEvent.ConnectionFailed;
import org.pragmatica.aether.api.ClusterEvent.StreamFailoverRefused;
import org.pragmatica.aether.api.ClusterEvent.StreamFailoverResolved;
import org.pragmatica.aether.api.ClusterEvent.StreamIsrBelowMinimum;
import org.pragmatica.aether.api.ClusterEvent.StreamIsrRestored;
import org.pragmatica.aether.api.ClusterEvent.StreamLineageRestarted;
import org.pragmatica.aether.api.ClusterEvent.StreamConfigChangeNotApplied;
import org.pragmatica.aether.api.ClusterEvent.DeparturePushIncomplete;
import org.pragmatica.aether.api.ClusterEvent.DeploymentCompleted;
import org.pragmatica.aether.api.ClusterEvent.DeploymentFailed;
import org.pragmatica.aether.api.ClusterEvent.DeploymentStarted;
import org.pragmatica.aether.api.ClusterEvent.LeaderElected;
import org.pragmatica.aether.api.ClusterEvent.LeaderLost;
import org.pragmatica.aether.api.ClusterEvent.NodeFailed;
import org.pragmatica.aether.api.ClusterEvent.NodeJoined;
import org.pragmatica.aether.api.ClusterEvent.NodeLeft;
import org.pragmatica.aether.api.ClusterEvent.NodeLifecycleChanged;
import org.pragmatica.aether.api.ClusterEvent.QuorumEstablished;
import org.pragmatica.aether.api.ClusterEvent.QuorumLost;
import org.pragmatica.aether.api.ClusterEvent.ScaleCapped;
import org.pragmatica.aether.api.ClusterEvent.ScaleDown;
import org.pragmatica.aether.api.ClusterEvent.ScaleUp;
import org.pragmatica.aether.api.ClusterEvent.Severity;
import org.pragmatica.aether.api.ClusterEvent.SliceFailure;
import org.pragmatica.aether.api.ClusterEvent.StreamMemoryExceeded;
import org.pragmatica.aether.controller.ScalingEvent;
import org.pragmatica.aether.controller.RollbackEvent;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.slice.StreamAccess.PartitionInfo;
import org.pragmatica.aether.slice.StreamAccess.StreamEvent;
import org.pragmatica.aether.slice.StreamAccess.StreamMetadata;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.slice.stream.FrameworkStreamConsumer;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublisher;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.aether.stream.StreamPartitionManager.Exhaustion;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.WarningLevel;

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
    /// {@link #EVENT_THROTTLE_MS}. Create-phase exhaustion is naturally infrequent but
    /// shares the same key space (keyed by phase), so it is never starved by growth-phase noise.
    private final ConcurrentHashMap<String, ThrottleWindow> streamMemoryEventThrottle = new ConcurrentHashMap<>();

    /// Per-`(code, subject)` window for {@link #onOperatorWarning} (#1574). The same mechanism as the
    /// stream-memory throttle, with its own key space, so a flood of one kind cannot starve the other.
    /// The key is `code:subject`. It is unambiguous because a code is kebab-case and never contains `:`,
    /// which `OperatorWarningCodeTest` enforces.
    private final ConcurrentHashMap<String, ThrottleWindow> operatorWarningThrottle = new ConcurrentHashMap<>();
    /// Throttle keys of operator warnings that were published and whose recovery code has not been raised since (#752).
    /// A recovery is published exactly when its key is here, so it never appears without the warning it closes and is
    /// never throttled away from one an operator saw. Bounded by the (code, subject) pairs the node can raise.
    private final Set<String> openRecoverable = ConcurrentHashMap.newKeySet();
    /// Warnings with a recovery that are admitted but not yet in the log, each holding the recovery that arrived meanwhile.
    /// Recoveries raised during snapshot/resync replay, by the key of the warning they close, released after it (#752).
    private final ConcurrentHashMap<String, ClusterEvent> replayHeldRecoveries = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Option<ClusterEvent>> awaitingDelivery = new ConcurrentHashMap<>();

    /// Throttle window shared by {@link #onStreamMemoryExceeded} (60s per `(streamName, phase)`, spec
    /// §4.5c) and {@link #onOperatorWarning} (60s per `(code, subject)`, #1574).
    private static final long EVENT_THROTTLE_MS = 60_000L;
    /// After an operator-warning event is LOST (redelivery gave up on it, or replay suppressed it), its key is held for
    /// this long before the next attempt (#1617 R4, v1562; since #1653 a failure redelivery retries past is not a loss).
    /// Releasing the window outright left attempts unbounded while publishing kept failing: 1,000 raises made 1,000
    /// publish attempts. A short window bounds a failing key to one attempt per this long.
    static final long OPERATOR_WARNING_RETRY_MS = 5_000L;

    private volatile Option<ClusterEvent> lastRaisedOperatorWarning = Option.none();

    /// A key with no call for this long is evicted by [#evictIdleThrottleWindows] (#1617 R3). Twice the
    /// window, so a key is never evicted while its window could still hold a call back.
    private static final long THROTTLE_IDLE_EVICTION_MS = 2 * EVENT_THROTTLE_MS;
    /// Most keys named in the log line that reports evicted held-back counts.
    private static final int EVICTION_SAMPLE_KEYS = 5;

    /// One throttle key's current window. `suppressed` counts the calls held back since `openedAt`, and
    /// `lastSeen` is the time of the latest call, admitted or not. `admitted` records whether the call that
    /// produced this state was let through, and for an admitted call `suppressedBefore` is the count held
    /// back in the window it closed.
    private record ThrottleWindow(long openedAt,
                                  long length,
                                  long lastSeen,
                                  long suppressed,
                                  boolean admitted,
                                  long suppressedBefore) {
        /// A new window opened at `now` by an admitted call, closing one that held back `suppressedBefore`.
        static ThrottleWindow throttleWindow(long now, long suppressedBefore) {
            return new ThrottleWindow(now, EVENT_THROTTLE_MS, now, 0, true, suppressedBefore);
        }

        /// The same window with one more suppressed call.
        ThrottleWindow held(long now) {
            return new ThrottleWindow(openedAt, length, now, suppressed + 1, false, 0);
        }

        boolean openAt(long now) {
            return now - openedAt < length;
        }

        /// The window an admitted call opened, shortened because its event was not published (#1617 R4). It closes
        /// [#OPERATOR_WARNING_RETRY_MS] after it opened, so the next attempt waits that long instead of a full window,
        /// and a key whose publishes keep failing makes at most one attempt per retry window. It carries every
        /// occurrence no published event represents: the count the failed event was meant to report, those held back
        /// while it was in flight, and the failed occurrence itself.
        ThrottleWindow shortenedForRetry() {
            return new ThrottleWindow(openedAt,
                                      OPERATOR_WARNING_RETRY_MS,
                                      lastSeen,
                                      suppressed + suppressedBefore + 1,
                                      false,
                                      0);
        }

        boolean idleAt(long now) {
            return now - lastSeen >= THROTTLE_IDLE_EVICTION_MS;
        }
    }

    private final IntSupplier clusterSizeSupplier;

    /// Default for the legacy factories: ownership is always resolvable, so they never take the
    /// ownerless-drop branch. Those factories pass `ALWAYS_OWNER` and therefore never suppress at all.
    private static final BooleanSupplier OWNERSHIP_ALWAYS_RESOLVABLE = () -> true;

    /// Distinguishes the two DIFFERENT falses `ownerCheck` returns (#957).
    ///
    /// `ownerCheck` is false both when ANOTHER node owns partition 0 — the normal steady state on N-1
    /// nodes, where the event IS published, just not by this node — and when ownership cannot be
    /// determined at all, where NO node publishes and the event is lost. A single boolean cannot tell
    /// them apart, so logging the first at WARN would emit a line per suppressed event per non-owner
    /// per tick and report correct operation as a fault.
    private final BooleanSupplier ownershipResolvable;
    /// Count of events dropped because ownership was unresolvable — the size of the audit-log hole.
    /// Read by [#ownerlessDrops].
    private final AtomicLong ownerlessDrops = new AtomicLong();
    /// #1640: events whose publish did not land wait here and are retried.
    private final ClusterEventRedelivery redelivery;
    private volatile Option<Function<ClusterEvent, Promise<Unit>>> publishHook = Option.none();
    /// #1653 round 2: stamps each event with `details.eventId` so a redelivered copy is recognised on read.
    private final ClusterEventIdentity identity = ClusterEventIdentity.clusterEventIdentity();
    private volatile int lastReadDuplicates;
    /// #1653: cluster-events ownership changes applied on this node; the event feed re-anchors when it moves.
    private final AtomicLong ownershipChanges = new AtomicLong();
    private volatile long lastEventsFrom = -1;

    /// Typed so [ClusterEventRedelivery#failuresByCause] counts it by name (#1653).
    private enum PublishError implements Cause {
        PUBLISHER_NOT_BOUND("cluster-events publisher not yet bound");
        private final String message;
        PublishError(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    private ClusterEventAggregator(Supplier<FrameworkStreamPublisher<ClusterEvent>> publisherSupplier,
                                   Supplier<FrameworkStreamConsumer<ClusterEvent>> consumerSupplier,
                                   BooleanSupplier ownerCheck,
                                   NodeId selfNode,
                                   HlcClock hlcClock,
                                   IntSupplier clusterSizeSupplier,
                                   BooleanSupplier replayingCheck,
                                   BooleanSupplier leaderCheck,
                                   BooleanSupplier ownershipResolvable) {
        this.publisherSupplier = publisherSupplier;
        this.consumerSupplier = consumerSupplier;
        this.ownerCheck = ownerCheck;
        this.selfNode = selfNode;
        this.hlcClock = hlcClock;
        this.clusterSizeSupplier = clusterSizeSupplier;
        this.replayingCheck = replayingCheck;
        this.leaderCheck = leaderCheck;
        this.ownershipResolvable = ownershipResolvable;
        this.redelivery = ClusterEventRedelivery.clusterEventRedelivery(this::publishOnce,
                                                                        () -> hlcClock.now()
                                                                                      .physicalMillis());
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
                                          LEADER_ALWAYS,
                                          OWNERSHIP_ALWAYS_RESOLVABLE);
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
                                          LEADER_ALWAYS,
                                          OWNERSHIP_ALWAYS_RESOLVABLE);
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
                                          LEADER_ALWAYS,
                                          OWNERSHIP_ALWAYS_RESOLVABLE);
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
                                          leaderCheck,
                                          OWNERSHIP_ALWAYS_RESOLVABLE);
    }

    /// Production factory (#957) — as above, plus `ownershipResolvable`, which reports whether this node
    /// can determine partition-0 ownership AT ALL. See [#ownershipResolvable] for why the extra supplier
    /// is needed rather than reusing `ownerCheck`.
    public static ClusterEventAggregator clusterEventAggregator(Supplier<FrameworkStreamPublisher<ClusterEvent>> publisherSupplier,
                                                                Supplier<FrameworkStreamConsumer<ClusterEvent>> consumerSupplier,
                                                                BooleanSupplier ownerCheck,
                                                                NodeId selfNode,
                                                                HlcClock hlcClock,
                                                                IntSupplier clusterSizeSupplier,
                                                                BooleanSupplier replayingCheck,
                                                                BooleanSupplier leaderCheck,
                                                                BooleanSupplier ownershipResolvable) {
        return new ClusterEventAggregator(publisherSupplier,
                                          consumerSupplier,
                                          ownerCheck,
                                          selfNode,
                                          hlcClock,
                                          clusterSizeSupplier,
                                          replayingCheck,
                                          leaderCheck,
                                          ownershipResolvable);
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
                                           .map(this::extractPayloads));
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

    /// One event as the log holds it, with its offset.
    public record LandedEvent(long offset, ClusterEvent event) {}

    /// A page of the log from an offset: the events at `[from, nextOffset)` in log order, as stored (not
    /// de-duplicated or sorted: the event feed de-duplicates against what it has already sent and sends in log order).
    /// `tailOffset` is the oldest retained offset. `ownershipChanges` counts the cluster-events ownership changes this
    /// node has applied, read before the fetch: a reader that sees it move knows the log may now be a new owner's,
    /// whose offsets can reuse ones it already read (#1653).
    public record EventPage(List<LandedEvent> events, long nextOffset, long tailOffset, long ownershipChanges) {}

    /// #1653: the event feed's incremental read. Reads from `fromOffset` (clamped up to the oldest retained offset)
    /// and reports where the next read starts. A redelivered event is APPENDED, so it lands at an offset beyond any
    /// earlier read of the same owner's log, whatever its `at`; each read costs what is new since the last one.
    public Promise<EventPage> eventsFrom(long fromOffset) {
        var changes = ownershipChanges.get();

        lastEventsFrom = fromOffset;

        return Option.option(consumerSupplier.get())
                     .map(consumer -> pageFrom(consumer, fromOffset, changes))
                     .or(() -> Promise.success(new EventPage(List.of(),
                                                             fromOffset,
                                                             0L,
                                                             changes)));
    }

    /// The offset the last [#eventsFrom] was asked for (observability for the #1653 feed-wiring pin).
    long lastEventsFrom() {
        return lastEventsFrom;
    }

    private static Promise<EventPage> pageFrom(FrameworkStreamConsumer<ClusterEvent> consumer,
                                               long fromOffset,
                                               long changes) {
        return consumer.metadata()
                       .map(ClusterEventAggregator::retainedTailOffset)
                       .flatMap(tail -> consumer.fetch(Math.max(fromOffset, tail),
                                                       FETCH_BATCH)
                                                .map(raw -> page(raw, fromOffset, tail, changes)));
    }

    private static EventPage page(List<StreamEvent<ClusterEvent>> raw, long fromOffset, long tail, long changes) {
        var nextOffset = raw.stream().mapToLong(StreamEvent::offset).max().orElse(fromOffset - 1) + 1;

        return new EventPage(raw.stream().map(event -> new LandedEvent(event.offset(), event.payload())).toList(),
                             nextOffset,
                             tail,
                             changes);
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

    /// #1640: an event can be in the log twice. A publish whose outcome was unknown may have landed, and its
    /// redelivery sends the same event again. Every copy carries the same `details.eventId` (see
    /// [ClusterEventIdentity]), so duplicates are removed here, keeping the first occurrence. Events without an id
    /// (from a node that predates it, or an `ExtendedEvent` that is not a record) fall back to `at`.
    ///
    /// #1653 round 2: the result is sorted by `at`, stably. A redelivered event lands in the log after events
    /// produced later, and readers of this method (the REST list, the event feed, alert history, traces) expect
    /// a timeline. Ties keep log order.
    ///
    /// Every reader of the aggregator goes through here. The raw stream read endpoint
    /// (`GET /api/v1/streams/system/cluster-events/1.0.0/read`) does not: it returns the log as stored, so it can
    /// show a redelivered event twice and out of `at` order. `details.eventId` is there to de-duplicate by.
    private List<ClusterEvent> extractPayloads(List<StreamEvent<ClusterEvent>> raw) {
        var seen = new HashSet<String>();
        var unique = raw.stream()
                        .map(StreamEvent::payload)
                        .filter(event -> seen.add(ClusterEventIdentity.key(event)))
                        .sorted(Comparator.comparing(ClusterEvent::at))
                        .toList();

        lastReadDuplicates = raw.size() - unique.size();

        return unique;
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
            suppressUnowned(event);

            return;
        }

        publishSafely(event);
    }

    /// Split the owner-gate's two falses (#957) so the loud one is actually loud.
    ///
    /// **Another node owns** — the steady state on every non-owner, once per emit per tick. The event
    /// reaches the log via the owner, nothing is lost, DEBUG.
    ///
    /// **Ownership unresolvable** — bootstrap window, quorum loss, partition 0 not yet materialized.
    /// NO node passes the gate, so the event is dropped by all of them and **never queued or retried**.
    /// That is a permanent hole in the audit log, and it is the same shape as the #926 defect where the
    /// event announcing there is no leader was itself leader-gated. WARN plus a counter, so the hole is
    /// measurable rather than inferred: a reader asking "did we lose alerts during that outage?" gets a
    /// number instead of an argument.
    ///
    /// The operator surface does NOT go dark in this window — `AlertManager` serves "what is firing now"
    /// from its locally-derived view, which needs no ownership. Only the durable history gaps.
    private void suppressUnowned(ClusterEvent event) {
        if (ownershipResolvable.getAsBoolean()) {
            LOG.debug("ClusterEventAggregator: not owner of cluster-events partition — suppressing emit of {}", event);

            return;
        }

        LOG.warn("ClusterEventAggregator: cluster-events ownership UNRESOLVABLE — {} dropped, not queued;"
                + " the audit log will gap here. Ownerless drops on this node since start: {}",
                 event.type(),
                 ownerlessDrops.incrementAndGet());
    }

    /// Events lost on this node because ownership could not be determined. Non-decreasing; the audit
    /// hole's size. Zero is the claim "no event was lost this way", and it is checkable.
    public long ownerlessDrops() {
        return ownerlessDrops.get();
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

        publishSafely(event);
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

        publishSafely(event);
    }

    /// Publish `event` to the cluster-events stream, ISOLATING any synchronous throw from the publisher
    /// so it can never propagate to the caller. {@link #onConfirmedDeparture} runs on the MembershipFsm
    /// DEAD-edge chokepoint (`enteredDead`) BEFORE the FSM emits its REMOVED membership delta; a publish
    /// that threw there (e.g. the leader's cluster-events partition not yet materialized mid-churn) would
    /// abort the death edge and starve membership recovery.
    ///
    /// #926 made the asynchronous failure visible. #1640 stops dropping it: a publish that does not land
    /// (most importantly `PublishOutcomeUnknown` while the partition's owner is dead) is handed to
    /// [ClusterEventRedelivery], which retries the same event until it lands or its horizon passes, and counts
    /// what it gives up on. Still never propagated to the caller.
    @Contract
    private void publishSafely(ClusterEvent event) {
        redelivery.deliver(stampedOrAsIs(event));
    }

    /// #1653: stamping copies `details` and so runs inside the same throw isolation as the publish. An event whose
    /// details cannot be copied (a null value) goes out without an id and is de-duplicated by `at`.
    private ClusterEvent stampedOrAsIs(ClusterEvent event) {
        return Result.lift(Causes::fromThrowable,
                           () -> identity.stamped(event))
                     .onFailure(cause -> LOG.warn("ClusterEventAggregator: {} published without an eventId: {}",
                                                  event.type(),
                                                  cause.message()))
                     .or(event);
    }

    /// One publish attempt with its outcome: fails when the publisher is not yet bound, when the publish
    /// throws, or when its promise fails. Each failure is logged once here; whether it is retried is
    /// [ClusterEventRedelivery]'s decision.
    private Promise<Unit> publishOnce(ClusterEvent event) {
        return publishHook.map(hook -> hook.apply(event))
                          .or(() -> publishViaPublisher(event));
    }

    /// Test seam: replaces the publish attempt, so a test can script outcomes the sealed publisher cannot produce, such
    /// as [PublishOutcomeUnknown] (#752). Never set in production.
    void interceptPublish(Function<ClusterEvent, Promise<Unit>> hook) {
        publishHook = Option.some(hook);
    }

    private Promise<Unit> publishViaPublisher(ClusterEvent event) {
        return Option.option(publisherSupplier.get())
                     .map(publisher -> publishedBy(publisher, event))
                     .or(() -> unboundPublisher(event));
    }

    private static Promise<Unit> publishedBy(FrameworkStreamPublisher<ClusterEvent> publisher, ClusterEvent event) {
        return Result.lift(Causes::fromThrowable,
                           () -> publisher.publish(event))
                     .onFailure(cause -> LOG.warn("ClusterEventAggregator: publish of {} threw: {}",
                                                  event.type(),
                                                  cause.message()))
                     .async()
                     .flatMap(promise -> promise.onFailure(cause -> LOG.warn("ClusterEventAggregator: publish of {} at {} failed: {}",
                                                                             event.type(),
                                                                             event.at(),
                                                                             cause.message())));
    }

    private static Promise<Unit> unboundPublisher(ClusterEvent event) {
        LOG.info("ClusterEventAggregator publisher not yet bound — event {} held for redelivery (bootstrap window)",
                 event.type());

        return PublishError.PUBLISHER_NOT_BOUND.promise();
    }

    /// #1640: re-sends the cluster events whose publish has not landed yet and are due. `AetherNode` calls it
    /// once a second.
    public Unit redeliverDue() {
        releaseReplayHeldRecoveries();
        redelivery.redeliver(false);

        return Unit.unit();
    }

    /// #1640: a new owner of the cluster-events partition means publishes can land again, so every waiting
    /// event is re-sent at once instead of on its backoff. #1653: the change is also counted, so the event feed
    /// re-reads the new owner's log instead of trusting offsets it read from the old one.
    @Contract
    public void onStreamPartitionOwnershipPut(ValuePut<StreamPartitionOwnershipKey, StreamPartitionOwnershipValue> put) {
        if (isClusterEventsPartition(put.cause().key())) {
            ownershipChanges.incrementAndGet();
            redelivery.redeliver(true);
        }
    }

    private static boolean isClusterEventsPartition(StreamPartitionOwnershipKey key) {
        return key.partition() == 0 && SystemStreams.CLUSTER_EVENTS.asString().equals(key.stream());
    }

    /// Observability for #1640: events waiting for redelivery on this node.
    public int redeliveryWaiting() {
        return redelivery.waiting();
    }

    /// Observability for #1640: events this node gave up on, by reason (overflow, expired, permanent).
    public Map<String, Long> redeliveryDropped() {
        return Map.of("overflow",
                      redelivery.dropped(ClusterEventRedelivery.DropReason.OVERFLOW),
                      "expired",
                      redelivery.dropped(ClusterEventRedelivery.DropReason.EXPIRED),
                      "permanent",
                      redelivery.dropped(ClusterEventRedelivery.DropReason.PERMANENT));
    }

    /// Observability for #1640: publishes that landed, retries attempted, and publishes whose outcome was unknown.
    public Map<String, Long> redeliveryCounters() {
        return Map.of("accepted",
                      redelivery.accepted(),
                      "held",
                      redelivery.held(),
                      "inFlight",
                      redelivery.inFlight(),
                      "delivered",
                      redelivery.delivered(),
                      "retried",
                      redelivery.retried(),
                      "outcomeUnknown",
                      redelivery.outcomeUnknown());
    }

    /// Observability for #1640: failed publish attempts on this node by cause type.
    public Map<String, Long> redeliveryFailuresByCause() {
        return redelivery.failuresByCause();
    }

    /// Observability for #1640: duplicate events (same `at`) that the most recent read removed.
    public int lastReadDuplicates() {
        return lastReadDuplicates;
    }

    /// Whether this node currently owns cluster-events partition 0 (observability; the owner gate reads the same).
    public boolean isClusterEventsOwner() {
        return ownerCheck.getAsBoolean();
    }

    /// Budget-exhaustion sink entry point (spec §4.5c / reconciliation #13). Bound into the
    /// `StreamPartitionManager` by `AetherNode` (reconciliation #14). Stamps THIS node's id, builds a
    /// `StreamMemoryExceeded` event, and emits it through the un-gated {@link #emitLocal} path
    /// (per-node fact). Rate-limited per `(streamName, phase)` to one event per
    /// {@link #EVENT_THROTTLE_MS} so a saturated growing stream cannot flood the log.
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
    /// The stream-memory throttle consumes its window on admission, whether or not the event lands; only
    /// operator warnings shorten a window to a retry window on a failed publish (#1617 R4 is scoped to them).
    private boolean shouldEmitStreamMemoryEvent(Exhaustion exhaustion) {
        return admit(streamMemoryEventThrottle,
                     exhaustion.streamName() + ":" + exhaustion.phase().name()).admitted();
    }

    /// Operator-warning sink entry point (#1574). Bound into lower modules by `AetherNode` as their
    /// `OperatorWarningSink`, and reached only through `OperatorWarnings.raise`, which has already
    /// logged the warning. So this method only decides whether to emit. It stamps THIS node's id and
    /// emits through the ungated {@link #emitLocal} path, because a warning is a per-node fact. It is
    /// throttled per `(code, subject)` to one event per {@link #EVENT_THROTTLE_MS}, and the next
    /// admitted event carries the number held back as `suppressedSince`.
    ///
    /// #1617 R4, with #1653: a full window is consumed by an event that lands or that redelivery is still holding. Only an
    /// event that is LOST (replay in progress suppressed it, or redelivery finally dropped it: permanent failure, expiry
    /// past its horizon, overflow) shortens the window to [#OPERATOR_WARNING_RETRY_MS], so the next occurrence after it
    /// is admitted and reports what this one could not, while a key whose events keep being lost is attempted at most
    /// once per retry window.
    @Contract
    public void onOperatorWarning(OperatorWarning warning) {
        var key = throttleKey(warning.code(), warning.subject());

        warning.code()
               .recoveryOf()
               .onPresent(closes -> onRecovery(warning, closes))
               .onEmpty(() -> onCondition(warning, key));
    }

    private void onCondition(OperatorWarning warning, String key) {
        // A recurrence makes a repair still held from replay stale: the tick must not publish it behind the new condition.
        replayHeldRecoveries.remove(key);
        var window = admit(operatorWarningThrottle, key);

        if (!window.admitted()) {
            LOG.debug("ClusterEventAggregator: suppressing throttled OperatorWarning {} for {}",
                      warning.code().code(),
                      warning.subject());

            return;
        }

        var event = warningEvent(warning, window.suppressedBefore());

        lastRaisedOperatorWarning = Option.some(event);
        if (replayingCheck.getAsBoolean()) {
            LOG.debug("ClusterEventAggregator: snapshot/resync replay in progress — suppressing local emit of {}", event);
            shortenWindow(key, window);

            return;
        }
        // #1653: through redelivery, like every other event, so a publish to a dying owner is held and re-sent rather
        // than lost. The window is shortened only if redelivery finally gives up on it: a failure it retries past is
        // not a gap, and shortening on it would admit a second event while the first is still being delivered.
        if (warning.code().hasRecovery()) {
            deliverRecoverable(event, key, window);
        } else {
            redelivery.deliver(stampedOrAsIs(event), () -> shortenWindow(key, window));
        }
    }

    /// #752: a warning that has a recovery is "shown" only once it is in the log. Until then a recovery that arrives is
    /// held in `awaitingDelivery` and released when the warning lands, or dropped with it if redelivery gives up, so a
    /// recovery never reaches the log without the warning it closes.
    private void deliverRecoverable(ClusterEvent event, String key, ThrottleWindow window) {
        awaitingDelivery.put(key, Option.none());
        redelivery.deliver(stampedOrAsIs(event), outcome -> warningLost(key, window, outcome), () -> warningLanded(key));
    }

    /// A warning redelivery gave up on. If an attempt's outcome was unknown it may be in the log, so it is treated as
    /// landed: a recovery for it is less harmful than an alarm left open for good, and the condition really is repaired.
    /// So a repair may appear without its warning only when the warning's delivery outcome was unknown; this avoids an
    /// alarm left open forever.
    /// If it was definitely not delivered, a recovery held for it is dropped, except that an earlier published warning
    /// for the subject that is still open keeps its recovery.
    private void warningLost(String key, ThrottleWindow window, ClusterEventRedelivery.GiveUpOutcome outcome) {
        if (outcome == ClusterEventRedelivery.GiveUpOutcome.POSSIBLY_DELIVERED) {
            warningLanded(key);
        } else {
            Option.option(awaitingDelivery.remove(key))
                  .flatMap(held -> held)
                  .filter(_ -> openRecoverable.contains(key))
                  .onPresent(recovery -> releaseHeldRecovery(key, recovery));
        }

        shortenWindow(key, window);
    }

    /// The mark is opened before the held recovery is looked up, so a recovery arriving between the two finds it open.
    private void warningLanded(String key) {
        openRecoverable.add(key);
        Option.option(awaitingDelivery.remove(key))
              .flatMap(held -> held)
              .onPresent(recovery -> releaseHeldRecovery(key, recovery));
    }

    private void releaseHeldRecovery(String key, ClusterEvent recovery) {
        openRecoverable.remove(key);
        redelivery.deliver(stampedOrAsIs(recovery));
    }

    /// A recovery is published iff the warning it closes was published for the same subject and is still open, and it
    /// closes it. It has no window of its own: one published warning allows one recovery. Publishing it also ends the
    /// warning's throttle window, so a condition that recurs right after its recovery is shown again rather than
    /// throttled behind a "repaired" that is no longer true (#752).
    private void onRecovery(OperatorWarning warning, OperatorWarningCode closes) {
        var closedKey = throttleKey(closes, warning.subject());
        var event = warningEvent(warning, 0);
        var held = Option.option(awaitingDelivery.computeIfPresent(closedKey, (_, _) -> Option.some(event)));

        if (held.isPresent()) {
            operatorWarningThrottle.remove(closedKey);
            LOG.debug("ClusterEventAggregator: holding OperatorWarning {} for {} until the warning it closes is delivered",
                      warning.code().code(),
                      warning.subject());

            return;
        }

        if (replayingCheck.getAsBoolean()) {
            holdDuringReplay(closedKey, event);

            return;
        }

        replayHeldRecoveries.remove(closedKey);
        if (!openRecoverable.remove(closedKey)) {
            LOG.debug("ClusterEventAggregator: refusing OperatorWarning {} for {}, no published warning is open",
                      warning.code().code(),
                      warning.subject());

            return;
        }

        publishRecovery(closedKey, event);
    }

    /// A shown warning must get its recovery, but replay suppresses local emits. So during replay the recovery is held
    /// and the open mark is left alone; [#releaseReplayHeldRecoveries] publishes it on the first tick after replay.
    private void holdDuringReplay(String closedKey, ClusterEvent recovery) {
        if (openRecoverable.contains(closedKey)) {
            replayHeldRecoveries.put(closedKey, recovery);
            LOG.debug("ClusterEventAggregator: snapshot/resync replay in progress — holding {} until it ends", recovery);
        }
    }

    private void releaseReplayHeldRecoveries() {
        if (replayingCheck.getAsBoolean()) {
            return;
        }

        replayHeldRecoveries.keySet().forEach(this::releaseReplayHeld);
    }

    private void releaseReplayHeld(String closedKey) {
        Option.option(replayHeldRecoveries.remove(closedKey))
              .filter(_ -> openRecoverable.remove(closedKey))
              .onPresent(recovery -> publishRecovery(closedKey, recovery));
    }

    private void publishRecovery(String closedKey, ClusterEvent event) {
        operatorWarningThrottle.remove(closedKey);
        lastRaisedOperatorWarning = Option.some(event);
        redelivery.deliver(stampedOrAsIs(event));
    }

    private ClusterEvent.OperatorWarning warningEvent(OperatorWarning warning, long suppressedSince) {
        return new ClusterEvent.OperatorWarning(hlcClock.now(),
                                                severityOf(warning.code().level()),
                                                warning.message(),
                                                operatorWarningDetails(warning, suppressedSince));
    }

    /// Observability: the operator-warning event this node most recently admitted for publication,
    /// whether or not the publish then landed. On a node that cannot reach the event log (an isolated
    /// worker is the case that matters) this is the only in-process evidence that the warning reached
    /// the aggregator rather than stopping at the log line.
    public Option<ClusterEvent> lastRaisedOperatorWarning() {
        return lastRaisedOperatorWarning;
    }

    /// Operator-warning throttle keys currently held (observability for #1617 R3).
    public int operatorWarningThrottleKeys() {
        return operatorWarningThrottle.size();
    }

    /// Evicts throttle keys idle for [#THROTTLE_IDLE_EVICTION_MS] from both throttles, so a key space as
    /// open as peers or `stream[partition]` subjects cannot grow without bound (#1617 R3). `AetherNode`
    /// schedules it once per window. An evicted operator-warning key that still held calls back would take
    /// that count with it, so the evicted counts are reported in one aggregate log line.
    public Unit evictIdleThrottleWindows() {
        var now = hlcClock.now().physicalMillis();

        evictIdle(streamMemoryEventThrottle, now);

        return reportEvictedHeldBack(evictIdle(operatorWarningThrottle, now));
    }

    private static List<Map.Entry<String, ThrottleWindow>> evictIdle(ConcurrentHashMap<String, ThrottleWindow> throttle,
                                                                     long now) {
        var idle = throttle.entrySet()
                           .stream()
                           .filter(entry -> entry.getValue()
                                                 .idleAt(now))
                           .map(entry -> Map.entry(entry.getKey(),
                                                   entry.getValue()))
                           .toList();
        // remove(key, value) is conditional, so a key that saw a call after the scan is kept.
        return idle.stream()
                   .filter(entry -> throttle.remove(entry.getKey(),
                                                    entry.getValue()))
                   .toList();
    }

    private static Unit reportEvictedHeldBack(List<Map.Entry<String, ThrottleWindow>> evicted) {
        var heldBack = evicted.stream().filter(entry -> entry.getValue()
                                                             .suppressed() > 0).toList();
        var total = heldBack.stream().mapToLong(entry -> entry.getValue()
                                                              .suppressed()).sum();

        if (total > 0) {
            LOG.warn("ClusterEventAggregator: {} held-back operator warning(s) across {} idle key(s) were never emitted "
                    + "as events; their log lines were written. Keys include: {}",
                     total,
                     heldBack.size(),
                     heldBack.stream().limit(EVICTION_SAMPLE_KEYS).map(Map.Entry::getKey).toList());
        }

        return Unit.unit();
    }

    /// Shortens `window` to a retry window if it is still the key's current window. A later window is left alone.
    private Unit shortenWindow(String key, ThrottleWindow window) {
        operatorWarningThrottle.computeIfPresent(key, (_, current) -> shortenedIfCurrent(current, window));

        return Unit.unit();
    }

    private static ThrottleWindow shortenedIfCurrent(ThrottleWindow current, ThrottleWindow admitted) {
        return current.openedAt() == admitted.openedAt()
               ? current.shortenedForRetry()
               : current;
    }

    private static String throttleKey(OperatorWarningCode code, String subject) {
        return code.code() + ":" + subject;
    }

    private static Severity severityOf(WarningLevel level) {
        return switch (level) {
            case INFO -> Severity.INFO;
            case WARNING -> Severity.WARNING;
            case CRITICAL -> Severity.CRITICAL;
        };
    }

    private Map<String, String> operatorWarningDetails(OperatorWarning warning, long suppressedSince) {
        return withNodeId(Map.of("code",
                                 warning.code().code(),
                                 "subsystem",
                                 warning.code().subsystem(),
                                 "subject",
                                 warning.subject(),
                                 "suppressedSince",
                                 Long.toString(suppressedSince)));
    }

    /// Advance `key`'s window in `throttle` and report the outcome. The window check and update run
    /// atomically inside `compute`, because the remapping function holds the bin lock. So concurrent
    /// callers on the same key cannot both pass the gate within one window, even when they land on the
    /// same physical millisecond.
    private ThrottleWindow admit(ConcurrentHashMap<String, ThrottleWindow> throttle, String key) {
        var now = hlcClock.now().physicalMillis();

        return throttle.compute(key, (_, previous) -> advanceWindow(previous, now));
    }

    /// Advance the throttle window for one key. When the previous window is absent or older than
    /// {@link #EVENT_THROTTLE_MS}, open a new one at `now`, admit the call, and carry the closed window's
    /// suppressed count. Otherwise keep the window open and count one more suppressed call.
    // RET-06: `previous` is the nullable prior value supplied by JDK Map.compute (absent key → null) —
    // a framework boundary, not a business optional.
    @SuppressWarnings("JBCT-RET-06")
    private static ThrottleWindow advanceWindow(ThrottleWindow previous, long now) {
        if (previous == null) {
            return ThrottleWindow.throttleWindow(now, 0);
        }

        if (previous.openAt(now)) {
            return previous.held(now);
        }

        return ThrottleWindow.throttleWindow(now, previous.suppressed());
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

    /// LEADER_ELECTED stays leader-gated — the newly elected leader is by definition the authoritative
    /// emitter of its own election, and the gate holds for it.
    ///
    /// LEADER_LOST does NOT (#926). It was previously emitted through {@link #emitAsLeader}, which made
    /// **the event announcing that there is no leader conditional on being the leader** — the branch is
    /// reached precisely when `leaderId()` is empty, so no node passes the gate and the signal was
    /// suppressed everywhere it mattered. It is emitted through the un-gated {@link #emitLocal} path
    /// instead: this is the "no leader, degraded observability" signal, and it is worthless if it can
    /// only be sent by a leader. Same at-least-once-per-observer contract as
    /// {@link #onConfirmedDeparture}, with the same bounded duplication and the same `observedBy` key.
    @Contract
    public void onLeaderChange(LeaderNotification.LeaderChange event) {
        event.leaderId()
             .onPresent(leaderId -> emitAsLeader(new LeaderElected(hlcClock.now(),
                                                                   Severity.INFO,
                                                                   "Node " + leaderId.id() + " elected as leader",
                                                                   Map.of("leaderId",
                                                                          leaderId.id()))))
             .onEmpty(() -> {
                          LOG.warn("Leadership lost on {}, election in progress — cluster observability degraded",
                                   selfNode.id());
                          emitLocal(new LeaderLost(hlcClock.now(),
                                                   Severity.WARNING,
                                                   "Leadership lost, election in progress",
                                                   Map.of("observedBy",
                                                          selfNode.id())));
                      });
    }

    /// Quorum transitions, UN-gated via {@link #emitLocal} (#926) — previously both leader-gated.
    ///
    /// QUORUM_LOST is the most severe event this class emits (CRITICAL) and was the least emittable.
    /// A cluster that has gone PASSIVE cannot commit through consensus and therefore cannot sustain a
    /// leader lease, so `leaderCheck` is false on every node exactly when quorum is lost — the same
    /// self-defeating shape as {@link #onLeaderChange}'s LEADER_LOST branch and
    /// {@link #onConfirmedDeparture}.
    ///
    /// QUORUM_ESTABLISHED is un-gated for a second, independent reason: quorum forms BEFORE a leader is
    /// elected, so the gate drops the recovery notice at the one moment it is guaranteed to be false.
    /// Un-gating the loss while leaving the recovery gated would be worse than fixing neither — an
    /// operator would see the cluster enter "quorum lost" and never see it leave, a permanently red
    /// signal that trains its own audience to ignore it. **A failure signal is only usable if its
    /// matching recovery signal is at least as reachable.**
    ///
    /// Quorum state is genuinely a per-node observation, not a cluster fact: a node partitioned away
    /// from the majority sees PASSIVE while the majority side sees ACTIVE, and each node is the only
    /// authority on its own consensus participation. `emitLocal` is therefore the semantically correct
    /// path here, matching the `SelfDrainInitiated` per-node contract rather than merely a workaround.
    ///
    /// Per-node duplicate suppression is unchanged and still applies: `advanceSequence` drops
    /// duplicate/out-of-order notifications on this node before any emit. Cross-node duplication is
    /// bounded by cluster size, per the {@link #onConfirmedDeparture} contract, and `observedBy` names
    /// the emitter.
    @Contract
    public void onQuorumStateChange(ClusterStateNotification event) {
        if (!event.advanceSequence(quorumSequence)) {
            return;
        }

        switch (event.state()) {
            case ACTIVE -> {
                // The recovery line is NOT decoration. This class nominates the local log as the
                // surface that survives a leaderless cluster, and on that surface the pair must be
                // complete: without this, an operator reading logs sees "Quorum lost" and never sees
                // it restored — the same latched-red failure the un-gating of QUORUM_ESTABLISHED
                // exists to prevent, reached on a different surface.
                LOG.info("Quorum established on {} — consensus available", selfNode.id());
                emitLocal(new QuorumEstablished(hlcClock.now(),
                                                Severity.INFO,
                                                "Quorum established",
                                                Map.of("observedBy", selfNode.id())));
            }
            case PASSIVE -> {
                LOG.warn("Quorum lost on {} — consensus unavailable, cluster observability degraded", selfNode.id());
                emitLocal(new QuorumLost(hlcClock.now(),
                                         Severity.CRITICAL,
                                         "Quorum lost",
                                         Map.of("observedBy", selfNode.id())));
            }
        }
    }

    /// Subscriber hook for `MembershipDecision` — re-sources the GRACEFUL departure event NODE_LEFT.
    /// `NodeDecommissioned` / `NodeDraining` → NODE_LEFT (WARNING), genuine consensus-committed
    /// scale-down/drain decisions. NODE_FAILED is NO LONGER sourced here (#210): `NodeRemoved` is a
    /// quorum-gated, drainer-confined projection of the FSM DEAD edge, so on a multi-node cluster a
    /// non-leader CRASH heals (the DEAD edge fires, auto-heal runs) but the `NodeRemoved` decision is
    /// dropped by the projector's `inQuorum` gate / `announced` baseline during the post-kill churn
    /// window — the failure event then never reached `/api/events`. NODE_FAILED is now emitted from the
    /// ungated FSM DEAD edge (see {@link #onConfirmedDeparture}), the SAME signal that drives auto-heal.
    /// LEADER-gated via {@link #emitAsLeader}: a committed membership departure's authoritative emitter
    /// is the cluster leader.
    @Contract
    public void onMembershipDecision(MembershipDecision decision) {
        switch (decision) {
            // NODE_FAILED moved to the ungated FSM DEAD edge (#210) — the projector can drop this
            // quorum-gated decision during post-kill churn, so it is no longer the failure source.
            case MembershipDecision.NodeRemoved ignored -> {}
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
            // a counted core member, so the MembershipDeltaProjector emits no `NodeJoined` for it
            // until its OBSERVED→MEMBER promotion (Wave 4 — the FSM delta edge fires on first
            // promotion, later than the handshake). The
            // authoritative join surface is the transport `PeerJoined` handshake (onPeerJoined), now
            // LEADER-gated (the leader dials every core member, so it observes the replacement's
            // handshake).
            case MembershipDecision.NodeJoined ignored -> {}
            case MembershipDecision.NodeJoining ignored -> {}
            case MembershipDecision.NodeFailedDrain ignored -> {}
            case MembershipDecision.NodeShuttingDown ignored -> {}
        }
    }

    /// FSM DEAD-edge failure hook (#210): NODE_FAILED is sourced from the SAME ungated confirmed-death
    /// edge that drives auto-heal ({@code MembershipFsm.onConfirmedDeparture}; since #1835 its reachable
    /// half, {@code MembershipFsm.onReachableDeath}), NOT the quorum-gated
    /// {@link MembershipDecision.NodeRemoved}. On a multi-node cluster a non-leader kill heals (the DEAD
    /// edge fires on every node's FSM) but the `NodeRemoved` decision is dropped by the projector's
    /// `inQuorum` gate / drainer-confined `announced` baseline during the post-kill re-election window,
    /// so NODE_FAILED never reached `/api/events`. The DEAD edge is the reliable signal — the projector
    /// derives `NodeRemoved` FROM it, so it is a strict superset — and whenever the cluster confirms a
    /// death, every survivor emits here. Mirrors the NODE_JOINED fix (sourced from the ungated
    /// transport handshake, not the unreliable membership delta).
    ///
    /// **UN-gated via {@link #emitLocal} (#926), previously leader-gated.** The leader gate
    /// deduplicated — one emitter instead of N — and in exchange made the report of a cluster failure
    /// depend on the cluster electing a leader. Measured over ten days on a five-node cluster: SWIM
    /// confirmed 8 faulty members, 1,297,717 leader-election lines were logged, and `NodeFailed`
    /// appeared 0 times. **The observability path failed exactly when the cluster did**, and a consumer
    /// cannot distinguish that silence from health.
    ///
    /// A dedup token is NOT the fix: any token whose scope matches the counted unit must be visible to
    /// every emitter, which costs at least quorum — and the measured incident ran three of five nodes
    /// unhealthy, below quorum. It would have been silent in the very incident it is meant to report,
    /// and would newly bind this path to a coordination outcome it does not otherwise need (the local
    /// append takes no leader, quorum or consensus). Gating on `leader().isEmpty()` fails for a related
    /// reason: it makes the leadership view — the least trustworthy input in this incident — the guard
    /// on the failure path, so a stale `currentLeader()` naming a dead node silences every survivor.
    ///
    /// GUARANTEE: **at-least-once per observing core member, per confirmed departure of a member that
    /// observer's FSM had ever seen reachable** (#1835: `MembershipFsm.onReachableDeath`; a death with no
    /// such evidence raises the `node-never-joined` operator warning instead), into that
    /// member's LOCAL partition-0 ring. Deliberately NOT exactly-once and NOT deduplicated. Duplicates
    /// are bounded, not unbounded — `MembershipFsm.enteredDead` is a fresh-edge fan-out firing once per
    /// DEAD transition per member FSM — so the ceiling is one event per member confirming the death.
    /// `details.observedBy` carries the emitting node so consumers can collapse duplicates, and the
    /// distinct-observer count is itself signal. The `replayingCheck` gate is retained, so snapshot
    /// replay still does not re-publish history.
    ///
    /// The WARN log is the surface that survives everything this contract is about: it needs no leader,
    /// quorum, replica or network. `/api/events` read-back is NOT claimed here — that read prefers a
    /// remote replica and can fail while the event sits in the local ring.
    @Contract
    public void onConfirmedDeparture(NodeId departed) {
        LOG.warn("Node {} failed (confirmed departure), observed by {} — cluster membership degraded",
                 departed.id(),
                 selfNode.id());
        emitLocal(new NodeFailed(hlcClock.now(),
                                 Severity.CRITICAL,
                                 "Node " + departed.id() + " failed (confirmed departure)",
                                 Map.of("nodeId", departed.id(), "observedBy", selfNode.id())));
    }

    /// Departure-push overrun sink (issue #427, D4). The gracefully-departing node reports the chunks
    /// it could not confirm reached a surviving replica within the drain grace window. A per-node fact
    /// — the leaving node is the only source of truth for its own unpushed chunks — so it is emitted
    /// through the un-gated {@link #emitLocal} path (mirrors the `SelfDrainInitiated` / per-node
    /// contract), not owner- or leader-gated. Best-effort observability: the keys are named for
    /// operator follow-up, never silently lost.
    @Contract
    public void onDeparturePushIncomplete(NodeId departingNode, int keysAtRisk, List<String> sampleKeys) {
        emitLocal(new DeparturePushIncomplete(hlcClock.now(),
                                              Severity.WARNING,
                                              "Departure push incomplete on " + departingNode.id()
                                             + ": " + keysAtRisk
                                             + " chunk(s) at risk",
                                              buildDepartureDetails(departingNode, keysAtRisk, sampleKeys)));
    }

    private static Map<String, String> buildDepartureDetails(NodeId departingNode,
                                                             int keysAtRisk,
                                                             List<String> sampleKeys) {
        return Map.of("nodeId",
                      departingNode.id(),
                      "keysAtRisk",
                      String.valueOf(keysAtRisk),
                      "sampleKeys",
                      String.join(",", sampleKeys));
    }

    /// #1652 — community mint and lifecycle-state edges, projected from the committed `CommunityValue`
    /// write by [CommunityLifecycleEvents]. Every node sees the commit; only the owner publishes
    /// (guarantees.md row 14b).
    @Contract
    public void onCommunityPut(ValuePut<CommunityKey, CommunityValue> event) {
        CommunityLifecycleEvents.fromCommunityPut(hlcClock::now,
                                                  event.cause().key().communityId(),
                                                  event.oldValue(),
                                                  event.cause().value())
                                .forEach(this::emit);
    }

    /// #1652 — community roster joins and leaves, projected from the committed `GovernorAnnouncementValue`
    /// write by [CommunityLifecycleEvents]. Owner-gated like [#onCommunityPut].
    @Contract
    public void onGovernorAnnouncementPut(ValuePut<GovernorAnnouncementKey, GovernorAnnouncementValue> event) {
        CommunityLifecycleEvents.fromRosterPut(hlcClock::now,
                                               event.cause().key().communityId(),
                                               event.oldValue(),
                                               event.cause().value())
                                .forEach(this::emit);
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

    /// #1573: every committed automatic rollback, CRITICAL, with its evidence. Leader-gated
    /// ([#emitAsLeader]), not owner-gated: the rollback is decided and committed on the leader only, so
    /// under the owner gate the event was lost whenever another node owned the cluster-events partition.
    @Contract
    public void onAutoRollback(RollbackEvent.AutoRollbackExecuted executed) {
        emitAsLeader(new AutoRollback(hlcClock.now(),
                                      Severity.CRITICAL,
                                      "Automatic rollback of " + executed.failedArtifact().asString()
                                     + " to " + executed.targetVersion().withQualifier(),
                                      autoRollbackDetails(executed)));
    }

    private static Map<String, String> autoRollbackDetails(RollbackEvent.AutoRollbackExecuted executed) {
        var details = new HashMap<String, String>();

        details.put("artifact",
                    executed.failedArtifact().base().asString());
        details.put("from",
                    executed.failedArtifact().version().withQualifier());
        details.put("to",
                    executed.targetVersion().withQualifier());
        details.put("rollbackNumber",
                    String.valueOf(executed.rollbackNumber()));
        details.put("windowMs",
                    String.valueOf(executed.windowMs()));
        details.put("requestId", executed.requestId());
        executed.defectsPerHost()
                .forEach((node, defects) -> details.put("defects." + node.id(),
                                                        String.valueOf(defects)));

        return Map.copyOf(details);
    }

    /// #1573: produced by the leader's all-instances-failed detector only, so it is leader-gated for
    /// the same reason as [#onAutoRollback].
    @Contract
    public void onSliceFailure(SliceFailureEvent.AllInstancesFailed event) {
        emitAsLeader(new SliceFailure(hlcClock.now(),
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
    public void onScaleCapped(ScalingEvent.ScaleCapped event) {
        emit(new ScaleCapped(hlcClock.now(),
                             Severity.WARNING,
                             event.artifact().asString()
                            + " scaling capped at " + event.cappedAtInstances()
                            + " instances (requested " + event.requestedInstances()
                            + ", reason: " + event.reason()
                            + ")",
                             Map.of("artifact",
                                    event.artifact().asString(),
                                    "requestedInstances",
                                    String.valueOf(event.requestedInstances()),
                                    "cappedAtInstances",
                                    String.valueOf(event.cappedAtInstances()),
                                    "reason",
                                    event.reason())));
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
    public void onBlueprintDeployed(OperationalEvent.BlueprintDeployed event) {
        emit(new BlueprintDeployed(hlcClock.now(),
                                   Severity.INFO,
                                   "Blueprint deployed: " + event.artifactCoords(),
                                   Map.of("artifactCoords", event.artifactCoords(), "requestedBy", event.requestedBy())));
    }

    @Contract
    public void onStreamFailoverRefused(OperationalEvent.StreamFailoverRefused event) {
        emit(new StreamFailoverRefused(hlcClock.now(),
                                       Severity.CRITICAL,
                                       "Stream " + event.stream()
                                      + "[" + event.partition()
                                      + "] has no owner: owner " + event.owner()
                                      + " is not live and no in-sync replica " + event.isr()
                                      + " is live",
                                       streamFailoverDetails(event.stream(),
                                                             event.partition(),
                                                             event.owner(),
                                                             event.isr(),
                                                             event.live(),
                                                             event.reason(),
                                                             event.eventId())));
    }

    @Contract
    public void onStreamFailoverResolved(OperationalEvent.StreamFailoverResolved event) {
        emit(new StreamFailoverResolved(hlcClock.now(),
                                        Severity.INFO,
                                        "Stream " + event.stream()
                                       + "[" + event.partition()
                                       + "] has an owner again: " + event.owner(),
                                        streamFailoverDetails(event.stream(),
                                                              event.partition(),
                                                              event.owner(),
                                                              event.isr(),
                                                              event.live(),
                                                              event.reason(),
                                                              event.eventId())));
    }

    @Contract
    public void onStreamIsrBelowMinimum(OperationalEvent.StreamIsrBelowMinimum event) {
        emit(new StreamIsrBelowMinimum(hlcClock.now(),
                                       Severity.WARNING,
                                       "Stream " + event.stream()
                                      + "[" + event.partition()
                                      + "] refuses acknowledged publishes: in-sync replicas " + event.isr()
                                      + " are fewer than the confirmation factor " + event.confirmationFactor(),
                                       streamIsrDetails(event.stream(),
                                                        event.partition(),
                                                        event.owner(),
                                                        event.isr(),
                                                        event.fenced(),
                                                        event.confirmationFactor(),
                                                        event.eventId())));
    }

    @Contract
    public void onStreamIsrRestored(OperationalEvent.StreamIsrRestored event) {
        emit(new StreamIsrRestored(hlcClock.now(),
                                   Severity.INFO,
                                   "Stream " + event.stream()
                                  + "[" + event.partition()
                                  + "] accepts acknowledged publishes again: in-sync replicas " + event.isr(),
                                   streamIsrDetails(event.stream(),
                                                    event.partition(),
                                                    event.owner(),
                                                    event.isr(),
                                                    event.fenced(),
                                                    event.confirmationFactor(),
                                                    event.eventId())));
    }

    /// Taste: WARNING. The operator asked for a lower factor and the system will not do it; nothing is lost and
    /// nothing stalls by this event itself.
    @Contract
    public void onStreamConfigChangeNotApplied(OperationalEvent.StreamConfigChangeNotApplied event) {
        emit(new StreamConfigChangeNotApplied(hlcClock.now(),
                                              Severity.WARNING,
                                              "Stream " + event.stream()
                                             + " keeps confirmation factor " + event.effectiveConfirmationFactor()
                                             + ": the committed change to " + event.requestedConfirmationFactor()
                                             + " is not applied (" + event.reason()
                                             + ")",
                                              Map.of(ClusterEventIdentity.EVENT_ID,
                                                     event.eventId(),
                                                     "stream",
                                                     event.stream(),
                                                     "requestedConfirmationFactor",
                                                     String.valueOf(event.requestedConfirmationFactor()),
                                                     "effectiveConfirmationFactor",
                                                     String.valueOf(event.effectiveConfirmationFactor()),
                                                     "reason",
                                                     event.reason())));
    }

    @Contract
    public void onStreamLineageRestarted(OperationalEvent.StreamLineageRestarted event) {
        emit(new StreamLineageRestarted(hlcClock.now(),
                                        Severity.INFO,
                                        "Stream " + event.stream()
                                       + "[" + event.partition()
                                       + "] began a new epoch " + event.newEpoch()
                                       + " on its owner " + event.owner()
                                       + " at offset " + event.startOffset()
                                       + ": its ring was rebuilt, consumers read from that offset again",
                                        Map.of("stream",
                                               event.stream(),
                                               "partition",
                                               String.valueOf(event.partition()),
                                               "owner",
                                               event.owner(),
                                               "oldEpoch",
                                               event.oldEpoch(),
                                               "newEpoch",
                                               event.newEpoch(),
                                               "startOffset",
                                               String.valueOf(event.startOffset()))));
    }

    private static Map<String, String> streamIsrDetails(String stream,
                                                        int partition,
                                                        String owner,
                                                        List<String> isr,
                                                        List<String> fenced,
                                                        int confirmationFactor,
                                                        String eventId) {
        return Map.of(ClusterEventIdentity.EVENT_ID,
                      eventId,
                      "stream",
                      stream,
                      "partition",
                      String.valueOf(partition),
                      "owner",
                      owner,
                      "isr",
                      String.join(",", isr),
                      "fenced",
                      String.join(",", fenced),
                      "confirmationFactor",
                      String.valueOf(confirmationFactor));
    }

    private static Map<String, String> streamFailoverDetails(String stream,
                                                             int partition,
                                                             String owner,
                                                             List<String> isr,
                                                             List<String> live,
                                                             String reason,
                                                             String eventId) {
        return Map.of(ClusterEventIdentity.EVENT_ID,
                      eventId,
                      "stream",
                      stream,
                      "partition",
                      String.valueOf(partition),
                      "owner",
                      owner,
                      "isr",
                      String.join(",", isr),
                      "live",
                      String.join(",", live),
                      "reason",
                      reason);
    }

    @Contract
    public void onBlueprintDeleted(OperationalEvent.BlueprintDeleted event) {
        emit(new BlueprintDeleted(hlcClock.now(),
                                  Severity.INFO,
                                  "Blueprint deleted: " + event.artifactId(),
                                  Map.of("artifactId", event.artifactId(), "requestedBy", event.requestedBy())));
    }

    /// #1777 R1b: every node derives this from the committed change record, so it goes through the owner-gated [#emit]
    /// and is published at most once (missed if the owner cannot publish at that moment).
    @Contract
    public void onDhtReplicationUnsettled(OperationalEvent.DhtReplicationUnsettled event) {
        emit(new ClusterEvent.DhtReplicationUnsettled(hlcClock.now(),
                                                      Severity.WARNING,
                                                      "DHT replication change " + event.changeVersion()
                                                     + " (RF " + event.replicationFactor()
                                                     + ", CF " + event.confirmationFactor()
                                                     + ") is " + event.reason()
                                                     + "; the stricter transitional quorums stay in force",
                                                      Map.of("changeVersion",
                                                             String.valueOf(event.changeVersion()),
                                                             "replicationFactor",
                                                             String.valueOf(event.replicationFactor()),
                                                             "confirmationFactor",
                                                             String.valueOf(event.confirmationFactor()),
                                                             "stage",
                                                             event.stage(),
                                                             "since",
                                                             String.valueOf(event.since()),
                                                             "reason",
                                                             event.reason())));
    }

    @Contract
    public void onDhtReplicationSettled(OperationalEvent.DhtReplicationSettled event) {
        emit(new ClusterEvent.DhtReplicationSettled(hlcClock.now(),
                                                    Severity.INFO,
                                                    "DHT replication change " + event.changeVersion()
                                                   + " is no longer unsettled: " + event.reason(),
                                                    Map.of("changeVersion",
                                                           String.valueOf(event.changeVersion()),
                                                           "replicationFactor",
                                                           String.valueOf(event.replicationFactor()),
                                                           "confirmationFactor",
                                                           String.valueOf(event.confirmationFactor()),
                                                           "since",
                                                           String.valueOf(event.since()),
                                                           "reason",
                                                           event.reason())));
    }

    /// #1777 (owner rule): a per-node fact raised by the stale writer itself, so it bypasses the owner gate
    /// ([#emitLocal]); published at most once per episode.
    @Contract
    public void onDhtWriterStale(OperationalEvent.DhtWriterStale event) {
        emitLocal(new ClusterEvent.DhtWriterStale(hlcClock.now(),
                                                  Severity.WARNING,
                                                  "DHT writes of node " + event.nodeId()
                                                 + " have been refused for over 5 minutes as stamped under replication change " + event.fence()
                                                 + "; the node has not adopted the cluster's newer change",
                                                  writerStaleDetails(event.nodeId(), event.fence(), event.since())));
    }

    @Contract
    public void onDhtWriterStaleResolved(OperationalEvent.DhtWriterStaleResolved event) {
        emitLocal(new ClusterEvent.DhtWriterStaleResolved(hlcClock.now(),
                                                          Severity.INFO,
                                                          "DHT writes of node " + event.nodeId()
                                                         + " are stamped under the cluster's replication change again",
                                                          writerStaleDetails(event.nodeId(),
                                                                             event.fence(),
                                                                             event.since())));
    }

    private static Map<String, String> writerStaleDetails(String nodeId, long fence, long since) {
        return Map.of("nodeId", nodeId, "fence", String.valueOf(fence), "since", String.valueOf(since));
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
