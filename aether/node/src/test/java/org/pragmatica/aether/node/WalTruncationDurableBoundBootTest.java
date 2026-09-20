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
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.storage.SnapshotManager;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #1345, pinned through the REAL boot path: `AetherNode` bounds WAL truncation by the refs in the latest
/// metadata snapshot ON DISK (`DurableSealedOffsetSource.fromLatestSnapshot(streams.snapshotManager())`),
/// never by the live `SegmentIndex`. The stream-module tests pin the manager given either source; what they
/// cannot pin is which source `assembleNode` hands it. So on a booted node: seal past the 8 MiB compaction
/// threshold, run the truncation tick BEFORE any snapshot exists — the WAL file must not shrink, because a
/// crash now would lose every ref and recovery would seed below the compaction point — then take the
/// snapshot and run the tick again: now it compacts. The second half is the control that the tick reaches
/// the WAL at all; without it a wiring that never truncates would pass the first half.
class WalTruncationDurableBoundBootTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final int RING_EVENTS = 20;
    /// 200 × 70 KiB ≈ 13.7 MiB > PartitionWal.COMPACTION_THRESHOLD_BYTES (8 MiB): the sealed prefix, once
    /// truncatable, is physically rewritten and the file shrinks by the sealed bytes.
    private static final int EVENTS = 200;
    private static final int PAYLOAD = 70 * 1024;
    /// 10 seals of 20 events each — well under the streams snapshot's 100-mutation trigger, so the only way a
    /// snapshot lands before the first tick is the 30 s interval, which [#awaitSealed]'s deadline stays inside.
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
    void truncateWalsToSealed_leavesWalIntactUntilSnapshot_thenCompacts() throws IOException {
        node = AetherNode.aetherNode(minimalConfig(), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        // start() returns post-formation; the periodic drivers (snapshot tick, truncation tick) are armed by it.
        // The streams SnapshotManager's 30 s interval trigger has been counting since construction, so the
        // sealing phase below must stay well inside it.
        node.start().await(START_BOUND).onFailure(cause -> fail("start must succeed: " + cause.message()));
        var manager = node.streamPartitionManager();
        var snapshots = streamsSnapshotManager();

        createStream(manager);
        IntStream.range(0, EVENTS).forEach(i -> publish(manager, i));
        awaitCondition(() -> sealedThrough(manager) >= SEALED_AT_LEAST, "seals reach " + SEALED_AT_LEAST);

        var walFile = walFile();
        var sizeBefore = Files.size(walFile);

        assertThat(sizeBefore).as("fixture: WAL past the compaction threshold").isGreaterThan(8L * 1024 * 1024);
        assertThat(snapshots.lastSnapshotEpoch()).as("fixture: no metadata snapshot has been taken yet").isZero();

        manager.truncateWalsToSealed();

        assertThat(Files.size(walFile)).as("no snapshot on disk: the live index (sealed through %d) must not license compaction",
                                           sealedThrough(manager))
                                       .isEqualTo(sizeBefore);

        snapshots.forceSnapshot();
        manager.truncateWalsToSealed();

        assertThat(Files.size(walFile)).as("snapshot on disk: the tick compacts the sealed prefix").isLessThan(sizeBefore);
    }

    private SnapshotManager streamsSnapshotManager() {
        var setup = node.storageSetups().get(StorageFactory.STREAMS_NAME);

        assertThat(setup).as("fixture: the node owns the 'streams' storage setup").isNotNull();

        return setup.snapshotManager();
    }

    private static long sealedThrough(StreamPartitionManager manager) {
        return manager.walSnapshot()
                      .streams()
                      .stream()
                      .filter(view -> view.stream().equals(STREAM))
                      .flatMap(view -> view.partitions().stream())
                      .filter(view -> view.partition() == PARTITION)
                      .mapToLong(StreamPartitionManager.PartitionWalView::sealedThroughOffset)
                      .findFirst()
                      .orElse(-1L);
    }

    /// The WAL lives under the node's derived stream data dir (`<artifacts sibling>/stream-segments/<node>/wal`);
    /// found by name so the test does not restate that derivation.
    private Path walFile() throws IOException {
        try (Stream<Path> files = Files.walk(tempDir)) {
            return files.filter(path -> path.getFileName().toString().equals(PARTITION + ".wal"))
                        .filter(path -> path.getParent().getFileName().toString().equals(STREAM))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("fixture: no " + STREAM + "/" + PARTITION + ".wal under " + tempDir));
        }
    }

    /// The publish-path create: the ring is materialized locally before it returns and the config commit is
    /// fired async — this single-node harness never elects a leader (`GUARD[empty-topology]`), so a SYNC
    /// `createStream` would wait on a consensus round that cannot complete.
    private static void createStream(StreamPartitionManager manager) {
        var retention = RetentionPolicy.retentionPolicy(RING_EVENTS, 2L * RING_EVENTS * PAYLOAD, 600_000);

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

    private static void awaitCondition(BooleanSupplier condition, String what) {
        var deadline = System.currentTimeMillis() + AWAIT_MS;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(POLL_NANOS);
        }

        assertThat(condition.getAsBoolean()).as("%s within %d ms", what, AWAIT_MS).isTrue();
    }

    private AetherNodeConfig minimalConfig() {
        var self = NodeId.nodeId("wal-durable-bound-boot-test").unwrap();
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

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
