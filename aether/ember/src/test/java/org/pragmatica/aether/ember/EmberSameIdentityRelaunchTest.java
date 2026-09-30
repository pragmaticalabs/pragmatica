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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1528 / #1545 — a NEW process launched under a killed core's NodeId must not restore quorum, and a
/// partitioned-but-live process (same boot token) still heals.
///
/// The verifier's scenario on real transport, SWIM and Rabia: three cores `btk-1..3`; kill `btk-3`;
/// start a new process as `btk-3` (fresh boot token); kill `btk-2`. Without the boot-token gate at the
/// QUIC handshake, the survivors refused the new process in SWIM but re-admitted it over QUIC as a
/// RECONNECT of the EVICTED peer, it synchronized and voted, and `btk-1` plus the refused process
/// committed a write. With the gate the only live voter set is `{btk-1}` and the write cannot commit.
///
/// Run for both addresses of the relaunched process: a different port (next free slot) and the killed
/// node's own port (same address, the container-restart shape).
class EmberSameIdentityRelaunchTest {
    private static final int CLUSTER_SIZE = 3;
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    /// Registered in forge-tests' `TEST_PORT_ALLOCATION.md`: blocks 23000-23300 (step 100; one block spans
    /// base..base+85), BELOW the Linux ephemeral range 32768-60999 — the former 36100-37900 lay inside it,
    /// and under load a kernel-assigned port left no free block in 4 of 9 bigboy runs (#1558). The previous
    /// 22000-23800 scan was not disjoint: five forge-tests classes bind fixed ports at 22000-22885 and
    /// 23500-23702. 23000-23499 is bound by no test in the repository (searched for every five-digit
    /// literal under `src/test`), and sits between those classes and `SurvivorLivenessAfterGracefulKillTest`
    /// (24500).
    private static final int FIRST_CANDIDATE_BASE = 23000;
    private static final int LAST_CANDIDATE_BASE = 23300;
    private static final int CANDIDATE_STEP = 100;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan WRITE_BOUND = TimeSpan.timeSpan(20).seconds();
    /// Longer than the ~65 s the verifier measured between the SWIM refusal and the QUIC re-admission.
    private static final long READMIT_WINDOW_MS = 90_000L;
    private static final long HEAL_BOUND_MS = 180_000L;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(480)
    void relaunchedSameIdentityProcess_atNewAddress_neverRestoresQuorum() {
        relaunchedProcessNeverRestoresQuorum(false);
    }

    @Test
    @Timeout(480)
    void relaunchedSameIdentityProcess_atSameAddress_neverRestoresQuorum() {
        relaunchedProcessNeverRestoresQuorum(true);
    }

    /// The positive arm on the same transport: a partitioned-but-LIVE process keeps its boot token, so
    /// when the partition heals (higher SWIM incarnation, same token) it is re-admitted over QUIC and
    /// counts toward quorum again — btk-1 plus the healed btk-3 commit after btk-2 is killed. No
    /// boot-token refusal may be recorded anywhere on the path.
    @Test
    @Timeout(480)
    void partitionedLiveProcess_sameToken_healsAndRestoresQuorum() {
        var basePort = freeBasePort();
        cluster = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "btk");
        assertThat(cluster.start().await(START_BOUND).fold(Cause::message, _ -> "started")).isEqualTo("started");
        var survivor = cluster.getNode("btk-1").unwrap();
        var partitioned = cluster.getNode("btk-3").unwrap();
        var partitionedId = partitioned.self();

        assertThat(write(survivor, "control-before").isSuccess()).as("control: a three-core cluster commits").isTrue();

        partitioned.blackhole(true);
        awaitCondition("the survivor's membership stops counting the partitioned node as MEMBER",
                       () -> !"Member".equals(survivor.membershipFsm().memberStates().get(partitionedId)));
        partitioned.blackhole(false);
        awaitCondition("the same process, same token, heals back to MEMBER on the survivor",
                       () -> "Member".equals(survivor.membershipFsm().memberStates().get(partitionedId)));

