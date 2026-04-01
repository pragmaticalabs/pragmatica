package org.pragmatica.aether.dht;

import org.pragmatica.lang.Contract;

public interface MapSubscription<K, V> {
    /// Called when a key-value pair is put (inserted or updated).
    @Contract void onPut(K key, V value);

    /// Called when a key is removed.
    @Contract void onRemove(K key);
}
