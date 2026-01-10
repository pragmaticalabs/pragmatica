package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.NodeId;

/**
 * Messages for inter-slice remote invocation.
 *
 * <p>Supports two patterns:
 * <ul>
 *   <li>Fire-and-forget: expectResponse=false</li>
 *   <li>Request-response: expectResponse=true</li>
 * </ul>
 *
 * <p>All invocations carry a requestId for distributed tracing.
 * The requestId remains constant through the entire invocation chain
 * (slice A → slice B → slice C), while correlationId is unique per request/response pair.
 */
public sealed interface InvocationMessage extends ProtocolMessage {
    /**
     * Request to invoke a method on a remote slice.
     *
     * @param sender         Node sending the request
     * @param correlationId  Unique ID for matching request/response
     * @param requestId      Distributed tracing ID (constant through chain)
     * @param targetSlice    The slice to invoke
     * @param method         Method name to call
     * @param payload        Serialized request parameter
     * @param expectResponse Whether caller expects a response
     */
    record InvokeRequest(NodeId sender,
                         String correlationId,
                         String requestId,
                         Artifact targetSlice,
                         MethodName method,
                         byte[] payload,
                         boolean expectResponse) implements InvocationMessage {}

    /**
     * Response from a remote slice invocation.
     *
     * @param sender        Node sending the response
     * @param correlationId Matches the request
     * @param requestId     Distributed tracing ID (echoed from request)
     * @param success       Whether invocation succeeded
     * @param payload       Serialized response (if success) or error message (if failure)
     */
    record InvokeResponse(NodeId sender,
                          String correlationId,
                          String requestId,
                          boolean success,
                          byte[] payload) implements InvocationMessage {}
}
