// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.DemotionManager;
import org.pragmatica.storage.PromotionManager;
import org.pragmatica.storage.PromotionManager.PromotionStats;
import org.pragmatica.storage.StorageGarbageCollector;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// What `StorageMaintenanceDriver.tick()` guarantees about the promotion cycle it starts: its place between demotion
/// and GC, that it runs off the ticking thread, that at most one runs at a time, that a failing cycle does not stop
/// the next, and that the stall watch looks at what every cycle did.
class StorageMaintenanceDriverPromotionTest {
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final DemotionManager demotion = mock(DemotionManager.class);
    private final StorageGarbageCollector gc = mock(StorageGarbageCollector.class);
    private final PromotionManager promotion = mock(PromotionManager.class);

    @Test
    void tick_demotesThenPromotesThenCollectsGarbage() {
        recordCalls();

        driver(Runnable::run).tick();

        assertThat(calls).containsExactly("demote", "promote", "gc");
    }

    @Test
    void tick_whileThePreviousCycleIsStillRunning_startsNoSecondCycle() {
        var pending = new ArrayList<Runnable>();
        var driver = driver(pending::add);

        driver.tick();
        driver.tick();

        assertThat(pending).as("the second tick finds the first cycle unfinished and skips promotion").hasSize(1);

        pending.getFirst().run();
        driver.tick();

        assertThat(pending).as("once the cycle ended the next tick starts a new one").hasSize(2);
    }

    @Test
    void tick_afterACycleThatThrew_startsTheNextCycle() {
        var attempts = new AtomicInteger();

        when(promotion.promote()).thenAnswer(invocation -> failFirstTime(attempts));

        var driver = driver(Runnable::run);

        driver.tick();
        driver.tick();

        assertThat(attempts.get()).as("one failed cycle must not leave the guard set for good").isEqualTo(2);
    }

    /// A thread that will not start (`OutOfMemoryError`) or a refusing executor must not leave the skip-if-running guard set:
    /// the next tick still runs a cycle.
    @Test
    void tick_afterTheExecutorRefusedToStartACycle_theNextTickStillRunsOne() {
        var refusals = new AtomicInteger();
        var cycles = new AtomicInteger();

        when(promotion.promote()).thenAnswer(invocation -> cycles.incrementAndGet());

        var driver = driver(cycle -> refuseOnce(refusals, cycle));

        driver.tick();
        assertThat(cycles.get()).as("control: the refused tick ran no cycle").isZero();

        driver.tick();
        assertThat(cycles.get()).as("the guard was released, so the next tick ran a cycle").isEqualTo(1);
    }

    @Test
    void tick_afterACycleDiedWithAnErrorThatEscapesTheLift_theNextTickStillRunsOne() {
        var cycles = new AtomicInteger();

        when(promotion.promote()).thenAnswer(invocation -> dieOnTheFirstCycle(cycles));

        var driver = driver(StorageMaintenanceDriverPromotionTest::runAsADyingThread);

        driver.tick();
        driver.tick();

        assertThat(cycles.get()).as("a StackOverflowError is rethrown by Result.lift; the guard must still be released")
                                .isEqualTo(2);
    }

    @Test
    void tick_runsThePromotionCycleOnItsOwnDaemonThread() throws InterruptedException {
        var ran = new CountDownLatch(1);
        var thread = new AtomicReference<Thread>();

        when(promotion.promote()).thenAnswer(invocation -> {
            thread.set(Thread.currentThread());
            ran.countDown();
            return 0;
        });

        StorageMaintenanceDriver.storageMaintenanceDriver(demotion, promotion, noWatch(), gc).tick();

        assertThat(ran.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(thread.get()).isNotSameAs(Thread.currentThread());
        assertThat(thread.get().isDaemon()).as("a cycle in flight must not hold the JVM open").isTrue();
        assertThat(thread.get().getName()).isEqualTo("storage-promotion");
    }

    @Test
    void tick_hasTheStallWatchLookAfterEveryCycle() throws InterruptedException {
        var delivered = new CopyOnWriteArrayList<OperatorWarning>();
        var sink = OperatorWarningSink.handingOffTo(delivered::add);
        var stats = new AtomicReference<>(new PromotionStats(0, 0, 0, 0, 0));
        var failing = mock(PromotionManager.class);

        when(failing.stats()).thenAnswer(invocation -> stats.get());
        when(failing.promote()).thenAnswer(invocation -> failOneMoreCopy(stats));

        var setups = Map.of("artifacts",
                            StorageFactory.StorageSetup.storageSetup("artifacts",
                                                                     null,
                                                                     null,
                                                                     null,
                                                                     null,
                                                                     null,
                                                                     null,
                                                                     Option.empty(),
                                                                     failing));
        var driver = StorageMaintenanceDriver.storageMaintenanceDriver(demotion,
                                                                        failing,
                                                                        PromotionStallWatch.promotionStallWatch(setups, sink),
                                                                        gc,
                                                                        Runnable::run);

        for (var i = 0; i < PromotionStallWatch.STALLED_AFTER_CYCLES; i++) {
            driver.tick();
        }

        var deadline = System.currentTimeMillis() + 5_000;

        while (delivered.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertThat(delivered).extracting(OperatorWarning::code).containsExactly(OperatorWarningCode.STORAGE_PROMOTION_STALLED);
    }

    private StorageMaintenanceDriver driver(java.util.concurrent.Executor executor) {
        return StorageMaintenanceDriver.storageMaintenanceDriver(demotion, promotion, noWatch(), gc, executor);
    }

    private static PromotionStallWatch noWatch() {
        return PromotionStallWatch.promotionStallWatch(Map.of(), OperatorWarningSink.logOnly());
    }

    private void recordCalls() {
        when(demotion.demote()).thenAnswer(invocation -> record("demote"));
        when(promotion.promote()).thenAnswer(invocation -> record("promote"));
        when(gc.collectGarbage()).thenAnswer(invocation -> record("gc"));
    }

    private int record(String call) {
        calls.add(call);

        return 0;
    }

    private static void refuseOnce(AtomicInteger refusals, Runnable cycle) {
        if (refusals.getAndIncrement() == 0) {
            throw new OutOfMemoryError("unable to create native thread (simulated)");
        }

        cycle.run();
    }

    private static int dieOnTheFirstCycle(AtomicInteger cycles) {
        if (cycles.incrementAndGet() == 1) {
            throw new StackOverflowError("simulated");
        }

        return 0;
    }

    /// What a thread does with an `Error` nobody catches: the cycle ends there, and nothing after the `finally` runs.
    private static void runAsADyingThread(Runnable cycle) {
        try {
            cycle.run();
        } catch (Throwable ignored) {
            // the thread dies; the driver's guard is not told
        }
    }

    private static int failFirstTime(AtomicInteger attempts) {
        if (attempts.incrementAndGet() == 1) {
            throw new IllegalStateException("simulated promotion failure");
        }

        return 0;
    }

    private static int failOneMoreCopy(AtomicReference<PromotionStats> stats) {
        stats.updateAndGet(s -> new PromotionStats(s.blocksPromoted(), 0, s.failed() + 1, s.cycles() + 1, s.lastRunMs() + 1));

        return 0;
    }
}
