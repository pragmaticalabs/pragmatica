// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.TlsConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #1777 track 2, the node-assembly half of the catch-up gate: a core's DHT store starts empty, so the
/// node must begin catching up at construction — refusing, not answering "absent" — and become serving once
/// it has formed and anti-entropy has run. Pinned through a REAL single-node core. Red with the
/// `dhtNode.beginCatchUp()` call removed from `AetherNode` (the node then answers absent once its replication
/// is resolved).
///
/// #1777 track 1 put an earlier gate in front: until the committed `[replication]` factors are read — after the
/// consensus state is restored — the node refuses with `ReplicationUnresolved`. The test therefore resolves the
/// factors itself before start to reach the catch-up gate, and start then resolves them for real.
class AetherNodeDhtCatchUpBootTest {
    /// #1276: node storage lives here, never under the machine-global `/data/aether/...` default.
    @TempDir
    Path tempDir;

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop().await(timeSpan(10).seconds()).onFailure(cause -> {});
        }
        org.pragmatica.config.ConfigService.clear();
        org.pragmatica.aether.resource.ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 90, unit = SECONDS)
    void bootedCore_refusesAsCatchingUp_untilFormed_thenAnswersAbsent() throws InterruptedException {
        node = AetherNode.aetherNode(minimalConfig(tempDir), () -> {})
                         .onFailure(cause -> fail("construction must succeed: " + cause.message()))
                         .unwrap();
        var dht = node.dhtClient().unwrap();
        var key = "never-written".getBytes(StandardCharsets.UTF_8);

        var unresolved = dht.get(key).await();

        assertThat(unresolved.isFailure()).as("factors not read yet: refuse, never answer: %s", unresolved).isTrue();
        unresolved.onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.ReplicationUnresolved.class));

        node.dhtNode().unwrap().resolveReplication(DHTConfig.DEFAULT);
        var beforeStart = dht.get(key).await();

        assertThat(beforeStart.isFailure()).as("an empty core is catching up, not authoritative: %s", beforeStart).isTrue();
        beforeStart.onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.NotCaughtUp.class));

        node.start().await(timeSpan(30).seconds())
            .onFailure(cause -> fail("start() must resolve on the single-node formation: " + cause.message()));

        var deadline = System.nanoTime() + timeSpan(15).seconds().nanos();
        var afterStart = dht.get(key).await();

        while (afterStart.isFailure() && System.nanoTime() < deadline) {
            Thread.sleep(200);
            afterStart = dht.get(key).await();
        }

        assertThat(afterStart).as("once formed and caught up, an absent key reads absent")
                              .isEqualTo(Result.success(Option.<byte[]>none()));
    }

    /// #1777 track 1, the wiring half: nothing resolves the factors but the node itself, on consensus state restore.
    /// Red with the `onStateRestored` resolution removed from `AetherNode` (the node then refuses forever).
    @Test
    @Timeout(value = 90, unit = SECONDS)
    void startedCore_resolvesItsReplicationOnStateRestore_andServes() throws InterruptedException {
        node = AetherNode.aetherNode(minimalConfig(tempDir), () -> {})
                         .onFailure(cause -> fail("construction must succeed: " + cause.message()))
                         .unwrap();
        var dht = node.dhtClient().unwrap();
        var key = "never-written".getBytes(StandardCharsets.UTF_8);

        node.start().await(timeSpan(30).seconds())
            .onFailure(cause -> fail("start() must resolve on the single-node formation: " + cause.message()));

        var deadline = System.nanoTime() + timeSpan(15).seconds().nanos();
        var afterStart = dht.get(key).await();

        while (afterStart.isFailure() && System.nanoTime() < deadline) {
            Thread.sleep(200);
            afterStart = dht.get(key).await();
        }

        assertThat(node.dhtNode().unwrap().replicationResolved()).as("resolved on state restore").isTrue();
        assertThat(afterStart).isEqualTo(Result.success(Option.<byte[]>none()));
    }

    private static AetherNodeConfig minimalConfig(Path storageRoot) {
        var self = NodeId.nodeId("dht-catch-up-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", freePort()).unwrap());

        return AetherNodeConfig.builder()
                               .self(self).coreNodes(List.of(selfInfo)).managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                               .sliceConfig(SliceConfig.sliceConfig()).artifactRepo(DHTConfig.DEFAULT).coreMax(1)
                               .appHttp(AppHttpConfig.appHttpConfig()).tls(Option.none()).quicTls(TlsConfig.selfSignedMutual())
                               .certificateProvider(Option.none()).configProvider(Option.none()).environment(Option.none())
                               .managementHttpProtocol(HttpProtocol.H1)
                               .storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false))
                               .build();
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
