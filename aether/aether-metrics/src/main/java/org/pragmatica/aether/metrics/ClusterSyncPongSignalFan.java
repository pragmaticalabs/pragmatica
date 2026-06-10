// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.pragmatica.aether.slice.generation.ConnectivityReport;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.metrics.ConnectivityState;
import org.pragmatica.cluster.metrics.HealthHintWire;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.metrics.PeerHealthObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.lang.Contract;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Leader-side fan-out of `ClusterSyncPong` observations into `HealthSignal`s, plus the
/// leader's in-memory node-readiness view (membership-architecture-v2-spec §7.5.6).
///
/// The readiness view is an epoch-fenced `NodeId → NodeReportedState` map built purely from
/// pong arrivals. It is DECOUPLED from `aether-deployment` (no CTM import): the stuck-SYNCING
/// reaper invokes an injected `Consumer<NodeId>` callback rather than calling CTM directly, and
/// the warmed-up guard is an injected `BooleanSupplier`. `AetherNode` wires these later.
@Contract
public interface ClusterSyncPongSignalFan {
    Logger log = LoggerFactory.getLogger(ClusterSyncPongSignalFan.class);
    /// Default number of consecutive SYNCING-state pongs (at the same incarnation) tolerated
    /// before the stuck-SYNCING reaper fires. Each SYNCING pong decrements the entry countdown;
    /// any READY/DRAINING pong resets it. See membership-architecture-v2-spec §7.5.6.
    int SYNC_REAP_THRESHOLD = 30;

    /// Sink invoked when the leader observes a non-empty `ClusterSyncPong.readyCandidate`. The
    /// signal fan does not depend on `aether-deployment`; `AetherNode` wires the lambda that
    /// constructs `LifecycleCommand.ForceOnDuty(candidate, ...)` and routes it through
    /// `LifecycleWriter.applyCommand`. Default is a no-op so embeddings that pre-date
    /// Phase 2 PR-B (cluster-convergence-reconciler) keep working unchanged.
    @Contract
    @FunctionalInterface
    interface ReadyCandidateSink {
        @Contract
        void onReadyCandidate(NodeId sender, NodeId candidate);

        ReadyCandidateSink NOOP = (_, _) -> {};
    }

    void fan(ClusterSyncPong pong);

    /// No-op fan for placeholder wiring (e.g. before `AetherNode` installs the real fan).
    /// Holds no readiness state and ignores every signal.
    ClusterSyncPongSignalFan NOOP = new ClusterSyncPongSignalFan() {
        @Override
        @Contract
        public void fan(ClusterSyncPong pong) {}

        @Override
        @Contract
        public void evict(NodeId nodeId) {}

        @Override
        @Contract
        public void sweepStale(long maxAgeNanos) {}

        @Override
        public Map<NodeId, NodeReportedState> readinessSnapshot() {
            return Map.of();
        }

        @Override
        @Contract
        public void onStuckSyncing(Consumer<NodeId> callback) {}

        @Override
        @Contract
        public void warmedUp(BooleanSupplier guard) {}
    };

    /// Remove a node from the readiness view regardless of incarnation. Invoked by `AetherNode`
    /// when the node is decommissioned / observed-failed so the leader's view does not retain a
    /// phantom entry.
    @Contract
    void evict(NodeId nodeId);

    /// Remove every readiness entry whose `lastSeenNanos` is older than `now - maxAgeNanos`.
    /// Invoked periodically by `AetherNode` to garbage-collect silent entries.
    @Contract
    void sweepStale(long maxAgeNanos);

    /// Immutable `NodeId → NodeReportedState` snapshot of the current readiness view, for the
    /// CDM allocation gate (later phase).
    Map<NodeId, NodeReportedState> readinessSnapshot();

    /// Wire the callback invoked when a stuck-SYNCING entry is reaped. Default is a no-op.
    @Contract
    void onStuckSyncing(Consumer<NodeId> callback);

    /// Wire the guard that gates the stuck-SYNCING reaper — the reaper only fires once the
    /// cluster is warmed up. Default is always-`false` (never warmed up), which disables the
    /// reaper until `AetherNode` wires the real guard.
    @Contract
    void warmedUp(BooleanSupplier guard);

