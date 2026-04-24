// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.fsm;

import org.pragmatica.aether.metrics.fsm.ClusterSyncEvents.PingTick;
import org.pragmatica.aether.metrics.fsm.ClusterSyncEvents.PongReceived;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.NodeGone;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumDisappeared;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumEstablished;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.Shutdown;
import org.pragmatica.lang.Option;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/// Sealed state hierarchy for the cluster-sync scheduler FSM.
///
/// - [`Dormant`] and [`Stopped`] are per-context singletons (data-free, shared for the FSM's
///   lifetime so CAS comparisons against them are stable).
/// - [`Pinging`] is a fresh record per entry, carrying the per-peer `lastSentEpoch` and
///   `missedPings` counters as immutable maps. Every handler that needs to bump a counter, reset
///   a counter, or drop a removed peer swaps the whole record (option (a) — see class Javadoc on
///   `Pinging` for the rationale).
///
/// Events ignored in a state fall through to `tx.ignore()` — no silent early-returns.
public sealed interface ClusterSyncState extends FsmState<ClusterSyncState, ClusterFsmEvent> {

    Logger LOG = LoggerFactory.getLogger(ClusterSyncState.class);

    ClusterSyncContext ctx();

    /// Dormant: quorum is not established, or quorum disappeared. No pings are sent. The scheduler
    /// is still observable (buffers, topology, observedEpochs, quorumSequence all live on the
    /// context), but there are no per-peer counters to maintain.
    record Dormant(ClusterSyncContext ctx) implements ClusterSyncState {
        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<ClusterSyncState, ClusterFsmEvent> tx) {
            switch (event) {
                case QuorumEstablished _ -> tx.transitionTo(Pinging.fresh(ctx));
                case Shutdown _ -> tx.transitionTo(ctx.stopped());
                default -> tx.ignore();
            }
        }
    }

    /// Terminal state: the scheduler has been stopped. No further transitions. Idempotent: a
    /// second `Shutdown` is recorded as ignored.
    record Stopped(ClusterSyncContext ctx) implements ClusterSyncState {
        @Override
        public void onEntry() {
            ctx.stopPinging();
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<ClusterSyncState, ClusterFsmEvent> tx) {
            // Terminal state: every event is ignored, no matter which concrete `ClusterFsmEvent`
            // subtype it is.
            tx.ignore();
        }
    }

    /// Pinging: quorum is established; the scheduler is periodically sending pings to all peers.
    ///
    /// Option (a) chosen for `missedPings`: both per-peer maps are immutable. Every mutation
    /// swaps the whole record via `tx.transitionToOrDrop(Pinging.with(...))` — pure state-swap
    /// semantics match the Fsm library contract (state identity via reference, fresh record per
    /// entry). Rationale for picking (a) over (b):
    ///
    /// - Quorum disappearance drops counters wholesale: Dormant has no data fields, so returning
    ///   to Pinging via a later QuorumEstablished starts from `Map.of()` — which IS the pre-FSM
    ///   semantic (counters were cleared at stopPinging).
    /// - All counter mutations happen under the FSM's serialized CAS dispatch path. No in-place
    ///   mutation means no internal synchronization on the record itself.
    /// - Topology shrink (NodeGone) drops one entry by rebuilding the maps without the gone peer.
    ///
    /// Fields:
    /// - `lastSentEpoch` — epoch of the last ping sent to each peer. Consumed by
    ///   `ClusterSyncContext.sendOnePing` to decide full-snapshot vs heartbeat-only payloads.
    /// - `missedPings` — consecutive tick count without a pong per peer. Reset to 0 on
    ///   `PongReceived`; increments on `PingTick`; emits `HealthSignal.PingTimeout` on threshold.
    record Pinging(ClusterSyncContext ctx,
                   Map<NodeId, Epoch> lastSentEpoch,
                   Map<NodeId, Integer> missedPings) implements ClusterSyncState {

        public static Pinging fresh(ClusterSyncContext ctx) {
            return new Pinging(ctx, Map.of(), Map.of());
        }

        @Override
        public void onEntry() {
            LOG.debug("ClusterSyncScheduler pinging started for node {}", ctx.self());
            ctx.startPinging(() -> ctx.dispatch(new PingTick(ctx.epochSupplier().get())));
        }

        @Override
        public void onExit() {
            ctx.stopPinging();
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<ClusterSyncState, ClusterFsmEvent> tx) {
            switch (event) {
                case PingTick pt -> handlePingTick(pt, tx);
                case PongReceived pr -> handlePongReceived(pr, tx);
                case QuorumDisappeared _ -> handleQuorumDisappeared(tx);
                case NodeGone ng -> handleNodeGone(ng, tx);
                case Shutdown _ -> tx.transitionTo(ctx.stopped());
                default -> tx.ignore();
            }
        }

        private void handleQuorumDisappeared(TransitionRequest<ClusterSyncState, ClusterFsmEvent> tx) {
            LOG.info("Quorum disappeared, moving cluster-sync scheduler to Dormant");
            tx.transitionTo(ctx.dormant());
        }

        private void handleNodeGone(NodeGone event,
                                    TransitionRequest<ClusterSyncState, ClusterFsmEvent> tx) {
            if (!lastSentEpoch.containsKey(event.node()) && !missedPings.containsKey(event.node())) {
                tx.ignore();
                return;
            }
            var nextLastSent = withoutKey(lastSentEpoch, event.node());
            var nextMissed = withoutKey(missedPings, event.node());
            tx.transitionToOrDrop(new Pinging(ctx, nextLastSent, nextMissed));
        }

        private void handlePongReceived(PongReceived event,
                                        TransitionRequest<ClusterSyncState, ClusterFsmEvent> tx) {
            if (!missedPings.containsKey(event.peer())) {
                tx.ignore();
                return;
            }
            var nextMissed = withoutKey(missedPings, event.peer());
            tx.transitionToOrDrop(new Pinging(ctx, lastSentEpoch, nextMissed));
        }

        private void handlePingTick(PingTick event,
                                    TransitionRequest<ClusterSyncState, ClusterFsmEvent> tx) {
            var topology = ctx.topology();
            if (topology.isEmpty()) {
                tx.ignore();
                return;
            }
            var maybeSnapshot = ctx.currentSnapshot();
            var currentEpoch = maybeSnapshot.map(s -> s.epoch()).or(event.currentEpoch());
            var rabiaTerm = ctx.currentRabiaTerm();
            var nextLastSent = new HashMap<>(lastSentEpoch);
            var nextMissed = new HashMap<>(missedPings);
            for (var peer : topology) {
                if (peer.equals(ctx.self())) { continue; }
                sendAndAccount(peer, currentEpoch, maybeSnapshot, rabiaTerm, nextLastSent, nextMissed);
            }
            tx.transitionToOrDrop(new Pinging(ctx, Map.copyOf(nextLastSent), Map.copyOf(nextMissed)));
        }

        private void sendAndAccount(NodeId peer,
                                    Epoch currentEpoch,
                                    Option<ClusterGenerationSnapshot> maybeSnapshot,
                                    long rabiaTerm,
                                    Map<NodeId, Epoch> nextLastSent,
                                    Map<NodeId, Integer> nextMissed) {
            var priorLastSent = Option.option(lastSentEpoch.get(peer));
            var sent = ctx.sendOnePing(peer, currentEpoch, priorLastSent, maybeSnapshot, rabiaTerm);
            nextLastSent.put(peer, sent);
            var newCount = nextMissed.getOrDefault(peer, 0) + 1;
            nextMissed.put(peer, newCount);
            ctx.emitPingTimeoutIfExceeded(peer, newCount);
        }

        private static <V> Map<NodeId, V> withoutKey(Map<NodeId, V> source, NodeId key) {
            if (!source.containsKey(key)) { return source; }
            var copy = new HashMap<>(source);
            copy.remove(key);
            return Map.copyOf(copy);
        }
    }
}
