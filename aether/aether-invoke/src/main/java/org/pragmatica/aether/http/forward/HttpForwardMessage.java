package org.pragmatica.aether.http.forward;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.serialization.Codec;


/// Messages for HTTP request forwarding between nodes.
///
///
/// When a node receives an HTTP request for a slice it doesn't host,
/// it forwards the request to a node that does host the slice.
///
///
/// Flow:
/// <ol>
///   - Node A receives HTTP request for slice S
///   - Node A doesn't host S, finds Node B that does
///   - Node A sends HttpForwardRequest to Node B
///   - Node B processes request, sends HttpForwardResponse back
///   - Node A returns response to original HTTP client
/// </ol>
@Codec public sealed interface HttpForwardMessage extends ProtocolMessage {
    record HttpForwardRequest(NodeId sender, String correlationId, String requestId, byte[] requestData) implements HttpForwardMessage{}

    record HttpForwardResponse(NodeId sender, String correlationId, String requestId, boolean success, byte[] payload) implements HttpForwardMessage{}
}
