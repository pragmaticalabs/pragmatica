package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.pragmatica.storage.GarbageCollectorConfig.garbageCollectorConfig;
import static org.pragmatica.storage.StorageGarbageCollector.storageGarbageCollector;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #981 -- [DefaultContentStore] deleted by block id, around the reference counts: overwriting a
/// chunked name released its manifest but never its chunks (each chunk holds only [StorageInstance#put]'s
/// credit and no name, so nothing else ever decremented it -- leaked forever), and `delete` called
/// [StorageInstance#delete], which removes the block from every tier unconditionally -- so deleting one
/// of two names that deduplicate to the same block destroyed it for the other.
///
/// Same discipline as [ContentStoreReclamationTest]: what is pinned is the CONSEQUENCE, observed through
/// a real `collectGarbage` cycle and through `get` -- a block is either collected, or still readable
/// through the surviving name. Refcounts are read into locals as the run proceeds and asserted only
/// after the consequence, as failure locators. Only elapsed time (the grace period) is hand-fed.
class ContentStoreDeleteRefcountTest {
    private static final long ONE_MB = 1024 * 1024;
    private static final int CHUNK_SIZE = 128;
    private static final long GRACE_ELAPSED_MS = 20;
    private static final String NAME = "doc.bin";
    private static final String OTHER_NAME = "copy-of-doc.bin";
    private static final byte[] SMALL = "content-store-refcount-small".getBytes(StandardCharsets.UTF_8);

    private MemoryTier memoryTier;
    private DeleteObservingTier tier;
    private SeamMetadataStore metadataStore;
    private GatedStorage storage;
    private ContentStore store;
    private StorageGarbageCollector gc;

    @BeforeEach
    void setUp() {
        wire(ONE_MB);
    }

    /// The tier capacity is the one fixture knob: one test needs a tier that fits the first document
    /// and refuses the first chunk of the second.
    private void wire(long tierCapacity) {
        memoryTier = MemoryTier.memoryTier(tierCapacity);
        tier = new DeleteObservingTier(memoryTier);
        metadataStore = new SeamMetadataStore(MetadataStore.inMemoryMetadataStore("content-delete-refcount"));
        var instance = StorageInstance.storageInstance("content-delete-refcount", List.of(tier), metadataStore);

        storage = new GatedStorage(instance);
        store = ContentStore.contentStore(storage, ContentStoreConfig.contentStoreConfig(CHUNK_SIZE, Compression.NONE));
        // Grace 0 is floored to 1ms by GarbageCollectorConfig; the sleep before each cycle is what lets
        // collection proceed (see ContentStoreReclamationTest). The collector runs on the real instance:
        // the gate is for the content store's calls only.
        gc = storageGarbageCollector(instance, metadataStore, garbageCollectorConfig(0, 500));
        gc.activate();
    }

    private BlockId putContent(String name, byte[] content) {
        return BlockId.fromHex(store.put(name, content).await().unwrap()).unwrap();
    }

    private void deleteContent(String name) {
        store.delete(name).await().onFailure(c -> fail("delete of " + name + " failed: " + c.message()));
    }

    private void assertReadable(String name, byte[] expected) {
        store.get(name)
             .await()
             .onFailure(c -> fail("get of " + name + " failed: " + c.message()))
             .onSuccess(opt -> {
                            assertThat(opt.isPresent()).as("%s must still be readable", name)
                                      .isTrue();
                            opt.onPresent(data -> assertThat(data).isEqualTo(expected));
                        });
    }

    private void assertGone(BlockId id) {
        assertThat(metadataStore.containsBlock(id)).as("lifecycle of %s must be gone", id).isFalse();
        storage.get(id)
               .await()
               .onFailure(c -> fail("get failed: " + c.message()))
               .onSuccess(opt -> assertThat(opt.isEmpty()).as("%s must be gone from the tier", id)
                                           .isTrue());
    }

    /// A missing lifecycle record reads as -1 rather than throwing: on the unfixed base, `delete`
    /// removes the record outright, and a locator read that throws would abort the test before the
    /// consequence (`get` through the surviving name) is ever asserted.
    private int refCountOf(BlockId id) {
        return metadataStore.getLifecycle(id)
                            .map(BlockLifecycle::refCount)
                            .or(-1);
    }

