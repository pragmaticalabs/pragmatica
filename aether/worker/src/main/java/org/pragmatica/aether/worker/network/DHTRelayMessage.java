package org.pragmatica.aether.worker.network;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.Codec;

/// Relay envelope for cross-community DHT messages routed through governors.
/// When a worker needs to send a DHT message to a node in another community,
/// it wraps the serialized payload in this envelope and sends it to the target
/// community's governor. The governor then forwards the raw payload bytes
/// to the actual target node.
@Codec
public record DHTRelayMessage(NodeId actualTarget, byte[] serializedPayload) {
    public static DHTRelayMessage dhtRelayMessage(NodeId actualTarget, byte[] serializedPayload) {
        return new DHTRelayMessage(actualTarget, serializedPayload);
    }
}
