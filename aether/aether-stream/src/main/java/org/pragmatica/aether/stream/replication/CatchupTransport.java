package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;

import java.util.List;

import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse.catchupResponse;

/// Request-response transport for catch-up during failover recovery.
/// Unlike fire-and-forget ReplicationTransport, this returns the response asynchronously.
@FunctionalInterface public interface CatchupTransport {
    /// Send a catch-up request to a replica and await the response.
    Promise<CatchupResponse> requestCatchup(NodeId target, ReplicationMessage.CatchupRequest request);

    /// No-op transport that always returns an empty response.
    CatchupTransport NOOP = (_, request) -> Promise.success(catchupResponse(request.replicaId(),
                                                                            request.streamName(),
                                                                            request.partition(),
                                                                            request.fromOffset(),
                                                                            request.fromOffset(),
                                                                            List.of(),
                                                                            List.of()));
}