    private List<Integer> refCountsOf(List<BlockId> ids) {
        return ids.stream()
                  .map(this::refCountOf)
                  .toList();
    }

    private List<BlockId> chunkIdsOf(BlockId manifestId) {
        return ContentManifest.fromBytes(storage.get(manifestId).await().unwrap().unwrap())
                              .unwrap()
                              .chunkBlockIds()
                              .stream()
                              .map(hex -> BlockId.fromHex(hex).unwrap())
                              .toList();
    }

    /// Lets the (1ms, floored) grace period elapse before the cycle. A deadline spin rather than
    /// `Thread.sleep`: no checked exception to declare, and no early return to explain a flake with.
    private static int collectAfterGrace(StorageGarbageCollector gc) {
        var deadline = System.currentTimeMillis() + GRACE_ELAPSED_MS;

        while (System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }

        return gc.collectGarbage();
    }

    /// `seed` makes two documents of the same size share no chunk.
    private static byte[] generateContent(int size, int seed) {
        var data = new byte[size];

        for (var i = 0; i < size; i++) {
            data[i] = (byte)((i + seed) % 251);
        }

        return data;
    }

    @Nested
    class OverwritingChunkedContent {
        /// Symptom 1. Red before the fix: `collectGarbage` returns 1 -- the superseded manifest, which
        /// `putRef` does decrement -- and every superseded chunk stays at refCount 1 with no name and no
        /// manifest left to reach it from.
        @Test
        void put_overChunkedName_releasesEveryPreviousChunk_andCollectsThem() {
            var first = generateContent(CHUNK_SIZE * 3 + 15, 1);
            var second = generateContent(CHUNK_SIZE * 2 + 7, 2);
            var firstManifest = putContent(NAME, first);
            var firstChunks = chunkIdsOf(firstManifest);

            putContent(NAME, second);
            var firstChunkRefCountsAfterOverwrite = refCountsOf(firstChunks);

            assertThat(collectAfterGrace(gc)).as("the superseded manifest AND every one of its chunks must be collected")
                      .isEqualTo(firstChunks.size() + 1);
            firstChunks.forEach(ContentStoreDeleteRefcountTest.this::assertGone);
            assertGone(firstManifest);
            assertReadable(NAME, second);
            assertThat(firstChunks).as("locator: the fixture must genuinely chunk, or this proves nothing").hasSize(4);
            assertThat(firstChunkRefCountsAfterOverwrite).as("locator: a chunk reachable from no manifest must be at zero")
                      .containsOnly(0);
        }

        /// The other direction of the same overwrite: the new content is small enough to be stored
        /// directly, so no new chunk is written -- the old chunks still have to go.
        @Test
        void put_directContentOverChunkedName_releasesEveryPreviousChunk() {
            var first = generateContent(CHUNK_SIZE * 3 + 15, 1);
            var firstManifest = putContent(NAME, first);
            var firstChunks = chunkIdsOf(firstManifest);

            putContent(NAME, SMALL);
            assertThat(collectAfterGrace(gc)).as("the superseded manifest AND every one of its chunks must be collected")
                      .isEqualTo(firstChunks.size() + 1);
            firstChunks.forEach(ContentStoreDeleteRefcountTest.this::assertGone);
            assertReadable(NAME, SMALL);
            assertThat(firstChunks).as("locator: the fixture must genuinely chunk, or this proves nothing").hasSize(4);
        }

        /// Symptom 1 in its smallest form, and the over-release guard in one. Red before the fix: re-
        /// storing the SAME chunked content credits every chunk once more through dedup and nothing
        /// releases the previous credit, so each chunk sits at 2 for one name. After the fix the two
        /// halves net to zero -- and a fix that released without first crediting, or released twice,
        /// orphans the live document here.
        @Test
        void put_sameChunkedContentAgain_keepsEveryChunkHeld() {
            var content = generateContent(CHUNK_SIZE * 3 + 15, 1);
            var manifest = putContent(NAME, content);
            var chunks = chunkIdsOf(manifest);

            putContent(NAME, content);
            var chunkRefCounts = refCountsOf(chunks);

            assertThat(collectAfterGrace(gc)).as("nothing here is orphaned -- the document is still named").isZero();
            assertReadable(NAME, content);
            assertThat(chunkRefCounts).as("locator: one name, one credit per chunk").containsOnly(1);
        }

