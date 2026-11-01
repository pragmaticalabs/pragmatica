// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// NT-22 (#2201 C-03, spec 4.4 items 1 and 3) through the REAL boot path. The environment arrives through the boot's
/// environment-function seam, so the test sets `STREAM_MAX_MEMORY_BYTES` in a map rather than in the JVM's own environment.
///
/// - A malformed variable refuses `AetherNode.aetherNode(...)` naming it, before anything binds the cluster port.
/// - A booted node's partition manager holds the variable's budget over the `[streaming] max_memory_bytes` the config sets, and
///   its sealer the decoupled pending cap (256 MiB of a 1 GiB budget), not the budget.
class StreamMemoryBudgetBootTest {
    private static final long MIB = 1024 * 1024L;

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
    @Timeout(value = 60, unit = SECONDS)
    void aetherNode_refusesBoot_whenStreamMaxMemoryBytesIsMalformed_beforeBindingAnyPort() {
        var port = ClusterTestPorts.freeClusterPort();

        boot(port, Map.of(StreamMemoryBudget.MAX_MEMORY_VARIABLE, "lots"))
            .onSuccess(booted -> {
                node = booted;
                fail("boot must REFUSE a malformed STREAM_MAX_MEMORY_BYTES; it booted");
            })
            .onFailure(cause -> assertThat(cause.message()).as("the refusal names the variable and its value")
                                                           .contains(StreamMemoryBudget.MAX_MEMORY_VARIABLE + "='lots'"));

        assertThat(isFree(port)).as("the refusal comes before anything binds the cluster port " + port).isTrue();
    }

    @Test
    @Timeout(value = 90, unit = SECONDS)
    void aBootedNode_holdsTheVariablesBudget_overTheKey_andItsSealerTheDecoupledPendingCap() {
        node = boot(ClusterTestPorts.freeClusterPort(), Map.of(StreamMemoryBudget.MAX_MEMORY_VARIABLE, "1GB"))
                   .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                   .unwrap();

        assertThat(node.streamPartitionManager().maxTotalBytes()).as("STREAM_MAX_MEMORY_BYTES over [streaming] max_memory_bytes = 300MB")
                                                                 .isEqualTo(1024 * MIB);
        assertThat(node.streamSegmentSealer().pendingCapBytes()).as("the pending cap is min(budget, 256 MiB), not the budget")
                                                                .isEqualTo(256 * MIB);
    }

    /// Positive control for the port probe: a port this test holds must read as NOT free, or a probe that always answered
    /// "free" would make the refusal's assertion vacuous.
    @Test
    void portProbe_seesAHeldPortAsTaken() throws IOException {
        try (var held = new DatagramSocket(0)) {
            assertThat(isFree(held.getLocalPort())).isFalse();
        }
    }

    private Result<AetherNode> boot(int port, Map<String, String> environment) {
        return AetherNode.aetherNode(config(port),
                                     MessageRouter.DelegateRouter.delegate(),
                                     NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()),
                                     () -> {},
                                     () -> {},
                                     () -> {},
                                     variable -> Option.option(environment.get(variable)));
    }

    private static boolean isFree(int port) {
        try (var tcp = new ServerSocket(port); var udp = new DatagramSocket(port)) {
            return tcp.getLocalPort() == port && udp.getLocalPort() == port;
        } catch (IOException e) {
            return false;
        }
    }

    private AetherNodeConfig config(int port) {
        var self = NodeId.nodeId("stream-memory-budget-boot-test").unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", port).unwrap());
        var artifactsConfig = new StorageConfig(8L * 1024 * 1024,
                                                256L * 1024 * 1024,
                                                tempDir.resolve("artifacts").toString(),
                                                tempDir.resolve("snapshots").toString(),
                                                1000,
                                                "60s",
                                                5,
                                                "",
                                                false);
        var streaming = StreamingConfig.streamingConfig().withMemoryBudget(300 * MIB, StreamingConfig.DERIVE_SEAL_PENDING_CAP_BYTES);

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
                               .backupConfig(Option.none())
                               .membership(Option.none())
                               .streaming(streaming)
                               .build();
    }
}
