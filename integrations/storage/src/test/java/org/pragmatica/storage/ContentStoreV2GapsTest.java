package org.pragmatica.storage;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.storage.V2Fixture.failureOf;
import static org.pragmatica.storage.V2Fixture.payload;
import static org.pragmatica.storage.V2Fixture.uploaded;


/// Properties of the v2 store that the larger tests leave to chance: the bounds of the value types, the manifest checks, the
/// ordering and paging rules of `list`, the version a read reports, what a crash in the middle of a bind leaves behind, the
/// window and read-ahead bounds, and what a failed writer does on its own.
class ContentStoreV2GapsTest {
    private static ObjectName name(String value) {
        return ObjectName.objectName(value).unwrap();
    }

    private final V2Fixture fixture = V2Fixture.v2Fixture();
    private final ContentStoreV2 store = fixture.store("alpha");

    // --- value types ---
    @Test
    void anObjectName_isOneToFiveHundredTwelveCharactersWithNoControlCharacter() {
        assertThat(ObjectName.objectName("").isFailure()).as("empty").isTrue();
        assertThat(ObjectName.objectName("a".repeat(512)).isSuccess()).as("512").isTrue();
        assertThat(ObjectName.objectName("a".repeat(513)).isFailure()).as("513").isTrue();
        assertThat(ObjectName.objectName("a\u0007b").isFailure()).as("control").isTrue();
    }

    @Test
    void anObjectId_parsesOnlyItsOwnSpelling_andCopiesItsBytes() {
        var text = ObjectId.objectId().asString();
        var lastDiffers = text.substring(0, 25) + "b";
        var raw = new byte[ObjectId.LENGTH];

        Arrays.fill(raw, (byte) 1);

        var id = new ObjectId(raw);

        raw[0] = 9;
        id.bytes()[1] = 9;

        assertThat(ObjectId.objectId(text).isSuccess()).isTrue();
        assertThat(failureOf(ObjectId.objectId(text.substring(1)))).hasMessageContaining("26 characters");
        assertThat(failureOf(ObjectId.objectId(text + "a"))).hasMessageContaining("26 characters");
        assertThat(failureOf(ObjectId.objectId("1" + text.substring(1)))).hasMessageContaining("unknown character");
        assertThat(ObjectId.objectId("A" + text.substring(1)).isFailure()).isTrue();
        assertThat(ObjectId.objectId(lastDiffers).isFailure()).as("a spelling with stray low bits is not canonical").isTrue();
        assertThat(id.bytes()[0]).isEqualTo((byte) 1);
        assertThat(id.bytes()[1]).isEqualTo((byte) 1);
    }

    @Test
    void aSha256_fromHex_needsExactlySixtyFourHexCharacters() {
        assertThat(Sha256.fromHex("abcd").isFailure()).isTrue();
        assertThat(Sha256.fromHex("00".repeat(32)).isSuccess()).isTrue();
    }

    @Test
    void aNamespace_startsWithALetterOrDigit_andIsAtMostSixtyThreeCharacters() {
        for (var bad : List.of("/lead", "-lead", "_lead", ".lead", "Upper", "a/b", "a".repeat(64))) {
            assertThat(Namespace.namespace(bad).isFailure()).as(bad).isTrue();
        }

        assertThat(Namespace.namespace("a".repeat(63)).isSuccess()).isTrue();
        assertThat(Namespace.namespace("0a.b_c-d").isSuccess()).isTrue();
    }

    @Test
    void theEnvelopeKinds_doNotReadEachOthersBlocks() {
        var entry = ContentEnvelope.wrap(ContentEnvelope.ENTRY, new byte[]{1});
        var binding = ContentEnvelope.wrap(ContentEnvelope.NAME, new byte[]{1});

        assertThat(ContentEnvelope.payloadOf(ContentEnvelope.ENTRY, binding).isEmpty()).isTrue();
        assertThat(ContentEnvelope.payloadOf(ContentEnvelope.NAME, entry).isEmpty()).isTrue();
        assertThat(ContentEnvelope.payloadOf(ContentEnvelope.MANIFEST, entry).isEmpty()).isTrue();
        assertThat(ContentEnvelope.payloadOf(ContentEnvelope.MANIFEST, binding).isEmpty()).isTrue();
        assertThat(ContentEnvelope.payloadOf(ContentEnvelope.ENTRY, entry).isPresent()).isTrue();
    }

