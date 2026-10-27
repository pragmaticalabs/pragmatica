package org.pragmatica.storage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.storage.GarbageCollectorConfig.garbageCollectorConfig;
import static org.pragmatica.storage.StorageGarbageCollector.storageGarbageCollector;
import static org.pragmatica.storage.V2Fixture.CHUNK;
import static org.pragmatica.storage.V2Fixture.failureOf;
import static org.pragmatica.storage.V2Fixture.feed;
import static org.pragmatica.storage.V2Fixture.payload;
import static org.pragmatica.storage.V2Fixture.read;
import static org.pragmatica.storage.V2Fixture.uploaded;


/// The credits a v2 writer takes and gives back, ported from the byte[] store's pins: #1437 (a write that fails part-way
/// returns exactly the credits it took), #981 (delete and supersede release the blocks, once), #812 (one write-and-ref call
/// per named block). Observed through the lifecycle records and through a real collector cycle, never through a counter
/// kept by the code under test. Chunk ids are computed here from the payload (no compression: a block is its chunk).
class ContentStoreV2CreditsTest {
    private static final byte[] DATA = payload(CHUNK * 6 + 5, 21);

    private static List<BlockId> chunkIdsOf(byte[] data) {
        var ids = new ArrayList<BlockId>();

        for (var from = 0; from < data.length; from += CHUNK) {
            ids.add(BlockId.blockId(Arrays.copyOfRange(data, from, Math.min(data.length, from + CHUNK))).unwrap());
        }

        return ids;
    }

    private static void assertCredits(V2Fixture fixture, List<BlockId> ids, int expected, String what) {
        assertThat(ids.stream().map(fixture::refCountOf).toList()).as(what).containsOnly(expected);
    }

    /// #1437. Mutation: abort and rollBack skip the releases.
    @Nested
    class WriterGivesCreditsBack {
        @Test
        void abort_afterPartialWrite_releasesEveryBlockItStored() {
            var fixture = V2Fixture.v2Fixture();
            var writer = fixture.store("alpha").create(ObjectSpec.objectSpec("a/b")).await().unwrap();

            feed(writer, DATA, 0, 10).await().unwrap();

            var taken = chunkIdsOf(Arrays.copyOf(DATA, CHUNK * 6));

            writer.abort().await().unwrap();

            assertThat(fixture.metadata.listAllLifecycles()).as("locator: the writer had stored blocks before the abort").hasSize(6);
            assertCredits(fixture, taken, 0, "abort must return every credit the writer took");
        }

        @Test
        void aBlockThatFailsToStore_failsTheWrite_andReturnsTheCreditsOfTheOthers() {
            var failing = chunkIdsOf(DATA).get(3);
            var fixture = new V2Fixture(new FaultyTier(MemoryTier.memoryTier(1 << 20), content -> blockIdOf(content).equals(failing)));
            var writer = fixture.store("alpha").create(ObjectSpec.objectSpec("a/b")).await().unwrap();
            var outcome = feed(writer, DATA, 0, 10).flatMap(_ -> writer.commit(none()));

            assertThat(failureOf(outcome)).as("the write fails with the tier's cause").isNotNull();
            writer.abort().await().unwrap();

            assertCredits(fixture, chunkIdsOf(DATA).subList(0, 3), 0, "the blocks stored before the failure gave their credit back");
            assertThat(fixture.refCountOf(failing)).as("the block that failed holds nothing").isLessThanOrEqualTo(0);
            assertThat(fixture.metadata.listAllLifecycles().stream().filter(lifecycle -> lifecycle.refCount() > 0).toList())
                .as("nothing is still credited").isEmpty();
            assertThat(fixture.storage.listRefs("o/alpha/")).as("no entry was written").isEmpty();
        }

