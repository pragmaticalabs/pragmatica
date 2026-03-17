package org.pragmatica.aether.dht;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.function.BiConsumer;

/// Typed, namespace-prefixed DHT map abstraction.
/// Each map has a unique name that prefixes all keys to avoid collision in a shared DHT.
///
/// @param <K> key type
/// @param <V> value type
public interface ReplicatedMap<K, V> {
    /// Put a value into the map.
    Promise<Unit> put(K key, V value);

    /// Get a value from the map.
    Promise<Option<V>> get(K key);

    /// Remove a value from the map.
    Promise<Boolean> remove(K key);

    /// Subscribe to map mutation events.
    ReplicatedMap<K, V> subscribe(MapSubscription<K, V> subscription);

    /// Iterate over all cached entries in the local materialized view.
    @SuppressWarnings("JBCT-RET-01") // Side-effect iteration over local cache
    void forEach(BiConsumer<K, V> consumer);

    /// The map name (namespace prefix).
    String name();
}
