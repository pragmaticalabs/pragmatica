package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.storage.PrefetchConfig.prefetchConfig;
import static org.pragmatica.storage.PrefetchHint.prefetchHint;

/// #1325: the manager's bookkeeping is bounded. Observation counters live while they are touched and
/// are reclaimed after `observationWindowMs` idle; the table holds at most `maxTrackedBlocks`;
/// cooldown entries are reclaimed once elapsed, without a deactivation; and a ranking pass reads an
/// immutable snapshot with a deterministic tie order. Note: nothing outside tests constructs a
/// `PrefetchManager` today, so these bounds govern a manager that production does not yet wire.
class PrefetchBoundsTest {
    private static final long WINDOW_MS = 10_000L;
    private static final long COOLDOWN_MS = 5_000L;
    private static final int CAPACITY = 50;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private StorageInstance storage;
    private CountingTier durable;

    @BeforeEach
    void setUp() {
        durable = new CountingTier(MemoryTier.memoryTier(1024 * 1024, TierLevel.LOCAL_DISK));
        storage = StorageInstance.storageInstance("prefetch-bounds", List.of(MemoryTier.memoryTier(1024 * 1024), durable));
    }

    private DefaultPrefetchManager manager(int threshold) {
        var manager = new DefaultPrefetchManager(storage,
                                                 prefetchConfig(threshold, 5, COOLDOWN_MS, WINDOW_MS, CAPACITY),
                                                 clock::get);

        manager.activate();

        return manager;
    }

    private static BlockId blockIdOf(int n) {
        return BlockId.blockId(("prefetch-bounds-" + n).getBytes(StandardCharsets.UTF_8))
                      .fold(_ -> {
                                fail("BlockId creation failed");
                                return null;
                            },
                            id -> id);
    }

    @Test
    void recordAccess_streamOfDistinctOnceReadBlocks_neverExceedsTheTrackedCap() {
        var manager = manager(10);

        for (var n = 0; n < 5_000; n++) {
            manager.recordAccess(blockIdOf(n));
            clock.addAndGet(2L);
        }

        assertThat(manager.trackedBlocks()).isLessThanOrEqualTo(CAPACITY);
    }

    @Test
    void recordAccess_atTheCap_stillCountsABlockAlreadyTracked() {
        var manager = manager(3);
        var hot = blockIdOf(0);

        manager.recordAccess(hot);
        for (var n = 1; n < 1_000; n++) {
            manager.recordAccess(blockIdOf(n));
        }
        manager.recordAccess(hot);
        manager.recordAccess(hot);

        assertThat(manager.collectHints()).extracting(PrefetchHint::blockIdHex).containsExactly(hot.hexString());
    }

    @Test
    void recordAccess_atTheCap_admitsNewBlocksOnceIdleCountersAreReclaimed() {
        var manager = manager(1);

        for (var n = 0; n < CAPACITY; n++) {
            manager.recordAccess(blockIdOf(n));
        }
        clock.addAndGet(WINDOW_MS);
        manager.recordAccess(blockIdOf(10_000));

        assertThat(manager.trackedBlocks()).isEqualTo(1);
    }

    @Test
    void collectHints_reclaimsCountersIdleLongerThanTheWindow() {
        var manager = manager(10);

        manager.recordAccess(blockIdOf(1));
        manager.recordAccess(blockIdOf(2));
        clock.addAndGet(WINDOW_MS);
        manager.collectHints();

        assertThat(manager.trackedBlocks()).isZero();
    }

    @Test
    void collectHints_keepsACounterThatIsStillBeingTouched() {
        var manager = manager(3);
        var id = blockIdOf(1);

        manager.recordAccess(id);
        clock.addAndGet(WINDOW_MS - 1);
        manager.recordAccess(id);
        clock.addAndGet(WINDOW_MS - 1);
        manager.recordAccess(id);

        assertThat(manager.collectHints()).hasSize(1);
    }

    @Test
    void processHints_reclaimsAnElapsedCooldownWithoutDeactivation() {
        var manager = manager(1);
        var hint = prefetchHint(blockIdOf(1).hexString(), 10, TierLevel.MEMORY.name());

        manager.processHints(List.of(hint));
        assertThat(manager.cooldownEntries()).as("the attempt recorded a cooldown").isEqualTo(1);

        clock.addAndGet(COOLDOWN_MS);
        manager.collectHints();

        assertThat(manager.cooldownEntries()).isZero();
    }

    @Test
    void processHints_alsoReclaimsElapsedCooldowns() {
        var manager = manager(1);

        manager.processHints(List.of(prefetchHint(blockIdOf(1).hexString(), 10, TierLevel.MEMORY.name())));
        clock.addAndGet(COOLDOWN_MS);
        manager.processHints(List.of(prefetchHint(blockIdOf(2).hexString(), 10, TierLevel.MEMORY.name())));

        assertThat(manager.cooldownEntries()).as("only the fresh attempt's cooldown remains").isEqualTo(1);
    }

