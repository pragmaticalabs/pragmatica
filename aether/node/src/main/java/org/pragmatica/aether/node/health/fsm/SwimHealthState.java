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
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;
import org.pragmatica.swim.GossipEncryptor;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimProtocol;
import org.pragmatica.swim.SwimTransport;

import java.net.InetSocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public sealed interface SwimHealthState extends FsmState<SwimHealthState, SwimHealthEvents> {
    int SWIM_PORT_OFFSET = 100;
    Logger LOG = LoggerFactory.getLogger(SwimHealthState.class);
    SwimHealthContext ctx();

    record Stopped(SwimHealthContext ctx) implements SwimHealthState {
        @Override
        @Contract
        public void handle(SwimHealthEvents event, TransitionRequest<SwimHealthState, SwimHealthEvents> tx) {
            switch (event) {
                case StartRequested _ -> tx.transitionTo(ctx.starting());
                case PeerJoined pj -> tx.handle(() -> handleStoppedPeerJoined(pj.member()));
                case PeerSuspect ps -> tx.handle(() -> ctx.reportHint(ps.member().nodeId(),
                                                                      HealthHint.SUSPECTED));
                case PeerFaulty pf -> tx.handle(() -> ctx.routeFaulty(pf.member().nodeId(),
                                                                      Option.none()));
                case PeerLeft pl -> tx.handle(() -> ctx.routeFaulty(pl.peer(), Option.none()));
                case PeerConnected pc -> tx.handle(() -> ctx.reportHint(pc.peer(), HealthHint.HEALTHY));
                case ReportHint rh -> tx.handle(() -> ctx.reportHint(rh.peer(), rh.hint()));
                case StopRequested _, LeaderChanged _, ProtocolReady _, StartFailed _ -> tx.ignore();
            }
        }
    }

    private static void handleStoppedPeerJoined(SwimMember member) {
        // Bare gossip join while the detector is Stopped/Starting carries no reachability
        // proof. Emit NO HEALTHY hint — let it arrive via `PeerConnected`/probe-ack once the
        // protocol is Running. Emitting HEALTHY here resurrects unreachable bare-gossip joins.
        LOG.info("SWIM member joined (detector stopped/starting): {} — no HEALTHY hint until connection", member.nodeId());
    }

    record Starting(SwimHealthContext ctx) implements SwimHealthState {
        @Override
        @Contract
        public void handle(SwimHealthEvents event, TransitionRequest<SwimHealthState, SwimHealthEvents> tx) {
            switch (event) {
                case ProtocolReady ready -> tx.transitionTo(new Running(ctx,
                                                                        ready.swim(),
                                                                        ready.transport(),
                                                                        ready.encryptor(),
                                                                        Option.none()));
                case StartFailed _ -> tx.transitionTo(ctx.stopped());
                case StopRequested _ -> tx.transitionTo(ctx.stopped());
                case PeerJoined pj -> tx.handle(() -> handleStoppedPeerJoined(pj.member()));
                case PeerSuspect ps -> tx.handle(() -> ctx.reportHint(ps.member().nodeId(),
                                                                      HealthHint.SUSPECTED));
                case PeerFaulty pf -> tx.handle(() -> ctx.routeFaulty(pf.member().nodeId(),
                                                                      Option.none()));
                case PeerLeft pl -> tx.handle(() -> ctx.routeFaulty(pl.peer(), Option.none()));
                case PeerConnected pc -> tx.handle(() -> ctx.reportHint(pc.peer(), HealthHint.HEALTHY));
                case ReportHint rh -> tx.handle(() -> ctx.reportHint(rh.peer(), rh.hint()));
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
                case PeerJoined pj -> tx.handle(() -> handlePeerJoined(pj.member()));
                case PeerSuspect ps -> tx.handle(() -> ctx.reportHint(ps.member().nodeId(),
                                                                      HealthHint.SUSPECTED));
                case PeerFaulty pf -> handlePeerFaulty(pf.member(), tx);
                case PeerLeft pl -> tx.handle(() -> handlePeerLeft(pl.peer()));
                case PeerConnected pc -> tx.handle(() -> handlePeerConnected(pc));
                case ReportHint rh -> tx.handle(() -> ctx.reportHint(rh.peer(), rh.hint()));
                case StartRequested _, ProtocolReady _, StartFailed _ -> tx.ignore();
            }
        }

        private void handleLeaderChanged(LeaderChanged event, TransitionRequest<SwimHealthState, SwimHealthEvents> tx) {
            if (event.leader().equals(currentLeader)) {
                tx.ignore();

                return;
            }
            tx.transitionTo(new Running(ctx, swim, transport, encryptor, event.leader()));
        }

        private void handlePeerJoined(SwimMember member) {
            // A bare SWIM join (gossip-driven) is NOT proof of reachability. Do NOT assert
            // HEALTHY and do NOT reset the faulty window here — doing so would resurrect a
            // dead, unreachable node (and erase accumulated FAULTY evidence) off a stale
            // Announce. HEALTHY arrives only via `PeerConnected` (real QUIC connection) or a
            // probe-ack. A STOPPED or FAULTY peer rejoining by gossip must NOT be auto-healed.
            LOG.info("SWIM member joined (gossip): {} — awaiting connection/probe-ack before HEALTHY", member.nodeId());
        }

        private void handlePeerFaulty(SwimMember member, TransitionRequest<SwimHealthState, SwimHealthEvents> tx) {
            if (isLocalDisconnect(member)) {
                tx.transitionTo(new LocalDisconnect(ctx, swim, transport, encryptor, currentLeader));

                return;
            }
            tx.handle(() -> routeFaultyPeer(member));
        }

        private void routeFaultyPeer(SwimMember member) {
            LOG.warn("SWIM member faulty: {} (currentLeader={})", member.nodeId(), currentLeader);
            ctx.routeFaulty(member.nodeId(), currentLeader);
        }

        private void handlePeerLeft(NodeId leftNodeId) {
            LOG.warn("SWIM member left: {} (currentLeader={})", leftNodeId, currentLeader);
            ctx.routeFaulty(leftNodeId, currentLeader);
        }

        private void handlePeerConnected(PeerConnected event) {
            var peer = event.peer();
            event.info().onPresent(info -> readdOrMarkAlive(peer, addressOf(info))).onEmpty(() -> readdOrMarkAliveFromTopology(peer));
            ctx.resetFaultyWindow(ctx.nowMs());
            ctx.reportHint(peer, HealthHint.HEALTHY);
        }

        private void readdOrMarkAliveFromTopology(NodeId peer) {
            if (swim.members().containsKey(peer)) {
                swim.markAlive(peer);

                return;
            }
            ctx.resolveSwimAddress(peer, SWIM_PORT_OFFSET).onPresent(addr -> addSeedAndLog(peer, addr));
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
            var now = ctx.nowMs();
            var count = ctx.incrementAndGetFaulty(now);
            var totalMembers = swim.members().size();

            if (totalMembers > 0 && count > totalMembers / 2) {
                LOG.warn("Local disconnect detected: {}/{} peers FAULTY — suppressing topology drain for {}",
                         count,
                         totalMembers,
                         member.nodeId().id());
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
                case LeaderChanged lc -> handleLeaderChanged(lc, tx);
                // A bare gossip PeerJoined is NOT proof local connectivity is restored, so it
                // must NOT recover from LocalDisconnect nor assert HEALTHY (that would resurrect
                // an unreachable node and wipe FAULTY evidence). Recovery flows only from a real
                // `PeerConnected` (re-established QUIC connection) or probe-ack.
                case PeerJoined _, PeerSuspect _, PeerFaulty _, PeerLeft _, ReportHint _ -> tx.ignore();
                case StartRequested _, ProtocolReady _, StartFailed _ -> tx.ignore();
            }
        }

        private void recoverOnPeerConnected(PeerConnected event,
                                            TransitionRequest<SwimHealthState, SwimHealthEvents> tx) {
            LOG.info("Network recovered from local disconnect via {}",
                     event.peer().id());
            tx.transitionTo(new Running(ctx, swim, transport, encryptor, currentLeader),
                            () -> applyPeerConnectedRecovery(event));
        }

        private void applyPeerConnectedRecovery(PeerConnected event) {
            var peer = event.peer();
            event.info().onPresent(info -> readdOrMarkAlive(peer,
                                                            SwimHealthContext.toSwimAddress(info, SWIM_PORT_OFFSET))).onEmpty(() -> readdOrMarkAliveFromTopology(peer));
            ctx.resetFaultyWindow(ctx.nowMs());
            ctx.reportHint(peer, HealthHint.HEALTHY);
        }

        private void readdOrMarkAliveFromTopology(NodeId peer) {
            if (swim.members().containsKey(peer)) {
                swim.markAlive(peer);

                return;
            }
            ctx.resolveSwimAddress(peer, SWIM_PORT_OFFSET).onPresent(addr -> addSeedAndLog(peer, addr));
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

        private void handleLeaderChanged(LeaderChanged event, TransitionRequest<SwimHealthState, SwimHealthEvents> tx) {
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
