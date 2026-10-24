package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.storage.SnapshotConfig.snapshotConfig;
import static org.pragmatica.storage.SnapshotManager.snapshotManager;

/// Uploads whose names look like another name's trusted ref, or like an escape of it, are never read back as the
/// trusted value of `victim`: not before a restart, not after a `format=2` snapshot is restored into a fresh store,
/// and deleting them releases none of `victim`'s chunks. Each upload is a forged manifest naming `victim`'s chunks.
class ContentStoreTrustedNamespaceRestoreTest {
    private static final int CHUNK = 128;
    private static final List<String> LOOKALIKE_NAMES = List.of("|victim", "||victim", "%7Cvictim", "%7cvictim",
                                                                "%u007Cvictim", "%257Cvictim", "victim|", "a|victim",
                                                                "victim%7C", "%7C%7Cvictim", "victim=", "#victim",
                                                                "victim\n|x");

    @TempDir
    Path tempDir;

    private MemoryTier tier;
    private MetadataStore metadataStore;
    private StorageInstance storage;
    private ContentStore store;

    @BeforeEach
    void setUp() {
        tier = MemoryTier.memoryTier(64L * 1024 * 1024);
        metadataStore = MetadataStore.inMemoryMetadataStore("trusted-namespace");
        storage = StorageInstance.storageInstance("trusted-namespace", List.of(tier), metadataStore);
        store = contentStoreOver(storage);
    }

    @Test
    void lookalikeUploads_neverReadAsTheTrustedRef_beforeOrAfterSnapshotRestore() {
        var victim = content(3 * CHUNK + 7, 1);

        put(store, "victim", victim);
        var victimChunks = chunkIds("victim");
        var uploads = uploadForgeries(victim, victimChunks);
        var before = refCounts(metadataStore, victimChunks);

        assertThat(get(store, "victim")).as("victim intact before restore").isEqualTo(victim);
        uploads.forEach((name, payload) -> assertThat(get(store, name)).as("upload %s verbatim", name).isEqualTo(payload));

        var restoredMetadata = MetadataStore.inMemoryMetadataStore("trusted-namespace-restored");
        var restored = restoreInto(restoredMetadata);

        assertThat(get(restored, "victim")).as("victim intact after restore").isEqualTo(victim);
        uploads.forEach((name, payload) -> assertThat(get(restored, name)).as("upload %s verbatim after restore", name)
                                                                          .isEqualTo(payload));
        LOOKALIKE_NAMES.forEach(name -> restored.delete(name).await().onFailure(c -> fail("delete " + name + ": " + c.message())));
        assertThat(refCounts(restoredMetadata, victimChunks)).as("deleting every lookalike releases none of victim's chunks")
                                                             .isEqualTo(before);
        assertThat(get(restored, "victim")).as("victim intact after the deletes").isEqualTo(victim);
    }

    /// Alternates the two forgery shapes: an enveloped manifest and a legacy text manifest, both naming victim's chunks.
    private Map<String, byte[]> uploadForgeries(byte[] victim, List<String> victimChunks) {
        var enveloped = ContentManifest.contentManifest(victim.length, victimChunks).toBytes();
        var legacy = (ContentManifest.MAGIC_HEADER + "victim|" + victim.length + "|" + victimChunks.size() + "|"
                      + String.join("|", victimChunks)).getBytes(StandardCharsets.UTF_8);
        var uploads = new HashMap<String, byte[]>();

        for (var i = 0; i < LOOKALIKE_NAMES.size(); i++) {
            var payload = i % 2 == 0
                          ? enveloped
                          : legacy;

            uploads.put(LOOKALIKE_NAMES.get(i), payload);
            put(store, LOOKALIKE_NAMES.get(i), payload);
        }

        return uploads;
    }

    private ContentStore restoreInto(MetadataStore restoredMetadata) {
        var config = snapshotConfig(tempDir.resolve("snapshots"), 100, 600_000, 5, "trusted-namespace-node");

        snapshotManager(metadataStore, config).forceSnapshot();
        var snapshot = snapshotManager(restoredMetadata, config).restoreFromLatest().unwrap();

        assertThat(snapshot.isPresent()).as("the snapshot restores").isTrue();
        restoredMetadata.restoreLifecycles(snapshot.unwrap().lifecycles());
        restoredMetadata.restoreRefs(snapshot.unwrap().refs());

        return contentStoreOver(StorageInstance.storageInstance("trusted-namespace-restored", List.of(tier), restoredMetadata));
    }

    private static ContentStore contentStoreOver(StorageInstance instance) {
        return ContentStore.contentStore(instance, ContentStoreConfig.contentStoreConfig(CHUNK, Compression.NONE));
    }

    private static byte[] content(int size, int seed) {
        var data = new byte[size];

        for (var i = 0; i < size; i++) {
            data[i] = (byte) ((i * 31 + seed) % 251);
        }

        return data;
    }

    private static void put(ContentStore target, String name, byte[] data) {
        target.put(name, data).await().onFailure(c -> fail("put " + name + ": " + c.message()));
    }

    private static byte[] get(ContentStore source, String name) {
        return source.get(name).await().unwrap().unwrap();
    }

    private List<String> chunkIds(String name) {
        var id = storage.resolveRef(ContentEnvelope.trustedRef(name)).unwrap();

        return ContentManifest.fromBytes(storage.get(id).await().unwrap().unwrap()).unwrap().chunkBlockIds();
    }

    private static List<Integer> refCounts(MetadataStore source, List<String> hexIds) {
        return hexIds.stream()
                     .map(hex -> source.getLifecycle(BlockId.fromHex(hex).unwrap()).map(BlockLifecycle::refCount).or(-1))
                     .toList();
    }
}
