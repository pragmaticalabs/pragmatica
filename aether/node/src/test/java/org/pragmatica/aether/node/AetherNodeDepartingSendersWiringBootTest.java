// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #1818 round 4 (J3): pins the `AetherNode` line that hands the DHT anti-entropy its departing-senders rule.
/// A real single-node core receives a departure push from a node outside its ring — which is what a pusher
/// already pruned from the receiver's ring looks like. Before the leader's drain ping names that node, the
/// push is refused; once it does, the same push is applied. Only the installed rule can tell them apart:
/// with the wiring line removed the predicate stays strict and the second push is refused too.
class AetherNodeDepartingSendersWiringBootTest {
    @TempDir
    Path tempDir;

    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final Duration BOUND = Duration.ofSeconds(10);
    private static final NodeId PUSHER = NodeId.nodeId("already-pruned-drainer").unwrap();

    private AetherNode node;
    private MessageRouter.DelegateRouter delegateRouter;

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
    void departurePush_isRefusedFromAStranger_andAppliedOnceTheLeadersDrainSetNamesIt() {
        boot();

        var before = key("before-drain-ping");
        var after = key("after-drain-ping");

        delegateRouter.route(push("push-before", before));

        assertThat(holds(before)).as("control: a push from a node nobody named as draining is refused").isFalse();

        commitLeadership();
        delegateRouter.route(drainPingNaming(PUSHER));
        delegateRouter.route(push("push-after", after));

        await().atMost(BOUND)
               .untilAsserted(() -> assertThat(holds(after)).as("the drain set reached the DHT's departing-senders rule")
                                                         .isTrue());
    }

    private void boot() {
        delegateRouter = MessageRouter.DelegateRouter.delegate();
        node = AetherNode.aetherNode(minimalConfig(tempDir),
                                      delegateRouter,
                                      NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()),
                                      () -> {})
                          .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                          .unwrap();
        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed: " + cause.message()));
    }

    private static byte[] key(String name) {
        return name.getBytes(StandardCharsets.UTF_8);
    }

    private static DHTMessage.MigrationDataResponse push(String requestId, byte[] key) {
        return new DHTMessage.MigrationDataResponse(requestId,
                                                    PUSHER,
                                                    List.of(new DHTMessage.KeyValue(key, key, 1L, 0L, 0L, 0L)),
                                                    true);
    }

    private boolean holds(byte[] key) {
        return node.dhtNode()
                   .flatMap(dht -> dht.getLocal(key)
                                      .await()
                                      .or(Option.none()))
                   .isPresent();
    }

    /// The single node is its own leader; the ping must come from the leader to carry authority.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void commitLeadership() {
        var store = node.kvStore();
        KVCommand command = new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(node.self(), 1));

        store.process(store.createBatch(List.of(command)));
    }

    /// The leader's heartbeat naming `drainer` in the global drain set — and not this node.
    private ClusterSyncPing drainPingNaming(NodeId drainer) {
        return new ClusterSyncPing(node.self(), Map.of(), 1L, 0L, 0L, 0L, Set.of(), Set.of(drainer), Map.of(), Set.of(), true, true);
    }

    private static AetherNodeConfig minimalConfig(Path storageRoot) {
        var self = NodeId.nodeId("departing-senders-wiring-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", freePort()).unwrap());

        return AetherNodeConfig.builder()
                               .self(self)
                               .coreNodes(List.of(selfInfo))
                               .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                               .sliceConfig(SliceConfig.sliceConfig())
                               .artifactRepo(DHTConfig.DEFAULT)
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
