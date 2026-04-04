package org.pragmatica.aether.dht;

import org.pragmatica.lang.Contract;
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
    Promise<Unit> put(K key, V value);
    Promise<Option<V>> get(K key);
    Promise<Boolean> remove(K key);
    ReplicatedMap<K, V> subscribe(MapSubscription<K, V> subscription);
    @Contract void forEach(BiConsumer<K, V> consumer);
    String name();
}
