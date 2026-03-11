package org.pragmatica.aether.dht;
/// Callback interface for DHT map mutation events.
/// Aggregates local and remote DHT events into typed callbacks.
///
/// @param <K> key type
/// @param <V> value type
public interface MapSubscription<K, V> {
    /// Called when a key-value pair is put (inserted or updated).
    @SuppressWarnings("JBCT-RET-01") // Event callback - void required
    void onPut(K key, V value);

    /// Called when a key is removed.
    @SuppressWarnings("JBCT-RET-01") // Event callback - void required
    void onRemove(K key);
}