        /// Pins the order: the new content is stored BEFORE the previous chunks are released, so a
        /// failed overwrite leaves the previous document exactly as it was. Green before the fix by
        /// construction (the base releases nothing); red under a release-first fix. The tier is sized to
        /// hold the first document and refuse the second's first chunk.
        @Test
        void put_thatFailsOverChunkedName_leavesPreviousContentHeld() {
            var first = generateContent(CHUNK_SIZE * 3 + 15, 1);
            var second = generateContent(CHUNK_SIZE * 2 + 7, 2);

            wire(750);
            var firstManifest = putContent(NAME, first);
            var firstChunks = chunkIdsOf(firstManifest);
            var usedByFirst = memoryTier.usedBytes();
            var secondPut = store.put(NAME, second).await();

            assertThat(secondPut.isFailure()).as("the fixture must make the overwrite fail, or this proves nothing")
                      .isTrue();
            assertThat(collectAfterGrace(gc)).as("a failed overwrite must orphan nothing").isZero();
            assertReadable(NAME, first);
            assertThat(usedByFirst + CHUNK_SIZE).as("locator: the tier must refuse the second document's first chunk")
                      .isGreaterThan(750);
            assertThat(refCountsOf(firstChunks)).as("locator: the previous chunks keep their credit").containsOnly(1);
        }
    }

    @Nested
    class DeletingOneOfTwoNames {
        /// Symptom 2, direct content. Red before the fix: `get` of the surviving name fails with
        /// `Content not found` -- the name still resolves, but the block behind it was deleted from
        /// every tier by the OTHER name's delete.
        @Test
        void delete_oneOfTwoNamesSharingABlock_keepsTheOtherReadable_andCollectsOnlyAtTheLastRelease() {
            var id = putContent(NAME, SMALL);
            var sameId = putContent(OTHER_NAME, SMALL);
            var refCountWhileShared = refCountOf(id);

            deleteContent(NAME);
            var refCountAfterFirstDelete = refCountOf(id);

            assertReadable(OTHER_NAME, SMALL);
            assertThat(collectAfterGrace(gc)).as("a block another name holds must not be collected").isZero();
            deleteContent(OTHER_NAME);
            assertThat(collectAfterGrace(gc)).as("the last name's release must make the block collectible").isEqualTo(1);
            assertGone(id);
            assertThat(sameId).as("locator: both names must deduplicate to one block, or this proves nothing")
                      .isEqualTo(id);
            assertThat(refCountWhileShared).as("locator: two names, two credits").isEqualTo(2);
            assertThat(refCountAfterFirstDelete).as("locator: deleting one name releases one credit").isEqualTo(1);
        }

        /// Symptom 2, chunked content. The manifests differ (a manifest carries its name) but the
        /// chunks deduplicate. Red before the fix: `get` of the surviving name fails with
        /// `One or more content chunks are missing`.
        @Test
        void delete_oneOfTwoNamesSharingChunks_keepsTheOtherReadable_andCollectsChunksOnlyAtTheLastRelease() {
            var content = generateContent(CHUNK_SIZE * 3 + 15, 1);
            var manifest = putContent(NAME, content);
            var otherManifest = putContent(OTHER_NAME, content);
            var chunks = chunkIdsOf(manifest);
            var chunkRefCountsWhileShared = refCountsOf(chunks);

            deleteContent(NAME);
            var chunkRefCountsAfterFirstDelete = refCountsOf(chunks);

            assertReadable(OTHER_NAME, content);
            assertThat(collectAfterGrace(gc)).as("only the deleted name's manifest is collectible").isEqualTo(1);
            assertGone(manifest);
            deleteContent(OTHER_NAME);
            assertThat(collectAfterGrace(gc)).as("the last name's release must make its manifest and every chunk collectible")
                      .isEqualTo(chunks.size() + 1);
            chunks.forEach(ContentStoreDeleteRefcountTest.this::assertGone);
            assertGone(otherManifest);
            assertThat(chunks).as("locator: the fixture must genuinely chunk, or this proves nothing").hasSize(4);
            assertThat(otherManifest).as("locator: a manifest carries its name, so the two differ")
                      .isNotEqualTo(manifest);
            assertThat(chunkRefCountsWhileShared).as("locator: two documents, two credits per chunk").containsOnly(2);
            assertThat(chunkRefCountsAfterFirstDelete).as("locator: one document left, one credit per chunk")
                      .containsOnly(1);
        }
    }

