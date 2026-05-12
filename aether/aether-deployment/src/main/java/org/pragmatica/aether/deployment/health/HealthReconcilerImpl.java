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
import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.swim.SwimObservation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


final class HealthReconcilerImpl implements HealthReconciler {
    private static final Logger log = LoggerFactory.getLogger(HealthReconcilerImpl.class);

    static final int MAX_SELF_ONDUTY_RETRIES = 8;

    static final long INITIAL_SELF_ONDUTY_RETRY_DELAY_MS = 200L;

    static final long MAX_SELF_ONDUTY_RETRY_DELAY_MS = 2_000L;

    private final NodeId self;
    private final int expectedClusterSize;
    private final Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader;
    private final Supplier<Option<ClusterPhase>> phaseReader;
    private final Supplier<Option<NodeId>> leaderReader;
    private final Supplier<Integer> onDutyCountSupplier;
    private final Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier;
    private final HealthReconcilerConfig config;
    private final ObservationAggregator aggregator;
    private final SelfOnDutyAtomFactory selfOnDutyAtomFactory;
    private final RetryScheduler retryScheduler;

    private final Object aggregatorLock = new Object();

    private final Object phaseListenerLock = new Object();

    private final Map<NodeId, Long> lastWriteAt = new ConcurrentHashMap<>();

    private final AtomicBoolean started = new AtomicBoolean(false);

    private final AtomicReference<ClusterPhase> currentPhase = new AtomicReference<>(ClusterPhase.COLD_BOOT);

    private final AtomicLong stableSinceMs = new AtomicLong(0L);

    private final AtomicBoolean selfReady = new AtomicBoolean(false);

    private final AtomicBoolean selfPromoted = new AtomicBoolean(false);

    private final List<Consumer<ClusterPhaseChanged>> phaseListeners = new CopyOnWriteArrayList<>();

    private HealthReconcilerImpl(NodeId self,
                                 int expectedClusterSize,
                                 Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader,
                                 Supplier<Option<ClusterPhase>> phaseReader,
                                 Supplier<Option<NodeId>> leaderReader,
                                 Supplier<Integer> onDutyCountSupplier,
                                 Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                 HealthReconcilerConfig config,
                                 SelfOnDutyAtomFactory selfOnDutyAtomFactory,
                                 RetryScheduler retryScheduler) {
        this.self = self;
        this.expectedClusterSize = expectedClusterSize;
        this.lifecycleReader = lifecycleReader;
        this.phaseReader = phaseReader;
        this.leaderReader = leaderReader;
        this.onDutyCountSupplier = onDutyCountSupplier;
        this.commandApplier = commandApplier;
        this.config = config;
        this.selfOnDutyAtomFactory = selfOnDutyAtomFactory;
        this.retryScheduler = retryScheduler;
        this.aggregator = ObservationAggregator.observationAggregator(config.aggregationWindow());
    }

