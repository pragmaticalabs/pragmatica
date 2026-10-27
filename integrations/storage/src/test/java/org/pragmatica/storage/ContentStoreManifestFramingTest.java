package org.pragmatica.storage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// AHSE manifest hazards. A manifest was once recognised by sniffing a value for the `AHSE-MANIFEST:` prefix, so an
/// upload beginning with it was read as a manifest (a confused-deputy read of whatever blocks it named) and its delete
/// released those blocks; and `|` was an unescaped separator, so a name containing it broke its own manifest. Now an
/// upload is only ever chunk blocks, a manifest is reached only from an object entry the store wrote, fields are
/// length-prefixed, and no caller bytes are read as an envelope.
class ContentStoreManifestFramingTest {
    private static final int CHUNK_SIZE = 4096;

    private MetadataStore metadataStore;
    private StorageInstance storage;
    private NamedContent store;

    @BeforeEach
    void setUp() {
        metadataStore = MetadataStore.inMemoryMetadataStore("manifest-framing");
        storage = StorageInstance.storageInstance("manifest-framing",
                                                  List.of(MemoryTier.memoryTier(64L * 1024 * 1024)),
                                                  metadataStore);
        store = NamedContent.namedContent(storage, ContentStoreConfig.contentStoreConfig(CHUNK_SIZE, Compression.NONE));
    }

    private static byte[] content(int size, int seed) {
        var data = new byte[size];

        for (var i = 0; i < size; i++) {
            data[i] = (byte) ((i * 31 + seed) % 251);
        }

        return data;
    }

    private void put(String name, byte[] data) {
        store.put(name, data).await().onFailure(c -> fail("put " + name + ": " + c.message()));
    }

    private byte[] get(String name) {
        return store.get(name).await().unwrap().unwrap();
    }

    private List<String> victimChunkIds(String name) {
        var info = store.store().lookup(ObjectName.objectName(name).unwrap(), Option.none()).await().unwrap().unwrap();

        return ContentManifest.fromBytes(storage.get(info.ref().manifest()).await().unwrap().unwrap())
                              .unwrap()
                              .chunkBlockIds();
    }

    private List<Integer> refCounts(List<String> hexIds) {
        return hexIds.stream()
                     .map(hex -> metadataStore.getLifecycle(BlockId.fromHex(hex).unwrap())
                                              .map(BlockLifecycle::refCount)
                                              .or(-1))
                     .toList();
    }

    @Test
    void forgedTextManifestUpload_readsBackVerbatim_andItsDeleteReleasesNothingElse() {
        var victim = content(3 * CHUNK_SIZE, 7);

        put("victim", victim);
        var chunkIds = victimChunkIds("victim");
        var before = refCounts(chunkIds);
        var forged = ("AHSE-MANIFEST:victim|" + victim.length + "|" + chunkIds.size() + "|"
                      + String.join("|", chunkIds)).getBytes(StandardCharsets.UTF_8);

        put("forged", forged);

        assertThat(get("forged")).as("an upload is returned as uploaded, never resolved as a manifest").isEqualTo(forged);

        store.delete("forged").await().onFailure(c -> fail("delete: " + c.message()));

        assertThat(refCounts(chunkIds)).as("deleting the forged upload must not release the victim's chunks")
                                       .isEqualTo(before);
        assertThat(get("victim")).isEqualTo(victim);
    }

    @Test
    void forgedV2ManifestUpload_readsBackVerbatim() {
        var victim = content(2 * CHUNK_SIZE + 5, 3);

        put("victim", victim);
        var forged = ContentManifest.contentManifest(CHUNK_SIZE, victim.length, "ab".repeat(32), victimChunkIds("victim")).toBytes();
        var before = refCounts(victimChunkIds("victim"));

        put("forged", forged);

        assertThat(get("forged")).isEqualTo(forged);
        store.delete("forged").await().onFailure(c -> fail("delete: " + c.message()));
        assertThat(refCounts(victimChunkIds("victim"))).isEqualTo(before);
    }

    @Test
    void nameWithPipe_roundTrips_chunked_andDeleteReleasesItsChunks() {
        var name = "reports|2026|q3.bin";
        var data = content(3 * CHUNK_SIZE + 11, 5);

        put(name, data);
        var chunkIds = victimChunkIds(name);

        assertThat(get(name)).isEqualTo(data);

        store.delete(name).await().onFailure(c -> fail("delete: " + c.message()));

        assertThat(refCounts(chunkIds)).as("every chunk's credit is given back").allMatch(count -> count <= 0);
    }

    @Test
    void nameWithPipe_roundTrips_direct() {
        var data = "small|piped|content".getBytes(StandardCharsets.UTF_8);

        put("a|b", data);

        assertThat(get("a|b")).isEqualTo(data);
    }

    /// F7: caller bytes are never read as an envelope, whatever kind byte follows the marker: every one of the 256
    /// uploads reads back verbatim.
    @Test
    void uploadStartingWithTheMarkerAndAnyKindByte_readsBackVerbatim() {
        var marker = ContentEnvelope.marker();

        for (int kind = 0; kind < 256; kind++) {
            var value = new byte[marker.length + 1 + 5];
            System.arraycopy(marker, 0, value, 0, marker.length);
            value[marker.length] = (byte) kind;
            System.arraycopy(new byte[]{'h', 'e', 'l', 'l', 'o'}, 0, value, marker.length + 1, 5);
            var name = "upload-" + kind;

            put(name, value);

            assertThat(get(name)).as("kind byte %d", kind).isEqualTo(value);
        }
    }

