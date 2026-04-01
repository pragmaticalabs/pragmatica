package org.pragmatica.aether.worker.network;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;
import org.pragmatica.serialization.Codec;

import java.util.Arrays;

/// Relay envelope for cross-community DHT messages routed through governors.
/// When a worker needs to send a DHT message to a node in another community,
/// it wraps the serialized payload in this envelope and sends it to the target
/// community's governor. The governor then forwards the relay to the actual
/// target node via cluster network.
@Codec public record DHTRelayMessage( NodeId actualTarget, byte[] serializedPayload) implements Message.Wired {
    public DHTRelayMessage {
        serializedPayload = serializedPayload.clone();
    }

    @Override public byte[] serializedPayload() {
        return serializedPayload.clone();
    }

    @Override public boolean equals(Object o) {
        return o instanceof DHTRelayMessage other &&
        actualTarget.equals(other.actualTarget) &&
        Arrays.equals(serializedPayload, other.serializedPayload);
    }

    @Override public int hashCode() {
        return 31 * actualTarget.hashCode() + Arrays.hashCode(serializedPayload);
    }

    public static DHTRelayMessage dhtRelayMessage(NodeId actualTarget, byte[] serializedPayload) {
        return new DHTRelayMessage(actualTarget, serializedPayload);
    }
}