        @Test
        void aDigestThatIsNotTheExpectedOne_failsTheCommit_andReleasesTheBlocks() {
            var fixture = V2Fixture.v2Fixture();
            var writer = fixture.store("alpha").create(ObjectSpec.objectSpec("a/b")).await().unwrap();

            feed(writer, DATA, 0, 13).await().unwrap();

            var wrong = Sha256.sha256(new byte[]{1}).unwrap();

            assertThat(failureOf(writer.commit(some(wrong)))).isInstanceOf(ContentStoreError.DigestMismatch.class);
            assertCredits(fixture, chunkIdsOf(DATA), 0, "a refused commit returns every credit");
            assertThat(fixture.storage.listRefs("o/alpha/")).isEmpty();
        }

        @Test
        void aLengthOtherThanDeclared_failsTheCommit_andReleasesTheBlocks() {
            var fixture = V2Fixture.v2Fixture();
            var spec = ObjectSpec.objectSpec(none(), "a/b", some(10L), none(), java.util.Map.of());
            var writer = fixture.store("alpha").create(spec).await().unwrap();

            feed(writer, DATA, 0, 13).await().unwrap();

            assertThat(failureOf(writer.commit(none()))).isInstanceOf(ContentStoreError.LengthMismatch.class);
            assertCredits(fixture, chunkIdsOf(DATA), 0, "a refused commit returns every credit");
        }

        @Test
        void anEntryThatCannotBeWritten_releasesTheManifestAndTheChunks() {
            var refused = new AtomicBoolean(true);
            var fixture = new V2Fixture(new FaultyTier(MemoryTier.memoryTier(1 << 20),
                                                       content -> refused.get() && ContentEnvelope.kindOf(content).filter(kind -> kind == ContentEnvelope.ENTRY).isPresent()));
            var writer = fixture.store("alpha").create(ObjectSpec.objectSpec("a/b")).await().unwrap();

            feed(writer, DATA, 0, 13).await().unwrap();

            assertThat(failureOf(writer.commit(none()))).isNotNull();
            assertCredits(fixture, chunkIdsOf(DATA), 0, "chunks");
            assertThat(fixture.metadata.listAllLifecycles().stream().filter(lifecycle -> lifecycle.refCount() > 0).toList())
                .as("nothing at all is still credited, the manifest included").isEmpty();
        }

        @Test
        void aWriterThatIsCommittedOrAborted_refusesFurtherUse() {
            var fixture = V2Fixture.v2Fixture();
            var writer = fixture.store("alpha").create(ObjectSpec.objectSpec("a/b")).await().unwrap();

            feed(writer, DATA, 0, 13).await().unwrap();
            writer.commit(none()).await().unwrap();

            assertThat(failureOf(writer.commit(none()))).isEqualTo(ContentStoreError.General.WRITER_CLOSED);
            assertThat(failureOf(writer.write(ByteBuffer.wrap(new byte[]{1})))).isEqualTo(ContentStoreError.General.WRITER_CLOSED);
            writer.abort().await().unwrap();
            assertThat(fixture.storage.listRefs("o/alpha/")).as("abort after commit changed nothing").hasSize(1);
        }

        @Test
        void abortedBlocks_areCollectedByARealCollectorCycle() {
            var memory = MemoryTier.memoryTier(1 << 20);
            var fixture = new V2Fixture(memory);
            var gc = storageGarbageCollector(fixture.storage, fixture.metadata, garbageCollectorConfig(0, 500));
            var writer = fixture.store("alpha").create(ObjectSpec.objectSpec("a/b")).await().unwrap();

            feed(writer, DATA, 0, 13).await().unwrap();
            writer.abort().await().unwrap();
            gc.activate();

            assertThat(collectAfterGrace(gc)).as("the collector found the six released full chunks orphaned").isEqualTo(DATA.length / CHUNK);
            assertThat(fixture.storage.get(chunkIdsOf(DATA).getFirst()).await().unwrap()).isEqualTo(none());
        }
    }

