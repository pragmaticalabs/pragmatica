package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class ContentStoreTest {

    private static final long MEMORY_MAX = 64 * 1024 * 1024; // 64 MB
    private static final int SMALL_CHUNK_SIZE = 64; // Small chunks for testing
    private static final byte[] SMALL_CONTENT = "hello content store".getBytes(StandardCharsets.UTF_8);

    private StorageInstance storage;
    private ContentStore store;

    @BeforeEach
    void setUp() {
        storage = StorageInstance.storageInstance("content-test", List.of(MemoryTier.memoryTier(MEMORY_MAX)));
    }

    private static byte[] largeContent(int size) {
        var data = new byte[size];

        for (var i = 0; i < size; i++) {
            data[i] = (byte) (i % 251); // Prime modulus to avoid repetition patterns
        }

        return data;
    }

    @Nested
    class SmallContentTests {

        @BeforeEach
        void setUp() {
            store = ContentStore.contentStore(storage, ContentStoreConfig.contentStoreConfig(SMALL_CHUNK_SIZE, Compression.NONE));
        }

        @Test
        void put_get_smallContent_roundTrips() {
            store.put("doc.txt", SMALL_CONTENT).await()
                 .onFailure(c -> fail("put failed: " + c.message()));

            store.get("doc.txt").await()
                 .onFailure(c -> fail("get failed: " + c.message()))
                 .onSuccess(opt -> {
                     assertThat(opt.isPresent()).isTrue();
                     opt.onPresent(data -> assertThat(data).isEqualTo(SMALL_CONTENT));
                 });
        }

        @Test
        void exists_afterPut_returnsTrue() {
            store.put("exists-test", SMALL_CONTENT).await()
                 .onFailure(c -> fail("put failed: " + c.message()));

            store.exists("exists-test").await()
                 .onFailure(c -> fail("exists failed: " + c.message()))
                 .onSuccess(found -> assertThat(found).isTrue());
        }

        @Test
        void exists_missing_returnsFalse() {
            store.exists("nonexistent").await()
                 .onFailure(c -> fail("exists failed: " + c.message()))
                 .onSuccess(found -> assertThat(found).isFalse());
        }

        @Test
        void get_missing_returnsFailure() {
            store.get("nonexistent").await()
                 .onSuccess(_ -> fail("expected failure for missing content"));
        }

        @Test
        void delete_removesContent() {
            store.put("to-delete", SMALL_CONTENT).await()
                 .onFailure(c -> fail("put failed: " + c.message()));

            store.delete("to-delete").await()
                 .onFailure(c -> fail("delete failed: " + c.message()));

            store.exists("to-delete").await()
                 .onSuccess(found -> assertThat(found).isFalse());
        }

        @Test
        void delete_missingContent_succeeds() {
            store.delete("never-existed").await()
                 .onFailure(c -> fail("delete of missing content should succeed: " + c.message()));
        }

        @Test
        void put_returnsHexId() {
            store.put("hex-test", SMALL_CONTENT).await()
                 .onFailure(c -> fail("put failed: " + c.message()))
                 .onSuccess(id -> {
                     assertThat(id).isNotNull();
                     assertThat(id).matches("[0-9a-f]{64}");
                 });
        }
    }

    @Nested
    class ChunkedContentTests {

        @BeforeEach
        void setUp() {
            store = ContentStore.contentStore(storage, ContentStoreConfig.contentStoreConfig(SMALL_CHUNK_SIZE, Compression.NONE));
        }

        @Test
        void put_get_largeContent_chunkedRoundTrips() {
            var large = largeContent(SMALL_CHUNK_SIZE * 3 + 17); // 3 full chunks + partial

            store.put("large.bin", large).await()
                 .onFailure(c -> fail("put failed: " + c.message()));

            store.get("large.bin").await()
                 .onFailure(c -> fail("get failed: " + c.message()))
                 .onSuccess(opt -> {
                     assertThat(opt.isPresent()).isTrue();
                     opt.onPresent(data -> assertThat(data).isEqualTo(large));
                 });
        }

        @Test
        void chunking_correctChunkCount() {
            var size = SMALL_CHUNK_SIZE * 5; // Exactly 5 chunks
            var large = largeContent(size);

            store.put("five-chunks", large).await()
                 .onFailure(c -> fail("put failed: " + c.message()));

            // Verify by resolving the ref and reading the manifest
            var refOpt = storage.resolveRef("five-chunks");
            assertThat(refOpt.isPresent()).isTrue();

            refOpt.onPresent(blockId ->
                storage.get(blockId).await()
                       .onFailure(c -> fail("get manifest failed: " + c.message()))
                       .onSuccess(opt -> opt.onPresent(data -> {
                           var manifest = ContentManifest.fromBytes(data);
                           assertThat(manifest.isPresent()).isTrue();
                           manifest.onPresent(m -> assertThat(m.chunkCount()).isEqualTo(5));
                       }))
            );
        }

        @Test
        void chunking_reassemblesInOrder() {
            // Content where each chunk has distinguishable data
            var size = SMALL_CHUNK_SIZE * 3;
            var content = new byte[size];

            for (var i = 0; i < size; i++) {
                content[i] = (byte) (i / SMALL_CHUNK_SIZE + 1); // Chunk 1 = all 1s, chunk 2 = all 2s, etc.
            }

            store.put("ordered.bin", content).await()
                 .onFailure(c -> fail("put failed: " + c.message()));

            store.get("ordered.bin").await()
                 .onFailure(c -> fail("get failed: " + c.message()))
                 .onSuccess(opt -> {
                     assertThat(opt.isPresent()).isTrue();
                     opt.onPresent(data -> assertThat(data).isEqualTo(content));
                 });
        }

        @Test
        void delete_chunkedContent_removesAllChunks() {
            var large = largeContent(SMALL_CHUNK_SIZE * 2 + 10);

            store.put("to-delete-chunked", large).await()
                 .onFailure(c -> fail("put failed: " + c.message()));

            store.delete("to-delete-chunked").await()
                 .onFailure(c -> fail("delete failed: " + c.message()));

            store.exists("to-delete-chunked").await()
                 .onSuccess(found -> assertThat(found).isFalse());
        }

        @Test
        void put_get_exactlyOneChunk_treatedAsDirect() {
            // Content exactly at chunk size boundary — stored as single block
            var content = largeContent(SMALL_CHUNK_SIZE);

            store.put("exact-chunk", content).await()
                 .onFailure(c -> fail("put failed: " + c.message()));

            store.get("exact-chunk").await()
                 .onFailure(c -> fail("get failed: " + c.message()))
                 .onSuccess(opt -> {
                     assertThat(opt.isPresent()).isTrue();
                     opt.onPresent(data -> assertThat(data).isEqualTo(content));
                 });
        }
    }

    @Nested
    class CompressionTests {

        @Test
        void put_get_withCompression_roundTrips() {
            store = ContentStore.contentStore(storage, ContentStoreConfig.contentStoreConfig(SMALL_CHUNK_SIZE, Compression.LZ4));
            var content = "compressible content repeated repeated repeated".getBytes(StandardCharsets.UTF_8);

            store.put("compressed.txt", content).await()
                 .onFailure(c -> fail("put failed: " + c.message()));

            store.get("compressed.txt").await()
                 .onFailure(c -> fail("get failed: " + c.message()))
                 .onSuccess(opt -> {
                     assertThat(opt.isPresent()).isTrue();
                     opt.onPresent(data -> assertThat(data).isEqualTo(content));
                 });
        }

        @Test
        void put_get_chunkedWithCompression_roundTrips() {
            store = ContentStore.contentStore(storage, ContentStoreConfig.contentStoreConfig(SMALL_CHUNK_SIZE, Compression.LZ4));
            var large = largeContent(SMALL_CHUNK_SIZE * 3 + 7);

            store.put("compressed-chunked.bin", large).await()
                 .onFailure(c -> fail("put failed: " + c.message()));

            store.get("compressed-chunked.bin").await()
                 .onFailure(c -> fail("get failed: " + c.message()))
                 .onSuccess(opt -> {
                     assertThat(opt.isPresent()).isTrue();
                     opt.onPresent(data -> assertThat(data).isEqualTo(large));
                 });
        }
    }

    @Nested
    class ManifestTests {

        @Test
        void manifest_serializationRoundTrip() {
            var manifest = ContentManifest.contentManifest("test-file", 1024, List.of("aabb", "ccdd", "eeff"));
            var bytes = manifest.toBytes();
            var parsed = ContentManifest.fromBytes(bytes);

            assertThat(parsed.isPresent()).isTrue();
            parsed.onPresent(m -> {
                assertThat(m.name()).isEqualTo("test-file");
                assertThat(m.totalSize()).isEqualTo(1024);
                assertThat(m.chunkCount()).isEqualTo(3);
                assertThat(m.chunkBlockIds()).containsExactly("aabb", "ccdd", "eeff");
            });
        }

        @Test
        void manifest_nonManifestData_returnsNone() {
            var data = "just regular data".getBytes(StandardCharsets.UTF_8);
            var parsed = ContentManifest.fromBytes(data);

            assertThat(parsed.isEmpty()).isTrue();
        }

        @Test
        void manifest_isManifest_detectsMagicHeader() {
            var manifest = ContentManifest.contentManifest("x", 10, List.of("ab"));

            assertThat(ContentManifest.isManifest(manifest.toBytes())).isTrue();
            assertThat(ContentManifest.isManifest("not a manifest".getBytes(StandardCharsets.UTF_8))).isFalse();
        }
    }
}
