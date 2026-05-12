// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.health;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterPhaseKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhaseValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.swim.SwimObservation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Lifecycle/phase reconciler — slimmed by spec §9 E.7.
///
/// **E.7 deletions (2026-05-12).** The legacy SWIM-driven lifecycle write path — the entire
/// gate stack (`ObservationAggregator`, `handleAggregatedEdge`, `suppressedByPhase`,
/// `cooldownActive`) and the self-promotion machinery (`signalSelfReady`,
/// `attemptSelfOnDutyWrite`, `SelfOnDutyAtomFactory`, etc.) are gone. The corresponding KV
/// writes now originate exclusively from `MembershipFsm` (operator events via E.4 and SWIM
/// observations via E.5) when `aether.membership.fsm.shadowEnabled=true`.
///
/// **Degenerate flag-off mode.** With `aether.membership.fsm.shadowEnabled=false`,
/// `onSwimObservation` no longer drives lifecycle writes — there is no longer any
/// SWIM-driven path to `DECOMMISSIONED`/`ON_DUTY`. Operator routes still work because they
/// call `requestDrain`/`requestDecommission`/`requestActivate`/`requestFailedDrain` directly,
/// which write through `forceLifecycleWrite`. This is the intentional regression documented in
/// spec §9 E.7: between E.7 and E.8 (flag flip + flag removal) the flag-off mode is degenerate,
/// and production runs with the flag on. See `aether/docs/specs/cluster-membership-fsm-spec.md` §9 E.7.
///
/// **What remains in this class.** Lifecycle (start/stop), operator-write entry points,
/// and the legacy `ClusterPhase` periodic-evaluation + KV write path (gated by `phaseWritesEnabled`
/// per E.6 — when the FSM shadow flag is on, `ClusterPhaseView` is authoritative and these writes
/// are suppressed). E.8 will retire the phase path entirely along with the feature flag.
final class HealthReconcilerImpl implements HealthReconciler {
    private static final Logger log = LoggerFactory.getLogger(HealthReconcilerImpl.class);

    private final NodeId self;
    private final int expectedClusterSize;
    private final Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader;
    private final Supplier<Option<ClusterPhase>> phaseReader;
    private final Supplier<Option<NodeId>> leaderReader;
    private final Supplier<Integer> onDutyCountSupplier;
    private final Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier;
    private final HealthReconcilerConfig config;
    private final RetryScheduler retryScheduler;
    private final BooleanSupplier phaseWritesEnabled;

    private final Object phaseListenerLock = new Object();

    private final AtomicBoolean started = new AtomicBoolean(false);

    private final AtomicReference<ClusterPhase> currentPhase = new AtomicReference<>(ClusterPhase.COLD_BOOT);

    private final AtomicLong stableSinceMs = new AtomicLong(0L);

    private final List<Consumer<ClusterPhaseChanged>> phaseListeners = new CopyOnWriteArrayList<>();

    private HealthReconcilerImpl(NodeId self,
                                 int expectedClusterSize,
                                 Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader,
                                 Supplier<Option<ClusterPhase>> phaseReader,
                                 Supplier<Option<NodeId>> leaderReader,
                                 Supplier<Integer> onDutyCountSupplier,
                                 Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                 HealthReconcilerConfig config,
                                 RetryScheduler retryScheduler,
                                 BooleanSupplier phaseWritesEnabled) {
        this.self = self;
        this.expectedClusterSize = expectedClusterSize;
        this.lifecycleReader = lifecycleReader;
        this.phaseReader = phaseReader;
        this.leaderReader = leaderReader;
        this.onDutyCountSupplier = onDutyCountSupplier;
        this.commandApplier = commandApplier;
        this.config = config;
        this.retryScheduler = retryScheduler;
        this.phaseWritesEnabled = phaseWritesEnabled;
    }

    static HealthReconcilerImpl healthReconcilerImpl(NodeId self,
                                                     int expectedClusterSize,
                                                     Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader,
                                                     Supplier<Option<ClusterPhase>> phaseReader,
                                                     Supplier<Option<NodeId>> leaderReader,
                                                     Supplier<Integer> onDutyCountSupplier,
                                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                                     HealthReconcilerConfig config,
                                                     RetryScheduler retryScheduler,
                                                     BooleanSupplier phaseWritesEnabled) {
        return new HealthReconcilerImpl(self,
                                        expectedClusterSize,
                                        lifecycleReader,
                                        phaseReader,
                                        leaderReader,
                                        onDutyCountSupplier,
                                        commandApplier,
                                        config,
                                        retryScheduler,
                                        phaseWritesEnabled);
    }

    @Override public Promise<Unit> start() {
        if (!started.compareAndSet(false, true)) {return HealthReconcilerError.General.ALREADY_STARTED.promise();}
        currentPhase.set(phaseReader.get().or(ClusterPhase.COLD_BOOT));
        log.info("HealthReconciler started for {} (expectedClusterSize={}, initialPhase={})",
                 self,
                 expectedClusterSize,
                 currentPhase.get());
        schedulePhaseEvaluationTick();
        return Promise.unitPromise();
    }

