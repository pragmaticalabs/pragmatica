package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.storage.GarbageCollectorConfig.garbageCollectorConfig;
import static org.pragmatica.storage.StorageGarbageCollector.storageGarbageCollector;
import static org.pragmatica.lang.Unit.unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #801: GC scans lifecycle records, then deletes -- two steps with nothing held across them. A
/// deduplicating `put` for the same content that lands in the gap increments the scanned orphan's
/// refCount and hands its caller the id, and the delete then removes the bytes (and the record)
/// out from under that id. Each test fixes the interleaving with a seam that runs the racing put
/// INLINE at one named point of the collector's path, so the outcome is a function of the code,
/// not of scheduling. The signature asserted is the contract `put` breaks: an id it returned
/// must be readable.
class StorageGarbageCollectorClaimRaceTest {
    private static final long MEMORY_MAX = 1024 * 1024;
    private static final long GRACE_PERIOD_MS = 1000;
    private static final int BATCH_SIZE = 500;
    private static final byte[] CONTENT = "gc-claim-race".getBytes(StandardCharsets.UTF_8);
    /// Bounds every wait on the racing put: a claimant left chained behind a collection that never
    /// releases it must fail the test, not hang it.
    private static final TimeSpan WAIT = TimeSpan.timeSpan(5).seconds();

