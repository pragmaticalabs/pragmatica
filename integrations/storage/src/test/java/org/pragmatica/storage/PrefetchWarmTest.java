package org.pragmatica.storage;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.storage.PrefetchConfig.prefetchConfig;
import static org.pragmatica.storage.PrefetchHint.prefetchHint;
import static org.pragmatica.storage.PrefetchManager.prefetchManager;

/// What the prefetch manager does with a hint now that a prefetch is [StorageInstance#warm]: it fetches, it never
/// runs more than [DefaultPrefetchManager#MAX_IN_FLIGHT] at once and gives a slot back however an attempt ends, and
/// its counters say what happened.
class PrefetchWarmTest {
    /// Deliberately not the default (4): the tests must be reading the configured cap, not a constant.
    private static final int CAP = 3;
    private static final int HINT_COUNT = CAP + 2;

    /// A storage instance whose `warm` stays pending until the test resolves it; everything else it refuses.
    private static final class PendingWarmStorage {
        final List<Promise<Boolean>> pending = new CopyOnWriteArrayList<>();
        final List<BlockId> warmed = new CopyOnWriteArrayList<>();
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final StorageInstance instance = (StorageInstance) Proxy.newProxyInstance(StorageInstance.class.getClassLoader(),
                                                                                  new Class<?>[]{StorageInstance.class},
                                                                                  (_, method, args) -> invoke(method.getName(), args));

        private Object invoke(String method, Object[] args) {
            return switch (method) {
                case "warm" -> startWarm((BlockId) args[0]);
                case "name" -> "pending-warm";
                default -> throw new UnsupportedOperationException(method);
            };
        }

        private Promise<Boolean> startWarm(BlockId id) {
            var promise = Promise.<Boolean> promise();

            warmed.add(id);
            pending.add(promise);
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            promise.onResultRun(running::decrementAndGet);

            return promise;
        }
    }

    private static BlockId blockOf(int index) {
        return BlockId.blockId(("prefetch-warm-" + index).getBytes(StandardCharsets.UTF_8)).unwrap();
    }

    private static PrefetchHint hintFor(int index) {
        return prefetchHint(blockOf(index).hexString(), 10);
    }

    private PendingWarmStorage storage;
    private PrefetchManager manager;

    @BeforeEach
    void setUp() {
        storage = new PendingWarmStorage();
        manager = prefetchManager(storage.instance, prefetchConfig(3, 5, 60_000, 300_000, 10_000, CAP));
        manager.activate();
    }

    @Nested
    class InFlightCap {
        @Test
        void processHints_aBurstOverManyRounds_neverHasMoreThanTheCapRunning() {
            var next = 0;

            for (var round = 0; round < 20; round++) {
                manager.processHints(IntStream.range(next, next + 15).mapToObj(PrefetchWarmTest::hintFor).toList());
                next += 15;
                // Some attempts end between rounds, so slots are given back and taken again all the way through.
                storage.pending.stream().filter(promise -> !promise.isResolved()).limit(2).forEach(promise -> promise.succeed(true));
            }

            assertThat(storage.peak.get()).as("peak attempts running at once").isLessThanOrEqualTo(CAP);
            assertThat(storage.peak.get()).as("control: the cap was actually reached").isEqualTo(CAP);
            assertThat(manager.stats().droppedInFlight()).as("control: the burst did overflow the cap").isPositive();
            assertThat(manager.stats().started() + manager.stats().droppedInFlight()).as("every hint is started or counted").isEqualTo(300);
        }

        @Test
        void prefetchConfig_withoutACap_allowsTheDefaultFour() {
            var defaulted = prefetchManager(storage.instance, prefetchConfig(3, 5, 60_000));

            defaulted.activate();
            defaulted.processHints(IntStream.range(100, 106).mapToObj(PrefetchWarmTest::hintFor).toList());

            assertThat(PrefetchConfig.DEFAULT_MAX_IN_FLIGHT).isEqualTo(4);
            assertThat(defaulted.stats().started()).isEqualTo(4);
            assertThat(defaulted.stats().droppedInFlight()).isEqualTo(2);
        }

        @Test
        void prefetchConfig_aCapBelowOne_isRaisedToOne_soPrefetchIsNeverSilentlyStarved() {
            assertThat(prefetchConfig(0).maxInFlight()).isEqualTo(1);
            assertThat(prefetchConfig(-7).maxInFlight()).isEqualTo(1);
            assertThat(prefetchConfig(9).maxInFlight()).isEqualTo(9);
        }

