package org.pragmatica.aether.dht;

import org.pragmatica.dht.DHTClient;

import java.util.function.Function;

/// Package-private factory creating ReplicatedMap instances backed by a DHTClient.
final class DhtBackedMapFactory implements ReplicatedMapFactory {
    private final DHTClient client;

    DhtBackedMapFactory(DHTClient client) {
        this.client = client;
    }

    @Override public <K, V> ReplicatedMap<K, V> create(String name,
                                                       Function<K, byte[]> keySerializer,
                                                       Function<byte[], K> keyDeserializer,
                                                       Function<V, byte[]> valueSerializer,
                                                       Function<byte[], V> valueDeserializer) {
        return new NamespacedReplicatedMap<>(name,
                                             client,
                                             keySerializer,
                                             keyDeserializer,
                                             valueSerializer,
                                             valueDeserializer);
    }
}