    private SeamTier tier;
    private SeamMetadataStore metadataStore;
    private StorageInstance instance;
    private StorageGarbageCollector gc;
    private final AtomicReference<Promise<BlockId>> racingPut = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        tier = new SeamTier(MemoryTier.memoryTier(MEMORY_MAX, TierLevel.MEMORY));
        metadataStore = new SeamMetadataStore(MetadataStore.inMemoryMetadataStore("gc-race"));
        instance = StorageInstance.storageInstance("gc-race", List.of(tier), metadataStore);
        gc = storageGarbageCollector(instance, metadataStore, garbageCollectorConfig(GRACE_PERIOD_MS, BATCH_SIZE));
        gc.activate();
    }

    private BlockId storeOrphanPastGrace() {
        var id = instance.put(CONTENT).await().onFailure(c -> fail("put failed: " + c.message())).unwrap();
        var expired = System.currentTimeMillis() - GRACE_PERIOD_MS - 100;

        metadataStore.computeLifecycle(id,
                                       lc -> new BlockLifecycle(lc.blockId(),
                                                                lc.presentIn(),
                                                                0,
                                                                expired,
                                                                lc.createdAt(),
                                                                lc.accessCount(),
                                                                expired));

        return id;
    }

    private void startRacingPut() {
        racingPut.set(instance.put(CONTENT));
    }

    private BlockId racedId() {
        return racingPut.get()
                        .await(WAIT)
                        .onFailure(c -> fail("racing put did not complete: " + c.message()))
                        .unwrap();
    }

    private void assertReadable(BlockId id, String when) {
        instance.get(id)
                .await()
                .onFailure(c -> fail("get failed: " + c.message()))
                .onSuccess(opt -> {
                               assertThat(opt.isPresent()).as("the id put handed out must be readable -- GC deleted the block under it (" + when
                                                             + ")")
                                         .isTrue();
                               opt.onPresent(bytes -> assertThat(bytes).isEqualTo(CONTENT));
                           });
    }

    /// Window 1: the put deduplicates after the scan and before any delete. The block is live
    /// (refCount 1) when GC deletes it, and the cycle reports it collected.
    @Test
    void put_deduplicatingBetweenScanAndDelete_keepsBlockReadableAndUncollected() {
        var id = storeOrphanPastGrace();

        metadataStore.afterScan(this::startRacingPut);
        var collected = gc.collectGarbage();

        assertThat(racedId()).isEqualTo(id);
        assertReadable(id, "dedup between scan and delete");
        assertThat(metadataStore.containsBlock(id)).as("the record of a block a caller holds must survive the cycle")
                  .isTrue();
        assertThat(metadataStore.getLifecycle(id).map(BlockLifecycle::refCount).or(-1)).as("the deduplicating put's credit must survive the cycle")
                  .isEqualTo(1);
        assertThat(collected).as("a block that was live at delete time was not collected, and the cycle must not count it")
                  .isZero();
    }

    /// Window 2: the put arrives once GC is already deleting from tiers. Before the fix it
    /// deduplicates onto the record GC removes a moment later; after it, the record is already
    /// gone, so the put claims the id afresh -- and must not write until GC's tier delete has
    /// finished, or that delete wipes the fresh bytes.
    @Test
    void put_arrivingDuringTierDelete_writesAfterTheDeleteAndStaysReadable() {
        var id = storeOrphanPastGrace();

        tier.beforeDelete(this::startRacingPut);
        var collected = gc.collectGarbage();

        assertThat(racedId()).isEqualTo(id);
        assertReadable(id, "put during tier delete");
        assertThat(metadataStore.containsBlock(id)).as("the record of a block a caller holds must survive the cycle")
                  .isTrue();
        assertThat(collected).as("the scanned orphan WAS collected; the caller holds a fresh write of the same content")
                  .isEqualTo(1);
        assertThat(tier.putCount()).as("the fresh write must land in the tier (initial put + the racing put's re-write)")
                  .isEqualTo(2);
    }

    /// Window 3: the put's claim fails against the orphan record, and GC takes the record and the
    /// bytes before the put credits it. The credit finds no record, so the put must not hand out
    /// the id on the strength of nothing -- it goes round again and writes afresh.
    @Test
    void put_whoseClaimLostToTheCollector_retriesInsteadOfReturningACollectedId() {
        var id = storeOrphanPastGrace();

        metadataStore.afterFailedClaim(() -> gc.collectGarbage());
        var racedId = instance.put(CONTENT).await().onFailure(c -> fail("put failed: " + c.message())).unwrap();

        assertThat(racedId).isEqualTo(id);
        assertReadable(id, "collector ran between failed claim and credit");
        assertThat(metadataStore.containsBlock(id)).as("the record of a block a caller holds must exist").isTrue();
    }

    /// A tier delete that fails must leave the scanned record in place for the next cycle, as it
    /// did when the record was removed only after a successful delete -- taking the record first
    /// (#801) must not turn a transient tier failure into a leaked, unrecorded block.
    @Test
    void tierDeleteFailure_keepsRecordForNextCycle() {
        var id = storeOrphanPastGrace();

        tier.failNextDelete();
        var collected = gc.collectGarbage();

        assertThat(collected).isZero();
        assertThat(metadataStore.containsBlock(id)).as("a failed tier delete must leave the record for the next cycle to retry")
                  .isTrue();
        assertThat(metadataStore.getLifecycle(id).map(BlockLifecycle::isOrphaned).or(false)).as("the retained record is still the orphan the next cycle will scan")
                  .isTrue();
        assertThat(gc.collectGarbage()).as("the next cycle collects it").isEqualTo(1);
    }

    /// A claimant chained behind a collection whose tier delete FAILS (rev1411 P1). Two properties
    /// the no-claimant test above cannot see: the restore must be conditional -- an unconditional
    /// put of the scanned orphan would overwrite the claimant's sentinel, its `trackNewBlock` would
    /// then decorate an orphan record and the next cycle would collect a block the caller holds --
    /// and the claimant must be released on the failure path too, or every later put of that
    /// content chains behind a promise that never resolves.
    @Test
    void claimantChainedBehindFailedTierDelete_keepsItsRecordAndIsReleased() {
        var id = storeOrphanPastGrace();

        tier.beforeDelete(this::startRacingPut);
        tier.failNextDelete();
        var collected = gc.collectGarbage();

        assertThat(racedId()).isEqualTo(id);
        assertReadable(id, "claimant behind a failed tier delete");
        assertThat(collected).as("a failed delete is not a collection").isZero();
        assertThat(metadataStore.getLifecycle(id).map(BlockLifecycle::refCount).or(-1)).as("the claimant's record (refCount 1) must survive the restore -- an orphan here means the restore overwrote a live claim")
                  .isEqualTo(1);
        assertThat(gc.collectGarbage()).as("the next cycle must not collect a block a caller holds").isZero();
        assertReadable(id, "after the next cycle");
    }

    /// The restore after a failed tier delete must happen INSIDE the resolution of that failure,
    /// before the collection's own promise resolves (rev1411 M3/P4). Shipped tiers fail
    /// asynchronously (`LocalDiskTier.delete` is lifted), and an `onFailure` registered on an
    /// unresolved promise runs on the executor AFTER the fold that resolves the caller's promise, so
    /// the record was absent for a window after `collectGarbage()` returned (19/200 measured). The
    /// seam holds the delete open and the TEST thread fails it, so "inside the resolution" has an
    /// exact observable: the restore ran on this thread, before `fail` returned.
    @Test
    void asyncTierDeleteFailure_restoresRecordBeforeTheCollectionResolves() {
        var id = storeOrphanPastGrace();
        var heldDelete = tier.holdNextDelete();
        var presentOnReturn = new AtomicBoolean();
        var collected = new AtomicInteger(-1);
        var collector = Promise.<Unit> promise(returned -> {
            collected.set(gc.collectGarbage());
            presentOnReturn.set(metadataStore.containsBlock(id));
            returned.succeed(unit());
        });

        assertThat(tier.awaitDeleteRequested(WAIT)).as("the collector must reach the tier delete").isTrue();
        assertThat(metadataStore.containsBlock(id)).as("the record was taken before the tier delete").isFalse();
        heldDelete.fail(StorageError.WriteError.writeError("induced async tier delete failure"));
        assertThat(metadataStore.restoreThread()).as("the restore must run inside the failed delete's resolution, on the resolving thread, before the collection's promise resolves -- not as an executor-dispatched onFailure")
                  .isSameAs(Thread.currentThread());
        assertThat(collector.await(WAIT).isSuccess()).as("the collector must return").isTrue();
        assertThat(collected.get()).isZero();
        assertThat(presentOnReturn.get()).as("the record must be back the instant collectGarbage() returns").isTrue();
        assertThat(metadataStore.getLifecycle(id).map(BlockLifecycle::isOrphaned).or(false)).as("the restored record is the orphan the next cycle will scan")
                  .isTrue();
    }

    /// Delegating tier with one seam: a hook run inline immediately BEFORE the backing delete, once.
    private static final class SeamTier implements StorageTier {
        private final StorageTier backing;
        private final AtomicReference<Runnable> beforeDelete = new AtomicReference<>();
        private final AtomicInteger putCount = new AtomicInteger();
        private final AtomicReference<Promise<Unit>> heldDelete = new AtomicReference<>();
        private final Promise<Unit> deleteRequested = Promise.promise();
        private volatile boolean failNextDelete;

        SeamTier(StorageTier backing) {
            this.backing = backing;
        }

        void beforeDelete(Runnable hook) {
            beforeDelete.set(hook);
        }

        void failNextDelete() {
            failNextDelete = true;
        }

        /// The next delete returns this unresolved promise and resolves [#awaitDeleteRequested];
        /// the test resolves the returned one from its own thread.
        Promise<Unit> holdNextDelete() {
            var held = Promise.<Unit> promise();

            heldDelete.set(held);

            return held;
        }

        boolean awaitDeleteRequested(TimeSpan timeout) {
            return deleteRequested.await(timeout)
                                  .isSuccess();
        }

        int putCount() {
            return putCount.get();
        }

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return backing.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            putCount.incrementAndGet();

            return backing.put(id, content);
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            Option.option(beforeDelete.getAndSet(null)).onPresent(Runnable::run);
            if (failNextDelete) {
                failNextDelete = false;

                return StorageError.WriteError.writeError("induced tier delete failure").promise();
            }

            var held = heldDelete.getAndSet(null);

            if (held != null) {
                deleteRequested.succeed(unit());

                return held;
            }

            return backing.delete(id);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return backing.exists(id);
        }

        @Override
        public TierLevel level() {
            return backing.level();
        }

        @Override
        public long usedBytes() {
            return backing.usedBytes();
        }

        @Override
        public long maxBytes() {
            return backing.maxBytes();
        }
    }

    /// Delegating store with two seams, each run inline once: after [#listAllLifecycles] returns
    /// its snapshot (GC's scan), and after a [#claimBlock] that lost (put's dedup branch).
    private static final class SeamMetadataStore implements MetadataStore {
        private final MetadataStore delegate;
        private final AtomicReference<Runnable> afterScan = new AtomicReference<>();
        private final AtomicReference<Runnable> afterFailedClaim = new AtomicReference<>();
        private final AtomicReference<Thread> restoreThread = new AtomicReference<>();

        SeamMetadataStore(MetadataStore delegate) {
            this.delegate = delegate;
        }

        void afterScan(Runnable hook) {
            afterScan.set(hook);
        }

        void afterFailedClaim(Runnable hook) {
            afterFailedClaim.set(hook);
        }

        @Override
        public List<BlockLifecycle> listAllLifecycles() {
            var snapshot = delegate.listAllLifecycles();

            Option.option(afterScan.getAndSet(null)).onPresent(Runnable::run);

            return snapshot;
        }

        /// The thread that re-claimed an ORPHAN record (GC's restore of the scanned record after a
        /// failed delete); a put's own sentinel has refCount 1 and never matches. Null until it ran.
        Thread restoreThread() {
            return restoreThread.get();
        }

        @Override
        public boolean claimBlock(BlockId blockId, BlockLifecycle sentinel) {
            var claimed = delegate.claimBlock(blockId, sentinel);

            if (sentinel.isOrphaned()) {
                restoreThread.set(Thread.currentThread());
            }

            if (!claimed) {
                Option.option(afterFailedClaim.getAndSet(null)).onPresent(Runnable::run);
            }

            return claimed;
        }

        @Override
        public Option<BlockLifecycle> getLifecycle(BlockId blockId) {
            return delegate.getLifecycle(blockId);
        }

        @Override
        @Contract
        public void createLifecycle(BlockLifecycle lifecycle) {
            delegate.createLifecycle(lifecycle);
        }

        @Override
        public boolean releaseClaim(BlockId blockId, BlockLifecycle sentinel) {
            return delegate.releaseClaim(blockId, sentinel);
        }

        @Override
        public Option<BlockLifecycle> computeLifecycle(BlockId blockId, UnaryOperator<BlockLifecycle> updater) {
            return delegate.computeLifecycle(blockId, updater);
        }

        @Override
        @Contract
        public void removeLifecycle(BlockId blockId) {
            delegate.removeLifecycle(blockId);
        }

        @Override
        @Contract
        public void putRef(String refName, BlockId blockId) {
            delegate.putRef(refName, blockId);
        }

        @Override
        public Option<BlockId> resolveRef(String refName) {
            return delegate.resolveRef(refName);
        }

        @Override
        public Option<BlockId> removeRef(String refName) {
            return delegate.removeRef(refName);
        }

        @Override
        public Option<BlockId> replaceRef(String refName, BlockId blockId) {
            return delegate.replaceRef(refName, blockId);
        }

        @Override
        public boolean containsBlock(BlockId blockId) {
            return delegate.containsBlock(blockId);
        }

        @Override
        public String instanceName() {
            return delegate.instanceName();
        }

        @Override
        public List<BlockLifecycle> listBlocksByTier(TierLevel tierLevel) {
            return delegate.listBlocksByTier(tierLevel);
        }

        @Override
        public Map<String, BlockId> listAllRefs() {
            return delegate.listAllRefs();
        }

        @Override
        public long currentEpoch() {
            return delegate.currentEpoch();
        }

        @Override
        @Contract
        public void restoreLifecycles(List<BlockLifecycle> entries) {
            delegate.restoreLifecycles(entries);
        }

        @Override
        @Contract
        public void restoreRefs(Map<String, BlockId> refs) {
            delegate.restoreRefs(refs);
        }

        @Override
        @Contract
        public void restoreEpoch(long epoch) {
            delegate.restoreEpoch(epoch);
        }
    }
}
