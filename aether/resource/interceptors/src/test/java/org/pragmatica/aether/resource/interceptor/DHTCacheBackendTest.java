package org.pragmatica.aether.resource.interceptor;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

class DHTCacheBackendTest {

    private ConcurrentHashMap<String, byte[]> storage;
    private DHTCacheBackend backend;

    @BeforeEach
    void setUp() {
        storage = new ConcurrentHashMap<>();
        var dhtClient = new FakeDHTClient(storage);
        var serializer = new StringSerializer();
        var deserializer = new StringDeserializer();
        backend = DHTCacheBackend.dhtCacheBackend(dhtClient, serializer, deserializer, "test-ns");
    }

    @Test
    void get_existingKey_returnsDeserializedValue() {
        storage.put("test-ns:key1", "hello".getBytes(UTF_8));

        var result = backend.get("key1").await().fold(_ -> Option.none(), v -> v);

        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap()).isEqualTo("hello");
    }

    @Test
    void get_missingKey_returnsNone() {
        var result = backend.get("missing").await().fold(_ -> Option.none(), v -> v);

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void put_value_serializesAndStores() {
        backend.put("key1", "world").await();

        assertThat(storage.containsKey("test-ns:key1")).isTrue();
        assertThat(new String(storage.get("test-ns:key1"), UTF_8)).isEqualTo("world");
    }

    @Test
    void remove_existingKey_removesEntry() {
        storage.put("test-ns:key1", "value".getBytes(UTF_8));

        backend.remove("key1").await();

        assertThat(storage.containsKey("test-ns:key1")).isFalse();
    }

    @Test
    void get_namespacedKeys_isolatesDifferentNamespaces() {
        var otherBackend = DHTCacheBackend.dhtCacheBackend(
            new FakeDHTClient(storage), new StringSerializer(), new StringDeserializer(), "other-ns");

        backend.put("key1", "ns1-value").await();
        otherBackend.put("key1", "ns2-value").await();

        var result1 = backend.get("key1").await().fold(_ -> Option.none(), v -> v);
        var result2 = otherBackend.get("key1").await().fold(_ -> Option.none(), v -> v);

        assertThat(result1.unwrap()).isEqualTo("ns1-value");
        assertThat(result2.unwrap()).isEqualTo("ns2-value");
    }

    @Test
    void put_thenGet_returnsValue() {
        backend.put("round-trip", "serialized-value").await();

        var result = backend.get("round-trip").await().fold(_ -> Option.none(), v -> v);

        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap()).isEqualTo("serialized-value");
    }

    @Test
    void put_nullishKey_handlesGracefully() {
        backend.put("null", "value-for-null-key").await();

        var result = backend.get("null").await().fold(_ -> Option.none(), v -> v);

        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap()).isEqualTo("value-for-null-key");
    }

    @Test
    void remove_nonExistentKey_succeeds() {
        var result = backend.remove("never-existed").await();

        assertThat(result.isSuccess()).isTrue();
    }

    private static final class FakeDHTClient implements DHTClient {
        private final ConcurrentHashMap<String, byte[]> storage;

        FakeDHTClient(ConcurrentHashMap<String, byte[]> storage) {
            this.storage = storage;
        }

        @Override
        public Promise<Option<byte[]>> get(byte[] key) {
            return Promise.success(Option.option(storage.get(new String(key, UTF_8))));
        }

        @Override
        public Promise<Unit> put(byte[] key, byte[] value) {
            storage.put(new String(key, UTF_8), value);
            return Promise.success(unit());
        }

        @Override
        public Promise<Boolean> remove(byte[] key) {
            var removed = storage.remove(new String(key, UTF_8)) != null;
            return Promise.success(removed);
        }

        @Override
        public Promise<Boolean> exists(byte[] key) {
            return Promise.success(storage.containsKey(new String(key, UTF_8)));
        }

        @Override
        public Partition partitionFor(byte[] key) {
            return null;
        }
    }

    private static final class StringSerializer implements Serializer {
        @Override
        public <T> byte[] encode(T object) {
            return object.toString().getBytes(UTF_8);
        }

        @Override
        public <T> void write(ByteBuf byteBuf, T object) {
            byteBuf.writeBytes(object.toString().getBytes(UTF_8));
        }
    }

    private static final class StringDeserializer implements Deserializer {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(byte[] bytes) {
            return (T) new String(bytes, UTF_8);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T read(ByteBuf byteBuf) {
            var bytes = new byte[byteBuf.readableBytes()];
            byteBuf.readBytes(bytes);
            return (T) new String(bytes, UTF_8);
        }
    }
}
