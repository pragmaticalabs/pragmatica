// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.health.fsm;

import org.pragmatica.aether.node.health.fsm.SwimHealthEvents.LeaderChanged;
import org.pragmatica.aether.node.health.fsm.SwimHealthEvents.PeerConnected;
import org.pragmatica.aether.node.health.fsm.SwimHealthEvents.PeerFaulty;
import org.pragmatica.aether.node.health.fsm.SwimHealthEvents.PeerJoined;
import org.pragmatica.aether.node.health.fsm.SwimHealthEvents.PeerLeft;
import org.pragmatica.aether.node.health.fsm.SwimHealthEvents.PeerSuspect;
import org.pragmatica.aether.node.health.fsm.SwimHealthEvents.ProtocolReady;
import org.pragmatica.aether.node.health.fsm.SwimHealthEvents.ReportHint;
import org.pragmatica.aether.node.health.fsm.SwimHealthEvents.StartFailed;
import org.pragmatica.aether.node.health.fsm.SwimHealthEvents.StartRequested;
import org.pragmatica.aether.node.health.fsm.SwimHealthEvents.StopRequested;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;
import org.pragmatica.swim.GossipEncryptor;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimProtocol;
import org.pragmatica.swim.SwimTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/// Sealed state hierarchy for the SWIM health-detector FSM.
///
/// - [`Stopped`] and [`Starting`] are per-context singletons (data-free, shared for the lifetime
///   of the FSM so CAS comparisons against them are stable).
/// - [`Running`] is a fresh record per entry, carrying the live SWIM collaborators (`swim`,
///   `transport`, `encryptor`) and the authoritative `currentLeader` snapshot. Every LeaderChange
///   event creates a new `Running` record with the updated leader.
/// - [`LocalDisconnect`] is a fresh record per entry, carrying the same collaborators plus the
///   current-leader snapshot taken at the moment quorum was lost. On `NodeConnected`, the FSM
///   transitions back to a new `Running` with preserved collaborators.
///
/// Events ignored in a state fall through to `tx.ignore()` — no silent early-returns.
public sealed interface SwimHealthState extends FsmState<SwimHealthState, SwimHealthEvents> {

    int SWIM_PORT_OFFSET = 100;

    Logger LOG = LoggerFactory.getLogger(SwimHealthState.class);

    SwimHealthContext ctx();

    // --- State records ---

    /// Data-free lifecycle state: SWIM protocol is not running. Membership events still arrive
    /// from unit tests that inject synthetic [`SwimMember`] values — they route through the
    /// shared context helpers without the rolling-window faulty check (no protocol = no member
    /// count to compare against). `currentLeader` is unknown in this state, so the
    /// faulty-is-current-leader branch never fires.
    record Stopped(SwimHealthContext ctx) implements SwimHealthState {
        @Override
        public void handle(SwimHealthEvents event, TransitionRequest<SwimHealthState, SwimHealthEvents> tx) {
            switch (event) {
                case StartRequested _ -> tx.transitionTo(ctx.starting());
                case PeerJoined pj -> handleStoppedPeerJoined(ctx, pj.member());
                case PeerSuspect ps -> ctx.reportHint(ps.member().nodeId(), HealthHint.SUSPECTED);
                case PeerFaulty pf -> ctx.routeFaulty(pf.member().nodeId(), Option.none());
                case PeerLeft pl -> ctx.routeFaulty(pl.peer(), Option.none());
                case PeerConnected pc -> ctx.reportHint(pc.peer(), HealthHint.HEALTHY);
                case ReportHint rh -> ctx.reportHint(rh.peer(), rh.hint());
                case StopRequested _, LeaderChanged _, ProtocolReady _, StartFailed _ -> tx.ignore();
            }
        }
    }

    private static void handleStoppedPeerJoined(SwimHealthContext ctx, SwimMember member) {
        LOG.info("SWIM member joined (detector stopped): {}", member.nodeId());
        ctx.reportHint(member.nodeId(), HealthHint.HEALTHY);
    }