    /// Periodic phase re-evaluation. `evaluatePhaseTransition` is driven event-style
    /// by `onSwimObservation`, but SWIM only emits on state changes. Once the cluster
    /// settles to all-Healthy steady state, no observations arrive and the COLD_BOOT →
    /// NORMAL (or RECOVERING → NORMAL) transition never fires even though lifecycle KV
    /// already holds quorum ON_DUTY entries. The periodic tick makes the transition
    /// deterministic: ~`stableWindow + tickInterval` after quorum is reached. Set
    /// `phaseEvaluationInterval` to 0ms (`timeSpan(0).millis()`) to disable
    /// (test wiring with `immediateRetryScheduler`).
    @Contract private void schedulePhaseEvaluationTick() {
        if (!started.get()) {return;}
        var interval = config.phaseEvaluationInterval();
        if (interval.nanos() <= 0L) {return;}
        retryScheduler.schedule(this::onPhaseEvaluationTick, interval);
    }

    @Contract private void onPhaseEvaluationTick() {
        if (!started.get()) {return;}
        evaluatePhaseTransition(System.currentTimeMillis());
        schedulePhaseEvaluationTick();
    }

    @Override public Promise<Unit> stop() {
        if (!started.compareAndSet(true, false)) {return HealthReconcilerError.General.NOT_STARTED.promise();}
        log.info("HealthReconciler stopped for {}", self);
        return Promise.unitPromise();
    }

    /// SWIM-observation entry point. Post-E.7 this is a phase-evaluation trigger only —
    /// the legacy aggregator + lifecycle-write path is gone. Lifecycle writes from SWIM
    /// observations now flow exclusively through `MembershipFsm.onSwimObservation` (E.5).
    /// The `observation` argument is retained to preserve the interface contract; its content
    /// is no longer inspected here.
    @Override@Contract public void onSwimObservation(SwimObservation observation) {
        var _unused = observation;
        if (!started.get()) {return;}
        evaluatePhaseTransition(System.currentTimeMillis());
    }

