// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.health;

import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.swim.SwimObservation;

import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


public final class ObservationAggregator {
    public static final long DEFAULT_AGGREGATION_WINDOW_MS = 5_000L;

    public record StateChanged(NodeId target, NodeLifecycleState newState){}

    private record Entry(NodeId observer, NodeLifecycleState observed, long timestampMs){}

    private final long aggregationWindowMs;

    private final Map<NodeId, Deque<Entry>> windows = new ConcurrentHashMap<>();

    private final Map<NodeId, NodeLifecycleState> lastAggregated = new ConcurrentHashMap<>();

    private final Set<NodeId> everSeenHealthy = ConcurrentHashMap.newKeySet();

    private ObservationAggregator(long aggregationWindowMs) {
        this.aggregationWindowMs = Math.max(1L, aggregationWindowMs);
    }

    public static ObservationAggregator observationAggregator() {
        return new ObservationAggregator(DEFAULT_AGGREGATION_WINDOW_MS);
    }

    public static ObservationAggregator observationAggregator(long aggregationWindowMs) {
        return new ObservationAggregator(aggregationWindowMs);
    }

    public Option<StateChanged> onObservation(NodeId observerNodeId,
                                              SwimObservation observation,
                                              int onDutyCount,
                                              long nowMs) {
        var target = observation.peer();
        var translated = translate(observation);
        recordCleanup(target, observerNodeId, translated, nowMs);
        rememberHealthy(observation, target);
        return computeEdge(target, onDutyCount, nowMs);
    }

    @Contract public void resetEdgeState(NodeId target, NodeLifecycleState confirmedState) {
        lastAggregated.put(target, confirmedState);
    }

    public boolean everSeenHealthy(NodeId target) {
        return everSeenHealthy.contains(target);
    }

    public int observerCount(NodeId target) {
        return Option.option(windows.get(target)).map(ObservationAggregator::countDistinctObservers)
                            .or(0);
    }

    private void recordCleanup(NodeId target, NodeId observer, Option<NodeLifecycleState> translated, long nowMs) {
        var window = windows.computeIfAbsent(target, _ -> new ConcurrentLinkedDeque<>());
        evictStale(window, nowMs);
        translated.onPresent(state -> appendEntry(window, observer, state, nowMs));
    }

    private static void appendEntry(Deque<Entry> window, NodeId observer, NodeLifecycleState state, long nowMs) {
        window.removeIf(entry -> entry.observer().equals(observer));
        window.addLast(new Entry(observer, state, nowMs));
    }

    private void evictStale(Deque<Entry> window, long nowMs) {
        while (!window.isEmpty() && nowMs - window.peekFirst().timestampMs() > aggregationWindowMs) {window.pollFirst();}
    }

    private void rememberHealthy(SwimObservation observation, NodeId target) {
        if (observation instanceof SwimObservation.HealthyObserved) {everSeenHealthy.add(target);}
    }

    private static Option<NodeLifecycleState> translate(SwimObservation observation) {
        return switch (observation){
            case SwimObservation.HealthyObserved _ -> some(NodeLifecycleState.ON_DUTY);
            case SwimObservation.FaultyObserved _ -> some(NodeLifecycleState.DECOMMISSIONED);
            case SwimObservation.DepartedObserved _ -> some(NodeLifecycleState.DECOMMISSIONED);
            case SwimObservation.SuspectObserved _ -> none();
            case SwimObservation.UnknownObserved _ -> none();
        };
    }

    private Option<StateChanged> computeEdge(NodeId target, int onDutyCount, long nowMs) {
        return Option.option(windows.get(target))
                            .flatMap(window -> computeEdgeForWindow(target, window, onDutyCount, nowMs));
    }

    private Option<StateChanged> computeEdgeForWindow(NodeId target, Deque<Entry> window, int onDutyCount, long nowMs) {
        evictStale(window, nowMs);
        var threshold = quorumThreshold(onDutyCount);
        // Cold-boot suppression is handled upstream:
        //   - SwimProtocol.emitFaultyOrUnknown gates emit by BOOTING/NORMAL phase (audit Step 6).
        //   - HealthReconcilerImpl.suppressedByPhase gates the actual lifecycle write in BOOTING.
        // The aggregator's prior respectColdBoot duplicated half of this logic without phase
        // awareness: peers added in initial ALIVE state never produce a HealthyObserved
        // (notifyAlive only fires on transition), so everSeenHealthy stayed empty even after
        // cluster reached NORMAL phase. The result was that FAULTY edges for the leader were
        // silently dropped on cloud Container post-kill: SWIM detected FAULTY, but the
        // aggregator suppressed it, preventing leader-eviction. Trust upstream emit gating.
        return tally(window, threshold).flatMap(state -> emitIfChanged(target, state));
    }

    @SuppressWarnings("unused") private static int quorumThreshold(int onDutyCount) {
        return 1;
    }

    private static Option<NodeLifecycleState> tally(Deque<Entry> window, int threshold) {
        var counts = new HashMap<NodeLifecycleState, Set<NodeId>>();
        for (var entry : window) {counts.computeIfAbsent(entry.observed(), _ -> new HashSet<>()).add(entry.observer());}
        return counts.entrySet().stream()
                              .filter(e -> e.getValue().size() >= threshold)
                              .map(Map.Entry::getKey)
                              .findFirst()
                              .map(Option::some)
                              .orElseGet(Option::none);
    }


    private Option<StateChanged> emitIfChanged(NodeId target, NodeLifecycleState newState) {
        var emitted = new AtomicBoolean(false);
        lastAggregated.compute(target,
                               (_, prior) -> {
                                   if (prior == newState) {return prior;}
                                   emitted.set(true);
                                   return newState;
                               });
        return emitted.get()
              ? some(new StateChanged(target, newState))
              : none();
    }

    private static int countDistinctObservers(Deque<Entry> window) {
        var observers = new HashSet<NodeId>();
        window.forEach(entry -> observers.add(entry.observer()));
        return observers.size();
    }
}
