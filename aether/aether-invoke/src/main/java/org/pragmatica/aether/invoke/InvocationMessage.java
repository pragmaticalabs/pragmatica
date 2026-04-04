package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.Codec;
import org.pragmatica.serialization.CodecFor;


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
@Codec@CodecFor(MethodName.class) public sealed interface InvocationMessage extends ProtocolMessage {
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

    record InvokeResponse(NodeId sender, String correlationId, String requestId, boolean success, byte[] payload) implements InvocationMessage {
        public static InvokeResponse invokeResponse(NodeId sender,
                                                    String correlationId,
                                                    String requestId,
                                                    boolean success,
                                                    byte[] payload) {
            return new InvokeResponse(sender, correlationId, requestId, success, payload);
        }
    }
}
