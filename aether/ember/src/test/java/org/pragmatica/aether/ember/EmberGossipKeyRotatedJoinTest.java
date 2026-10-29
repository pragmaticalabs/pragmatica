// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GossipKeyRotationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.GossipKeyRotationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1200 pin on a REAL in-JVM cluster: after the cluster has rotated its gossip key, the replacement that auto-heal provisions for a crashed core
/// REJOINS and is SWIM-present in the leader's view within a bound, and sees a surviving core itself.
///
/// This pins behaviour that already holds (measured on `c7a53bb7de`, before any change of #1200): the replacement boots on its
/// `cluster_secret`-derived key, which the rotated cluster rejects, so its SWIM gossip is dropped for about a second; it dials its configured
/// peers ITSELF after a 2 s isolation grace (`MembershipAssembly.bootstrapInitiator`), forms quorum, replays the rotation record and recovers.
/// The issue's "the cycle closes" is not what happens. Found by removal, one candidate at a time, on this pin: removing the FSM descriptor seed
/// from the configured topology (`MembershipAssembly`, "Item 1") alone, or the SWIM seeding alone, leaves it green because the two feeds are
/// redundant; removing BOTH turns it red, and so does switching the isolation initiator rule off (the replacement then waits for peers to
/// dial it). A change that made the replacement wait on SWIM before dialing, or that kept the rotation record from reaching it, turns it red too.
///
/// The seeds are named `aa-N` so that they sort below the replacement's `aether-node-...` id: production replacement ids are time-ordered and
/// sort above their seeds, and QUIC's single-dialer rule has the lower id initiate.
///
/// **Control.** The crashed core is blackholed and stays in the cluster registry. The same presence predicate must read it as ABSENT, which
/// proves the predicate can say no; a predicate that read every node as present would pass the replacement for the wrong reason.
@PortBudget
class EmberGossipKeyRotatedJoinTest {
    private static final int SIZE = 3;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long HEAL_BOUND_MS = 300_000L;
    private static final long JOIN_BOUND_MS = 30_000L;
    private static final long SETTLE_MS = 20_000L;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(1200)
    void afterAGossipKeyRotation_anAutoHealReplacement_rejoinsAndIsSwimPresent() {
        cluster = EmberTestPorts.startedCluster(new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                          EmberTestPorts.POOL_LAST,
                                                                          EmberTestPorts.POOL_STEP,
                                                                          2 * SIZE + 4,
                                                                          MGMT_OFFSET,
                                                                          APP_HTTP_OFFSET),
                                                basePort -> emberCluster(SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "aa"),
                                                START_BOUND);
        var leader = awaitLeader();
        var originals = cluster.allNodes().stream().map(AetherNode::self).collect(Collectors.toSet());
        var victim = cluster.allNodes().stream().filter(node -> !node.self().equals(leader.self())).findFirst().orElseThrow().self();

        rotateGossipKey(leader);
        awaitCondition("every node holds the rotation record",
                       HEAL_BOUND_MS,
                       () -> cluster.allNodes().stream().allMatch(node -> node.kvStore().get(GossipKeyRotationKey.gossipKeyRotationKey()).isPresent()));
        // The crashed core goes silent (no SWIM leave), so auto-heal has to mint its replacement.
        cluster.blackhole(victim.id()).await(START_BOUND);

        var replacement = awaitReplacement(originals);

        awaitCondition("the leader's SWIM view holds the replacement as present within the join bound", JOIN_BOUND_MS, () -> leaderSees(replacement.self()));
        awaitCondition("the replacement's own SWIM view holds a surviving original as present within the join bound",
                       JOIN_BOUND_MS,
                       () -> replacementSees(replacement, originals, victim));
        sleep(SETTLE_MS);

        assertThat(leaderSees(replacement.self())).as("the replacement is still present in the leader's SWIM view").isTrue();
        assertThat(replacement.membershipView().presentMembers().contains(victim))
            .as("control: the crashed core is not present in the replacement's view, so the presence predicate can say no")
            .isFalse();
        assertThat(leaderSees(victim)).as("control: nor in the leader's view").isFalse();
    }

    private AetherNode awaitReplacement(Set<NodeId> originals) {
        var found = new AetherNode[1];

        awaitCondition("auto-heal provisions a replacement for the crashed core",
                       HEAL_BOUND_MS,
                       () -> {
                           found[0] = cluster.allNodes().stream().filter(node -> !originals.contains(node.self())).findFirst().orElse(null);

                           return found[0] != null;
                       });

        return found[0];
    }

    private boolean leaderSees(NodeId replacement) {
        return cluster.allNodes().stream().filter(AetherNode::isLeader).anyMatch(leader -> leader.membershipView().isPresent(replacement));
    }

    private static boolean replacementSees(AetherNode replacement, Set<NodeId> originals, NodeId victim) {
        return replacement.membershipView().presentMembers().stream().anyMatch(id -> originals.contains(id) && !id.equals(victim));
    }

    @SuppressWarnings("unchecked")
    private void rotateGossipKey(AetherNode leader) {
        var key = new byte[32];

        new SecureRandom().nextBytes(key);
        var value = GossipKeyRotationValue.gossipKeyRotationValue(1, Base64.getEncoder().encodeToString(key));
        var command = (KVCommand<AetherKey>) (KVCommand<?>) new KVCommand.Put<>(GossipKeyRotationKey.gossipKeyRotationKey(), value);

        leader.<Object> apply(List.of(command)).await(START_BOUND).onFailure(cause -> {
            throw new AssertionError(cause.message());
        });
    }

    private AetherNode awaitLeader() {
        var found = new AetherNode[1];

        awaitCondition("a leader is elected", HEAL_BOUND_MS, () -> {
            found[0] = cluster.allNodes().stream().filter(AetherNode::isLeader).findFirst().orElse(null);

            return found[0] != null;
        });

        return found[0];
    }

    private static void awaitCondition(String what, long boundMs, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + boundMs;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what);
            }

            sleep(250);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
