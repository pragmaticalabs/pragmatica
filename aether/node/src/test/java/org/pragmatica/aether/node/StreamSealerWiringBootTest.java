// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
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
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2201 C-02 / #1875, pinned through the REAL boot path: the node's `[streaming] seal_*` thresholds reach the sealer
/// `StreamAssembly` builds, and the node's stop seals what that sealer holds before it resolves (know 73fd8d9759), with the
/// partition's WAL still open, then closes it. The thresholds here (a run of 10 events, no age or byte trigger within the
/// test) differ from every default, so a node built with the defaults would not seal the first ten evictions within the
/// bound, and would seal nothing at stop either.
class StreamSealerWiringBootTest {
    private static final String STREAM = "runs";
    private static final int PARTITION = 0;
    private static final int RING_EVENTS = 20;
    private static final int PAYLOAD = 1024;
    private static final int RUN_EVENTS = 10;
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
    void theNodesSealThresholds_reachTheSealer_andStopSealsWhatItHolds_thenClosesIt() {
        node = AetherNode.aetherNode(config(), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start().await(START_BOUND).onFailure(cause -> fail("start must succeed: " + cause.message()));
        node.periodicTasks().cancel();

        var manager = node.streamPartitionManager();
        var sealer = node.streamSegmentSealer();

        manager.ownerServeGate((_, _) -> Result.unitResult());
        createStream(manager);
        IntStream.range(0, RING_EVENTS + RUN_EVENTS).forEach(i -> publish(manager, i));
        awaitCondition(() -> sealer.lowestUnsealed(STREAM, PARTITION).isEmpty(), 3_000, "the run of 10 evictions sealed (seal_run_events = 10)");

        IntStream.range(RING_EVENTS + RUN_EVENTS, RING_EVENTS + RUN_EVENTS + 5).forEach(i -> publish(manager, i));
        LockSupport.parkNanos(500_000_000L);
        assertThat(sealer.lowestUnsealed(STREAM, PARTITION)).as("control: five evictions are below every threshold")
                                                           .isEqualTo(Option.some((long) RUN_EVENTS));
        assertThat(sealer.armedTimers()).as("control: the partition waits on its age timer").isEqualTo(1);

        var failuresBefore = sealer.sealFailureCount();

        node.stop().await(timeSpan(10).seconds()).onFailure(cause -> fail("stop: " + cause.message()));
        node = null;

        assertThat(sealer.lowestUnsealed(STREAM, PARTITION)).as("the stop sealed the queued run before it resolved")
                                                           .isEqualTo(Option.none());
        assertThat(sealer.sealFailureCount()).as("with the WAL still open: the seal did not fail").isEqualTo(failuresBefore);
        assertThat(sealer.armedTimers()).as("stop closed the sealer: no timer outlives the node").isZero();
    }

    /// Know 73fd8d9759, a SOURCE tripwire for what the boot test cannot force (a sink slow enough to outlast the rest of the
    /// stop): the stop sequence runs only after the stop seal, and the partition manager closes the WALs, followed by the
    /// replication shutdown (#932), only after it.
    @Test
    void stop_closesTheWalsAndRunsTheStopSequence_onlyAfterTheStopSeal() {
        var source = AssemblySource.spaced();

        assertThat(source).containsOnlyOnce("streamSegmentSealer.stopSealed(SEALER_STOP_FLUSH_BOUND) .map(this::closeStreamPartitions);")
                          .containsOnlyOnce("private Unit closeStreamPartitions(Unit sealed) { streamPartitionManager.close(); streamReplicationShutdown.run();")
                          .containsOnlyOnce("streamPartitionManager.close();")
                          .containsOnlyOnce("backupFlushed.flatMap(() -> streamsStopped) .flatMap(() -> NodeStopSequence.run(");
    }

    @Test
    void theSealerStopBound_isFiveSeconds() {
        assertThat(AetherNode.SEALER_STOP_FLUSH_BOUND).isEqualTo(timeSpan(5).seconds());
    }

    private static void createStream(StreamPartitionManager manager) {
        var retention = RetentionPolicy.retentionPolicy(RING_EVENTS, 4L * RING_EVENTS * PAYLOAD, 600_000);

        manager.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM, 1, retention, "earliest"))
               .onFailure(cause -> fail("ensureStreamMaterialized: " + cause.message()));
    }

    private static void publish(StreamPartitionManager manager, int i) {
        var payload = new byte[PAYLOAD];

        payload[0] = (byte) i;
        manager.publishLocal(STREAM, PARTITION, payload, 1000L + i)
               .onFailure(cause -> fail("publish " + i + ": " + cause.message()))
               .onSuccess(offset -> assertThat(offset).isEqualTo((long) i));
    }

    private static void awaitCondition(BooleanSupplier condition, long awaitMs, String what) {
        var deadline = System.currentTimeMillis() + awaitMs;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(20_000_000L);
        }

        assertThat(condition.getAsBoolean()).as("%s within %d ms", what, awaitMs).isTrue();
    }

    private AetherNodeConfig config() {
        var self = NodeId.nodeId("stream-sealer-wiring-boot-test").unwrap();
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
        var streaming = StreamingConfig.streamingConfig().withSealRun(Long.MAX_VALUE, RUN_EVENTS, timeSpan(1).hours(), 4L * 1024 * 1024);

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