    /// Data-free lifecycle state: SWIM start in flight. Membership callbacks that arrive during
    /// the start window route through the same Stopped-style path (no live protocol yet).
    record Starting(SwimHealthContext ctx) implements SwimHealthState {
        @Override
        public void handle(SwimHealthEvents event, TransitionRequest<SwimHealthState, SwimHealthEvents> tx) {
            switch (event) {
                case ProtocolReady ready -> tx.transitionTo(new Running(ctx,
                                                                         ready.swim(),
                                                                         ready.transport(),
                                                                         ready.encryptor(),
                                                                         Option.none()));
                case StartFailed _ -> tx.transitionTo(ctx.stopped());
                case StopRequested _ -> tx.transitionTo(ctx.stopped());
                case PeerJoined pj -> handleStoppedPeerJoined(ctx, pj.member());
                case PeerSuspect ps -> ctx.reportHint(ps.member().nodeId(), HealthHint.SUSPECTED);
                case PeerFaulty pf -> ctx.routeFaulty(pf.member().nodeId(), Option.none());
                case PeerLeft pl -> ctx.routeFaulty(pl.peer(), Option.none());
                case PeerConnected pc -> ctx.reportHint(pc.peer(), HealthHint.HEALTHY);
                case ReportHint rh -> ctx.reportHint(rh.peer(), rh.hint());
                case StartRequested _, LeaderChanged _ -> tx.ignore();
            }
        }
    }

    record Running(SwimHealthContext ctx,
                   SwimProtocol swim,
                   SwimTransport transport,
                   GossipEncryptor encryptor,
                   Option<NodeId> currentLeader) implements SwimHealthState {

        @Override
        public void handle(SwimHealthEvents event, TransitionRequest<SwimHealthState, SwimHealthEvents> tx) {
            switch (event) {
                case StopRequested _ -> tx.transitionTo(ctx.stopped(), this::stopProtocolAndTransport);
                case LeaderChanged lc -> handleLeaderChanged(lc, tx);
                case PeerJoined pj -> handlePeerJoined(pj.member());
                case PeerSuspect ps -> ctx.reportHint(ps.member().nodeId(), HealthHint.SUSPECTED);
                case PeerFaulty pf -> handlePeerFaulty(pf.member(), tx);
                case PeerLeft pl -> handlePeerLeft(pl.peer());
                case PeerConnected pc -> handlePeerConnected(pc);
                case ReportHint rh -> ctx.reportHint(rh.peer(), rh.hint());
                case StartRequested _, ProtocolReady _, StartFailed _ -> tx.ignore();
            }
        }

        private void handleLeaderChanged(LeaderChanged event,
                                         TransitionRequest<SwimHealthState, SwimHealthEvents> tx) {
            if (event.leader().equals(currentLeader)) {
                tx.ignore();
                return;
            }
            tx.transitionTo(new Running(ctx, swim, transport, encryptor, event.leader()));
        }

        private void handlePeerJoined(SwimMember member) {
            LOG.info("SWIM member joined: {}", member.nodeId());
            ctx.resetFaultyWindow(System.currentTimeMillis());
            ctx.reportHint(member.nodeId(), HealthHint.HEALTHY);
        }

        private void handlePeerFaulty(SwimMember member,
                                      TransitionRequest<SwimHealthState, SwimHealthEvents> tx) {
            if (isLocalDisconnect(member)) {
                tx.transitionTo(new LocalDisconnect(ctx, swim, transport, encryptor, currentLeader));
                return;
            }
            LOG.warn("SWIM member faulty: {} (currentLeader={})", member.nodeId(), currentLeader);
            // Per plan §"follower routes DisconnectNode when the faulty peer is the current
            // leader": inspect state.currentLeader, not any external atomic. `ctx.routeFaulty`
            // encapsulates leader/follower routing policy.
            ctx.routeFaulty(member.nodeId(), currentLeader);
        }

        private void handlePeerLeft(NodeId leftNodeId) {
            LOG.warn("SWIM member left: {} (currentLeader={})", leftNodeId, currentLeader);
            ctx.routeFaulty(leftNodeId, currentLeader);
        }

        private void handlePeerConnected(PeerConnected event) {
            var peer = event.peer();
            event.info().onPresent(info -> readdOrMarkAlive(peer, addressOf(info)))
                 .onEmpty(() -> readdOrMarkAliveFromTopology(peer));
            ctx.resetFaultyWindow(System.currentTimeMillis());
            ctx.reportHint(peer, HealthHint.HEALTHY);
        }

        private void readdOrMarkAliveFromTopology(NodeId peer) {
            if (swim.members().containsKey(peer)) {
                swim.markAlive(peer);
                return;
            }
            ctx.resolveSwimAddress(peer, SWIM_PORT_OFFSET)
               .onPresent(addr -> addSeedAndLog(peer, addr));
        }

        private void readdOrMarkAlive(NodeId peer, InetSocketAddress address) {
            if (swim.members().containsKey(peer)) {
                swim.markAlive(peer);
                return;
            }
            addSeedAndLog(peer, address);
        }

        private void addSeedAndLog(NodeId peer, InetSocketAddress address) {
            swim.addSeedMember(peer, address);
            LOG.info("Re-added SWIM member {} at {} after disconnect recovery", peer.id(), address);
        }

        private static InetSocketAddress addressOf(NodeInfo info) {
            return SwimHealthContext.toSwimAddress(info, SWIM_PORT_OFFSET);
        }

        private boolean isLocalDisconnect(SwimMember member) {
            var now = System.currentTimeMillis();
            var count = ctx.incrementAndGetFaulty(now);
            var totalMembers = swim.members().size();
            if (totalMembers > 0 && count > totalMembers / 2) {
                LOG.warn("Local disconnect detected: {}/{} peers FAULTY — suppressing topology drain for {}",
                         count, totalMembers, member.nodeId().id());
                return true;
            }
            return false;
        }

        private void stopProtocolAndTransport() {
            swim.stop();
            transport.stop();
        }
    }

