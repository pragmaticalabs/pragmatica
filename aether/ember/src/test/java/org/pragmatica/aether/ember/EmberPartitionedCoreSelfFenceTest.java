// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossSnapshot;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1560 — a single black-holed core of a five-core cluster must fence itself (self-drain and leave the
/// cluster) within a bounded interval, whether or not it is the leader. Before #1390 it self-drained about
/// 18 s after isolation; after #1390 a black-holed non-leader stayed registered and unfenced indefinitely,
/// so on heal it reclaimed stream ownership or served a stale view.
///
/// The black-hole starts only after the cold-boot convergence window has elapsed on every node, because a
/// quorum-loss self-drain is deliberately deferred during that window (A6); the property under test is the
/// steady-state fence, not the boot deferral.
class EmberPartitionedCoreSelfFenceTest {
    private static final Logger log = LoggerFactory.getLogger(EmberPartitionedCoreSelfFenceTest.class);
    private static final int CLUSTER_SIZE = 5;
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    /// Disjoint from every other Ember test's candidate range (25600-31500, 34000-34800, 36100-37900, 45100).
    private static final int FIRST_CANDIDATE_BASE = 38100;
    private static final int LAST_CANDIDATE_BASE = 39900;
    private static final int CANDIDATE_STEP = 200;
    /// #1667: probed through the shared EmberTestPorts, which also probes each node's SWIM UDP port.
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(FIRST_CANDIDATE_BASE,
                                                                                LAST_CANDIDATE_BASE,
                                                                                CANDIDATE_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    /// Past AetherNode.COLD_BOOT_CONVERGENCE_WINDOW_MS (75 s), measured from cluster start.
    private static final long COLD_BOOT_CLEARANCE_MS = 80_000L;
    /// The pre-#1390 measurement was ~18 s; the bound leaves room for a loaded CI host.
    private static final long FENCE_BOUND_MS = 30_000L;
    private static final long OBSERVE_MS = 60_000L;
    /// How long one flap may stay black-holed while it arms. A flap is armed when a survivor sees the flapper
    /// SUSPECT AND the flapper's own quorum-loss detector has gone below threshold (it sees its peers SUSPECT
    /// too) — the path the #1560 re-check guards. It is healed the moment both hold, so it never reaches
    /// FAULTY, which in the black-hole tests follows SUSPECT by several seconds more. Round-robin probing
    /// (1 s period over four peers, 800 ms ack timeout, then an indirect round) can take about 5 s to suspect
    /// a peer. A fixed 4 s flap missed that in all five cycles of one CI run: the 12 s flap period is a
    /// multiple of the probe round, so every cycle landed on the same late phase.
    private static final long SUSPECT_BOUND_MS = 10_000L;
    private static final long FLAP_OFF_MS = 8_000L;
    private static final int FLAP_CYCLES = 5;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(420)
    void blackholedNonLeaderCore_selfFences_withinBound() {
        startCluster();
        var leader = cluster.currentLeader().unwrap();
        var target = cluster.allNodes()
                            .stream()
                            .map(node -> node.self().id())
                            .filter(id -> !id.equals(leader))
                            .findFirst()
                            .orElseThrow();

        assertFences(target);
    }

    @Test
    @Timeout(420)
    void blackholedLeaderCore_selfFences_withinBound() {
        startCluster();

        assertFences(cluster.currentLeader().unwrap());
    }

    /// The false-fence guard for the #1560 re-check: a healthy five-core cluster in which ONE non-leader
    /// flaps — black-holed until it is armed (bounded by [#SUSPECT_BOUND_MS]), then healed for [#FLAP_OFF_MS],
    /// [#FLAP_CYCLES] times — must never fence any node. Armed on every cycle, and at the end by every
    /// survivor seeing it MEMBER again, so the test cannot pass by never disturbing the cluster. (Which survivor suspects it varies:
    /// SWIM probes a random member per interval, and the leader alone missed every flap in one run.) Partition mechanism: Ember `blackhole` (#1563: it still lets a
    /// QUIC Hello through, which is irrelevant here because no flap is long enough to reach DEAD).
    @Test
    @Timeout(420)
    void flappingPeer_suspectThenRefutes_noNodeFences() {
        startCluster();
        var leaderId = cluster.currentLeader().unwrap();
        var flapper = cluster.allNodes()
                             .stream()
                             .filter(node -> !node.self().id().equals(leaderId))
                             .findFirst()
                             .orElseThrow();
        var survivors = cluster.allNodes()
                               .stream()
                               .filter(node -> node != flapper)
                               .toList();

        for (int cycle = 0; cycle < FLAP_CYCLES; cycle++) {
            flapper.blackhole(true);
            var armed = awaitArmed(survivors, flapper, SUSPECT_BOUND_MS);
            flapper.blackhole(false);
            assertThat(armed.suspectedBySurvivor())
                .as("arming, cycle %d: a survivor must see the black-holed peer SUSPECT within %d ms", cycle, SUSPECT_BOUND_MS)
                .isTrue();
            assertThat(armed.flapperBelowThreshold())
                .as("arming, cycle %d: the flapper's quorum-loss detector must go below threshold within %d ms",
                    cycle,
                    SUSPECT_BOUND_MS)
                .isTrue();
            sleep(FLAP_OFF_MS);
            assertThat(cluster.nodeCount()).as("no node fenced after flap cycle %d", cycle).isEqualTo(CLUSTER_SIZE);
        }
        sleep(OBSERVE_MS);

        survivors.forEach(node -> assertThat(node.membershipFsm().memberStates().get(flapper.self()))
                                      .as("the flapping peer refuted on %s", node.self().id())
                                      .isEqualTo("Member"));
        assertThat(cluster.nodeCount()).as("no node of a healthy cluster with one flapping peer fences")
                                       .isEqualTo(CLUSTER_SIZE);
    }

    private record Armed(boolean suspectedBySurvivor, boolean flapperBelowThreshold) {}

    private static Armed awaitArmed(List<AetherNode> observers, AetherNode flapper, long boundMs) {
        var deadline = System.currentTimeMillis() + boundMs;
        var suspected = false;
        var below = false;

        while (!(suspected && below) && System.currentTimeMillis() < deadline) {
            suspected |= observers.stream()
                                  .anyMatch(observer -> "Suspect".equals(observer.membershipFsm().memberStates().get(flapper.self())));
            below |= flapper.quorumLossSnapshot().map(QuorumLossSnapshot::belowThreshold).or(false);
            if (!(suspected && below)) {
                sleep(100);
            }
        }

        return new Armed(suspected, below);
    }

    private void startCluster() {
        var startedAt = System.currentTimeMillis();

        // #1667: a port lost between the probe and the bind moves the start to a fresh block instead of failing.
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                basePort -> emberCluster(CLUSTER_SIZE,
                                                                         basePort,
                                                                         basePort + MGMT_OFFSET,
                                                                         basePort + APP_HTTP_OFFSET,
                                                                         "psf"),
                                                START_BOUND);
        awaitCondition("a leader is elected", 60_000L, () -> cluster.currentLeader().isPresent());
        sleep(Math.max(0, COLD_BOOT_CLEARANCE_MS - (System.currentTimeMillis() - startedAt)));
        assertThat(cluster.nodeCount()).as("control: no node fenced before the black-hole").isEqualTo(CLUSTER_SIZE);
    }

    private void assertFences(String target) {
        var node = cluster.getNode(target).unwrap();
        var blackholedAt = System.currentTimeMillis();

        cluster.blackhole(target).await(STOP_BOUND);
        var fencedAfter = awaitFence(target, node, blackholedAt);

        log.info("#1560 probe: {} fenced after {} ms", target, fencedAfter);
        assertThat(fencedAfter).as("black-holed core %s self-fences within %d ms", target, FENCE_BOUND_MS)
                               .isBetween(0L, FENCE_BOUND_MS);
    }

    private long awaitFence(String target, AetherNode node, long blackholedAt) {
        var deadline = blackholedAt + OBSERVE_MS;

        while (System.currentTimeMillis() < deadline) {
            if (cluster.getNode(target).isEmpty()) {
                return System.currentTimeMillis() - blackholedAt;
            }
            log.info("#1560 probe t+{}ms {}: quorumLoss={} fsm={}",
                     System.currentTimeMillis() - blackholedAt,
                     target,
                     node.quorumLossSnapshot(),
                     node.membershipFsm().memberStates());
            sleep(1_000);
        }

        return -1L;
    }

    private static void awaitCondition(String what, long boundMs, java.util.function.BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + boundMs;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what);
            }
            sleep(250);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
