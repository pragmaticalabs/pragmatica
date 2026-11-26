// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

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
import org.pragmatica.aether.stream.replication.BackfillError;
import org.pragmatica.aether.stream.replication.PartitionBackfill;
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

/// #3754 n3, pinned through the REAL boot path: the `streamReplicationShutdown` Runnable `StreamAssembly.streamOwnership` builds
/// (the lambda at its `stopStreamReplication` call) is the one the node's stop runs, and it ends the backfill it was built over.
/// The test reads that Runnable off the booted node, takes the `PartitionBackfill` and the executor it captured, stops the node
/// and asserts both: the executor is shut down (control: it was not before the stop) and the backfill is closed, which is seen
/// from outside as a call refused with the stopped cause. A Runnable that stops the controller and the executor but not the
/// backfill (the shape before #3754) leaves the first assertion green and the second red.
class StreamReplicationStopBootTest {
    private static final String STREAM = "runs";
    private static final int PARTITION = 0;
    private static final TimeSpan START_BOUND = timeSpan(30).seconds();

    @TempDir
    Path tempDir;

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> fail("stop failed in teardown: " + cause.message()));
        }

        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 90, unit = SECONDS)
    void nodeStop_runsTheRealReplicationShutdown_whichClosesTheBackfillAndShutsItsExecutor() throws ReflectiveOperationException {
        node = AetherNode.aetherNode(config(), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start().await(START_BOUND).onFailure(cause -> fail("start must succeed: " + cause.message()));
        node.periodicTasks().cancel();

        var shutdown = replicationShutdownOf(node);
        var backfill = captured(shutdown, PartitionBackfill.class);
        var executor = captured(shutdown, ExecutorService.class);

        assertThat(executor.isShutdown()).as("control: the backfill executor runs until the node stops").isFalse();

        node.stop().await(timeSpan(10).seconds()).onFailure(cause -> fail("stop: " + cause.message()));
        node = null;

        assertThat(executor.isShutdown()).as("the stop shut the backfill executor").isTrue();
        assertThat(backfill.backfill(STREAM, PARTITION).await(timeSpan(10).seconds()).fold(cause -> cause == BackfillError.General.STOPPED,
                                                                                            _ -> false))
            .as("the stop closed the backfill: a call after it is refused with the stopped cause")
            .isTrue();
    }

    /// The node record's `streamReplicationShutdown` component: the Runnable `AetherNode.stop` runs after the partition manager's close.
    private static Runnable replicationShutdownOf(AetherNode node) throws ReflectiveOperationException {
        var accessor = node.getClass().getDeclaredMethod("streamReplicationShutdown");

        accessor.setAccessible(true);

        return (Runnable) accessor.invoke(node);
    }

    /// What the Runnable's lambda captured, by type: the assembly's own wiring, not a copy the test built.
    private static <T> T captured(Runnable lambda, Class<T> type) throws ReflectiveOperationException {
        for (var field : lambda.getClass().getDeclaredFields()) {
            if (type.isAssignableFrom(field.getType())) {
                field.setAccessible(true);

                return type.cast(field.get(lambda));
            }
        }

        throw new AssertionError("the replication shutdown Runnable captured no " + type.getSimpleName());
    }

    private AetherNodeConfig config() {
        var self = NodeId.nodeId("stream-replication-stop-boot-test").unwrap();
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
                               .backupConfig(Option.none())
                               .membership(Option.none())
                               .streaming(StreamingConfig.streamingConfig())
                               .build();
    }
}
