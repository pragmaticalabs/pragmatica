// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
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
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTMessage;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.dht.RepairPlane;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SystemTags;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// A6 (#2265): pins the `AetherNode` line that installs the repair plane's routes. A real single-node core is
/// assembled exactly as production assembles it, and a `VerifiedReadRequest` routed through its router must reach
/// the DHT repair plane's handler. With the assembly's call to `routeDhtRepairPlane` removed, the request reaches
/// nothing, and every remote verified read in a cluster waits out its phase deadline before the quorum fallback.
/// `AetherNodeDhtRepairPlaneRoutesTest` covers what the helper routes; this covers that the node installs it.
class AetherNodeVerifiedReadRouteBootTest {
    @TempDir
    Path tempDir;

    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final Duration BOUND = Duration.ofSeconds(10);
    private static final byte[] KEY = "content-blocks/00".getBytes(StandardCharsets.UTF_8);

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
    void verifiedReadRequest_routedByTheAssembledNode_reachesTheRepairPlaneHandler() {
        boot();

        var before = answered();

        assertThat(SystemTags.TAGS.get("org.pragmatica.dht.DHTMessage.VerifiedReadRequest")).as("the wire tag routed here")
                                                                                         .isEqualTo(2176);
        delegateRouter.route(new DHTMessage.VerifiedReadRequest("verified-1", node.self(), KEY, new byte[32], false));

        await().atMost(BOUND)
               .untilAsserted(() -> assertThat(answered()).as("the repair plane answered the routed request")
                                                          .isEqualTo(before + 1));
    }

    private long answered() {
        return node.dhtNode()
                   .map(DHTNode::repair)
                   .map(RepairPlane::verifiedReadsAnswered)
                   .or(-1L);
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

    private static AetherNodeConfig minimalConfig(Path storageRoot) {
        var self = NodeId.nodeId("verified-read-route-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());

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
}
