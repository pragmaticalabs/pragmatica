// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership;

import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot.ReachabilityKind;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot.ReachabilityState;
import org.pragmatica.cluster.metrics.ConnectivityState;
import org.pragmatica.cluster.metrics.HealthHintWire;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.metrics.PeerHealthObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;


/// Leader-side aggregator that folds per-observer transport observations into a
/// cluster-canonical reachability snapshot. Followers cache the snapshot for
/// warm-takeover; `/api/status` consumes it to eliminate per-reader QUIC-view
/// variance.
///
/// Aggregation contract:
///
/// * **Input — remote observers**: `PeerConnectivityObservation` (QUIC) and
///   `PeerHealthObservation` (SWIM) per observer, delivered through pong
///   listeners. Each observation has a producer timestamp.
/// * **Input — self**: at snapshot time, the leader's view of currently-connected
///   peers is folded in via `selfConnectedSupplier` (e.g., `network.connectedPeers()`).
///   Self contributes REACHABLE for connected peers and UNREACHABLE for known-
///   topology peers it cannot reach. No per-tick drain needed.
/// * **State**: per (target, observer) latest observation. Older same-pair entries
///   are overwritten.
/// * **TTL**: at snapshot time, observations older than `ttlMs` are dropped from
///   the quorum count (passive eviction).
/// * **Quorum**: for each target, count observers reporting REACHABLE vs
///   UNREACHABLE within TTL. Promote to a canonical kind if count crosses
///   ⌈N/2⌉+1 where N is the current ON_DUTY core count (KV-canonical).
/// * **Single-writer rule**: this class does NOT write KV. It emits a snapshot
///   only; `HealthReconciler` remains the sole `NodeLifecycleKey` writer.
///
/// Threading: callers (leader's pong-receive path + tick-build path) must serialize
/// access. The cluster-sync FSM already provides this serialization.
///
/// See `aether/docs/specs/reachability-aggregator-spec.md`.
public interface ReachabilityAggregator {
    /// Fold one remote observer's contribution into the aggregator state. Called
    /// by the leader-gated pong-receive listener.
    @Contract void ingest(NodeId observer,
                          List<PeerConnectivityObservation> connectivity,
                          List<PeerHealthObservation> health);

    /// Build the current cluster-canonical reachability snapshot. Folds the
    /// leader's self-view from the configured suppliers at build time. Returns
    /// `Option.none()` when the supplier surface is empty (cold-start window);
    /// callers fall back to KV-only view in that case.
    Option<AggregatedReachabilitySnapshot> snapshot();

    /// Drop all accumulated state. Called on leader-loss; the next leader rebuilds
    /// from incoming observations.
    @Contract void reset();

    /// Seed from the most-recent cached snapshot received from the prior leader.
    /// Called on leader-gained to shorten warmup: cached states become one-observer
    /// entries (self) until real observations from pongs refine them.
    @Contract void seedFromCache(AggregatedReachabilitySnapshot cached);

    static ReachabilityAggregator reachabilityAggregator(NodeId self,
                                                          IntSupplier onDutyCountSupplier,
                                                          Supplier<Set<NodeId>> selfConnectedSupplier,
                                                          Supplier<Set<NodeId>> topologySupplier,
                                                          LongSupplier clockMs,
                                                          long ttlMs) {
        return new ReachabilityAggregatorRecord(self,
                                                onDutyCountSupplier,
                                                selfConnectedSupplier,
                                                topologySupplier,
                                                clockMs,
                                                ttlMs,
                                                new HashMap<>());
    }
}

