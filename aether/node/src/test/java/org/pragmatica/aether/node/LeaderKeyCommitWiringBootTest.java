// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #1797 review N1: pins the `AetherNode` ROUTE REGISTRATION that drives `LeaderTerm.onLeaderKeyCommitted`
/// from the committed `LeaderKey` notification. `LeaderTermTest` calls the static handler directly, so
/// deleting the `allEntries.add(ValuePut -> onLeaderKeyCommit)` line reddened nothing.
///
/// A real single-node boot (the #858 shape; a lone node never elects itself, so no leader-gain edge can
/// fire on its own). A `LeaderKey` naming the node is committed through its own KV-Store, then a SECOND one
/// at a higher `viewSequence`: whichever path (a gain edge, if the first commit makes the FSM adopt it) takes
/// the term to the first, the second is a same-leader re-commit that emits no gain edge, so the held term
/// (read from the booted node's `leaderTerm` component) reaches it only through the registered `ValuePut`
/// route.
class LeaderKeyCommitWiringBootTest {
    @TempDir
    Path tempDir;

    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final long FIRST_SEQUENCE = 1_000_000L;
    private static final long RECOMMIT_SEQUENCE = 2_000_000L;

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }

        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void bootedNode_raisesItsLeaderTerm_onASelfNamingLeaderKeyRecommit() throws Exception {
        var self = NodeId.nodeId("leader-key-wiring-boot-" + UUID.randomUUID()).unwrap();

        node = AetherNode.aetherNode(minimalConfig(self, tempDir), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed: " + cause.message()));

        var term = (LeaderTerm) node.getClass()
                                    .getMethod("leaderTerm")
                                    .invoke(node);

        assertThat(term.current()).as("control: a lone node has led nobody, so nothing has been adopted yet")
                                  .isZero();

        commitLeaderKey(self, FIRST_SEQUENCE);
        commitLeaderKey(self, RECOMMIT_SEQUENCE);

        assertThat(term.current()).as("the registered ValuePut<LeaderKey> route re-adopts the committed sequence")
                                  .isEqualTo(RECOMMIT_SEQUENCE);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void commitLeaderKey(NodeId leader, long viewSequence) {
        var kvStore = node.kvStore();

        kvStore.process(kvStore.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE,
                                                                               LeaderValue.leaderValue(leader,
                                                                                                       viewSequence)))));
    }

    private static AetherNodeConfig minimalConfig(NodeId self, Path storageRoot) {
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", freePort()).unwrap());

        return AetherNodeConfig.builder()
                               .self(self)
                               .coreNodes(List.of(selfInfo))
                               .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                               .sliceConfig(SliceConfig.sliceConfig())
                               .artifactRepo(DHTConfig.FULL)
                               .coreMax(1)
                               .appHttp(AppHttpConfig.appHttpConfig())
                               .tls(Option.none())
                               .quicTls(TlsConfig.selfSignedMutual())
                               .certificateProvider(Option.none())
                               .configProvider(Option.none())
                               .environment(Option.none())
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
