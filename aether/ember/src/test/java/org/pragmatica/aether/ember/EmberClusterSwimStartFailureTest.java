// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.util.Map;

import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.assertj.core.api.Assertions.assertThat;

/// #1308 — `EmberCluster.start()` must SETTLE, with the bind failure as its cause, when one node's
/// SWIM UDP port is already taken. The mirror of [EmberClusterPartialStartFailureTest] for the
/// SWIM port instead of the management port.
///
/// A node's SWIM starts at transport-ready, from a `whenReady` hook. Before the join in
/// `AetherNode.startClusterUnlessSwimFails`, that hook discarded the SWIM start's outcome: the node's
/// own `jvmExit` (here `EmberCluster.handleSelfDrain`) stopped and deregistered the node, the other
/// two formed a cluster, and node 1's `start()` — settled only by consensus formation, which a stopped
/// node never reaches — stayed pending forever. `Promise.allOf` never settled and `firstFailure` never
/// fired, so `start()` hung with no bound (rev1343 BLOCKING-1: measured at 91.9 s on a 90 s bound).
///
/// The pin is on the CAUSE, not merely on "it failed": a bounded `await` returns a `Timeout` failure
/// too, and that is exactly the hang. Reverting the join turns this red with the `START_BOUND`
/// `Timeout` cause instead of `Address already in use`.
class EmberClusterSwimStartFailureTest {
    private static final int BASE_PORT = 26300;
    private static final int BASE_MGMT_PORT = 26340;
    private static final int BASE_APP_HTTP_PORT = 26380;
    private static final String NODE_PREFIX = "swimfail";
    /// Well above the measured green (node 1's stop plus the abort's bounded stops of the other two),
    /// well below the 90 s the reviewer's probe hung for: a `Timeout` here IS the hang.
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(30).seconds();
    private static final long RECLAIM_WAIT_MS = 5_000;

    private EmberCluster cluster;

    /// By the time this runs `abortStart` has stopped every node; a green result here is evidence
    /// that the second stop is idempotent, as in the sibling test.
    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped"))
                .describedAs("stopping an already-aborted cluster must complete within %s", STOP_BOUND)
                .isEqualTo("stopped");
        }
    }

    @Test
    @Timeout(150)
    void start_settlesWithTheBindFailure_whenOneNodeCannotBindItsSwimPort() throws IOException {
        // Slots are assigned in node order: node 1 -> BASE_PORT; its SWIM listener is that + offset.
        var node1SwimPort = BASE_PORT + CoreSwimHealthDetector.SWIM_PORT_OFFSET;

        try (var heldSwim = new DatagramSocket(node1SwimPort)) {
            cluster = emberCluster(3, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, NODE_PREFIX);
            var startedAt = System.nanoTime();
            var outcome = cluster.start().await(START_BOUND).fold(Cause::message, _ -> "started");
            var elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(outcome)
                .describedAs("start() settled after %d ms with: %s", elapsedMs, outcome)
                .contains("Address already in use")
                .doesNotContain("not resolved within");
            assertThat(cluster.status().nodes())
                .describedAs("the abort clears the live registry (#913)")
                .isEmpty();
            assertThat(cluster.lastStartFailure()
                              .map(EmberCluster.StartFailure::nodeFailures)
                              .or(Map.of()))
                .describedAs("the retained snapshot names the node whose SWIM could not bind")
                .hasEntrySatisfying(NODE_PREFIX + "-1",
                                    message -> assertThat(message).contains("Address already in use"));
        }
        // Every node was stopped as part of the abort: node 1's management port and the survivors'
        // are reclaimable, and so is node 1's QUIC (UDP) port.
        for (int slot = 0; slot < 3; slot++) {
            assertReclaimableTcp(BASE_MGMT_PORT + slot);
        }
        assertReclaimableUdp(BASE_PORT);
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