        assertThat(cluster.killNode("btk-2", false).await(STOP_BOUND).isSuccess()).isTrue();

        assertThat(write(survivor, "after-heal").isSuccess())
            .as("btk-1 plus the healed btk-3 (same process) form a quorum")
            .isTrue();
        assertThat(survivor.transportMetrics().get("boot_token_refusals_total").longValue())
            .as("a same-token heal is never refused")
            .isZero();
    }

    private static void awaitCondition(String what, java.util.function.BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + HEAL_BOUND_MS;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting: " + what);
            }
            sleep(250);
        }
    }

    private void relaunchedProcessNeverRestoresQuorum(boolean sameAddress) {
        var basePort = freeBasePort();
        cluster = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "btk");
        assertThat(cluster.start().await(START_BOUND).fold(Cause::message, _ -> "started")).isEqualTo("started");
        var survivor = cluster.getNode("btk-1").unwrap();

        assertThat(write(survivor, "control-before").isSuccess()).as("control: a three-core cluster commits").isTrue();

        assertThat(cluster.killNode("btk-3", false).await(STOP_BOUND).isSuccess()).isTrue();
        if (sameAddress) {
            awaitSlotFree(basePort, 2);
        }

        var launch = cluster.relaunchNode("btk-3", sameAddress);
        // The node is registered before its start runs; the refused process may exit within seconds.
        var relaunched = cluster.getNode("btk-3").unwrap();

        assertThat(launch.await(START_BOUND).fold(Cause::message, _ -> "launched"))
            .as("the refused process never completes a start")
            .isNotEqualTo("launched");

        sleep(READMIT_WINDOW_MS);
        assertThat(relaunched.isReady()).as("the refused process must never become consensus-active").isFalse();
        assertThat(cluster.getNode("btk-3").isEmpty())
            .as("the refused process learns it was refused (explicit HelloRefused/IdentityRefused) and exits")
            .isTrue();
        assertThat(cluster.exitCodeOf("btk-3"))
            .as("#1558: a refused identity exits with its own code, distinct from a drain's")
            .isEqualTo(Option.some(AetherNode.EXIT_IDENTITY_REFUSED));

        assertThat(cluster.killNode("btk-2", false).await(STOP_BOUND).isSuccess()).isTrue();

        assertThat(write(survivor, "after-kill").isSuccess())
            .as("btk-1 plus a refused same-NodeId process must NOT form a quorum")
            .isFalse();
    }

    private static org.pragmatica.lang.Result<List<Object>> write(AetherNode node, String keyId) {
        KVCommand<AetherKey> put = new KVCommand.Put<>(AetherKey.ApiKeyKey.apiKeyKey(keyId),
                                                       AetherValue.ApiKeyValue.apiKeyValue(keyId, "00", 0L));

        return node.<Object>apply(List.of(put))
                   .await(WRITE_BOUND);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// A hard kill resolves after a 1 s bound while the killed node may still hold its sockets; the
    /// same-address relaunch waits until every port of btk-3's slot (slot 2) is free again. It FAILS on its
    /// deadline: since #1719 the QUIC bind is exclusive, so relaunching onto a still-held port fails with
    /// BindFailed, and "the refused process never completes a start" would then pass for the wrong reason.
    private static void awaitSlotFree(int base, int slot) {
        var deadline = System.currentTimeMillis() + 60_000L;

        while (!slotFree(base, slot) && System.currentTimeMillis() < deadline) {
            sleep(250);
        }
        if (!slotFree(base, slot)) {
            throw new AssertionError("slot " + slot + " of base port " + base + " is still held 60 s after the kill: a relaunch "
                                     + "there would be refused by the bind (BindFailed), not by the boot token");
        }
    }

    private static boolean slotFree(int base, int slot) {
        return udpFree(base + slot) && tcpFree(base + slot) && tcpFree(base + MGMT_OFFSET + slot)
               && tcpFree(base + APP_HTTP_OFFSET + slot);
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
