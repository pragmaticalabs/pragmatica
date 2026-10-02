// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TransportObservation.ObservationSource;
import org.pragmatica.consensus.topology.TransportObservation.PeerObservedFaulty;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1748 — a follower that loses ONLY its own view of a healthy leader must not depose it, and a
/// genuinely dead leader must still be replaced. Real nodes, real QUIC and Rabia; the follower's loss is
/// injected the way the rc4 cloud run (12-network S05) produced it: its inbound traffic from the leader
/// is dropped and its failure detector reports the leader gone, while every other node still reaches the
/// leader. Before the fix the follower went `Led -> NodeGone(leader) -> ReElecting`, proposed, and the
/// majority that could still reach it committed the challenger.
///
/// The control is half of the pin: it proves the pre-vote's answers actually cross the wire, because a
/// follower's round can only conclude "proceed" on affirmative doubt from peers — a dead wire would keep a
/// dead leader in office, and the first test alone would pass vacuously on it.
class EmberFollowerLinkLossKeepsLeaderTest {
    private static final int CLUSTER_SIZE = 3;
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    /// Disjoint from every other Ember/Forge test's candidate range (highest below is 52900).
    private static final int FIRST_CANDIDATE_BASE = 54100;
    private static final int LAST_CANDIDATE_BASE = 55900;
    private static final int CANDIDATE_STEP = 200;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(FIRST_CANDIDATE_BASE,
                                                                                LAST_CANDIDATE_BASE,
                                                                                CANDIDATE_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long POLL_MS = 250L;
    private static final long LEADER_BOUND_MS = 90_000L;
    /// Well past the election staircase (2 s base + rank x 1 s) plus a Rabia round: at the unfixed base the
    /// follower has proposed, and the cluster has committed, inside the first ~6 s.
    private static final long HOLD_MS = 25_000L;
    /// A killed leader is detected by SWIM in about 16 s; the bound leaves room for a loaded host.
    private static final long REPLACEMENT_BOUND_MS = 90_000L;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.allNodes().forEach(node -> node.setInboundFaultFilter((_, _) -> true));
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(420)
    void followerLosingOnlyItsOwnViewOfTheLeader_doesNotDeposeIt() {
        startCluster();
        var leader = awaitAgreedLeader();
        var follower = cluster.allNodes().stream().filter(node -> !node.self().id().equals(leader)).findFirst().orElseThrow();
        var committedBefore = committedLeaderSequence(follower);

        assertThat(committedBefore).as("control: a leader is committed before the fault").isPositive();

        loseLeader(follower, leader);
        var deadline = System.currentTimeMillis() + HOLD_MS;

        while (System.currentTimeMillis() < deadline) {
            assertThat(cluster.currentLeader().or("none")).as("the leader stays the leader while one follower's view is broken")
                                                          .isEqualTo(leader);
            assertThat(follower.isLeader()).as("the follower with the broken view never becomes leader").isFalse();
            sleep(POLL_MS);
        }

        assertThat(committedLeaderSequence(follower)).as("no new leader was committed: the LeaderKey sequence is unchanged")
                                                     .isEqualTo(committedBefore);
        cluster.allNodes()
               .forEach(node -> assertThat(node.leader().map(NodeId::id).or("none")).as("%s still follows the original leader", node.self().id())
                                                                                     .isEqualTo(leader));
    }

    @Test
    @Timeout(420)
    void killedLeader_isReplaced_andThePreVoteAnswersCrossTheWire() {
        startCluster();
        var leader = awaitAgreedLeader();
        var started = System.currentTimeMillis();

        assertThat(cluster.killNode(leader, false).await(STOP_BOUND).isSuccess()).as("the leader is killed").isTrue();
        var replacement = awaitReplacement(leader);

        System.out.println("pre-vote-control: leader " + leader + " replaced by " + replacement + " in " + (System.currentTimeMillis() - started) + " ms");
        assertThat(replacement).as("a dead leader is replaced").isNotEqualTo(leader);
    }

    private void startCluster() {
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                basePort -> emberCluster(CLUSTER_SIZE,
                                                                         basePort,
                                                                         basePort + MGMT_OFFSET,
                                                                         basePort + APP_HTTP_OFFSET,
                                                                         "pvote"),
                                                START_BOUND);
    }

    /// The follower stops hearing the leader (inbound drop) and its failure detector reports the leader gone —
    /// the exact event the cloud run's minority node acted on. Everyone else is untouched.
    private void loseLeader(AetherNode follower, String leader) {
        var leaderId = cluster.getNode(leader).unwrap().self();
        var remaining = new ArrayList<NodeId>(cluster.allNodes().stream().map(AetherNode::self).toList());

        remaining.remove(leaderId);
        follower.setInboundFaultFilter((peer, _) -> !peer.equals(leaderId));
        runtime(follower).leaderManager().peerObservedFaulty(new PeerObservedFaulty(leaderId, remaining, ObservationSource.SWIM));
    }

    private String awaitAgreedLeader() {
        var deadline = System.currentTimeMillis() + LEADER_BOUND_MS;

        while (System.currentTimeMillis() < deadline) {
            var leader = cluster.currentLeader();

            if (leader.isPresent() && cluster.allNodes().stream().allMatch(node -> agrees(node, leader.or("none")))) {
                return leader.or("none");
            }

            sleep(POLL_MS);
        }

        throw new AssertionError("no leader every node agrees on within " + LEADER_BOUND_MS + " ms");
    }

    private static boolean agrees(AetherNode node, String leader) {
        return node.leader().map(NodeId::id).or("none").equals(leader);
    }

    private String awaitReplacement(String dead) {
        var deadline = System.currentTimeMillis() + REPLACEMENT_BOUND_MS;

        while (System.currentTimeMillis() < deadline) {
            var leader = cluster.currentLeader().or("none");

            if (!"none".equals(leader) && !dead.equals(leader) && cluster.allNodes().stream().allMatch(node -> agrees(node, leader))) {
                return leader;
            }

            sleep(POLL_MS);
        }

        throw new AssertionError("dead leader " + dead + " not replaced within " + REPLACEMENT_BOUND_MS + " ms");
    }

    private static long committedLeaderSequence(AetherNode node) {
        return node.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).map(LeaderValue::viewSequence).or(0L);
    }

    private static RabiaNode<?> runtime(AetherNode node) {
        return Result.lift(() -> {
            var accessor = node.getClass().getDeclaredMethod("clusterNode");

            accessor.setAccessible(true);
            return (RabiaNode<?>) accessor.invoke(node);
        }).unwrap();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}
