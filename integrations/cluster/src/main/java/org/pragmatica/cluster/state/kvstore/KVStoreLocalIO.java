package org.pragmatica.cluster.state.kvstore;

import java.util.List;
import java.util.Map;

import org.pragmatica.messaging.Message;


public sealed interface KVStoreLocalIO extends Message.Local {
    sealed interface Request extends KVStoreLocalIO {
        record Find() implements Request {}
    }

    sealed interface Response extends KVStoreLocalIO {
        record FoundEntries<K extends StructuredKey, V>(List<Map.Entry<K, V>> entries) implements Response {}
    }
}
