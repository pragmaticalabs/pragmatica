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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// Streams audit D-01 (#2201): a WAL fail-stop (an I/O failure on the partition log, disk full above all) leaves the partition unwritable until a human
/// restarts the node, and nothing tells an operator: no operator warning, no gauge, no ownership step-down. `WalStats.failStopped` IS exposed, as a
/// pull field in the management JSON (1b-edge), so this is not "no metric"; it is "no alarm, no gauge, no step-down".
///
/// Reached here, at component level: the alarm half. The real manager over a real `AppendLog` whose channel fails (a closed channel stands in for
/// ENOSPC), with a real operator-warning sink bound the way `AetherNode` binds the cluster one. NOT reached here, and handed to the cluster author
/// (t-trip-cluster): the ownership step-down (the relinquish seam lives in the ownership writer on a node, with committed records to step down from; the
/// manager has no hook for it) and the Prometheus gauge (registered by `ManagementServer`, not by the manager).
///
/// TRIPWIRE (enabled, GREEN) beside the REAL assertion (`@Disabled`, RED today). The real assertion names no warning code: the overhaul chooses it;
/// that some operator warning is raised for the fail-stop is the acceptance, and the figure of 5 s to raise it is a guess marked as one.
class WalFailStopObservabilityTripwireTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final String ENABLE = "D-01 tripwire: a WAL fail-stop now raises an operator warning; delete this tripwire and enable the real assertion beside it (#2201)";

    @TempDir
    Path walDir;

    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
        manager.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
        manager.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        manager.publishLocal(STREAM, PARTITION, "acked".getBytes(UTF_8), 1L).onFailure(cause -> fail(cause.message()));
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void walFailStop_isExposedAsAPullField_butRaisesNoOperatorWarning_tripwire() throws Exception {
        failStopTheWal();

        var first = manager.publishLocal(STREAM, PARTITION, "lost-1".getBytes(UTF_8), 2L);
        var second = manager.publishLocal(STREAM, PARTITION, "lost-2".getBytes(UTF_8), 3L);

        assertThat(first.isFailure()).as("control: the publish that met the I/O failure is reported failed").isTrue();
        assertThat(second.isFailure()).as("control: the partition stays unwritable").isTrue();
        assertThat(failStoppedInTheSnapshot()).as("control, 1b-edge: WalStats.failStopped IS exposed (the management JSON reads this)").isTrue();
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(warnings).as(ENABLE).isEmpty();
    }

    @Disabled("D-01 real assertion, RED today: a WAL fail-stop must raise an operator warning within 5 s (#2201)")
    @Test
    void walFailStop_raisesAnOperatorWarning() throws Exception {
        failStopTheWal();

        manager.publishLocal(STREAM, PARTITION, "lost-1".getBytes(UTF_8), 2L);

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (warnings.isEmpty() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }

        assertThat(warnings).as("an operator learns of the fail-stop from an alarm, not from polling the JSON").isNotEmpty();
    }

    private boolean failStoppedInTheSnapshot() {
        return manager.walSnapshot()
                      .streams()
                      .stream()
                      .flatMap(stream -> stream.partitions().stream())
                      .anyMatch(view -> view.wal().map(AppendLog.WalStats::failStopped).or(false));
    }

    /// An I/O failure on the WAL's channel: the next frame write fails and the log stops for good (`failStop`).
    private void failStopTheWal() throws Exception {
        var field = AppendLog.class.getDeclaredField("channel");

        field.setAccessible(true);
        ((FileChannel) field.get(GatedWalFsync.walOf(manager, STREAM, PARTITION))).close();
    }
}