    @Test
    void anEntryWithBytesAfterItsLastField_isRefused() {
        var meta = ObjectMeta.objectMeta(none(), "t", 1, new Sha256(new byte[Sha256.LENGTH]), "o", Instant.EPOCH, none(), Map.of());
        var bytes = ObjectEntry.objectEntry(new BlockId(new byte[Sha256.LENGTH]), meta).toBytes().unwrap();

        assertThat(ObjectEntry.fromBytes(bytes).isSuccess()).isTrue();
        assertThat(ObjectEntry.fromBytes(Arrays.copyOf(bytes, bytes.length + 1)).isFailure()).isTrue();
    }

    @Test
    void listRefs_returnsTheReferencesUnderThePrefixOnly() {
        var block = fixture.storage.put(new byte[]{1}).await().unwrap();

        fixture.storage.createRef("o/a/1", block).await().unwrap();
        fixture.storage.createRef("o/a/2", block).await().unwrap();
        fixture.storage.createRef("o/b/1", block).await().unwrap();

        assertThat(fixture.storage.listRefs("o/a/")).containsExactlyInAnyOrder("o/a/1", "o/a/2");
    }

    @Test
    void aStoreIsRefusedForAChunkSizeTheInstanceCannotTake() {
        var config = ContentStoreConfig.contentStoreConfig(fixture.storage.maxBlockBytes() + 1, Compression.NONE, "alpha", 2, 2);

        assertThat(ContentStoreV2.contentStoreV2(fixture.storage, config).isFailure()).isTrue();
    }

    @Test
    void resumeLinkAndCheckpoint_sayWhichOperationIsNotSupported() {
        var info = uploaded(store, payload(20, 1));
        var writer = store.create(ObjectSpec.objectSpec("t")).await().unwrap();

        assertThat(failureOf(store.resume(WriteSessionId.writeSessionId("s"))).message()).contains("resume");
        assertThat(failureOf(store.link(info.ref())).message()).contains("link");
        assertThat(failureOf(writer.checkpoint()).message()).contains("checkpoint");
    }

    // --- reads ---
    @Test
    void aRangeStartingAtTheEnd_isRefused_aReadBufferIsReadOnly_andAClosedStreamIsEmpty() {
        var info = uploaded(store, payload(V2Fixture.CHUNK * 8, 2));
        var stream = store.open(info.ref(), ByteRange.whole()).await().unwrap();

        assertThat(failureOf(store.open(info.ref(), ByteRange.byteRange(V2Fixture.CHUNK * 8, some(V2Fixture.CHUNK * 8L + 5))))).isInstanceOf(ContentStoreError.InvalidRange.class);
        assertThat(stream.next().await().unwrap().or((ByteBuffer) null).isReadOnly()).isTrue();

        stream.close().await().unwrap();

        assertThat(stream.next().await().unwrap().isEmpty()).as("blocks were left, and none is handed out after close").isTrue();
    }

    private Promise<ChunkStream> openCrafted(int chunkSize, long total, List<String> ids) {
        var manifest = ContentManifest.contentManifest(chunkSize, total, "00".repeat(32), ids);
        var manifestId = fixture.storage.put(manifest.toBytes()).await().unwrap();
        var ref = ObjectRef.objectRef("alpha", ObjectId.objectId(), manifestId, total, new Sha256(new byte[Sha256.LENGTH]));

        return store.open(ref, ByteRange.whole());
    }

    @Test
    void aManifestThatContradictsItself_isRefusedWhenOpened() {
        var block = fixture.storage.put(new byte[4]).await().unwrap().hexString();

        assertThat(failureOf(openCrafted(0, 1, List.of(block)))).as("chunk size 0").isEqualTo(ContentStoreError.General.MANIFEST_PARSE_FAILED);
        assertThat(failureOf(openCrafted(4, -4, List.of(block)))).as("negative size").isEqualTo(ContentStoreError.General.MANIFEST_PARSE_FAILED);
        assertThat(failureOf(openCrafted(4, 8, List.of(block)))).as("one block for two chunks").isEqualTo(ContentStoreError.General.MANIFEST_PARSE_FAILED);
    }

