// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation.fsm;

import org.pragmatica.aether.deployment.generation.PeerObservationReducer;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.slice.generation.ConnectivityReport;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.CommandsApplied;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.CommandsApplyFailed;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.MembershipReseeded;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.ReprojectionCompleted;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.ReprojectionFailed;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.ReprojectionRequested;
import org.pragmatica.aether.deployment.generation.fsm.HealthReconcilerEvents.SignalReceived;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.CommunitySummary;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.GenerationChangedNotice;
import org.pragmatica.aether.slice.generation.GenerationChangedSink;
import org.pragmatica.aether.slice.generation.GenerationReason;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.OperatorIntent;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SpokesmanKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtPartitionOwnershipValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanValue;
import org.pragmatica.cluster.metrics.ConnectivityState;
import org.pragmatica.cluster.metrics.HealthHintWire;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.metrics.PeerHealthObservation;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.TransitionRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot.empty;


/// Shared context for the HealthReconciler FSM. Holds every long-lived artifact that is
/// intentionally NOT on a state record:
///
/// - Collaborators and config (cluster, projector, clock, suppliers, external leader supplier,
///   generation-changed sink, self id, auto-heal config).
/// - Per-leadership mutable bookkeeping that is NOT guard-visible by state transitions —
///   `swimHints`, `pendingRemovals`, `peerObservationReducer`, and the
///   `consensusApplyFailed` metric counter. Cleared on `clearLeaderData()` at entry to
///   `Dormant` / `Following` / `Stopped`. The consecutive ping-miss counter
///   intentionally lives on the node-singleton [`PeerObservationStore`] (NOT here) — its
///   lifetime is per-NODE so leader thrash does not lose miss telemetry.
/// - The reprojection executor — a dedicated single-thread `ExecutorService` that runs supplier
///   tasks and dispatches `ReprojectionCompleted` / `ReprojectionFailed` events back into the FSM.
/// - The ambient snapshot — the snapshot visible to external readers when we are NOT leading
///   (Dormant / QuorumWaiting / Following). Leading states own their own snapshot on the record.
public final class HealthReconcilerContext {
    private static final Logger log = LoggerFactory.getLogger(HealthReconcilerContext.class);

    private static final String CORE_COMMUNITY_ID = "core";

    private static final int DEFAULT_SUSPECT_THRESHOLD = 3;

    private static final int DEFAULT_REMOVE_THRESHOLD = 10;

    private static final int LATE_SIGNAL_WINDOW = 2;

    private final Fsm<HealthReconcilerState, ClusterFsmEvent> fsm;
    private final NodeId self;
    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final HlcClock hlcClock;
    private final Supplier<Long> rabiaTermSupplier;
    private final BooleanSupplier externalLeaderSupplier;
    private final AutoHealConfig autoHealConfig;
    private final GenerationChangedSink generationChangedSink;
    private final PeerObservationReducer peerObservationReducer;
    private final PeerObservationStore peerObservationStore;
    private final LongSupplier clock;
    private final HealthReconcilerState dormant;
    private final HealthReconcilerState quorumWaiting;
    private final HealthReconcilerState following;
    private final HealthReconcilerState stopped;

    /// Per-NodeId leader-projection SWIM hints with write-time TTL. Each entry records the
    /// hint AND the wall-clock instant it was written. `swimHintsView()` and all internal
    /// reads filter entries older than `autoHealConfig.swimHintsTtl()` — they are treated as
    /// absent (HEALTHY default per `ClusterGenerationProjector.deriveHealthHint`).
    /// Defense-in-depth against sticky SUSPECTED state from transient SWIM probes during boot
    /// when SWIM doesn't re-emit HEALTHY for peers it considers stable. Does NOT mask SWIM
    /// SUSPECT/FAULT signals — those refresh `writeMs` on every emission.
    private final Map<NodeId, TimestampedHint> swimHints = new ConcurrentHashMap<>();

    /// `swimHints` entry: a hint plus the wall-clock instant (ms) at which it was last
    /// written. Used by `effectiveSwimHints()` to evict expired entries from the read view.
    private record TimestampedHint(HealthHint hint, long writeMs) {}

    private final Set<NodeId> pendingRemovals = ConcurrentHashMap.newKeySet();

    private final AtomicLong consensusApplyFailed = new AtomicLong();

    private final AtomicReference<ExecutorService> reprojectionExecutor = new AtomicReference<>();

    private final AtomicReference<Supplier<ClusterGenerationSnapshot>> lastSupplier = new AtomicReference<>();

    private final AtomicReference<ClusterGenerationSnapshot> ambientSnapshot;

    /// True when the next entry into Leading* is the first since promotion (or since
    /// `clearLeaderData()` ran on a demote). The first entry takes a single
    /// subscribe-and-drain pair on the [`PeerObservationStore`] (held on the context, NOT on
    /// the state records) so intra-Leading* transitions inherit them — avoiding the
    /// duplicate-callback race that would arise if every fresh Leading record took its own
    /// pair. Reset to `true` on `clearLeaderData()`.
    private final AtomicBoolean firstLeadingEntry = new AtomicBoolean(true);

    /// Tracks whether the FIRST publish of the current Leading tenure has completed. Until
    /// this flips to `true`, [`#publishLeadingSnapshotWithBarrier`] defers the publish behind
    /// either a no-op consensus apply (proof of replication drain) OR a 1-second grace timer.
    /// Reset to `false` on every `clearLeaderData()` so each new Leading tenure re-arms the
    /// barrier. Theme B Item 1 — closes the phantom-provision window between leader-gain and
    /// reconciler-ready.
    private final AtomicBoolean firstPublishCompleted = new AtomicBoolean(false);

    /// Active peer-observation subscriptions for the current Leading-tenure. Released by
    /// `releasePeerObservationChannel()` from `clearLeaderData()`.
    private final AtomicReference<PeerObservationStore.Subscription> healthSubscription = new AtomicReference<>();
    private final AtomicReference<PeerObservationStore.Subscription> connectivitySubscription = new AtomicReference<>();

    public HealthReconcilerContext(Fsm<HealthReconcilerState, ClusterFsmEvent> fsm,
                                   NodeId self,
                                   ClusterNode<KVCommand<AetherKey>> cluster,
                                   HlcClock hlcClock,
                                   Supplier<Long> rabiaTermSupplier,
                                   BooleanSupplier externalLeaderSupplier,
                                   AutoHealConfig autoHealConfig,
                                   GenerationChangedSink generationChangedSink,
                                   PeerObservationReducer peerObservationReducer,
                                   PeerObservationStore peerObservationStore) {
        this(fsm, self, cluster, hlcClock, rabiaTermSupplier, externalLeaderSupplier,
             autoHealConfig, generationChangedSink, peerObservationReducer,
             peerObservationStore, System::currentTimeMillis);
    }

    /// Full-arity constructor with injectable clock — for tests that need deterministic time.
    public HealthReconcilerContext(Fsm<HealthReconcilerState, ClusterFsmEvent> fsm,
                                   NodeId self,
                                   ClusterNode<KVCommand<AetherKey>> cluster,
                                   HlcClock hlcClock,
                                   Supplier<Long> rabiaTermSupplier,
                                   BooleanSupplier externalLeaderSupplier,
                                   AutoHealConfig autoHealConfig,
                                   GenerationChangedSink generationChangedSink,
                                   PeerObservationReducer peerObservationReducer,
                                   PeerObservationStore peerObservationStore,
                                   LongSupplier clock) {
        this.fsm = fsm;
        this.self = self;
        this.cluster = cluster;
        this.hlcClock = hlcClock;
        this.rabiaTermSupplier = rabiaTermSupplier;
        this.externalLeaderSupplier = externalLeaderSupplier;
        this.autoHealConfig = autoHealConfig;
        this.generationChangedSink = generationChangedSink;
        this.peerObservationReducer = peerObservationReducer;
        this.peerObservationStore = peerObservationStore;
        this.clock = clock;
        this.ambientSnapshot = new AtomicReference<>(empty(rabiaTermSupplier.get()));
        this.dormant = new HealthReconcilerState.Dormant(this);
        this.quorumWaiting = new HealthReconcilerState.QuorumWaiting(this);
        this.following = new HealthReconcilerState.Following(this);
        this.stopped = new HealthReconcilerState.Stopped(this);
    }

    /// Current time in milliseconds. Reads from the injected clock so tests can make FSM
    /// transitions deterministic. Equivalent to `System.currentTimeMillis()` in production.
    public long nowMs() {
        return clock.getAsLong();
    }

    public Fsm<HealthReconcilerState, ClusterFsmEvent> fsm() {
        return fsm;
    }

    @Contract public void dispatch(ClusterFsmEvent event) {
        fsm.dispatch(event);
    }

    public HealthReconcilerState dormant() {
        return dormant;
    }

    public HealthReconcilerState quorumWaiting() {
        return quorumWaiting;
    }

    public HealthReconcilerState following() {
        return following;
    }

    public HealthReconcilerState stopped() {
        return stopped;
    }

