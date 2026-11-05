// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// Streams overhaul PR-2c, D-01 (#2201, `know:` `4ddd1ddfda`), NT-31 and NT-32 at component level. A partition whose WAL fail-stopped refuses
/// every later owner publish with `PartitionFailStopped` before the ring assigns it an offset; the fail-stop raises ONE
/// `STORAGE_WAL_FAIL_STOPPED` warning with the subject `stream[partition]` (the listener the manager registers on the log at open), and
/// the manager's fail-stopped count, the value of the node gauge `aether.streams.wal.failstopped`, is 0 before and 1 after.
@Timeout(60)
class PartitionFailStopTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;

    @TempDir
    Path walDir;

    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private final List<StreamPartitionManager> managers = new ArrayList<>();
    private StreamPartitionManager manager;

    @AfterEach
    void tearDown() {
        managers.forEach(StreamPartitionManager::close);
    }

    @Test
    void failStoppedWal_refusesBeforeAnOffset_andRaisesOneWarningNamingThePartition() throws Exception {
        manager = materialized();
        manager.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
        publish("acked").onFailure(cause -> fail(cause.message()));
        assertThat(manager.failStoppedWalCount()).as("NT-32: 0 before any fail-stop").isZero();

        failStopTheWal();
        assertThat(publish("meets-the-failure").isFailure()).as("control: the publish that met the I/O failure failed").isTrue();
        var head = ring().headOffset();

        for (var i = 0; i < 3; i++) {
            publish("refused-" + i).onSuccess(offset -> fail("a fail-stopped partition took offset " + offset))
                                   .onFailure(PartitionFailStopTest::assertCarriesTheLogsCause);
        }
        assertThat(ring().headOffset()).as("a refused publish takes no offset").isEqualTo(head);
        awaitWarnings(1);
        TimeUnit.MILLISECONDS.sleep(200);
        assertThat(warnings).as("one warning per fail-stop, not one per refused publish").hasSize(1);
        assertThat(warnings.getFirst().code()).isEqualTo(OperatorWarningCode.STORAGE_WAL_FAIL_STOPPED);
        assertThat(warnings.getFirst().subject()).isEqualTo(STREAM + "[" + PARTITION + "]");
        assertThat(manager.failStoppedWalCount()).as("NT-32: at least 1 while the partition is fail-stopped").isEqualTo(1L);
    }

    @Test
    void failStoppedWal_refusesAnOwnerBatchBeforeAnOffset() throws Exception {
        manager = materialized();
        publish("acked").onFailure(cause -> fail(cause.message()));
        failStopTheWal();
        publish("meets-the-failure");
        var head = ring().headOffset();

        manager.publishLocalBatchAtFloor(STREAM, PARTITION, List.of("b0".getBytes(UTF_8), "b1".getBytes(UTF_8)), 2L, 0)
               .onSuccess(offset -> fail("a fail-stopped partition took a batch through offset " + offset))
               .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.PartitionFailStopped.class));
        assertThat(ring().headOffset()).as("a refused batch takes no offset").isEqualTo(head);
    }

    @Test
    void failStopBeforeTheSinkIsBound_isRaisedWhenItBinds() throws Exception {
        manager = materialized();
        publish("acked").onFailure(cause -> fail(cause.message()));
        failStopTheWal();
        publish("meets-the-failure");
        assertThat(warnings).as("control: nothing is emitted before the sink is bound").isEmpty();

        manager.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
        awaitWarnings(1);
        assertThat(warnings.getFirst().subject()).isEqualTo(STREAM + "[" + PARTITION + "]");
    }

    /// A partition hydrated from a committed config (the follower path) reports its log's fail-stop the same way.
    @Test
    void hydratedPartition_failStop_raisesTheWarning_andCounts() throws Exception {
        manager = tracked(streamPartitionManager(Long.MAX_VALUE, Option.some(walDir)));
        manager.placementRoleSupplier((_, _) -> Role.OWNER);
        manager.onStreamConfigPut(configPut(StreamConfig.streamConfig(STREAM)));
        assertThat(manager.partitionBuffer(STREAM, PARTITION).isPresent()).as("control: the hydrate materialized the partition").isTrue();

        assertFailStopIsReported();
    }

    /// A partition materialized on its own, after a metadata-only hydrate (the reconcile hook), reports its log's fail-stop the same way.
    @Test
    void lazilyMaterializedPartition_failStop_raisesTheWarning_andCounts() throws Exception {
        var role = new AtomicReference<>(Role.NONE);

        manager = tracked(streamPartitionManager(Long.MAX_VALUE, Option.some(walDir)));
        manager.placementRoleSupplier((_, _) -> role.get());
        manager.onStreamConfigPut(configPut(StreamConfig.streamConfig(STREAM)));
        assertThat(manager.partitionBuffer(STREAM, PARTITION).isPresent()).as("control: the hydrate was metadata-only").isFalse();
        role.set(Role.OWNER);
        manager.materializePartition(STREAM, PARTITION).onFailure(cause -> fail(cause.message()));

        assertFailStopIsReported();
    }

    /// A partition with no WAL has nothing to fail-stop: the gauge counts only logs that did.
    @Test
    void partitionWithoutAWal_isNotCountedAsFailStopped() {
        manager = tracked(streamPartitionManager(Long.MAX_VALUE, Option.none()));
        manager.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        assertThat(manager.partitionBuffer(STREAM, PARTITION).isPresent()).as("control: the partition is materialized").isTrue();

        assertThat(manager.failStoppedWalCount()).isZero();
    }

    /// CTO ruling on #2615 (verdict A item 6): the fail-stop is closed by `STORAGE_WAL_WRITABLE`, same subject, when the node restarts
    /// and the partition's reopened log accepts a durable write. Raised once: the next restart has nothing to close.
    @Test
    void failStopThenRestart_firstDurableWrite_raisesTheRecoveryOnce() throws Exception {
        manager = materialized();
        manager.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
        publish("acked").onFailure(cause -> fail(cause.message()));
        failStopTheWal();
        publish("meets-the-failure");
        awaitWarnings(1);
        closeManager();
        warnings.clear();

        manager = materialized();
        manager.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
        TimeUnit.MILLISECONDS.sleep(200);
        assertThat(warnings).as("reopening alone is not the recovery: no write was accepted yet").isEmpty();
        publish("after-restart").onFailure(cause -> fail(cause.message()));
        awaitWarnings(1);
        assertThat(warnings.getFirst().code()).isEqualTo(OperatorWarningCode.STORAGE_WAL_WRITABLE);
        assertThat(warnings.getFirst().subject()).isEqualTo(STREAM + "[" + PARTITION + "]");
        publish("second-after-restart").onFailure(cause -> fail(cause.message()));
        TimeUnit.MILLISECONDS.sleep(200);
        assertThat(warnings).as("once per restart, not once per write").hasSize(1);
        closeManager();
        warnings.clear();

        manager = materialized();
        manager.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
        publish("after-second-restart").onFailure(cause -> fail(cause.message()));
        TimeUnit.MILLISECONDS.sleep(300);
        assertThat(warnings).as("the recovery deleted the mark: a later restart raises nothing").isEmpty();
    }

    private void assertFailStopIsReported() throws Exception {
        manager.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
        publish("acked").onFailure(cause -> fail(cause.message()));
        failStopTheWal();
        publish("meets-the-failure");

        awaitWarnings(1);
        assertThat(warnings.getFirst().code()).isEqualTo(OperatorWarningCode.STORAGE_WAL_FAIL_STOPPED);
        assertThat(warnings.getFirst().subject()).isEqualTo(STREAM + "[" + PARTITION + "]");
        assertThat(manager.failStoppedWalCount()).isEqualTo(1L);
    }

    /// The refusal is terminal (a retry on this node cannot succeed), carries the log's own cause as its source, and names it.
    private static void assertCarriesTheLogsCause(Cause cause) {
        assertThat(cause).isInstanceOf(StreamError.PartitionFailStopped.class)
                         .isInstanceOf(Cause.Terminal.class);
        var walCause = ((StreamError.PartitionFailStopped) cause).cause();

        assertThat(cause.source()).as("the source is the log's cause").contains(walCause);
        assertThat(cause.message()).as("the message carries the log's cause").contains(walCause.message());
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> configPut(StreamConfig config) {
        return new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey(config.name()),
                                                  StreamConfigValue.streamConfigValue(config)),
                              Option.none());
    }

    private void closeManager() {
        managers.remove(manager);
        manager.close();
    }

    private StreamPartitionManager tracked(StreamPartitionManager built) {
        managers.add(built);
        return built;
    }

    private StreamPartitionManager materialized() {
        var built = tracked(streamPartitionManager(Long.MAX_VALUE, Option.some(walDir)));

        built.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        return built;
    }

    private Result<Long> publish(String text) {
        return manager.publishLocal(STREAM, PARTITION, text.getBytes(UTF_8), 1L);
    }

    private OffHeapRingBuffer ring() {
        return manager.partitionBuffer(STREAM, PARTITION).unwrap();
    }

    private void awaitWarnings(int count) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (warnings.size() < count && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(warnings).as("the fail-stop warning arrived").hasSizeGreaterThanOrEqualTo(count);
    }

    /// An I/O failure on the WAL's channel: the next frame write fails and the log stops for good.
    private void failStopTheWal() throws Exception {
        var field = AppendLog.class.getDeclaredField("channel");

        field.setAccessible(true);
        ((FileChannel) field.get(GatedWalFsync.walOf(manager, STREAM, PARTITION))).close();
    }
}