    @Test
    void aBlockOfTheWrongLength_failsTheNextThatReachesIt() {
        var shortBlock = fixture.storage.put(new byte[3]).await().unwrap().hexString();

        assertThat(failureOf(openCrafted(4, 4, List.of(shortBlock)).flatMap(ChunkStream::next))).isEqualTo(ContentStoreError.General.MANIFEST_PARSE_FAILED);
    }

    @Test
    void aCompressingStore_writesSmallerBlocksForCompressibleData() {
        var compressing = fixture.store(ContentStoreConfig.contentStoreConfig(1024, Compression.LZ4, "zip", 2, 2));
        var info = uploaded(compressing, new byte[1024]);
        var stored = fixture.storage.get(fixture.chunksOf(info).getFirst()).await().unwrap().unwrap();

        assertThat(stored.length).isLessThan(1024);
    }

    private static class Tier implements StorageTier {
        private final StorageTier backing;
        private final BiFunction<BlockId, byte[], Promise<Unit>> onPut;
        final AtomicInteger gets = new AtomicInteger();

        Tier(StorageTier backing, BiFunction<BlockId, byte[], Promise<Unit>> onPut) {
            this.backing = backing;
            this.onPut = onPut;
        }

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            gets.incrementAndGet();
            return backing.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return onPut.apply(id, content).flatMap(_ -> backing.put(id, content));
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

