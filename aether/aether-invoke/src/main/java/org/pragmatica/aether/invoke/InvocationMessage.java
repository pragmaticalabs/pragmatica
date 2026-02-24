package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.NodeId;

/// Messages for inter-slice remote invocation.
///
///
/// Supports two patterns:
///
///   - Fire-and-forget: expectResponse=false
///   - Request-response: expectResponse=true
///
///
///
/// All invocations carry a requestId for distributed tracing.
/// The requestId remains constant through the entire invocation chain
/// (slice A → slice B → slice C), while correlationId is unique per request/response pair.
///
///
/// Observability fields (RFC-0010):
///
///   - depth: invocation depth in the call chain (0 = entry point)
///   - hops: number of network hops traversed
///   - sampled: whether this request is sampled for detailed tracing
///
public sealed interface InvocationMessage extends ProtocolMessage {
    /// Request to invoke a method on a remote slice.
    ///
    /// @param sender         Node sending the request
    /// @param correlationId  Unique ID for matching request/response
    /// @param requestId      Distributed tracing ID (constant through chain)
    /// @param targetSlice    The slice to invoke
    /// @param method         Method name to call
    /// @param payload        Serialized request parameter
    /// @param expectResponse Whether caller expects a response
    /// @param depth          Invocation depth in the call chain (0 = entry point)
    /// @param hops           Number of network hops traversed
    /// @param sampled        Whether this request is sampled for detailed tracing
    record InvokeRequest(NodeId sender,
                         String correlationId,
                         String requestId,
                         Artifact targetSlice,
                         MethodName method,
                         byte[] payload,
                         boolean expectResponse,
                         int depth,
                         int hops,
                         boolean sampled) implements InvocationMessage {
        /// Factory method following JBCT naming convention.
        public static InvokeRequest invokeRequest(NodeId sender,
                                                  String correlationId,
                                                  String requestId,
                                                  Artifact targetSlice,
                                                  MethodName method,
                                                  byte[] payload,
                                                  boolean expectResponse,
                                                  int depth,
                                                  int hops,
                                                  boolean sampled) {
            return new InvokeRequest(sender,
                                     correlationId,
                                     requestId,
                                     targetSlice,
                                     method,
                                     payload,
                                     expectResponse,
                                     depth,
                                     hops,
                                     sampled);
        }

        /// Backward-compatible factory (defaults: depth=0, hops=0, sampled=false).
        public static InvokeRequest invokeRequest(NodeId sender,
                                                  String correlationId,
                                                  String requestId,
                                                  Artifact targetSlice,
                                                  MethodName method,
                                                  byte[] payload,
                                                  boolean expectResponse) {
            return new InvokeRequest(sender,
                                     correlationId,
                                     requestId,
                                     targetSlice,
                                     method,
                                     payload,
                                     expectResponse,
                                     0,
                                     0,
                                     false);
        }
    }

    /// Response from a remote slice invocation.
    ///
    /// @param sender        Node sending the response
    /// @param correlationId Matches the request
    /// @param requestId     Distributed tracing ID (echoed from request)
    /// @param success       Whether invocation succeeded
    /// @param payload       Serialized response (if success) or error message (if failure)
    record InvokeResponse(NodeId sender,
                          String correlationId,
                          String requestId,
                          boolean success,
                          byte[] payload) implements InvocationMessage {
        /// Factory method following JBCT naming convention. */
        public static InvokeResponse invokeResponse(NodeId sender,
                                                    String correlationId,
                                                    String requestId,
                                                    boolean success,
                                                    byte[] payload) {
            return new InvokeResponse(sender, correlationId, requestId, success, payload);
        }
    }
}
