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
@Codec
public sealed interface HttpForwardMessage extends ProtocolMessage {
    /// Request to forward an HTTP request to another node.
    ///
    /// @param sender        Node forwarding the request
    /// @param correlationId Unique ID for matching request/response
    /// @param requestId     Distributed tracing ID (constant through chain)
    /// @param requestData   Serialized HttpRequestContext
    record HttpForwardRequest(NodeId sender,
                              String correlationId,
                              String requestId,
                              byte[] requestData) implements HttpForwardMessage {}

    /// Response from a forwarded HTTP request.
    ///
    /// @param sender        Node that processed the request
    /// @param correlationId Matches the request
    /// @param requestId     Distributed tracing ID (echoed from request)
    /// @param success       Whether processing succeeded
    /// @param payload       Serialized HttpResponseData (if success) or error message (if failure)
    record HttpForwardResponse(NodeId sender,
                               String correlationId,
                               String requestId,
                               boolean success,
                               byte[] payload) implements HttpForwardMessage {}
}