    public HealthReconcilerState.LeadingSteady newLeadingSteady(Epoch startEpoch, ClusterGenerationSnapshot snapshot) {
        return new HealthReconcilerState.LeadingSteady(this, startEpoch, snapshot);
    }

    public HealthReconcilerState.LeadingReprojecting newLeadingReprojecting(Epoch startEpoch,
                                                                            ClusterGenerationSnapshot snapshot,
                                                                            Supplier<ClusterGenerationSnapshot> supplier,
                                                                            String reason) {
        log.trace("LeadingReprojecting constructed (reason={}, startEpoch={})", reason, startEpoch);
        return new HealthReconcilerState.LeadingReprojecting(this, startEpoch, snapshot, supplier);
    }

    /// Returns the node-singleton observation store. Used by `LeadingSteady.onEntry` and
    /// `LeadingReprojecting.onEntry` to drain pre-existing observations on entry.
    public PeerObservationStore peerObservationStore() {
        return peerObservationStore;
    }

    /// Atomically (1) subscribe live callbacks for fresh peer-observation arrivals AND
    /// (2) forward every pre-promotion buffered observation through the same callbacks IF
    /// this is the first Leading* entry since promotion (or last `clearLeaderData()`).
    /// Subsequent intra-Leading* re-entries are no-ops: the subscriptions taken on first
    /// entry survive intra-Leading transitions and are released only when `clearLeaderData()`
    /// runs (demote / quorum loss / shutdown).
    ///
    /// Why context-held subscriptions: per-state-record subscriptions would create a
    /// brief double-subscription window during every intra-Leading transition (factory
    /// subscribes NEW before CAS while OLD subscription is still live), causing every push
    /// in that window to fire BOTH callbacks → duplicate `SignalReceived` events. Holding a
    /// single subscription pair for the entire Leading-tenure eliminates that race and is
    /// consistent with the per-NODE semantics of [`PeerObservationStore`] (counters and
    /// buffers outlive any single state record).
    @Contract public void activatePeerObservationChannelOnFirstLeadingEntry() {
        if (!firstLeadingEntry.compareAndSet(true, false)) {return;}
        var healthDrainAndSub = peerObservationStore.subscribeHealthAndDrain(this::onPeerHealth);
        var connDrainAndSub = peerObservationStore.subscribeConnectivityAndDrain(this::onPeerConnectivity);
        healthSubscription.set(healthDrainAndSub.subscription());
        connectivitySubscription.set(connDrainAndSub.subscription());
        healthDrainAndSub.drained().forEach(this::onPeerHealth);
        connDrainAndSub.drained().forEach(this::onPeerConnectivity);
    }

    /// Release the peer-observation subscriptions taken by
    /// [`#activatePeerObservationChannelOnFirstLeadingEntry`]. Idempotent — safe to call from
    /// `clearLeaderData()` even if no subscription was taken (e.g., never reached Leading*).
    @Contract private void releasePeerObservationChannel() {
        Option.option(healthSubscription.getAndSet(null)).onPresent(PeerObservationStore.Subscription::unsubscribe);
        Option.option(connectivitySubscription.getAndSet(null)).onPresent(PeerObservationStore.Subscription::unsubscribe);
    }

    /// Callback fired by the [`PeerObservationStore`] subscription on every fresh health
    /// observation arrival, AND replayed for every observation drained on Leading* entry.
    /// Translates the buffered self-observation into a [`HealthSignal.RemoteSwimHint`] (observer
    /// = self) and dispatches a `SignalReceived` event so the existing leader-side handlers
    /// process it. Q4 staleness filter is applied downstream by [`#isStaleObservation`] inside
    /// `handleRemoteSwimHint`.
    @Contract public void onPeerHealth(PeerHealthObservation observation) {
        var signal = new HealthSignal.RemoteSwimHint(self,
                                                     observation.peerId(),
                                                     translateHint(observation.hint()),
                                                     Epoch.epoch(observation.observedEpochTerm(),
                                                                 observation.observedEpochCounter()),
                                                     observation.producedAtMs());
        fsm.dispatch(new SignalReceived(signal));
    }

    /// Companion to [`#onPeerHealth`] for connectivity observations.
    @Contract public void onPeerConnectivity(PeerConnectivityObservation observation) {
        var signal = new HealthSignal.RemoteConnectivity(self,
                                                         observation.peerId(),
                                                         translateConnectivity(observation.state()),
                                                         Epoch.epoch(observation.observedEpochTerm(),
                                                                     observation.observedEpochCounter()),
                                                         observation.producedAtMs());
        fsm.dispatch(new SignalReceived(signal));
    }

    private static HealthHint translateHint(HealthHintWire wire) {
        return switch (wire){
            case HEALTHY -> HealthHint.HEALTHY;
            case SUSPECTED -> HealthHint.SUSPECTED;
            case FAULTY -> HealthHint.FAULTY;
        };
    }

    private static ConnectivityReport translateConnectivity(ConnectivityState state) {
        return switch (state){
            case CONNECTED -> ConnectivityReport.CONNECTED;
            case DISCONNECTED -> ConnectivityReport.DISCONNECTED;
            case STALE -> ConnectivityReport.STALE;
        };
    }

    public NodeId self() {
        return self;
    }

    public AutoHealConfig autoHealConfig() {
        return autoHealConfig;
    }

    public long consensusApplyFailedCount() {
        return consensusApplyFailed.get();
    }

    public Epoch defaultLeaderEpoch() {
        return Epoch.epoch(rabiaTermSupplier.get(), 0L);
    }

    public boolean gateAllowsLeaderWork() {
        return externalLeaderSupplier.getAsBoolean();
    }

    public ClusterGenerationSnapshot ambientSnapshot() {
        return ambientSnapshot.get();
    }

    /// Read-only view over the leader-projection SWIM hints map, filtered by the configured
    /// TTL. Entries whose `writeMs` predates `now - autoHealConfig.swimHintsTtl()` are
    /// excluded — the projector then defaults to `HEALTHY` per
    /// `ClusterGenerationProjector.deriveHealthHint`. Exposed for tests and diagnostics —
    /// callers must not mutate. Mutations go through `handleSwimHint` /
    /// `promoteToFaultyIfThresholdReached` / `clearLeaderData`.
    public Map<NodeId, HealthHint> swimHintsView() {
        return effectiveSwimHints();
    }

