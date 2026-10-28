package org.pragmatica.storage;

import java.time.Instant;
import java.util.Map;
import java.util.Random;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.storage.V2Fixture.CHUNK;
import static org.pragmatica.storage.V2Fixture.failureOf;
import static org.pragmatica.storage.V2Fixture.payload;
import static org.pragmatica.storage.V2Fixture.read;
import static org.pragmatica.storage.V2Fixture.uploaded;
import static org.pragmatica.storage.V2Fixture.upload;


/// The v2 content store in local mode: what an object is, what a namespace confines, what a range returns. The credits a
/// writer takes and gives back are pinned in [ContentStoreV2CreditsTest]; the above-heap stream in
/// [ContentStoreV2LargeObjectTest].
@Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ContentStoreV2Test {
    private V2Fixture fixture;
    private ContentStoreV2 store;

    @BeforeEach
    void setUp() {
        fixture = V2Fixture.v2Fixture();
        store = fixture.store("alpha");
    }

    /// ObjectMeta round-trips: display name, content type, owner, created, expiry and attributes written at create come back
    /// from `stat`, from `lookup` and from a store re-opened on the same instance. Mutation: drop the metadata at commit.
    @Nested
    class MetaRoundTrip {
        @Test
        void objectMeta_roundTripsThroughStatLookupAndAFreshStore() {
            var expiry = Instant.parse("2031-02-03T04:05:06.789Z");
            var spec = ObjectSpec.objectSpec(some("report.pdf"), "application/pdf", some(40L), some(expiry), Map.of("team", "ops", "k", "v"));
            var before = Instant.now();
            var info = upload(store, spec, payload(40, 1), 9).await().unwrap();
            var name = ObjectName.objectName("reports/2031/report.pdf").unwrap();

            store.bind(name, info.ref().id(), Precondition.nameFree()).await().unwrap();

            var viaStat = store.stat(info.ref().id()).await().unwrap().unwrap();
            var viaLookup = store.lookup(name, none()).await().unwrap().unwrap();
            var viaFreshStore = fixture.store("alpha").stat(info.ref().id()).await().unwrap().unwrap();

            for (var seen : new ObjectInfo[]{viaStat, viaLookup, viaFreshStore}) {
                assertThat(seen.meta().displayName()).isEqualTo(some("report.pdf"));
                assertThat(seen.meta().contentType()).isEqualTo("application/pdf");
                assertThat(seen.meta().owner()).isEqualTo("alpha");
                assertThat(seen.meta().expiresAt()).isEqualTo(some(expiry));
                assertThat(seen.meta().attributes()).isEqualTo(Map.of("team", "ops", "k", "v"));
                assertThat(seen.meta().size()).isEqualTo(40);
                assertThat(seen.meta().createdAt()).isBetween(before, Instant.now());
                assertThat(seen.ref().digest()).isEqualTo(Sha256.sha256(payload(40, 1)).unwrap());
            }

            assertThat(viaLookup.name().map(ObjectName::value)).isEqualTo(some("reports/2031/report.pdf"));
            assertThat(viaStat.meta().createdAt()).isEqualTo(info.meta().createdAt());
        }

        @Test
        void update_patchesOnlyTheMutableFieldsAndBumpsTheVersion() {
            var info = uploaded(store, payload(20, 2));
            var patched = store.update(info.ref().id(),
                                       MetaPatch.metaPatch(some(Instant.parse("2040-01-01T00:00:00Z")),
                                                           some("text/plain"),
                                                           some(Map.of("a", "b")))).await().unwrap();

            assertThat(patched.meta().contentType()).isEqualTo("text/plain");
            assertThat(patched.meta().attributes()).isEqualTo(Map.of("a", "b"));
            assertThat(patched.meta().expiresAt()).isEqualTo(some(Instant.parse("2040-01-01T00:00:00Z")));
            assertThat(patched.meta().owner()).isEqualTo(info.meta().owner());
            assertThat(patched.meta().createdAt()).isEqualTo(info.meta().createdAt());
            assertThat(patched.meta().digest()).isEqualTo(info.meta().digest());
            assertThat(patched.catalogVersion()).isEqualTo(info.catalogVersion() + 1);
            assertThat(read(store, patched.ref(), ByteRange.whole())).isEqualTo(payload(20, 2));
        }

        @Test
        void update_ofAnObjectThatIsNotThere_isNotFound() {
            var cause = failureOf(store.update(ObjectId.objectId(), MetaPatch.metaPatch(none(), none(), none())));

            assertThat(cause).isInstanceOf(ContentStoreError.ObjectNotFound.class);
        }
    }

    /// A namespace-bound store cannot delete or list another namespace's object. Mutation: omit the namespace on the key.
    @Nested
    class NamespaceConfinement {
        @Test
        void aStoreBoundToAnotherNamespace_neitherSeesNorDeletesNorBindsTheObject() {
            var other = fixture.store("beta");
            var mine = uploaded(store, payload(50, 3));
            var theirs = uploaded(other, payload(30, 4));
            var name = ObjectName.objectName("shared-name").unwrap();

            assertThat(other.stat(mine.ref().id()).await().unwrap()).isEqualTo(none());
            other.delete(mine.ref().id()).await().unwrap();
            assertThat(failureOf(other.bind(name, mine.ref().id(), Precondition.unconditional())))
                .isInstanceOf(ContentStoreError.ObjectNotFound.class);
            assertThat(failureOf(other.update(mine.ref().id(), MetaPatch.metaPatch(none(), some("x/y"), none()))))
                .isInstanceOf(ContentStoreError.ObjectNotFound.class);

            assertThat(other.list(none(), none(), 100).await().unwrap().items()).extracting(info -> info.ref().id())
                                                                                  .containsExactly(theirs.ref().id());
            assertThat(store.list(none(), none(), 100).await().unwrap().items()).extracting(info -> info.ref().id())
                                                                                 .containsExactly(mine.ref().id());
            assertThat(store.stat(mine.ref().id()).await().unwrap().isPresent()).as("alpha's object survived beta's delete").isTrue();
            assertThat(read(store, mine.ref(), ByteRange.whole())).isEqualTo(payload(50, 3));
        }

        @Test
        void sameNameInTwoNamespaces_boundToTwoObjects() {
            var other = fixture.store("beta");
            var mine = uploaded(store, payload(10, 5));
            var theirs = uploaded(other, payload(10, 6));
            var name = ObjectName.objectName("config.json").unwrap();

            store.bind(name, mine.ref().id(), Precondition.nameFree()).await().unwrap();
            other.bind(name, theirs.ref().id(), Precondition.nameFree()).await().unwrap();

            assertThat(store.lookup(name, none()).await().unwrap().unwrap().ref().id()).isEqualTo(mine.ref().id());
            assertThat(other.lookup(name, none()).await().unwrap().unwrap().ref().id()).isEqualTo(theirs.ref().id());
        }

        @Test
        void readingByReference_crossesNamespaces() {
            var other = fixture.store("beta");
            var mine = uploaded(store, payload(45, 7));

            assertThat(read(other, mine.ref(), ByteRange.byteRange(3, some(40L)))).isEqualTo(slice(payload(45, 7), 3, 40));
        }

        @Test
        void aNamespaceThatIsNotValid_isRefusedWhenTheStoreIsBuilt() {
            for (var bad : new String[]{"", "A", "a/b", "-a", "a b", "x".repeat(64)}) {
                var config = ContentStoreConfig.contentStoreConfig(CHUNK, Compression.NONE, bad, 2, 2);

                assertThat(config.validatedAgainst(StorageInstance.DEFAULT_MAX_BLOCK_BYTES).isFailure()).as("'%s'", bad).isTrue();
                assertThat(ContentStoreV2.contentStoreV2(fixture.storage, config).isFailure()).as("'%s'", bad).isTrue();
            }

            assertThat(ContentStoreConfig.contentStoreConfig().validatedAgainst(StorageInstance.DEFAULT_MAX_BLOCK_BYTES).isSuccess())
                .as("the default namespace").isTrue();
        }

        @Test
        void aWindowOrReadAheadOutOfRange_isRefusedWhenTheStoreIsBuilt() {
            var noWindow = ContentStoreConfig.contentStoreConfig(CHUNK, Compression.NONE, "alpha", 0, 2);
            var negativeAhead = ContentStoreConfig.contentStoreConfig(CHUNK, Compression.NONE, "alpha", 2, -1);

            assertThat(noWindow.validatedAgainst(StorageInstance.DEFAULT_MAX_BLOCK_BYTES).isFailure()).isTrue();
            assertThat(negativeAhead.validatedAgainst(StorageInstance.DEFAULT_MAX_BLOCK_BYTES).isFailure()).isTrue();
        }
    }

    /// Ranged reads at the block edges. Chunk size is 16, so the payload below is 7 blocks, the last one short.
    @Nested
    class RangedRead {
        private static final int LENGTH = 100;
        private final byte[] data = payload(LENGTH, 8);

        @Test
        void everyRangeReturnsExactlyTheBytesOfThatRange() {
            var info = uploaded(store, data);
            var random = new Random(42);

            for (var i = 0; i < 300; i++) {
                var first = random.nextInt(LENGTH);
                var last = first + random.nextInt(LENGTH - first);

                assertThat(read(store, info.ref(), ByteRange.byteRange(first, some((long) last)))).as("range %d-%d", first, last)
                                                                                                   .isEqualTo(slice(data, first, last));
            }
        }

        @Test
        void rangesOnBlockEdges_firstAndLastBlocksAreSliced() {
            var info = uploaded(store, data);
            long[][] edges = {{0, 0}, {0, 15}, {15, 16}, {16, 16}, {16, 31}, {31, 32}, {0, 99}, {96, 99}, {99, 99}, {15, 32}, {17, 30}};

            for (var edge : edges) {
                assertThat(read(store, info.ref(), ByteRange.byteRange(edge[0], some(edge[1])))).as("range %d-%d", edge[0], edge[1])
                                                                                                 .isEqualTo(slice(data, (int) edge[0], (int) edge[1]));
            }
        }

        @Test
        void aRangeWithNoLast_runsToTheEnd_andALastPastTheEndIsClamped() {
            var info = uploaded(store, data);

            assertThat(read(store, info.ref(), ByteRange.byteRange(37, none()))).isEqualTo(slice(data, 37, LENGTH - 1));
            assertThat(read(store, info.ref(), ByteRange.byteRange(37, some(10_000L)))).isEqualTo(slice(data, 37, LENGTH - 1));
            assertThat(read(store, info.ref(), ByteRange.whole())).isEqualTo(data);
        }

        @Test
        void aRangeThatIsNotInTheObject_isRefused() {
            var info = uploaded(store, data);

            assertThat(failureOf(store.open(info.ref(), ByteRange.byteRange(LENGTH, none())))).isInstanceOf(ContentStoreError.InvalidRange.class);
            assertThat(failureOf(store.open(info.ref(), ByteRange.byteRange(-1, none())))).isInstanceOf(ContentStoreError.InvalidRange.class);
            assertThat(failureOf(store.open(info.ref(), ByteRange.byteRange(10, some(9L))))).isInstanceOf(ContentStoreError.InvalidRange.class);
        }

        @Test
        void anEmptyObject_hasAManifestAndReadsAsNothing() {
            var info = upload(store, ObjectSpec.objectSpec("text/plain"), new byte[0], 1).await().unwrap();

            assertThat(info.meta().size()).isZero();
            assertThat(read(store, info.ref(), ByteRange.whole())).isEmpty();
            assertThat(failureOf(store.open(info.ref(), ByteRange.byteRange(1, none())))).isInstanceOf(ContentStoreError.InvalidRange.class);
        }

        @Test
        void compressedChunks_areReadBackThroughTheSameRanges() {
            var compressing = fixture.store(ContentStoreConfig.contentStoreConfig(CHUNK, Compression.LZ4, "zip", 2, 2));
            var compressible = new byte[LENGTH];

            new Random(5).nextBytes(compressible);
            java.util.Arrays.fill(compressible, 20, 70, (byte) 7);

            var info = uploaded(compressing, compressible);

            assertThat(read(compressing, info.ref(), ByteRange.byteRange(18, some(72L)))).isEqualTo(slice(compressible, 18, 72));
        }
    }

    /// A block that fails its integrity check fails the read that reaches it, after the blocks before it were delivered.
    /// Mutation: turn a failed block fetch into a zero-filled block.
    @Nested
    class IntegrityOnRead {
        @Test
        void aBlockThatRotted_failsTheNextThatReachesIt_andTheBlocksBeforeItWereDelivered() {
            var data = payload(CHUNK * 4, 16);
            var clean = V2Fixture.v2Fixture();
            var info = uploaded(clean.store("alpha"), data);
            var rotten = clean.chunksOf(info).get(2);
            var tier = new V2Fixture.TamperingTier(MemoryTier.memoryTier(1 << 20), rotten);
            var fixture = new V2Fixture(tier);
            var uploadedAgain = uploaded(fixture.store("alpha"), data);
            var stream = fixture.store("alpha").open(uploadedAgain.ref(), ByteRange.whole()).await().unwrap();
            var delivered = new java.util.ArrayList<byte[]>();

            for (var i = 0; i < 2; i++) {
                var buffer = stream.next().await().unwrap().unwrap();
                var bytes = new byte[buffer.remaining()];

                buffer.get(bytes);
                delivered.add(bytes);
            }

            assertThat(delivered.get(0)).isEqualTo(java.util.Arrays.copyOfRange(data, 0, CHUNK));
            assertThat(delivered.get(1)).isEqualTo(java.util.Arrays.copyOfRange(data, CHUNK, 2 * CHUNK));
            assertThat(failureOf(stream.next())).isInstanceOf(StorageError.IntegrityError.class);
        }
    }

    /// Every object has a manifest, a one-chunk object included: the entry names it and a reader can open it.
    @Nested
    class Layout {
        @Test
        void aOneChunkObject_hasAOneChunkManifest() {
            var info = uploaded(store, payload(CHUNK, 9));
            var manifest = ContentManifest.fromBytes(fixture.storage.get(info.ref().manifest()).await().unwrap().unwrap()).unwrap();

            assertThat(manifest.chunkCount()).isEqualTo(1);
            assertThat(manifest.chunkSize()).isEqualTo(CHUNK);
            assertThat(manifest.totalSize()).isEqualTo(CHUNK);
            assertThat(manifest.sha256()).isEqualTo(info.meta().digest().hexString());
        }

        @Test
        void aManyChunkObject_listsItsChunksInOrder() {
            var data = payload(CHUNK * 5 + 3, 10);
            var info = uploaded(store, data);

            assertThat(fixture.chunksOf(info)).hasSize(6);
            assertThat(info.meta().digest()).isEqualTo(Sha256.sha256(data).unwrap());
        }

        @Test
        void objectIds_roundTripOnlyInTheirCanonicalSpelling() {
            var id = ObjectId.objectId();

            assertThat(id.asString()).hasSize(26);
            assertThat(ObjectId.objectId(id.asString()).unwrap()).isEqualTo(id);
            assertThat(ObjectId.objectId(id.asString().toUpperCase()).isFailure()).isTrue();
            assertThat(ObjectId.objectId(id.asString().substring(1)).isFailure()).isTrue();
        }

        @Test
        void resumeLinkAndCheckpoint_areNotSupportedInLocalMode() {
            var info = uploaded(store, payload(5, 11));

            assertThat(failureOf(store.resume(WriteSessionId.writeSessionId("s")))).isInstanceOf(ContentStoreError.NotSupported.class);
            assertThat(failureOf(store.link(info.ref()))).isInstanceOf(ContentStoreError.NotSupported.class);
            assertThat(failureOf(store.create(ObjectSpec.objectSpec("a/b")).await().unwrap().checkpoint()))
                .isInstanceOf(ContentStoreError.NotSupported.class);
        }
    }

    @Nested
    class NamesAndListing {
        @Test
        void bind_enforcesItsPrecondition_andMovesNamesBetweenObjects() {
            var first = uploaded(store, payload(8, 12));
            var second = uploaded(store, payload(9, 13));
            var name = ObjectName.objectName("latest").unwrap();

            store.bind(name, first.ref().id(), Precondition.nameFree()).await().unwrap();

            assertThat(failureOf(store.bind(name, second.ref().id(), Precondition.nameFree())))
                .isInstanceOf(ContentStoreError.PreconditionFailed.class);
            assertThat(failureOf(store.bind(name, second.ref().id(), Precondition.boundTo(second.ref().id()))))
                .isInstanceOf(ContentStoreError.PreconditionFailed.class);

            store.bind(name, second.ref().id(), Precondition.boundTo(first.ref().id())).await().unwrap();

            assertThat(store.lookup(name, none()).await().unwrap().unwrap().ref().id()).isEqualTo(second.ref().id());
            assertThat(store.stat(first.ref().id()).await().unwrap().unwrap().name()).as("the name left its previous object").isEqualTo(none());
            assertThat(store.stat(second.ref().id()).await().unwrap().unwrap().name().map(ObjectName::value)).isEqualTo(some("latest"));
        }

        @Test
        void bindingASecondName_movesTheObject_andTheFirstNameIsFree() {
            var info = uploaded(store, payload(8, 14));
            var oldName = ObjectName.objectName("old").unwrap();
            var newName = ObjectName.objectName("new").unwrap();

            store.bind(oldName, info.ref().id(), Precondition.nameFree()).await().unwrap();
            store.bind(newName, info.ref().id(), Precondition.nameFree()).await().unwrap();

            assertThat(store.lookup(oldName, none()).await().unwrap()).isEqualTo(none());
            assertThat(store.lookup(newName, none()).await().unwrap().unwrap().ref().id()).isEqualTo(info.ref().id());
        }

        @Test
        void delete_dropsTheNameWithTheObject_andIsIdempotent() {
            var info = uploaded(store, payload(8, 15));
            var name = ObjectName.objectName("gone-soon").unwrap();

            store.bind(name, info.ref().id(), Precondition.nameFree()).await().unwrap();
            store.delete(info.ref().id()).await().unwrap();
            store.delete(info.ref().id()).await().unwrap();

            assertThat(store.stat(info.ref().id()).await().unwrap()).isEqualTo(none());
            assertThat(store.lookup(name, none()).await().unwrap()).isEqualTo(none());
            assertThat(fixture.storage.resolveRef("n/alpha/gone-soon")).as("no name binding is left behind").isEqualTo(none());
        }

        @Test
        void list_pagesInNameOrder_filtersByPrefix_andResumesFromACursor() {
            for (var i = 0; i < 7; i++) {
                var info = uploaded(store, payload(5, 100 + i));
                var name = (i % 2 == 0 ? "docs/" : "img/") + "item-" + i;

                store.bind(ObjectName.objectName(name).unwrap(), info.ref().id(), Precondition.nameFree()).await().unwrap();
            }

            var all = collectPages(none(), 3);
            var docs = collectPages(some("docs/"), 2);

            assertThat(all).hasSize(7).isSorted();
            assertThat(all.getFirst()).isEqualTo("docs/item-0");
            assertThat(docs).containsExactly("docs/item-0", "docs/item-2", "docs/item-4", "docs/item-6");
            assertThat(failureOf(store.list(none(), none(), 0))).isInstanceOf(ContentStoreError.InvalidRange.class);
        }

        @Test
        void list_ofALargeNamespace_resolvesWithoutDeepRecursion() {
            var count = 5000;

            for (var i = 0; i < count; i++) {
                uploaded(store, payload(3, 1000L + i));
            }

            var seen = new java.util.HashSet<ObjectId>();
            Option<Cursor> after = none();

            do {
                var page = store.list(none(), after, 1000).await().unwrap();

                page.items().forEach(info -> seen.add(info.ref().id()));
                after = page.next();
            } while (after.isPresent());

            assertThat(seen).hasSize(count);
        }

        @Test
        void delete_ofAnObjectWithManyChunks_releasesThemAll() {
            var data = payload(CHUNK * 4000, 77);
            var info = upload(store, ObjectSpec.objectSpec("a/b"), data, 4096).await().unwrap();

            assertThat(fixture.chunksOf(info)).hasSize(4000);
            store.delete(info.ref().id()).await().unwrap();
            assertThat(store.stat(info.ref().id()).await().unwrap()).isEqualTo(none());
            assertThat(fixture.metadata.listAllLifecycles().stream().filter(lifecycle -> lifecycle.refCount() > 0).toList()).isEmpty();
        }

        /// A bind whose first step (the name binding) fails leaves the old state visible: nothing was recorded.
        @Test
        void aBindThatStopsBeforeTheBindingIsWritten_leavesTheOldStateVisible() {
            var refuseNames = new java.util.concurrent.atomic.AtomicBoolean();
            var flaky = new V2Fixture(new V2Fixture.RefusingTier(MemoryTier.memoryTier(1 << 20),
                                                                 content -> refuseNames.get() && ContentEnvelope.kindOf(content)
                                                                                                                .filter(kind -> kind == ContentEnvelope.NAME)
                                                                                                                .isPresent()));
            var flakyStore = flaky.store("alpha");
            var info = uploaded(flakyStore, payload(8, 31));
            var newName = ObjectName.objectName("new").unwrap();

            refuseNames.set(true);

            assertThat(failureOf(flakyStore.bind(newName, info.ref().id(), Precondition.nameFree()))).isNotNull();

            assertThat(flakyStore.lookup(newName, none()).await().unwrap()).as("the new name was never bound").isEqualTo(none());
            assertThat(flakyStore.stat(info.ref().id()).await().unwrap().unwrap().name()).as("a hint nothing confirms is not reported")
                                                                                       .isEqualTo(none());
            assertThat(flakyStore.list(none(), none(), 10).await().unwrap().items()).hasSize(1);
        }

        private java.util.List<String> collectPages(Option<String> prefix, int limit) {
            var names = new java.util.ArrayList<String>();
            Option<Cursor> after = none();

            do {
                var page = store.list(prefix, after, limit).await().unwrap();

                assertThat(page.items().size()).isLessThanOrEqualTo(limit);
                page.items().forEach(info -> names.add(info.name().map(ObjectName::value).or("")));
                after = page.next();
            } while (after.isPresent());

            return names;
        }
    }

    private static byte[] slice(byte[] data, int first, int last) {
        return java.util.Arrays.copyOfRange(data, first, last + 1);
    }
}
