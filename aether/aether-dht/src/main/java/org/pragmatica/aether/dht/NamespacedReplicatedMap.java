package org.pragmatica.aether.dht;

import org.pragmatica.dht.DHTClient;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Package-private implementation of ReplicatedMap with namespace-prefixed keys.
final class NamespacedReplicatedMap<K, V> implements ReplicatedMap<K, V> {
    private static final Logger log = LoggerFactory.getLogger(NamespacedReplicatedMap.class);

    private final String name;
    private final byte[] namespacePrefix;
    private final DHTClient client;
    private final Function<K, byte[]> keySerializer;
    private final Function<byte[], K> keyDeserializer;
    private final Function<V, byte[]> valueSerializer;
    private final Function<byte[], V> valueDeserializer;
    private final ConcurrentHashMap<K, V> localCache = new ConcurrentHashMap<>();
    private final List<MapSubscription<K, V>> subscriptions = new CopyOnWriteArrayList<>();

    NamespacedReplicatedMap(String name,
                            DHTClient client,
                            Function<K, byte[]> keySerializer,
                            Function<byte[], K> keyDeserializer,
                            Function<V, byte[]> valueSerializer,
                            Function<byte[], V> valueDeserializer) {
        this.name = name;
        this.namespacePrefix = (name + "/").getBytes(StandardCharsets.UTF_8);
        this.client = client;
        this.keySerializer = keySerializer;
        this.keyDeserializer = keyDeserializer;
        this.valueSerializer = valueSerializer;
        this.valueDeserializer = valueDeserializer;
    }

    @Override
    public Promise<Unit> put(K key, V value) {
        return client.put(prefixKey(keySerializer.apply(key)),
                          valueSerializer.apply(value))
                     .onSuccess(_ -> cacheAndNotifyPut(key, value));
    }

    @Override
    public Promise<Option<V>> get(K key) {
        return client.get(prefixKey(keySerializer.apply(key)))
                     .map(opt -> opt.map(valueDeserializer::apply));
    }

    @Override
    public Promise<Boolean> remove(K key) {
        return client.remove(prefixKey(keySerializer.apply(key)))
                     .onSuccess(removed -> cacheRemoveAndNotify(key, removed));
    }

    @Override
    public ReplicatedMap<K, V> subscribe(MapSubscription<K, V> subscription) {
        subscriptions.add(subscription);
        return this;
    }

    @Override
    @SuppressWarnings("JBCT-RET-01") // Side-effect iteration over local cache
    public void forEach(BiConsumer<K, V> consumer) {
        localCache.forEach(consumer);
    }

    @Override
    public String name() {
        return name;
    }

    /// Dispatch a remote DHT put to local subscribers if the key matches this map's namespace.
    /// Returns true if the key was dispatched (prefix matched), false otherwise.
    boolean onRemotePut(byte[] rawKey, byte[] rawValue) {
        if (!startsWith(rawKey, namespacePrefix)) {
            return false;
        }
        var key = keyDeserializer.apply(Arrays.copyOfRange(rawKey, namespacePrefix.length, rawKey.length));
        var value = valueDeserializer.apply(rawValue);
        cacheAndNotifyPut(key, value);
        return true;
    }

    /// Dispatch a remote DHT remove to local subscribers if the key matches this map's namespace.
    boolean onRemoteRemove(byte[] rawKey) {
        if (!startsWith(rawKey, namespacePrefix)) {
            return false;
        }
        var key = keyDeserializer.apply(Arrays.copyOfRange(rawKey, namespacePrefix.length, rawKey.length));
        localCache.remove(key);
        subscriptions.forEach(sub -> safeOnRemove(sub, key));
        return true;
    }

    private static boolean startsWith(byte[] array, byte[] prefix) {
        if (array.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (array[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private byte[] prefixKey(byte[] rawKey) {
        var result = new byte[namespacePrefix.length + rawKey.length];
        System.arraycopy(namespacePrefix, 0, result, 0, namespacePrefix.length);
        System.arraycopy(rawKey, 0, result, namespacePrefix.length, rawKey.length);
        return result;
    }

    @SuppressWarnings("JBCT-RET-01") // Notification side-effect - void required
    private void cacheAndNotifyPut(K key, V value) {
        localCache.put(key, value);
        subscriptions.forEach(sub -> safeOnPut(sub, key, value));
    }

    @SuppressWarnings("JBCT-RET-01") // Notification side-effect - void required
    private void cacheRemoveAndNotify(K key, boolean removed) {
        if (removed) {
            localCache.remove(key);
            subscriptions.forEach(sub -> safeOnRemove(sub, key));
        }
    }

    @SuppressWarnings("JBCT-RET-01") // Notification side-effect - void required
    private void safeOnPut(MapSubscription<K, V> sub, K key, V value) {
        try{
            sub.onPut(key, value);
        } catch (Exception e) {
            log.warn("MapSubscription.onPut failed for map '{}': {}", name, e.getMessage());
        }
    }

    @SuppressWarnings("JBCT-RET-01") // Notification side-effect - void required
    private void safeOnRemove(MapSubscription<K, V> sub, K key) {
        try{
            sub.onRemove(key);
        } catch (Exception e) {
            log.warn("MapSubscription.onRemove failed for map '{}': {}", name, e.getMessage());
        }
    }
}