    /// Return a read-only snapshot of `swimHints` with expired entries filtered out. An
    /// entry is "expired" when `nowMs() - writeMs > swimHintsTtl().millis()`. The map is
    /// rebuilt on every call — cheap (hint count is bounded by core size, ≤ ~10s in
    /// practice) and reflects the freshest TTL state. Internal callers
    /// (`shouldEvict` / `promoteToFaultyIfThresholdReached` / `handleRemoteConnected`)
    /// use [`#effectiveHint`] for single-key lookups instead of materialising the map.
    private Map<NodeId, HealthHint> effectiveSwimHints() {
        var ttlMs = autoHealConfig.swimHintsTtl().millis();
        var cutoffMs = nowMs() - ttlMs;
        var result = new HashMap<NodeId, HealthHint>(swimHints.size());
        for (var entry : swimHints.entrySet()) {
            var stamped = entry.getValue();
            if (stamped.writeMs() >= cutoffMs) {
                result.put(entry.getKey(), stamped.hint());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /// Single-key lookup that respects the TTL — returns `HEALTHY` (default-when-absent) for
    /// expired or absent entries, mirroring the contract of `effectiveSwimHints()`.
    private HealthHint effectiveHint(NodeId nodeId) {
        var stamped = swimHints.get(nodeId);
        if (stamped == null) {return HealthHint.HEALTHY;}
        var ttlMs = autoHealConfig.swimHintsTtl().millis();
        if ((nowMs() - stamped.writeMs()) > ttlMs) {return HealthHint.HEALTHY;}
        return stamped.hint();
    }

    /// Idempotent write to `swimHints` that records the current write timestamp. Subsequent
    /// reads return the hint unless `swimHintsTtl` elapses without re-emission.
    private void putHint(NodeId nodeId, HealthHint hint) {
        swimHints.put(nodeId, new TimestampedHint(hint, nowMs()));
    }

    @Contract public void setAmbientSnapshot(ClusterGenerationSnapshot snapshot) {
        ambientSnapshot.set(snapshot);
    }

    @Contract public void publishLeadingSnapshot(ClusterGenerationSnapshot snapshot) {
        ambientSnapshot.set(snapshot);
    }

    /// Latest snapshot pending publication while the first-publish barrier is armed. Updated
    /// by every `publishLeadingSnapshotWithBarrier` call during the barrier window so that
    /// when the grace timer fires it publishes the FRESHEST snapshot (not the snapshot from
    /// the original arming call). Coalesces a flurry of intra-tenure publishes into a single
    /// publish at grace expiry.
    private final AtomicReference<ClusterGenerationSnapshot> pendingBarrierSnapshot = new AtomicReference<>();

    /// First-publish barrier (Theme B Item 1): on the FIRST publish of a new Leading tenure
    /// the snapshot is held back until a grace timer expires. Subsequent publishes within
    /// the same tenure that arrive BEFORE the grace timer fires update the pending snapshot
    /// pointer (so the freshest is published at grace expiry). Once the timer has fired,
    /// all subsequent publishes are immediate. Reset by `clearLeaderData()` on demote/shutdown.
    ///
    /// The publish itself just sets `ambientSnapshot`; the barrier exists so external readers
    /// (CTM, dashboard, REST) cannot observe a stale-replication-window snapshot before the
    /// new leader's term has been replicated.
    ///
    /// Grace duration: previously 1 second; extended to 3 seconds as a defensive stopgap
    /// for QUIC backpressure / consensus-drain latency under load. The original 1s window
    /// expired before consensus had drained on slow paths (heavy QUIC backpressure) and
    /// allowed external readers to observe a stale ambient snapshot, racing CTM into
    /// phantom-provision territory. 3s is conservative — replication on a healthy 5-node
    /// cluster completes in <100ms; the elongated window only matters on the pathological
    /// path and remains short enough to keep first-publish latency acceptable.
    ///
    /// TODO(rc2): replace the time-based grace with a real consensus-drain barrier. The
    /// straightforward approach (`cluster.apply(List.of())` as a no-op probe) breaks fake
    /// `ClusterNode` test fixtures that count `apply` invocations — every Leading entry would
    /// emit an empty batch into their recorded history. Either introduce a `KVCommand.NoOp`
    /// sentinel that fixtures can ignore OR expose a dedicated drain primitive on
    /// `ClusterNode`. Until then, the time-based grace is best-effort.
    private static final long FIRST_PUBLISH_GRACE_SECONDS = 3L;

    @Contract public void publishLeadingSnapshotWithBarrier(ClusterGenerationSnapshot snapshot) {
        if (firstPublishCompleted.get()) {
            ambientSnapshot.set(snapshot);
            return;
        }
        // Always remember the latest snapshot while the barrier is armed.
        pendingBarrierSnapshot.set(snapshot);
        // Schedule the grace timer ONCE per tenure. CAS ensures exactly one timer is armed.
        if (barrierTimerScheduled.compareAndSet(false, true)) {
            log.warn("First-publish barrier armed with {}s time-based grace (rc2 TODO: replace with consensus-drain barrier)",
                     FIRST_PUBLISH_GRACE_SECONDS);
            org.pragmatica.lang.utils.SharedScheduler.schedule(this::releaseFirstPublishBarrier,
                                                                org.pragmatica.lang.io.TimeSpan.timeSpan(FIRST_PUBLISH_GRACE_SECONDS).seconds());
        }
    }

    /// Latch for "grace timer scheduled" — flips false→true on first barrier call per tenure;
    /// reset by `clearLeaderData()` so each new tenure re-arms exactly one timer.
    private final AtomicBoolean barrierTimerScheduled = new AtomicBoolean(false);

    /// Grace-timer callback: publishes the latest pending snapshot (if any) and flips the
    /// barrier flag so subsequent publishes are immediate. Idempotent — re-entry is a no-op
    /// because `firstPublishCompleted` is already true.
    @Contract private void releaseFirstPublishBarrier() {
        if (!firstPublishCompleted.compareAndSet(false, true)) {return;}
        var pending = pendingBarrierSnapshot.getAndSet(null);
        if (pending != null) {ambientSnapshot.set(pending);}
        log.debug("LeadingSteady first-publish barrier released by grace-timer");
    }

    /// Clears strictly leader-projection state. The consecutive ping-miss counter is
    /// intentionally NOT cleared here — it lives on the node-singleton
    /// [`PeerObservationStore`] and is per-NODE, not per-leader-tenure. swimHints and
    /// pendingRemovals are leader-projection state correctly re-derived from KV-store on
    /// next leader entry.
    @Contract public void clearLeaderData() {
        swimHints.clear();
        pendingRemovals.clear();
        lastSupplier.set(null);
        releasePeerObservationChannel();
        firstLeadingEntry.set(true);
        // If the first-publish barrier was armed but never released, flush the freshest
        // pending snapshot to ambientSnapshot before tearing down. Otherwise external
        // readers would observe a stale snapshot from before the barrier was armed.
        var pending = pendingBarrierSnapshot.getAndSet(null);
        if (pending != null) {ambientSnapshot.set(pending);}
        firstPublishCompleted.set(false);
        barrierTimerScheduled.set(false);
    }

    public Option<Supplier<ClusterGenerationSnapshot>> lastSupplier() {
        return Option.option(lastSupplier.get());
    }

    @Contract public void rememberSupplier(Supplier<ClusterGenerationSnapshot> supplier) {
        lastSupplier.set(supplier);
    }

    @Contract public void ensureReprojectionExecutor() {
        if (reprojectionExecutor.get() != null) {return;}
        var executor = Executors.newSingleThreadExecutor(reprojectionThreadFactory(self));
        if (!reprojectionExecutor.compareAndSet(null, executor)) {executor.shutdownNow();}
    }

    @Contract public void shutdownReprojectionExecutor() {
        Option.option(reprojectionExecutor.getAndSet(null)).onPresent(HealthReconcilerContext::drainAndTerminate);
    }

    @Contract public void submitReprojection(Epoch startEpoch, Supplier<ClusterGenerationSnapshot> supplier) {
        ensureReprojectionExecutor();
        var executor = reprojectionExecutor.get();
        if (executor == null) {
            fsm.dispatch(new ReprojectionFailed(startEpoch));
            return;
        }
        Result.lift(() -> executor.execute(() -> runReprojectionTask(startEpoch, supplier)))
                   .onFailure(cause -> handleExecutorRejection(startEpoch, cause));
    }

    @Contract private void handleExecutorRejection(Epoch startEpoch, Cause cause) {
        log.trace("Reprojection submission rejected (executor shut down): {}", cause.message());
        fsm.dispatch(new ReprojectionFailed(startEpoch));
    }

    @Contract private void runReprojectionTask(Epoch startEpoch, Supplier<ClusterGenerationSnapshot> supplier) {
        Result.lift(supplier::get).onSuccess(fresh -> dispatchReprojectionResult(startEpoch, fresh))
                   .onFailure(cause -> dispatchReprojectionFailure(startEpoch, cause));
    }

    @Contract private void dispatchReprojectionResult(Epoch startEpoch, ClusterGenerationSnapshot fresh) {
        fsm.dispatch(new ReprojectionCompleted(startEpoch, fresh));
    }

    @Contract private void dispatchReprojectionFailure(Epoch startEpoch, Cause cause) {
        log.warn("Reprojection supplier failed: {}", cause.message());
        fsm.dispatch(new ReprojectionFailed(startEpoch));
    }

    private static ThreadFactory reprojectionThreadFactory(NodeId self) {
        return runnable -> {
            var thread = new Thread(runnable, "aether-reconciler-" + self.id());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Contract private static void drainAndTerminate(ExecutorService executor) {
        executor.shutdown();
        Result.lift(() -> awaitWithFallback(executor)).onFailure(_ -> terminateInterrupted(executor));
    }

    private static boolean awaitWithFallback(ExecutorService executor) throws InterruptedException {
        if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            return false;
        }
        return true;
    }

    @Contract private static void terminateInterrupted(ExecutorService executor) {
        Thread.currentThread().interrupt();
        executor.shutdownNow();
    }

    // The supplier is authoritative but may have computed off a snapshot that is now stale — other
    // signals may have advanced `state.snapshot()` while the supplier executed on the reprojection
    // executor thread. `computeReseedResult` diffs the supplier output against the CURRENT snapshot
    // and returns `none()` if nothing actually moved, in which case we roll back to the current
    // snapshot (no-op transition). Do NOT simplify to `projected` directly.
    @Contract public void handleReprojectionCompletedPayload(HealthReconcilerState.LeadingReprojecting state,
                                                             ClusterGenerationSnapshot projected,
                                                             TransitionRequest<HealthReconcilerState, ClusterFsmEvent> tx) {
        var nextSnapshot = computeReseedResult(state.snapshot(),
                                               projected).map(ReseedApplied::nextSnapshot)
                                              .or(state.snapshot());
        emitGenerationChangedIfMoved(state.snapshot(), nextSnapshot, reseedReason(state.snapshot(), projected));
        tx.transitionToOrDrop(newLeadingSteady(state.startEpoch(), nextSnapshot));
    }

    @Contract private void emitGenerationChangedIfMoved(ClusterGenerationSnapshot previous,
                                                        ClusterGenerationSnapshot next,
                                                        GenerationReason reason) {
        if (previous.epoch().equals(next.epoch())) {return;}
        generationChangedSink.emit(GenerationChangedNotice.generationChangedNotice(previous.epoch(),
                                                                                   next.epoch(),
                                                                                   reason));
    }

    private static GenerationReason reseedReason(ClusterGenerationSnapshot current, ClusterGenerationSnapshot fresh) {
        return fresh.coreMembers().size() >= current.coreMembers().size()
              ? GenerationReason.MEMBER_ADDED
              : GenerationReason.MEMBER_REMOVED;
    }

    @Contract public void handleSignalFromLeadingSteady(HealthReconcilerState.LeadingSteady state,
                                                        SignalReceived event,
                                                        TransitionRequest<HealthReconcilerState, ClusterFsmEvent> tx) {
        var outcome = processSignal(state.startEpoch(), state.snapshot(), event.signal());
        if (outcome.membershipChange() == MembershipChange.UNCHANGED) {
            tx.ignore();
            return;
        }
        applyOutcomeEffects(state.startEpoch(), state.snapshot(), outcome);
        tx.transitionToOrDrop(newLeadingSteady(state.startEpoch(), outcome.nextSnapshot()));
    }

    @Contract public void handleSignalFromLeadingReprojecting(HealthReconcilerState.LeadingReprojecting state,
                                                              SignalReceived event,
                                                              TransitionRequest<HealthReconcilerState, ClusterFsmEvent> tx) {
        var outcome = processSignal(state.startEpoch(), state.snapshot(), event.signal());
        if (outcome.membershipChange() == MembershipChange.UNCHANGED) {
            tx.ignore();
            return;
        }
        applyOutcomeEffects(state.startEpoch(), state.snapshot(), outcome);
        tx.transitionToOrDrop(newLeadingReprojecting(state.startEpoch(),
                                                     outcome.nextSnapshot(),
                                                     state.supplier(),
                                                     "signal-received"));
    }

    @Contract private void applyOutcomeEffects(Epoch startEpoch,
                                               ClusterGenerationSnapshot previous,
                                               SignalOutcome outcome) {
        if (outcome.termAdvance() == TermAdvance.ADVANCED) {
            clearLeaderData();
        }
        if (!outcome.commands().isEmpty()) {
            fireCommandsApply(startEpoch,
                              outcome.commands(),
                              outcome.reason(),
                              previous,
                              outcome.nextSnapshot(),
                              outcome.attemptedNodeIds());
            return;
        }
        generationChangedSink.emit(GenerationChangedNotice.generationChangedNotice(previous.epoch(),
                                                                                   outcome.nextSnapshot().epoch(),
                                                                                   outcome.reason()));
    }

    @Contract public void handleMembershipReseedFromLeadingSteady(HealthReconcilerState.LeadingSteady state,
                                                                  MembershipReseeded event,
                                                                  TransitionRequest<HealthReconcilerState, ClusterFsmEvent> tx) {
        var result = computeReseedResult(state.snapshot(), event.freshProjection());
        if (result.isEmpty()) {
            tx.ignore();
            return;
        }
        var applied = result.unwrap();
        fireCommandsApply(state.startEpoch(),
                          applied.commands(),
                          applied.reason(),
                          state.snapshot(),
                          applied.nextSnapshot(),
                          applied.attemptedNodeIds());
        tx.transitionToOrDrop(newLeadingSteady(state.startEpoch(), applied.nextSnapshot()));
    }

    @Contract public void handleMembershipReseedFromLeadingReprojecting(HealthReconcilerState.LeadingReprojecting state,
                                                                        MembershipReseeded event,
                                                                        TransitionRequest<HealthReconcilerState, ClusterFsmEvent> tx) {
        var result = computeReseedResult(state.snapshot(), event.freshProjection());
        if (result.isEmpty()) {
            tx.ignore();
            return;
        }
        var applied = result.unwrap();
        fireCommandsApply(state.startEpoch(),
                          applied.commands(),
                          applied.reason(),
                          state.snapshot(),
                          applied.nextSnapshot(),
                          applied.attemptedNodeIds());
        tx.transitionToOrDrop(newLeadingReprojecting(state.startEpoch(),
                                                     applied.nextSnapshot(),
                                                     state.supplier(),
                                                     "membership-reseed"));
    }

    @Contract public void handleCommandsAppliedFromLeadingSteady(HealthReconcilerState.LeadingSteady state,
                                                                 CommandsApplied event,
                                                                 TransitionRequest<HealthReconcilerState, ClusterFsmEvent> tx) {
        if (!event.startEpoch().equals(state.startEpoch())) {
            tx.ignore();
            return;
        }
        generationChangedSink.emit(GenerationChangedNotice.generationChangedNotice(event.previousSnapshot().epoch(),
                                                                                   event.nextSnapshot().epoch(),
                                                                                   event.reason()));
        tx.transitionToOrDrop(newLeadingSteady(state.startEpoch(), event.nextSnapshot()));
    }

    @Contract public void handleCommandsAppliedFromLeadingReprojecting(HealthReconcilerState.LeadingReprojecting state,
                                                                       CommandsApplied event,
                                                                       TransitionRequest<HealthReconcilerState, ClusterFsmEvent> tx) {
        if (!event.startEpoch().equals(state.startEpoch())) {
            tx.ignore();
            return;
        }
        generationChangedSink.emit(GenerationChangedNotice.generationChangedNotice(event.previousSnapshot().epoch(),
                                                                                   event.nextSnapshot().epoch(),
                                                                                   event.reason()));
        tx.transitionToOrDrop(newLeadingReprojecting(state.startEpoch(),
                                                     event.nextSnapshot(),
                                                     state.supplier(),
                                                     "commands-applied"));
    }

    @Contract public void handleCommandsApplyFailedFromLeading(HealthReconcilerState state,
                                                               CommandsApplyFailed event,
                                                               TransitionRequest<HealthReconcilerState, ClusterFsmEvent> tx) {
        consensusApplyFailed.incrementAndGet();
        event.attemptedNodeIds().forEach(pendingRemovals::remove);
        log.warn("HealthReconciler consensus apply failed (attempted nodes={})", event.attemptedNodeIds());
        dispatchConsensusFailureReprojection(state);
        tx.ignore();
    }

    @Contract private void dispatchConsensusFailureReprojection(HealthReconcilerState state) {
        var supplier = switch (state){
            case HealthReconcilerState.LeadingReprojecting lr -> Option.some(lr.supplier());
            default -> lastSupplier();
        };
        supplier.onPresent(fn -> fsm.dispatch(new ReprojectionRequested(fn, "consensus-apply-failure")));
    }

    @Contract private void fireCommandsApply(Epoch startEpoch,
                                             List<KVCommand<AetherKey>> commands,
                                             GenerationReason reason,
                                             ClusterGenerationSnapshot previous,
                                             ClusterGenerationSnapshot next,
                                             Set<NodeId> attemptedNodeIds) {
        if (commands.isEmpty()) {
            fsm.dispatch(new CommandsApplied(reason, startEpoch, previous, next));
            return;
        }
        cluster.apply(commands).onSuccess(_ -> fsm.dispatch(new CommandsApplied(reason, startEpoch, previous, next)))
                     .onFailure(_ -> fsm.dispatch(new CommandsApplyFailed(startEpoch, attemptedNodeIds)));
    }

    private SignalOutcome processSignal(Epoch startEpoch, ClusterGenerationSnapshot snapshot, HealthSignal signal) {
        if (!externalLeaderSupplier.getAsBoolean()) {return SignalOutcome.unchanged(snapshot, TermAdvance.STABLE);}
        if (isFencedOut(startEpoch, snapshot, signal)) {return SignalOutcome.unchanged(snapshot, TermAdvance.STABLE);}
        var afterTerm = reconcileLeaderTermIfChanged(snapshot);
        return switch (signal){
            case HealthSignal.PingTimeout ping -> handlePingTimeout(afterTerm.snapshot(), ping, afterTerm.termAdvance());
            case HealthSignal.SwimHint swim -> handleSwimHint(afterTerm.snapshot(), swim, afterTerm.termAdvance());
            case HealthSignal.QuicDisconnect quic -> handleQuicDisconnect(afterTerm.snapshot(),
                                                                          quic,
                                                                          afterTerm.termAdvance());
            case HealthSignal.DrainCompleted drain -> handleDrainCompleted(afterTerm.snapshot(),
                                                                           drain,
                                                                           afterTerm.termAdvance());
            case HealthSignal.GovernorAnnounced announced -> handleGovernorAnnounced(afterTerm.snapshot(),
                                                                                     announced,
                                                                                     afterTerm.termAdvance());
            case HealthSignal.CommunityDissolved dissolved -> handleCommunityDissolved(afterTerm.snapshot(),
                                                                                       dissolved,
                                                                                       afterTerm.termAdvance());
            case HealthSignal.SpokesmanAssignmentFailed failed -> handleSpokesmanAssignmentFailed(afterTerm.snapshot(),
                                                                                                  failed,
                                                                                                  afterTerm.termAdvance());
            case HealthSignal.OperatorAction action -> handleOperatorAction(afterTerm.snapshot(),
                                                                            action.intent(),
                                                                            afterTerm.termAdvance());
            case HealthSignal.RemoteSwimHint remote -> handleRemoteSwimHint(afterTerm.snapshot(),
                                                                            remote,
                                                                            afterTerm.termAdvance());
            case HealthSignal.RemoteConnectivity remote -> handleRemoteConnectivity(afterTerm.snapshot(),
                                                                                    remote,
                                                                                    afterTerm.termAdvance());
        };
    }

    private boolean isFencedOut(Epoch startEpoch, ClusterGenerationSnapshot snapshot, HealthSignal signal) {
        var observedAt = signal.observedAt();
        if (observedAt.equals(Epoch.ZERO)) {return false;}
        if (observedAt.rabiaTerm() < startEpoch.rabiaTerm()) {
            log.trace("Dropping pre-leader-change signal {} observedAt={} startEpoch={}",
                      signal.getClass().getSimpleName(),
                      observedAt,
                      startEpoch);
            return true;
        }
        var current = snapshot.epoch();
        if (observedAt.rabiaTerm() == current.rabiaTerm() && observedAt.localCounter() < current.localCounter() - LATE_SIGNAL_WINDOW) {
            log.trace("Dropping stale-counter signal {} observedAt={} currentEpoch={}",
                      signal.getClass().getSimpleName(),
                      observedAt,
                      current);
            return true;
        }
        return false;
    }

    private TermReconciliation reconcileLeaderTermIfChanged(ClusterGenerationSnapshot snapshot) {
        var currentTerm = rabiaTermSupplier.get();
        if (snapshot.rabiaTerm() < currentTerm) {
            log.info("HealthReconciler detected new Rabia term {} (was {}); resetting to (term,0)",
                     currentTerm,
                     snapshot.rabiaTerm());
            return new TermReconciliation(empty(currentTerm), TermAdvance.ADVANCED);
        }
        pruneMapsAgainstCore(snapshot.coreMembers().keySet());
        return new TermReconciliation(snapshot, TermAdvance.STABLE);
    }

    private void pruneMapsAgainstCore(Set<NodeId> liveCore) {
        peerObservationStore.retainPingMisses(liveCore);
        swimHints.keySet().retainAll(liveCore);
        pendingRemovals.retainAll(liveCore);
    }

    private SignalOutcome handlePingTimeout(ClusterGenerationSnapshot current,
                                            HealthSignal.PingTimeout ping,
                                            TermAdvance termAdvance) {
        var nodeId = ping.nodeId();
        var missed = peerObservationStore.recordPingMiss(nodeId);
        // Defense-in-depth: independent of SWIM. A sustained run of QUIC ping-misses is
        // sufficient to flip swimHints[FAULTY] without needing a `QuicDisconnect` to fire.
        // Mirrors the call shape in `handleQuicDisconnect`. The idempotency guard inside
        // `promoteToFaultyIfThresholdReached` makes repeat calls safe.
        promoteToFaultyIfThresholdReached(nodeId, missed);
        return Option.option(current.coreMembers().get(nodeId)).filter(_ -> !pendingRemovals.contains(nodeId))
                            .map(member -> applyPingTimeoutDecision(current, nodeId, member, missed, termAdvance))
                            .or(() -> SignalOutcome.unchanged(current, termAdvance));
    }

    private SignalOutcome applyPingTimeoutDecision(ClusterGenerationSnapshot current,
                                                   NodeId nodeId,
                                                   CoreMember member,
                                                   int missed,
                                                   TermAdvance termAdvance) {
        if (shouldEvict(missed, member, nodeId)) {return evictNode(current,
                                                                   nodeId,
                                                                   member,
                                                                   GenerationReason.MEMBER_REMOVED,
                                                                   termAdvance);}
        if (shouldMarkSuspected(missed, member)) {return markSuspectedInMemory(current, nodeId, termAdvance);}
        return SignalOutcome.unchanged(current, termAdvance);
    }

    private boolean shouldEvict(int missed, CoreMember member, NodeId nodeId) {
        return missed >= DEFAULT_REMOVE_THRESHOLD && effectiveHint(nodeId) == HealthHint.FAULTY && member.lifecycle() == NodeLifecycleState.ON_DUTY;
    }

    private boolean shouldMarkSuspected(int missed, CoreMember member) {
        return missed >= DEFAULT_SUSPECT_THRESHOLD && member.lifecycle() == NodeLifecycleState.ON_DUTY;
    }

    private SignalOutcome handleSwimHint(ClusterGenerationSnapshot current,
                                         HealthSignal.SwimHint swim,
                                         TermAdvance termAdvance) {
        putHint(swim.nodeId(), swim.state());
        return switch (swim.state()){
            case SUSPECTED, FAULTY -> markSuspectedInMemory(current, swim.nodeId(), termAdvance);
            case HEALTHY -> clearSuspectedInMemory(current, swim.nodeId(), termAdvance);
        };
    }

    private SignalOutcome handleQuicDisconnect(ClusterGenerationSnapshot current,
                                               HealthSignal.QuicDisconnect quic,
                                               TermAdvance termAdvance) {
        if (!current.coreMembers().containsKey(quic.nodeId())) {return SignalOutcome.unchanged(current, termAdvance);}
        var missed = peerObservationStore.recordPingMiss(quic.nodeId());
        log.debug("QUIC disconnect from {} (counted as advisory miss {})", quic.nodeId(), missed);
        promoteToFaultyIfThresholdReached(quic.nodeId(), missed);
        return SignalOutcome.unchanged(current, termAdvance);
    }

    /// Defense-in-depth: SWIM is the primary signal that flips `swimHints` to FAULTY, but if
    /// SWIM is delayed or wedged a sustained run of QUIC ping-misses still indicates the peer
    /// is unreachable. Once the consecutive-miss counter on `peerObservationStore` reaches
    /// `autoHealConfig.quicMissPromotionThreshold()`, force `swimHints[peer] = FAULTY` so the
    /// existing `shouldEvict` / auto-heal path can act. Idempotent — repeat promotions on
    /// subsequent misses are no-ops (no duplicate log entries). The counter is reset whenever
    /// SWIM reports HEALTHY (`applyClearSuspected`) or QUIC reports CONNECTED
    /// (`handleRemoteConnectivity`).
    @Contract private void promoteToFaultyIfThresholdReached(NodeId peer, int missed) {
        if (missed < autoHealConfig.quicMissPromotionThreshold()) {return;}
        if (effectiveHint(peer) == HealthHint.FAULTY) {return;}
        putHint(peer, HealthHint.FAULTY);
        log.warn("Promoting peer {} to FAULTY based on {} sustained QUIC ping-misses (threshold={})",
                 peer, missed, autoHealConfig.quicMissPromotionThreshold());
    }

    private SignalOutcome handleDrainCompleted(ClusterGenerationSnapshot current,
                                               HealthSignal.DrainCompleted drain,
                                               TermAdvance termAdvance) {
        return Option.option(current.coreMembers().get(drain.nodeId())).filter(member -> member.lifecycle() != NodeLifecycleState.DECOMMISSIONED)
                            .map(member -> performDrainCompletion(current,
                                                                  drain.nodeId(),
                                                                  member,
                                                                  termAdvance))
                            .or(() -> logAndUnchanged(current,
                                                      drain.nodeId(),
                                                      termAdvance));
    }

    private SignalOutcome logAndUnchanged(ClusterGenerationSnapshot current, NodeId nodeId, TermAdvance termAdvance) {
        log.debug("DrainCompleted({}) ignored — member absent or already decommissioned", nodeId);
        return SignalOutcome.unchanged(current, termAdvance);
    }

    private SignalOutcome performDrainCompletion(ClusterGenerationSnapshot current,
                                                 NodeId nodeId,
                                                 CoreMember member,
                                                 TermAdvance termAdvance) {
        log.info("DrainCompleted({}) — writing DECOMMISSIONED via single-writer reconciler", nodeId);
        return evictNode(current, nodeId, member, GenerationReason.MEMBER_REMOVED, termAdvance);
    }

    private SignalOutcome handleGovernorAnnounced(ClusterGenerationSnapshot current,
                                                  HealthSignal.GovernorAnnounced announced,
                                                  TermAdvance termAdvance) {
        if (current.communities().containsKey(announced.communityId())) {return bumpCounter(current,
                                                                                            GenerationReason.HEALTH_CHANGE,
                                                                                            termAdvance);}
        return assignNewCommunity(current, announced.communityId(), termAdvance);
    }

    private SignalOutcome handleCommunityDissolved(ClusterGenerationSnapshot current,
                                                   HealthSignal.CommunityDissolved dissolved,
                                                   TermAdvance termAdvance) {
        return Option.option(current.communities().get(dissolved.communityId())).map(community -> dissolveCommunity(current,
                                                                                                                    dissolved.communityId(),
                                                                                                                    community,
                                                                                                                    termAdvance))
                            .or(() -> SignalOutcome.unchanged(current, termAdvance));
    }

    private SignalOutcome dissolveCommunity(ClusterGenerationSnapshot current,
                                            String communityId,
                                            CommunitySummary community,
                                            TermAdvance termAdvance) {
        var survivors = coreNodesFromSnapshot(current);
        if (survivors.isEmpty()) {
            log.warn("CommunityDissolved({}) — no surviving core nodes to absorb partitions", communityId);
            return SignalOutcome.unchanged(current, termAdvance);
        }
        var commands = buildDissolveCommands(current, communityId, community.partitions(), survivors);
        return applyCommandsWithCounterBump(current, commands, GenerationReason.COMMUNITY_DISSOLVED, Set.of(), termAdvance);
    }

    private SignalOutcome handleSpokesmanAssignmentFailed(ClusterGenerationSnapshot current,
                                                          HealthSignal.SpokesmanAssignmentFailed failed,
                                                          TermAdvance termAdvance) {
        var survivors = coreNodesFromSnapshot(current).stream()
                                             .filter(id -> !id.equals(failed.coreNodeId()))
                                             .toList();
        if (survivors.isEmpty()) {
            log.warn("SpokesmanAssignmentFailed({}, {}) — no surviving core nodes",
                     failed.coreNodeId(),
                     failed.affectedCommunities());
            return SignalOutcome.unchanged(current, termAdvance);
        }
        var commands = buildReassignCommands(current, failed.coreNodeId(), failed.affectedCommunities(), survivors);
        return applyCommandsWithCounterBump(current,
                                            commands,
                                            GenerationReason.SPOKESMAN_REBALANCED,
                                            Set.of(),
                                            termAdvance);
    }

    private SignalOutcome handleOperatorAction(ClusterGenerationSnapshot current,
                                               OperatorIntent intent,
                                               TermAdvance termAdvance) {
        return switch (intent){
            case OperatorIntent.RemoveMember remove -> operatorRemove(current, remove.nodeId(), termAdvance);
            case OperatorIntent.SetDesiredSize resize -> operatorSetDesiredSize(current, resize.size(), termAdvance);
            case OperatorIntent.DrainMember drain -> operatorDrain(current, drain.nodeId(), termAdvance);
        };
    }

    private SignalOutcome operatorRemove(ClusterGenerationSnapshot current, NodeId nodeId, TermAdvance termAdvance) {
        return Option.option(current.coreMembers().get(nodeId)).map(member -> applyOperatorRemoveDecision(current,
                                                                                                          nodeId,
                                                                                                          member,
                                                                                                          termAdvance))
                            .or(() -> SignalOutcome.unchanged(current, termAdvance));
    }

    private SignalOutcome applyOperatorRemoveDecision(ClusterGenerationSnapshot current,
                                                      NodeId nodeId,
                                                      CoreMember member,
                                                      TermAdvance termAdvance) {
        if (isDrainingOrTerminal(member)) {
            log.debug("operatorRemove({}) ignored — already {} (await DrainCompleted)", nodeId, member.lifecycle());
            return SignalOutcome.unchanged(current, termAdvance);
        }
        return writeDrainingAtom(current, nodeId, member, GenerationReason.MEMBER_REMOVED, termAdvance);
    }

    private SignalOutcome operatorSetDesiredSize(ClusterGenerationSnapshot current, int newSize, TermAdvance termAdvance) {
        if (current.desiredCoreSize() == newSize) {return SignalOutcome.unchanged(current, termAdvance);}
        return updateAndBump(current,
                             s -> s.withDesiredCoreSize(newSize),
                             GenerationReason.CLUSTER_SIZE_CHANGED,
                             termAdvance);
    }

    private SignalOutcome operatorDrain(ClusterGenerationSnapshot current, NodeId nodeId, TermAdvance termAdvance) {
        return Option.option(current.coreMembers().get(nodeId)).map(member -> applyOperatorDrainDecision(current,
                                                                                                         nodeId,
                                                                                                         member,
                                                                                                         termAdvance))
                            .or(() -> SignalOutcome.unchanged(current, termAdvance));
    }

    private SignalOutcome applyOperatorDrainDecision(ClusterGenerationSnapshot current,
                                                     NodeId nodeId,
                                                     CoreMember member,
                                                     TermAdvance termAdvance) {
        if (isDrainingOrTerminal(member)) {
            log.debug("operatorDrain({}) ignored — already {}", nodeId, member.lifecycle());
            return SignalOutcome.unchanged(current, termAdvance);
        }
        return writeDrainingAtom(current, nodeId, member, GenerationReason.HEALTH_CHANGE, termAdvance);
    }

    private static boolean isDrainingOrTerminal(CoreMember member) {
        var state = member.lifecycle();
        return state == NodeLifecycleState.DRAINING || state == NodeLifecycleState.DECOMMISSIONED || state == NodeLifecycleState.SHUTTING_DOWN;
    }

    private SignalOutcome handleRemoteSwimHint(ClusterGenerationSnapshot current,
                                               HealthSignal.RemoteSwimHint remote,
                                               TermAdvance termAdvance) {
        if (!current.coreMembers().containsKey(remote.peer())) {return SignalOutcome.unchanged(current, termAdvance);}
        if (isStaleObservation(remote.producedAtMs())) {
            log.trace("Dropping stale RemoteSwimHint observer={} peer={} producedAtMs={} (TTL={})",
                      remote.observer(), remote.peer(), remote.producedAtMs(),
                      autoHealConfig.staleObservationTtl());
            return SignalOutcome.unchanged(current, termAdvance);
        }
        peerObservationReducer.recordHint(remote.observer(), remote.peer(), remote.hint(), remote.observedAtEpoch());
        var totalObservers = current.coreMembers().size();
        var resolved = peerObservationReducer.resolvedHint(remote.peer(), totalObservers);
        var currentHint = Option.option(current.coreMembers().get(remote.peer())).map(CoreMember::healthHint)
                                       .or(HealthHint.HEALTHY);
        if (resolved == currentHint) {return SignalOutcome.unchanged(current, termAdvance);}
        return handleSwimHint(current,
                              new HealthSignal.SwimHint(remote.peer(), resolved, remote.observedAtEpoch()),
                              termAdvance);
    }

    private SignalOutcome handleRemoteConnectivity(ClusterGenerationSnapshot current,
                                                   HealthSignal.RemoteConnectivity remote,
                                                   TermAdvance termAdvance) {
        if (!current.coreMembers().containsKey(remote.peer())) {return SignalOutcome.unchanged(current, termAdvance);}
        if (isStaleObservation(remote.producedAtMs())) {
            log.trace("Dropping stale RemoteConnectivity observer={} peer={} producedAtMs={} (TTL={})",
                      remote.observer(), remote.peer(), remote.producedAtMs(),
                      autoHealConfig.staleObservationTtl());
            return SignalOutcome.unchanged(current, termAdvance);
        }
        return switch (remote.state()){
            case DISCONNECTED, STALE -> handleQuicDisconnect(current,
                                                             new HealthSignal.QuicDisconnect(remote.peer(),
                                                                                             remote.observedAtEpoch()),
                                                             termAdvance);
            case CONNECTED -> handleRemoteConnected(current, remote.peer(), termAdvance);
        };
    }

    /// Reset the consecutive QUIC ping-miss counter on a fresh CONNECTED report so a peer that
    /// recovers does not accrue toward the FAULTY-promotion threshold from prior outages.
    /// Pairs with [`#promoteToFaultyIfThresholdReached`].
    ///
    /// Also self-heals sticky SUSPECTED state: SWIM may transiently mark a peer SUSPECTED
    /// during cluster boot (probes fire before the peer's SWIM transport is ready). Without
    /// this clear path, SUSPECTED only lifts on a fresh `SwimHint(HEALTHY)`, which SWIM may
    /// never re-emit for a peer it considers stable. A live QUIC connection with zero
    /// consecutive misses is positive liveness evidence — clear the SUSPECTED hint and the
    /// in-memory snapshot's `healthHint=SUSPECTED` so `MembershipView.healthyOnDutyCount`
    /// reflects reality. FAULTY remains sticky until SWIM-side `revivalGrace` clears it.
    private SignalOutcome handleRemoteConnected(ClusterGenerationSnapshot current,
                                                NodeId peer,
                                                TermAdvance termAdvance) {
        peerObservationStore.clearPingMisses(peer);
        if (effectiveHint(peer) == HealthHint.SUSPECTED) {
            log.debug("Clearing sticky SUSPECTED for peer {} on positive QUIC liveness (CONNECTED, missed=0)", peer);
            swimHints.remove(peer);
            return clearSuspectedInMemory(current, peer, termAdvance);
        }
        return SignalOutcome.unchanged(current, termAdvance);
    }

    /// Whether a remote observation is older than the configured staleness TTL.
    /// `producedAtMs == 0L` is treated as "no timestamp available" and accepted —
    /// preserves backward compatibility with synthetic in-process signals (test
    /// fixtures, Q1 in-process emits) that never traversed the wire.
    private boolean isStaleObservation(long producedAtMs) {
        if (producedAtMs == 0L) {return false;}
        var cutoff = nowMs() - autoHealConfig.staleObservationTtl().millis();
        return producedAtMs < cutoff;
    }

    private SignalOutcome writeDrainingAtom(ClusterGenerationSnapshot current,
                                            NodeId nodeId,
                                            CoreMember member,
                                            GenerationReason reason,
                                            TermAdvance termAdvance) {
        var draining = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING,
                                                             nowMs(),
                                                             member.host(),
                                                             member.port(),
                                                             current.epoch(),
                                                             hlcClock.now(),
                                                             member.provisioningSource());
        var commands = List.<KVCommand<AetherKey>>of(new KVCommand.Put<AetherKey, AetherValue>(NodeLifecycleKey.nodeLifecycleKey(nodeId),
                                                                                               draining));
        return applyCommandsWithCounterBump(current, commands, reason, Set.of(), termAdvance);
    }

    private SignalOutcome markSuspectedInMemory(ClusterGenerationSnapshot current, NodeId nodeId, TermAdvance termAdvance) {
        return Option.option(current.coreMembers().get(nodeId)).filter(member -> member.healthHint() != HealthHint.SUSPECTED)
                            .map(member -> {
                                log.debug("Marking peer {} healthHint=SUSPECTED (was {})", nodeId, member.healthHint());
                                return applyHealthHintChange(current,
                                                             nodeId,
                                                             member.withHealthHint(HealthHint.SUSPECTED),
                                                             termAdvance);
                            })
                            .or(() -> SignalOutcome.unchanged(current, termAdvance));
    }

    private SignalOutcome clearSuspectedInMemory(ClusterGenerationSnapshot current, NodeId nodeId, TermAdvance termAdvance) {
        return Option.option(current.coreMembers().get(nodeId)).filter(member -> member.healthHint() != HealthHint.HEALTHY)
                            .map(member -> applyClearSuspected(current, nodeId, member, termAdvance))
                            .or(() -> SignalOutcome.unchanged(current, termAdvance));
    }

    private SignalOutcome applyClearSuspected(ClusterGenerationSnapshot current,
                                              NodeId nodeId,
                                              CoreMember member,
                                              TermAdvance termAdvance) {
        log.debug("Clearing peer {} healthHint=HEALTHY (was {})", nodeId, member.healthHint());
        peerObservationStore.clearPingMisses(nodeId);
        return applyHealthHintChange(current, nodeId, member.withHealthHint(HealthHint.HEALTHY), termAdvance);
    }

    private SignalOutcome applyHealthHintChange(ClusterGenerationSnapshot current,
                                                NodeId nodeId,
                                                CoreMember replacement,
                                                TermAdvance termAdvance) {
        var updatedMap = replaceMember(current.coreMembers(), nodeId, replacement);
        return updateAndBump(current, s -> s.withCoreMembers(updatedMap), GenerationReason.HEALTH_CHANGE, termAdvance);
    }

    private SignalOutcome evictNode(ClusterGenerationSnapshot current,
                                    NodeId nodeId,
                                    CoreMember member,
                                    GenerationReason reason,
                                    TermAdvance termAdvance) {
        // Theme C — split-brain protection: only the majority-side reconciler may write
        // DECOMMISSIONED atoms. A minority partition's leader-projection that sees a peer as
        // unreachable would otherwise erase healthy metadata after the partition heals. The
        // guard is intentionally based on the projected snapshot (single source of truth) so
        // it survives leader handoff and does not require a cross-component subscription.
        if (!quorumActive(current)) {
            log.warn("Skipping eviction of {} — quorum not active in projected snapshot "
                     + "(onDuty={}, desired={}). Possible minority partition; deferring write.",
                     nodeId,
                     onDutyCount(current),
                     current.desiredCoreSize());
            return SignalOutcome.unchanged(current, termAdvance);
        }
        pendingRemovals.add(nodeId);
        var leftValue = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DECOMMISSIONED,
                                                              nowMs(),
                                                              member.host(),
                                                              member.port(),
                                                              current.epoch(),
                                                              hlcClock.now(),
                                                              member.provisioningSource());
        var commands = new ArrayList<KVCommand<AetherKey>>();
        commands.add(new KVCommand.Put<AetherKey, AetherValue>(NodeLifecycleKey.nodeLifecycleKey(nodeId), leftValue));
        commands.addAll(handlePartitionsOf(current, nodeId));
        return applyCommandsWithCounterBump(current, commands, reason, Set.of(nodeId), termAdvance);
    }

    /// Quorum-activity gate (Theme C). Returns true iff the projected snapshot reports a
    /// majority of `desiredCoreSize` core members in `ON_DUTY` state. Used to guard
    /// destructive lifecycle writes (`DECOMMISSIONED`) so a minority-partition reconciler
    /// cannot eject a healthy peer based on stale connectivity observations.
    ///
    /// Edge case: when `desiredCoreSize == 0` (uninitialized snapshot) the guard returns
    /// false — at that point no quorum has ever existed, and silently allowing the write
    /// would mask configuration bugs.
    private boolean quorumActive(ClusterGenerationSnapshot current) {
        var desired = current.desiredCoreSize();
        if (desired <= 0) {return false;}
        var majority = (desired / 2) + 1;
        return onDutyCount(current) >= majority;
    }

    private long onDutyCount(ClusterGenerationSnapshot current) {
        return current.coreMembers()
                      .values()
                      .stream()
                      .filter(member -> member.lifecycle() == NodeLifecycleState.ON_DUTY)
                      .count();
    }

    private List<KVCommand<AetherKey>> handlePartitionsOf(ClusterGenerationSnapshot current, NodeId departedNode) {
        var survivors = coreNodesFromSnapshot(current).stream()
                                             .filter(id -> !id.equals(departedNode))
                                             .toList();
        if (survivors.isEmpty()) {return List.of();}
        var commands = new ArrayList<KVCommand<AetherKey>>();
        var partitionsOwnedByDeparted = current.partitions().values()
                                                          .stream()
                                                          .filter(p -> departedNode.equals(p.ownerNodeId()))
                                                          .toList();
        var pointer = 0;
        for (var partition : partitionsOwnedByDeparted) {
            var target = survivors.get(pointer % survivors.size());
            var value = DhtPartitionOwnershipValue.dhtPartitionOwnershipValue(target,
                                                                              CORE_COMMUNITY_ID,
                                                                              current.epoch(),
                                                                              partition.ownershipTerm() + 1,
                                                                              hlcClock.now());
            commands.add(new KVCommand.Put<AetherKey, AetherValue>(DhtPartitionOwnershipKey.dhtPartitionOwnershipKey(partition.partitionId()),
                                                                   value));
            pointer++;
        }
        return commands;
    }

    private SignalOutcome assignNewCommunity(ClusterGenerationSnapshot current, String communityId, TermAdvance termAdvance) {
        var survivors = coreNodesFromSnapshot(current);
        if (survivors.isEmpty()) {
            log.warn("GovernorAnnounced({}) — no surviving core nodes; deferring spokesman assignment", communityId);
            return SignalOutcome.unchanged(current, termAdvance);
        }
        var loads = computeSpokesmanLoad(current);
        var target = selectLeastLoadedCoreNode(survivors, loads);
        var existing = existingSpokesmanCommunities(current, target);
        var value = SpokesmanValue.spokesmanValue(appendIfAbsent(existing, communityId),
                                                  current.epoch(),
                                                  hlcClock.now(),
                                                  loads.getOrDefault(target, 0) + 1L);
        var commands = List.<KVCommand<AetherKey>>of(new KVCommand.Put<AetherKey, AetherValue>(SpokesmanKey.spokesmanKey(target),
                                                                                               value));
        return applyCommandsWithCounterBump(current, commands, GenerationReason.COMMUNITY_FORMED, Set.of(), termAdvance);
    }

    private List<KVCommand<AetherKey>> buildDissolveCommands(ClusterGenerationSnapshot current,
                                                             String communityId,
                                                             Set<String> partitionIds,
                                                             List<NodeId> survivors) {
        var commands = new ArrayList<KVCommand<AetherKey>>();
        var sortedPartitions = new ArrayList<>(partitionIds);
        sortedPartitions.sort(Comparator.naturalOrder());
        var pointer = 0;
        for (var partitionId : sortedPartitions) {
            var target = survivors.get(pointer % survivors.size());
            var partition = current.partitions().get(partitionId);
            var nextTerm = partition == null
                          ? 1L
                          : partition.ownershipTerm() + 1;
            var value = DhtPartitionOwnershipValue.dhtPartitionOwnershipValue(target,
                                                                              CORE_COMMUNITY_ID,
                                                                              current.epoch(),
                                                                              nextTerm,
                                                                              hlcClock.now());
            commands.add(new KVCommand.Put<AetherKey, AetherValue>(DhtPartitionOwnershipKey.dhtPartitionOwnershipKey(partitionId),
                                                                   value));
            pointer++;
        }
        commands.addAll(removeCommunityFromAllSpokesmen(current, communityId));
        return commands;
    }

    private List<KVCommand<AetherKey>> removeCommunityFromAllSpokesmen(ClusterGenerationSnapshot current,
                                                                       String communityId) {
        var commands = new ArrayList<KVCommand<AetherKey>>();
        for (var coreNodeId : coreNodesFromSnapshot(current)) {
            var existing = existingSpokesmanCommunities(current, coreNodeId);
            if (!existing.contains(communityId)) {continue;}
            var remaining = existing.stream().filter(id -> !id.equals(communityId))
                                           .toList();
            var value = SpokesmanValue.spokesmanValue(remaining, current.epoch(), hlcClock.now(), 0L);
            commands.add(new KVCommand.Put<AetherKey, AetherValue>(SpokesmanKey.spokesmanKey(coreNodeId), value));
        }
        return commands;
    }

    private List<KVCommand<AetherKey>> buildReassignCommands(ClusterGenerationSnapshot current,
                                                             NodeId failedCoreNode,
                                                             List<String> affectedCommunities,
                                                             List<NodeId> survivors) {
        var loads = computeSpokesmanLoad(current);
        var pointer = 0;
        var accumulated = new LinkedHashMap<NodeId, List<String>>();
        for (var communityId : affectedCommunities) {
            var target = survivors.get(pointer % survivors.size());
            var existingForTarget = accumulated.computeIfAbsent(target,
                                                                id -> new ArrayList<>(existingSpokesmanCommunities(current,
                                                                                                                   id)));
            if (!existingForTarget.contains(communityId)) {
                existingForTarget.add(communityId);
                loads.merge(target, 1, Integer::sum);
            }
            pointer++;
        }
        var commands = new ArrayList<KVCommand<AetherKey>>();
        accumulated.forEach((coreNodeId, communities) -> commands.add(spokesmanPutCommand(coreNodeId,
                                                                                          communities,
                                                                                          current.epoch())));
        commands.add(clearFailedFlag(failedCoreNode, current));
        return commands;
    }

    private KVCommand<AetherKey> spokesmanPutCommand(NodeId coreNodeId, List<String> communities, Epoch epoch) {
        var value = SpokesmanValue.spokesmanValue(List.copyOf(communities), epoch, hlcClock.now(), 1L);
        return new KVCommand.Put<AetherKey, AetherValue>(SpokesmanKey.spokesmanKey(coreNodeId), value);
    }

    private KVCommand<AetherKey> clearFailedFlag(NodeId failedCoreNode, ClusterGenerationSnapshot current) {
        var reset = SpokesmanValue.spokesmanValue(List.of(),
                                                  current.epoch(),
                                                  hlcClock.now(),
                                                  1L)
        .withStatus(SpokesmanStatus.ASSIGNED);
        return new KVCommand.Put<AetherKey, AetherValue>(SpokesmanKey.spokesmanKey(failedCoreNode), reset);
    }

    private SignalOutcome applyCommandsWithCounterBump(ClusterGenerationSnapshot current,
                                                       List<KVCommand<AetherKey>> commands,
                                                       GenerationReason reason,
                                                       Set<NodeId> attemptedNodeIds,
                                                       TermAdvance termAdvance) {
        if (commands.isEmpty()) {return bumpCounter(current, reason, termAdvance);}
        var next = current.withBumpedCounter(reason);
        return SignalOutcome.changed(next, reason, commands, attemptedNodeIds, termAdvance);
    }

    private SignalOutcome bumpCounter(ClusterGenerationSnapshot current, GenerationReason reason, TermAdvance termAdvance) {
        return updateAndBump(current, UnaryOperator.identity(), reason, termAdvance);
    }

    private SignalOutcome updateAndBump(ClusterGenerationSnapshot current,
                                        UnaryOperator<ClusterGenerationSnapshot> transform,
                                        GenerationReason reason,
                                        TermAdvance termAdvance) {
        var next = transform.apply(current).withBumpedCounter(reason);
        return SignalOutcome.changed(next, reason, List.of(), Set.of(), termAdvance);
    }

    private Option<ReseedApplied> computeReseedResult(ClusterGenerationSnapshot current,
                                                      ClusterGenerationSnapshot fresh) {
        if (current.coreMembers().equals(fresh.coreMembers()) && current.desiredCoreSize() == fresh.desiredCoreSize()) {return Option.none();}
        var reason = fresh.coreMembers().size() >= current.coreMembers().size()
                    ? GenerationReason.MEMBER_ADDED
                    : GenerationReason.MEMBER_REMOVED;
        var next = current.withCoreMembers(fresh.coreMembers()).withDesiredCoreSize(fresh.desiredCoreSize())
                                          .withBumpedCounter(reason);
        return Option.some(new ReseedApplied(next, reason, List.of(), Set.of()));
    }

    private List<NodeId> coreNodesFromSnapshot(ClusterGenerationSnapshot snapshot) {
        return snapshot.coreMembers().values()
                                   .stream()
                                   .filter(m -> m.lifecycle() != NodeLifecycleState.DECOMMISSIONED)
                                   .map(CoreMember::nodeId)
                                   .sorted()
                                   .toList();
    }

    private static Map<NodeId, CoreMember> replaceMember(Map<NodeId, CoreMember> source,
                                                         NodeId nodeId,
                                                         CoreMember replacement) {
        var copy = new LinkedHashMap<>(source);
        copy.put(nodeId, replacement);
        return Map.copyOf(copy);
    }

    private Map<NodeId, Integer> computeSpokesmanLoad(ClusterGenerationSnapshot snapshot) {
        var loads = new HashMap<NodeId, Integer>();
        coreNodesFromSnapshot(snapshot).forEach(id -> loads.put(id, 0));
        snapshot.communities().values()
                            .forEach(c -> c.assignedSpokesman().onPresent(id -> loads.merge(id, 1, Integer::sum)));
        return loads;
    }

    private NodeId selectLeastLoadedCoreNode(List<NodeId> survivors, Map<NodeId, Integer> loads) {
        return survivors.stream().min(Comparator.<NodeId, Integer>comparing(id -> loads.getOrDefault(id, 0))
                                                .thenComparing(Comparator.naturalOrder()))
                               .orElse(survivors.getFirst());
    }

    private List<String> existingSpokesmanCommunities(ClusterGenerationSnapshot snapshot, NodeId coreNodeId) {
        return snapshot.communities().values()
                                   .stream()
                                   .filter(c -> isAssignedTo(c.assignedSpokesman(),
                                                             coreNodeId))
                                   .map(CommunitySummary::communityId)
                                   .toList();
    }

    private static boolean isAssignedTo(Option<NodeId> assigned, NodeId coreNodeId) {
        return assigned.filter(coreNodeId::equals).isPresent();
    }

    private static List<String> appendIfAbsent(List<String> source, String item) {
        if (source.contains(item)) {return source;}
        var copy = new ArrayList<>(source);
        copy.add(item);
        return List.copyOf(copy);
    }

    /// Whether the membership / cluster-generation moved as a result of processing a signal.
    /// CHANGED implies either KV commands were emitted or the snapshot epoch was bumped in-memory
    /// (and the consumer must transition + emit a GenerationChangedNotice).
    public enum MembershipChange { CHANGED, UNCHANGED }

    /// Whether the leader Rabia term advanced relative to the snapshot's recorded term.
    /// ADVANCED requires the consumer to clear leader-scoped bookkeeping.
    public enum TermAdvance { ADVANCED, STABLE }

    private record SignalOutcome(ClusterGenerationSnapshot nextSnapshot,
                                 GenerationReason reason,
                                 List<KVCommand<AetherKey>> commands,
                                 Set<NodeId> attemptedNodeIds,
                                 MembershipChange membershipChange,
                                 TermAdvance termAdvance) {
        static SignalOutcome changed(ClusterGenerationSnapshot snapshot,
                                     GenerationReason reason,
                                     List<KVCommand<AetherKey>> commands,
                                     Set<NodeId> attemptedNodeIds,
                                     TermAdvance termAdvance) {
            return new SignalOutcome(snapshot,
                                     reason,
                                     commands,
                                     attemptedNodeIds,
                                     MembershipChange.CHANGED,
                                     termAdvance);
        }

        // A term advance forces a CHANGED outcome: `reconcileLeaderTermIfChanged` resets the snapshot
        // to `empty(currentTerm)`, which is itself a real generation change that must be published
        // and have leader-scoped state cleared.
        static SignalOutcome unchanged(ClusterGenerationSnapshot snapshot, TermAdvance termAdvance) {
            return termAdvance == TermAdvance.ADVANCED
                  ? changed(snapshot, GenerationReason.HEALTH_CHANGE, List.of(), Set.of(), termAdvance)
                  : new SignalOutcome(snapshot,
                                      GenerationReason.HEALTH_CHANGE,
                                      List.of(),
                                      Set.of(),
                                      MembershipChange.UNCHANGED,
                                      TermAdvance.STABLE);
        }
    }

    private record TermReconciliation(ClusterGenerationSnapshot snapshot, TermAdvance termAdvance){}

    private record ReseedApplied(ClusterGenerationSnapshot nextSnapshot,
                                 GenerationReason reason,
                                 List<KVCommand<AetherKey>> commands,
                                 Set<NodeId> attemptedNodeIds){}
}
