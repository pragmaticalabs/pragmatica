// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// U2 wiring: a booted node binds its content storage to its app-http server, so `streaming_ingest` has somewhere to write.
/// The app-http tests build the server without [AetherNode]; only a real boot (`AetherNode.aetherNode`) reaches the
/// `setUploadStorage` call, and the server keeps what it was given in its (private) `uploadIngest` reference, read here.
class AetherNodeUploadStorageWiringBootTest {
    @TempDir
    Path tempDir;

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
    void bootedNode_bindsItsContentStorageToTheAppHttpServer() throws Exception {
        node = AetherNode.aetherNode(config(), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();

        var server = node.appHttpServer();
        var field = server.getClass().getDeclaredField("uploadIngest");

        field.setAccessible(true);

        @SuppressWarnings("unchecked")
        var ingest = (AtomicReference<Option<?>>) field.get(server);

        assertThat(ingest.get().isPresent()).as("the node bound its content storage to the app-http server").isTrue();
    }

    private AetherNodeConfig config() {
        var self = NodeId.nodeId("upload-storage-wiring-boot-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());

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
                               .storageConfig(HermeticStorage.nodeStorageIn(tempDir, false))
                               .backupConfig(Option.none())
                               .membership(Option.none())
                               .streaming(StreamingConfig.streamingConfig())
                               .protocol(ProtocolConfig.defaultConfig())
                               .sliceAction(SliceActionConfig.sliceActionConfig())
                               .cache(DHTConfig.CACHE_DEFAULT)
                               .controllerConfig(ControllerConfig.DEFAULT)
                               .autoHeal(AutoHealConfig.DEFAULT)
                               .build();
    }
}
