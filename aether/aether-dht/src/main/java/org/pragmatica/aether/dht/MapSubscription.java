package org.pragmatica.aether.dht;

import org.pragmatica.lang.Contract;


public interface MapSubscription<K, V> {
    @Contract void onPut(K key, V value);
    @Contract void onRemove(K key);
}