/// Mutable record-shaped implementation. See class docs for threading constraints.
record ReachabilityAggregatorRecord(NodeId self,
                                    IntSupplier onDutyCountSupplier,
                                    Supplier<Set<NodeId>> selfConnectedSupplier,
                                    Supplier<Set<NodeId>> topologySupplier,
                                    LongSupplier clockMs,
                                    long ttlMs,
                                    Map<NodeId, Map<NodeId, ObservationEntry>> byTarget) implements ReachabilityAggregator {

    record ObservationEntry(ReachabilityKind kind, long observedAtMs) {}

    @Contract @Override public void ingest(NodeId observer,
                                            List<PeerConnectivityObservation> connectivity,
                                            List<PeerHealthObservation> health) {
        connectivity.forEach(obs -> recordObservation(observer, obs.peerId(), translate(obs.state()), obs.producedAtMs()));
        health.forEach(obs -> recordObservation(observer, obs.peerId(), translate(obs.hint()), obs.producedAtMs()));
    }

    @Contract @Override public void reset() {
        byTarget.clear();
    }

    @Contract @Override public void seedFromCache(AggregatedReachabilitySnapshot cached) {
        var now = clockMs.getAsLong();
        cached.states().forEach((target, state) -> {
            var bySource = byTarget.computeIfAbsent(target, _ -> new HashMap<>());
            bySource.put(self, new ObservationEntry(state.kind(), Math.min(state.lastObservedAtMs(), now)));
        });
    }

    @Override public Option<AggregatedReachabilitySnapshot> snapshot() {
        var now = clockMs.getAsLong();
        foldSelfObservations(now);
        var quorumThreshold = quorumThreshold(onDutyCountSupplier.getAsInt());
        var states = new LinkedHashMap<NodeId, ReachabilityState>();
        byTarget.forEach((target, observers) -> {
            var live = liveObservers(observers, now);
            if (live.isEmpty()) {return;}
            states.put(target, derive(target, live, quorumThreshold));
        });
        if (states.isEmpty()) {return Option.none();}
        return Option.some(new AggregatedReachabilitySnapshot(now, states));
    }

    private void foldSelfObservations(long nowMs) {
        var connected = selfConnectedSupplier.get();
        var topology = topologySupplier.get();
        for (var peer : topology) {
            if (peer.equals(self)) {continue;}
            var kind = connected.contains(peer) ? ReachabilityKind.REACHABLE : ReachabilityKind.UNREACHABLE;
            recordObservation(self, peer, kind, nowMs);
        }
    }

    private void recordObservation(NodeId observer, NodeId target, ReachabilityKind kind, long producedAtMs) {
        if (target.equals(observer)) {return;}
        var bySource = byTarget.computeIfAbsent(target, _ -> new HashMap<>());
        var existing = bySource.get(observer);
        if (existing == null || producedAtMs >= existing.observedAtMs()) {
            bySource.put(observer, new ObservationEntry(kind, producedAtMs));
        }
    }

    private Map<NodeId, ObservationEntry> liveObservers(Map<NodeId, ObservationEntry> observers, long nowMs) {
        var alive = new HashMap<NodeId, ObservationEntry>();
        observers.forEach((observer, entry) -> {
            if (nowMs - entry.observedAtMs() <= ttlMs) {alive.put(observer, entry);}
        });
        return alive;
    }

    private static ReachabilityState derive(NodeId target, Map<NodeId, ObservationEntry> live, int quorumThreshold) {
        var reachable = 0;
        var unreachable = 0;
        var latestObservedAtMs = 0L;
        for (var entry : live.values()) {
            if (entry.kind() == ReachabilityKind.REACHABLE) {reachable++;}
            else if (entry.kind() == ReachabilityKind.UNREACHABLE) {unreachable++;}
            if (entry.observedAtMs() > latestObservedAtMs) {latestObservedAtMs = entry.observedAtMs();}
        }
        // Asymmetric quorum: REACHABLE upgrades on a single positive observer (any node
        // saying "I see this peer" is positive evidence and aligns with how local SWIM
        // HEALTHY works — local detection is sufficient). UNREACHABLE requires the full
        // ⌈N/2⌉+1 quorum to guard against single-witness false positives that would
        // misreport a transient local disconnect as cluster-wide unreachable. Without
        // this asymmetry, the snapshot decays to UNKNOWN once transition-driven follower
        // observations age past TTL on a stable cluster (no flaps → no buffer pushes →
        // only the leader's per-tick self-fold remains). See reachability-aggregator-spec.md.
        ReachabilityKind kind;
        int observerCount;
        if (reachable >= 1 && unreachable < quorumThreshold) {
            kind = ReachabilityKind.REACHABLE;
            observerCount = reachable;
        } else if (unreachable >= quorumThreshold) {
            kind = ReachabilityKind.UNREACHABLE;
            observerCount = unreachable;
        } else {
            kind = ReachabilityKind.UNKNOWN;
            observerCount = live.size();
        }
        return new ReachabilityState(target, kind, observerCount, latestObservedAtMs);
    }

    private static int quorumThreshold(int onDutyCount) {
        if (onDutyCount <= 0) {return 1;}
        return (onDutyCount / 2) + 1;
    }

    private static ReachabilityKind translate(ConnectivityState state) {
        return switch (state){
            case CONNECTED -> ReachabilityKind.REACHABLE;
            case DISCONNECTED, STALE -> ReachabilityKind.UNREACHABLE;
        };
    }

    private static ReachabilityKind translate(HealthHintWire hint) {
        return switch (hint){
            case HEALTHY -> ReachabilityKind.REACHABLE;
            case SUSPECTED, FAULTY -> ReachabilityKind.UNREACHABLE;
        };
    }
}
