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
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.DegradeReporter;
import org.pragmatica.storage.DemotionManager;
import org.pragmatica.storage.PromotionManager;
import org.pragmatica.storage.StorageGarbageCollector;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

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
    private volatile boolean retryThrows = false;

    @Test
    void progress_beforeAnyTick_neverRun() {
        assertThat(driver().progress()).isEqualTo(StorageMaintenanceProgress.storageMaintenanceProgress());
        assertThat(driver().progress().outcome()).isEqualTo(Outcome.NEVER_RUN);
    }

    @Test
    void progress_reflectsEachTick_countsAccumulateAndFailuresStreak() {
        var driver = driver();

        doReturn(3, 4).when(demotion).demote();
        doReturn(2, 0).when(gc).collectGarbage();
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

        doThrow(new IllegalStateException("tier gone")).when(demotion).demote();
        driver.tick();
        driver.tick();
        var failed = driver.progress();

        assertThat(failed.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(failed.consecutiveFailedTicks()).isEqualTo(2);
        assertThat(failed.lastFailure().fold(() -> "", message -> message)).contains("tier gone");
        assertThat(failed.demoted()).as("a failed pass adds nothing").isEqualTo(7);

        doReturn(1).when(demotion).demote();
        driver.tick();
        var recovered = driver.progress();

        assertThat(recovered.outcome()).isEqualTo(Outcome.OK);
        assertThat(recovered.consecutiveFailedTicks()).as("a good tick restarts the streak").isZero();
        assertThat(recovered.lastFailure().fold(() -> "", message -> message)).as("the latest cause is kept").contains("tier gone");
        assertThat(recovered.demoted()).isEqualTo(8);
    }

    @Test
    void progress_failedGarbageCollectionAloneFailsTheTick() {
        var driver = driver();

        doThrow(new IllegalStateException("gc boom")).when(gc).collectGarbage();
        driver.tick();

        assertThat(driver.progress().outcome()).isEqualTo(Outcome.FAILED);
        assertThat(driver.progress().lastFailure().fold(() -> "", message -> message)).contains("gc boom");
    }

    @Test
    void failingDemotion_raisesOnceThenRecovers_andGarbageCollectionStillRuns() throws InterruptedException {
        var driver = driver();

        doThrow(new IllegalStateException("tier gone")).when(demotion).demote();
        doReturn(5).when(gc).collectGarbage();
        driver.tick();
        driver.tick();

        assertThat(codes(1)).containsExactly(FAILED);
        assertThat(raised.getFirst().subject()).isEqualTo("storage-maintenance/demotion");
        assertThat(raised.getFirst().message()).contains("tier gone");
        assertThat(driver.progress().collected()).as("GC ran although demotion threw").isEqualTo(10);

        doReturn(0).when(demotion).demote();
        driver.tick();
        driver.tick();

        assertThat(codes(2)).containsExactly(FAILED, RECOVERED);
        assertThat(raised.getLast().subject()).isEqualTo("storage-maintenance/demotion");
    }

    @Test
    void failingGarbageCollection_raisesItsOwnPartThenRecovers() throws InterruptedException {
        var driver = driver();

        doThrow(new IllegalStateException("gc boom")).when(gc).collectGarbage();
        driver.tick();

        assertThat(codes(1)).containsExactly(FAILED);
        assertThat(raised.getFirst().subject()).isEqualTo("storage-maintenance/garbage-collection");

        doReturn(0).when(gc).collectGarbage();
        driver.tick();

        assertThat(codes(2)).containsExactly(FAILED, RECOVERED);
        assertThat(raised.getLast().subject()).isEqualTo("storage-maintenance/garbage-collection");
    }

    @Test
    void failingPromotionCycle_raisesOnceThenRecovers() throws InterruptedException {
        var driver = driver();

        doThrow(new IllegalStateException("source unreadable")).when(promotion).promote();
        driver.tick();
        driver.tick();

        assertThat(codes(1)).containsExactly(FAILED);
        assertThat(raised.getFirst().subject()).isEqualTo("storage-maintenance/promotion");

        doReturn(0).when(promotion).promote();
        driver.tick();

        assertThat(codes(2)).containsExactly(FAILED, RECOVERED);
        assertThat(raised.getLast().subject()).isEqualTo("storage-maintenance/promotion");
    }

    @Test
    void promotionCycleThatCannotStart_raisesThePromotionPassFailed() throws InterruptedException {
        var driver = driver(_ -> {
            throw new IllegalStateException("no thread");
        });

        driver.tick();

        assertThat(codes(1)).containsExactly(FAILED);
        assertThat(raised.getFirst().subject()).isEqualTo("storage-maintenance/promotion");
        assertThat(raised.getFirst().message()).contains("no thread");
    }

    @Test
    void tick_asksTheAdapterToRetryPendingStateChangesOnce() {
        driver().tick();

        assertThat(retries.get()).isEqualTo(1);
    }

    /// #3087: a throwing retry of the pending activation is the tick's failed pass, reported as itself: the tick completes
    /// (so the overdue watchdog does not take over), its progress record shows the real cause, and the warning names the part.
    @Test
    void failingRetryPending_isReportedAsTheFailedPass_andTheTickStillRecordsProgress() throws InterruptedException {
        var driver = driver();

        retryThrows = true;
        doReturn(2).when(gc).collectGarbage();

        assertThatCode(driver::tick).as("the tick survives a throwing retry").doesNotThrowAnyException();
        assertThat(driver.progress().outcome()).as("the tick recorded its progress").isEqualTo(Outcome.FAILED);
        assertThat(driver.progress().lastFailure().fold(() -> "", message -> message)).contains("retry boom");
        assertThat(driver.progress().collected()).as("passes before the retry still counted").isEqualTo(2);
        assertThat(codes(1)).containsExactly(FAILED);
        assertThat(raised.getFirst().subject()).isEqualTo("storage-maintenance/retry-pending");
        assertThat(raised.getFirst().message()).contains("retry boom");

        retryThrows = false;
        driver.tick();

        assertThat(driver.progress().outcome()).isEqualTo(Outcome.OK);
        assertThat(codes(2)).containsExactly(FAILED, RECOVERED);
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
        return driver(Runnable::run);
    }

    private StorageMaintenanceDriver driver(java.util.concurrent.Executor executor) {
        var degrade = DegradeReporter.degradeReporter(StorageMaintenanceDriver.REPORT_SUBJECT);

        degrade.bind(OperatorWarningSink.handingOffTo(raised::add));

        return StorageMaintenanceDriver.storageMaintenanceDriver(demotion,
                                                                  promotion,
                                                                  PromotionStallWatch.promotionStallWatch(Map.of(),
                                                                                                          OperatorWarningSink.logOnly()),
                                                                  gc,
                                                                  executor,
                                                                  degrade,
                                                                  this::countRetry,
                                                                  System::currentTimeMillis,
                                                                  TimeSpan.timeSpan(5).minutes());
    }

    private Unit countRetry() {
        retries.incrementAndGet();
        if (retryThrows) {
            throw new IllegalStateException("retry boom");
        }

        return Unit.unit();
    }
}