    @Nested
    class DeletingTheLastName {
        /// The last release goes through the SAME lifecycle the collector already reads -- there is no
        /// second delete path. Red before the fix: `collectGarbage` returns 0, because `delete` had
        /// already removed the blocks and their lifecycle records itself.
        @Test
        void delete_lastName_makesManifestAndChunksCollectible_ratherThanDeletingThemItself() {
            var content = generateContent(CHUNK_SIZE * 3 + 15, 1);
            var manifest = putContent(NAME, content);
            var chunks = chunkIdsOf(manifest);

            deleteContent(NAME);
            var existsAfterDelete = store.exists(NAME).await().unwrap();

            assertThat(collectAfterGrace(gc)).as("the manifest and every chunk must be collected by the collector, not by delete")
                      .isEqualTo(chunks.size() + 1);
            chunks.forEach(ContentStoreDeleteRefcountTest.this::assertGone);
            assertGone(manifest);
            assertThat(chunks).as("locator: the fixture must genuinely chunk, or this proves nothing").hasSize(4);
            assertThat(existsAfterDelete).as("locator: the name is gone the moment delete returns").isFalse();
        }
    }

    @Nested
    class NoTierDeleteFromTheContentStore {
        /// rev1411's P3, on this side of the boundary. `StorageInstance.delete(id)` deletes the tier bytes and
        /// then removes the record unconditionally, so a deduplicating `put` landing DURING its tier delete
        /// is credited on a record that is wiped a moment later and handed an id nobody can read. The
        /// content store must never open that window: its delete is a release (an atomic decrement, nothing
        /// for a put to race), and the only remover is the collector, whose compare-and-remove (#801) refuses
        /// a record a put has touched. What is pinned is that no tier delete happens at all. Red under a
        /// `delete` that goes back to `StorageInstance.delete`: one tier delete for the block.
        @Test
        void delete_directContent_issuesNoTierDelete() {
            var id = putContent(NAME, SMALL);

            deleteContent(NAME);
            assertThat(tier.deletes()).as("the content store's delete must not delete from any tier").isZero();
            assertThat(metadataStore.containsBlock(id)).as("the record is left for the collector, not removed here")
                      .isTrue();
        }

        @Test
        void delete_chunkedContent_issuesNoTierDelete() {
            var content = generateContent(CHUNK_SIZE * 3 + 15, 1);
            var chunks = chunkIdsOf(putContent(NAME, content));

            deleteContent(NAME);
            assertThat(tier.deletes()).as("neither the manifest nor a chunk is deleted by the content store").isZero();
            assertThat(chunks).as("locator: the fixture must genuinely chunk, or this proves nothing").hasSize(4);
        }
    }