    record LocalDisconnect(SwimHealthContext ctx,
                           SwimProtocol swim,
                           SwimTransport transport,
                           GossipEncryptor encryptor,
                           Option<NodeId> currentLeader) implements SwimHealthState {

        @Override
        public void onEntry() {
            LOG.warn("Entering LocalDisconnect — majority of peers FAULTY within suspect window, "
                     + "suppressing topology drain until a peer re-connects");
        }

        @Override
        public void handle(SwimHealthEvents event, TransitionRequest<SwimHealthState, SwimHealthEvents> tx) {
            switch (event) {
                case StopRequested _ -> tx.transitionTo(ctx.stopped(), this::stopProtocolAndTransport);
                case PeerConnected pc -> recoverOnPeerConnected(pc, tx);
                case PeerJoined pj -> recoverOnPeerJoined(pj, tx);
                case LeaderChanged lc -> handleLeaderChanged(lc, tx);
                case PeerSuspect _, PeerFaulty _, PeerLeft _, ReportHint _ -> tx.ignore();
                case StartRequested _, ProtocolReady _, StartFailed _ -> tx.ignore();
            }
        }

        private void recoverOnPeerConnected(PeerConnected event,
                                            TransitionRequest<SwimHealthState, SwimHealthEvents> tx) {
            LOG.info("Network recovered from local disconnect via {}", event.peer().id());
            tx.transitionTo(new Running(ctx, swim, transport, encryptor, currentLeader),
                            () -> applyPeerConnectedRecovery(event));
        }

        private void recoverOnPeerJoined(PeerJoined event,
                                         TransitionRequest<SwimHealthState, SwimHealthEvents> tx) {
            LOG.info("Network recovered from local disconnect via join {}", event.member().nodeId());
            tx.transitionTo(new Running(ctx, swim, transport, encryptor, currentLeader),
                            () -> applyPeerJoinedRecovery(event));
        }

        private void applyPeerConnectedRecovery(PeerConnected event) {
            var peer = event.peer();
            event.info().onPresent(info -> readdOrMarkAlive(peer, SwimHealthContext.toSwimAddress(info, SWIM_PORT_OFFSET)))
                 .onEmpty(() -> readdOrMarkAliveFromTopology(peer));
            ctx.resetFaultyWindow(System.currentTimeMillis());
            ctx.reportHint(peer, HealthHint.HEALTHY);
        }

        private void applyPeerJoinedRecovery(PeerJoined event) {
            ctx.resetFaultyWindow(System.currentTimeMillis());
            ctx.reportHint(event.member().nodeId(), HealthHint.HEALTHY);
        }

        private void readdOrMarkAliveFromTopology(NodeId peer) {
            if (swim.members().containsKey(peer)) {
                swim.markAlive(peer);
                return;
            }
            ctx.resolveSwimAddress(peer, SWIM_PORT_OFFSET)
               .onPresent(addr -> addSeedAndLog(peer, addr));
        }

        private void readdOrMarkAlive(NodeId peer, InetSocketAddress address) {
            if (swim.members().containsKey(peer)) {
                swim.markAlive(peer);
                return;
            }
            addSeedAndLog(peer, address);
        }

        private void addSeedAndLog(NodeId peer, InetSocketAddress address) {
            swim.addSeedMember(peer, address);
            LOG.info("Re-added SWIM member {} at {} after disconnect recovery", peer.id(), address);
        }

        private void handleLeaderChanged(LeaderChanged event,
                                         TransitionRequest<SwimHealthState, SwimHealthEvents> tx) {
            if (event.leader().equals(currentLeader)) {
                tx.ignore();
                return;
            }
            tx.transitionTo(new LocalDisconnect(ctx, swim, transport, encryptor, event.leader()));
        }

        private void stopProtocolAndTransport() {
            swim.stop();
            transport.stop();
        }
    }
}
