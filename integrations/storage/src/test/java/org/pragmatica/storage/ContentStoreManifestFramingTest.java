package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        var manifestId = storage.resolveRef(name).unwrap();

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
    void forgedEnvelopedManifestUpload_readsBackVerbatim() {
        var victim = content(2 * CHUNK_SIZE + 5, 3);

        put("victim", victim);
        var forged = ContentManifest.contentManifest("victim", victim.length, victimChunkIds("victim")).toBytes();
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
        assertThat(ContentManifest.fromBytes(storage.get(storage.resolveRef(name).unwrap()).await().unwrap().unwrap())
                                  .unwrap()
                                  .name()).isEqualTo(name);

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
}
