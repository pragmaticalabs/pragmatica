// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
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
    private StreamPartitionManager manager;

    @AfterEach
    void tearDown() {
        manager.close();
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
                                   .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.PartitionFailStopped.class));
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

    private StreamPartitionManager materialized() {
        var built = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

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
