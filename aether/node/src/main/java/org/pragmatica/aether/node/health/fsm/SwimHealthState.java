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
            // A completed QUIC channel IS transport-plane liveness proof. For a KNOWN SWIM
            // member we promote it ALIVE via `markAliveFromTransport`, which is tombstone-gated
            // inside SwimProtocol: only a proven-healthy-then-silently-dead ("black-holed")
            // id is refused (#231 — not resurrected off a stale/reopened channel). A
            // never-tombstoned member (cold-start seed / live flap) is promoted so its SUSPECT
            // window survives until the first probe-ack — closing the formation race where
            // startupDelay ≈ suspectTimeout would otherwise evict a reachable follower.
            // A GENUINELY-UNKNOWN peer is re-added so it can be probed.
            var peer = event.peer();
            if (swim.members().containsKey(peer)) {
                promoteKnownMember(peer);

                return;
            }
            event.info().onPresent(info -> addSeedAndLog(peer, addressOf(info)))
                        .onEmpty(() -> readdUnknownFromTopology(peer));
        }

        private void promoteKnownMember(NodeId peer) {
            swim.markAliveFromTransport(peer);
            LOG.debug("PeerConnected for known SWIM member {} — transport liveness proof, markAliveFromTransport (tombstone-gated)", peer.id());
        }

        private void readdUnknownFromTopology(NodeId peer) {
            ctx.resolveSwimAddress(peer, SWIM_PORT_OFFSET).onPresent(addr -> addSeedAndLog(peer, addr));
        }

        private void addSeedAndLog(NodeId peer, InetSocketAddress address) {
            swim.addSeedMember(peer, address);
            LOG.info("Re-added SWIM member {} at {} after disconnect recovery", peer.id(), address);
        }

        private static InetSocketAddress addressOf(NodeInfo info) {
            return SwimHealthContext.toSwimAddress(info, SWIM_PORT_OFFSET);
        }

        private void stopProtocolAndTransport() {
            swim.stop();
            transport.stop();
        }
    }
}
