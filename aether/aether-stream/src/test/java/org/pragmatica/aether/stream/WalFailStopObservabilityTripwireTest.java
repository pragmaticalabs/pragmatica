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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
/// PR-2c flipped it (D-01): the tripwire is deleted and the real assertion enabled. The manager registers a fail-stop listener on every partition log
/// it opens and raises `STORAGE_WAL_FAIL_STOPPED` with the subject `stream[partition]` (`know:` `4ddd1ddfda`). The real assertion names no warning
/// code: that some operator warning naming the partition is raised is the acceptance, and the figure of 5 s to raise it is a guess marked as one.
/// `PartitionFailStopTest` pins the code, the subject, the once-per-log count and the refusal.
@Timeout(60)
class WalFailStopObservabilityTripwireTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;

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
    void walFailStop_raisesAnOperatorWarning() throws Exception {
        failStopTheWal();

        manager.publishLocal(STREAM, PARTITION, "lost-1".getBytes(UTF_8), 2L);

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (!namesTheStoppedPartition() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }

        assertThat(namesTheStoppedPartition()).as("an operator learns of the fail-stop from an alarm that names the stream and partition, not from polling the JSON: %s", warnings).isTrue();
    }

    private boolean namesTheStoppedPartition() {
        return warnings.stream().anyMatch(warning -> warning.subject().contains(STREAM) && warning.subject().contains(String.valueOf(PARTITION)));
    }

    /// An I/O failure on the WAL's channel: the next frame write fails and the log stops for good (`failStop`).
    private void failStopTheWal() throws Exception {
        var field = AppendLog.class.getDeclaredField("channel");

        field.setAccessible(true);
        ((FileChannel) field.get(GatedWalFsync.walOf(manager, STREAM, PARTITION))).close();
    }
}