    @Nested
    class SameNameConcurrency {
        /// rev1420's P1, the round-1 regression: two overwrites of the SAME name interleave so both pre-read
        /// the same previous manifest; a fix that released the PRE-READ chunks released them twice, and a
        /// third name deduplicating to those chunks went 2 -> 1 -> 0 and was collected. The base only leaked
        /// the loser's chunks (symptom 1); the round-1 head lost B's data (symptom 2 on the concurrent path).
        /// Interleaving: put1(A, Y) pre-reads X and is parked at its FIRST chunk write; put2(A, Z) runs to
        /// completion (displaces X, releases X's chunks); the gate opens; put1 finishes and displaces Z --
        /// so it must release Z's chunks, never X's again. Red under a pre-read release: B unreadable,
        /// `One or more content chunks are missing`.
        @Test
        void put_sameNameInterleaved_releasesEachDisplacedManifestOnce_andKeepsAThirdNameReadable() {
            var x = generateContent(CHUNK_SIZE * 3 + 15, 1);
            var y = generateContent(CHUNK_SIZE * 3 + 15, 2);
            var z = generateContent(CHUNK_SIZE * 3 + 15, 3);

            putContent(OTHER_NAME, x);
            var chunksX = chunkIdsOf(putContent(NAME, x));
            var sharedBefore = refCountsOf(chunksX);

            storage.holdNextPut();
            var put1 = store.put(NAME, y);
            var put1Parked = !put1.isResolved();
            var put2 = store.put(NAME, z).await();
            var chunksZ = chunkIdsOf(BlockId.fromHex(put2.unwrap()).unwrap());
            var xAfterPut2 = refCountsOf(chunksX);

            storage.openPutGate();
            var put1Result = put1.await();
            var xAfterBoth = refCountsOf(chunksX);
            var zAfterBoth = refCountsOf(chunksZ);
            var collected = collectAfterGrace(gc);

            assertReadable(OTHER_NAME, x);
            assertReadable(NAME, y);
            assertThat(collected).as("Z's manifest and chunks, X's manifest: the two displaced documents, once each")
                      .isEqualTo(chunksZ.size() + 2);
            assertThat(put1Parked).as("locator: put1 must be parked at its first chunk write").isTrue();
            assertThat(put2.isSuccess()).as("locator: put2 must complete while put1 is parked").isTrue();
            assertThat(put1Result.isSuccess()).as("locator: put1 must complete once released").isTrue();
            assertThat(sharedBefore).as("locator: both names share X's chunks").containsOnly(2);
            assertThat(xAfterPut2).as("locator: put2 released X's chunks once").containsOnly(1);
            assertThat(xAfterBoth).as("locator: put1 must not release X's chunks again -- B still holds them")
                      .containsOnly(1);
            assertThat(zAfterBoth).as("locator: put1 displaced Z, so Z's chunks are the ones it releases")
                      .containsOnly(0);
        }

        /// rev1420's P3: two deletes of the SAME name interleave (both pre-read the manifest, the first is
        /// parked at its ref drop); a third name holds the chunks. Only the delete whose drop actually
        /// removed the name releases; the other drops nothing and releases nothing. Not a regression
        /// against the base (which destroyed the chunks outright) -- pinned to bound the same-name hazard.
        @Test
        void delete_sameNameInterleaved_releasesTheManifestOnce_andKeepsAThirdNameReadable() {
            var content = generateContent(CHUNK_SIZE * 3 + 15, 1);

            putContent(OTHER_NAME, content);
            var chunks = chunkIdsOf(putContent(NAME, content));

            storage.holdNextDropRef();
            var delete1 = store.delete(NAME);
            var delete1Parked = !delete1.isResolved();
            var delete2 = store.delete(NAME).await();
            var afterDelete2 = refCountsOf(chunks);

            storage.openDropRefGate();
            var delete1Result = delete1.await();
            var afterBoth = refCountsOf(chunks);
            var collected = collectAfterGrace(gc);

            assertReadable(OTHER_NAME, content);
            assertThat(collected).as("only the deleted name's manifest is collectible").isEqualTo(1);
            assertThat(delete1Parked).as("locator: delete1 must be parked at its ref drop").isTrue();
            assertThat(delete2.isSuccess()).as("locator: delete2 must complete while delete1 is parked").isTrue();
            assertThat(delete1Result.isSuccess()).as("locator: delete1 must complete once released").isTrue();
            assertThat(afterDelete2).as("locator: the delete that removed the name released once").containsOnly(1);
            assertThat(afterBoth).as("locator: the delete that removed nothing released nothing").containsOnly(1);
        }

