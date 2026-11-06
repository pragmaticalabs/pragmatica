// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.StreamPartitionManager.PartitionRef;

import static org.assertj.core.api.Assertions.assertThat;

/// #2201 A-05, the author pre-check of PR-2a: [MaterializationScheduler#clearOnClose] drops what the manager's `close` dropped
/// before the extraction, every row but the slot tenure ([MaterializationScheduler#slotAcquiredTick] and
/// [MaterializationScheduler#preemptedSlots]). A closed manager is never ticked again, so no manager-level test can see a row
/// the close skipped; this pins the moved method itself.
class MaterializationSchedulerClearOnCloseTest {
    private static final PartitionRef REF = new PartitionRef("orders", 0);

    @Test
    void clearOnClose_dropsEveryRowButTheSlotTenure() {
        var scheduler = MaterializationScheduler.materializationScheduler();

        scheduler.inFlightMaterializations().add(REF);
        scheduler.systemMaterializeQueue().add(REF);
        scheduler.appMaterializeQueue().add(REF);
        scheduler.queuedMaterializations().add(REF);
        scheduler.heldUnmaterializedSince().put(REF, 1L);
        scheduler.releaseCandidacy().put(REF, 1L);
        scheduler.durableAtPreviousTick().put("orders#0", 1L);
        scheduler.slotAcquiredTick().put(REF, 1L);
        scheduler.preemptedSlots().add(REF);

        scheduler.clearOnClose();

        assertThat(scheduler.inFlightMaterializations()).as("inFlightMaterializations").isEmpty();
        assertThat(scheduler.systemMaterializeQueue()).as("systemMaterializeQueue").isEmpty();
        assertThat(scheduler.appMaterializeQueue()).as("appMaterializeQueue").isEmpty();
        assertThat(scheduler.queuedMaterializations()).as("queuedMaterializations").isEmpty();
        assertThat(scheduler.heldUnmaterializedSince()).as("heldUnmaterializedSince").isEmpty();
        assertThat(scheduler.releaseCandidacy()).as("releaseCandidacy").isEmpty();
        assertThat(scheduler.durableAtPreviousTick()).as("durableAtPreviousTick").isEmpty();
        assertThat(scheduler.slotAcquiredTick()).as("the slot tenure is kept, as before").containsKey(REF);
        assertThat(scheduler.preemptedSlots()).as("the slot tenure is kept, as before").contains(REF);
    }
}
