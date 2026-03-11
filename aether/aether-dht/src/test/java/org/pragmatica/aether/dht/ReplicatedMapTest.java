package org.pragmatica.aether.dht;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.dht.ReplicatedMapFactory.replicatedMapFactory;

class ReplicatedMapTest {
    private StubDHTClient dhtClient;
    private ReplicatedMap<String, String> map;

    @BeforeEach
    void setUp() {
        dhtClient = new StubDHTClient();
        var factory = replicatedMapFactory(dhtClient);
        map = factory.create(
            "test-map",
            s -> s.getBytes(StandardCharsets.UTF_8),
            b -> new String(b, StandardCharsets.UTF_8),
            s -> s.getBytes(StandardCharsets.UTF_8),
            b -> new String(b, StandardCharsets.UTF_8)
        );
    }

    @Nested
    class PutOperation {
        @Test
        void put_value_delegatesToDht() {
            var result = map.put("key1", "value1").await();

            result.onFailure(_ -> fail("Expected success"));
            assertThat(dhtClient.lastPutKey).startsWith("test-map/".getBytes(StandardCharsets.UTF_8));
            assertThat(dhtClient.lastPutValue).isEqualTo("value1".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void put_value_prefixesKeyWithNamespace() {
            map.put("mykey", "myvalue").await();

            var expectedPrefix = "test-map/".getBytes(StandardCharsets.UTF_8);
            var actualKey = dhtClient.lastPutKey;

            assertThat(actualKey).hasSize(expectedPrefix.length + "mykey".getBytes(StandardCharsets.UTF_8).length);
            // First bytes should be the namespace prefix
            var prefix = new byte[expectedPrefix.length];
            System.arraycopy(actualKey, 0, prefix, 0, expectedPrefix.length);
            assertThat(prefix).isEqualTo(expectedPrefix);
        }
    }

    @Nested
    class GetOperation {
        @Test
        void get_existingKey_returnsValue() {
            dhtClient.valueToReturn = Option.some("found-value".getBytes(StandardCharsets.UTF_8));

            var result = map.get("key1").await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(opt -> assertThat(opt.isPresent()).isTrue());
        }

        @Test
        void get_missingKey_returnsNone() {
            dhtClient.valueToReturn = Option.none();

            var result = map.get("missing").await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(opt -> assertThat(opt.isPresent()).isFalse());
        }

        @Test
        void get_existingKey_deserializesValue() {
            dhtClient.valueToReturn = Option.some("hello".getBytes(StandardCharsets.UTF_8));

            var result = map.get("key1").await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(opt -> opt.onPresent(v -> assertThat(v).isEqualTo("hello")));
        }
    }

    @Nested
    class RemoveOperation {
        @Test
        void remove_existingKey_returnsTrue() {
            dhtClient.removeResult = true;

            var result = map.remove("key1").await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(removed -> assertThat(removed).isTrue());
        }

        @Test
        void remove_missingKey_returnsFalse() {
            dhtClient.removeResult = false;

            var result = map.remove("missing").await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(removed -> assertThat(removed).isFalse());
        }
    }

    @Nested
    class Subscriptions {
        @Test
        void subscribe_onPut_notifiesSubscriber() {
            var putKeys = new CopyOnWriteArrayList<String>();
            var putValues = new CopyOnWriteArrayList<String>();

            MapSubscription<String, String> sub = new MapSubscription<>() {
                @Override
                public void onPut(String key, String value) {
                    putKeys.add(key);
                    putValues.add(value);
                }

                @Override
                public void onRemove(String key) {}
            };

            map.subscribe(sub);
            map.put("k", "v").await();

            assertThat(putKeys).containsExactly("k");
            assertThat(putValues).containsExactly("v");
        }

        @Test
        void subscribe_onRemove_notifiesSubscriber() {
            dhtClient.removeResult = true;
            var removedKeys = new CopyOnWriteArrayList<String>();

            MapSubscription<String, String> sub = new MapSubscription<>() {
                @Override
                public void onPut(String key, String value) {}

                @Override
                public void onRemove(String key) {
                    removedKeys.add(key);
                }
            };

            map.subscribe(sub);
            map.remove("k").await();

            assertThat(removedKeys).containsExactly("k");
        }

        @Test
        void subscribe_removeFalse_doesNotNotify() {
            dhtClient.removeResult = false;
            var removedKeys = new CopyOnWriteArrayList<String>();

            MapSubscription<String, String> sub = new MapSubscription<>() {
                @Override
                public void onPut(String key, String value) {}

                @Override
                public void onRemove(String key) {
                    removedKeys.add(key);
                }
            };

            map.subscribe(sub);
            map.remove("k").await();

            assertThat(removedKeys).isEmpty();
        }

        @Test
        void subscribe_multipleSubscribers_allNotified() {
            var count = new AtomicReference<>(0);

            MapSubscription<String, String> sub1 = new MapSubscription<>() {
                @Override
                public void onPut(String key, String value) {
                    count.updateAndGet(c -> c + 1);
                }

                @Override
                public void onRemove(String key) {}
            };

            MapSubscription<String, String> sub2 = new MapSubscription<>() {
                @Override
                public void onPut(String key, String value) {
                    count.updateAndGet(c -> c + 1);
                }

                @Override
                public void onRemove(String key) {}
            };

            map.subscribe(sub1);
            map.subscribe(sub2);
            map.put("k", "v").await();

            assertThat(count.get()).isEqualTo(2);
        }
    }

    @Nested
    class MapName {
        @Test
        void name_returnsConfiguredName() {
            assertThat(map.name()).isEqualTo("test-map");
        }
    }

    /// Stub DHTClient for testing — stores last operation args.
    static class StubDHTClient implements DHTClient {
        byte[] lastPutKey;
        byte[] lastPutValue;
        Option<byte[]> valueToReturn = Option.none();
        boolean removeResult = false;

        @Override
        public Promise<Option<byte[]>> get(byte[] key) {
            return Promise.success(valueToReturn);
        }

        @Override
        public Promise<Unit> put(byte[] key, byte[] value) {
            lastPutKey = key;
            lastPutValue = value;
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Boolean> remove(byte[] key) {
            return Promise.success(removeResult);
        }

        @Override
        public Promise<Boolean> exists(byte[] key) {
            return Promise.success(valueToReturn.isPresent());
        }

        @Override
        public Partition partitionFor(byte[] key) {
            return null;
        }
    }
}
