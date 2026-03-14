package org.pragmatica.aether.dht;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.serialization.Codec;

/// Notifications for DHT map mutations, broadcast to passive peers.
/// Allows passive nodes (load balancers) to maintain local caches of DHT data
/// without participating in the DHT hash ring.
@Codec
public sealed interface DHTNotification extends ProtocolMessage {
    @Override
    default boolean deliverToPassive() {
        return true;
    }

    /// A key-value pair was put into a DHT map.
    record Put(NodeId sender, byte[] key, byte[] value) implements DHTNotification {
        public Put {
            key = key.clone();
            value = value.clone();
        }
    }

    /// A key was removed from a DHT map.
    record Removed(NodeId sender, byte[] key) implements DHTNotification {
        public Removed {
            key = key.clone();
        }
    }
}
