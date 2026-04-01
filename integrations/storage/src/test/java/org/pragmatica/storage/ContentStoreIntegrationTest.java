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

/// Integration tests for ContentStore through a multi-tier StorageInstance (Memory + LocalDisk).
class ContentStoreIntegrationTest {

    private static final long MEMORY_MAX = 1024 * 1024;
    private static final long DISK_MAX = 10 * 1024 * 1024;
    private static final int CHUNK_SIZE = 128;
    private static final byte[] SMALL_CONTENT = "content-store-integration-test-data".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private StorageInstance storage;

    @BeforeEach
    void setUp() {
        var memoryTier = MemoryTier.memoryTier(MEMORY_MAX);
        var diskTier = LocalDiskTier.localDiskTier(tempDir.resolve("blocks"), DISK_MAX)
                                    .fold(c -> { fail("Disk tier creation failed: " + c.message()); return null; },
                                          t -> t);
        storage = StorageInstance.storageInstance("content-integration", List.of(memoryTier, diskTier));
    }

    private static byte[] generateContent(int size) {
        var data = new byte[size];

        for (var i = 0; i < size; i++) {
            data[i] = (byte) (i % 251);
        }

        return data;
    }

    @Nested
    class SmallContentTests {

        private ContentStore store;

        @BeforeEach
        void setUp() {
            store = ContentStore.contentStore(storage, ContentStoreConfig.contentStoreConfig(CHUNK_SIZE, Compression.NONE));
        }

        @Test
        void putGet_smallContent_singleBlock() {
            store.put("small.txt", SMALL_CONTENT).await()
                 .onFailure(c -> fail("put failed: " + c.message()));

            store.get("small.txt").await()
                 .onFailure(c -> fail("get failed: " + c.message()))
                 .onSuccess(opt -> {
                     assertThat(opt.isPresent()).isTrue();
                     opt.onPresent(data -> assertThat(data).isEqualTo(SMALL_CONTENT));
                 });
        }
    }

    @Nested
    class ChunkedContentTests {

        private ContentStore store;

        @BeforeEach
        void setUp() {
            store = ContentStore.contentStore(storage, ContentStoreConfig.contentStoreConfig(CHUNK_SIZE, Compression.NONE));
        }

        @Test
        void putGet_largeContent_chunked() {
            var large = generateContent(CHUNK_SIZE * 4 + 37);

            store.put("large.bin", large).await()
                 .onFailure(c -> fail("put failed: " + c.message()));

            store.get("large.bin").await()
                 .onFailure(c -> fail("get failed: " + c.message()))
                 .onSuccess(opt -> {
                     assertThat(opt.isPresent()).isTrue();
                     opt.onPresent(data -> assertThat(data).isEqualTo(large));
                 });
        }
    }

    @Nested
    class CompressionTests {

        @Test
        void putGet_withCompression_roundTrips() {
            var store = ContentStore.contentStore(storage, ContentStoreConfig.contentStoreConfig(CHUNK_SIZE, Compression.LZ4));
            var content = "compressible content repeated repeated repeated repeated repeated".getBytes(StandardCharsets.UTF_8);

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
        void putGet_largeCompressed_chunkedRoundTrips() {
            var store = ContentStore.contentStore(storage, ContentStoreConfig.contentStoreConfig(CHUNK_SIZE, Compression.LZ4));
            var large = generateContent(CHUNK_SIZE * 3 + 11);

            store.put("large-compressed.bin", large).await()
                 .onFailure(c -> fail("put failed: " + c.message()));

            store.get("large-compressed.bin").await()
                 .onFailure(c -> fail("get failed: " + c.message()))
                 .onSuccess(opt -> {
                     assertThat(opt.isPresent()).isTrue();
                     opt.onPresent(data -> assertThat(data).isEqualTo(large));
                 });
        }
    }

    @Nested
    class DeleteTests {

        private ContentStore store;

        @BeforeEach
        void setUp() {
            store = ContentStore.contentStore(storage, ContentStoreConfig.contentStoreConfig(CHUNK_SIZE, Compression.NONE));
        }

        @Test
        void delete_removesContentAndChunks() {
            var large = generateContent(CHUNK_SIZE * 3 + 15);

            store.put("to-delete.bin", large).await()
                 .onFailure(c -> fail("put failed: " + c.message()));

            store.delete("to-delete.bin").await()
                 .onFailure(c -> fail("delete failed: " + c.message()));

            store.exists("to-delete.bin").await()
                 .onFailure(c -> fail("exists failed: " + c.message()))
                 .onSuccess(found -> assertThat(found).isFalse());
        }

        @Test
        void delete_singleBlock_removesContent() {
            store.put("single.txt", SMALL_CONTENT).await()
                 .onFailure(c -> fail("put failed: " + c.message()));

            store.delete("single.txt").await()
                 .onFailure(c -> fail("delete failed: " + c.message()));

            store.exists("single.txt").await()
                 .onSuccess(found -> assertThat(found).isFalse());
        }
    }
}
