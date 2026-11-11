// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.StorageMaintenanceProgress.Outcome;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.DegradeReporter;
import org.pragmatica.storage.DemotionManager;
import org.pragmatica.storage.PromotionManager;
import org.pragmatica.storage.StorageGarbageCollector;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// #800: each failing maintenance pass raises its warning once and the next good pass closes it; the progress record reflects
/// every tick; the tick asks the adapter to retry a failed activation.
class StorageMaintenanceDriverProgressTest {
    private static final OperatorWarningCode FAILED = OperatorWarningCode.STORAGE_MAINTENANCE_PASS_FAILED;
    private static final OperatorWarningCode RECOVERED = OperatorWarningCode.STORAGE_MAINTENANCE_PASS_RECOVERED;

    private final DemotionManager demotion = mock(DemotionManager.class);
    private final StorageGarbageCollector gc = mock(StorageGarbageCollector.class);
    private final PromotionManager promotion = mock(PromotionManager.class);
    private final List<OperatorWarning> raised = new CopyOnWriteArrayList<>();
    private final AtomicInteger retries = new AtomicInteger();

    @Test
    void progress_beforeAnyTick_neverRun() {
        assertThat(driver().progress()).isEqualTo(StorageMaintenanceProgress.storageMaintenanceProgress());
        assertThat(driver().progress().outcome()).isEqualTo(Outcome.NEVER_RUN);
    }

    @Test
    void progress_reflectsEachTick_countsAccumulateAndFailuresStreak() {
        var driver = driver();

        when(demotion.demote()).thenReturn(3, 4);
        when(gc.collectGarbage()).thenReturn(2, 0);
        driver.tick();
        var first = driver.progress();
        driver.tick();
        var second = driver.progress();

        assertThat(first.outcome()).isEqualTo(Outcome.OK);
        assertThat(first.lastRunMs()).isPositive();
        assertThat(first.demoted()).isEqualTo(3);
        assertThat(first.collected()).isEqualTo(2);
        assertThat(second.demoted()).as("cumulative").isEqualTo(7);
        assertThat(second.collected()).isEqualTo(2);
        assertThat(second.lastRunMs()).isGreaterThanOrEqualTo(first.lastRunMs());

        when(demotion.demote()).thenThrow(new IllegalStateException("tier gone"));
        driver.tick();
        driver.tick();
        var failed = driver.progress();

        assertThat(failed.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(failed.consecutiveFailedTicks()).isEqualTo(2);
        assertThat(failed.lastFailure().fold(() -> "", message -> message)).contains("tier gone");
        assertThat(failed.demoted()).as("a failed pass adds nothing").isEqualTo(7);

        when(demotion.demote()).thenReturn(1);
        driver.tick();
        var recovered = driver.progress();

        assertThat(recovered.outcome()).isEqualTo(Outcome.OK);
        assertThat(recovered.consecutiveFailedTicks()).as("a good tick restarts the streak").isZero();
        assertThat(recovered.lastFailure().fold(() -> "", message -> message)).as("the latest cause is kept").contains("tier gone");
        assertThat(recovered.demoted()).isEqualTo(8);
    }

    @Test
    void failingDemotion_raisesOnceThenRecovers_andGarbageCollectionStillRuns() throws InterruptedException {
        var driver = driver();

        when(demotion.demote()).thenThrow(new IllegalStateException("tier gone"));
        when(gc.collectGarbage()).thenReturn(5);
        driver.tick();
        driver.tick();

        assertThat(codes(1)).containsExactly(FAILED);
        assertThat(raised.getFirst().subject()).isEqualTo("storage-maintenance/demotion");
        assertThat(raised.getFirst().message()).contains("tier gone");
        assertThat(driver.progress().collected()).as("GC ran although demotion threw").isEqualTo(10);

        when(demotion.demote()).thenReturn(0);
        driver.tick();
        driver.tick();

        assertThat(codes(2)).containsExactly(FAILED, RECOVERED);
        assertThat(raised.getLast().subject()).isEqualTo("storage-maintenance/demotion");
    }

    @Test
    void failingGarbageCollection_raisesItsOwnPartThenRecovers() throws InterruptedException {
        var driver = driver();

        when(gc.collectGarbage()).thenThrow(new IllegalStateException("gc boom"));
        driver.tick();

        assertThat(codes(1)).containsExactly(FAILED);
        assertThat(raised.getFirst().subject()).isEqualTo("storage-maintenance/garbage-collection");

        when(gc.collectGarbage()).thenReturn(0);
        driver.tick();

        assertThat(codes(2)).containsExactly(FAILED, RECOVERED);
        assertThat(raised.getLast().subject()).isEqualTo("storage-maintenance/garbage-collection");
    }

    @Test
    void failingPromotionCycle_raisesOnceThenRecovers() throws InterruptedException {
        var driver = driver();

        when(promotion.promote()).thenThrow(new IllegalStateException("source unreadable"));
        driver.tick();
        driver.tick();

        assertThat(codes(1)).containsExactly(FAILED);
        assertThat(raised.getFirst().subject()).isEqualTo("storage-maintenance/promotion");

        when(promotion.promote()).thenReturn(0);
        driver.tick();

        assertThat(codes(2)).containsExactly(FAILED, RECOVERED);
        assertThat(raised.getLast().subject()).isEqualTo("storage-maintenance/promotion");
    }

    @Test
    void tick_asksTheAdapterToRetryPendingStateChangesOnce() {
        driver().tick();

        assertThat(retries.get()).isEqualTo(1);
    }

    /// Waits for `expected` warnings to reach the hand-off sink, then lets a spurious extra one arrive before the caller asserts
    /// the list is exactly what it expects.
    private List<OperatorWarningCode> codes(int expected) throws InterruptedException {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (raised.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        Thread.sleep(200);

        return raised.stream()
                     .map(OperatorWarning::code)
                     .toList();
    }

    private StorageMaintenanceDriver driver() {
        var degrade = DegradeReporter.degradeReporter(StorageMaintenanceDriver.REPORT_SUBJECT);

        degrade.bind(OperatorWarningSink.handingOffTo(raised::add));

        return StorageMaintenanceDriver.storageMaintenanceDriver(demotion,
                                                                  promotion,
                                                                  PromotionStallWatch.promotionStallWatch(Map.of(),
                                                                                                          OperatorWarningSink.logOnly()),
                                                                  gc,
                                                                  Runnable::run,
                                                                  degrade,
                                                                  this::countRetry);
    }

    private Unit countRetry() {
        retries.incrementAndGet();

        return Unit.unit();
    }
}
