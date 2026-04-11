package org.pragmatica.aether.stream.forward;

import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.messaging.MessageReceiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Governor-side handler for stream publish forwarding.
///
/// Receives [PublishForward] messages from remote nodes,
/// calls [StreamPartitionManager#publishLocal] to append the event,
/// and sends a [PublishForwardResponse] back to the requesting node.
public interface StreamForwardHandler {
    @MessageReceiver@SuppressWarnings("JBCT-RET-01") void onPublishForward(PublishForward request);

    static StreamForwardHandler streamForwardHandler(NodeId selfNodeId,
                                                     StreamPartitionManager partitionManager,
                                                     StreamForwardTransport transport) {
        return new DefaultStreamForwardHandler(selfNodeId, partitionManager, transport);
    }

    StreamForwardHandler NOOP = _ -> {};
}

final class DefaultStreamForwardHandler implements StreamForwardHandler {
    private static final Logger log = LoggerFactory.getLogger(StreamForwardHandler.class);

    private final NodeId selfNodeId;
    private final StreamPartitionManager partitionManager;
    private final StreamForwardTransport transport;

    DefaultStreamForwardHandler(NodeId selfNodeId,
                                StreamPartitionManager partitionManager,
                                StreamForwardTransport transport) {
        this.selfNodeId = selfNodeId;
        this.partitionManager = partitionManager;
        this.transport = transport;
    }

    @Contract@Override@SuppressWarnings("JBCT-RET-01") public void onPublishForward(PublishForward request) {
        partitionManager.publishLocal(request.streamName(),
                                      request.partition(),
                                      request.payload(),
                                      request.timestamp()).onSuccess(offset -> sendSuccessResponse(request, offset))
                                     .onFailure(cause -> sendFailureResponse(request,
                                                                             cause.message()));
    }

    @Contract private void sendSuccessResponse(PublishForward request, long offset) {
        var response = PublishForwardResponse.successResponse(selfNodeId, request.correlationId(), offset);
        transport.send(request.sender(), response);
        log.trace("Forwarded publish succeeded for {}[{}] correlationId={} offset={}",
                  request.streamName(),
                  request.partition(),
                  request.correlationId(),
                  offset);
    }

    @Contract private void sendFailureResponse(PublishForward request, String errorMessage) {
        var response = PublishForwardResponse.failureResponse(selfNodeId, request.correlationId(), errorMessage);
        transport.send(request.sender(), response);
        log.warn("Forwarded publish failed for {}[{}] correlationId={}: {}",
                 request.streamName(),
                 request.partition(),
                 request.correlationId(),
                 errorMessage);
    }
}