    static HealthReconcilerImpl healthReconcilerImpl(NodeId self,
                                                     int expectedClusterSize,
                                                     Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader,
                                                     Supplier<Option<ClusterPhase>> phaseReader,
                                                     Supplier<Option<NodeId>> leaderReader,
                                                     Supplier<Integer> onDutyCountSupplier,
                                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                                     HealthReconcilerConfig config,
                                                     SelfOnDutyAtomFactory selfOnDutyAtomFactory,
                                                     RetryScheduler retryScheduler) {
        return new HealthReconcilerImpl(self,
                                        expectedClusterSize,
                                        lifecycleReader,
                                        phaseReader,
                                        leaderReader,
                                        onDutyCountSupplier,
                                        commandApplier,
                                        config,
                                        selfOnDutyAtomFactory,
                                        retryScheduler);
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

    @Override@Contract public void onSwimObservation(SwimObservation observation) {
        if (!started.get()) {return;}
        var nowMs = System.currentTimeMillis();
        var edge = aggregateEdge(observation, nowMs);
        edge.onPresent(stateChanged -> handleAggregatedEdge(stateChanged, nowMs));
        evaluatePhaseTransition(nowMs);
        evaluateSelfPromotion(nowMs);
    }

    @Override@Contract public void signalSelfReady() {
        if (!started.get()) {return;}
        if (selfReady.compareAndSet(false, true)) {
            log.info("HealthReconciler: self-ready signal received for {}", self);
            evaluateSelfPromotion(System.currentTimeMillis());
        }
    }

    private void evaluateSelfPromotion(long nowMs) {
        if (!selfReady.get()) {return;}
        if (selfPromoted.get()) {return;}
        if (selfAlreadyOnDuty()) {
            selfPromoted.compareAndSet(false, true);
            return;
        }
        promoteSelfToOnDuty(nowMs);
    }

    private boolean selfAlreadyOnDuty() {
        return lifecycleReader.apply(self).map(v -> v.state() == NodeLifecycleState.ON_DUTY)
                                    .or(false);
    }

    private void promoteSelfToOnDuty(long nowMs) {
        if (!selfPromoted.compareAndSet(false, true)) {return;}
        log.info("HealthReconciler: promoting self {} to ON_DUTY (phase={})", self, currentPhase.get());
        proposeSelfOnDutyWrite(nowMs);
    }

    @Contract private void proposeSelfOnDutyWrite(long nowMs) {
        attemptSelfOnDutyWrite(nowMs, 1);
    }

    @Contract private void attemptSelfOnDutyWrite(long nowMs, int attempt) {
        if (selfAlreadyOnDuty()) {
            log.debug("HealthReconciler: self {} already ON_DUTY — retry attempt {} short-circuited", self, attempt);
            return;
        }
        var prior = lifecycleReader.apply(self);
        var value = prior.isPresent()
                   ? buildLifecycleValue(prior, NodeLifecycleState.ON_DUTY, nowMs)
                   : selfOnDutyAtomFactory.build(NodeLifecycleState.ON_DUTY, nowMs);
        var command = putLifecycleAtom(NodeLifecycleKey.nodeLifecycleKey(self), value);
        commandApplier.apply(List.of(command)).onFailure(cause -> handleSelfOnDutyFailure(cause, attempt))
                            .onSuccess(_ -> recordWrite(self, NodeLifecycleState.ON_DUTY, nowMs));
    }

    private void handleSelfOnDutyFailure(Cause cause, int attempt) {
        if (!isTransientInactiveRejection(cause)) {
            log.error("HealthReconciler: self {} ON_DUTY write rejected with non-retriable cause on attempt {}: {}",
                      self,
                      attempt,
                      cause.message());
            return;
        }
        if (attempt >= MAX_SELF_ONDUTY_RETRIES) {
            log.error("HealthReconciler: self {} ON_DUTY write exhausted {} attempts — giving up; last cause: {}",
                      self,
                      MAX_SELF_ONDUTY_RETRIES,
                      cause.message());
            return;
        }
        var delay = computeBackoffDelay(attempt);
        log.warn("HealthReconciler: self {} ON_DUTY write rejected (attempt {}/{}); retrying in {}ms — cause: {}",
                 self,
                 attempt,
                 MAX_SELF_ONDUTY_RETRIES,
                 delay.millis(),
                 cause.message());
        retryScheduler.schedule(() -> attemptSelfOnDutyWrite(System.currentTimeMillis(), attempt + 1), delay);
    }

    private static boolean isTransientInactiveRejection(Cause cause) {
        return cause instanceof ConsensusError.NodeInactive;
    }

    private static TimeSpan computeBackoffDelay(int attempt) {
        var raw = INITIAL_SELF_ONDUTY_RETRY_DELAY_MS<<Math.min(attempt - 1, 30);
        var clamped = Math.min(raw, MAX_SELF_ONDUTY_RETRY_DELAY_MS);
        return TimeSpan.timeSpan(clamped).millis();
    }

    private Option<ObservationAggregator.StateChanged> aggregateEdge(SwimObservation observation, long nowMs) {
        var onDutyCount = onDutyCountSupplier.get();
        synchronized (aggregatorLock) {
            return aggregator.onObservation(self, observation, onDutyCount, nowMs);
        }
    }

    private void handleAggregatedEdge(ObservationAggregator.StateChanged edge, long nowMs) {
        var currentLeader = leaderReader.get();
        var leaderUnknown = currentLeader.isEmpty();
        var targetIsLeader = currentLeader.map(l -> l.equals(edge.target())).or(false);
        if (!isLeader() && !targetIsLeader && !leaderUnknown) {
            log.trace("HealthReconciler: follower {} skips lifecycle write for {} -> {} (leader-gated)",
                      self,
                      edge.target(),
                      edge.newState());
            return;
        }
        if (targetIsLeader && !isLeader()) {log.info("HealthReconciler: faulty target {} is current leader; non-leader {} attempting eviction write (self-leader-eviction escape hatch)",
                                                     edge.target(),
                                                     self);}
        if (leaderUnknown && !isLeader()) {log.info("HealthReconciler: leader unknown (handoff window); non-leader {} attempting lifecycle write {} -> {} (consensus de-dups)",
                                                     self,
                                                     edge.target(),
                                                     edge.newState());}
        var target = edge.target();
        if (cooldownActive(target, nowMs)) {
            log.debug("HealthReconciler: cooldown active for {} — suppressing aggregated edge {}",
                      target,
                      edge.newState());
            return;
        }
        if (suppressedByPhase(edge)) {
            log.debug("HealthReconciler: phase {} suppresses {} write for {}",
                      currentPhase.get(),
                      edge.newState(),
                      target);
            return;
        }
        proposeLifecycleWrite(target, edge.newState(), nowMs);
    }

    /// Lifecycle-write suppression (D.3): suppresses DECOMMISSIONED / SHUTTING_DOWN /
    /// DRAINING writes during `COLD_BOOT` only. `RECOVERING` does NOT suppress — the
    /// whole point of the phase split is that real failures during re-establishment
    /// must produce real lifecycle transitions so the NODE_FAILED downstream event
    /// fires and tests do not time out waiting on it.
    private boolean suppressedByPhase(ObservationAggregator.StateChanged edge) {
        if (currentPhase.get() != ClusterPhase.COLD_BOOT) {return false;}
        return edge.newState() == NodeLifecycleState.DECOMMISSIONED || edge.newState() == NodeLifecycleState.SHUTTING_DOWN || edge.newState() == NodeLifecycleState.DRAINING;
    }

    private boolean cooldownActive(NodeId target, long nowMs) {
        return Option.option(lastWriteAt.get(target)).map(lastAt -> nowMs - lastAt <config.cooldown().millis())
                            .or(false);
    }

    @Contract private void proposeLifecycleWrite(NodeId target, NodeLifecycleState newState, long nowMs) {
        var prior = lifecycleReader.apply(target);
        var value = buildLifecycleValue(prior, newState, nowMs);
        var command = putLifecycleAtom(NodeLifecycleKey.nodeLifecycleKey(target), value);
        commandApplier.apply(List.of(command)).onFailure(cause -> log.warn("HealthReconciler: failed to write {} for {}: {}",
                                                                           newState,
                                                                           target,
                                                                           cause.message()))
                            .onSuccess(_ -> recordWrite(target, newState, nowMs));
    }

    @SuppressWarnings("unchecked") private static KVCommand<AetherKey> putLifecycleAtom(NodeLifecycleKey key,
                                                                                        NodeLifecycleValue value) {
        return (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<AetherKey, AetherValue>(key, value);
    }

    @SuppressWarnings("unchecked") private static KVCommand<AetherKey> putClusterPhaseAtom(ClusterPhaseKey key,
                                                                                           ClusterPhaseValue value) {
        return (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<AetherKey, AetherValue>(key, value);
    }

    private void recordWrite(NodeId target, NodeLifecycleState newState, long nowMs) {
        lastWriteAt.put(target, nowMs);
        synchronized (aggregatorLock) {
            aggregator.resetEdgeState(target, newState);
        }
        log.info("HealthReconciler: wrote {} for {}", newState, target);
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
        return commandApplier.apply(List.of(command)).onSuccess(_ -> recordWrite(target, newState, nowMs))
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

    @Contract private void proposeClusterPhase(ClusterPhase target) {
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
