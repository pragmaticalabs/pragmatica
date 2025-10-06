package org.pragmatica.aether.kvstore;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.message.MessageRouter;
import org.pragmatica.net.serialization.Deserializer;
import org.pragmatica.net.serialization.Serializer;

public class AetherKVStore extends KVStore<AetherKey, AetherValue> {
    public AetherKVStore(MessageRouter router,
                         Serializer serializer,
                         Deserializer deserializer) {
        super(router, serializer, deserializer);
    }
}