    /// Backward-compatible factory: no readiness sink wired — `readyCandidate` arrivals are
    /// observed silently. Preserves pre-Phase-2 behaviour for tests / embeddings that pre-date
    /// the convergence-reconciler wiring.
    static ClusterSyncPongSignalFan clusterSyncPongSignalFan(HealthSignalSink sink, LeaderManager leaderManager) {
        return clusterSyncPongSignalFan(sink, leaderManager, ReadyCandidateSink.NOOP);
    }

    /// Phase 2 PR-B factory. The supplied `readySink` is invoked for every non-empty
    /// `ClusterSyncPong.readyCandidate` observed while this node is leader. Idempotency lives
    /// in the downstream reducer — re-signalling an already-present candidate is a no-op there.
    static ClusterSyncPongSignalFan clusterSyncPongSignalFan(HealthSignalSink sink,
                                                             LeaderManager leaderManager,
                                                             ReadyCandidateSink readySink) {
        return new StatefulSignalFan(sink, leaderManager, readySink, System::nanoTime);
    }

    /// Membership-v2 B2 factory with an injected clock for deterministic testing. The clock
    /// drives `lastSeenNanos` stamping and `sweepStale` age comparison.
    static ClusterSyncPongSignalFan clusterSyncPongSignalFan(HealthSignalSink sink,
                                                             LeaderManager leaderManager,
                                                             ReadyCandidateSink readySink,
                                                             LongSupplier nowNanos) {
        return new StatefulSignalFan(sink, leaderManager, readySink, nowNanos);
    }

    /// Per-node readiness entry in the leader's in-memory view. `syncCountdown` is the remaining
    /// number of SYNCING pongs tolerated before the reaper fires; it is meaningful only while
    /// `state == SYNCING`.
    record ReadinessEntry(NodeReportedState state, long incarnation, long lastSeenNanos, int syncCountdown) {}

    final class StatefulSignalFan implements ClusterSyncPongSignalFan {
        private final HealthSignalSink sink;
        private final LeaderManager leaderManager;
        private final ReadyCandidateSink readySink;
        private final LongSupplier nowNanos;
        private final ConcurrentHashMap<NodeId, ReadinessEntry> readiness = new ConcurrentHashMap<>();

        private volatile Consumer<NodeId> onStuckSyncing = _ -> {};

        private volatile BooleanSupplier warmedUp = () -> false;

        private StatefulSignalFan(HealthSignalSink sink,
                                  LeaderManager leaderManager,
                                  ReadyCandidateSink readySink,
                                  LongSupplier nowNanos) {
            this.sink = sink;
            this.leaderManager = leaderManager;
            this.readySink = readySink;
            this.nowNanos = nowNanos;
        }

        @Override
        @Contract
        public void fan(ClusterSyncPong pong) {
            if (!leaderManager.isLeader()) {
                return;
            }

            sink.emit(new HealthSignal.SwimHint(pong.sender(),
                                                HealthHint.HEALTHY,
                                                Epoch.epoch(pong.observedEpochTerm(), pong.observedEpochCounter())));
            pong.readyCandidate().onPresent(candidate -> readySink.onReadyCandidate(pong.sender(), candidate));
            recordReadiness(pong);
            pong.peerHealth().forEach(observation -> emitHealth(pong, observation, sink));
            pong.peerConnectivity().forEach(observation -> emitConnectivity(pong, observation, sink));
        }

        @Override
        @Contract
        public void evict(NodeId nodeId) {
            readiness.remove(nodeId);
        }

        @Override
        @Contract
        public void sweepStale(long maxAgeNanos) {
            var cutoff = nowNanos.getAsLong() - maxAgeNanos;

            readiness.values().removeIf(entry -> entry.lastSeenNanos() < cutoff);
        }

        @Override
        public Map<NodeId, NodeReportedState> readinessSnapshot() {
            var snapshot = new HashMap<NodeId, NodeReportedState>();

            readiness.forEach((nodeId, entry) -> snapshot.put(nodeId, entry.state()));

            return Map.copyOf(snapshot);
        }

