// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.SegmentTierPressure;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;

/// #1604: at [SegmentTierPressure#WARN_AT] retention warns once per pressure episode, and relieves the pressure
/// (the snapshot-bounded collection) on every pass while it lasts; below it, neither.
class RetentionPressureWarningTest {
    @Test
    void warnsOncePerEpisode_andRelievesOnEveryPassUnderPressure() {
        var utilization = new AtomicReference<>(0.9);
        var reliefs = new AtomicInteger();
        var storage = StorageInstance.storageInstance("pressure", List.of(MemoryTier.memoryTier(1 << 20)));
        var index = new SegmentIndex();
        var enforcer = RetentionEnforcer.retentionEnforcer(storage,
                                                           index,
                                                           60_000L,
                                                           RetentionEnforcer.SegmentRetentionFloor.NONE,
                                                           RefDurability.LIVE,
                                                           SegmentReader.segmentReader(storage, index),
                                                           utilization::get,
                                                           reliefs::incrementAndGet);

        try (var capture = LogCapture.capture(RetentionEnforcer.class, Level.ALL, true)) {
            enforcer.enforceNow().await();
            enforcer.enforceNow().await();
            assertThat(capture.messages(Level.WARN)).as("one WARN for the episode").hasSize(1);
            assertThat(reliefs.get()).as("relieved on each pass under pressure").isEqualTo(2);

            utilization.set(0.5);
            enforcer.enforceNow().await();
            assertThat(reliefs.get()).as("no relief below the threshold").isEqualTo(2);

            utilization.set(0.9);
            enforcer.enforceNow().await();
            assertThat(capture.messages(Level.WARN)).as("a new episode warns again").hasSize(2);
        }
    }
}
