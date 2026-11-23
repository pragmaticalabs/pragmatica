// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.ClusterTestPorts;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.HotWindowFixture;
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
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// T-05 (stream-storage design 9.1, tripwire on G31 at the read SHA): while every seal fails (node booted with a 4 KiB
/// `dht_segment_max_bytes`, as in T-03), a partition with a WAL keeps accepting appends and its WAL grows past the hot window.
/// "The limit moves to the WAL's disk" (`SegmentSealer` class doc). Not a flip tripwire: it stays green through S2c and after,
/// because hand-over eviction never back-pressures (G31, G43).
///
/// In this package, not beside the other boot tests, because the node's published `walBytes` is derived by the package-private
/// `RetentionRoutes.walTotalBytes` and the cross-check must read that derivation, not restate it.
class AetherNodeWalGrowsWhileSealsFailBootTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final int RING_EVENTS = 8;
    /// Above the 4 KiB cap, so every segment the sealer forms is refused by the DHT put.
    private static final int PAYLOAD = 8 * 1024;
    /// The ring's data bytes (the retention byte bound the stream is created with is twice this).
    private static final long RING_DATA_BYTES = (long) RING_EVENTS * PAYLOAD;
    /// K: four times the ring's data bytes.
    private static final long K = 4 * RING_DATA_BYTES;
    private static final int FIRST_WAVE = 3 * RING_EVENTS;
    private static final int SECOND_WAVE = 3 * RING_EVENTS;
    /// Smaller than one sealed segment, so the sealer's heap copies exceed the cap and spill to the WAL.
    private static final long PENDING_CAP_BYTES = 16L * 1024;
    private static final long MAX_MEMORY_BYTES = 300L * 1024 * 1024;
    private static final long CAP_BYTES = 4_096L;
    private static final long DISK_MAX_BYTES = 256L * 1024 * 1024;
    private static final long AWAIT_MS = 45_000;
    private static final long POLL_NANOS = 20_000_000;
    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final String FAILURE_MESSAGE = "G31 broken: with seals failing, appends were refused or the WAL stopped growing; "
                                                  + "§4.2 says hand-over eviction never back-pressures (G31, G43)";

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

    @ParameterizedTest
    @EnumSource(value = DeploymentEngine.class, names = {"LEGACY", "FSM2"})
    @Timeout(value = 120, unit = SECONDS)
    void walGrowsWhileSealsFail_readSha(DeploymentEngine engine) {
        node = AetherNode.aetherNode(config().withDeploymentEngine(engine), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start must succeed: " + cause.message()));

        System.out.println("deployment engine " + engine + " (selected explicitly by this test)");
        assertThat(node.deploymentWiring().isFsm2()).as("fixture: the boot ran the engine this test selected (" + engine + ")")
                                                    .isEqualTo(engine == DeploymentEngine.FSM2);

        var manager = node.streamPartitionManager();
        var sealer = node.streamSegmentSealer();

        manager.ownerServeGate((_, _) -> Result.unitResult());
        createStream(manager);
        IntStream.range(0, FIRST_WAVE).forEach(i -> publish(manager, i));
        awaitCondition(() -> sealer.sealFailureCount() > 0, "the sealer attempts a seal and it fails");
        IntStream.range(FIRST_WAVE, FIRST_WAVE + SECOND_WAVE).forEach(i -> publish(manager, i));
        awaitCondition(() -> sealer.spillCount() > 0, "the sealer spills heap copies to the WAL");

        var snapshot = manager.walSnapshot();
        var view = partition(snapshot);
        var wal = view.wal().or(() -> fail("fixture: the partition has a WAL"));
        var aboveSealed = wal.sizeBytes() - Math.max(0L, view.sealedThroughOffset() + 1);

        assertThat(view.sealedThroughOffset()).as(FAILURE_MESSAGE + " (fixture: every seal failed, nothing sealed)").isEqualTo(-1L);
        assertThat(wal.lastOffset()).as(FAILURE_MESSAGE + " (every append was acked)").isEqualTo((long) (FIRST_WAVE + SECOND_WAVE - 1));
        assertThat(wal.sizeBytes()).as(FAILURE_MESSAGE + " (WAL bytes above lastSealedOffset, K = 4 x ring data bytes)")
                                   .isGreaterThanOrEqualTo(K);
        assertThat(aboveSealed).as(FAILURE_MESSAGE).isGreaterThanOrEqualTo(K);
        assertThat(RetentionRoutes.walTotalBytes(snapshot)).as(FAILURE_MESSAGE + " (node-published walBytes agrees with the AppendLog)")
                                                           .isEqualTo(wal.sizeBytes());
        assertThat(sealer.spillCount()).as(FAILURE_MESSAGE + " (heap copies were handed over to the WAL)").isPositive();
        assertThat(sealer.refusalCount()).as(FAILURE_MESSAGE + " (no SEALING_BEHIND / SEGMENT_TIER_FULL refusal)").isZero();
    }

    private static StreamPartitionManager.PartitionWalView partition(StreamPartitionManager.WalSnapshot snapshot) {
        return snapshot.streams()
                       .stream()
                       .filter(view -> view.stream().equals(STREAM))
                       .flatMap(view -> view.partitions().stream())
                       .filter(view -> view.partition() == PARTITION)
                       .findFirst()
                       .orElseGet(() -> fail("fixture: partition " + STREAM + "/" + PARTITION + " is materialized"));
    }

    /// The publish-path create, as in `WalTruncationDurableBoundBootTest`: the ring is materialized locally before it returns.
    private static void createStream(StreamPartitionManager manager) {
        // The hot window is declared equal to the retention so the ring holds the same RING_EVENTS entries whether it is sized
        // from retention or from the declared window; the data region (128 KiB) is under 256 KiB, so the first segment is the same in both.
        var retention = RetentionPolicy.retentionPolicy(RING_EVENTS, 2 * RING_DATA_BYTES, 600_000);

        manager.ensureStreamMaterialized(HotWindowFixture.declaredHot(StreamConfig.streamConfig(STREAM, 1, retention, "earliest"),
                                                                        retention.maxCount(),
                                                                        retention.maxBytes()))
               .onFailure(cause -> fail("ensureStreamMaterialized: " + cause.message()));
    }

    /// Publishes the way a caller is meant to: `REPLICATION_BEHIND` is a transient capacity refusal raised before the append takes
    /// an offset ("clears as the replication sends return", `StreamError.General`), so the caller waits and retries within the
    /// test's deadline. Any other refusal is the broken property and fails at once.
    private static void publish(StreamPartitionManager manager, int i) {
        var payload = new byte[PAYLOAD];
        var deadline = System.currentTimeMillis() + AWAIT_MS;

        payload[0] = (byte) i;

        var outcome = manager.publishLocal(STREAM, PARTITION, payload, 1000L + i);

        while (isSendBudgetRefusal(outcome) && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(POLL_NANOS);
            outcome = manager.publishLocal(STREAM, PARTITION, payload, 1000L + i);
        }

        outcome.onFailure(cause -> fail(FAILURE_MESSAGE + ": publish " + i + " refused: " + cause.message()))
               .onSuccess(offset -> assertThat(offset).isEqualTo((long) i));
    }

    private static boolean isSendBudgetRefusal(Result<Long> outcome) {
        return outcome.fold(cause -> cause == StreamError.General.REPLICATION_BEHIND, _ -> false);
    }

    private static void awaitCondition(BooleanSupplier condition, String what) {
        var deadline = System.currentTimeMillis() + AWAIT_MS;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(POLL_NANOS);
        }

        assertThat(condition.getAsBoolean()).as("%s within %d ms", what, AWAIT_MS).isTrue();
    }

    private AetherNodeConfig config() {
        var self = NodeId.nodeId("wal-grows-boot-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());
        var artifacts = new StorageConfig(8L * 1024 * 1024,
                                          DISK_MAX_BYTES,
                                          tempDir.resolve("artifacts").toString(),
                                          tempDir.resolve("snapshots").toString(),
                                          1000,
                                          "60s",
                                          5,
                                          "",
                                          false);
        var streaming = StreamingConfig.streamingConfig()
                                       .withDhtSegmentMaxBytes(CAP_BYTES)
                                       .withMemoryBudget(MAX_MEMORY_BYTES, PENDING_CAP_BYTES);

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
                               .storageConfig(Map.of("artifacts", artifacts))
                               .backupConfig(Option.none())
                               .membership(Option.none())
                               .streaming(streaming)
                               .protocol(ProtocolConfig.defaultConfig())
                               .sliceAction(SliceActionConfig.sliceActionConfig())
                               .cache(DHTConfig.CACHE_DEFAULT)
                               .controllerConfig(ControllerConfig.DEFAULT)
                               .autoHeal(AutoHealConfig.DEFAULT)
                               .build();
    }
}
