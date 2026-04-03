package org.pragmatica.aether.dht;

import org.pragmatica.dht.DHTClient;

import java.util.function.Function;


/// Factory for creating typed ReplicatedMap instances backed by a shared DHTClient.
public interface ReplicatedMapFactory {
    <K, V> ReplicatedMap<K, V> create(String name,
                                      Function<K, byte[]> keySerializer,
                                      Function<byte[], K> keyDeserializer,
                                      Function<V, byte[]> valueSerializer,
                                      Function<byte[], V> valueDeserializer);

    static ReplicatedMapFactory replicatedMapFactory(DHTClient client) {
        return new DhtBackedMapFactory(client);
    }
}
