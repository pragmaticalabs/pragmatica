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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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
    @Contract
    void ingest(NodeId observer, List<PeerConnectivityObservation> connectivity, List<PeerHealthObservation> health);

    /// Compute the current cluster-canonical reachability snapshot WITHOUT any
    /// side effects. Folds the leader's self-view from the configured suppliers at
    /// build time. Returns `Option.none()` when the supplier surface is empty
    /// (cold-start window); callers fall back to KV-only view in that case.
    ///
    /// This is the PURE read path. It does NOT invoke snapshot listeners. Use it
    /// for any reader (status endpoints, `bestSnapshot`, FSM reachability gate,
    /// Tier-2 redistributors) so a snapshot READ never triggers dispatch — which
    /// would otherwise re-enter the FSM and recurse. Exactly one canonical caller
    /// (the Tier-1 ping-tick producer) must use `produceAndDispatch()` instead.
    Option<AggregatedReachabilitySnapshot> currentSnapshot();

    /// Compute the current snapshot via `currentSnapshot()` and, when present,
    /// dispatch it to all registered snapshot listeners before returning it. This
    /// is the SINGLE canonical once-per-round produce/redistribute entry point —
    /// it must be invoked from exactly one place (the Tier-1 ClusterSync ping-tick
    /// producer) so each aggregation round dispatches at most once.
    ///
    /// Listeners are invoked synchronously in registration order; a throwing
    /// listener does not interrupt the others (see `addSnapshotListener`). Returns
    /// `Option.none()` (and dispatches nothing) on an empty snapshot.
    Option<AggregatedReachabilitySnapshot> produceAndDispatch();

    /// Drop all accumulated state. Called on leader-loss; the next leader rebuilds
    /// from incoming observations.
    @Contract
    void reset();

    /// Seed from the most-recent cached snapshot received from the prior leader.
    /// Called on leader-gained to shorten warmup: cached states become one-observer
    /// entries (self) until real observations from pongs refine them.
    @Contract
    void seedFromCache(AggregatedReachabilitySnapshot cached);

    /// Topology-observation refactor Step 3: register a listener invoked synchronously
    /// from the snapshot-builder thread after each successful `produceAndDispatch()` build
    /// (i.e. when it returns `Option.some(...)`). Used to feed the aggregated
    /// snapshot into downstream consumers (currently `MembershipFsm.onTransportSnapshot`
    /// for `TransportReachable` / `TransportUnreachable` event derivation). Listeners are
    /// NEVER invoked from the pure `currentSnapshot()` read path.
    ///
    /// Listeners are invoked in registration order. A listener that throws does NOT
    /// interrupt subsequent listeners — the exception is logged and other listeners
    /// still receive the snapshot. The aggregator itself never propagates listener
    /// failures.
    @Contract
    void addSnapshotListener(Consumer<AggregatedReachabilitySnapshot> listener);

    /// Topology-observation refactor Step 4: leader's own QUIC layer fires a transition
    /// observation directly into the aggregator, bypassing the 5-second self-fold tick
    /// AND the follower→leader pong-relay roundtrip. The leader is its own observer for
    /// the `(self, target)` cell — calling this records a fresh observation with the
    /// given timestamp under latest-wins semantics (`recordObservation` retains the
    /// entry with the greatest `producedAtMs`).
    ///
    /// Symmetric for CONNECTED and DISCONNECTED transitions; expected to be invoked
    /// only when this node is leader. On followers the same transitions flow through
    /// the local `PeerObservationBuffer` and arrive at the leader via the next pong.
    /// Safe to call regardless of role — the aggregator is leader-side-only by design,
    /// so a misrouted call simply mutates state that the next `reset()` (on leader loss)
    /// will discard.
    @Contract
    void ingestSelfTransition(NodeId peer, ReachabilityKind kind, long producedAtMs);

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
                                                new HashMap<>(),
                                                new CopyOnWriteArrayList<>());
    }
}

