// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.health;

import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
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
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Cross-node SWIM observation aggregator.
///
/// Each observation (from `observer` about `target`) is appended to a per-target
/// sliding window. Once `target` accumulates at least `quorumThreshold(onDutyCount)`
/// distinct observers agreeing on the same observed lifecycle state within the
/// `aggregationWindow` window, a single `StateChanged` edge is emitted. Below
/// the threshold the observation is kept pending — subsequent observations
/// re-evaluate the tally.
///
/// **Threshold semantics (RC1, post-revision 2026-05-12).** Quorum threshold
/// `(onDutyCount / 2) + 1` applies to `ON_DUTY` (peer re-confirmation, usually a
/// no-op since bootstrap goes through `attemptSelfOnDutyWrite`). `DECOMMISSIONED`
/// uses **threshold=1** because the aggregator currently receives observations
/// only from the local SWIM detector (`aggregateEdge` always tags the observer as
/// `self`); cross-node observation propagation is not yet wired, so demanding a
/// majority of *distinct observers* would make `DECOMMISSIONED` unreachable —
/// dead nodes cannot self-promote and the aggregator is the only write path.
/// Cluster-wide agreement is still guaranteed because the leader's
/// `DECOMMISSIONED` write is consensus-replicated; the local-SWIM-vs-KV
/// divergence that motivated the majority migration is resolved at the
/// consensus boundary, not in the aggregator. True majority quorum returns when
/// cross-node observation gossip lands (`ClusterFormationConfig` follow-up).
///
/// **Window.** `aggregationWindow` bounds how long an unfulfilled observation is
/// retained as pending — observations older than the window are evicted on the next
/// `onObservation` call and no longer count toward the tally. The window is sized
/// to match SWIM `suspectTimeout` (default 10s) so WAN jitter cannot defeat the
/// majority while the SWIM detector itself can still confirm FAULTY.
///
/// **Leader-failure path.** Leader-failure detection is owned by
/// `LeaderElectionFsm` and does NOT route through this aggregator. The escape hatch
/// in `HealthReconcilerImpl.handleAggregatedEdge` lets any surviving node attempt
/// the lifecycle write when the aggregated edge target IS the current leader; this
/// is independent of the threshold logic here.
public final class ObservationAggregator {
    public static final TimeSpan DEFAULT_AGGREGATION_WINDOW = timeSpan(10).seconds();

    public record StateChanged(NodeId target, NodeLifecycleState newState){}

    private record Entry(NodeId observer, NodeLifecycleState observed, long timestampMs){}

    private final long aggregationWindowMs;

    private final Map<NodeId, Deque<Entry>> windows = new ConcurrentHashMap<>();

    private final Map<NodeId, NodeLifecycleState> lastAggregated = new ConcurrentHashMap<>();

    private final Set<NodeId> everSeenHealthy = ConcurrentHashMap.newKeySet();

    private ObservationAggregator(TimeSpan aggregationWindow) {
        this.aggregationWindowMs = Math.max(1L, aggregationWindow.millis());
    }

    public static ObservationAggregator observationAggregator() {
        return new ObservationAggregator(DEFAULT_AGGREGATION_WINDOW);
    }

    public static ObservationAggregator observationAggregator(TimeSpan aggregationWindow) {
        return new ObservationAggregator(aggregationWindow);
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

    public int observerCount(NodeId target, NodeLifecycleState state) {
        return Option.option(windows.get(target)).map(window -> countDistinctObserversForState(window, state))
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
        return tally(window, threshold).flatMap(state -> emitIfChanged(target, state));
    }

    static int quorumThreshold(int onDutyCount) {
        return onDutyCount <= 1
              ? 1
              : (onDutyCount / 2) + 1;
    }

    private static Option<NodeLifecycleState> tally(Deque<Entry> window, int threshold) {
        var counts = new HashMap<NodeLifecycleState, Set<NodeId>>();
        for (var entry : window) {counts.computeIfAbsent(entry.observed(), _ -> new HashSet<>()).add(entry.observer());}
        return counts.entrySet().stream()
                              .filter(e -> e.getValue().size() >= effectiveThreshold(e.getKey(), threshold))
                              .map(Map.Entry::getKey)
                              .findFirst()
                              .map(Option::some)
                              .orElseGet(Option::none);
    }

    /// Failure-detection writes (`DECOMMISSIONED`) bypass the majority quorum because
    /// the aggregator only sees local-SWIM observations (one observer per node — see
    /// class-level "Threshold semantics" docstring). Requiring a majority of distinct
    /// observers would make `DECOMMISSIONED` unreachable; the leader's
    /// consensus-replicated write provides cluster-wide agreement instead.
    private static int effectiveThreshold(NodeLifecycleState state, int configuredThreshold) {
        return state == NodeLifecycleState.DECOMMISSIONED ? 1 : configuredThreshold;
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

    private static int countDistinctObserversForState(Deque<Entry> window, NodeLifecycleState state) {
        var observers = new HashSet<NodeId>();
        window.stream().filter(entry -> entry.observed() == state)
                     .map(Entry::observer)
                     .forEach(observers::add);
        return observers.size();
    }
}
