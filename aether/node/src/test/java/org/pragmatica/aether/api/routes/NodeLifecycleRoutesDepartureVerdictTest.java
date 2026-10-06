// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.ClusterSyncPongSignalFan;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.metrics.MetricObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.http.HttpError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// #1868 — the per-node lifecycle GET answers 404 only for a node whose DEPARTURE membership has committed
/// (`MembershipFsm` state Dead), never for mere absence from the soft readiness view. Wires the PRODUCTION fan
/// and collector exactly as AetherNode does and drives the three ways the leader's view drops a LIVE draining
/// node: a transient QUIC evict (`PeerDisconnected` -> `fan.evict`), three missed pongs (`sweepStale`), and a
/// freshly elected leader's empty map. In each the node is still a tracked, non-departed member, so the answer
/// must be 503 "unknown", never the 404 a drain wait reads as completion. (Found by the v1872 verifier, whose
/// probe asserted the old 404 for all three.)
class NodeLifecycleRoutesDepartureVerdictTest {
    private static final NodeId SELF = new NodeId("core-1");
    private static final NodeId DRAINING = new NodeId("core-2");
    private static final long PING_NANOS = 1_000_000_000L;

    private final AtomicBoolean leader = new AtomicBoolean(true);
    private final AtomicLong clock = new AtomicLong(10 * PING_NANOS);
    private final LeaderManager leaderManager = (LeaderManager) Proxy.newProxyInstance(
        LeaderManager.class.getClassLoader(),
        new Class[]{LeaderManager.class},
        (_, method, _) -> switch (method.getName()) {
            case "isLeader" -> leader.get();
            case "leader" -> leader.get() ? Option.some(SELF) : Option.some(DRAINING);
            default -> null;
        });
    private final ClusterSyncPongSignalFan fan = ClusterSyncPongSignalFan.clusterSyncPongSignalFan(
        leaderManager, ClusterSyncPongSignalFan.ReadyCandidateSink.NOOP, clock::get);
    private final org.pragmatica.aether.deployment.membership.fsm.MembershipFsm fsm = trackedLiveMember();
    private final ClusterSyncCollector collector = wiredCollector();
    private final NodeLifecycleRoutes routes = routes(collector, fsm);

    private static org.pragmatica.aether.deployment.membership.fsm.MembershipFsm trackedLiveMember() {
        var membership = org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.membershipFsm();

        membership.seed(Set.of(DRAINING));
        membership.onSwimHealthy(DRAINING, 7L);

        return membership;
    }

    private ClusterSyncCollector wiredCollector() {
        var network = (ClusterNetwork) Proxy.newProxyInstance(ClusterNetwork.class.getClassLoader(),
                                                              new Class[]{ClusterNetwork.class},
                                                              (_, _, _) -> null);
        var c = ClusterSyncCollector.clusterSyncCollector(SELF, network);

        c.setPongSignalFan(fan);
        c.setReadinessViewContext(leaderManager, clock::get, TimeSpan.timeSpan(1).seconds());

        return c;
    }

    private static NodeLifecycleRoutes routes(ClusterSyncCollector collector, org.pragmatica.aether.deployment.membership.fsm.MembershipFsm fsm) {
        var node = (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                           new Class[]{ManageableNode.class},
                                                           (_, method, _) -> switch (method.getName()) {
                                                               case "metricsCollector" -> collector;
                                                               case "membershipFsm" -> fsm;
                                                               case "leader" -> Option.none();
                                                               case "topologyManager" -> noTopology();
                                                               default -> throw new UnsupportedOperationException(method.getName());
                                                           });

        return NodeLifecycleRoutes.nodeLifecycleRoutes(() -> node, _ -> {}, Set::of);
    }

    private static org.pragmatica.consensus.topology.TopologyManager noTopology() {
        return (org.pragmatica.consensus.topology.TopologyManager) Proxy.newProxyInstance(
            org.pragmatica.consensus.topology.TopologyManager.class.getClassLoader(),
            new Class[]{org.pragmatica.consensus.topology.TopologyManager.class},
            (_, _, _) -> Option.<org.pragmatica.consensus.net.NodeInfo> none());
    }

