// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.fsm;

import org.pragmatica.aether.metrics.fsm.ClusterSyncEvents.PingTick;
import org.pragmatica.aether.metrics.fsm.ClusterSyncEvents.PongReceived;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.NodeGone;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumDisappeared;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumEstablished;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.Shutdown;
import org.pragmatica.lang.Contract;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public sealed interface ClusterSyncState extends FsmState<ClusterSyncState, ClusterFsmEvent> {
    Logger LOG = LoggerFactory.getLogger(ClusterSyncState.class);

    ClusterSyncContext ctx();

    record Dormant(ClusterSyncContext ctx) implements ClusterSyncState {
        @Contract@Override public void handle(ClusterFsmEvent event,
                                              TransitionRequest<ClusterSyncState, ClusterFsmEvent> tx) {
            switch (event){
                case QuorumEstablished _ -> tx.transitionTo(Pinging.fresh(ctx));
                case Shutdown _ -> tx.transitionTo(ctx.stopped());
                default -> tx.ignore();
            }
        }
    }

    record Stopped(ClusterSyncContext ctx) implements ClusterSyncState {
        @Contract@Override public void onEntry() {
            ctx.clearObservationBuffers();
        }

        @Contract@Override public void handle(ClusterFsmEvent event,
                                              TransitionRequest<ClusterSyncState, ClusterFsmEvent> tx) {
            tx.ignore();
        }
    }

    record Pinging(ClusterSyncContext ctx,
                   Map<NodeId, Epoch> lastSentEpoch,
                   Map<NodeId, Integer> missedPings,
                   ScheduledFuture<?> pingTimer,
                   ScheduledFuture<?> periodicEmissionTimer) implements ClusterSyncState {
        public static Pinging fresh(ClusterSyncContext ctx) {
            return with(ctx, Map.of(), Map.of());
        }

        private static Pinging with(ClusterSyncContext ctx,
                                    Map<NodeId, Epoch> lastSentEpoch,
                                    Map<NodeId, Integer> missedPings) {
            return new Pinging(ctx,
                               lastSentEpoch,
                               missedPings,
                               ctx.schedulePingTimer(() -> ctx.dispatch(new PingTick(ctx.epochSupplier().get()))),
                               ctx.schedulePeriodicEmission());
        }

        @Contract@Override public void onEntry() {
            LOG.debug("ClusterSyncScheduler pinging started for node {}", ctx.self());
        }

        @Contract@Override public void onExit() {
            pingTimer.cancel(false);
            periodicEmissionTimer.cancel(false);
        }

        @Contract@Override public void onCasLost() {
            pingTimer.cancel(false);
            periodicEmissionTimer.cancel(false);
        }

        @Contract@Override public void handle(ClusterFsmEvent event,
                                              TransitionRequest<ClusterSyncState, ClusterFsmEvent> tx) {
            switch (event){
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

        private void handleNodeGone(NodeGone event, TransitionRequest<ClusterSyncState, ClusterFsmEvent> tx) {
            if (!lastSentEpoch.containsKey(event.node()) && !missedPings.containsKey(event.node())) {
                tx.ignore();
                return;
            }
            var nextLastSent = withoutKey(lastSentEpoch, event.node());
            var nextMissed = withoutKey(missedPings, event.node());
            tx.transitionToOrDrop(with(ctx, nextLastSent, nextMissed));
        }

        private void handlePongReceived(PongReceived event, TransitionRequest<ClusterSyncState, ClusterFsmEvent> tx) {
            if (!missedPings.containsKey(event.peer())) {
                tx.ignore();
                return;
            }
            var nextMissed = withoutKey(missedPings, event.peer());
            tx.transitionToOrDrop(with(ctx, lastSentEpoch, nextMissed));
        }

        private void handlePingTick(PingTick event, TransitionRequest<ClusterSyncState, ClusterFsmEvent> tx) {
            // #231 regression fix: ping DISPATCH is leader-only. Every node enters Pinging on
            // QuorumEstablished (so it stays ready/responsive and resumes promptly on becoming
            // leader), but only the current leader runs the ping cycle — dispatch pings, increment
            // missedPings, and emit ping-timeout / eviction hints. A non-leader tick is a no-op.
            // Followers still RESPOND to incoming pings (the unconditional onClusterSyncPing→pong
            // path in ClusterSyncCollector is untouched), so the leader's aggregator/φ keep getting
            // data. This restores the pre-04cae266d single-pinger model: all-to-all amplified a
            // stale-term fencing drop into a cluster-wide eviction storm (missed pongs → ping-timeout
            // → DisconnectNode → quorum collapse). Resume-on-leader-change is automatic: the tick
            // simply starts producing on the not-leader→leader edge and stops on the reverse edge.
            if (!ctx.isLeader()) {
                tx.ignore();
                return;
            }
            // Effective ping-target set: prefer the membership-seeded topology (unchanged
            // behavior), but when it is still empty fall back to the live transport peers
            // (same source the periodic emission reads). Spike-1 finding: entering Pinging on
            // QuorumEstablished while topology() is unseeded left ping/pong silently dormant.
            var targets = effectiveTargets();
            if (targets.isEmpty()) {
                tx.ignore();
                return;
            }
            var currentEpoch = event.currentEpoch();
            var rabiaTerm = ctx.currentRabiaTerm();
            var nextLastSent = new HashMap<>(lastSentEpoch);
            var nextMissed = new HashMap<>(missedPings);
            for (var peer : targets) {
                if (peer.equals(ctx.self())) {continue;}
                nextLastSent.put(peer, currentEpoch);
                nextMissed.put(peer, nextMissed.getOrDefault(peer, 0) + 1);
            }
            tx.transitionToOrDrop(with(ctx, Map.copyOf(nextLastSent), Map.copyOf(nextMissed)),
                                  () -> dispatchPings(targets, currentEpoch, rabiaTerm, nextMissed));
        }

        private List<NodeId> effectiveTargets() {
            var topology = ctx.topology();
            return topology.isEmpty()
                  ? List.copyOf(ctx.connectedPeers())
                  : topology;
        }

        private void dispatchPings(List<NodeId> topology,
                                   Epoch currentEpoch,
                                   long rabiaTerm,
                                   Map<NodeId, Integer> nextMissed) {
            for (var peer : topology) {
                if (peer.equals(ctx.self())) {continue;}
                ctx.sendOnePing(peer, currentEpoch, rabiaTerm);
                ctx.emitPingTimeoutIfExceeded(peer, nextMissed.get(peer));
            }
        }

        private static <V> Map<NodeId, V> withoutKey(Map<NodeId, V> source, NodeId key) {
            if (!source.containsKey(key)) {return source;}
            var copy = new HashMap<>(source);
            copy.remove(key);
            return Map.copyOf(copy);
        }
    }
}
