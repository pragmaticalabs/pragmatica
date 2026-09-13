// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.ClusterSyncScheduler;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage.Broadcast;
import org.pragmatica.consensus.net.NetworkServiceMessage.ConnectNode;
import org.pragmatica.consensus.net.NetworkServiceMessage.DisconnectNode;
import org.pragmatica.consensus.net.NetworkServiceMessage.ListConnectedNodes;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.consensus.net.quic.QuicPeerStateListener;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.swim.SwimConfig;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMembershipListener;
import org.pragmatica.swim.SwimMessage;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimObservation;
import org.pragmatica.swim.SwimProtocol;
import org.pragmatica.swim.SwimTransport;
import org.pragmatica.swim.TransportObservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1061 round 2 — the chain the review's B1 found, driven through the REAL pieces and the REAL
/// wiring: `SwimProtocol`, `ClusterSyncScheduler` + `ClusterSyncCollector` wired exactly as
/// `AetherNode.assembleNode` wires them (pong listener → scheduler, `AetherNode.pingTimeoutReporter`,
/// `AetherNode.pongResponsiveReporter`), and the QUIC peer-state listener from
/// `AetherNode.quicPeerStateListener`. Only the QUIC link itself is modelled: a connected-peers set
/// plus the listener callbacks a `QuicClusterNetwork` view change fires. Every assertion states the
/// SAFE outcome. Adapted from the review's `Verify1065ChainProbeTest`.
class SwimHintLeaderChainTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId VICTIM = new NodeId("node-victim");
    private static final NodeId GOSSIPER = new NodeId("node-gossiper");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9300);
    private static final InetSocketAddress VICTIM_ADDR = new InetSocketAddress("127.0.0.1", 9301);
    private static final InetSocketAddress GOSSIPER_ADDR = new InetSocketAddress("127.0.0.1", 9302);
    private static final long SUSPECT_TIMEOUT_MS = 5_000L;
    private static final int PING_TIMEOUT_THRESHOLD = 3;

    private final Set<NodeId> liveTransport = new CopyOnWriteArraySet<>();
    private final Set<NodeId> connected = new CopyOnWriteArraySet<>();
    private final List<SwimObservation> observations = new CopyOnWriteArrayList<>();
    private final List<TransportObservation> hints = new CopyOnWriteArrayList<>();
    private SwimProtocol protocol;

    private record Wiring(ClusterSyncCollector collector, ClusterSyncScheduler scheduler, QuicPeerStateListener quic) {}

    @BeforeEach
    void setUp() {
        var config = SwimConfig.swimConfig(timeSpan(40).millis(),
                                           timeSpan(20).millis(),
                                           3,
                                           timeSpan(SUSPECT_TIMEOUT_MS).millis(),
                                           8,
                                           timeSpan(40).millis())
                               .withJoinGrace(timeSpan(0).millis());
        protocol = SwimProtocol.swimProtocol(config, new NullTransport(), new NullListener(), SELF, SELF_ADDR,
                                             () -> false, liveTransport::contains)
                               .unwrap();
        protocol.addObservationListener(observations::add);
        // VICTIM is an established, ever-HEALTHY core member with a CONNECTED link.
        protocol.onMessage(GOSSIPER_ADDR, new Ping(GOSSIPER, 1L, List.of(new MembershipUpdate(VICTIM, MemberState.ALIVE, 0, VICTIM_ADDR))));
        liveTransport.add(VICTIM);
        connected.add(VICTIM);
    }

    /// Required test 1 (review B1, leader evictor): the stall accumulates misses while SWIM still trusts
    /// the victim, the zombie sweep evicts, the re-dial succeeds 29ms later, and the first ClusterSync
    /// tick on the new link must report nothing. The victim is live; the lone first-hand FAULTY is held.
    @Test
    void leaderChain_stallThenEvictAndRedial_firstPingReportsNothing_liveNodeNotTerminalized() throws InterruptedException {
        var wiring = wire(TimeSpan.timeSpan(1).hours(), this::isSwimAlive);

        stall(wiring, 8);
        var evictedAt = evictAndRedial(wiring);
        wiring.scheduler().sendPingsNow();

        assertThat(pingTimeoutHints())
            .as("Misses counted against the evicted link must not produce a PING_TIMEOUT hint on its replacement")
            .isEmpty();
        var elapsed = awaitVerdict(wiring, evictedAt);
        assertThat(count(SwimObservation.DepartedObserved.class))
            .as("A live node whose link healed in 29ms must not be terminalized (elapsed %dms)", elapsed)
            .isZero();
        assertThat(count(SwimObservation.UnknownObserved.class)).isPositive();
        assertThat(elapsed).as("Held on the unfloored window").isGreaterThanOrEqualTo(SUSPECT_TIMEOUT_MS - 100);
    }

    /// Required test 1, production cadence: 1s ClusterSync ticks keep running and the live victim answers
    /// every ping over the new link. Neither the counter nor a hint may terminalize it.
    @Test
    void leaderChain_stallThenRedial_victimPongsOnNewLink_notTerminalized() throws InterruptedException {
        var wiring = wire(TimeSpan.timeSpan(1).seconds(), this::isSwimAlive);

        stall(wiring, 8);
        var evictedAt = evictAndRedial(wiring);
        wiring.scheduler().sendPingsNow();
        var ponger = Thread.ofVirtual().start(() -> pongUntilInterrupted(wiring));
        long elapsed;
        try {
            elapsed = awaitVerdict(wiring, evictedAt);
        } finally {
            ponger.interrupt();
        }

        assertThat(count(SwimObservation.DepartedObserved.class))
            .as("A live, ponging node must not be terminalized (elapsed %dms)", elapsed)
            .isZero();
        assertThat(elapsed).isGreaterThanOrEqualTo(SUSPECT_TIMEOUT_MS - 100);
    }

    /// Required test 2 (R-c): a hung peer keeps its link CONNECTED and never pongs. After a (re)connect the
    /// count grows again in the new link epoch, the PING_TIMEOUT hint fires at the threshold, and it still
    /// floors and vetoes with the link connected.
    @Test
    void hungPeer_linkConnectedNoPongs_hintFiresAtThresholdInNewLinkEpoch_andDeparts() throws InterruptedException {
        var swimTrusts = new AtomicBoolean(true);
        var wiring = wire(TimeSpan.timeSpan(1).hours(), _ -> swimTrusts.get());

        stall(wiring, 5);
        swimTrusts.set(false);
        wiring.quic().onPeerReconnected(VICTIM);
        wiring.scheduler().sendPingsNow();
        wiring.scheduler().sendPingsNow();
        assertThat(pingTimeoutHints()).as("Two misses in the new link epoch stay below the threshold").isEmpty();

        wiring.scheduler().sendPingsNow();
        var hintedAt = System.currentTimeMillis();
        assertThat(pingTimeoutHints())
            .as("The third consecutive miss in the new link epoch reports the hung peer")
            .hasSize(1);
        var elapsed = awaitVerdict(wiring, hintedAt);

        assertThat(count(SwimObservation.DepartedObserved.class))
            .as("Hung-peer detection is preserved: floored and vetoed with the link CONNECTED (elapsed %dms)", elapsed)
            .isEqualTo(1);
        assertThat(elapsed).isLessThan(SUSPECT_TIMEOUT_MS);
    }

    /// Required test 3 (R-b): a PING_TIMEOUT hint is present, then a pong from the peer arrives through the
    /// real collector path. The hint is retracted, so neither the 3s floor nor the veto applies.
    @Test
    void pingTimeoutHint_thenPongArrives_hintRetracted_floorAndVetoWithdrawn() throws InterruptedException {
        var wiring = wire(TimeSpan.timeSpan(1).hours(), _ -> false);

        stall(wiring, PING_TIMEOUT_THRESHOLD);
        var hintedAt = System.currentTimeMillis();
        assertThat(pingTimeoutHints()).as("Control: the hint was recorded").hasSize(1);
        assertThat(protocol.members().get(VICTIM).state()).isEqualTo(MemberState.SUSPECT);

        wiring.collector().onClusterSyncPong(ClusterSyncPong.clusterSyncPong(VICTIM, Map.of()));
        assertThat(hints).contains(new TransportObservation.PeerResponsive(VICTIM));
        var elapsed = awaitVerdict(wiring, hintedAt);

        assertThat(count(SwimObservation.DepartedObserved.class))
            .as("A retracted PING_TIMEOUT hint must not corroborate the lone first-hand FAULTY (elapsed %dms)", elapsed)
            .isZero();
        assertThat(count(SwimObservation.UnknownObserved.class)).isPositive();
        assertThat(elapsed).as("Held on the unfloored window").isGreaterThanOrEqualTo(SUSPECT_TIMEOUT_MS - 100);
    }

    /// Control — #1061 as observed in C2 (non-leader evictor: no ClusterSync ticks at all).
    @Test
    void nonLeaderChain_evictThenRedial_loneFirstHandFaultyNotTerminalized() throws InterruptedException {
        var wiring = wire(TimeSpan.timeSpan(1).hours(), this::isSwimAlive);

        var evictedAt = evictAndRedial(wiring);
        var elapsed = awaitVerdict(wiring, evictedAt);

        assertThat(count(SwimObservation.DepartedObserved.class)).as("elapsed %dms", elapsed).isZero();
        assertThat(elapsed).isGreaterThanOrEqualTo(SUSPECT_TIMEOUT_MS - 100);
    }

    private Wiring wire(TimeSpan interval, Predicate<NodeId> swimTrusts) {
        var network = new ConnectedPeersNetwork(connected);
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, network);
        var scheduler = ClusterSyncScheduler.clusterSyncScheduler(SELF, network, collector, interval,
                                                                  () -> 7L, PING_TIMEOUT_THRESHOLD, () -> Epoch.epoch(7L, 0L));
        collector.setPeerLocallyAlive(swimTrusts);
        collector.addPongListener(pong -> scheduler.onPongReceived(pong.sender()));
        collector.setUnreachableReporter(AetherNode.pingTimeoutReporter(this::deliver));
        collector.addPongListener(AetherNode.pongResponsiveReporter(this::deliver));
        scheduler.onMembershipDecision(MembershipDecision.nodeJoined(VICTIM, List.of(SELF, VICTIM)));
        scheduler.onQuorumStateChange(ClusterStateNotification.active());
        return new Wiring(collector, scheduler, AetherNode.quicPeerStateListener(this::deliver, scheduler::onLinkEstablished));
    }

    private void deliver(TransportObservation hint) {
        hints.add(hint);
        protocol.recordTransportHint(hint.peer(), hint);
    }

    private void stall(Wiring wiring, int ticks) {
        for (var i = 0; i < ticks; i++) {
            wiring.scheduler().sendPingsNow();
        }
    }

    private long evictAndRedial(Wiring wiring) throws InterruptedException {
        var evictedAt = System.currentTimeMillis();

        connected.remove(VICTIM);
        liveTransport.remove(VICTIM);
        wiring.quic().onPeerLeft(VICTIM);
        Thread.sleep(29);
        connected.add(VICTIM);
        liveTransport.add(VICTIM);
        wiring.quic().onPeerReconnected(VICTIM);
        return evictedAt;
    }

    private long awaitVerdict(Wiring wiring, long startedAt) {
        protocol.start();
        try {
            await().atMost(Duration.ofSeconds(12))
                   .until(() -> count(SwimObservation.DepartedObserved.class) > 0 || count(SwimObservation.UnknownObserved.class) > 0);
        } finally {
            protocol.stop();
            wiring.scheduler().stop();
        }
        return System.currentTimeMillis() - startedAt;
    }

    private void pongUntilInterrupted(Wiring wiring) {
        while (!Thread.currentThread().isInterrupted()) {
            wiring.collector().onClusterSyncPong(ClusterSyncPong.clusterSyncPong(VICTIM, Map.of()));
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private boolean isSwimAlive(NodeId peer) {
        return Option.option(protocol.members().get(peer))
                     .map(member -> member.state() == MemberState.ALIVE)
                     .or(false);
    }

    private List<TransportObservation.PeerUnreachable> pingTimeoutHints() {
        return hints.stream()
                    .filter(TransportObservation.PeerUnreachable.class::isInstance)
                    .map(TransportObservation.PeerUnreachable.class::cast)
                    .filter(hint -> hint.cause() == AetherNode.QuicTransportCause.PING_TIMEOUT)
                    .toList();
    }

    private long count(Class<? extends SwimObservation> type) {
        return observations.stream()
                           .filter(type::isInstance)
                           .filter(observation -> observation.peer().equals(VICTIM))
                           .count();
    }

    private static final class NullTransport implements SwimTransport {
        @Override public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {return Promise.success(Unit.unit());}
        @Override public Promise<Unit> start(int port, SwimMessageHandler handler) {return Promise.success(Unit.unit());}
        @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}
    }

    private static final class NullListener implements SwimMembershipListener {
        @Override public void onMemberJoined(SwimMember member) {}
        @Override public void onMemberSuspect(SwimMember member) {}
        @Override public void onMemberFaulty(SwimMember member, boolean firstHand) {}
        @Override public void onMemberLeft(NodeId nodeId) {}
    }

    private static final class ConnectedPeersNetwork implements ClusterNetwork {
        private final Set<NodeId> connected;

        ConnectedPeersNetwork(Set<NodeId> connected) {this.connected = connected;}

        @Override public <M extends ProtocolMessage> Unit broadcast(M message) {return Unit.unit();}
        @Override public void connect(ConnectNode connectNode) {}
        @Override public void disconnect(DisconnectNode disconnectNode) {}
        @Override public void listNodes(ListConnectedNodes listConnectedNodes) {}
        @Override public void handleSend(Send send) {}
        @Override public void handleBroadcast(Broadcast broadcast) {}
        @Override public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {return Unit.unit();}
        @Override public Promise<Unit> start() {return Promise.success(Unit.unit());}
        @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}
        @Override public int connectedNodeCount() {return connected.size();}
        @Override public Set<NodeId> connectedPeers() {return Set.copyOf(connected);}
        @Override public Option<Server> server() {return Option.none();}
    }
}