        /// The primitive underneath, pinned where its window is: `DefaultStorageInstance.swapRef` must report
        /// the id `MetadataStore.replaceRef` displaced, not one read a statement earlier. The competing
        /// overwrite runs to completion from INSIDE that statement gap (a one-shot hook before the first
        /// `replaceRef`), so an implementation that pre-reads reports the stale id, releases its chunks a
        /// second time, and the third name loses them. Red under exactly that mutation.
        @Test
        void put_competingOverwriteInsideTheSwap_releasesWhatTheSwapDisplaced_notAPreRead() {
            var x = generateContent(CHUNK_SIZE * 3 + 15, 1);
            var y = generateContent(CHUNK_SIZE * 3 + 15, 2);
            var z = generateContent(CHUNK_SIZE * 3 + 15, 3);

            putContent(OTHER_NAME, x);
            var chunksX = chunkIdsOf(putContent(NAME, x));

            metadataStore.beforeNextReplaceRef(() -> putContent(NAME, z));
            var manifestY = putContent(NAME, y);
            var xAfterBoth = refCountsOf(chunksX);
            var collected = collectAfterGrace(gc);

            assertReadable(OTHER_NAME, x);
            assertReadable(NAME, y);
            assertThat(metadataStore.replaceRefHookFired()).as("locator: the competing overwrite must have run inside the swap")
                      .isTrue();
            assertThat(xAfterBoth).as("locator: X's chunks were released once, by the overwrite that displaced X")
                      .containsOnly(1);
            assertThat(collected).as("locator: X's manifest, and Z's manifest with its chunks")
                      .isEqualTo(chunksX.size() + 2);
            assertThat(storage.resolveRef(NAME)).as("locator: the name ends at Y").isEqualTo(some(manifestY));
        }

        /// Same for `dropRef`: the id must come from `MetadataStore.removeRef` itself. The competing delete
        /// runs to completion from inside the gap before the first `removeRef`; a pre-reading
        /// implementation reports the id twice and the third name loses its chunks.
        @Test
        void delete_competingDeleteInsideTheDrop_releasesWhatTheDropRemoved_notAPreRead() {
            var content = generateContent(CHUNK_SIZE * 3 + 15, 1);

            putContent(OTHER_NAME, content);
            var chunks = chunkIdsOf(putContent(NAME, content));

            metadataStore.beforeNextRemoveRef(() -> deleteContent(NAME));
            deleteContent(NAME);
            var afterBoth = refCountsOf(chunks);
            var collected = collectAfterGrace(gc);

            assertReadable(OTHER_NAME, content);
            assertThat(metadataStore.removeRefHookFired()).as("locator: the competing delete must have run inside the drop")
                      .isTrue();
            assertThat(afterBoth).as("locator: released once, by the delete whose removal took the name")
                      .containsOnly(1);
            assertThat(collected).as("locator: the deleted name's manifest only").isEqualTo(1);
        }
    }

    /// Delegating metadata store with one-shot hooks BEFORE `replaceRef` / `removeRef` -- the statement gap
    /// in which a pre-reading `swapRef` / `dropRef` would hold a stale id.
    private static final class SeamMetadataStore implements MetadataStore {
        private final MetadataStore delegate;
        private final AtomicReference<Runnable> beforeReplaceRef = new AtomicReference<>();
        private final AtomicReference<Runnable> beforeRemoveRef = new AtomicReference<>();
        private final AtomicBoolean replaceRefHookFired = new AtomicBoolean();
        private final AtomicBoolean removeRefHookFired = new AtomicBoolean();

        SeamMetadataStore(MetadataStore delegate) {
            this.delegate = delegate;
        }

        void beforeNextReplaceRef(Runnable hook) {
            beforeReplaceRef.set(hook);
        }

        void beforeNextRemoveRef(Runnable hook) {
            beforeRemoveRef.set(hook);
        }

        boolean replaceRefHookFired() {
            return replaceRefHookFired.get();
        }

        boolean removeRefHookFired() {
            return removeRefHookFired.get();
        }

        private static void fireOnce(AtomicReference<Runnable> hook, AtomicBoolean fired) {
            option(hook.getAndSet(null)).onPresent(once -> fire(once, fired));
        }

        private static void fire(Runnable once, AtomicBoolean fired) {
            fired.set(true);
            once.run();
        }

        @Override
        public Option<BlockId> replaceRef(String refName, BlockId blockId) {
            fireOnce(beforeReplaceRef, replaceRefHookFired);

            return delegate.replaceRef(refName, blockId);
        }

        @Override
        public Option<BlockId> removeRef(String refName) {
            fireOnce(beforeRemoveRef, removeRefHookFired);

            return delegate.removeRef(refName);
        }

        @Override
        public Option<BlockLifecycle> getLifecycle(BlockId blockId) {
            return delegate.getLifecycle(blockId);
        }

