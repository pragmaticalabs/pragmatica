// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.messaging.MessageReceiver;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Governor/replica-side handler for stream forwarding.
///
/// Publish (existing): receives [PublishForward], calls [StreamPartitionManager#publishLocal],
/// responds with [PublishForwardResponse].
///
/// Read (SPEC: §5): receives [ReadForward], calls [StreamPartitionManager#readLocal],
/// converts events to [RawEventDto], applies defensive size cap (SPEC: §10.5),
/// responds with [ReadForwardResponse] possibly marked truncated.
public interface StreamForwardHandler {
    @MessageReceiver@SuppressWarnings("JBCT-RET-01") void onPublishForward(PublishForward request);
    @MessageReceiver@SuppressWarnings("JBCT-RET-01") void onReadForward(ReadForward request);

    long DEFAULT_MAX_READ_RESPONSE_BYTES = 28L * 1024 * 1024;

    static StreamForwardHandler streamForwardHandler(NodeId selfNodeId,
                                                     StreamPartitionManager partitionManager,
                                                     StreamForwardTransport transport) {
        return new DefaultStreamForwardHandler(selfNodeId,
                                               partitionManager,
                                               transport,
                                               DEFAULT_MAX_READ_RESPONSE_BYTES,
                                               StreamReadForwardMetrics.NOOP);
    }

    static StreamForwardHandler streamForwardHandler(NodeId selfNodeId,
                                                     StreamPartitionManager partitionManager,
                                                     StreamForwardTransport transport,
                                                     long maxReadResponseBytes,
                                                     StreamReadForwardMetrics metrics) {
        return new DefaultStreamForwardHandler(selfNodeId, partitionManager, transport, maxReadResponseBytes, metrics);
    }

    StreamForwardHandler NOOP = new StreamForwardHandler() {
        @Contract@Override public void onPublishForward(PublishForward request) {}

        @Contract@Override public void onReadForward(ReadForward request) {}
    };
}

final class DefaultStreamForwardHandler implements StreamForwardHandler {
    private static final Logger log = LoggerFactory.getLogger(StreamForwardHandler.class);

    private static final long PER_EVENT_OVERHEAD_BYTES = 24L;

    private static final long ENVELOPE_OVERHEAD_BYTES = 64L;

    private final NodeId selfNodeId;
    private final StreamPartitionManager partitionManager;
    private final StreamForwardTransport transport;
    private final long maxReadResponseBytes;
    private final StreamReadForwardMetrics metrics;

    DefaultStreamForwardHandler(NodeId selfNodeId,
                                StreamPartitionManager partitionManager,
                                StreamForwardTransport transport,
                                long maxReadResponseBytes,
                                StreamReadForwardMetrics metrics) {
        this.selfNodeId = selfNodeId;
        this.partitionManager = partitionManager;
        this.transport = transport;
        this.maxReadResponseBytes = maxReadResponseBytes;
        this.metrics = metrics;
    }

    @Contract@Override@SuppressWarnings("JBCT-RET-01") public void onPublishForward(PublishForward request) {
        partitionManager.publishLocal(request.streamName(),
                                      request.partition(),
                                      request.payload(),
                                      request.timestamp()).onSuccess(offset -> sendSuccessResponse(request, offset))
                                     .onFailure(cause -> sendFailureResponse(request,
                                                                             cause.message()));
    }

    @Contract@Override@SuppressWarnings("JBCT-RET-01") public void onReadForward(ReadForward request) {
        partitionManager.readLocal(request.streamName(),
                                   request.partition(),
                                   request.fromOffset(),
                                   request.maxEvents()).onSuccess(events -> sendReadSuccess(request, events))
                                  .onFailure(cause -> sendReadFailure(request,
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

    @Contract private void sendReadSuccess(ReadForward request, List<OffHeapRingBuffer.RawEvent> events) {
        var capped = applyCap(events);
        var response = capped.truncated()
                      ? ReadForwardResponse.truncatedResponse(selfNodeId, request.correlationId(), capped.events())
                      : ReadForwardResponse.successResponse(selfNodeId, request.correlationId(), capped.events());
        if (capped.truncated()) {metrics.recordTruncated();}
        transport.send(request.sender(), response);
        log.trace("Forwarded read succeeded for {}[{}] fromOffset={} correlationId={} events={} truncated={}",
                  request.streamName(),
                  request.partition(),
                  request.fromOffset(),
                  request.correlationId(),
                  capped.events().size(),
                  capped.truncated());
    }

    @Contract private void sendReadFailure(ReadForward request, String errorMessage) {
        var response = ReadForwardResponse.failureResponse(selfNodeId, request.correlationId(), errorMessage);
        transport.send(request.sender(), response);
        log.warn("Forwarded read failed for {}[{}] fromOffset={} correlationId={}: {}",
                 request.streamName(),
                 request.partition(),
                 request.fromOffset(),
                 request.correlationId(),
                 errorMessage);
    }

    private CappedEvents applyCap(List<OffHeapRingBuffer.RawEvent> events) {
        var capped = new ArrayList<RawEventDto>();
        var total = ENVELOPE_OVERHEAD_BYTES;
        for (var event : events) {
            var next = total + event.data().length + PER_EVENT_OVERHEAD_BYTES;
            if (next > maxReadResponseBytes) {break;}
            capped.add(RawEventDto.fromRawEvent(event));
            total = next;
        }
        var truncated = capped.size() <events.size();
        return new CappedEvents(List.copyOf(capped), truncated);
    }

    private record CappedEvents(List<RawEventDto> events, boolean truncated){}
}
