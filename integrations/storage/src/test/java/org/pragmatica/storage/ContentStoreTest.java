package org.pragmatica.storage;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
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
    private NamedContent store;

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
            store = NamedContent.namedContent(storage, ContentStoreConfig.contentStoreConfig(SMALL_CHUNK_SIZE, Compression.NONE));
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
            store = NamedContent.namedContent(storage, ContentStoreConfig.contentStoreConfig(SMALL_CHUNK_SIZE, Compression.NONE));
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

            // Verify by reading the manifest block the put reports
            var manifestId = BlockId.fromHex(store.put("five-chunks", large).await().unwrap()).unwrap();

            storage.get(manifestId).await()
                   .onFailure(c -> fail("get manifest failed: " + c.message()))
                   .onSuccess(opt -> opt.onPresent(data -> {
                       var manifest = ContentManifest.fromBytes(data);
                       assertThat(manifest.isPresent()).isTrue();
                       manifest.onPresent(m -> assertThat(m.chunkCount()).isEqualTo(5));
                   }));
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
        void put_get_exactlyOneChunk_roundTrips() {
            // Content exactly at chunk size boundary — one chunk
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
            store = NamedContent.namedContent(storage, ContentStoreConfig.contentStoreConfig(SMALL_CHUNK_SIZE, Compression.LZ4));
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
            store = NamedContent.namedContent(storage, ContentStoreConfig.contentStoreConfig(SMALL_CHUNK_SIZE, Compression.LZ4));
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
        private static final String SHA256 = HexFormat.of().formatHex(sequence(32));
        /// marker `00 AHSE 02`, kind `M`, version 2, chunk size 4096, size 12293, sha256 `00..1f`, count 2, ids `aabb` and `010203`.
        private static final String GOLDEN_V2 = "004148534502" + "4d" + "02" + "00001000" + "0000000000003005"
                                                + HexFormat.of().formatHex(sequence(32)) + "00000002" + "00000002aabb" + "00000003010203";

        private static byte[] sequence(int length) {
            var bytes = new byte[length];

            for (var i = 0; i < length; i++) {
                bytes[i] = (byte) i;
            }

            return bytes;
        }

        @Test
        void manifest_serializationRoundTrip() {
            var manifest = ContentManifest.contentManifest(512, 1024, SHA256, List.of("aabb", "ccdd", "eeff"));
            var bytes = manifest.toBytes();
            var parsed = ContentManifest.fromBytes(bytes);

            assertThat(parsed.isPresent()).isTrue();
            parsed.onPresent(m -> {
                assertThat(m.chunkSize()).isEqualTo(512);
                assertThat(m.sha256()).isEqualTo(SHA256);
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
        void manifest_unknownFormatVersion_isRefused() {
            var bytes = ContentManifest.contentManifest(4, 10, SHA256, List.of("ab")).toBytes();

            bytes[ContentEnvelope.marker().length + 1] = ContentManifest.FORMAT_VERSION + 1;

            assertThat(ContentManifest.fromBytes(bytes).isEmpty()).isTrue();
        }

        @Test
        void manifest_namePrefixedFormatOfAnEarlierDevelopmentBuild_isRefused() {
            var name = "file".getBytes(StandardCharsets.UTF_8);
            var id = "ab".getBytes(StandardCharsets.UTF_8);
            var body = ByteBuffer.allocate(Integer.BYTES + name.length + Long.BYTES + Integer.BYTES + Integer.BYTES + id.length)
                                 .putInt(name.length)
                                 .put(name)
                                 .putLong(10)
                                 .putInt(1)
                                 .putInt(id.length)
                                 .put(id)
                                 .array();

            assertThat(ContentManifest.fromBytes(ContentEnvelope.wrap(ContentEnvelope.MANIFEST, body)).isEmpty()).isTrue();
        }

        @Test
        void manifest_carriesNoName_soNothingNeedsEscaping() {
            var bytes = ContentManifest.contentManifest(4, 10, SHA256, List.of("ab")).toBytes();

            // envelope marker (6) + kind (1) + version (1) + chunk size (4) + size (8) + sha256 (32) + count (4)
            // + id length (4) + id (1)
            assertThat(bytes).hasSize(6 + 1 + 1 + 4 + 8 + 32 + 4 + 4 + 1);
        }

        @Test
        void manifest_v2Layout_isPinnedByteForByte_whenEncoded() {
            var manifest = ContentManifest.contentManifest(4096, 12293, SHA256, List.of("aabb", "010203"));

            assertThat(HexFormat.of().formatHex(manifest.toBytes())).isEqualTo(GOLDEN_V2);
        }

        @Test
        void manifest_v2Layout_isPinnedByteForByte_whenDecoded() {
            var parsed = ContentManifest.fromBytes(HexFormat.of().parseHex(GOLDEN_V2));

            assertThat(parsed.isPresent()).isTrue();
            parsed.onPresent(m -> {
                assertThat(m.chunkSize()).isEqualTo(4096);
                assertThat(m.totalSize()).isEqualTo(12293L);
                assertThat(m.sha256()).isEqualTo(SHA256);
                assertThat(m.chunkCount()).isEqualTo(2);
                assertThat(m.chunkBlockIds()).containsExactly("aabb", "010203");
            });
        }

        @Test
        void manifest_withNoChunks_isRefused() {
            var golden = HexFormat.of().parseHex(GOLDEN_V2);
            var countOffset = ContentEnvelope.marker().length + 1 + 1 + 4 + 8 + 32;
            var empty = java.util.Arrays.copyOf(golden, countOffset + 4);

            empty[countOffset + 3] = 0;
            assertThat(ContentManifest.fromBytes(empty).isEmpty()).as("count 0, no ids").isTrue();
        }

        @Test
        void manifest_withTrailingBytes_isRefused() {
            var golden = HexFormat.of().parseHex(GOLDEN_V2);
            var padded = java.util.Arrays.copyOf(golden, golden.length + 1);

            assertThat(ContentManifest.fromBytes(padded).isEmpty()).isTrue();
        }

        /// A v2 payload (no envelope) with one id whose stored length is `idLength`, followed by `tailBytes` zero bytes.
        private static byte[] payloadWithIdLength(int idLength, int tailBytes) {
            return ByteBuffer.allocate(1 + 4 + 8 + 32 + 4 + 4 + tailBytes)
                             .put(ContentManifest.FORMAT_VERSION)
                             .putInt(4096)
                             .putLong(10)
                             .put(sequence(32))
                             .putInt(1)
                             .putInt(idLength)
                             .array();
        }

        private static long allocatedBytesWhile(Runnable action) {
            var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            var threadId = Thread.currentThread().threadId();
            var before = bean.getThreadAllocatedBytes(threadId);

            action.run();

            return bean.getThreadAllocatedBytes(threadId) - before;
        }

        @Test
        void manifest_idLengthPastTheRemainingBytes_isRefusedWithoutAllocatingIt() {
            var eightMiB = 8 * 1024 * 1024;
            var payload = payloadWithIdLength(eightMiB + 1, eightMiB);

            ContentManifest.parseEnveloped(payloadWithIdLength(1, 1));  // warm-up: class loading must not count

            var allocated = allocatedBytesWhile(() -> assertThat(ContentManifest.parseEnveloped(payload).isEmpty()).isTrue());

            assertThat(allocated).as("remaining + 1 refused before any allocation of that length").isLessThan(1024 * 1024);
        }

        @Test
        void manifest_idLengthOfExactlyTheRemainingBytes_isAccepted_asTheControl() {
            var parsed = ContentManifest.parseEnveloped(payloadWithIdLength(3, 3));

            assertThat(parsed.isPresent()).isTrue();
            parsed.onPresent(m -> assertThat(m.chunkBlockIds()).containsExactly("000000"));
        }

        @Test
        void manifest_idLengthPastTheRemainingBytes_isRefused_byOneByte() {
            assertThat(ContentManifest.parseEnveloped(payloadWithIdLength(4, 3)).isEmpty()).isTrue();
            assertThat(ContentManifest.parseEnveloped(payloadWithIdLength(1, 0)).isEmpty()).as("nothing left for the id").isTrue();
        }

        @Test
        void manifest_negativeIdLength_isRefused() {
            assertThat(ContentManifest.parseEnveloped(payloadWithIdLength(-1, 8)).isEmpty()).isTrue();
            assertThat(ContentManifest.parseEnveloped(payloadWithIdLength(Integer.MIN_VALUE, 8)).isEmpty()).isTrue();
        }

        @Test
        void manifest_idLengthOfIntegerMaxValue_isRefusedWithoutAllocating() {
            for (var length : new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE - 8}) {
                var payload = payloadWithIdLength(length, 8);
                var allocated = allocatedBytesWhile(() -> assertThat(ContentManifest.parseEnveloped(payload).isEmpty()).isTrue());

                assertThat(allocated).as("id length " + length).isLessThan(1024 * 1024);
            }
        }

        @Test
        void manifest_truncatedBeforeTheDigest_isRefused() {
            var golden = HexFormat.of().parseHex(GOLDEN_V2);
            var cut = java.util.Arrays.copyOf(golden, ContentEnvelope.marker().length + 1 + 1 + 4 + 8 + 16);

            assertThat(ContentManifest.fromBytes(cut).isEmpty()).isTrue();
        }
    }
}