        @Override
        public void createLifecycle(BlockLifecycle lifecycle) {
            delegate.createLifecycle(lifecycle);
        }

        @Override
        public boolean claimBlock(BlockId blockId, BlockLifecycle sentinel) {
            return delegate.claimBlock(blockId, sentinel);
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
        public void removeLifecycle(BlockId blockId) {
            delegate.removeLifecycle(blockId);
        }

        @Override
        public void putRef(String refName, BlockId blockId) {
            delegate.putRef(refName, blockId);
        }

        @Override
        public Option<BlockId> resolveRef(String refName) {
            return delegate.resolveRef(refName);
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
        public List<BlockLifecycle> listBlocksByTier(TierLevel tier) {
            return delegate.listBlocksByTier(tier);
        }

        @Override
        public List<BlockLifecycle> listAllLifecycles() {
            return delegate.listAllLifecycles();
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
        public void restoreLifecycles(List<BlockLifecycle> entries) {
            delegate.restoreLifecycles(entries);
        }

        @Override
        public void restoreRefs(Map<String, BlockId> refs) {
            delegate.restoreRefs(refs);
        }

        @Override
        public void restoreEpoch(long epoch) {
            delegate.restoreEpoch(epoch);
        }
    }

    /// Delegates everything -- INCLUDING `release`, `swapRef` and `dropRef`, whose interface defaults would
    /// re-open the race -- and can park one chunk `put` or one `dropRef` behind a gate.
    private static final class GatedStorage implements StorageInstance {
        private final StorageInstance delegate;
        private final AtomicBoolean holdPut = new AtomicBoolean();
        private final AtomicBoolean holdDropRef = new AtomicBoolean();
        private final Promise<Unit> putGate = Promise.promise();
        private final Promise<Unit> dropRefGate = Promise.promise();

        GatedStorage(StorageInstance delegate) {
            this.delegate = delegate;
        }

        void holdNextPut() {
            holdPut.set(true);
        }

        void openPutGate() {
            putGate.succeed(unit());
        }

        void holdNextDropRef() {
            holdDropRef.set(true);
        }

        void openDropRefGate() {
            dropRefGate.succeed(unit());
        }

        @Override
        public Promise<BlockId> put(byte[] content) {
            return holdPut.compareAndSet(true, false)
                   ? putGate.flatMap(_ -> delegate.put(content))
                   : delegate.put(content);
        }

        @Override
        public Promise<BlockId> put(byte[] content, BlockMetadata metadata) {
            return delegate.put(content, metadata);
        }

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return delegate.get(id);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return delegate.exists(id);
        }

        @Override
        public Promise<Unit> createRef(String name, BlockId id) {
            return delegate.createRef(name, id);
        }

        @Override
        public Option<BlockId> resolveRef(String name) {
            return delegate.resolveRef(name);
        }

        @Override
        public Promise<Unit> deleteRef(String name) {
            return delegate.deleteRef(name);
        }

        @Override
        public Promise<Option<BlockId>> dropRef(String name) {
            return holdDropRef.compareAndSet(true, false)
                   ? dropRefGate.flatMap(_ -> delegate.dropRef(name))
                   : delegate.dropRef(name);
        }

        @Override
        public Promise<Unit> release(BlockId id) {
            return delegate.release(id);
        }

        @Override
        public Promise<BlockId> putRef(String name, byte[] content) {
            return delegate.putRef(name, content);
        }

        @Override
        public Promise<RefSwap> swapRef(String name, byte[] content) {
            return delegate.swapRef(name, content);
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return delegate.delete(id);
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public List<TierInfo> tierInfo() {
            return delegate.tierInfo();
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }
    }

    /// Delegating tier that counts deletes -- the seam rev1411's P3 hooked on `StorageInstance.delete`,
    /// here to show the content store never reaches it.
    private static final class DeleteObservingTier implements StorageTier {
        private final StorageTier backing;
        private final AtomicInteger deletes = new AtomicInteger();

        DeleteObservingTier(StorageTier backing) {
            this.backing = backing;
        }

        int deletes() {
            return deletes.get();
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            deletes.incrementAndGet();

            return backing.delete(id);
        }

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return backing.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return backing.put(id, content);
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
}
