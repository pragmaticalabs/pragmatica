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
import org.pragmatica.lang.utils.Causes;
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

    private final Object aggregatorLock = new Object();

    private final Object phaseListenerLock = new Object();

    private final Map<NodeId, Long> lastWriteAt = new ConcurrentHashMap<>();

    private final AtomicBoolean started = new AtomicBoolean(false);

    private final AtomicReference<ClusterPhase> currentPhase = new AtomicReference<>(ClusterPhase.BOOTING);

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
                                 SelfOnDutyAtomFactory selfOnDutyAtomFactory) {
        this.self = self;
        this.expectedClusterSize = expectedClusterSize;
        this.lifecycleReader = lifecycleReader;
        this.phaseReader = phaseReader;
        this.leaderReader = leaderReader;
        this.onDutyCountSupplier = onDutyCountSupplier;
        this.commandApplier = commandApplier;
        this.config = config;
        this.selfOnDutyAtomFactory = selfOnDutyAtomFactory;
        this.aggregator = ObservationAggregator.observationAggregator(config.aggregationWindowMs());
    }

    static HealthReconcilerImpl healthReconcilerImpl(NodeId self,
                                                     int expectedClusterSize,
                                                     Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader,
                                                     Supplier<Option<ClusterPhase>> phaseReader,
                                                     Supplier<Option<NodeId>> leaderReader,
                                                     Supplier<Integer> onDutyCountSupplier,
                                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                                     HealthReconcilerConfig config,
                                                     SelfOnDutyAtomFactory selfOnDutyAtomFactory) {
        return new HealthReconcilerImpl(self,
                                        expectedClusterSize,
                                        lifecycleReader,
                                        phaseReader,
                                        leaderReader,
                                        onDutyCountSupplier,
                                        commandApplier,
                                        config,
                                        selfOnDutyAtomFactory);
    }

    @Override public Promise<Unit> start() {
        if (!started.compareAndSet(false, true)) {return HealthReconcilerError.General.ALREADY_STARTED.promise();}
        currentPhase.set(phaseReader.get().or(ClusterPhase.BOOTING));
        log.info("HealthReconciler started for {} (expectedClusterSize={}, initialPhase={})",
                 self,
                 expectedClusterSize,
                 currentPhase.get());
        return Promise.unitPromise();
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
        var prior = lifecycleReader.apply(self);
        var value = prior.isPresent()
                   ? buildLifecycleValue(prior, NodeLifecycleState.ON_DUTY, nowMs)
                   : selfOnDutyAtomFactory.build(NodeLifecycleState.ON_DUTY, nowMs);
        var command = putLifecycleAtom(NodeLifecycleKey.nodeLifecycleKey(self), value);
        commandApplier.apply(List.of(command)).onFailure(cause -> log.warn("HealthReconciler: failed to write ON_DUTY for self {}: {}",
                                                                           self,
                                                                           cause.message()))
                            .onSuccess(_ -> recordWrite(self, NodeLifecycleState.ON_DUTY, nowMs));
    }

    private Option<ObservationAggregator.StateChanged> aggregateEdge(SwimObservation observation, long nowMs) {
        var onDutyCount = onDutyCountSupplier.get();
        synchronized (aggregatorLock) {
            return aggregator.onObservation(self, observation, onDutyCount, nowMs);
        }
    }

    private void handleAggregatedEdge(ObservationAggregator.StateChanged edge, long nowMs) {
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

    private boolean suppressedByPhase(ObservationAggregator.StateChanged edge) {
        if (currentPhase.get() != ClusterPhase.BOOTING) {return false;}
        return edge.newState() == NodeLifecycleState.DECOMMISSIONED || edge.newState() == NodeLifecycleState.SHUTTING_DOWN || edge.newState() == NodeLifecycleState.DRAINING;
    }

    private boolean cooldownActive(NodeId target, long nowMs) {
        return Option.option(lastWriteAt.get(target)).map(lastAt -> nowMs - lastAt <config.cooldownMs())
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
        var quorum = expectedClusterSize / 2 + 1;
        return switch (current){
            case BOOTING -> bootingTarget(onDuty, leaderPresent, nowMs);
            case NORMAL -> onDuty <quorum
                          ? ClusterPhase.RECOVERING
                          : resetStableMarker(ClusterPhase.NORMAL, nowMs);
            case RECOVERING -> recoveringTarget(onDuty, nowMs);
        };
    }

    private ClusterPhase bootingTarget(int onDuty, boolean leaderPresent, long nowMs) {
        if (!leaderPresent || onDuty <expectedClusterSize) {
            stableSinceMs.set(0L);
            return ClusterPhase.BOOTING;
        }
        return promoteAfterStable(ClusterPhase.NORMAL, ClusterPhase.BOOTING, nowMs, config.stableWindowMs());
    }

    private ClusterPhase recoveringTarget(int onDuty, long nowMs) {
        if (onDuty <expectedClusterSize) {
            stableSinceMs.set(0L);
            return ClusterPhase.RECOVERING;
        }
        return promoteAfterStable(ClusterPhase.NORMAL, ClusterPhase.RECOVERING, nowMs, config.recoveryStableWindowMs());
    }

    private ClusterPhase promoteAfterStable(ClusterPhase promoted, ClusterPhase fallback, long nowMs, long windowMs) {
        var since = stableSinceMs.get();
        if (since == 0L) {
            stableSinceMs.set(nowMs);
            return fallback;
        }
        return nowMs - since >= windowMs
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
