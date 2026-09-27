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
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1528 / #1545 — a NEW process launched under a killed core's NodeId must not restore quorum.
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
    /// Disjoint from every other Ember test's candidate range (25600, 25700-27500, 27700-29500, 29700-31500).
    private static final int FIRST_CANDIDATE_BASE = 36100;
    private static final int LAST_CANDIDATE_BASE = 37900;
    private static final int CANDIDATE_STEP = 200;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan WRITE_BOUND = TimeSpan.timeSpan(20).seconds();
    /// Longer than the ~65 s the verifier measured between the SWIM refusal and the QUIC re-admission.
    private static final long READMIT_WINDOW_MS = 90_000L;

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

    private void relaunchedProcessNeverRestoresQuorum(boolean sameAddress) {
        var basePort = freeBasePort();
        cluster = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "btk");
        assertThat(cluster.start().await(START_BOUND).fold(Cause::message, _ -> "started")).isEqualTo("started");
        var survivor = cluster.getNode("btk-1").unwrap();

        assertThat(write(survivor, "control-before").isSuccess()).as("control: a three-core cluster commits").isTrue();

        assertThat(cluster.killNode("btk-3", false).await(STOP_BOUND).isSuccess()).isTrue();
        assertThat(cluster.relaunchNode("btk-3", sameAddress).await(START_BOUND).fold(Cause::message, _ -> "launched"))
            .as("the new process itself boots; refusal is the cluster's decision")
            .isIn("launched", "Promise is not resolved within specified timeout");
        var relaunched = cluster.getNode("btk-3").unwrap();

        sleep(READMIT_WINDOW_MS);
        assertThat(relaunched.isReady()).as("the refused process must never become consensus-active").isFalse();

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
            if (!udpFree(base + slot)
                || !tcpFree(base + slot)
                || !tcpFree(base + MGMT_OFFSET + slot)
                || !tcpFree(base + APP_HTTP_OFFSET + slot)) {
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
