// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageReceiver;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.stream.forward.StreamForwardError.General.FORWARD_TIMEOUT;
import static org.pragmatica.aether.stream.forward.StreamForwardError.General.READ_FORWARD_TIMEOUT;
import static org.pragmatica.aether.stream.forward.StreamForwardError.General.STREAM_FORWARD_UNAVAILABLE;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward.publishForward;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward.readForward;
import static org.pragmatica.lang.Option.option;


public interface StreamForwardClient {
    Promise<Long> publishRemote(NodeId governorId, String streamName, int partition, byte[] payload, long timestamp);
    Promise<ReadForwardResult> readRemote(NodeId replicaId,
                                          String streamName,
                                          int partition,
                                          long fromOffset,
                                          int maxEvents);
    @MessageReceiver@SuppressWarnings("JBCT-RET-01") void onPublishForwardResponse(PublishForwardResponse response);
    @MessageReceiver@SuppressWarnings("JBCT-RET-01") void onReadForwardResponse(ReadForwardResponse response);

    StreamForwardClient NOOP = noOpClient();

    static StreamForwardClient streamForwardClient(NodeId selfNodeId, StreamForwardTransport transport) {
        return new DefaultStreamForwardClient(selfNodeId,
                                              transport,
                                              DefaultStreamForwardClient.DEFAULT_TIMEOUT,
                                              DefaultStreamForwardClient.DEFAULT_TIMEOUT,
                                              StreamReadForwardMetrics.NOOP);
    }

    static StreamForwardClient streamForwardClient(NodeId selfNodeId,
                                                   StreamForwardTransport transport,
                                                   TimeSpan timeout) {
        return new DefaultStreamForwardClient(selfNodeId, transport, timeout, timeout, StreamReadForwardMetrics.NOOP);
    }

    static StreamForwardClient streamForwardClient(NodeId selfNodeId,
                                                   StreamForwardTransport transport,
                                                   TimeSpan publishTimeout,
                                                   TimeSpan readTimeout) {
        return new DefaultStreamForwardClient(selfNodeId,
                                              transport,
                                              publishTimeout,
                                              readTimeout,
                                              StreamReadForwardMetrics.NOOP);
    }

    static StreamForwardClient streamForwardClient(NodeId selfNodeId,
                                                   StreamForwardTransport transport,
                                                   TimeSpan publishTimeout,
                                                   TimeSpan readTimeout,
                                                   StreamReadForwardMetrics metrics) {
        return new DefaultStreamForwardClient(selfNodeId, transport, publishTimeout, readTimeout, metrics);
    }

    record ReadForwardResult(List<RawEventDto> events, boolean truncated) {
        public ReadForwardResult {
            events = List.copyOf(events);
        }

        public static ReadForwardResult readForwardResult(List<RawEventDto> events, boolean truncated) {
            return new ReadForwardResult(events, truncated);
        }
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

            @Override public Promise<ReadForwardResult> readRemote(NodeId replicaId,
                                                                   String streamName,
                                                                   int partition,
                                                                   long fromOffset,
                                                                   int maxEvents) {
                return STREAM_FORWARD_UNAVAILABLE.promise();
            }

            @Override@SuppressWarnings("JBCT-RET-01") public void onPublishForwardResponse(PublishForwardResponse response) {}

            @Override@SuppressWarnings("JBCT-RET-01") public void onReadForwardResponse(ReadForwardResponse response) {}
        };
    }
}

final class DefaultStreamForwardClient implements StreamForwardClient {
    private static final Logger log = LoggerFactory.getLogger(StreamForwardClient.class);

    static final TimeSpan DEFAULT_TIMEOUT = TimeSpan.timeSpan(5).seconds();

    private final NodeId selfNodeId;
    private final StreamForwardTransport transport;
    private final TimeSpan publishTimeout;
    private final TimeSpan readTimeout;
    private final StreamReadForwardMetrics metrics;

    private final ConcurrentHashMap<String, Promise<Long>> pendingRequests = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Promise<ReadForwardResult>> pendingReads = new ConcurrentHashMap<>();

