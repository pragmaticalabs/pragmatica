package org.pragmatica.storage;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private static final int HINT_COUNT = DefaultPrefetchManager.MAX_IN_FLIGHT + 2;

    /// A storage instance whose `warm` stays pending until the test resolves it; everything else it refuses.
    private static final class PendingWarmStorage {
        final List<Promise<Boolean>> pending = new CopyOnWriteArrayList<>();
        final List<BlockId> warmed = new CopyOnWriteArrayList<>();
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
        manager = prefetchManager(storage.instance, prefetchConfig(3, 5, 60_000));
        manager.activate();
    }

    @Nested
    class InFlightCap {
        @Test
        void processHints_pastTheCap_dropsTheExcessAndCountsIt_neverQueuesIt() {
            manager.processHints(IntStream.range(0, HINT_COUNT).mapToObj(PrefetchWarmTest::hintFor).toList());

            assertThat(storage.warmed).as("only the free slots start").hasSize(DefaultPrefetchManager.MAX_IN_FLIGHT);
            assertThat(manager.stats().started()).isEqualTo(DefaultPrefetchManager.MAX_IN_FLIGHT);
            assertThat(manager.stats().droppedInFlight()).isEqualTo(HINT_COUNT - DefaultPrefetchManager.MAX_IN_FLIGHT);

            // Nothing was queued: finishing every attempt starts nothing by itself.
            storage.pending.forEach(promise -> promise.succeed(true));

            assertThat(storage.warmed).hasSize(DefaultPrefetchManager.MAX_IN_FLIGHT);
        }

        @Test
        void processHints_afterAnAttemptSucceeds_itsSlotIsFreeAgain() {
            manager.processHints(IntStream.range(0, DefaultPrefetchManager.MAX_IN_FLIGHT).mapToObj(PrefetchWarmTest::hintFor).toList());
            manager.processHints(List.of(hintFor(HINT_COUNT)));

            assertThat(manager.stats().droppedInFlight()).as("control: the cap is full").isEqualTo(1);

            storage.pending.getFirst().succeed(true);
            manager.processHints(List.of(hintFor(HINT_COUNT + 1)));

            assertThat(storage.warmed).hasSize(DefaultPrefetchManager.MAX_IN_FLIGHT + 1);
            assertThat(manager.stats().droppedInFlight()).isEqualTo(1);
        }

        @Test
        void processHints_afterAnAttemptFails_itsSlotIsFreeAgain() {
            manager.processHints(IntStream.range(0, DefaultPrefetchManager.MAX_IN_FLIGHT).mapToObj(PrefetchWarmTest::hintFor).toList());

            Cause failure = Causes.cause("the shared tier is unreachable");

            storage.pending.getFirst().fail(failure);
            manager.processHints(List.of(hintFor(HINT_COUNT)));

            assertThat(storage.warmed).hasSize(DefaultPrefetchManager.MAX_IN_FLIGHT + 1);
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
