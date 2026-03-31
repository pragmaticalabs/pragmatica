package org.pragmatica.aether.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.TierLevel;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Arrays.copyOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

class DhtStorageTierTest {
    private static final byte[] SAMPLE_CONTENT = "hello distributed world".getBytes();
    private static final String KEY_PREFIX = "test-blocks";

    private InMemoryDHTClient dhtClient;
    private DhtStorageTier tier;

    @BeforeEach
    void setUp() {
        dhtClient = new InMemoryDHTClient();
        tier = DhtStorageTier.dhtStorageTier(dhtClient, KEY_PREFIX);
    }

    @Nested
    class PutAndGet {
        @Test
        void putGet_roundTrip_returnsOriginalContent() {
            var blockId = blockIdOf(SAMPLE_CONTENT);

            tier.put(blockId, SAMPLE_CONTENT)
                .await()
                .onFailure(_ -> fail("put should succeed"));

            tier.get(blockId)
                .await()
                .onFailure(_ -> fail("get should succeed"))
                .onSuccess(opt -> assertThat(opt.isPresent()).isTrue());
        }

        @Test
        void get_nonExistent_returnsNone() {
            var blockId = blockIdOf("nonexistent".getBytes());

            tier.get(blockId)
                .await()
                .onFailure(_ -> fail("get should succeed"))
                .onSuccess(opt -> assertThat(opt.isPresent()).isFalse());
        }
    }

    @Nested
    class ExistsAndDelete {
        @Test
        void exists_afterPut_returnsTrue() {
            var blockId = blockIdOf(SAMPLE_CONTENT);

            tier.put(blockId, SAMPLE_CONTENT).await();

            tier.exists(blockId)
                .await()
                .onFailure(_ -> fail("exists should succeed"))
                .onSuccess(found -> assertThat(found).isTrue());
        }

        @Test
        void exists_beforePut_returnsFalse() {
            var blockId = blockIdOf(SAMPLE_CONTENT);

            tier.exists(blockId)
                .await()
                .onFailure(_ -> fail("exists should succeed"))
                .onSuccess(found -> assertThat(found).isFalse());
        }

        @Test
        void delete_removesBlock() {
            var blockId = blockIdOf(SAMPLE_CONTENT);

            tier.put(blockId, SAMPLE_CONTENT).await();
            tier.delete(blockId).await();

            tier.exists(blockId)
                .await()
                .onFailure(_ -> fail("exists should succeed"))
                .onSuccess(found -> assertThat(found).isFalse());
        }
    }

    @Nested
    class TierProperties {
        @Test
        void level_returnsRemote() {
            assertThat(tier.level()).isEqualTo(TierLevel.REMOTE);
        }

        @Test
        void usedBytes_returnsZero() {
            assertThat(tier.usedBytes()).isZero();
        }

        @Test
        void maxBytes_returnsMaxValue() {
            assertThat(tier.maxBytes()).isEqualTo(Long.MAX_VALUE);
        }
    }

    // --- Helpers ---

    private static BlockId blockIdOf(byte[] content) {
        return BlockId.blockId(content).unwrap();
    }

    /// In-memory DHTClient stub backed by ConcurrentHashMap.
    static final class InMemoryDHTClient implements DHTClient {
        private final ConcurrentHashMap<String, byte[]> store = new ConcurrentHashMap<>();

        @Override
        public Promise<Option<byte[]>> get(byte[] key) {
            return Promise.success(option(store.get(keyString(key))).map(v -> copyOf(v, v.length)));
        }

        @Override
        public Promise<Unit> put(byte[] key, byte[] value) {
            store.put(keyString(key), copyOf(value, value.length));
            return Promise.success(unit());
        }

        @Override
        public Promise<Boolean> remove(byte[] key) {
            return Promise.success(store.remove(keyString(key)) != null);
        }

        @Override
        public Promise<Boolean> exists(byte[] key) {
            return Promise.success(store.containsKey(keyString(key)));
        }

        @Override
        public Partition partitionFor(byte[] key) {
            return null;
        }

        private static String keyString(byte[] key) {
            return new String(key, StandardCharsets.UTF_8);
        }
    }
}
