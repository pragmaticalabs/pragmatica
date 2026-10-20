// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.PromotionManager;
import org.pragmatica.storage.PromotionManager.PromotionStats;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// The stall watch raises `storage-promotion-stalled` once after three consecutive cycles that failed and copied
/// nothing, names the instance and the way out, and raises `storage-promotion-resumed` when a cycle stops being
/// stalled. The hand-off sink delivers on its own thread and in order, so a sentinel raised last marks the point by
/// which every earlier event has arrived.
class PromotionStallWatchTest {
    private static final String INSTANCE = "artifacts";
    private static final long TIMEOUT_MS = 5_000;

    private final List<OperatorWarning> delivered = new CopyOnWriteArrayList<>();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(delivered::add);
    private final StubPromotion promotion = new StubPromotion();
    private final AtomicInteger sentinels = new AtomicInteger();

    @Test
    void observe_threeStalledCycles_raiseTheStalledEventOnce() {
        var watch = watchOver(promotion);

        promotion.cycle(0, 2);
        watch.observe();
        promotion.cycle(0, 1);
        watch.observe();

        assertThat(events()).as("two stalled cycles are not yet a stall").isEmpty();

        promotion.cycle(0, 1);
        watch.observe();
        promotion.cycle(0, 1);
        watch.observe();

        var events = events();

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().code()).isEqualTo(OperatorWarningCode.STORAGE_PROMOTION_STALLED);
        assertThat(events.getFirst().code().code()).as("the kebab string is the operator-facing contract")
                                                   .isEqualTo("storage-promotion-stalled");
        assertThat(events.getFirst().code().subsystem()).isEqualTo("storage");
        assertThat(events.getFirst().code().level()).isEqualTo(org.pragmatica.utility.warning.WarningLevel.WARNING);
        assertThat(events.getFirst().subject()).isEqualTo(INSTANCE);
        assertThat(events.getFirst().message()).contains("[storage.artifacts] memory_max_bytes or disk_max_bytes")
                                               .contains("[storage.artifacts] promotion = false");
    }

    @Test
    void observe_acycleThatCopiedABlock_endsTheStallWithTheResumedEvent() {
        var watch = watchOver(promotion);

        stallFor(watch, PromotionStallWatch.STALLED_AFTER_CYCLES);
        promotion.cycle(1, 1);
        watch.observe();

        var events = events();

        assertThat(events).extracting(OperatorWarning::code)
                          .containsExactly(OperatorWarningCode.STORAGE_PROMOTION_STALLED,
                                           OperatorWarningCode.STORAGE_PROMOTION_RESUMED);
        assertThat(events.getLast().code().code()).isEqualTo("storage-promotion-resumed");
        assertThat(events.getLast().code().level()).isEqualTo(org.pragmatica.utility.warning.WarningLevel.INFO);
        assertThat(events.getLast().code().recoveryOf()).isEqualTo(Option.some(OperatorWarningCode.STORAGE_PROMOTION_STALLED));
    }

    @Test
    void observe_anIdleCycle_endsTheStallToo() {
        var watch = watchOver(promotion);

        stallFor(watch, PromotionStallWatch.STALLED_AFTER_CYCLES);
        promotion.cycle(0, 0);
        watch.observe();

        assertThat(events()).extracting(OperatorWarning::code)
                            .containsExactly(OperatorWarningCode.STORAGE_PROMOTION_STALLED,
                                             OperatorWarningCode.STORAGE_PROMOTION_RESUMED);
    }

    @Test
    void observe_aSuccessInTheMiddle_resetsTheStreak() {
        var watch = watchOver(promotion);

        stallFor(watch, PromotionStallWatch.STALLED_AFTER_CYCLES - 1);
        promotion.cycle(1, 1);
        watch.observe();
        stallFor(watch, PromotionStallWatch.STALLED_AFTER_CYCLES - 1);

        assertThat(events()).as("the streak restarts after a cycle that copied a block").isEmpty();
    }

    @Test
    void observe_aLookWithNoNewCycle_keepsTheStreak() {
        var watch = watchOver(promotion);

        stallFor(watch, PromotionStallWatch.STALLED_AFTER_CYCLES - 1);
        watch.observe();
        promotion.cycle(0, 1);
        watch.observe();

        assertThat(events()).as("a skipped tick runs no cycle: it neither adds to the streak nor resets it")
                            .extracting(OperatorWarning::code)
                            .containsExactly(OperatorWarningCode.STORAGE_PROMOTION_STALLED);
    }

    private void stallFor(PromotionStallWatch watch, int cycles) {
        for (var i = 0; i < cycles; i++) {
            promotion.cycle(0, 1);
            watch.observe();
        }
    }

    private PromotionStallWatch watchOver(PromotionManager manager) {
        return PromotionStallWatch.promotionStallWatch(setups(Map.of(INSTANCE, manager)), sink);
    }

    private static Map<String, StorageFactory.StorageSetup> setups(Map<String, PromotionManager> managers) {
        var result = new java.util.HashMap<String, StorageFactory.StorageSetup>();

        managers.forEach((name, manager) -> result.put(name,
                                                       StorageFactory.StorageSetup.storageSetup(name,
                                                                                                null,
                                                                                                null,
                                                                                                null,
                                                                                                null,
                                                                                                null,
                                                                                                null,
                                                                                                Option.empty(),
                                                                                                manager)));

        return result;
    }

    /// Everything delivered before this call's sentinel, which is raised last and so arrives last. Each call raises a
    /// sentinel of its own: an earlier call's would satisfy the wait before this call's events had arrived.
    private List<OperatorWarning> events() {
        var sentinel = "sentinel-" + sentinels.incrementAndGet();

        OperatorWarnings.raise(LoggerFactory.getLogger(PromotionStallWatchTest.class),
                               sink,
                               OperatorWarningCode.SWIM_KILL_GATE_HELD,
                               sentinel,
                               "marks the end of this test's events");

        var deadline = System.currentTimeMillis() + TIMEOUT_MS;

        while (delivered.stream().noneMatch(w -> sentinel.equals(w.subject())) && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(delivered).as("the sentinel must arrive, or the events below are not complete")
                             .anyMatch(w -> sentinel.equals(w.subject()));

        return delivered.stream().filter(w -> !w.subject().startsWith("sentinel-")).toList();
    }

    /// A promotion manager whose stats the test advances one cycle at a time.
    private static final class StubPromotion implements PromotionManager {
        private final AtomicReference<PromotionStats> stats = new AtomicReference<>(new PromotionStats(0, 0, 0, 0, 0));

        void cycle(int promoted, int failed) {
            stats.updateAndGet(s -> new PromotionStats(s.blocksPromoted() + promoted,
                                                       s.bytesMoved(),
                                                       s.failed() + failed,
                                                       s.cycles() + 1,
                                                       s.lastRunMs() + 1));
        }

        @Override
        public int promote() {
            return 0;
        }

        @Override
        public PromotionStats stats() {
            return stats.get();
        }

        @Override
        public Result<Unit> activate() {
            return Result.success(unit());
        }

        @Override
        public Result<Unit> deactivate() {
            return Result.success(unit());
        }

        @Override
        public boolean isActive() {
            return true;
        }
    }
}
