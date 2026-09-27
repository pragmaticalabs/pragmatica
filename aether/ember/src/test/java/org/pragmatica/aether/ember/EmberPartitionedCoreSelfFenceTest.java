// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    /// Past AetherNode.COLD_BOOT_CONVERGENCE_WINDOW_MS (75 s), measured from cluster start.
    private static final long COLD_BOOT_CLEARANCE_MS = 80_000L;
    /// The pre-#1390 measurement was ~18 s; the bound leaves room for a loaded CI host.
    private static final long FENCE_BOUND_MS = 30_000L;
    private static final long OBSERVE_MS = 60_000L;

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

    private void startCluster() {
        var basePort = freeBasePort();
        cluster = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "psf");
        var startedAt = System.currentTimeMillis();

        assertThat(cluster.start().await(START_BOUND).fold(Cause::message, _ -> "started")).isEqualTo("started");
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

    private static int freeBasePort() {
        for (int base = FIRST_CANDIDATE_BASE; base <= LAST_CANDIDATE_BASE; base += CANDIDATE_STEP) {
            if (blockIsFree(base)) {
                return base;
            }
        }
        throw new AssertionError("no free port block between " + FIRST_CANDIDATE_BASE + " and " + LAST_CANDIDATE_BASE);
    }

    private static boolean blockIsFree(int base) {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (!(udpFree(base + slot) && tcpFree(base + slot) && tcpFree(base + MGMT_OFFSET + slot)
                  && tcpFree(base + APP_HTTP_OFFSET + slot))) {
                return false;
            }
        }
        return true;
    }

    private static boolean tcpFree(int port) {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(loopback(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean udpFree(int port) {
        try (var socket = new DatagramSocket(null)) {
            socket.setReuseAddress(false);
            socket.bind(loopback(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }
}
