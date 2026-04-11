package org.pragmatica.aether.stream.forward;

import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageReceiver;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.stream.forward.StreamForwardError.General.FORWARD_TIMEOUT;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward.publishForward;
import static org.pragmatica.lang.Option.option;


/// Caller-side client for forwarding stream publishes to a remote governor.
///
/// Sends [PublishForward] messages and tracks pending requests by correlation ID.
/// When a [PublishForwardResponse] arrives, the corresponding promise is resolved.
/// Pending requests time out after a configurable duration.
public interface StreamForwardClient {
    Promise<Long> publishRemote(NodeId governorId, String streamName, int partition, byte[] payload, long timestamp);
    @MessageReceiver@SuppressWarnings("JBCT-RET-01") void onPublishForwardResponse(PublishForwardResponse response);

    StreamForwardClient NOOP = noOpClient();

    static StreamForwardClient streamForwardClient(NodeId selfNodeId, StreamForwardTransport transport) {
        return new DefaultStreamForwardClient(selfNodeId, transport, DefaultStreamForwardClient.DEFAULT_TIMEOUT);
    }

    static StreamForwardClient streamForwardClient(NodeId selfNodeId,
                                                   StreamForwardTransport transport,
                                                   TimeSpan timeout) {
        return new DefaultStreamForwardClient(selfNodeId, transport, timeout);
    }

    private static StreamForwardClient noOpClient() {
        return new StreamForwardClient() {
            @Override public Promise<Long> publishRemote(NodeId governorId,
                                                         String streamName,
                                                         int partition,
                                                         byte[] payload,
                                                         long timestamp) {
                return StreamForwardError.General.GOVERNOR_UNAVAILABLE.promise();
            }

            @Override@SuppressWarnings("JBCT-RET-01") public void onPublishForwardResponse(PublishForwardResponse response) {}
        };
    }
}

final class DefaultStreamForwardClient implements StreamForwardClient {
    private static final Logger log = LoggerFactory.getLogger(StreamForwardClient.class);

    static final TimeSpan DEFAULT_TIMEOUT = TimeSpan.timeSpan(5).seconds();

    private final NodeId selfNodeId;
    private final StreamForwardTransport transport;
    private final TimeSpan timeout;

    private final ConcurrentHashMap<String, Promise<Long>> pendingRequests = new ConcurrentHashMap<>();

    DefaultStreamForwardClient(NodeId selfNodeId, StreamForwardTransport transport, TimeSpan timeout) {
        this.selfNodeId = selfNodeId;
        this.transport = transport;
        this.timeout = timeout;
    }

    @Override public Promise<Long> publishRemote(NodeId governorId,
                                                 String streamName,
                                                 int partition,
                                                 byte[] payload,
                                                 long timestamp) {
        var correlationId = UUID.randomUUID().toString();
        Promise<Long> promise = Promise.promise();
        pendingRequests.put(correlationId, promise);
        SharedScheduler.schedule(() -> timeoutRequest(correlationId), timeout);
        var message = publishForward(selfNodeId, correlationId, streamName, partition, payload, timestamp);
        transport.send(governorId, message);
        log.trace("Sent PublishForward to {} for {}[{}] correlationId={}",
                  governorId,
                  streamName,
                  partition,
                  correlationId);
        return promise;
    }

    @Override@SuppressWarnings("JBCT-RET-01") public void onPublishForwardResponse(PublishForwardResponse response) {
        option(pendingRequests.remove(response.correlationId())).onEmpty(() -> logOrphanedResponse(response))
              .onPresent(promise -> resolveFromResponse(promise, response));
    }

    private void resolveFromResponse(Promise<Long> promise, PublishForwardResponse response) {
        if (response.success()) {promise.succeed(response.offset());} else {promise.resolve(new StreamForwardError.RemotePublishFailed(response.errorMessage()).result());}
    }

    private void timeoutRequest(String correlationId) {
        option(pendingRequests.remove(correlationId)).onPresent(promise -> promise.resolve(FORWARD_TIMEOUT.result()));
    }

    private static void logOrphanedResponse(PublishForwardResponse response) {
        log.debug("Received PublishForwardResponse for unknown correlationId: {}", response.correlationId());
    }
}
