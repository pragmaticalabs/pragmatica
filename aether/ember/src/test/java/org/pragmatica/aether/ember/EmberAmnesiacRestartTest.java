// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.ClusterConfig;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.GenesisAnnouncement;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.Message;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1526 — [limit: amnesiac-same-id-excluded-by-boot-token] pinned on real QUIC/SWIM.
///
/// The verifier's schedule (#1554, `V1554AmnesiaProbeTest` at the engine layer): voters {v0,v1,v2}; a
/// §4 swap installs {v0,v1,D} while v2 lags at epoch 0; v1 and D decide X in slot S while v0 is slow;
/// v1 crashes and a NEW process starts under v1's NodeId; D is slow; the lagging v2 would answer the new
/// process with the epoch-0 roster. Without the transport gate the new process adopts v0's epoch-1 state
/// from one live responder and decides slot S again with v0. With the gate, peers hold v1's original boot
/// token and refuse the new process at the QUIC Hello and in SWIM (HelloRefused / IdentityRefused), so it
/// exits and never takes part in consensus; once D returns, v0 learns X and every node agrees.
class EmberAmnesiacRestartTest {
    private static final int CLUSTER_SIZE = 3;
    private static final int SLOTS = 2 * CLUSTER_SIZE + 2;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    /// #1667: probed through the shared EmberTestPorts, which also probes each node's SWIM UDP port.
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan WRITE_BOUND = TimeSpan.timeSpan(20).seconds();
    private static final long CONDITION_BOUND_MS = 120_000L;
    /// Long enough for the refused process's genesis rounds, sync attempts and exit.
    private static final long REFUSAL_WINDOW_MS = 30_000L;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.allNodes().forEach(node -> node.setInboundFaultFilter((_, _) -> true));
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(600)
    void sameIdRestartAfterASwap_isRefusedAtTransport_andSlotAgreementHolds() {
        cluster = EmberTestPorts.startedCluster(PORTS, basePort -> emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, "amn"), START_BOUND);
        var v0 = cluster.getNode("amn-1").unwrap();
        var v1 = cluster.getNode("amn-2").unwrap();
        var v2 = cluster.getNode("amn-3").unwrap();
        var addedId = cluster.addNode().await(START_BOUND).unwrap();
        var d = cluster.getNode(addedId.id()).unwrap();
        awaitCondition("the added core observes the cluster", () -> runtime(d).isObserving() || runtime(d).isActive());

        // Stage 1: swap v2 -> D while v2 hears no consensus traffic (it stays at epoch 0).
        v2.setInboundFaultFilter(dropConsensus());
        var target = new ClusterConfig(List.of(v0.self(), v1.self(), d.self()));
        assertThat(runtime(v0).reconfigure(target).await(WRITE_BOUND).isSuccess()).as("the swap is agreed").isTrue();
        awaitCondition("v0, v1 and D install epoch 1",
                       () -> List.of(v0, v1, d).stream().allMatch(node -> epochOf(node) == 1));
        assertThat(epochOf(v2)).as("v2 lags at epoch 0").isZero();

        // Stage 2: v0 is slow; v1 and D decide X.
        v0.setInboundFaultFilter((peer, message) -> !(message instanceof RabiaProtocolMessage)
                                                     || !(peer.equals(v1.self()) || peer.equals(d.self())));
        assertThat(write(v1, "amnesia-x").isSuccess()).as("v1 and D decide X").isTrue();
        awaitCondition("D applied X", () -> hasKey(d, "amnesia-x"));

        // v1 crashes. D becomes slow. v2 answers genesis announcements only.
        assertThat(cluster.killNode("amn-2", false).await(STOP_BOUND).isSuccess()).isTrue();
        d.setInboundFaultFilter(dropConsensus());
        v0.setInboundFaultFilter((peer, message) -> !(message instanceof RabiaProtocolMessage) || !peer.equals(d.self()));
        v2.setInboundFaultFilter((_, message) -> !(message instanceof RabiaProtocolMessage)
                                                 || message instanceof GenesisAnnouncement
                                                 || message instanceof SyncRequest);

        // A NEW process under v1's NodeId (fresh boot token), steered as the verifier did: v0's formed
        // answer is withheld, so the lagging v2's epoch-0 answer is the one it would install.
        var launch = cluster.relaunchNode("amn-2", false);
        var relaunched = cluster.getNode("amn-2").unwrap();
        relaunched.setInboundFaultFilter((peer, message) -> !(message instanceof GenesisAnnouncement && peer.equals(v0.self())));

        KVCommand<AetherKey> putY = new KVCommand.Put<>(AetherKey.ApiKeyKey.apiKeyKey("amnesia-y"),
                                                        AetherValue.ApiKeyValue.apiKeyValue("amnesia-y", "00", 0L));
        var pendingY = v0.<Object>apply(List.of(putY));
        var deadline = System.currentTimeMillis() + REFUSAL_WINDOW_MS;
        while (System.currentTimeMillis() < deadline) {
            assertThat(runtime(relaunched).isActive()).as("the same-id process must never take part in consensus").isFalse();
            sleep(250);
        }
        assertThat(pendingY.await(WRITE_BOUND).isSuccess()).as("v0 alone (D slow, the same-id process refused) must not commit slot S").isFalse();
        assertThat(launch.await(START_BOUND).fold(Cause::message, _ -> "launched"))
            .as("the refused process never completes a start")
            .isNotEqualTo("launched");
        assertThat(cluster.getNode("amn-2").isEmpty()).as("the refused process learns it was refused and exits").isTrue();

        // D returns: every node converges on the slot v1 and D decided.
        List.of(v0, v2, d).forEach(node -> node.setInboundFaultFilter((_, _) -> true));
        awaitCondition("v0 learns X, the value decided in slot S", () -> hasKey(v0, "amnesia-x"));
        awaitCondition("D and v0 hold the same probe keys",
                       () -> hasKey(d, "amnesia-y") == hasKey(v0, "amnesia-y") && hasKey(d, "amnesia-x"));
    }

    private static BiPredicate<NodeId, Message.Wired> dropConsensus() {
        return (_, message) -> !(message instanceof RabiaProtocolMessage);
    }

    private static long epochOf(AetherNode node) {
        return runtime(node).voterConfiguration()
                            .map(configuration -> configuration.epoch())
                            .or(-1L);
    }

    private static boolean hasKey(AetherNode node, String keyId) {
        return node.kvStore()
                   .get(AetherKey.ApiKeyKey.apiKeyKey(keyId))
                   .isPresent();
    }

    private static Result<List<Object>> write(AetherNode node, String keyId) {
        KVCommand<AetherKey> put = new KVCommand.Put<>(AetherKey.ApiKeyKey.apiKeyKey(keyId),
                                                       AetherValue.ApiKeyValue.apiKeyValue(keyId, "00", 0L));

        return node.<Object>apply(List.of(put))
                   .await(WRITE_BOUND);
    }

    static RabiaNode<?> runtime(AetherNode node) {
        return Result.lift(() -> {
            var accessor = node.getClass().getDeclaredMethod("clusterNode");
            accessor.setAccessible(true);
            return (RabiaNode<?>) accessor.invoke(node);
        }).unwrap();
    }

    private static void awaitCondition(String what, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + CONDITION_BOUND_MS;

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
