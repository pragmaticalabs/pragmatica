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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.PeerState;
import org.pragmatica.consensus.net.quic.PeerTransitionRecord;
import org.pragmatica.consensus.rabia.ClusterConfig;
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
///
/// The partial arm places the joiner BETWEEN the seeds: `pc-1x` sorts above `pc-1` and below `pc-2`
/// and `pc-3` (String order). It is the designated dialer toward `pc-2`/`pc-3` and connects to them,
/// so it is never isolated; `pc-1` is designated toward it but cannot see it. It reaches `pc-1`
/// because its connected seeds answer its genesis announcement with the formed configuration: once
/// installed, that configuration does not contain it, the #1390 staged rule applies, and it dials
/// `pc-1`. The late-starter arm boots one cold-start core 10 s after the others, which wait for it in
/// genesis, and requires that it dial only the peers it is the designated dialer for. It pins the dial
/// DECISION rather than a handshake total: the designated side's own retries to a peer that was down
/// can land more than once after the peer starts, a transport behaviour this rule does not govern.
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
    private static final TimeSpan WRITE_BOUND = TimeSpan.timeSpan(20).seconds();
    private static final long LATE_START_DELAY_MS = 10_000L;
    private static final long LATE_HIGHEST_DELAY_MS = 3_000L;

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

    @Test
    @Timeout(300)
    void joinerBetweenSeeds_connectsToEverySeedAndIsAdmitted_withinTheBound() {
        var basePort = freeBasePort();
        cluster = emberCluster(JOIN_CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "pc");
        assertThat(cluster.start().await(START_BOUND).fold(Cause::message, _ -> "started")).isEqualTo("started");
        var seeds = List.of(node("pc-1"), node("pc-2"), node("pc-3"));

        var started = System.currentTimeMillis();
        var joinerId = cluster.addCoreNode("pc-1x").await(START_BOUND).unwrap();
        var joiner = node(joinerId.id());

        assertThat(List.of(seeds.get(0).self().compareTo(joinerId), joinerId.compareTo(seeds.get(1).self()),
                           joinerId.compareTo(seeds.get(2).self())))
            .as("pc-1 < pc-1x < pc-2 < pc-3: the joiner dials pc-2/pc-3 and only pc-1 is designated toward it")
            .allMatch(order -> order < 0);

        while (!fullyConnected(joiner, seeds) && System.currentTimeMillis() - started < WAIT_BOUND_MS) {
            sleep(250);
        }

        var connectedAfterMs = System.currentTimeMillis() - started;

        assertThat(fullyConnected(joiner, seeds)).as("the joiner and every seed hold a connection to each other")
                                                 .isTrue();
        assertThat(connectedAfterMs).as("full core connectivity well before the 60 s higher-id fallback")
                                    .isLessThanOrEqualTo(JOIN_BOUND_MS);

        var target = new ClusterConfig(List.of(seeds.get(0).self(), seeds.get(1).self(), seeds.get(2).self(), joinerId));

        assertThat(EmberAmnesiacRestartTest.runtime(seeds.get(0)).reconfigure(target).await(WRITE_BOUND).isSuccess())
            .as("the joiner is admitted as a voter through a Rabia section 4 add")
            .isTrue();

        while (!EmberAmnesiacRestartTest.runtime(joiner).voterConfiguration()
                                       .filter(configuration -> configuration.contains(joinerId)).isPresent()
               && System.currentTimeMillis() - started < WAIT_BOUND_MS) {
            sleep(250);
        }

        assertThat(EmberAmnesiacRestartTest.runtime(joiner).voterConfiguration()
                                           .filter(configuration -> configuration.contains(joinerId)).isPresent())
            .as("the joiner installs the configuration that admits it")
            .isTrue();
    }

    @Test
    @Timeout(300)
    void lateStartingCore_dialsOnlyThePeersItIsDesignatedFor() {
        var basePort = freeBasePort();
        cluster = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "ls");
        var starting = cluster.startWithLateGenesisMembers(Set.of("ls-3"));
        var lateDials = new CopyOnWriteArrayList<NodeId>();

        EmberAmnesiacRestartTest.runtime(cluster.heldBackNode("ls-3").unwrap())
                                .network()
                                .setPeerTransitionListener(record -> recordDial(record, lateDials));
        sleep(LATE_START_DELAY_MS);
        assertThat(cluster.startHeldBackNodes().await(START_BOUND).fold(Cause::message, _ -> "started"))
            .as("ls-3 starts 10 s after the others; ls-1/ls-2 are its designated dialers, it dials ls-4/ls-5")
            .isEqualTo("started");
        assertThat(starting.await(START_BOUND).fold(Cause::message, _ -> "started")).isEqualTo("started");
        sleep(SETTLE_MS);

        assertThat(cluster.allNodes()).hasSize(CLUSTER_SIZE);
        assertThat(lateDials).as("ls-3 dials only the peers it is the designated dialer for")
                             .doesNotContain(node("ls-1").self(), node("ls-2").self())
                             .contains(node("ls-4").self(), node("ls-5").self());
        assertThat(connectedToAll(node("ls-3"))).as("the late core is connected to every other core").isTrue();
    }

    private static void recordDial(PeerTransitionRecord record, List<NodeId> dials) {
        if (record.to() == PeerState.Phase.CONNECTING) {
            dials.add(record.peerId());
        }
    }

    private boolean connectedToAll(AetherNode core) {
        var peers = EmberAmnesiacRestartTest.runtime(core).network().connectedPeers();

        return cluster.allNodes()
                      .stream()
                      .filter(other -> !other.self().equals(core.self()))
                      .allMatch(other -> peers.contains(other.self()));
    }

    /// #1554 R5: the late core is the HIGHEST id, so it is the designated dialer for no pair and reaches
    /// the isolation branch while the four designated dialers' connections to it are still handshaking. Its
    /// own forced dials in flight count as reaching a peer, so it does not re-dial every seed on the next
    /// tick: every pair connects once.
    @Test
    @Timeout(300)
    void lateStartingHighestCore_connectsEachPairOnce() {
        var basePort = freeBasePort();
        cluster = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "la");
        var starting = cluster.startWithLateGenesisMembers(Set.of("la-5"));
        sleep(LATE_HIGHEST_DELAY_MS);
        assertThat(cluster.startHeldBackNodes().await(START_BOUND).fold(Cause::message, _ -> "started")).isEqualTo("started");
        assertThat(starting.await(START_BOUND).fold(Cause::message, _ -> "started")).isEqualTo("started");
        sleep(SETTLE_MS + SETTLE_MS);

        assertThat(handshakes()).as("one connection per pair; the late highest core must not re-dial seeds whose connection is in flight")
                                .isEqualTo((long) CLUSTER_SIZE * (CLUSTER_SIZE - 1));
    }

    private AetherNode node(String id) {
        return cluster.getNode(id).unwrap();
    }

    private static boolean fullyConnected(AetherNode joiner, List<AetherNode> seeds) {
        var joinerPeers = EmberAmnesiacRestartTest.runtime(joiner).network().connectedPeers();

        return seeds.stream()
                    .allMatch(seed -> joinerPeers.contains(seed.self())
                                      && EmberAmnesiacRestartTest.runtime(seed).network().connectedPeers()
                                                                 .contains(joiner.self()));
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