    /// A user name beginning with `|` is a different name, never another name's internal ref. Absent, it reads "not found".
    @Test
    void getOfAPipeName_neverReadsAnotherNamesTrustedRef() {
        put("victim", content(3 * CHUNK_SIZE, 11));

        assertThat(store.get("|victim").await().map(Option::isPresent).or(false)).as("|victim was never written").isFalse();
    }

    /// `exists` answers for the name asked, not for the name whose trusted ref it spells.
    @Test
    void existsOfAPipeName_neverSeesAnotherNamesTrustedRef() {
        put("victim", content(100, 12));

        assertThat(store.exists("|victim").await().unwrap()).isFalse();
    }

    /// Writing `|victim` stores its own value and leaves `victim` untouched.
    @Test
    void putOfAPipeName_leavesTheOtherNameReadable() {
        var victim = content(3 * CHUNK_SIZE, 13);
        var other = "other".getBytes(StandardCharsets.UTF_8);

        put("victim", victim);
        put("|victim", other);

        assertThat(get("victim")).isEqualTo(victim);
        assertThat(get("|victim")).isEqualTo(other);
    }

    /// Deleting `|victim` leaves `victim` readable and its chunks credited.
    @Test
    void deleteOfAPipeName_leavesTheOtherNameReadable() {
        var victim = content(3 * CHUNK_SIZE, 14);

        put("victim", victim);
        var before = refCounts(victimChunkIds("victim"));

        store.delete("|victim").await().onFailure(c -> fail("delete: " + c.message()));

        assertThat(get("victim")).isEqualTo(victim);
        assertThat(refCounts(victimChunkIds("victim"))).isEqualTo(before);
    }

    /// The restore path splits a ref line at its first `|`, for snapshots in the layout written before the `format=2` encoding:
    /// the restore path splits a ref line at its FIRST `|`, so a name containing `|` (a trusted ref, or a forged one)
    /// is never restored from such a file.
    @Test
    void legacySnapshot_neverRestoresANameContainingAPipe(@TempDir Path snapshots) throws Exception {
        var id = BlockId.fromHex("ab".repeat(32)).unwrap();
        var digest = MessageDigest.getInstance("SHA-256");

        digest.update("ok".getBytes(StandardCharsets.UTF_8));
        var body = "epoch=1\ntimestamp=1700000000000\nnodeId=legacy-node\ncontentHash="
                   + HexFormat.of().formatHex(digest.digest())
                   + "\n\n# Lifecycles\n\n# Refs\n"
                   + "|victim|" + id.hexString() + "\n"
                   + "planted|x|" + id.hexString() + "\n"
                   + "ok|" + id.hexString() + "\n";

        Files.writeString(snapshots.resolve("snapshot-000001.dat"), body);
        Files.writeString(snapshots.resolve("LATEST"), "snapshot-000001.dat");

        var restored = SnapshotManager.snapshotManager(MetadataStore.inMemoryMetadataStore("legacy-restore"),
                                                       SnapshotConfig.snapshotConfig(snapshots, 100, 600_000, 5, "legacy-node"))
                                      .restoreFromLatest()
                                      .unwrap();

        assertThat(restored.isPresent()).as("control: the legacy file restores").isTrue();
        assertThat(restored.unwrap().refs()).containsOnlyKeys("ok");
    }
    @Test
    void chunkedPut_recordsItsChunkSizeAndTheDigestOfTheWholeContent() throws Exception {
        var data = content(3 * CHUNK_SIZE + 17, 8);

        var manifestId = BlockId.fromHex(store.put("recorded", data).await().unwrap()).unwrap();
        var manifest = ContentManifest.fromBytes(storage.get(manifestId).await().unwrap().unwrap()).unwrap();

        assertThat(manifest.chunkSize()).isEqualTo(CHUNK_SIZE);
        assertThat(manifest.totalSize()).isEqualTo(data.length);
        assertThat(manifest.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
    }

    /// A manifest block whose id length is corrupt fails an open of the object as a manifest parse failure: it is
    /// refused before the id is allocated, and no chunk is read.
    @Test
    void manifestWithACorruptIdLength_failsTheOpenAsAManifestParseFailure() {
        var corrupt = ByteBuffer.allocate(1 + 4 + 8 + 32 + 4 + 4 + 8)
                                .put(ContentManifest.FORMAT_VERSION)
                                .putInt(CHUNK_SIZE)
                                .putLong(10)
                                .put(new byte[32])
                                .putInt(1)
                                .putInt(Integer.MAX_VALUE)
                                .array();
        var manifestBlock = storage.put(ContentEnvelope.wrap(ContentEnvelope.MANIFEST, corrupt)).await().unwrap();
        var ref = ObjectRef.objectRef("default", ObjectId.objectId(), manifestBlock, 10, new Sha256(new byte[Sha256.LENGTH]));

        store.store().open(ref, ByteRange.whole()).await()
             .onSuccess(_ -> fail("a manifest naming an id longer than itself must not be read"))
             .onFailure(cause -> assertThat(cause).isEqualTo(ContentStoreError.General.MANIFEST_PARSE_FAILED));
    }
}
