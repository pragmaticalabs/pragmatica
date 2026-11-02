// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamSeam;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.testlogging.LogCapture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #2201 A-14, the author pre-check of PR-2a, through the REAL boot path: `StreamAssembly` binds every late-bound seam of the
/// stream partition manager except `EPOCH_STARTS` (which production never binds; its default is conservative), the owner-epoch
/// source counting as bound because the assembly hands it to the factory, and its boot check
/// (`reportUnboundSafetySeams`) runs only after the last safety-relevant seam is bound, so a correctly wired node with a WAL
/// logs no seam ERROR and its gauge reads 0. The stream-module tests pin each setter's registration; what they cannot pin is
/// which seams the assembly binds and where it calls the check.
///
/// Red with any seam binding removed from `StreamAssembly` (the first assertion), with the owner-epoch source no longer
/// registered by the constructor, or with the check called before the last safety-relevant binding (the ERROR assertion). Not
/// red with the check's call removed: on a correctly wired node it has nothing to say, and its own behaviour is pinned in
/// `StreamSeamBootCheckTest`.
class StreamSeamWiringBootTest {
    private static final String SENTINEL = "positive control: StreamSeamWiringBootTest capture is attached";

    @TempDir
    Path tempDir;

    private LogCapture capture;
    private AetherNode node;

    @BeforeEach
    void setUp() {
        capture = LogCapture.capture(StreamPartitionManager.class, Level.ERROR, false);
    }

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }

        ConfigService.clear();
        ResourceProvider.clear();
        capture.close();
    }

    @Test
    @Timeout(value = 90, unit = SECONDS)
    void aBootedNodeWithAWal_bindsEverySeamButEpochStarts_andItsBootCheckStaysSilent() {
        org.slf4j.LoggerFactory.getLogger(StreamPartitionManager.class).error(SENTINEL);
        node = AetherNode.aetherNode(minimalConfig(), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        var manager = node.streamPartitionManager();

        assertThat(node.storageSetups().get(StorageFactory.STREAMS_NAME).instance().logRoot().isPresent())
            .as("fixture: the node keeps a stream WAL, so the boot check is armed").isTrue();
        assertThat(manager.unboundSeams()).as("every seam bound by the assembly but EPOCH_STARTS").containsExactly(StreamSeam.EPOCH_STARTS);
        assertThat(manager.unboundSafetySeamCount()).as("the gauge's value").isZero();
        assertThat(capture.messages(Level.ERROR)).as("the boot check found nothing to report; only the positive control was captured")
                                                 .containsExactly(SENTINEL);
    }

    private AetherNodeConfig minimalConfig() {
        var self = NodeId.nodeId("stream-seam-wiring-boot-test").unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());
        var artifactsConfig = new StorageConfig(8L * 1024 * 1024,
                                                256L * 1024 * 1024,
                                                tempDir.resolve("artifacts").toString(),
                                                tempDir.resolve("snapshots").toString(),
                                                1000,
                                                "60s",
                                                5,
                                                "",
                                                false);

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
                               .storageConfig(Map.of("artifacts", artifactsConfig))
                               .build();
    }
}