        @Test
        void processHints_pastTheCap_dropsTheExcessAndCountsIt_neverQueuesIt() {
            manager.processHints(IntStream.range(0, HINT_COUNT).mapToObj(PrefetchWarmTest::hintFor).toList());

            assertThat(storage.warmed).as("only the free slots start").hasSize(CAP);
            assertThat(manager.stats().started()).isEqualTo(CAP);
            assertThat(manager.stats().droppedInFlight()).isEqualTo(HINT_COUNT - CAP);

            // Nothing was queued: finishing every attempt starts nothing by itself.
            storage.pending.forEach(promise -> promise.succeed(true));

            assertThat(storage.warmed).hasSize(CAP);
        }

        @Test
        void processHints_afterAnAttemptSucceeds_itsSlotIsFreeAgain() {
            manager.processHints(IntStream.range(0, CAP).mapToObj(PrefetchWarmTest::hintFor).toList());
            manager.processHints(List.of(hintFor(HINT_COUNT)));

            assertThat(manager.stats().droppedInFlight()).as("control: the cap is full").isEqualTo(1);

            storage.pending.getFirst().succeed(true);
            manager.processHints(List.of(hintFor(HINT_COUNT + 1)));

            assertThat(storage.warmed).hasSize(CAP + 1);
            assertThat(manager.stats().droppedInFlight()).isEqualTo(1);
        }

        @Test
        void processHints_afterAnAttemptFails_itsSlotIsFreeAgain() {
            manager.processHints(IntStream.range(0, CAP).mapToObj(PrefetchWarmTest::hintFor).toList());

            Cause failure = Causes.cause("the shared tier is unreachable");

            storage.pending.getFirst().fail(failure);
            manager.processHints(List.of(hintFor(HINT_COUNT)));

            assertThat(storage.warmed).hasSize(CAP + 1);
            assertThat(manager.stats().warmed()).isZero();
        }

        @Test
        void processHints_aDroppedHint_isNotOnCooldown_soALaterRoundMayTryIt() {
            manager.processHints(IntStream.range(0, HINT_COUNT).mapToObj(PrefetchWarmTest::hintFor).toList());
            storage.pending.getFirst().succeed(false);

            var dropped = hintFor(HINT_COUNT - 1);

            manager.processHints(List.of(dropped));

            assertThat(storage.warmed).extracting(BlockId::hexString).contains(dropped.blockIdHex());
        }
    }

    @Nested
    class Counters {
        @Test
        void collectHints_countsTheHintsItHandsOut() {
            var id = blockOf(0);

            for (var i = 0; i < 4; i++) {
                manager.recordAccess(id);
            }

            assertThat(manager.collectHints()).hasSize(1);
            assertThat(manager.stats().hintsSent()).isEqualTo(1);
            assertThat(manager.stats().tracked()).as("a collected hint's counter is spent").isZero();
        }

        @Test
        void processHints_countsEveryHintReceived_andTheOnesItActsOn() {
            manager.processHints(List.of(hintFor(0), prefetchHint(blockOf(1).hexString(), 1)));

            var stats = manager.stats();

            assertThat(stats.hintsReceived()).isEqualTo(2);
            assertThat(stats.started()).as("the second is below the threshold").isEqualTo(1);
        }

        @Test
        void processHints_aBlockAttemptedRecently_isCountedAsOnCooldown() {
            manager.processHints(List.of(hintFor(0)));
            manager.processHints(List.of(hintFor(0)));

            assertThat(manager.stats().started()).isEqualTo(1);
            assertThat(manager.stats().droppedCooldown()).isEqualTo(1);
        }

        @Test
        void processHints_whileDormant_dropsThemAndCountsThem_startsNothing() {
            manager.deactivate();
            manager.processHints(List.of(hintFor(0), hintFor(1)));

            assertThat(storage.warmed).isEmpty();
            assertThat(manager.stats().droppedDormant()).isEqualTo(2);
            assertThat(manager.stats().hintsReceived()).as("received counts only what an awake manager acts on").isZero();
        }

        @Test
        void recordAccess_withTheObservationTableFull_countsTheUnobservedAccess() {
            var tiny = prefetchManager(storage.instance, PrefetchConfig.prefetchConfig(3, 5, 60_000, 300_000, 1));

            tiny.activate();
            tiny.recordAccess(blockOf(0));
            tiny.recordAccess(blockOf(1));

            assertThat(tiny.stats().droppedUntracked()).isEqualTo(1);
            assertThat(tiny.stats().tracked()).isEqualTo(1);
        }

        @Test
        void stats_reportActivity_andTheNoopReportsNothing() {
            assertThat(manager.stats().active()).isTrue();
            manager.deactivate();
            assertThat(manager.stats().active()).isFalse();
            assertThat(PrefetchManager.NOOP.stats()).isEqualTo(PrefetchStats.empty());
        }
    }
}
