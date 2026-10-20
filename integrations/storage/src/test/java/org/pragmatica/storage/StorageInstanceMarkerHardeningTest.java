package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /// A durable tier whose writes can be made to fail.
    private static final class FlakyDurableTier implements StorageTier {
        private final MemoryTier backing = MemoryTier.memoryTier(1_000_000, TierLevel.LOCAL_DISK);
        final AtomicBoolean failPuts = new AtomicBoolean();

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
            return TierLevel.LOCAL_DISK;
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
            return true;
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
        return new DefaultStorageInstance("bounded",
                                          List.of(cache, shared),
                                          metadata,
                                          WritePolicy.WRITE_THROUGH,
                                          Option.none(),
                                          AppendLog.TornTailSink.logOnly(),
                                          _ -> {},
                                          DefaultStorageInstance.CacheMarkerLimits.cacheMarkerLimits(cap, 600_000L, 60_000L));
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
        var swallowed = new AtomicBoolean();

        return (MetadataStore) Proxy.newProxyInstance(MetadataStore.class.getClassLoader(),
                                                      new Class<?>[]{MetadataStore.class},
                                                      (_, method, args) -> {
                                                          if (method.getName().equals("claimBlock") && swallowed.compareAndSet(false, true)) {
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