    /// #981 and the dedup property: a block two objects share is freed by the last of them, and not before.
    @Nested
    class DeleteReleasesOnce {
        @Test
        void delete_returnsChunksManifestAndEntryCredits() {
            var fixture = V2Fixture.v2Fixture();
            var store = fixture.store("alpha");
            var info = uploaded(store, DATA);
            var entryBlock = fixture.storage.resolveRef("o/alpha/" + info.ref().id().asString()).unwrap();

            assertCredits(fixture, chunkIdsOf(DATA), 1, "locator: one credit per chunk while the object lives");
            assertThat(fixture.refCountOf(info.ref().manifest())).as("locator: manifest").isEqualTo(1);
            assertThat(fixture.refCountOf(entryBlock)).as("locator: entry").isEqualTo(1);

            store.delete(info.ref().id()).await().unwrap();

            assertCredits(fixture, chunkIdsOf(DATA), 0, "chunks");
            assertThat(fixture.refCountOf(info.ref().manifest())).as("manifest").isEqualTo(0);
            assertThat(fixture.refCountOf(entryBlock)).as("entry").isEqualTo(0);
        }

        @Test
        void twoObjectsWithTheSameContent_shareBlocks_andEachDeleteFreesOnlyItsOwnCredit() {
            var fixture = V2Fixture.v2Fixture();
            var store = fixture.store("alpha");
            var first = uploaded(store, DATA);
            var second = uploaded(fixture.store("beta"), DATA);

            assertCredits(fixture, chunkIdsOf(DATA), 2, "locator: two objects, two credits per shared chunk");

            store.delete(first.ref().id()).await().unwrap();

            assertCredits(fixture, chunkIdsOf(DATA), 1, "the other object still holds its credit");
            assertThat(read(fixture.store("beta"), second.ref(), ByteRange.whole())).isEqualTo(DATA);

            fixture.store("beta").delete(second.ref().id()).await().unwrap();

            assertCredits(fixture, chunkIdsOf(DATA), 0, "the last delete frees the blocks");
        }

        @Test
        void twoDeletesOfOneObject_releaseItsBlocksOnce() {
            var fixture = V2Fixture.v2Fixture();
            var store = fixture.store("alpha");
            var info = uploaded(store, DATA);
            var keeper = uploaded(fixture.store("beta"), DATA);

            store.delete(info.ref().id()).await().unwrap();
            store.delete(info.ref().id()).await().unwrap();

            assertCredits(fixture, chunkIdsOf(DATA), 1, "the second delete of the same object released nothing more");
            assertThat(read(fixture.store("beta"), keeper.ref(), ByteRange.whole())).isEqualTo(DATA);
        }

        @Test
        void updateAndBind_supersedeTheEntry_withoutLeakingItsCredit() {
            var fixture = V2Fixture.v2Fixture();
            var store = fixture.store("alpha");
            var info = uploaded(store, DATA);
            var entryRef = "o/alpha/" + info.ref().id().asString();
            var firstEntry = fixture.storage.resolveRef(entryRef).unwrap();

            store.update(info.ref().id(), MetaPatch.metaPatch(none(), some("x/y"), none())).await().unwrap();
            store.bind(ObjectName.objectName("n").unwrap(), info.ref().id(), Precondition.nameFree()).await().unwrap();

            assertThat(fixture.refCountOf(firstEntry)).as("the superseded entry gave back its credit").isEqualTo(0);
            assertThat(fixture.refCountOf(fixture.storage.resolveRef(entryRef).unwrap())).isEqualTo(1);
            assertThat(fixture.refCountOf(fixture.storage.resolveRef("n/alpha/n").unwrap())).as("the name binding holds one credit").isEqualTo(1);

            store.delete(info.ref().id()).await().unwrap();

            assertThat(fixture.metadata.listAllLifecycles().stream().filter(lifecycle -> lifecycle.refCount() > 0).toList())
                .as("after delete nothing is credited any more").isEmpty();
        }
    }

    private static int collectAfterGrace(StorageGarbageCollector gc) {
        var deadline = System.currentTimeMillis() + 20;

        while (System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }

        return gc.collectGarbage();
    }

    private static BlockId blockIdOf(byte[] content) {
        return BlockId.blockId(content).unwrap();
    }

    /// A tier that refuses the blocks `refuse` names and stores the rest.
    private record FaultyTier(StorageTier backing, java.util.function.Predicate<byte[]> refuse) implements StorageTier {
        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return backing.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return refuse.test(content)
                   ? Causes.cause("tier refused block " + id).promise()
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
