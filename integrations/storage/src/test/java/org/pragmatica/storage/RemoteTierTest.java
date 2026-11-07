package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.cloud.aws.s3.S3Client;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

class RemoteTierTest {

    private static final byte[] CONTENT_A = "content-alpha".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_B = "content-bravo".getBytes(StandardCharsets.UTF_8);
    private static final long MAX_BYTES = 1024 * 1024;
    private static final String PREFIX = "test-blocks";

    private InMemoryS3Client mockS3;
    private RemoteTier tier;

    @BeforeEach
    void setUp() {
        mockS3 = new InMemoryS3Client();
        tier = RemoteTier.remoteTier(mockS3, PREFIX, MAX_BYTES);
    }

    private static BlockId blockIdOf(byte[] content) {
        return BlockId.blockId(content)
                      .fold(_ -> { fail("BlockId creation failed"); return null; },
                            id -> id);
    }

    @Nested
    class PutGetTests {

        @Test
        void put_get_roundTrip() {
            var id = blockIdOf(CONTENT_A);

            tier.put(id, CONTENT_A).await()
                .onFailure(c -> fail("put failed: " + c.message()));

            tier.get(id).await()
                .onFailure(c -> fail("get failed: " + c.message()))
                .onSuccess(opt -> {
                    assertThat(opt.isPresent()).isTrue();
                    opt.onPresent(data -> assertThat(data).isEqualTo(CONTENT_A));
                });
        }

        @Test
        void get_nonExistent_returnsNone() {
            var id = blockIdOf(CONTENT_A);

            tier.get(id).await()
                .onFailure(c -> fail("get failed: " + c.message()))
                .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
        }
    }

    @Nested
    class DeleteTests {

        @Test
        void delete_removesBlock() {
            var id = blockIdOf(CONTENT_A);

            tier.put(id, CONTENT_A).await();
            tier.delete(id).await();

            tier.get(id).await()
                .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
        }
    }

    @Nested
    class ExistsTests {

        @Test
        void exists_afterPut_returnsTrue() {
            var id = blockIdOf(CONTENT_A);

            tier.put(id, CONTENT_A).await();

            tier.exists(id).await()
                .onFailure(c -> fail("exists failed: " + c.message()))
                .onSuccess(found -> assertThat(found).isTrue());
        }

        @Test
        void exists_missing_returnsFalse() {
            var id = blockIdOf(CONTENT_A);

            tier.exists(id).await()
                .onFailure(c -> fail("exists failed: " + c.message()))
                .onSuccess(found -> assertThat(found).isFalse());
        }
    }

    @Nested
    class S3KeyTests {

        @Test
        void s3Key_usesShardPrefix() {
            var id = blockIdOf(CONTENT_A);

            tier.put(id, CONTENT_A).await();

            var expectedKey = PREFIX + "/" + id.shardPrefix() + "/" + id.hexString();
            assertThat(mockS3.containsKey(expectedKey)).isTrue();
        }
    }

    @Nested
    class CapacityTests {

        @Test
        void put_exceedsMaxBytes_returnsFailure() {
            var smallTier = RemoteTier.remoteTier(mockS3, PREFIX, 10);
            var id = blockIdOf(CONTENT_A);

            var result = smallTier.put(id, CONTENT_A).await();

            result.onSuccess(_ -> fail("Expected failure for exceeding capacity"));
            result.onFailure(cause -> assertThat(cause).isInstanceOf(StorageError.TierFull.class));
        }

        @Test
        void usedBytes_tracksAfterPut() {
            var id = blockIdOf(CONTENT_A);

            assertThat(tier.usedBytes()).isZero();

            tier.put(id, CONTENT_A).await();
            assertThat(tier.usedBytes()).isEqualTo(CONTENT_A.length);
        }
    }

    @Nested
    class MetadataTests {

        @Test
        void level_returnsRemote() {
            assertThat(tier.level()).isEqualTo(TierLevel.REMOTE);
        }

        @Test
        void maxBytes_returnsConfiguredValue() {
            assertThat(tier.maxBytes()).isEqualTo(MAX_BYTES);
        }
    }

    /// In-memory S3Client implementation for testing. Each PUT and touch stamps the object with [#clock], which a test
    /// advances; LIST pages in ascending key order as S3 does.
    static final class InMemoryS3Client implements S3Client {
        private final ConcurrentSkipListMap<String, byte[]> store = new ConcurrentSkipListMap<>();
        private final ConcurrentHashMap<String, Long> modified = new ConcurrentHashMap<>();
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final AtomicInteger puts = new AtomicInteger();
        /// The key of every PUT, in order.
        final List<String> putKeys = new CopyOnWriteArrayList<>();
        /// The most objects one LIST page returns, whatever `maxKeys` asks: a provider may end a page early.
        final AtomicInteger listPageCap = new AtomicInteger(Integer.MAX_VALUE);

        @Override
        public Promise<Unit> putObject(String key, byte[] content, String contentType) {
            puts.incrementAndGet();
            putKeys.add(key);
            store.put(key, content.clone());
            modified.put(key, clock.get());
            return Promise.success(unit());
        }

        @Override
        public Promise<Option<byte[]>> getObject(String key) {
            return Promise.success(option(store.get(key)).map(byte[]::clone));
        }

        @Override
        public Promise<Boolean> headObject(String key) {
            return Promise.success(store.containsKey(key));
        }

        @Override
        public Promise<Unit> deleteObject(String key) {
            store.remove(key);
            modified.remove(key);
            return Promise.success(unit());
        }

        @Override
        public Promise<List<String>> listObjects(String prefix, int maxKeys) {
            var keys = store.keySet().stream()
                            .filter(k -> k.startsWith(prefix))
                            .limit(maxKeys)
                            .toList();
            return Promise.success(keys);
        }

        @Override
        public Promise<Option<ObjectStat>> statObject(String key) {
            return Promise.success(option(store.get(key)).map(bytes -> ObjectStat.objectStat(key, bytes.length, modified.get(key))));
        }

        @Override
        public Promise<ObjectListing> listObjectStats(String prefix, Option<String> startAfter, int maxKeys) {
            var keys = store.keySet().stream()
                            .filter(k -> k.startsWith(prefix))
                            .filter(k -> startAfter.fold(() -> true, after -> k.compareTo(after) > 0))
                            .toList();
            var page = keys.stream()
                           .limit(Math.min(maxKeys, listPageCap.get()))
                           .map(k -> ObjectStat.objectStat(k, store.get(k).length, modified.get(k)))
                           .toList();
            return Promise.success(ObjectListing.objectListing(page, keys.size() > page.size()));
        }

        boolean containsKey(String key) {
            return store.containsKey(key);
        }

        /// Every key held, in ascending order.
        List<String> keys() {
            return List.copyOf(store.keySet());
        }

        /// The PUTs of keys that are not touch markers: the block writes.
        long blockPuts() {
            return putKeys.stream().filter(key -> !key.endsWith(".touched")).count();
        }
    }
}