    DefaultStreamForwardClient(NodeId selfNodeId,
                               StreamForwardTransport transport,
                               TimeSpan publishTimeout,
                               TimeSpan readTimeout,
                               StreamReadForwardMetrics metrics) {
        this.selfNodeId = selfNodeId;
        this.transport = transport;
        this.publishTimeout = publishTimeout;
        this.readTimeout = readTimeout;
        this.metrics = metrics;
    }

    @Override public Promise<Long> publishRemote(NodeId governorId,
                                                 String streamName,
                                                 int partition,
                                                 byte[] payload,
                                                 long timestamp) {
        var correlationId = UUID.randomUUID().toString();
        Promise<Long> promise = Promise.promise();
        pendingRequests.put(correlationId, promise);
        SharedScheduler.schedule(() -> timeoutRequest(correlationId), publishTimeout);
        var message = publishForward(selfNodeId, correlationId, streamName, partition, payload, timestamp);
        transport.send(governorId, message);
        log.trace("Sent PublishForward to {} for {}[{}] correlationId={}",
                  governorId,
                  streamName,
                  partition,
                  correlationId);
        return promise;
    }

    @Override public Promise<ReadForwardResult> readRemote(NodeId replicaId,
                                                           String streamName,
                                                           int partition,
                                                           long fromOffset,
                                                           int maxEvents) {
        metrics.recordAttempt();
        var correlationId = UUID.randomUUID().toString();
        Promise<ReadForwardResult> promise = Promise.promise();
        pendingReads.put(correlationId, promise);
        SharedScheduler.schedule(() -> timeoutRead(correlationId), readTimeout);
        var message = readForward(selfNodeId, correlationId, streamName, partition, fromOffset, maxEvents);
        transport.send(replicaId, message);
        log.trace("Sent ReadForward to {} for {}[{}] fromOffset={} maxEvents={} correlationId={}",
                  replicaId,
                  streamName,
                  partition,
                  fromOffset,
                  maxEvents,
                  correlationId);
        return promise;
    }

    @Override@SuppressWarnings("JBCT-RET-01") public void onPublishForwardResponse(PublishForwardResponse response) {
        option(pendingRequests.remove(response.correlationId())).onEmpty(() -> logOrphanedPublishResponse(response))
              .onPresent(promise -> resolveFromResponse(promise, response));
    }

    @Override@SuppressWarnings("JBCT-RET-01") public void onReadForwardResponse(ReadForwardResponse response) {
        option(pendingReads.remove(response.correlationId())).onEmpty(() -> logOrphanedReadResponse(response))
              .onPresent(promise -> resolveFromReadResponse(promise, response));
    }

    private void resolveFromResponse(Promise<Long> promise, PublishForwardResponse response) {
        if (response.success()) {promise.succeed(response.offset());} else {promise.resolve(new StreamForwardError.RemotePublishFailed(response.errorMessage()).result());}
    }

    private void resolveFromReadResponse(Promise<ReadForwardResult> promise, ReadForwardResponse response) {
        if (response.success()) {
            metrics.recordSuccess();
            promise.succeed(new ReadForwardResult(response.events(), response.truncated()));
        } else {promise.resolve(new StreamForwardError.ReadForwardFailed(response.errorMessage()).result());}
    }

    private void timeoutRequest(String correlationId) {
        option(pendingRequests.remove(correlationId)).onPresent(promise -> promise.resolve(FORWARD_TIMEOUT.result()));
    }

    private void timeoutRead(String correlationId) {
        option(pendingReads.remove(correlationId)).onPresent(promise -> {
                                                                 metrics.recordTimeout();
                                                                 promise.resolve(READ_FORWARD_TIMEOUT.result());
                                                             });
    }

    private static void logOrphanedPublishResponse(PublishForwardResponse response) {
        log.debug("Received PublishForwardResponse for unknown correlationId: {}", response.correlationId());
    }

    private static void logOrphanedReadResponse(ReadForwardResponse response) {
        log.debug("Received ReadForwardResponse for unknown correlationId: {}", response.correlationId());
    }
}
