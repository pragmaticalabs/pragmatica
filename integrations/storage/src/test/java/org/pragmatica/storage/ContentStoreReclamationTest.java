package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.pragmatica.lang.Option;

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

/// #812 -- the content store paired [StorageInstance#put] with [StorageInstance#createRef]. Each
/// credits the block, so one named block sat at refCount 2, an explicit `deleteRef` only
/// brought it back to 1, and the block could never report [BlockLifecycle#isOrphaned] -- making EVERY
/// content-store block permanently uncollectable by the production [StorageGarbageCollector]. On [ContentStore] the
/// named blocks are the object entry and the name binding, written with one swap each.
///
/// What is pinned is the CONSEQUENCE, not the arithmetic: each test runs a real `collectGarbage` cycle
/// and asserts the block is gone from the metadata store and from the tier. Refcounts are read into
/// locals as the run proceeds and asserted only AFTER the collection assertion, deliberately -- a count
/// assertion placed first aborts the test before the consequence is ever reached, which would leave
/// every mutation probe proving that a counter moved rather than that a block was reclaimed. Here they
/// are failure locators for a result the collection assertion has already reported.
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
    private NamedContent store;
    private StorageGarbageCollector gc;

    @BeforeEach
    void setUp() {
        memoryTier = MemoryTier.memoryTier(ONE_MB);
        metadataStore = MetadataStore.inMemoryMetadataStore("content-reclamation");
        storage = StorageInstance.storageInstance("content-reclamation", List.of(memoryTier), metadataStore);
        store = NamedContent.namedContent(storage, ContentStoreConfig.contentStoreConfig(CHUNK_SIZE, Compression.NONE));
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

    /// The ref the store keeps the entry of the object `name` is bound to under.
    private String entryRefOf(String name) {
        var info = store.store().lookup(ObjectName.objectName(name).unwrap(), Option.none()).await().unwrap().unwrap();

        return "o/default/" + info.ref().id().asString();
    }

    /// Naming an object rewrites its entry twice, so a bind leaves the two superseded versions of the entry for the
    /// collector; they are the one thing a test that collects after naming must expect besides what it is about.
    private static final int BIND_RESIDUE = 2;

    private int collectAfterBinds() throws InterruptedException {
        Thread.sleep(20);

        return gc.collectGarbage();
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
        /// `isOrphaned` stays false, and `collectGarbage` returns 0 with the block still on the tier.
        @Test
        void put_thenDropTheEntryName_reachesRefCountZero_andIsCollected() throws InterruptedException {
            putContent(NAME, CONTENT_A);
            var entryRef = entryRefOf(NAME);
            var id = storage.resolveRef(entryRef).unwrap();
            var refCountWhileNamed = refCountOf(id);
            var orphanedWhileNamed = isOrphaned(id);
            var settled = collectAfterBinds();

            storage.deleteRef(entryRef)
                   .await()
                   .onFailure(c -> fail("deleteRef failed: " + c.message()));

            var refCountAfterDrop = refCountOf(id);
            var orphanedAfterDrop = isOrphaned(id);

            Thread.sleep(20);

            assertThat(settled).as("locator: the bind left only the two superseded entry versions").isEqualTo(BIND_RESIDUE);
            assertThat(gc.collectGarbage())
                    .as("the block must actually be collected once its only name is gone")
                    .isEqualTo(1);
            assertThat(metadataStore.containsBlock(id)).as("lifecycle metadata must be gone").isFalse();
            storage.get(id)
                   .await()
                   .onFailure(c -> fail("get failed: " + c.message()))
                   .onSuccess(opt -> assertThat(opt.isEmpty()).as("the block must be gone from the tier").isTrue());

            assertThat(refCountWhileNamed)
                    .as("locator: one name must credit exactly one reference -- put+createRef credited two (#812)")
                    .isEqualTo(1);
            assertThat(orphanedWhileNamed).as("locator: a named block is not orphaned").isFalse();
            assertThat(refCountAfterDrop).as("locator: dropping the only name must reach zero").isZero();
            assertThat(orphanedAfterDrop).as("locator: at zero the block must report orphaned").isTrue();
        }

        /// The whole lifecycle through the store alone: storing new content under an existing name displaces
        /// the old object, whose blocks (chunk, manifest, entry and name binding) must then be collected and
        /// nothing else. Red when the old object's name binding or entry credit is not given back: `collectGarbage`
        /// returns fewer than four.
        @Test
        void put_overExistingName_collectsSupersededObject_andKeepsCurrentContent() throws InterruptedException {
            var supersededId = putContent(NAME, CONTENT_A);
            collectAfterBinds();
            var currentId = putContent(NAME, CONTENT_B);
            var supersededRefCount = refCountOf(supersededId);
            var currentRefCount = refCountOf(currentId);

            Thread.sleep(20);

            assertThat(gc.collectGarbage()).as("exactly the superseded object's chunk, manifest, entry and name binding, and the second bind's residue, must be collected")
                                           .isEqualTo(4 + BIND_RESIDUE);
            assertThat(metadataStore.containsBlock(supersededId)).as("the displaced manifest must be gone").isFalse();
            assertThat(metadataStore.containsBlock(currentId)).as("the live manifest must survive").isTrue();

            store.get(NAME)
                 .await()
                 .onFailure(c -> fail("get failed: " + c.message()))
                 .onSuccess(opt -> {
                     assertThat(opt.isPresent()).as("collection must not have taken the live content").isTrue();
                     opt.onPresent(data -> assertThat(data).isEqualTo(CONTENT_B));
                 });

            assertThat(supersededRefCount).as("locator: nothing points at the displaced manifest").isZero();
            assertThat(currentRefCount).as("locator: the live manifest holds put's one credit").isEqualTo(1);
        }
    }

    @Nested
    class ChunkedContent {

        /// The counter-mutation guard, and the reason the fix removes NEITHER increment. The double
        /// count could also be "resolved" by dropping [StorageInstance#put]'s own credit and moving it
        /// onto the named-ref path -- and that is a use-after-free, not a fix: a chunk block carries no
        /// name at all (only the entry and the name binding are named), so `put`'s credit is the only thing holding it
        /// above zero. Drop it and the production collector deletes every chunk out from under a live
        /// document.
        ///
        /// Red under exactly that mutation: `collectGarbage` returns 4 instead of 0 and the content
        /// becomes unreadable.
        @Test
        void put_chunkedContent_namesOnlyTheEntry_andChunksSurviveCollection() throws InterruptedException {
            var content = generateContent(CHUNK_SIZE * 3 + 15);
            var manifestId = putContent(NAME, content);
            var chunkIds = chunkIdsOf(manifestId);
            var manifestRefCount = refCountOf(manifestId);
            var chunkRefCounts = chunkIds.stream()
                                         .map(ContentStoreReclamationTest.this::refCountOf)
                                         .toList();

            Thread.sleep(20);

            assertThat(gc.collectGarbage())
                    .as("only the superseded entry versions of the bind are orphaned -- collecting any chunk or the manifest is data loss, not reclamation")
                    .isEqualTo(BIND_RESIDUE);

            store.get(NAME)
                 .await()
                 .onFailure(c -> fail("get failed: " + c.message()))
                 .onSuccess(opt -> {
                     assertThat(opt.isPresent()).as("the document must still be readable after a GC cycle").isTrue();
                     opt.onPresent(data -> assertThat(data).isEqualTo(content));
                 });

            assertThat(chunkIds).as("locator: the fixture must genuinely chunk, or this proves nothing").hasSize(4);
            assertThat(manifestRefCount).as("locator: the manifest is named by nothing and held by put's credit alone").isEqualTo(1);
            assertThat(chunkRefCounts).as("locator: an unnamed chunk is held by put's credit alone")
                                      .containsOnly(1);
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
        /// path end to end -- restore into a fresh store, then drop the name and collect THERE.
        ///
        /// Note for operators: this proves the fix survives a rebuild, NOT that it repairs one. A
        /// snapshot written before this fix carries refCount 2 and a node restored from it inherits an
        /// uncollectable block; no migration is attempted here.
        @Test
        void put_snapshotAndRestore_rebuiltStoreStillReachesZero_andCollects() throws InterruptedException {
            putContent(NAME, CONTENT_A);
            var entryRef = entryRefOf(NAME);
            var id = storage.resolveRef(entryRef).unwrap();
            var config = snapshotConfig(tempDir.resolve("snapshots"), 100, 600_000, 5, "reclamation-node");

            snapshotManager(metadataStore, config).forceSnapshot();

            var restoredStore = MetadataStore.inMemoryMetadataStore("reclamation-restored");
            var snapshot = snapshotManager(restoredStore, config).restoreFromLatest()
                                                                 .unwrap()
                                                                 .unwrap();

            restoredStore.restoreLifecycles(snapshot.lifecycles());
            restoredStore.restoreRefs(snapshot.refs());

            var restoredRefCount = refCountIn(restoredStore, id);
            var restoredInstance = StorageInstance.storageInstance("reclamation-restored",
                                                                   List.of(memoryTier),
                                                                   restoredStore);
            var restoredGc = storageGarbageCollector(restoredInstance,
                                                     restoredStore,
                                                     garbageCollectorConfig(0, 500));
            restoredGc.activate();

            restoredInstance.deleteRef(entryRef)
                            .await()
                            .onFailure(c -> fail("deleteRef failed: " + c.message()));

            var refCountAfterDrop = refCountIn(restoredStore, id);

            Thread.sleep(20);

            assertThat(restoredGc.collectGarbage())
                    .as("a store rebuilt from a snapshot must collect the block too, with the superseded entry versions the snapshot carried")
                    .isEqualTo(1 + BIND_RESIDUE);
            assertThat(restoredStore.containsBlock(id)).as("lifecycle metadata must be gone").isFalse();

            assertThat(restoredRefCount)
                    .as("locator: the durable side must carry the corrected count, or the fix is undone on rebuild")
                    .isEqualTo(1);
            assertThat(refCountAfterDrop).as("locator: dropping the only name must reach zero").isZero();
        }

        /// F7: a name containing `=` and `|` survives a restart. A snapshot reader skips every data line containing
        /// `=`, so an unescaped `=` would drop the ref, and the snapshot's content hash would then reject the whole
        /// snapshot.
        @Test
        void put_nameWithEqualsAndPipe_snapshotAndRestore_isReadableFromTheRebuiltStore() {
            var name = "reports/q=3|final.bin";
            var config = snapshotConfig(tempDir.resolve("snapshots"), 100, 600_000, 5, "reclamation-node");

            putContent(name, CONTENT_A);
            snapshotManager(metadataStore, config).forceSnapshot();

            var restoredStore = MetadataStore.inMemoryMetadataStore("reclamation-restored");
            var snapshot = snapshotManager(restoredStore, config).restoreFromLatest()
                                                                 .unwrap();

            assertThat(snapshot.isPresent()).as("the snapshot passes its integrity check and restores").isTrue();
            restoredStore.restoreLifecycles(snapshot.unwrap().lifecycles());
            restoredStore.restoreRefs(snapshot.unwrap().refs());

            var restoredStorage = StorageInstance.storageInstance("reclamation-restored", List.of(memoryTier), restoredStore);
            var restored = NamedContent.namedContent(restoredStorage,
                                                     ContentStoreConfig.contentStoreConfig(CHUNK_SIZE, Compression.NONE));

            assertThat(restored.get(name).await().unwrap().unwrap()).isEqualTo(CONTENT_A);
        }
    }
}