    @Test
    void recordAccess_atTheCapWithAllCountersFresh_scansAtMostOncePerReclaimInterval() {
        var manager = manager(10);

        for (var n = 0; n < 5_000; n++) {
            manager.recordAccess(blockIdOf(n));
        }

        assertThat(manager.reclaimPasses()).as("a full table of fresh counters must not be rescanned per access")
                                           .isLessThanOrEqualTo(1);
    }

    @Test
    void processHints_activeCooldownStillSuppressesARepeat_andElapsedOneAllowsIt() {
        var manager = manager(1);
        var hint = prefetchHint(blockIdOf(1).hexString(), 10, TierLevel.MEMORY.name());

        manager.processHints(List.of(hint));
        clock.addAndGet(COOLDOWN_MS - 1);
        manager.processHints(List.of(hint));
        assertThat(durable.gets.get()).as("within the cooldown the repeat is suppressed").isEqualTo(1);

        clock.addAndGet(1);
        manager.processHints(List.of(hint));
        assertThat(durable.gets.get()).as("once it has elapsed the block is attempted again").isEqualTo(2);
    }

    @Test
    void recordAccess_whileInactive_recordsNothing_evenOnceActivated() {
        var manager = manager(1);

        manager.deactivate();
        manager.recordAccess(blockIdOf(1));
        manager.activate();

        assertThat(manager.trackedBlocks()).as("a dormant manager must not accumulate observations").isZero();
    }

    @Test
    void deactivate_clearsTheCooldowns() {
        var manager = manager(1);

        manager.processHints(List.of(prefetchHint(blockIdOf(1).hexString(), 10, TierLevel.MEMORY.name())));
        assertThat(manager.cooldownEntries()).as("the attempt recorded a cooldown").isEqualTo(1);

        manager.deactivate();

        assertThat(manager.cooldownEntries()).isZero();
    }

    /// The reclaim at the start of `processHints` and the cooldown check read the clock separately. A clock that moves
    /// on between the two reads isolates the check itself: the entry survives the reclaim (one millisecond short of
    /// `cooldownMs`) and is exactly `cooldownMs` old when the check looks at it, which must allow the repeat.
    @Test
    void processHints_cooldownCheck_allowsARepeatAtExactlyTheCooldown() {
        var drift = new AtomicLong();
        var manager = new DefaultPrefetchManager(storage,
                                                 prefetchConfig(1, 5, COOLDOWN_MS, WINDOW_MS, CAPACITY),
                                                 () -> clock.getAndAdd(drift.get()));
        var hint = prefetchHint(blockIdOf(1).hexString(), 10, TierLevel.MEMORY.name());

        manager.activate();
        manager.processHints(List.of(hint));
        clock.addAndGet(COOLDOWN_MS - 1);
        drift.set(1);
        manager.processHints(List.of(hint));

        assertThat(durable.gets.get()).as("elapsed == cooldownMs allows the repeat").isEqualTo(2);
    }

    @Test
    void collectHints_tiesRankDeterministically_byBlockId() {
        var manager = manager(2);
        var ids = List.of(blockIdOf(1), blockIdOf(2), blockIdOf(3), blockIdOf(4), blockIdOf(5), blockIdOf(6), blockIdOf(7));

        ids.forEach(id -> {
            manager.recordAccess(id);
            manager.recordAccess(id);
        });

        var expected = ids.stream().map(BlockId::hexString).sorted().limit(5).toList();

        assertThat(manager.collectHints()).extracting(PrefetchHint::blockIdHex).containsExactlyElementsOf(expected);
    }

    @Test
    void collectHints_reportsTheCountsOfTheSnapshot_highestFirst() {
        var manager = manager(2);
        var a = blockIdOf(1);
        var b = blockIdOf(2);

        manager.recordAccess(a);
        manager.recordAccess(a);
        manager.recordAccess(b);
        manager.recordAccess(b);
        manager.recordAccess(b);

        assertThat(manager.collectHints()).extracting(PrefetchHint::blockIdHex, PrefetchHint::accessCount)
                                          .containsExactly(tuple(b.hexString(), 3),
                                                           tuple(a.hexString(), 2));
    }

    /// A tier that counts reads, over a delegate that holds nothing: every prefetch attempt reaches it once.
    private static final class CountingTier implements StorageTier {
        private final StorageTier delegate;
        final AtomicInteger gets = new AtomicInteger();

        CountingTier(StorageTier delegate) {
            this.delegate = delegate;
        }

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            gets.incrementAndGet();

            return delegate.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return delegate.put(id, content);
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return delegate.delete(id);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return delegate.exists(id);
        }

        @Override
        public TierLevel level() {
            return delegate.level();
        }

        @Override
        public long usedBytes() {
            return delegate.usedBytes();
        }

        @Override
        public long maxBytes() {
            return delegate.maxBytes();
        }
    }
}
