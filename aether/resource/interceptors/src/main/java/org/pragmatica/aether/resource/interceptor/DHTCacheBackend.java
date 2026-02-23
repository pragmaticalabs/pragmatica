package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.dht.DHTClient;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.pragmatica.lang.Unit.unit;

/// DHT-backed distributed cache backend.
///
/// Stores cache entries in the cluster's distributed hash table.
/// Keys are namespaced to avoid collisions between different cache instances.
/// Values are serialized to bytes for network transport.
final class DHTCacheBackend implements CacheBackend {
    private final DHTClient dhtClient;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final String namespace;

    private DHTCacheBackend(DHTClient dhtClient,
                            Serializer serializer,
                            Deserializer deserializer,
                            String namespace) {
        this.dhtClient = dhtClient;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.namespace = namespace;
    }

    static DHTCacheBackend dhtCacheBackend(DHTClient dhtClient,
                                           Serializer serializer,
                                           Deserializer deserializer,
                                           String namespace) {
        return new DHTCacheBackend(dhtClient, serializer, deserializer, namespace);
    }

    @Override
    public Promise<Option<Object>> get(Object key) {
        return dhtClient.get(namespacedKey(key))
                        .map(opt -> opt.map(bytes -> (Object) deserializer.decode(bytes)));
    }

    @Override
    public Promise<Unit> put(Object key, Object value) {
        var keyBytes = namespacedKey(key);
        var valueBytes = serializer.encode(value);
        return dhtClient.put(keyBytes, valueBytes);
    }

    @Override
    public Promise<Unit> remove(Object key) {
        return dhtClient.remove(namespacedKey(key))
                        .map(_ -> unit());
    }

    private byte[] namespacedKey(Object key) {
        return (namespace + ":" + key).getBytes(UTF_8);
    }
}
