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
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.storage.StorageError;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.TierLevel;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// T-03 (stream-storage design 9.1, tripwire on G6): on a booted node whose `dht_segment_max_bytes` is 4 KiB, a seal whose DHT put
/// is refused for capacity FAILS CLOSED and leaves no sealed copy. Not a defect test: it pins the designed behaviour at the read
/// SHA, so S7 (which removes the streams instance's DHT copy) has to retire it deliberately.
///
/// Two arms on one boot, both through the node's own `streams` storage instance:
/// - the seal itself: its promise fails with `TierFull(REMOTE, used, 4096)` (`DhtStorageTier.tierFullOnCapacity`), no ref names the
///   block, the log's sealed bound does not move, and the local disk tier holds nothing afterwards (the copy written before the
///   DHT put is undone);
/// - the sealer's own path over a real partition: every seal the sealer attempts fails, and the partition's sealed bound and the
///   instance's refs stay as they were.
class AetherNodeSealFailsClosedOnDhtCapacityBootTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final int RING_EVENTS = 8;
    /// Above the 4 KiB cap, so a segment of any number of these is refused outright.
    private static final int PAYLOAD = 8 * 1024;
    private static final int EVENTS = 3 * RING_EVENTS;
    private static final long CAP_BYTES = 4_096L;
    private static final long DISK_MAX_BYTES = 256L * 1024 * 1024;
    private static final long AWAIT_MS = 45_000;
    private static final long POLL_NANOS = 20_000_000;
    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final TimeSpan SEAL_BOUND = timeSpan(30).seconds();
    private static final String FAILURE_MESSAGE = "S7 changed G6's designed fail-closed seal: enable T-130 and retire this tripwire";

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
    void sealFailsClosedOnDhtCapacity(DeploymentEngine engine) {
        node = AetherNode.aetherNode(config().withDeploymentEngine(engine), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start must succeed: " + cause.message()));

        System.out.println("deployment engine " + engine + " (selected explicitly by this test)");
        assertThat(node.deploymentWiring().isFsm2()).as("fixture: the boot ran the engine this test selected (" + engine + ")")
                                                    .isEqualTo(engine == DeploymentEngine.FSM2);
        node.periodicTasks().cancel();

        var instance = node.storageSetups().get(StorageFactory.STREAMS_NAME).instance();
        var diskBefore = usedBytes(instance, TierLevel.LOCAL_DISK);

        assertThat(instance.tierInfo()).as("fixture: the streams instance has a local disk tier and a REMOTE (DHT) tier")
                                       .extracting(StorageInstance.TierInfo::level)
                                       .contains(TierLevel.LOCAL_DISK, TierLevel.REMOTE);
        assertThat(diskBefore).as("fixture: nothing on the local disk tier yet").isZero();

        directSealFailsClosed(instance, diskBefore);
        sealerPathFailsClosed(instance, diskBefore);
    }

    private void directSealFailsClosed(StorageInstance instance, long diskBefore) {
        var scratch = instance.openLog("t03-scratch/0")
                              .onFailure(cause -> fail("fixture: scratch log: " + cause.message()))
                              .unwrap();
        var block = new byte[PAYLOAD];

        block[0] = 3;

        var outcome = instance.seal(scratch, 0, 1, "t03-ref", block).await(SEAL_BOUND);

        assertThat(outcome.isFailure()).as("the seal's promise fails, it does not succeed over a refused DHT put").isTrue();
        outcome.onFailure(cause -> assertTierFull(cause));
        assertThat(instance.resolveRef("t03-ref").isPresent()).as("no ref names the block (no repointRef)").isFalse();
        assertThat(scratch.sealedThrough()).as("the log's sealed bound did not move (no markSealed)").isEqualTo(-1L);
        assertThat(usedBytes(instance, TierLevel.LOCAL_DISK)).as("the local disk copy written before the DHT put is undone").isEqualTo(diskBefore);
    }

    private static void assertTierFull(Cause cause) {
        assertThat(cause).as(FAILURE_MESSAGE + " (cause was " + cause.getClass().getName() + ": " + cause.message() + ")")
                         .isInstanceOfSatisfying(StorageError.TierFull.class,
                                                 full -> {
                                                     assertThat(full.tier()).as(FAILURE_MESSAGE).isEqualTo(TierLevel.REMOTE);
                                                     assertThat(full.maxBytes()).as(FAILURE_MESSAGE).isEqualTo(CAP_BYTES);
                                                     assertThat(full.usedBytes()).as(FAILURE_MESSAGE).isLessThanOrEqualTo(CAP_BYTES);
                                                 });
    }

    private void sealerPathFailsClosed(StorageInstance instance, long diskBefore) {
        var manager = node.streamPartitionManager();
        var sealer = node.streamSegmentSealer();

        manager.ownerServeGate((_, _) -> Result.unitResult());
        createStream(manager);
        IntStream.range(0, EVENTS).forEach(i -> publish(manager, i));
        awaitCondition(() -> sealer.sealFailureCount() > 0, "the sealer attempts a seal and it fails");

        assertThat(partitionWal(manager).sealedThrough()).as(FAILURE_MESSAGE + ": the partition WAL's own sealed bound did not move (no markSealed before the store)")
                                                          .isEqualTo(-1L);
        assertThat(sealedThrough(manager)).as(FAILURE_MESSAGE + ": the partition's sealed bound did not move").isEqualTo(-1L);
        assertThat(instance.listRefs("")).as(FAILURE_MESSAGE + ": no sealed ref exists").isEmpty();
        assertThat(usedBytes(instance, TierLevel.LOCAL_DISK)).as(FAILURE_MESSAGE + ": the local disk tier holds no sealed copy")
                                                             .isEqualTo(diskBefore);
    }

    private static long usedBytes(StorageInstance instance, TierLevel level) {
        return instance.tierInfo()
                       .stream()
                       .filter(info -> info.level() == level)
                       .mapToLong(StorageInstance.TierInfo::usedBytes)
                       .sum();
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
                      .orElse(Long.MIN_VALUE);
    }

    /// The publish-path create: the ring is materialized locally before it returns and the config commit is fired async (this
    /// single-node harness never elects a leader), as in `WalTruncationDurableBoundBootTest`.
    private static void createStream(StreamPartitionManager manager) {
        var retention = RetentionPolicy.retentionPolicy(RING_EVENTS, 2L * RING_EVENTS * PAYLOAD, 600_000);

        manager.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM, 1, retention, "earliest"))
               .onFailure(cause -> fail("ensureStreamMaterialized: " + cause.message()));
    }

    /// Publishes the way a caller is meant to: `REPLICATION_BEHIND` is a transient capacity refusal raised before the append takes
    /// an offset ("clears as the replication sends return", `StreamError.General`), so the caller waits and retries within the
    /// test's deadline. Any other refusal fails at once.
    private static void publish(StreamPartitionManager manager, int i) {
        var payload = new byte[PAYLOAD];
        var deadline = System.currentTimeMillis() + AWAIT_MS;

        payload[0] = (byte) i;

        var outcome = manager.publishLocal(STREAM, PARTITION, payload, 1000L + i);

        while (isSendBudgetRefusal(outcome) && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(POLL_NANOS);
            outcome = manager.publishLocal(STREAM, PARTITION, payload, 1000L + i);
        }

        outcome.onFailure(cause -> fail("publish " + i + ": " + cause.message()))
               .onSuccess(offset -> assertThat(offset).isEqualTo((long) i));
    }

    private static boolean isSendBudgetRefusal(Result<Long> outcome) {
        return outcome.fold(cause -> cause == StreamError.General.REPLICATION_BEHIND, _ -> false);
    }

    /// The partition's own WAL, the log `StorageSegmentSink` hands to `StorageInstance.seal`. The manager keeps it private and
    /// exposes only the manager-side sealed watermark, which a premature `markSealed` on the log never moves; the bound this arm
    /// pins lives on the log itself, so it is read there.
    private static AppendLog partitionWal(StreamPartitionManager manager) {
        try {
            var walFor = StreamPartitionManager.class.getDeclaredMethod("walFor", String.class, int.class);

            walFor.setAccessible(true);
            return ((Option<?>) walFor.invoke(manager, STREAM, PARTITION)).map(AppendLog.class::cast)
                                                                          .or(() -> fail("fixture: the partition has a WAL"));
        } catch (ReflectiveOperationException e) {
            return fail("fixture: StreamPartitionManager.walFor(String, int) is gone; point this read at the partition WAL: " + e);
        }
    }

    private static void awaitCondition(BooleanSupplier condition, String what) {
        var deadline = System.currentTimeMillis() + AWAIT_MS;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(POLL_NANOS);
        }

        assertThat(condition.getAsBoolean()).as("%s within %d ms", what, AWAIT_MS).isTrue();
    }

    private AetherNodeConfig config() {
        var self = NodeId.nodeId("seal-fails-closed-boot-" + java.util.UUID.randomUUID()).unwrap();
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
                               .streaming(StreamingConfig.streamingConfig().withDhtSegmentMaxBytes(CAP_BYTES))
                               .protocol(ProtocolConfig.defaultConfig())
                               .sliceAction(SliceActionConfig.sliceActionConfig())
                               .cache(DHTConfig.CACHE_DEFAULT)
                               .controllerConfig(ControllerConfig.DEFAULT)
                               .autoHeal(AutoHealConfig.DEFAULT)
                               .build();
    }
}
