// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// B9 (v1890 probe G). The CF=1 data-loss report is deferred until the repair "settles" (the copy is
/// CAUGHT_UP again) and is held in memory meanwhile (`pendingCuts`). The cut itself is durable at once (WAL forced). So a
/// copy that cuts acknowledged-at-owner records and then restarts before it settles never reports the loss: the report
/// is gone with the process, and the restarted copy has nothing left to cut. The report is therefore also recorded beside the WAL until it is made, and made at reopen when it was not.
class RepairReportSurvivesRestartTest {
    private static final String STREAM = "single";
    private static final int PARTITION = 0;
    private static final Epoch E = Epoch.epoch(1L, 2L, 3L);

    @TempDir
    Path walDir;

    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();

    @Test
    void cf1CutThenRestartBeforeSettling_stillReportsTheLoss() {
        var first = cf1Manager();

        seedAndQuarantine(first);
        var cut = first.repairDivergence(STREAM, PARTITION, _ -> true).unwrap();

        assertThat(cut.isPresent()).as("premise: the tail was cut").isTrue();
        first.close();
        var restarted = cf1Manager();

        assertThat(texts(restarted)).as("premise: the cut is durable across the restart").hasSize(3);
        quietPeriod();
        restarted.close();

        assertThat(warnings).as("CF=1 acknowledged-at-owner records [3, 7] were discarded; the operator was told").isNotEmpty();
    }

    /// Control: the same cut, settled before any restart, reports once (the path the author pins).
    @Test
    void control_cf1CutSettled_reports() {
        var manager = cf1Manager();

        seedAndQuarantine(manager);
        manager.repairDivergence(STREAM, PARTITION, _ -> true).unwrap();
        manager.quarantineView().repairSettled(STREAM, PARTITION);
        awaitWarnings(1);
        manager.close();

        assertThat(warnings).hasSize(1);
    }

    private StreamPartitionManager cf1Manager() {
        var manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

        manager.createStream(StreamConfig.streamConfig(STREAM).withReplication(ReplicationFactors.replicationFactors(1, 1).unwrap()))
               .onFailure(cause -> fail(cause.message()));
        manager.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));

        return manager;
    }

    private static void seedAndQuarantine(StreamPartitionManager manager) {
        for (var i = 0; i < 8; i++) {
            manager.appendRecovered(STREAM, PARTITION, i, ("r" + i).getBytes(UTF_8), 1000L + i, E).unwrap();
        }
        manager.syncReplicated(STREAM, PARTITION).await();
        manager.appendRecovered(STREAM, PARTITION, 3, "different".getBytes(UTF_8), 1003L, E);
        assertThat(manager.quarantinedAt(STREAM, PARTITION).or(-1L)).as("premise: quarantined at 3").isEqualTo(3L);
    }

    private static List<String> texts(StreamPartitionManager manager) {
        return manager.readAppended(STREAM, PARTITION, 0, 100)
                      .unwrap()
                      .stream()
                      .map(event -> new String(event.data(), UTF_8))
                      .toList();
    }

    private void awaitWarnings(int expected) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (warnings.size() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static void quietPeriod() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
