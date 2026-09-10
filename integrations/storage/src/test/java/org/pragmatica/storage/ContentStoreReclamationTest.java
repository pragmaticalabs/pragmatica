package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.storage.GarbageCollectorConfig.garbageCollectorConfig;
import static org.pragmatica.storage.SnapshotConfig.snapshotConfig;
import static org.pragmatica.storage.SnapshotManager.snapshotManager;
import static org.pragmatica.storage.StorageGarbageCollector.storageGarbageCollector;

/// #812 -- [DefaultContentStore] paired [StorageInstance#put] with [StorageInstance#createRef]. Each
/// credits the block, so one named piece of content sat at refCount 2, an explicit `deleteRef` only
/// brought it back to 1, and the block could never report [BlockLifecycle#isOrphaned] -- making EVERY
/// content-store block permanently uncollectable by the production [StorageGarbageCollector].
///
/// What is pinned here is the CONSEQUENCE, not the arithmetic: each test runs a real `collectGarbage`
/// cycle and asserts the block is gone from the metadata store and from the tier. A test asserting
/// `refCount == 1` alone would pin the counter and not the collection it is supposed to enable, so the
/// count assertions are present only as a locator for a failure the collection assertion reports.
///
/// The only thing hand-fed is elapsed time (the grace period). Every refCount below is produced by the
/// code under test.
class ContentStoreReclamationTest {

    private static final long ONE_MB = 1024 * 1024;
    private static final int CHUNK_SIZE = 128;
    private static final String NAME = "doc.bin";
    private static final byte[] CONTENT_A = "content-store-reclamation-alpha".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_B = "content-store-reclamation-bravo".getBytes(StandardCharsets.UTF_8);

    private MemoryTier memoryTier;
    private MetadataStore metadataStore;
    private StorageInstance storage;
    private ContentStore store;
    private StorageGarbageCollector gc;

    @BeforeEach
    void setUp() {
        memoryTier = MemoryTier.memoryTier(ONE_MB);
        metadataStore = MetadataStore.inMemoryMetadataStore("content-reclamation");
        storage = StorageInstance.storageInstance("content-reclamation", List.of(memoryTier), metadataStore);
        store = ContentStore.contentStore(storage, ContentStoreConfig.contentStoreConfig(CHUNK_SIZE, Compression.NONE));
        // 0 requests the shortest possible grace period; GarbageCollectorConfig floors it at 1ms
        // (its canonical constructor clamps gracePeriodMs to Math.max(gracePeriodMs, 1)), so the
        // sleep in each test -- not a zero grace -- is what actually lets collection proceed.
        gc = storageGarbageCollector(storage, metadataStore, garbageCollectorConfig(0, 500));
        gc.activate();
    }

    private BlockId putContent(String name, byte[] content) {
        return BlockId.fromHex(store.put(name, content)
                                    .await()
                                    .unwrap())
                      .unwrap();
    }

    private int refCountOf(BlockId id) {
        return refCountIn(metadataStore, id);
    }

    private static int refCountIn(MetadataStore store, BlockId id) {
        return store.getLifecycle(id)
                    .unwrap()
                    .refCount();
    }

    private boolean isOrphaned(BlockId id) {
        return metadataStore.getLifecycle(id)
                            .unwrap()
                            .isOrphaned();
    }

    private static byte[] generateContent(int size) {
        var data = new byte[size];

        for (var i = 0; i < size; i++) {
            data[i] = (byte) (i % 251);
        }

        return data;
    }

    @Nested
    class SingleBlockContent {

        /// Red before the fix: the block sits at refCount 2 after `put`, `deleteRef` brings it to 1,
        /// `isOrphaned` stays false and `collectGarbage` returns 0.
        @Test
        void put_thenDropTheName_reachesRefCountZero_andIsCollected() throws InterruptedException {
            var id = putContent(NAME, CONTENT_A);

            assertThat(refCountOf(id))
                    .as("one name must credit exactly one reference -- put+createRef credited two (#812)")
                    .isEqualTo(1);
            assertThat(isOrphaned(id)).isFalse();

            storage.deleteRef(NAME)
                   .await()
                   .onFailure(c -> fail("deleteRef failed: " + c.message()));

            assertThat(refCountOf(id)).as("dropping the only name must reach zero").isZero();
            assertThat(isOrphaned(id)).isTrue();

            Thread.sleep(20);

            assertThat(gc.collectGarbage())
                    .as("the orphaned block must actually be collected, not merely counted down")
                    .isEqualTo(1);
            assertThat(metadataStore.containsBlock(id)).as("lifecycle metadata must be gone").isFalse();
            storage.get(id)
                   .await()
                   .onFailure(c -> fail("get failed: " + c.message()))
                   .onSuccess(opt -> assertThat(opt.isEmpty()).as("the block must be gone from the tier").isTrue());
        }

