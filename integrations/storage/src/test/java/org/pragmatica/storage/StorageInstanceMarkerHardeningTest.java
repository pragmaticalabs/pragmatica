package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The edges of the cache-only marker: a failed put over a warmed block, the marker bound as a hard bound (warm and
/// a restored snapshot count too), what `exists` means, and what `createRef` refuses.
class StorageInstanceMarkerHardeningTest {
    private static final long MEMORY_MAX = 10_000;

    /// A tier whose writes can be made to fail; durable (the disk) or not (a cache).
    private static final class FlakyDurableTier implements StorageTier {
        private final TierLevel level;
        private final boolean durable;
        private final MemoryTier backing;
        final AtomicBoolean failPuts = new AtomicBoolean();

        FlakyDurableTier() {
            this(TierLevel.LOCAL_DISK, true);
        }

        FlakyDurableTier(TierLevel level, boolean durable) {
            this.level = level;
            this.durable = durable;
            this.backing = MemoryTier.memoryTier(1_000_000, level);
        }

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return backing.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return failPuts.get()
                   ? StorageError.WriteError.writeError("disk refused").promise()
                   : backing.put(id, content);
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return backing.delete(id);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return backing.exists(id);
        }

        @Override
        public TierLevel level() {
            return level;
        }

        @Override
        public long usedBytes() {
            return backing.usedBytes();
        }

        @Override
        public long maxBytes() {
            return backing.maxBytes();
        }

        @Override
        public boolean isDurable() {
            return durable;
        }
    }

    private SharedTestTier shared;
    private StorageInstance writer;
    private MemoryTier memory;
    private FlakyDurableTier disk;
    private MetadataStore store;
    private StorageInstance reader;

    @BeforeEach
    void setUp() {
        shared = new SharedTestTier();
        writer = StorageInstance.storageInstance("writer",
                                                 List.of(MemoryTier.memoryTier(MEMORY_MAX), shared),
                                                 MetadataStore.inMemoryMetadataStore("writer"));
        memory = MemoryTier.memoryTier(MEMORY_MAX, TierLevel.MEMORY);
        disk = new FlakyDurableTier();
        store = MetadataStore.inMemoryMetadataStore("reader");
        reader = StorageInstance.storageInstance("reader", List.of(memory, disk, shared), store);
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private BlockId writeElsewhere(String content) {
        return writer.put(bytes(content)).await().unwrap();
    }

    private StorageInstance boundedReader(MemoryTier cache, int cap, MetadataStore metadata) {
        return boundedReader(cache, cap, metadata, System::currentTimeMillis);
    }

    /// With `() -> 0L` the marker sweep never runs (it needs the clock to be a full interval past its start), so a
    /// slot that leaked stays leaked instead of being recounted away by the first sweep.
    private StorageInstance boundedReader(MemoryTier cache, int cap, MetadataStore metadata, LongSupplier clock) {
        return new DefaultStorageInstance("bounded",
                                          List.of(cache, shared),
                                          metadata,
                                          WritePolicy.WRITE_THROUGH,
                                          Option.none(),
                                          AppendLog.TornTailSink.logOnly(),
                                          _ -> {},
                                          DefaultStorageInstance.CacheMarkerLimits.cacheMarkerLimits(cap, 600_000L, 60_000L),
                                          clock);
    }

    private static long markersIn(MetadataStore metadata) {
        return metadata.listAllLifecycles().stream().filter(BlockLifecycle::cacheOnly).count();
    }

    @Test
    void put_overAWarmedBlock_whoseRequiredWriteFails_keepsTheCacheCopyTracked() {
        var id = writeElsewhere("warmed-then-put");

        assertThat(reader.warm(id).await().unwrap()).isTrue();
        disk.failPuts.set(true);

        assertThat(reader.put(bytes("warmed-then-put")).await().isFailure()).as("control: the put really failed").isTrue();

        var record = store.getLifecycle(id).unwrap();

        assertThat(record.cacheOnly()).as("the failed put put the marker back").isTrue();
        assertThat(record.presentIn()).contains(TierLevel.MEMORY).doesNotContain(TierLevel.LOCAL_DISK);
        assertThat(memory.exists(id).await().unwrap()).isTrue();
        assertThat(store.listBlocksByTier(TierLevel.MEMORY)).extracting(BlockLifecycle::blockId).containsExactly(id);
    }

    @Test
    void put_ofABlockNoCacheTierHolds_whoseRequiredWriteFails_leavesNoRecord() {
        disk.failPuts.set(true);

        var content = bytes("never-held-here");

        assertThat(reader.put(content).await().isFailure()).isTrue();
        assertThat(store.listAllLifecycles()).isEmpty();
    }

    /// The shared tier holds every block ever written, so it can never count as "a cache copy survived": a failed put
    /// whose only surviving copies are the shared one must leave no record at all, not an empty marker.
    @Test
    void put_whoseRequiredWriteFails_withTheCacheCopyGone_andTheBlockOnlyOnTheSharedTier_removesTheRecord() {
        var id = writeElsewhere("only-shared-remains");

        assertThat(reader.warm(id).await().unwrap()).isTrue();
        memory.delete(id).await().unwrap();
        disk.failPuts.set(true);

        assertThat(reader.put(bytes("only-shared-remains")).await().isFailure()).as("control: the put really failed").isTrue();
        assertThat(shared.exists(id).await().unwrap()).as("control: the shared tier still holds it").isTrue();
        assertThat(store.getLifecycle(id).isPresent()).as("no empty marker").isFalse();
    }

    /// A durable tier is not a cache either: a copy there is the node's own data, never what a marker tracks.
    @Test
    void put_whoseRequiredWriteFails_withACopyOnlyOnADurableTier_removesTheRecord() {
        var id = writeElsewhere("durable-copy-only");
        var content = bytes("durable-copy-only");

        assertThat(reader.warm(id).await().unwrap()).isTrue();
        memory.delete(id).await().unwrap();
        disk.put(id, content).await().unwrap();
        disk.failPuts.set(true);

        assertThat(reader.put(content).await().isFailure()).as("control: the put really failed").isTrue();
        assertThat(disk.exists(id).await().unwrap()).as("control: the durable copy is there").isTrue();
        assertThat(store.getLifecycle(id).isPresent()).as("a durable copy does not keep a cache marker").isFalse();
    }

    /// A marker that names a durable level (a restored snapshot can say anything) is put back naming only the cache
    /// levels: the durable level is dropped.
    @Test
    void put_whoseRequiredWriteFails_putsTheMarkerBackWithoutTheDurableLevel() {
        var id = writeElsewhere("marker-names-a-durable-level");
        var content = bytes("marker-names-a-durable-level");

        memory.put(id, content).await().unwrap();
        store.createLifecycle(BlockLifecycle.cacheOnlyLifecycle(id, EnumSet.of(TierLevel.MEMORY, TierLevel.LOCAL_DISK)));
        disk.failPuts.set(true);

        assertThat(reader.put(content).await().isFailure()).as("control: the put really failed").isTrue();
        assertThat(store.getLifecycle(id).unwrap().presentIn()).contains(TierLevel.MEMORY).doesNotContain(TierLevel.LOCAL_DISK);
    }

    /// A read whose marker claim loses to another reader hands back the slot it reserved.
    @Test
    void get_whoseMarkerClaimIsLost_givesTheSlotBack() {
        var lost = boundedReader(memory, 2, swallowingFirstMarkerClaim(), () -> 0L);
        var ids = IntStream.range(0, 4).mapToObj(i -> writeElsewhere("claim-lost-" + i)).toList();

        ids.forEach(id -> lost.get(id).await().unwrap());

        assertThat(store.listAllLifecycles().stream().filter(BlockLifecycle::cacheOnly).count())
            .as("the first read's slot came back, so two of the other three reads found room")
            .isEqualTo(2);
    }

    /// The marker a warm claimed and then did not need (no cache tier took the copy) is given back WITH its slot. (The
    /// read's own claim is swallowed so that the marker in question is warm's, not the read's.)
    @Test
    void warm_thatNoCacheTierAccepted_givesItsSlotBack() {
        var cache = new FlakyDurableTier(TierLevel.MEMORY, false);
        var flaky = new DefaultStorageInstance("flaky-cache",
                                               List.of(cache, shared),
                                               swallowingFirstMarkerClaim(),
                                               WritePolicy.WRITE_THROUGH,
                                               Option.none(),
                                               AppendLog.TornTailSink.logOnly(),
                                               _ -> {},
                                               DefaultStorageInstance.CacheMarkerLimits.cacheMarkerLimits(1, 600_000L, 60_000L),
                                               () -> 0L);
        var refused = writeElsewhere("cache-refuses-this");
        var accepted = writeElsewhere("cache-takes-this");

        cache.failPuts.set(true);

        assertThat(flaky.warm(refused).await().unwrap()).isFalse();
        assertThat(markersIn(store)).as("the read's claim was lost and warm's own marker was given back").isZero();

        cache.failPuts.set(false);

        assertThat(flaky.warm(accepted).await().unwrap()).as("the refused warm's slot came back").isTrue();
    }

    /// Both claims of one warm (the read's and warm's own) lose to other readers: no record, so no copy, and no slot
    /// is left reserved by the loser.
    @Test
    void warm_whoseEveryClaimIsLost_makesNoCopy_andLeaksNoSlot() {
        var cache = MemoryTier.memoryTier(MEMORY_MAX, TierLevel.MEMORY);
        var lost = boundedReader(cache, 1, swallowingMarkerClaims(2), () -> 0L);
        var first = writeElsewhere("claims-all-lost");
        var second = writeElsewhere("claims-go-through");

        assertThat(lost.warm(first).await().unwrap()).isFalse();
        assertThat(cache.exists(first).await().unwrap()).as("a copy with no record is not made").isFalse();
        assertThat(lost.warm(second).await().unwrap()).as("the lost claims gave their slots back").isTrue();
    }

    @Test
    void warm_ofABlockThatAlreadyHasARecord_isAcceptedAtAFullMarkerTable() {
        var metadata = MetadataStore.inMemoryMetadataStore("bounded");
        var cache = MemoryTier.memoryTier(MEMORY_MAX, TierLevel.MEMORY);
        var bounded = boundedReader(cache, 1, metadata);
        var id = writeElsewhere("already-tracked");

        assertThat(bounded.warm(id).await().unwrap()).as("control: it takes the only slot").isTrue();
        cache.delete(id).await().unwrap();

        assertThat(bounded.warm(id).await().unwrap()).as("tracking it again needs no new slot").isTrue();
        assertThat(cache.exists(id).await().unwrap()).isTrue();
        assertThat(markersIn(metadata)).isEqualTo(1);
    }

    @Test
    void warm_concurrently_neverTracksMoreThanTheCap() throws Exception {
        var pool = Executors.newFixedThreadPool(16);

        try {
            for (var round = 0; round < 25; round++) {
                var tag = "round-" + round;
                var metadata = MetadataStore.inMemoryMetadataStore("bounded-" + round);
                var bounded = boundedReader(MemoryTier.memoryTier(MEMORY_MAX * 100, TierLevel.MEMORY), 3, metadata);
                var ids = IntStream.range(0, 64).mapToObj(i -> writeElsewhere("concurrent-" + tag + "-" + i)).toList();
                var start = new CountDownLatch(1);
                var futures = ids.stream()
                                 .map(id -> pool.submit(() -> warmWhenReleased(bounded, id, start)))
                                 .toList();

                start.countDown();
                for (var future : futures) {
                    future.get();
                }

                assertThat(markersIn(metadata)).as("round %d: 64 warms racing for 3 slots", round).isEqualTo(3);
            }
        } finally {
            pool.shutdown();
        }
    }

    private static boolean warmWhenReleased(StorageInstance instance, BlockId id, CountDownLatch start) throws InterruptedException {
        start.await();

        return instance.warm(id).await().unwrap();
    }

    @Test
    void warm_againstACacheTierThatRefuses_neverTracksMoreThanTheCap() {
        var full = MemoryTier.memoryTier(4, TierLevel.MEMORY);
        var metadata = MetadataStore.inMemoryMetadataStore("bounded");
        var bounded = boundedReader(full, 3, metadata);
        var results = new ArrayList<Boolean>();

        IntStream.range(0, 40)
                 .mapToObj(i -> writeElsewhere("block-too-big-for-four-bytes-" + i))
                 .forEach(id -> results.add(bounded.warm(id).await().unwrap()));

        assertThat(results).containsOnly(false);
        assertThat(markersIn(metadata)).isLessThanOrEqualTo(3);
        assertThat(metadata.listAllLifecycles()).as("no empty marker is left by a warm that no tier accepted")
                                                .allSatisfy(record -> assertThat(record.presentIn()).isNotEmpty());
    }

    @Test
    void warm_ofABlockTheTableHasNoRoomToTrack_isRefusedNotHalfDone() {
        var metadata = MetadataStore.inMemoryMetadataStore("bounded");
        var bounded = boundedReader(MemoryTier.memoryTier(MEMORY_MAX, TierLevel.MEMORY), 1, metadata);
        var first = writeElsewhere("takes-the-only-slot");
        var second = writeElsewhere("finds-no-slot");

        assertThat(bounded.warm(first).await().unwrap()).as("control: room for one").isTrue();
        assertThat(bounded.warm(second).await().unwrap()).isFalse();
        assertThat(metadata.getLifecycle(second).isPresent()).isFalse();
        assertThat(markersIn(metadata)).isEqualTo(1);
    }

    @Test
    void reads_afterARestoreThatAlreadyHoldsMoreMarkersThanTheCap_createNoMore() {
        var metadata = MetadataStore.inMemoryMetadataStore("restored");
        var restored = IntStream.range(0, 10)
                                .mapToObj(i -> BlockId.blockId(bytes("restored-" + i)).unwrap())
                                .map(id -> BlockLifecycle.cacheOnlyLifecycle(id, EnumSet.of(TierLevel.REMOTE)))
                                .toList();

        metadata.restoreLifecycles(restored);

        var bounded = boundedReader(MemoryTier.memoryTier(MEMORY_MAX, TierLevel.MEMORY), 5, metadata);

        IntStream.range(0, 10).mapToObj(i -> writeElsewhere("fresh-" + i)).forEach(id -> bounded.get(id).await().unwrap());

        assertThat(markersIn(metadata)).as("the restored markers count; nothing new was admitted").isEqualTo(10);
    }

    /// The read that fetches a warmed block normally leaves the marker. When another reader took the last slot
    /// between warm's room check and that read (its claim answers false and stores nothing here), it leaves none; the copy must still be tracked, or not made.
    private MetadataStore swallowingFirstMarkerClaim() {
        return swallowingMarkerClaims(1);
    }

    private MetadataStore swallowingMarkerClaims(int count) {
        var swallowed = new AtomicInteger();

        return (MetadataStore) Proxy.newProxyInstance(MetadataStore.class.getClassLoader(),
                                                      new Class<?>[]{MetadataStore.class},
                                                      (_, method, args) -> {
                                                          if (method.getName().equals("claimBlock") && swallowed.getAndIncrement() < count) {
                                                              return false;
                                                          }
                                                          try {
                                                              return method.invoke(store, args);
                                                          } catch (InvocationTargetException e) {
                                                              throw e.getCause();
                                                          }
                                                      });
    }

    @Test
    void warm_whoseReadLeftNoRecord_tracksTheCopyItMakes() {
        var raced = boundedReader(memory, 1, swallowingFirstMarkerClaim());
        var id = writeElsewhere("record-lost-to-a-race");

        assertThat(raced.warm(id).await().unwrap()).isTrue();
        assertThat(raced.warm(writeElsewhere("no-slot-left-for-this-one")).await().unwrap())
            .as("the marker warm claimed took the only slot")
            .isFalse();

        var record = store.getLifecycle(id).unwrap();

        assertThat(record.cacheOnly()).isTrue();
        assertThat(record.presentIn()).contains(TierLevel.MEMORY);
        assertThat(markersIn(store)).isEqualTo(1);
    }

    @Test
    void warm_whoseReadLeftNoRecord_andWhoseCacheRefuses_leavesNoMarkerBehind() {
        var full = MemoryTier.memoryTier(4, TierLevel.MEMORY);
        var raced = boundedReader(full, 5, swallowingFirstMarkerClaim());
        var id = writeElsewhere("record-lost-and-cache-full");

        assertThat(raced.warm(id).await().unwrap()).isFalse();
        assertThat(store.getLifecycle(id).isPresent()).as("the marker warm claimed is given back").isFalse();
    }

    @Test
    void exists_ofABlockOnlyAStaleMarkerRemembers_isFalse() {
        var id = writeElsewhere("deleted-at-the-owner");

        reader.get(id).await().unwrap();
        assertThat(store.getLifecycle(id).unwrap().cacheOnly()).as("control: the read left a marker").isTrue();
        assertThat(reader.exists(id).await().unwrap()).as("control: readable while the shared copy is there").isTrue();

        shared.delete(id).await().unwrap();

        assertThat(reader.exists(id).await().unwrap()).as("the marker alone does not make a block exist").isFalse();
    }

    @Test
    void exists_ofABlockThisNodeOwns_isTrueFromItsRecord() {
        var id = reader.put(bytes("owned")).await().unwrap();

        assertThat(reader.exists(id).await().unwrap()).isTrue();
    }

    @Test
    void createRef_overABlockOnlyCachedHere_isRefusedAndChangesNothing() {
        var id = writeElsewhere("only-cached");

        reader.get(id).await().unwrap();

        var result = reader.createRef("name", id).await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(StorageError.CacheOnlyBlock.class));
        assertThat(store.getLifecycle(id).unwrap().cacheOnly()).isTrue();
        assertThat(store.getLifecycle(id).unwrap().refCount()).isZero();
        assertThat(reader.resolveRef("name").isPresent()).isFalse();
    }

    @Test
    void createRef_overABlockThisNodeOwns_namesItAndCountsIt() {
        var id = reader.put(bytes("owned-for-naming")).await().unwrap();

        assertThat(reader.createRef("alias", id).await().isSuccess()).isTrue();
        assertThat(reader.resolveRef("alias").isPresent()).isTrue();
        assertThat(store.getLifecycle(id).unwrap().refCount()).isEqualTo(2);
    }
}
