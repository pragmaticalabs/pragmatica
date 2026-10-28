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

import org.apache.logging.log4j.Level;
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
import org.pragmatica.swim.NettySwimTransport;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1200 on a REAL in-JVM cluster: after the cluster has rotated its gossip key, the replacement that auto-heal provisions for a crashed core
/// boots on the cluster's CURRENT key, so it is understood by the cluster from its first datagram, and is seen alive by it.
///
/// **What this test does and does not pin.** The leader reads the current key from the replicated rotation record and hands it to the
/// compute provider in the provisioning context; the provider boots the node on it. The observable consequence is that no peer ever logs
/// `Failed to decrypt gossip` for the replacement. Measured on the base (`c7a53bb7de`, same scenario, same file): the replacement's datagrams
/// were rejected for about a second ("Unknown gossip key ID"), after which it joined consensus over QUIC from its configured peers, replayed
/// the rotation record, rotated, and SWIM recovered by itself: it was seen alive within 3.5 s on the base as well. So on this harness the
/// rotation costs a transient, not a stranded node; the issue's "the cycle closes" is NOT reproduced here. What fails on the base is the
/// absence of decrypt failures, which is exactly what the delivery changes.
///
/// The seeds are named `aa-N` so that they sort below the replacement's `aether-node-...` id: production replacement ids are time-ordered
/// and sort above their seeds, and QUIC's single-dialer rule has the lower id initiate. That ordering did not strand the replacement on the
/// base either.
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
    private static final String DECRYPT_FAILURE = "Failed to decrypt gossip";

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(1200)
    void afterAGossipKeyRotation_anAutoHealReplacement_speaksTheClustersKey_andIsSeenAlive() {
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
        // Opened AFTER the rotation has been applied everywhere, so only datagrams about the replacement can be counted; the crashed core is
        // blackholed first so its own silence is not mistaken for traffic.
        cluster.blackhole(victim.id()).await(START_BOUND);

        try (var transportLog = LogCapture.capture(NettySwimTransport.class, Level.WARN, true)) {
            var replacement = awaitReplacement(originals);

            awaitCondition("the leader's SWIM view holds the replacement as present within the join bound", JOIN_BOUND_MS, () -> leaderSees(replacement.self()));
            awaitCondition("the replacement's own SWIM view holds a surviving original as present within the join bound",
                           JOIN_BOUND_MS,
                           () -> replacementSees(replacement, originals, victim));
            sleep(SETTLE_MS);

            assertThat(leaderSees(replacement.self())).as("the replacement is still present in the leader's SWIM view").isTrue();
            assertThat(transportLog.messages().stream().filter(message -> message.contains(DECRYPT_FAILURE)).toList())
                .as("#1200: nothing the replacement sent was undecryptable by a peer, and it could decrypt every peer")
                .isEmpty();
        }
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