    private static Tier tier(BiFunction<BlockId, byte[], Promise<Unit>> onPut) {
        return new Tier(MemoryTier.memoryTier(16L * 1024 * 1024), onPut);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aStreamFetchesExactlyReadAheadBlocksBeyondTheOneItServes() {
        var counting = tier((_, _) -> Promise.success(unit()));
        var counted = new V2Fixture(counting);
        var countedStore = counted.store(ContentStoreConfig.contentStoreConfig(V2Fixture.CHUNK, Compression.NONE, "alpha", 2, 2));
        var info = uploaded(countedStore, payload(V2Fixture.CHUNK * 8, 3));
        var stream = countedStore.open(info.ref(), ByteRange.whole()).await().unwrap();
        var before = counting.gets.get();

        stream.next().await().unwrap();

        for (var i = 0; i < 40 && counting.gets.get() - before < 3; i++) {
            pause(50);
        }

        pause(200);

        assertThat(counting.gets.get() - before).as("the served block and two ahead").isEqualTo(3);
    }

    // --- writes ---
    private final List<Promise<Unit>> gates = new CopyOnWriteArrayList<>();
    private final AtomicInteger started = new AtomicInteger();

    /// A fixture whose every block put waits on its own gate, in the order the puts start.
    private V2Fixture gatedFixture() {
        return new V2Fixture(tier((_, _) -> {
            started.incrementAndGet();

            var gate = Promise.<Unit>promise();

            gates.add(gate);

            return gate;
        }));
    }

    @Test
    void aWriter_hasAtMostWriteWindowBlocksBeingStored_andHoldsTheWriteBack() {
        var gated = gatedFixture();
        var writer = gated.store("alpha").create(ObjectSpec.objectSpec("t")).await().unwrap();
        var write = writer.write(ByteBuffer.wrap(payload(V2Fixture.CHUNK * 6, 4)));

        for (var i = 0; i < 40 && started.get() < 2; i++) {
            pause(50);
        }

        pause(200);

        assertThat(started.get()).as("window 2").isEqualTo(2);
        assertThat(write.isResolved()).isFalse();

        gates.forEach(gate -> gate.succeed(unit()));
        pause(200);
        gates.forEach(gate -> gate.succeed(unit()));

        write.await().unwrap();
    }

    private ObjectWriter writerOver(V2Fixture target) {
        return target.store("alpha").create(ObjectSpec.objectSpec("t")).await().unwrap();
    }

    @Test
    void whenTheFirstOfTwoBlocksFails_firstTheFirstFailureIsTheWritersAndAnAbortWaitsForTheOtherBlock() {
        var gated = gatedFixture();
        var writer = writerOver(gated);
        var first = Causes.cause("first");
        var second = Causes.cause("second");

        writer.write(ByteBuffer.wrap(payload(V2Fixture.CHUNK * 2 + 1, 5)));

        for (var i = 0; i < 40 && gates.size() < 2; i++) {
            pause(50);
        }

        gates.get(0).fail(first);
        pause(100);
        gates.get(1).fail(second);
        pause(100);

        assertThat(failureOf(writer.commit(none()))).isEqualTo(first);
    }

    @Test
    void aFailedWriter_failsFurtherWrites_andAbortWaitsForTheBlocksStillBeingStored() {
        var gated = gatedFixture();
        var writer = writerOver(gated);
        var cause = Causes.cause("boom");

        writer.write(ByteBuffer.wrap(payload(V2Fixture.CHUNK * 2 + 1, 6)));

        for (var i = 0; i < 40 && gates.size() < 2; i++) {
            pause(50);
        }

        gates.get(0).fail(cause);
        pause(200);

        var abort = writer.abort();

        pause(200);

        assertThat(failureOf(writer.write(ByteBuffer.wrap(new byte[1])))).isEqualTo(cause);
        assertThat(abort.isResolved()).as("the second block is still being stored").isFalse();

        gates.get(1).succeed(unit());
        abort.await().unwrap();
    }

    @Test
    void aWriterThatFails_givesBackTheCreditsOfItsStoredBlocks_withNoCommitOrAbort() {
        var puts = new AtomicInteger();
        var failing = new V2Fixture(tier((_, _) -> puts.incrementAndGet() == 2
                                                  ? Causes.cause("refused").promise()
                                                  : Promise.success(unit())));
        var data = payload(V2Fixture.CHUNK * 2 + 1, 7);
        var firstBlock = BlockId.blockId(Arrays.copyOf(data, V2Fixture.CHUNK)).unwrap();
        var writer = writerOver(failing);

        writer.write(ByteBuffer.wrap(data));

        for (var i = 0; i < 40 && failing.refCountOf(firstBlock) > 0; i++) {
            pause(50);
        }

        assertThat(failing.refCountOf(firstBlock)).as("the writer released the one block it had stored").isLessThanOrEqualTo(0);
    }

    @Test
    void aFramingFailureAtCommit_failsTheCommit() {
        var codec = new CompressionCodec() {
            @Override
            public Result<byte[]> compress(byte[] data) {
                return Causes.cause("codec").result();
            }

            @Override
            public Result<byte[]> decompress(byte[] data, int originalSize) {
                return Causes.cause("codec").result();
            }
        };
        var config = ContentStoreConfig.contentStoreConfig(V2Fixture.CHUNK, Compression.NONE, "alpha", 2, 2);
        var writer = new LocalObjectWriter((LocalContentStore) store,
                                           fixture.storage,
                                           config,
                                           new ChunkFraming(codec, true),
                                           ObjectSpec.objectSpec("t"),
                                           ObjectId.objectId());

        writer.write(ByteBuffer.wrap(new byte[5])).await().unwrap();

        assertThat(failureOf(writer.commit(none())).message()).isEqualTo("codec");
    }

    // --- names, versions and crashes ---
    private ObjectEntry entryOf(ObjectId id) {
        var block = fixture.storage.resolveRef(((LocalContentStore) store).entryRef(id)).or((BlockId) null);

        return ObjectEntry.fromBytes(fixture.storage.get(block).await().unwrap().unwrap()).unwrap();
    }

    private Option<ObjectId> found(ContentStoreV2 target, ObjectName name) {
        return target.lookup(name, none()).await().unwrap().map(info -> info.ref().id());
    }

    private final List<String> refOps = new ArrayList<>();

    /// A store whose retire of a name binding fails (the crash), and, when `crashAfterBind`, whose bind of a name fails after
    /// the binding was written.
    private ContentStoreV2 crashing(boolean crashAfterBind) {
        var hooked = (StorageInstance) Proxy.newProxyInstance(StorageInstance.class.getClassLoader(),
                                                              new Class<?>[]{StorageInstance.class},
                                                              (_, method, args) -> {
                                                                  try {
                                                                      if (method.getName().equals("dropRef") && args[0] instanceof String ref && ref.startsWith("n/")) {
                                                                          refOps.add("drop " + ref);

                                                                          return Causes.cause("crash").promise();
                                                                      }

                                                                      if (crashAfterBind && method.getName().equals("swapRef") && args[0] instanceof String ref && ref.startsWith("n/")) {
                                                                          return fixture.storage.swapRef(ref, (byte[]) args[1]).flatMap(_ -> Causes.cause("crash").promise());
                                                                      }

                                                                      return method.invoke(fixture.storage, args);
                                                                  } catch (InvocationTargetException e) {
                                                                      throw e.getCause();
                                                                  }
                                                              });

        return ContentStoreV2.contentStoreV2(hooked, ContentStoreConfig.contentStoreConfig(V2Fixture.CHUNK, Compression.NONE, "alpha", 2, 2)).unwrap();
    }

    @Test
    void theVersionOfAnObject_isOneWhenCreated_risesWithEveryChange_andIsTheSameWhicheverWayItIsRead() {
        var created = uploaded(store, payload(20, 8));
        var other = uploaded(store, payload(20, 9));
        var n = name("n");

        assertThat(created.catalogVersion()).isEqualTo(1);

        var bound = store.bind(n, created.ref().id(), Precondition.nameFree()).await().unwrap();

        assertThat(bound.catalogVersion()).isGreaterThan(1);
        assertThat(store.stat(created.ref().id()).await().unwrap().unwrap().catalogVersion()).isEqualTo(bound.catalogVersion());
        assertThat(store.lookup(n, none()).await().unwrap().unwrap().catalogVersion()).isEqualTo(bound.catalogVersion());

        var patched = store.update(created.ref().id(), MetaPatch.metaPatch(none(), some("x/y"), none())).await().unwrap();

        assertThat(patched.catalogVersion()).isEqualTo(bound.catalogVersion() + 1);
        assertThat(store.stat(created.ref().id()).await().unwrap().unwrap().catalogVersion()).isEqualTo(patched.catalogVersion());

        store.bind(n, other.ref().id(), Precondition.unconditional()).await().unwrap();

        var afterSteal = store.stat(created.ref().id()).await().unwrap().unwrap();

        assertThat(afterSteal.name().isEmpty()).as("the name now points at the other object").isTrue();
        assertThat(afterSteal.catalogVersion()).as("dropping an unconfirmed name does not count as a change").isEqualTo(patched.catalogVersion());
    }

    @Test
    void bindingTheNameAnObjectAlreadyHolds_keepsItBound() {
        var info = uploaded(store, payload(20, 10));
        var n = name("n");

        store.bind(n, info.ref().id(), Precondition.nameFree()).await().unwrap();
        store.bind(n, info.ref().id(), Precondition.unconditional()).await().unwrap();

        assertThat(found(store, n)).isEqualTo(some(info.ref().id()));
        assertThat(entryOf(info.ref().id()).alsoBound().isEmpty()).isTrue();
    }

    @Test
    void aFinishedRename_recordsOneNameOnly() {
        var info = uploaded(store, payload(20, 11));

        store.bind(name("one"), info.ref().id(), Precondition.nameFree()).await().unwrap();
        store.bind(name("two"), info.ref().id(), Precondition.nameFree()).await().unwrap();

        assertThat(entryOf(info.ref().id()).name()).isEqualTo(some(name("two")));
        assertThat(entryOf(info.ref().id()).alsoBound().isEmpty()).isTrue();
        assertThat(found(store, name("one")).isEmpty()).isTrue();
    }

    @Test
    void deletingAnObject_leavesAnotherObjectsBindingToAFormerNameAlone() {
        var first = uploaded(store, payload(20, 12));
        var second = uploaded(store, payload(20, 13));
        var n = name("n");

        store.bind(n, first.ref().id(), Precondition.nameFree()).await().unwrap();
        store.bind(n, second.ref().id(), Precondition.unconditional()).await().unwrap();
        store.delete(first.ref().id()).await().unwrap();

        assertThat(found(store, n)).isEqualTo(some(second.ref().id()));
    }

    @Test
    void duringACrashedRename_eachNameIsReportedAsTheNameItWasLookedUpBy() {
        var info = uploaded(store, payload(20, 14));

        store.bind(name("old"), info.ref().id(), Precondition.nameFree()).await().unwrap();
        failureOf(crashing(false).bind(name("new"), info.ref().id(), Precondition.nameFree()));

        assertThat(store.lookup(name("old"), none()).await().unwrap().unwrap().name()).isEqualTo(some(name("old")));
        assertThat(store.lookup(name("new"), none()).await().unwrap().unwrap().name()).isEqualTo(some(name("new")));
    }

    @Test
    void aCrashRightAfterTheNewNameWasBound_thenDelete_leavesNoBinding() {
        var info = uploaded(store, payload(20, 15));

        store.bind(name("old"), info.ref().id(), Precondition.nameFree()).await().unwrap();
        failureOf(crashing(true).bind(name("new"), info.ref().id(), Precondition.nameFree()));
        store.delete(info.ref().id()).await().unwrap();

        assertThat(fixture.storage.resolveRef("n/alpha/new").isEmpty()).as("the intent recorded the new name, so delete retired it").isTrue();
        assertThat(fixture.storage.resolveRef("n/alpha/old").isEmpty()).isTrue();
    }

    @Test
    void aRenameAfterACrashedRename_retiresTheNameTheCrashLeft_andRenamingBackKeepsItBound() {
        var info = uploaded(store, payload(20, 16));
        var id = info.ref().id();

        store.bind(name("old"), id, Precondition.nameFree()).await().unwrap();
        failureOf(crashing(false).bind(name("one"), id, Precondition.nameFree()));
        store.bind(name("two"), id, Precondition.nameFree()).await().unwrap();

        assertThat(found(store, name("old")).isEmpty()).as("the leftover of the crashed rename").isTrue();
        assertThat(found(store, name("one")).isEmpty()).isTrue();
        assertThat(found(store, name("two"))).isEqualTo(some(id));

        var other = uploaded(store, payload(20, 17));
        var otherId = other.ref().id();

        store.bind(name("a"), otherId, Precondition.nameFree()).await().unwrap();
        failureOf(crashing(false).bind(name("b"), otherId, Precondition.nameFree()));
        refOps.clear();
        failureOf(crashing(false).bind(name("a"), otherId, Precondition.unconditional()));

        assertThat(refOps).as("binding the name the crash left as the second name does not retire it first").doesNotContain("drop n/alpha/a");
    }

    @Test
    void aNameBindingOfTheWrongLength_failsTheLookup() {
        fixture.storage.swapRef("n/alpha/x", ContentEnvelope.wrap(ContentEnvelope.NAME, new byte[3])).await().unwrap();

        assertThat(failureOf(store.lookup(name("x"), none()))).isEqualTo(ContentStoreError.General.ENTRY_PARSE_FAILED);
    }

    // --- list ---
    private ObjectInfo named(ContentStoreV2 target, String value, long seed) {
        var info = uploaded(target, payload(20, seed));

        target.bind(name(value), info.ref().id(), Precondition.nameFree()).await().unwrap();

        return info;
    }

    @Test
    void list_takesAPageOfUpToAThousand_matchesTheNamePrefix_andEndsExactlyOnTheLastPage() {
        named(store, "ab", 20);
        named(store, "ba", 21);
        named(store, "bb", 22);

        assertThat(failureOf(store.list(none(), none(), 1001))).isInstanceOf(ContentStoreError.InvalidRange.class);
        assertThat(failureOf(store.list(none(), none(), 0))).isInstanceOf(ContentStoreError.InvalidRange.class);
        assertThat(store.list(none(), none(), 1000).await().isSuccess()).isTrue();
        assertThat(store.list(some("b"), none(), 10).await().unwrap().items()).extracting(info -> info.name().unwrap().value()).containsExactly("ba", "bb");
        assertThat(store.list(none(), none(), 3).await().unwrap().next().isEmpty()).as("three objects, a page of three").isTrue();
        assertThat(store.list(none(), none(), 2).await().unwrap().next().isPresent()).isTrue();
    }

    @Test
    void list_putsUnnamedObjectsFirst_andOrdersANameBeforeTheNamesItIsThePrefixOf() {
        var unnamed = uploaded(store, payload(20, 23));

        named(store, "a", 24);

        assertThat(store.list(none(), none(), 10).await().unwrap().items().getFirst().ref().id()).isEqualTo(unnamed.ref().id());

        for (var i = 0; i < 12; i++) {
            var own = fixture.store("o" + i);

            named(own, "a", 30 + i);
            named(own, "ab", 50 + i);

            assertThat(own.list(none(), none(), 10).await().unwrap().items()).extracting(info -> info.name().unwrap().value()).containsExactly("a", "ab");
        }
    }
}