        /// The whole lifecycle through the [ContentStore] API alone: storing new content under an
        /// existing name displaces the old block, which must then be collected. Red before the fix for
        /// the OTHER half of the same defect -- `createRef` overwrites the ref pointer without ever
        /// decrementing what it displaced, so the superseded block stayed at 2 and `collectGarbage`
        /// returned 0.
        @Test
        void put_overExistingName_collectsSupersededBlock_andKeepsCurrentContent() throws InterruptedException {
            var supersededId = putContent(NAME, CONTENT_A);
            var currentId = putContent(NAME, CONTENT_B);

            assertThat(refCountOf(supersededId)).as("nothing points at the displaced block").isZero();
            assertThat(refCountOf(currentId)).as("the name points at exactly one block, once").isEqualTo(1);

            Thread.sleep(20);

            assertThat(gc.collectGarbage()).as("exactly the superseded block").isEqualTo(1);
            assertThat(metadataStore.containsBlock(supersededId)).isFalse();
            assertThat(metadataStore.containsBlock(currentId)).as("the live block must survive").isTrue();

            store.get(NAME)
                 .await()
                 .onFailure(c -> fail("get failed: " + c.message()))
                 .onSuccess(opt -> {
                     assertThat(opt.isPresent()).as("collection must not have taken the live content").isTrue();
                     opt.onPresent(data -> assertThat(data).isEqualTo(CONTENT_B));
                 });
        }
    }

    @Nested
    class ChunkedContent {

        /// The counter-mutation guard, and the reason the fix removes NEITHER increment. The double
        /// count could also be "resolved" by dropping [StorageInstance#put]'s own credit -- and that is
        /// a use-after-free, not a fix: a chunk block carries no name at all (only the manifest is
        /// named), so `put`'s credit is the only thing holding it above zero. Drop it and the
        /// production collector deletes every chunk out from under a live document.
        ///
        /// Red if `put` stops crediting: the chunks orphan immediately, `collectGarbage` returns 4
        /// instead of 0, and the content becomes unreadable.
        @Test
        void put_chunkedContent_namesOnlyTheManifest_andChunksSurviveCollection() throws InterruptedException {
            var content = generateContent(CHUNK_SIZE * 3 + 15);
            var manifestId = putContent(NAME, content);
            var chunkIds = chunkIdsOf(manifestId);

            assertThat(chunkIds).as("the fixture must genuinely chunk, or this test proves nothing").hasSize(4);
            assertThat(refCountOf(manifestId)).as("the manifest carries the one name").isEqualTo(1);
            chunkIds.forEach(chunkId -> assertThat(refCountOf(chunkId))
                    .as("an unnamed chunk is held by put's credit alone")
                    .isEqualTo(1));

            Thread.sleep(20);

            assertThat(gc.collectGarbage()).as("nothing here is orphaned -- collecting any of it is data loss").isZero();

            store.get(NAME)
                 .await()
                 .onFailure(c -> fail("get failed: " + c.message()))
                 .onSuccess(opt -> {
                     assertThat(opt.isPresent()).isTrue();
                     opt.onPresent(data -> assertThat(data).isEqualTo(content));
                 });
        }

        private List<BlockId> chunkIdsOf(BlockId manifestId) {
            return parseChunkIds(storage.get(manifestId)
                                        .await()
                                        .unwrap()
                                        .unwrap());
        }

        private List<BlockId> parseChunkIds(byte[] manifestBytes) {
            return ContentManifest.fromBytes(manifestBytes)
                                  .unwrap()
                                  .chunkBlockIds()
                                  .stream()
                                  .map(hex -> BlockId.fromHex(hex).unwrap())
                                  .toList();
        }
    }

    @Nested
    class AcrossSnapshotRestore {

        @TempDir
        Path tempDir;

        /// The corrected count has to survive the durable path, not merely live in the in-memory map:
        /// [SnapshotManager] writes lifecycle entries verbatim and [MetadataStore#restoreLifecycles]
        /// reads them back the same way, so a count corrected only in memory would be re-established
        /// wrong on every node restart while every in-process test still passed. Drives the rebuild
        /// path end to end -- restore into a fresh store, then drop the name and collect there.
        ///
        /// Note for operators: this proves the fix survives a rebuild, NOT that it repairs one. A
        /// snapshot written before this fix carries refCount 2 and a node restored from it inherits an
        /// uncollectable block; no migration is attempted here.
        @Test
        void put_snapshotAndRestore_rebuiltStoreStillReachesZero_andCollects() throws InterruptedException {
            var id = putContent(NAME, CONTENT_A);
            var config = snapshotConfig(tempDir.resolve("snapshots"), 100, 600_000, 5, "reclamation-node");

            snapshotManager(metadataStore, config).forceSnapshot();

            var restoredStore = MetadataStore.inMemoryMetadataStore("reclamation-restored");
            var snapshot = snapshotManager(restoredStore, config).restoreFromLatest()
                                                                 .unwrap();

            restoredStore.restoreLifecycles(snapshot.lifecycles());
            restoredStore.restoreRefs(snapshot.refs());

            assertThat(refCountIn(restoredStore, id))
                    .as("the durable side must carry the corrected count, or the fix is undone on rebuild")
                    .isEqualTo(1);

            var restoredInstance = StorageInstance.storageInstance("reclamation-restored",
                                                                   List.of(memoryTier),
                                                                   restoredStore);
            var restoredGc = storageGarbageCollector(restoredInstance,
                                                     restoredStore,
                                                     garbageCollectorConfig(0, 500));
            restoredGc.activate();

            restoredInstance.deleteRef(NAME)
                            .await()
                            .onFailure(c -> fail("deleteRef failed: " + c.message()));

            assertThat(refCountIn(restoredStore, id)).isZero();

            Thread.sleep(20);

            assertThat(restoredGc.collectGarbage()).as("the rebuilt store must collect it too").isEqualTo(1);
            assertThat(restoredStore.containsBlock(id)).isFalse();
        }
    }
}
