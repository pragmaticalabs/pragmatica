// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/// #800 (ruling know de31898ea4): a pass that does not complete within three maintenance intervals raises the overdue
/// warning, the next completed tick or promotion cycle raises its recovery; promotion results count in the progress record;
/// the degradation and the gauges read the same state. Time is the injected clock: nothing here waits for real time to pass.
class StorageMaintenanceDriverOverdueTest {
    private static final OperatorWarningCode OVERDUE = OperatorWarningCode.STORAGE_MAINTENANCE_PASS_OVERDUE;
    private static final OperatorWarningCode COMPLETED = OperatorWarningCode.STORAGE_MAINTENANCE_PASS_COMPLETED;
    private static final long INTERVAL_MS = 60_000;
    /// Three intervals, as ruled; written out rather than read from the driver, so that a changed constant goes red here.
    private static final long OVERDUE_MS = 3 * INTERVAL_MS;

    private final DemotionManager demotion = mock(DemotionManager.class);
    private final StorageGarbageCollector gc = mock(StorageGarbageCollector.class);
    private final PromotionManager promotion = mock(PromotionManager.class);
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final BlockingQueue<OperatorWarning> raised = new LinkedBlockingQueue<>();
    private final DegradeReporter degrade = DegradeReporter.degradeReporter(StorageMaintenanceDriver.REPORT_SUBJECT);
    private final AtomicReference<Runnable> heldCycle = new AtomicReference<>();

    StorageMaintenanceDriverOverdueTest() {
        degrade.bind(OperatorWarningSink.handingOffTo(raised::add));
    }

    @Test
    void checkOverdue_noTickWithinThreeIntervals_raisesOnce_andTheNextTickRecovers() throws InterruptedException {
        var driver = driver(Runnable::run);

        driver.tick();
        clock.addAndGet(OVERDUE_MS);
        driver.checkOverdue();

        assertThat(degrade.isDegradedFor(OVERDUE, StorageMaintenanceDriver.PART_TICK)).as("exactly three intervals is not overdue")
                                                                                      .isFalse();

        clock.addAndGet(1);
        driver.checkOverdue();
        driver.checkOverdue();

        assertThat(degrade.isDegradedFor(OVERDUE, StorageMaintenanceDriver.PART_TICK)).isTrue();
        assertNext(OVERDUE, "storage-maintenance/tick");

        driver.tick();

        assertThat(degrade.isDegradedFor(OVERDUE, StorageMaintenanceDriver.PART_TICK)).isFalse();
        assertNext(COMPLETED, "storage-maintenance/tick");
        assertThat(raised.poll(200, TimeUnit.MILLISECONDS)).as("raised once, recovered once").isNull();
    }

    @Test
    void checkOverdue_neverTicked_countsFromTheDriversCreation() {
        var driver = driver(Runnable::run);

        clock.addAndGet(OVERDUE_MS);
        driver.checkOverdue();

        assertThat(degrade.isDegradedFor(OVERDUE, StorageMaintenanceDriver.PART_TICK)).as("three intervals since creation")
                                                                                      .isFalse();

        clock.addAndGet(1);
        driver.checkOverdue();

        assertThat(degrade.isDegradedFor(OVERDUE, StorageMaintenanceDriver.PART_TICK)).isTrue();
    }

    @Test
    void checkOverdue_promotionCycleRunningThreeIntervals_raises_andItsEndRecovers() throws InterruptedException {
        var driver = driver(heldCycle::set);

        driver.tick();
        clock.addAndGet(OVERDUE_MS + 1);
        driver.tick();
        driver.checkOverdue();

        assertThat(degrade.isDegradedFor(OVERDUE, StorageMaintenanceDriver.PART_PROMOTION)).isTrue();
        assertThat(degrade.isDegradedFor(OVERDUE, StorageMaintenanceDriver.PART_TICK)).as("ticks still complete")
                                                                                      .isFalse();
        assertNext(OVERDUE, "storage-maintenance/promotion");

        heldCycle.get().run();

        assertThat(degrade.isDegradedFor(OVERDUE, StorageMaintenanceDriver.PART_PROMOTION)).isFalse();
        assertNext(COMPLETED, "storage-maintenance/promotion");
    }

    @Test
    void checkOverdue_promotionCycleWithinThreeIntervals_isNotOverdue() {
        var driver = driver(heldCycle::set);

        driver.tick();
        clock.addAndGet(OVERDUE_MS);
        driver.tick();
        driver.checkOverdue();

        assertThat(degrade.isDegradedFor(OVERDUE, StorageMaintenanceDriver.PART_PROMOTION)).isFalse();
    }

