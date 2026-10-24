package org.pragmatica.storage;

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


/// AHSE manifest hazards. A manifest was recognised by sniffing a value for the `AHSE-MANIFEST:` prefix, so an
/// upload beginning with it was read as a manifest (a confused-deputy read of whatever blocks it named) and its delete
/// released those blocks; and `|` was an unescaped separator, so a name containing it broke its own manifest. Now the
/// store's envelope kind byte decides ([ContentEnvelope]), fields are length-prefixed, and legacy manifests stay
/// readable.
class ContentStoreManifestFramingTest {
    private static final int CHUNK_SIZE = 4096;

    private MetadataStore metadataStore;
    private StorageInstance storage;
    private ContentStore store;

    @BeforeEach
    void setUp() {
        metadataStore = MetadataStore.inMemoryMetadataStore("manifest-framing");
        storage = StorageInstance.storageInstance("manifest-framing",
                                                  List.of(MemoryTier.memoryTier(64L * 1024 * 1024)),
                                                  metadataStore);
        store = ContentStore.contentStore(storage, ContentStoreConfig.contentStoreConfig(CHUNK_SIZE, Compression.NONE));
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
        var manifestId = storage.resolveRef(ContentEnvelope.trustedRef(name)).unwrap();

        return ContentManifest.fromBytes(storage.get(manifestId).await().unwrap().unwrap())
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
    void forgedLegacyManifestUpload_readsBackVerbatim_andItsDeleteReleasesNothingElse() {
        var victim = content(3 * CHUNK_SIZE, 7);

        put("victim", victim);
        var chunkIds = victimChunkIds("victim");
        var before = refCounts(chunkIds);
        var forged = (ContentManifest.MAGIC_HEADER + "victim|" + victim.length + "|" + chunkIds.size() + "|"
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
        var forged = ContentManifest.contentManifest(victim.length, victimChunkIds("victim")).toBytes();
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

    /// Values written before the envelope: legacy direct content and a legacy text manifest (one with `|` in its
    /// name too) stay readable.
    @Test
    void legacyValues_stayReadable() {
        var legacyDirect = "written before the envelope".getBytes(StandardCharsets.UTF_8);
        var part1 = content(100, 1);
        var part2 = content(50, 2);

        storage.swapRef("legacy-direct", legacyDirect).await().unwrap();
        var id1 = storage.put(part1).await().unwrap().hexString();
        var id2 = storage.put(part2).await().unwrap().hexString();
        var legacyManifest = (ContentManifest.MAGIC_HEADER + "old|name|150|2|" + id1 + "|" + id2).getBytes(StandardCharsets.UTF_8);

        storage.swapRef("old|name", legacyManifest).await().unwrap();

        assertThat(get("legacy-direct")).isEqualTo(legacyDirect);
        var expected = new byte[150];
        System.arraycopy(part1, 0, expected, 0, 100);
        System.arraycopy(part2, 0, expected, 100, 50);
        assertThat(get("old|name")).isEqualTo(expected);
    }

    /// F7: a legacy value (under a bare name) is never read as an envelope, whatever kind byte follows the marker:
    /// every one of the 256 reads back verbatim.
    @Test
    void legacyValueStartingWithTheMarkerAndAnyKindByte_readsBackVerbatim() {
        var marker = ContentEnvelope.marker();

        for (int kind = 0; kind < 256; kind++) {
            var value = new byte[marker.length + 1 + 5];
            System.arraycopy(marker, 0, value, 0, marker.length);
            value[marker.length] = (byte) kind;
            System.arraycopy(new byte[]{'h', 'e', 'l', 'l', 'o'}, 0, value, marker.length + 1, 5);
            var name = "legacy-" + kind;

            storage.swapRef(name, value).await().unwrap();

            assertThat(get(name)).as("kind byte %d", kind).isEqualTo(value);
        }
    }

    /// F7: a legacy value that is byte-for-byte an enveloped manifest naming another file's chunks reads back verbatim,
    /// and its delete releases nothing of the victim's.
    @Test
    void legacyValueShapedLikeAnEnvelopedManifest_readsBackVerbatim_andItsDeleteReleasesNothingElse() {
        var victim = content(3 * CHUNK_SIZE, 9);

        put("victim", victim);
        var chunkIds = victimChunkIds("victim");
        var before = refCounts(chunkIds);
        var forged = ContentManifest.contentManifest(victim.length, chunkIds).toBytes();

        storage.swapRef("planted", forged).await().unwrap();

        assertThat(get("planted")).isEqualTo(forged);
        store.delete("planted").await().onFailure(c -> fail("delete: " + c.message()));
        assertThat(refCounts(chunkIds)).isEqualTo(before);
        assertThat(get("victim")).isEqualTo(victim);
    }

    /// Overwriting a legacy name moves it to the trusted ref and releases the legacy manifest's chunks.
    @Test
    void overwritingALegacyName_retiresTheLegacyRef_andReleasesItsChunks() {
        var part = content(100, 4);
        var id = storage.put(part).await().unwrap().hexString();
        var legacyManifest = (ContentManifest.MAGIC_HEADER + "old|100|1|" + id).getBytes(StandardCharsets.UTF_8);

        storage.swapRef("old", legacyManifest).await().unwrap();
        put("old", "fresh".getBytes(StandardCharsets.UTF_8));

        assertThat(get("old")).isEqualTo("fresh".getBytes(StandardCharsets.UTF_8));
        assertThat(storage.resolveRef("old").isEmpty()).as("the legacy ref is retired").isTrue();
        assertThat(refCounts(List.of(id))).allMatch(count -> count <= 0);
    }

    /// Deleting a name written before the upgrade retires its legacy ref: the file is no longer readable, and the legacy
    /// manifest's chunks are released.
    @Test
    void deletingALegacyName_retiresTheLegacyRef_andReleasesItsChunks() {
        var part = content(100, 6);
        var id = storage.put(part).await().unwrap().hexString();
        var legacyManifest = (ContentManifest.MAGIC_HEADER + "old|100|1|" + id).getBytes(StandardCharsets.UTF_8);

        storage.swapRef("old", legacyManifest).await().unwrap();
        assertThat(get("old")).as("control: the legacy file is readable before the delete").isEqualTo(part);

        store.delete("old").await().onFailure(c -> fail("delete: " + c.message()));

        assertThat(storage.resolveRef("old").isEmpty()).as("the legacy ref is retired").isTrue();
        assertThat(store.get("old").await().map(Option::isPresent).or(false)).as("a deleted pre-upgrade file is no longer readable")
                                                                       .isFalse();
        assertThat(refCounts(List.of(id))).allMatch(count -> count <= 0);
    }

    /// A name written before the upgrade exists although it has no trusted ref.
    @Test
    void legacyName_exists() {
        storage.swapRef("legacy-only", "written before the envelope".getBytes(StandardCharsets.UTF_8)).await().unwrap();

        assertThat(store.exists("legacy-only").await().unwrap()).isTrue();
        assertThat(store.exists("never-written").await().unwrap()).as("control: an absent name does not exist").isFalse();
    }

    /// A user name beginning with `|` is a different name, never another name's internal ref: `|victim` is the trusted
    /// ref of `victim`, so a legacy read of it would return `victim`'s manifest. Absent, it reads "not found".
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

    /// Writing `|victim` stores its own value and leaves `victim` untouched (the legacy retire step used to drop
    /// `victim`'s trusted ref, as the bare legacy ref of `|victim`).
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

    /// The guard is scoped to names beginning with `|`: a legacy name that merely CONTAINS `|` elsewhere, and a plain
    /// legacy name, are still read, found and retired the legacy way.
    @Test
    void legacyNamesNotBeginningWithAPipe_stillWorkTheLegacyWay() {
        var plain = "plain legacy".getBytes(StandardCharsets.UTF_8);
        var piped = "piped legacy".getBytes(StandardCharsets.UTF_8);

        storage.swapRef("plain", plain).await().unwrap();
        storage.swapRef("a|b", piped).await().unwrap();

        assertThat(get("plain")).isEqualTo(plain);
        assertThat(get("a|b")).isEqualTo(piped);
        assertThat(store.exists("plain").await().unwrap()).isTrue();
        store.delete("plain").await().onFailure(c -> fail("delete: " + c.message()));
        assertThat(storage.resolveRef("plain").isEmpty()).as("the legacy ref is retired").isTrue();
    }

    /// The proof the trusted namespace rests on, for snapshots in the layout written before the `format=2` encoding:
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

    /// The window [ContentEnvelope#trustedRef] documents as accepted pre-GA (a `format=2` snapshot of a dev build that has
    /// the snapshot encoding but not the trusted namespace restores a bare `|x`), and a raw [StorageInstance] writer,
    /// can both leave a LEGACY text manifest under a trusted ref. The trusted path must not read it as a manifest:
    /// legacy magic is believed only for a bare name.
    private List<String> storeRawChunks(byte[] data) {
        var ids = new java.util.ArrayList<String>();

        for (var offset = 0; offset < data.length; offset += CHUNK_SIZE) {
            var chunk = java.util.Arrays.copyOfRange(data, offset, Math.min(offset + CHUNK_SIZE, data.length));

            ids.add(storage.put(chunk).await().unwrap().hexString());
        }

        return ids;
    }

    private static byte[] legacyText(String name, long size, List<String> ids) {
        return (ContentManifest.MAGIC_HEADER + name + "|" + size + "|" + ids.size() + "|" + String.join("|", ids)).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void legacyTextManifestUnderATrustedRef_isNotReadAsAManifest() {
        var data = content(3 * CHUNK_SIZE, 4);
        var ids = storeRawChunks(data);

        storage.swapRef("|win", legacyText("|win", data.length, ids)).await().unwrap();

        assertThat(store.get("win").await().isFailure()).as("a legacy value under a trusted ref is not a manifest").isTrue();
    }

    @Test
    void legacyTextManifestUnderATrustedRef_releasesNothingOnDelete() {
        var data = content(3 * CHUNK_SIZE, 5);
        var ids = storeRawChunks(data);

        storage.swapRef("|win", legacyText("|win", data.length, ids)).await().unwrap();
        store.delete("win").await().onFailure(c -> fail("delete: " + c.message()));

        assertThat(refCounts(ids)).as("the chunks keep put's credit: the trusted delete read no manifest").containsOnly(1);
    }

    @Test
    void legacyTextManifestUnderABareName_stillReadsAndReleases_asTheControl() {
        var data = content(3 * CHUNK_SIZE, 6);
        var ids = storeRawChunks(data);

        storage.swapRef("old", legacyText("old", data.length, ids)).await().unwrap();

        assertThat(get("old")).as("a legacy name is read through its legacy manifest").isEqualTo(data);
        store.delete("old").await().onFailure(c -> fail("delete: " + c.message()));
        assertThat(refCounts(ids)).as("its delete releases the chunks").containsOnly(0);
    }
}
