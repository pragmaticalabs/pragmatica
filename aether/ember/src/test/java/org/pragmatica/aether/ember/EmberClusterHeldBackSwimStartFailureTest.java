// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.util.List;
import java.util.Set;

import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.assertj.core.api.Assertions.assertThat;

/// #1308 (rev1343 MEDIUM-2) — a held-back node whose SWIM port is taken when `startHeldBackNodes()`
/// releases it must be STOPPED, and the release must FAIL with the bind failure.
///
/// The held-back path registers a node in the running set only once its `start()` succeeds, so the
/// node's own `jvmExit` (`EmberCluster.handleSelfDrain`, which removes-then-stops) found nothing to
/// remove and stopped nothing: the node kept running SWIM-less with its QUIC and management ports
/// bound — the #1308 symptom surviving on this path — while `startHeldBackNodes()` hung on a start
/// promise nothing would ever settle.
///
/// Two independent pins. The join in `AetherNode.startClusterUnlessSwimFails` makes the release
/// SETTLE (reverting it: `Timeout`, not `Address already in use`). `EmberCluster.stopFailedHeldBackNode`
/// makes the failure REACH the node (reverting it: the release settles, but the node's management
/// port is still bound).
class EmberClusterHeldBackSwimStartFailureTest {
    /// The shared Ember pool (EmberTestPorts.POOL_*), below the Linux ephemeral floor.
    /// #939: a probed block, not fixed ports: this test's own failure mode IS a bind failure, so a collision with
    /// another process would read as the behaviour under test. The port it occupies on purpose it binds itself.
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(3, 40, 80);
    private static final String NODE_PREFIX = "heldswim";
    private static final String HELD_BACK_ID = NODE_PREFIX + "-3";
    private static final int HELD_BACK_SLOT = 2;
    private static final TimeSpan FORMATION_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan RELEASE_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(30).seconds();
    private static final long RECLAIM_WAIT_MS = 5_000;

    private EmberCluster cluster;
    private int basePort;
    private int baseMgmtPort;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped"))
                .describedAs("stopping the cluster must complete within %s", STOP_BOUND)
                .isEqualTo("stopped");
        }
    }

    @Test
    @Timeout(300)
    void startHeldBackNodes_stopsTheNodeAndFailsWithTheBindFailure_whenItsSwimPortIsTaken() throws IOException {
        try (var held = formedHoldingTheHeldBackSwimPort()) {
            var startedAt = System.nanoTime();
            var outcome = cluster.startHeldBackNodes().await(RELEASE_BOUND).fold(Cause::message, _ -> "started");
            var elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(outcome)
                .describedAs("startHeldBackNodes() settled after %d ms with: %s", elapsedMs, outcome)
                .contains("Address already in use")
                .doesNotContain("not resolved within");
            assertThat(cluster.status().nodes())
                .describedAs("the failed node never joins the running set")
                .noneMatch(node -> node.id().equals(HELD_BACK_ID));
            assertThat(cluster.heldBackNode(HELD_BACK_ID).isPresent())
                .describedAs("the failed node is no longer held back either")
                .isFalse();
        }
        // The failed node was stopped: its management (TCP) and QUIC (UDP) ports are reclaimable
        // while the two formed nodes keep theirs.
        assertReclaimableTcp(baseMgmtPort + HELD_BACK_SLOT);
        assertReclaimableUdp(basePort + HELD_BACK_SLOT);
        assertThat(cluster.status().nodes())
            .describedAs("the formed pair keeps running")
            .hasSize(2);
    }

    /// The 2-of-3 formation is the fixture and the test binds the held-back node's SWIM port itself; both race the
    /// probe, so a collision before formation retries on a fresh block. The release of the held-back node, after this,
    /// is NOT retried: its bind failure is the behaviour under test. The port is held from before formation, which is
    /// equivalent: the held-back node binds nothing until `startHeldBackNodes()`.
    private EmberTestPorts.Held formedHoldingTheHeldBackSwimPort() {
        var failure = "";

        for (int attempt = 1; attempt <= EmberTestPorts.START_ATTEMPTS; attempt++) {
            var held = EmberTestPorts.hold(PORTS,
                                           List.of(EmberTestPorts.Hold.udp(HELD_BACK_SLOT
                                                                           + CoreSwimHealthDetector.SWIM_PORT_OFFSET)));

            basePort = held.base();
            baseMgmtPort = basePort + PORTS.mgmtOffset();
            cluster = emberCluster(3, basePort, baseMgmtPort, basePort + PORTS.appOffset(), NODE_PREFIX);
            // The two started nodes are the genesis roster (#1526: a held-back node is not a genesis
            // member, because genesis forms only when every member announces it), so they form alone.
            var formed = cluster.start(Set.of(HELD_BACK_ID)).await(FORMATION_BOUND).fold(Cause::message, _ -> "started");

            if ("started".equals(formed)) {
                return held;
            }

            failure = formed;
            var stopped = cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped");

            cluster = null;
            held.close();
            assertThat(stopped).describedAs("the failed fixture start must stop cleanly before another is tried")
                               .isEqualTo("stopped");
            assertThat(EmberTestPorts.isBindCollision(formed))
                .describedAs("2-of-3 formation is the fixture; it must succeed or the test proves nothing: %s", formed)
                .isTrue();
        }

        throw new AssertionError("every fixture start lost a port between probe and bind; last: " + failure);
    }

    /// A closed channel's port can trail the stop promise by a few milliseconds; poll for it, bounded.
    private static void assertReclaimableTcp(int port) {
        assertThat(reclaimable(() -> new ServerSocket(port).close()))
            .describedAs("TCP %d must be reclaimable within %d ms", port, RECLAIM_WAIT_MS)
            .isTrue();
    }

    private static void assertReclaimableUdp(int port) {
        assertThat(reclaimable(() -> new DatagramSocket(port).close()))
            .describedAs("UDP %d must be reclaimable within %d ms", port, RECLAIM_WAIT_MS)
            .isTrue();
    }

    private static boolean reclaimable(Bind bind) {
        var deadline = System.nanoTime() + RECLAIM_WAIT_MS * 1_000_000L;

        while (true) {
            try {
                bind.bind();

                return true;
            } catch (IOException e) {
                if (System.nanoTime() >= deadline) {
                    return false;
                }
                sleepQuietly();
            }
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface Bind {
        void bind() throws IOException;
    }
}