    private static ClusterSyncPong drainingPong(NodeId sender) {
        return new ClusterSyncPong(sender, new MetricObservation(1L, 1L, 1L, Map.of()), 7L, 0L, 0L, 0L, 0L,
                                   "DRAINING", List.of(), List.of(), List.of(), Option.none());
    }

    private String answer() {
        return routes.getNodeLifecycleForTest(DRAINING.id())
                     .await()
                     .fold(cause -> String.valueOf(((HttpError) cause).status().code()), entry -> entry.state());
    }

    @Test
    void control_leaderHoldingTheDrainingNode_answersDraining() {
        fan.fan(drainingPong(DRAINING));

        assertThat(answer()).isEqualTo("DRAINING");
    }

    /// Item 1: AetherNode.java:4527 routes TransportObservation.PeerDisconnected -> fan.evict. QuicClusterNetwork
    /// emits PeerDisconnected for a transient CONNECTED->EVICTED evict (its own mapping doc, ~:1246), and the
    /// reconnect emits PeerReconnected, which re-adds nothing. Until the node's next pong lands, the leader answers 404.
    @Test
    void transientEviction_ofALiveDrainingNode_answers503UntilItsNextPong() {
        fan.fan(drainingPong(DRAINING));
        fan.evict(DRAINING);

        assertThat(answer()).as("leader's answer between a transient QUIC evict and the next pong: unknown, not gone")
                                .isEqualTo("503");

        fan.fan(drainingPong(DRAINING));
        assertThat(answer()).isEqualTo("DRAINING");
    }

    /// Item 1b: the stale sweep (AetherNode.java:2926) drops a node silent for 3 ping intervals. A draining node is
    /// alive and busy (in-flight quiesce + DHT departure push) for up to its 30 s grace.
    @Test
    void threeMissedPongs_ofALiveDrainingNode_answers503() {
        fan.fan(drainingPong(DRAINING));
        clock.addAndGet(3 * PING_NANOS + 1);
        fan.sweepStale(3 * PING_NANOS);

        assertThat(answer()).isEqualTo("503");
    }

    /// Item 2: authority is `isLeader()` (ClusterSyncCollector.java:776-779), true the instant leadership is held.
    /// The fan only records pongs while leader (ClusterSyncPongSignalFan.java:170) and the sweep runs on every node,
    /// so a node that was a follower for >3 intervals takes leadership with an EMPTY map.
    @Test
    void freshLeader_beforeFirstPongFromTheDrainingNode_answers503() {
        fan.fan(drainingPong(DRAINING));
        leader.set(false);
        clock.addAndGet(3 * PING_NANOS + 1);
        fan.sweepStale(3 * PING_NANOS);
        fan.fan(drainingPong(DRAINING));
        leader.set(true);

        assertThat(collector.hasAuthoritativeReadiness()).isTrue();
        assertThat(answer()).as("new leader's answer before the draining node's first pong to it").isEqualTo("503");
    }

    /// The one verdict that IS a death: membership committed the departure (a SWIM graceful LEAVE; a co-confirmed
    /// FAULTY reaches the same terminal state). Precondition asserted so the answer cannot be 404 for want of a state.
    @Test
    void departedMember_answers404() {
        fan.fan(drainingPong(DRAINING));
        fan.evict(DRAINING);
        fsm.onSwimDeparted(DRAINING, 7L);

        assertThat(fsm.memberStates().get(DRAINING)).as("precondition: membership committed the departure").isEqualTo("Dead");
        assertThat(answer()).isEqualTo("404");
    }

    @Test
    void neverAMember_answers404() {
        var stranger = new NodeId("core-9");

        String status = routes.getNodeLifecycleForTest(stranger.id())
                              .await()
                              .fold(cause -> String.valueOf(((HttpError) cause).status().code()), entry -> entry.state());

        assertThat(status).isEqualTo("404");
    }
}
