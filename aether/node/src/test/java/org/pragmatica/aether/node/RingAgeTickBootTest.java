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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// PR-0c, pinned through the REAL boot path: `AetherNode` schedules the ring age tick
/// ([StreamPartitionManager#evictRingsByAge]) every [AetherNode#RING_AGE_TICK_INTERVAL]. The stream-module test
/// (`RingAgeTickTest`) pins what the tick does given a manager; what it cannot pin is that `assembleNode` arms it.
/// So on a booted single node whose periodic tasks are left RUNNING: a stream with a one-hour max age holds one
/// event stamped at the epoch (older than any max age) and one stamped now; after the first tick the old event
/// has left the ring and the fresh one has stayed. The wait is the tick interval plus margin (the first tick fires
/// one interval after `start()` arms the task), so the test costs about a minute.
class RingAgeTickBootTest {
    private static final String STREAM = "aged";
    private static final int PARTITION = 0;
    private static final long MAX_AGE_MS = 3_600_000L;
    private static final long DISK_MAX_BYTES = 256L * 1024 * 1024;
    private static final long AWAIT_MS = AetherNode.RING_AGE_TICK_INTERVAL.millis() + 30_000L;
    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final long POLL_NANOS = 100_000_000L;

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
    @Timeout(value = 150, unit = SECONDS)
    void ringAgeTick_evictsTheAgedEvent_onABootedNode() {
        node = AetherNode.aetherNode(minimalConfig(), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start().await(START_BOUND).onFailure(cause -> fail("start must succeed: " + cause.message()));

        var manager = node.streamPartitionManager();
        // This single-node harness never forms consensus, so the owner promotion gate would refuse every append;
        // the test pins the tick, not ownership, so it admits the owner directly (as the WAL truncation boot test).
        manager.ownerServeGate((_, _) -> Result.unitResult());
        createStream(manager);
        publish(manager, 1000L);
        publish(manager, System.currentTimeMillis());

        var ring = manager.partitionBuffer(STREAM, PARTITION).unwrap();

        assertThat(ring.eventCount()).as("fixture: both events are in the ring before the first tick").isEqualTo(2L);
        awaitCondition(() -> ring.tailOffset() >= 1, "the first ring age tick evicts the aged event");
        assertThat(ring.tailOffset()).as("only the aged event left the ring").isEqualTo(1L);
        assertThat(ring.eventCount()).as("the fresh event stayed").isEqualTo(1L);
    }

    /// The spec's default (3.3 item 1). Pinned so that a change of cadence is a decision, not a drive-by.
    @Test
    void ringAgeTickInterval_isSixtySeconds() {
        assertThat(AetherNode.RING_AGE_TICK_INTERVAL).isEqualTo(timeSpan(60).seconds());
    }

    private static void createStream(StreamPartitionManager manager) {
        var retention = RetentionPolicy.retentionPolicy(100_000, 256L * 1024 * 1024, MAX_AGE_MS);

        manager.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM, 1, retention, "earliest"))
               .onFailure(cause -> fail("ensureStreamMaterialized: " + cause.message()));
    }

    private static void publish(StreamPartitionManager manager, long timestamp) {
        manager.publishLocal(STREAM, PARTITION, new byte[64], timestamp)
               .onFailure(cause -> fail("publish at " + timestamp + ": " + cause.message()));
    }

    private static void awaitCondition(BooleanSupplier condition, String what) {
        var deadline = System.currentTimeMillis() + AWAIT_MS;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(POLL_NANOS);
        }

        assertThat(condition.getAsBoolean()).as("%s within %d ms", what, AWAIT_MS).isTrue();
    }

    private AetherNodeConfig minimalConfig() {
        var self = NodeId.nodeId("ring-age-tick-boot-test").unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());
        // Everything on disk lands under tempDir: `content` and the stream data dir derive from this path.
        var artifactsConfig = new StorageConfig(8L * 1024 * 1024,
                                                DISK_MAX_BYTES,
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
