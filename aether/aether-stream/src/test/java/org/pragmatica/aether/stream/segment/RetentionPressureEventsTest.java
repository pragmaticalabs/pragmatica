// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.WarningLevel;

import static org.assertj.core.api.Assertions.assertThat;

/// #2258 spec 3.2 item 8 (NT-126, the disk codes): where `reportPressure` logged once per episode, the node now also raises
/// `STREAM_SEGMENT_DISK_PRESSURE` there, once per episode, closed by `STREAM_SEGMENT_DISK_PRESSURE_RELIEVED` when the tier
/// falls below the warning ratio; subject `node <nodeId> storage streams`.
class RetentionPressureEventsTest {
    private final List<OperatorWarning> raised = new CopyOnWriteArrayList<>();
    private final AtomicReference<Double> utilization = new AtomicReference<>(0.5);
    private final RetentionEnforcer enforcer = enforcer();

    private RetentionEnforcer enforcer() {
        var storage = StorageInstance.storageInstance("pressure", List.of(MemoryTier.memoryTier(1 << 20)));
        var index = new SegmentIndex();
        var enforcer = RetentionEnforcer.retentionEnforcer(storage,
                                                           index,
                                                           60_000L,
                                                           RetentionEnforcer.SegmentRetentionFloor.NONE,
                                                           RefDurability.LIVE,
                                                           SegmentReader.segmentReader(storage, index),
                                                           utilization::get,
                                                           () -> 0);

        enforcer.operatorWarnings(OperatorWarningSink.handingOffTo(raised::add), "node-1");

        return enforcer;
    }

    /// The sink hands warnings off on its own thread: wait until the queue has drained and nothing more arrives.
    private void awaitQuiet() {
        try {
            for (var round = 0; round < 40; round++) {
                var seen = raised.size();

                Thread.sleep(150);
                if (raised.size() == seen) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<OperatorWarningCode> codes() {
        awaitQuiet();

        return raised.stream().map(OperatorWarning::code).toList();
    }

    @Test
    void anEpisodeIsRaisedOnce_andClosedWhenTheTierFallsBelowTheWarningRatio() {
        enforcer.enforceNow().await();
        assertThat(codes()).as("50% is below the warning").isEmpty();

        utilization.set(0.9);
        enforcer.enforceNow().await();
        enforcer.enforceNow().await();
        enforcer.enforceNow().await();

        assertThat(codes()).as("three passes under pressure are one event")
                           .containsExactly(OperatorWarningCode.STREAM_SEGMENT_DISK_PRESSURE);
        assertThat(raised.getFirst().subject()).isEqualTo("node node-1 storage streams");
        assertThat(raised.getFirst().code().level()).isEqualTo(WarningLevel.WARNING);

        utilization.set(0.5);
        enforcer.enforceNow().await();
        enforcer.enforceNow().await();

        assertThat(codes()).containsExactly(OperatorWarningCode.STREAM_SEGMENT_DISK_PRESSURE,
                                            OperatorWarningCode.STREAM_SEGMENT_DISK_PRESSURE_RELIEVED);
        assertThat(raised.getLast().subject()).isEqualTo("node node-1 storage streams");

        utilization.set(0.95);
        enforcer.enforceNow().await();

        assertThat(codes()).as("a new episode raises again")
                           .containsExactly(OperatorWarningCode.STREAM_SEGMENT_DISK_PRESSURE,
                                            OperatorWarningCode.STREAM_SEGMENT_DISK_PRESSURE_RELIEVED,
                                            OperatorWarningCode.STREAM_SEGMENT_DISK_PRESSURE);
    }
}