/// Mutable record-shaped implementation. See class docs for threading constraints.
record ReachabilityAggregatorRecord(NodeId self,
                                    IntSupplier onDutyCountSupplier,
                                    Supplier<Set<NodeId>> selfConnectedSupplier,
                                    Supplier<Set<NodeId>> topologySupplier,
                                    LongSupplier clockMs,
                                    long ttlMs,
                                    Map<NodeId, Map<NodeId, ObservationEntry>> byTarget,
                                    List<Consumer<AggregatedReachabilitySnapshot>> snapshotListeners) implements ReachabilityAggregator {
    private static final Logger log = LoggerFactory.getLogger(ReachabilityAggregatorRecord.class);

    record ObservationEntry(ReachabilityKind kind, long observedAtMs) {}

    @Contract
    @Override
    public void ingest(NodeId observer,
                       List<PeerConnectivityObservation> connectivity,
                       List<PeerHealthObservation> health) {
        connectivity.forEach(obs -> recordObservation(observer, obs.peerId(), translate(obs.state()), obs.producedAtMs()));
        health.forEach(obs -> recordObservation(observer, obs.peerId(), translate(obs.hint()), obs.producedAtMs()));
    }

    @Contract
    @Override
    public void reset() {
        byTarget.clear();
    }

    @Contract
    @Override
    public void seedFromCache(AggregatedReachabilitySnapshot cached) {
        var now = clockMs.getAsLong();
        cached.states().forEach((target, state) -> {
                                    var bySource = byTarget.computeIfAbsent(target, _ -> new HashMap<>());
                                    bySource.put(self,
                                                 new ObservationEntry(state.kind(),
                                                                      Math.min(state.lastObservedAtMs(), now)));
                                });
    }

    @Contract
    @Override
    public void addSnapshotListener(Consumer<AggregatedReachabilitySnapshot> listener) {
        snapshotListeners.add(listener);
    }

    @Contract
    @Override
    public void ingestSelfTransition(NodeId peer, ReachabilityKind kind, long producedAtMs) {
        recordObservation(self, peer, kind, producedAtMs);
    }

    @Override
    public Option<AggregatedReachabilitySnapshot> currentSnapshot() {
        var now = clockMs.getAsLong();
        foldSelfObservations(now);
        var quorumThreshold = quorumThreshold(onDutyCountSupplier.getAsInt());
        var states = new LinkedHashMap<NodeId, ReachabilityState>();
        byTarget.forEach((target, observers) -> {
                             var live = liveObservers(observers, now);
                             if (live.isEmpty()) {
                             return;
                         }
                             states.put(target, derive(target, live, quorumThreshold));
                         });

        if (states.isEmpty()) {return Option.none();}

        return Option.some(new AggregatedReachabilitySnapshot(now, states));
    }

    @Override
    public Option<AggregatedReachabilitySnapshot> produceAndDispatch() {
        return currentSnapshot().onPresent(this::dispatchSnapshotToListeners);
    }

    private void dispatchSnapshotToListeners(AggregatedReachabilitySnapshot snapshot) {
        for (var listener : snapshotListeners) {
            invokeListenerIsolated(listener, snapshot);
        }
    }

    private void invokeListenerIsolated(Consumer<AggregatedReachabilitySnapshot> listener,
                                        AggregatedReachabilitySnapshot snapshot) {
        try {
            listener.accept(snapshot);
        } catch (RuntimeException ex) {
            log.warn("ReachabilityAggregator: snapshot listener threw {} — continuing with remaining listeners",
                     ex.toString());
        }
    }

    private void foldSelfObservations(long nowMs) {
        var connected = selfConnectedSupplier.get();
        var topology = topologySupplier.get();

        for (var peer : topology) {
            if (peer.equals(self)) {continue;}

            var kind = connected.contains(peer)
                       ? ReachabilityKind.REACHABLE
                       : ReachabilityKind.UNREACHABLE;
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
                              if (nowMs - entry.observedAtMs() <= ttlMs) {
                              alive.put(observer, entry);
                          }
                          });

        return alive;
    }

    private static ReachabilityState derive(NodeId target, Map<NodeId, ObservationEntry> live, int quorumThreshold) {
        var reachable = 0;
        var unreachable = 0;
        var latestObservedAtMs = 0L;

        for (var entry : live.values()) {
            if (entry.kind() == ReachabilityKind.REACHABLE) {reachable++;} else if (entry.kind() == ReachabilityKind.UNREACHABLE) {unreachable++;}
            if (entry.observedAtMs() > latestObservedAtMs) {latestObservedAtMs = entry.observedAtMs();}
        }
        // Symmetric quorum: BOTH REACHABLE and UNREACHABLE require ⌈N/2⌉+1 observers.
        // REACHABLE additionally requires zero dissenting UNREACHABLE observations within
        // TTL — a single live UNREACHABLE blocks the upgrade. Otherwise UNKNOWN.
        //
        // Supersedes the prior asymmetric scheme (REACHABLE on >=1 positive observer):
        // during cluster B chaos a single stale CONNECTED observation buffered through
        // RECONNECT flaps was outvoting a strict-majority `(N/2)+1` UNREACHABLE quorum,
        // marking dead peers alive and breaking the 12-network suite. On a stable cluster
        // the snapshot may degrade to all-UNKNOWN (self-fold only = 1 observer < threshold);
        // this is OK because `MembershipView.resolveOnDutyStatus` takes the SWIM-HEALTHY
        // fast path and never consults the snapshot when SWIM has converged. The snapshot
        // only matters when SWIM is NOT HEALTHY — exactly the chaos scenario we just fixed.
        //
        // Threshold is strict majority `(N/2)+1` (Rabia is CFT, not BFT — Byzantine
        // `⌈N/2⌉+1` is not required and would block 2-of-N kill detection). The aggregator
        // cannot distinguish a kill (peer dead, won't come back) from a partition (peer
        // alive on the other side); both produce identical observations from any one side.
        // The partition story is owned upstream: the minority side's `SelfDrainCoordinator`
        // detects "I see ≥majority unreachable" and exits independently. See
        // reachability-aggregator-spec.md (§ supersession note) and
        // membership-architecture-spec.md §16 S05/S06.
        ReachabilityKind kind;
        int observerCount;

        if (reachable >= quorumThreshold && unreachable == 0) {
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
        return switch (state) {
            case CONNECTED -> ReachabilityKind.REACHABLE;
            case DISCONNECTED, STALE -> ReachabilityKind.UNREACHABLE;
        };
    }

    private static ReachabilityKind translate(HealthHintWire hint) {
        return switch (hint) {
            case HEALTHY -> ReachabilityKind.REACHABLE;
            case SUSPECTED, FAULTY -> ReachabilityKind.UNREACHABLE;
        };
    }
}
