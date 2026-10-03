// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
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
import org.pragmatica.aether.stream.DurableSealedOffsetSource;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.storage.SnapshotManager;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #1441, pinned through the REAL boot path: a partition WITHOUT a WAL has no record above the last metadata snapshot
/// but its segment refs, so `AetherNode` must hand the sealer a sink that makes each no-WAL seal's ref durable before
/// the seal resolves (`StorageSegmentSink.RefDurability.snapshotted(streams.snapshotManager())`). The stream-module
/// tests pin the sink given either durability; what they cannot pin is which one `assembleNode` wires. So on a booted
/// node whose explicit `wal_path` is unwritable while `segments/` is not -- the reachable no-WAL state, degraded with a
/// WARN because the node is built outside `Main` -- seal with the periodic snapshot tick cancelled, then rebuild the
/// floor from the snapshot ON DISK, the way the next boot would: it must cover what was sealed.
class NoWalSealSnapshotBootTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final int RING_EVENTS = 20;
    private static final int EVENTS = 100;
    private static final int PAYLOAD = 1024;
    private static final int SEALED_AT_LEAST = EVENTS - 2 * RING_EVENTS;
    private static final long DISK_MAX_BYTES = 256L * 1024 * 1024;
    private static final long AWAIT_MS = 15_000;
    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final long POLL_NANOS = 20_000_000;

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
    void noWalSeal_isOnDiskBeforeItCompletes_soARebuiltFloorCoversIt() throws IOException {
        Files.writeString(tempDir.resolve("wal-is-a-file"), "not a directory");
        node = AetherNode.aetherNode(minimalConfig(), () -> {})
                         .onFailure(cause -> fail("boot must succeed (no-WAL degrades outside Main): " + cause.message()))
                         .unwrap();
        // The periodic snapshot tick would put the refs on disk by itself; cancelled, only the seals can.
        node.start().await(START_BOUND).onFailure(cause -> fail("start must succeed: " + cause.message()));
        node.periodicTasks().cancel();
        var manager = node.streamPartitionManager();
        // #1555: this single-node harness never forms consensus, so the owner is admitted directly.
        manager.ownerServeGate((_, _) -> Result.unitResult());
        var snapshots = streamsSnapshotManager();

        createStream(manager);
        assertThat(hasWal(manager)).as("fixture: the unwritable wal_path left this partition without a WAL").isFalse();
        IntStream.range(0, EVENTS).forEach(i -> publish(manager, i));
        awaitCondition(() -> liveSealedThrough(manager) >= SEALED_AT_LEAST, "seals reach " + SEALED_AT_LEAST);

        var floorOnDisk = DurableSealedOffsetSource.fromLatestSnapshot(snapshots)
                                                   .current()
                                                   .lastSealedOffset(STREAM, PARTITION);

        assertThat(floorOnDisk).as("the floor the next boot rebuilds covers every completed seal (live: %d)",
                                   liveSealedThrough(manager))
                               .isGreaterThanOrEqualTo(SEALED_AT_LEAST);
    }

    private SnapshotManager streamsSnapshotManager() {
        var setup = node.storageSetups().get(StorageFactory.STREAMS_NAME);

        assertThat(setup).as("fixture: the node owns the 'streams' storage setup").isNotNull();

        return setup.snapshotManager();
    }

    private static Stream<StreamPartitionManager.PartitionWalView> partitionView(StreamPartitionManager manager) {
        return manager.walSnapshot()
                      .streams()
                      .stream()
                      .filter(view -> view.stream().equals(STREAM))
                      .flatMap(view -> view.partitions().stream())
                      .filter(view -> view.partition() == PARTITION);
    }

    private static long liveSealedThrough(StreamPartitionManager manager) {
        return partitionView(manager).mapToLong(StreamPartitionManager.PartitionWalView::sealedThroughOffset)
                                     .findFirst()
                                     .orElse(-1L);
    }

    private static boolean hasWal(StreamPartitionManager manager) {
        return partitionView(manager).findFirst()
                                     .map(view -> view.wal().isPresent())
                                     .orElseThrow(() -> new AssertionError("fixture: partition not materialized"));
    }

    private static void createStream(StreamPartitionManager manager) {
        var retention = RetentionPolicy.retentionPolicy(RING_EVENTS, 2L * RING_EVENTS * PAYLOAD, 600_000);

        manager.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM, 1, retention, "earliest"))
               .onFailure(cause -> fail("ensureStreamMaterialized: " + cause.message()));
    }

    private static void publish(StreamPartitionManager manager, int i) {
        var payload = new byte[PAYLOAD];

        payload[0] = (byte) i;
        manager.publishLocal(STREAM, PARTITION, payload, 1000L + i)
               .onFailure(cause -> fail("publish " + i + ": " + cause.message()));
    }

    private static void awaitCondition(BooleanSupplier condition, String what) {
        var deadline = System.currentTimeMillis() + AWAIT_MS;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(POLL_NANOS);
        }

        assertThat(condition.getAsBoolean()).as("%s within %d ms", what, AWAIT_MS).isTrue();
    }

    private AetherNodeConfig minimalConfig() {
        var self = NodeId.nodeId("nowal-seal-snapshot-boot-test").unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", freePort()).unwrap());
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

        // An explicit wal_path under a regular FILE: the node-id subdirectory cannot be created, while the derived
        // `segments/` under `artifacts` can -- exactly the reachable no-WAL state.
        var streamsConfig = StorageConfig.storageConfig(8L * 1024 * 1024,
                                                        DISK_MAX_BYTES,
                                                        tempDir.resolve("streams").toString(),
                                                        tempDir.resolve("streams-snapshots").toString(),
                                                        1000,
                                                        "60s",
                                                        5,
                                                        tempDir.resolve("wal-is-a-file").toString());

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
                                .storageConfig(Map.of("artifacts", artifactsConfig, "streams", streamsConfig))
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
