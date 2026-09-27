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
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1554 — a cold-start cluster keeps the single-dialer order while genesis is pending.
///
/// Every core of a cold start runs genesis view agreement and holds no voter configuration until the
/// agreement installs one. The staged-core rule (#1390) lets a core without a configuration that
/// includes it dial its configured peers regardless of the single-dialer order. Applied to genesis
/// candidates, every pair dialed both ways: both Hellos completed, the later one superseded the first,
/// and on CI a superseded FORWARD lane left bh-1 unreachable for forwarded requests for four minutes
/// (MembershipBlackHoleSpikeTest setUp). A pair that connects once completes one handshake on each
/// side, so a five-core cold start completes exactly 5 * 4 handshakes; without the fix this test read 29,
/// and the Forge logs showed 40–42 Hello completions where the base showed 20.
///
/// The joiner arm pins the other side of the same rule. A core with a fresh id joining a formed
/// cluster also starts with no configuration, and the formed cores keep it outside their SWIM scope, so
/// they never dial it. The designated dialer of a pair is the LOWER NodeId
/// (`ConnectionDirection.shouldInitiate`); the joiner is `jn-4` against `jn-1..3`, so every voter is
/// the designated dialer of its pair with the joiner and the joiner is designated for none. It must
/// still initiate once it is isolated, well before the transport's 60 s higher-id fallback.
class EmberColdStartSingleDialerTest {
    private static final int CLUSTER_SIZE = 5;
    private static final int JOIN_CLUSTER_SIZE = 3;
    private static final int SLOTS = CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    /// Disjoint from every other Ember/Forge test's port range (highest below is 41900, next above 45100).
    private static final int FIRST_CANDIDATE_BASE = 42100;
    private static final int LAST_CANDIDATE_BASE = 43900;
    private static final int CANDIDATE_STEP = 200;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    /// Past the genesis rounds and the first reconciler ticks, where a late second dial would land.
    private static final long SETTLE_MS = 5_000L;
    /// Isolation grace (2 s) plus dial, Hello and one genesis round, far below the 60 s fallback.
    private static final long JOIN_BOUND_MS = 20_000L;
    /// Long enough to observe the fallback path, so a regression reads as a latency, not a hang.
    private static final long WAIT_BOUND_MS = 120_000L;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(240)
    void coldStart_connectsEachPairOnce_whileGenesisIsPending() {
        var basePort = freeBasePort();
        cluster = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "sd");
        assertThat(cluster.start().await(START_BOUND).fold(Cause::message, _ -> "started")).isEqualTo("started");
        sleep(SETTLE_MS);

        assertThat(handshakes())
            .as("each pair connects once, so every core completes one handshake per peer; a genesis candidate "
                + "that bypasses the single-dialer order adds a second connection per pair")
            .isEqualTo((long) CLUSTER_SIZE * (CLUSTER_SIZE - 1));
    }

    @Test
    @Timeout(240)
    void freshCoreJoiner_designatedForNoPair_initiatesOnceIsolated_andLearnsTheFormedConfiguration() {
        var basePort = freeBasePort();
        cluster = emberCluster(JOIN_CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "jn");
        assertThat(cluster.start().await(START_BOUND).fold(Cause::message, _ -> "started")).isEqualTo("started");

        // addNode resolves only once the joiner's start settles, which already includes its connection;
        // the clock starts before it.
        var started = System.currentTimeMillis();
        var joinerId = cluster.addNode().await(START_BOUND).unwrap();
        var joiner = cluster.getNode(joinerId.id()).unwrap();

        assertThat(joinerId.id()).as("every voter id sorts below the joiner, so no pair designates the joiner")
                                 .isEqualTo("jn-4");

        while (EmberAmnesiacRestartTest.runtime(joiner).voterConfiguration().isEmpty()
               && System.currentTimeMillis() - started < WAIT_BOUND_MS) {
            sleep(250);
        }

        var joinedAfterMs = System.currentTimeMillis() - started;

        assertThat(EmberAmnesiacRestartTest.runtime(joiner).voterConfiguration().isPresent())
            .as("the joiner connects and installs the formed configuration")
            .isTrue();
        assertThat(joinedAfterMs)
            .as("the joiner initiates once isolated instead of waiting for the 60 s higher-id fallback")
            .isLessThanOrEqualTo(JOIN_BOUND_MS);
    }

    private long handshakes() {
        return cluster.allNodes()
                      .stream()
                      .mapToLong(node -> node.transportMetrics().get("quic_handshake_total").longValue())
                      .sum();
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
            if (!slotFree(base, slot)) {
                return false;
            }
        }
        return true;
    }

    private static boolean slotFree(int base, int slot) {
        return udpFree(base + slot) && tcpFree(base + slot) && tcpFree(base + MGMT_OFFSET + slot)
               && tcpFree(base + APP_HTTP_OFFSET + slot);
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