    @Test
    void failingPromotion_failsTheTick() {
        var driver = driver(Runnable::run);

        doThrow(new IllegalStateException("source unreadable")).when(promotion).promote();
        driver.tick();

        assertThat(driver.progress().outcome()).isEqualTo(Outcome.FAILED);
        assertThat(driver.progress().consecutiveFailedTicks()).isEqualTo(1);
        assertThat(driver.progress().lastFailure().or("")).contains("source unreadable");
    }

    @Test
    void promotionThatCannotStart_failsTheTick() {
        var driver = driver(_ -> {
            throw new IllegalStateException("no thread");
        });

        driver.tick();

        assertThat(driver.progress().outcome()).isEqualTo(Outcome.FAILED);
        assertThat(driver.progress().consecutiveFailedTicks()).isEqualTo(1);
    }

    @Test
    void promotedBlocks_accumulate_andAHeldCycleCountsInTheTickAfterItEnds() {
        var driver = driver(heldCycle::set);

        doReturn(4).when(promotion).promote();
        driver.tick();

        assertThat(driver.progress().promoted()).as("the cycle has not ended").isZero();

        heldCycle.get().run();
        driver.tick();
        driver.tick();

        assertThat(driver.progress().promoted()).as("counted once").isEqualTo(4);
    }

    @Test
    void degradation_followsTheFailedStreakAndTheOverdueTick() {
        var driver = driver(Runnable::run);

        assertThat(driver.degradation().isEmpty()).isTrue();

        doThrow(new IllegalStateException("tier gone")).when(demotion).demote();
        driver.tick();

        assertThat(driver.degradation().or("")).contains("tier gone");

        doReturn(0).when(demotion).demote();
        driver.tick();

        assertThat(driver.degradation().isEmpty()).isTrue();

        clock.addAndGet(OVERDUE_MS + 1);

        assertThat(driver.degradation().or("")).contains("no maintenance tick completed");
    }

    @Test
    void degradation_overduePromotionCycle() {
        var driver = driver(heldCycle::set);

        driver.tick();
        clock.addAndGet(OVERDUE_MS + 1);
        driver.tick();

        assertThat(driver.degradation().or("")).contains("promotion cycle");
    }

    @Test
    void registerMeters_exposesTheStreakTheAgeAndTheLastOutcome() {
        var driver = driver(Runnable::run);
        var registry = new SimpleMeterRegistry();

        driver.registerMeters(registry);

        assertThat(outcomeGauge(registry, Outcome.NEVER_RUN)).isEqualTo(1.0);
        clock.addAndGet(7_000);
        assertThat(registry.get("aether_storage_maintenance_seconds_since_last_pass").gauge().value()).isEqualTo(7.0);

        doThrow(new IllegalStateException("tier gone")).when(demotion).demote();
        driver.tick();
        driver.tick();

        assertThat(registry.get("aether_storage_maintenance_consecutive_failed_ticks").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("aether_storage_maintenance_seconds_since_last_pass").gauge().value()).isZero();
        assertThat(outcomeGauge(registry, Outcome.FAILED)).isEqualTo(1.0);
        assertThat(outcomeGauge(registry, Outcome.OK)).isZero();
        assertThat(outcomeGauge(registry, Outcome.NEVER_RUN)).isZero();
    }

    private static double outcomeGauge(SimpleMeterRegistry registry, Outcome outcome) {
        return registry.get("aether_storage_maintenance_last_outcome")
                       .tag("outcome", outcome.name())
                       .gauge()
                       .value();
    }

    private void assertNext(OperatorWarningCode code, String subject) throws InterruptedException {
        var warning = raised.poll(5, TimeUnit.SECONDS);

        assertThat(warning).isNotNull();
        assertThat(warning.code()).isEqualTo(code);
        assertThat(warning.subject()).isEqualTo(subject);
    }

    private StorageMaintenanceDriver driver(Executor executor) {
        return StorageMaintenanceDriver.storageMaintenanceDriver(demotion,
                                                                  promotion,
                                                                  PromotionStallWatch.promotionStallWatch(Map.of(),
                                                                                                          OperatorWarningSink.logOnly()),
                                                                  gc,
                                                                  executor,
                                                                  degrade,
                                                                  () -> Unit.unit(),
                                                                  clock::get,
                                                                  TimeSpan.timeSpan(INTERVAL_MS).millis());
    }
}
