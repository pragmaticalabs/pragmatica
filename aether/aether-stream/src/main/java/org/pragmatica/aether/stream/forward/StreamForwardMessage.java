package org.pragmatica.aether.stream.forward;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.serialization.Codec;

import java.util.Arrays;


/// Protocol messages for stream publish forwarding between nodes.
///
/// When a producer publishes to a partition not governed by the local node,
/// the publish is forwarded via QUIC to the governor node that owns the
/// STREAMING task group.
///
/// Flow:
///   - Node A receives publish for stream S, partition P
///   - Node A does not govern S:P, finds governor Node B
///   - Node A sends PublishForward to Node B
///   - Node B calls publishLocal, sends PublishForwardResponse back
///   - Node A resolves the pending promise with offset or error
@Codec public sealed interface StreamForwardMessage extends ProtocolMessage {
    record PublishForward(NodeId sender,
                          String correlationId,
                          String streamName,
                          int partition,
                          byte[] payload,
                          long timestamp) implements StreamForwardMessage {
        public PublishForward {
            payload = payload.clone();
        }

        public static PublishForward publishForward(NodeId sender,
                                                    String correlationId,
                                                    String streamName,
                                                    int partition,
                                                    byte[] payload,
                                                    long timestamp) {
            return new PublishForward(sender, correlationId, streamName, partition, payload, timestamp);
        }

        @Override public byte[] payload() {
            return payload.clone();
        }

        @Override public boolean equals(Object obj) {
            return obj instanceof PublishForward other && sender.equals(other.sender) && correlationId.equals(other.correlationId) && streamName.equals(other.streamName) && partition == other.partition && Arrays.equals(payload,
                                                                                                                                                                                                                           other.payload) && timestamp == other.timestamp;
        }

        @Override public int hashCode() {
            int result = sender.hashCode();
            result = 31 * result + correlationId.hashCode();
            result = 31 * result + streamName.hashCode();
            result = 31 * result + Integer.hashCode(partition);
            result = 31 * result + Arrays.hashCode(payload);
            result = 31 * result + Long.hashCode(timestamp);
            return result;
        }
    }

    record PublishForwardResponse(NodeId sender,
                                  String correlationId,
                                  boolean success,
                                  long offset,
                                  String errorMessage) implements StreamForwardMessage {
        public static PublishForwardResponse successResponse(NodeId sender, String correlationId, long offset) {
            return new PublishForwardResponse(sender, correlationId, true, offset, "");
        }

        public static PublishForwardResponse failureResponse(NodeId sender, String correlationId, String errorMessage) {
            return new PublishForwardResponse(sender, correlationId, false, - 1L, errorMessage);
        }
    }
}
