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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class NamespacedReplicatedMapTest {
    private static final String NAMESPACE = "test-ns";
    private static final String OTHER_NAMESPACE = "other-ns";

    private StubDHTClient dhtClient;
    private NamespacedReplicatedMap<String, String> map;

    @BeforeEach
    void setUp() {
        dhtClient = new StubDHTClient();
        map = new NamespacedReplicatedMap<>(
            NAMESPACE,
            dhtClient,
            NamespacedReplicatedMapTest::toBytes,
            NamespacedReplicatedMapTest::fromBytes,
            NamespacedReplicatedMapTest::toBytes,
            NamespacedReplicatedMapTest::fromBytes
        );
    }

    @Nested
    class LocalCache {
        @Test
        void put_populatesLocalCache() {
            map.put("key1", "value1").await();

            var entries = collectEntries();

            assertThat(entries).containsExactly("key1=value1");
        }

        @Test
        void onRemotePut_populatesLocalCache() {
            var rawKey = prefixedKey(NAMESPACE, "remote-key");
            var rawValue = toBytes("remote-value");

            map.onRemotePut(rawKey, rawValue);

            var entries = collectEntries();

            assertThat(entries).containsExactly("remote-key=remote-value");
        }

        @Test
        void onRemoteRemove_evictsFromCache() {
            map.put("key1", "value1").await();
            assertThat(collectEntries()).hasSize(1);

            map.onRemoteRemove(prefixedKey(NAMESPACE, "key1"));

            assertThat(collectEntries()).isEmpty();
        }

        @Test
        void forEach_multipleEntries_iteratesAll() {
            map.put("a", "1").await();
            map.put("b", "2").await();
            map.put("c", "3").await();

            var entries = collectEntries();

            assertThat(entries).containsExactlyInAnyOrder("a=1", "b=2", "c=3");
        }

        @Test
        void forEach_emptyMap_noIteration() {
            var count = new AtomicInteger(0);

            map.forEach((_, _) -> count.incrementAndGet());

            assertThat(count.get()).isZero();
        }
    }

    @Nested
    class NamespaceFiltering {
        @Test
        void onRemotePut_matchingNamespace_returnsTrue() {
            var result = map.onRemotePut(prefixedKey(NAMESPACE, "k"), toBytes("v"));

            assertThat(result).isTrue();
        }

        @Test
        void onRemotePut_differentNamespace_returnsFalse() {
            var result = map.onRemotePut(prefixedKey(OTHER_NAMESPACE, "k"), toBytes("v"));

            assertThat(result).isFalse();
            assertThat(collectEntries()).isEmpty();
        }

        @Test
        void onRemoteRemove_matchingNamespace_returnsTrue() {
            map.put("k", "v").await();

            var result = map.onRemoteRemove(prefixedKey(NAMESPACE, "k"));

            assertThat(result).isTrue();
        }

        @Test
        void onRemoteRemove_differentNamespace_returnsFalse() {
            var result = map.onRemoteRemove(prefixedKey(OTHER_NAMESPACE, "k"));

            assertThat(result).isFalse();
        }
    }

    @Nested
    class SubscriptionNotification {
        @Test
        void put_notifiesSubscribers() {
            var recorder = new RecordingSubscription();
            map.subscribe(recorder);

            map.put("k1", "v1").await();

            assertThat(recorder.puts).containsExactly("k1=v1");
        }

        @Test
        void onRemotePut_notifiesSubscribers() {
            var recorder = new RecordingSubscription();
            map.subscribe(recorder);

            map.onRemotePut(prefixedKey(NAMESPACE, "rk"), toBytes("rv"));

            assertThat(recorder.puts).containsExactly("rk=rv");
        }

        @Test
        void onRemoteRemove_notifiesSubscribers() {
            var recorder = new RecordingSubscription();
            map.put("k1", "v1").await();
            map.subscribe(recorder);

            map.onRemoteRemove(prefixedKey(NAMESPACE, "k1"));

            assertThat(recorder.removes).containsExactly("k1");
        }

        @Test
        void put_multipleSubscribers_allNotified() {
            var recorder1 = new RecordingSubscription();
            var recorder2 = new RecordingSubscription();
            map.subscribe(recorder1);
            map.subscribe(recorder2);

            map.put("k1", "v1").await();

            assertThat(recorder1.puts).containsExactly("k1=v1");
            assertThat(recorder2.puts).containsExactly("k1=v1");
        }

        @Test
        void subscriber_throwsException_otherSubscribersStillNotified() {
            var throwing = new ThrowingSubscription();
            var recorder = new RecordingSubscription();
            map.subscribe(throwing);
            map.subscribe(recorder);

            map.put("k1", "v1").await();

            assertThat(recorder.puts).containsExactly("k1=v1");
        }
    }

    @Nested
    class NotificationOrdering {
        @Test
        void put_rapidSequentialWrites_notificationsPreserveWriteOrder() {
            var recorder = new RecordingSubscription();
            map.subscribe(recorder);

            map.put("state", "LOAD").await();
            map.put("state", "LOADING").await();
            map.put("state", "LOADED").await();
            map.put("state", "ACTIVATE").await();
            map.put("state", "ACTIVATING").await();
            map.put("state", "ACTIVE").await();

            assertThat(recorder.puts).containsExactly(
                "state=LOAD",
                "state=LOADING",
                "state=LOADED",
                "state=ACTIVATE",
                "state=ACTIVATING",
                "state=ACTIVE"
            );
        }

        @Test
        void put_rapidSequentialWrites_cacheReflectsFinalValue() {
            map.put("state", "LOAD").await();
            map.put("state", "LOADING").await();
            map.put("state", "LOADED").await();
            map.put("state", "ACTIVATE").await();
            map.put("state", "ACTIVE").await();

            var entries = collectEntries();

            assertThat(entries).containsExactly("state=ACTIVE");
        }

        @Test
        void put_chainedWrites_notificationsInCausalOrder() {
            var recorder = new RecordingSubscription();
            map.subscribe(recorder);

            map.put("key", "first")
               .flatMap(_ -> map.put("key", "second"))
               .flatMap(_ -> map.put("key", "third"))
               .await();

            assertThat(recorder.puts).containsExactly(
                "key=first",
                "key=second",
                "key=third"
            );
        }
    }

    // --- Helpers ---

    private List<String> collectEntries() {
        var entries = new ArrayList<String>();
        map.forEach((k, v) -> entries.add(k + "=" + v));
        return entries;
    }

    private static byte[] toBytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String fromBytes(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] prefixedKey(String namespace, String key) {
        return (namespace + "/" + key).getBytes(StandardCharsets.UTF_8);
    }

    // --- Stubs ---

    static class RecordingSubscription implements MapSubscription<String, String> {
        final CopyOnWriteArrayList<String> puts = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<String> removes = new CopyOnWriteArrayList<>();

        @Override
        public void onPut(String key, String value) {
            puts.add(key + "=" + value);
        }

        @Override
        public void onRemove(String key) {
            removes.add(key);
        }
    }

    static class ThrowingSubscription implements MapSubscription<String, String> {
        @Override
        public void onPut(String key, String value) {
            throw new RuntimeException("intentional test failure");
        }

        @Override
        public void onRemove(String key) {
            throw new RuntimeException("intentional test failure");
        }
    }

    static class StubDHTClient implements DHTClient {
        @Override
        public Promise<Option<byte[]>> get(byte[] key) {
            return Promise.success(Option.none());
        }

        @Override
        public Promise<Unit> put(byte[] key, byte[] value) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Boolean> remove(byte[] key) {
            return Promise.success(true);
        }

        @Override
        public Promise<Boolean> exists(byte[] key) {
            return Promise.success(false);
        }

        @Override
        public Partition partitionFor(byte[] key) {
            return new Partition(0);
        }
    }
}