    @SuppressWarnings("unchecked") private static KVCommand<AetherKey> putLifecycleAtom(NodeLifecycleKey key,
                                                                                        NodeLifecycleValue value) {
        return (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<AetherKey, AetherValue>(key, value);
    }

    @SuppressWarnings("unchecked") private static KVCommand<AetherKey> putClusterPhaseAtom(ClusterPhaseKey key,
                                                                                           ClusterPhaseValue value) {
        return (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<AetherKey, AetherValue>(key, value);
    }

    private static NodeLifecycleValue buildLifecycleValue(Option<NodeLifecycleValue> prior,
                                                          NodeLifecycleState newState,
                                                          long nowMs) {
        if (prior.isEmpty()) {return NodeLifecycleValue.nodeLifecycleValue(newState, nowMs);}
        var p = prior.unwrap();
        return AetherValue.NodeLifecycleValue.nodeLifecycleValue(newState,
                                                                 nowMs,
                                                                 p.host(),
                                                                 p.port(),
                                                                 p.observedCoreEpoch(),
                                                                 p.transitionedAt(),
                                                                 p.provisioningSource());
    }

    @Override public Promise<Unit> requestDrain(NodeId target) {
        if (!started.get()) {return HealthReconcilerError.General.NOT_STARTED.promise();}
        return forceLifecycleWrite(target, NodeLifecycleState.DRAINING);
    }

    @Override public Promise<Unit> requestDecommission(NodeId target) {
        if (!started.get()) {return HealthReconcilerError.General.NOT_STARTED.promise();}
        return forceLifecycleWrite(target, NodeLifecycleState.DECOMMISSIONED);
    }

    @Override public Promise<Unit> requestActivate(NodeId target) {
        if (!started.get()) {return HealthReconcilerError.General.NOT_STARTED.promise();}
        return forceLifecycleWrite(target, NodeLifecycleState.ON_DUTY);
    }

    @Override public Promise<Unit> requestFailedDrain(NodeId target) {
        if (!started.get()) {return HealthReconcilerError.General.NOT_STARTED.promise();}
        return forceLifecycleWrite(target, NodeLifecycleState.FAILED_DRAIN);
    }

    private Promise<Unit> forceLifecycleWrite(NodeId target, NodeLifecycleState newState) {
        var nowMs = System.currentTimeMillis();
        var prior = lifecycleReader.apply(target);
        var value = buildLifecycleValue(prior, newState, nowMs);
        var command = putLifecycleAtom(NodeLifecycleKey.nodeLifecycleKey(target), value);
        return commandApplier.apply(List.of(command)).onSuccess(_ -> log.info("HealthReconciler: wrote {} for {}", newState, target))
                                   .mapError(cause -> new HealthReconcilerError.ProposalRejected(target, cause))
                                   .mapToUnit();
    }

    @Override public ClusterPhase phase() {
        return currentPhase.get();
    }

    @Override@Contract public void addPhaseListener(Consumer<ClusterPhaseChanged> listener) {
        phaseListeners.add(listener);
        var current = currentPhase.get();
        notifyListener(listener, ClusterPhaseChanged.clusterPhaseChanged(current, current));
    }

    private void evaluatePhaseTransition(long nowMs) {
        if (!isLeader()) {return;}
        var observed = currentPhase.get();
        var target = computeTargetPhase(observed, nowMs);
        if (target == observed) {return;}
        proposeClusterPhase(target);
    }

    private boolean isLeader() {
        return leaderReader.get().map(self::equals)
                               .or(false);
    }

    private ClusterPhase computeTargetPhase(ClusterPhase current, long nowMs) {
        var onDuty = onDutyCountSupplier.get();
        var leaderPresent = leaderReader.get().isPresent();
        var quorum = quorumThreshold();
        return switch (current){
            case COLD_BOOT -> coldBootTarget(onDuty, leaderPresent, quorum, nowMs);
            case NORMAL -> onDuty <quorum
                          ? ClusterPhase.RECOVERING
                          : resetStableMarker(ClusterPhase.NORMAL, nowMs);
            case RECOVERING -> recoveringTarget(onDuty, quorum, nowMs);
        };
    }

    /// Quorum threshold ⌈(N+1)/2⌉ derived from `expectedClusterSize`. Mirrors the spec
    /// (D.3) so both COLD_BOOT → NORMAL and RECOVERING → NORMAL trigger on quorum, not
    /// on full cluster membership. Floored at 1 for the single-node case.
    private int quorumThreshold() {
        return Math.max(1, expectedClusterSize / 2 + 1);
    }

    private ClusterPhase coldBootTarget(int onDuty, boolean leaderPresent, int quorum, long nowMs) {
        if (!leaderPresent || onDuty <quorum) {
            stableSinceMs.set(0L);
            return ClusterPhase.COLD_BOOT;
        }
        return promoteAfterStable(ClusterPhase.NORMAL, ClusterPhase.COLD_BOOT, nowMs, config.stableWindow());
    }

    private ClusterPhase recoveringTarget(int onDuty, int quorum, long nowMs) {
        if (onDuty <quorum) {
            stableSinceMs.set(0L);
            return ClusterPhase.RECOVERING;
        }
        return promoteAfterStable(ClusterPhase.NORMAL, ClusterPhase.RECOVERING, nowMs, config.recoveryStableWindow());
    }

    private ClusterPhase promoteAfterStable(ClusterPhase promoted, ClusterPhase fallback, long nowMs, TimeSpan window) {
        var since = stableSinceMs.get();
        if (since == 0L) {
            stableSinceMs.set(nowMs);
            return fallback;
        }
        return nowMs - since >= window.millis()
              ? promoted
              : fallback;
    }

    private ClusterPhase resetStableMarker(ClusterPhase phase, long nowMs) {
        stableSinceMs.set(nowMs);
        return phase;
    }

    /// E.6 / spec §7.2: when `phaseWritesEnabled` returns `false` (the FSM shadow flag is
    /// `on`, i.e., `ClusterPhaseView` is authoritative), suppress the KV write entirely.
    /// E.8 will retire this method together with the legacy phase-evaluation path.
    @Contract private void proposeClusterPhase(ClusterPhase target) {
        if (!phaseWritesEnabled.getAsBoolean()) {
            log.debug("HealthReconciler: phase writes disabled (FSM owns ClusterPhase); skipping ClusterPhaseValue={} write",
                      target);
            return;
        }
        var nowMs = System.currentTimeMillis();
        var value = ClusterPhaseValue.clusterPhaseValue(target, nowMs);
        var command = putClusterPhaseAtom(ClusterPhaseKey.SINGLETON, value);
        commandApplier.apply(List.of(command)).onFailure(cause -> log.warn("HealthReconciler: failed to write ClusterPhaseValue={}: {}",
                                                                           target,
                                                                           cause.message()))
                            .onSuccess(_ -> log.info("HealthReconciler: leader wrote ClusterPhaseValue={}", target));
    }

    @Override@Contract public void onClusterPhasePut(ClusterPhaseValue value) {
        synchronized (phaseListenerLock) {
            var previous = currentPhase.getAndSet(value.phase());
            if (previous == value.phase()) {return;}
            stableSinceMs.set(0L);
            var event = ClusterPhaseChanged.clusterPhaseChanged(previous, value.phase());
            phaseListeners.forEach(listener -> notifyListener(listener, event));
            log.info("HealthReconciler: cluster phase transitioned {} -> {}",
                     previous,
                     value.phase());
        }
    }

    private static void notifyListener(Consumer<ClusterPhaseChanged> listener, ClusterPhaseChanged event) {
        Result.lift(Causes::fromThrowable,
                    () -> listener.accept(event))
        .onFailure(cause -> log.warn("HealthReconciler: phase listener failed: {}",
                                     cause.message()));
    }
}