        @Override
        @Contract
        public void onStuckSyncing(Consumer<NodeId> callback) {
            this.onStuckSyncing = callback == null
                                  ? _ -> {}
                                  : callback;
        }

        @Override
        @Contract
        public void warmedUp(BooleanSupplier guard) {
            this.warmedUp = guard == null
                            ? () -> false
                            : guard;
        }

        private void recordReadiness(ClusterSyncPong pong) {
            var sender = pong.sender();
            var state = parseState(pong.lifecycleState());
            var incarnation = pong.incarnation();
            var now = nowNanos.getAsLong();
            var merged = readiness.merge(sender,
                                         freshEntry(state, incarnation, now),
                                         (existing, incoming) -> reconcile(existing, incoming, now));

            maybeReap(sender, merged);
        }

        /// Epoch-fenced reconciliation: a strictly-higher incarnation installs a fresh entry, an
        /// equal incarnation updates state + countdown, a lower incarnation is ignored (stale).
        private static ReadinessEntry reconcile(ReadinessEntry existing, ReadinessEntry incoming, long now) {
            return incoming.incarnation() > existing.incarnation()
                   ? incoming
                   : incoming.incarnation() < existing.incarnation()
                     ? existing
                     : updateSameEpoch(existing, incoming.state(), now);
        }

        private static ReadinessEntry updateSameEpoch(ReadinessEntry existing, NodeReportedState state, long now) {
            return new ReadinessEntry(state, existing.incarnation(), now, nextCountdown(existing, state));
        }

        private static int nextCountdown(ReadinessEntry existing, NodeReportedState state) {
            return state == NodeReportedState.SYNCING
                   ? Math.max(0, existing.syncCountdown() - 1)
                   : SYNC_REAP_THRESHOLD;
        }

        private static ReadinessEntry freshEntry(NodeReportedState state, long incarnation, long now) {
            return new ReadinessEntry(state,
                                      incarnation,
                                      now,
                                      state == NodeReportedState.SYNCING
                                      ? SYNC_REAP_THRESHOLD
                                      : 0);
        }

        private void maybeReap(NodeId sender, ReadinessEntry entry) {
            if (entry.state() == NodeReportedState.SYNCING && entry.syncCountdown() <= 0 && warmedUp.getAsBoolean()) {
                reap(sender);
            }
        }

        private void reap(NodeId sender) {
            readiness.remove(sender);
            onStuckSyncing.accept(sender);
        }

        private static NodeReportedState parseState(String wire) {
            return NodeReportedState.fromWire(wire);
        }

        private static void emitHealth(ClusterSyncPong pong, PeerHealthObservation observation, HealthSignalSink sink) {
            sink.emit(new HealthSignal.RemoteSwimHint(pong.sender(),
                                                      observation.peerId(),
                                                      translateHint(observation.hint()),
                                                      Epoch.epoch(observation.observedEpochTerm(),
                                                                  observation.observedEpochCounter()),
                                                      observation.producedAtMs()));
        }

        private static void emitConnectivity(ClusterSyncPong pong,
                                             PeerConnectivityObservation observation,
                                             HealthSignalSink sink) {
            sink.emit(new HealthSignal.RemoteConnectivity(pong.sender(),
                                                          observation.peerId(),
                                                          translateConnectivity(observation.state()),
                                                          Epoch.epoch(observation.observedEpochTerm(),
                                                                      observation.observedEpochCounter()),
                                                          observation.producedAtMs()));
        }

        private static HealthHint translateHint(HealthHintWire wire) {
            return switch (wire) {
                case HEALTHY -> HealthHint.HEALTHY;
                case SUSPECTED -> HealthHint.SUSPECTED;
                case FAULTY -> HealthHint.FAULTY;
            };
        }

        private static ConnectivityReport translateConnectivity(ConnectivityState state) {
            return switch (state) {
                case CONNECTED -> ConnectivityReport.CONNECTED;
                case DISCONNECTED -> ConnectivityReport.DISCONNECTED;
                case STALE -> ConnectivityReport.STALE;
            };
        }
    }
}
