// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import org.pragmatica.aether.stream.LinearizableOwnerServe;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageReceiver;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface StreamForwardHandler {
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onPublishForward(PublishForward request);

    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onReadForward(ReadForward request);

    long DEFAULT_MAX_READ_RESPONSE_BYTES = 28L * 1024 * 1024;

    static StreamForwardHandler streamForwardHandler(NodeId selfNodeId,
                                                     StreamPartitionManager partitionManager,
                                                     StreamForwardTransport transport) {
        return new DefaultStreamForwardHandler(selfNodeId,
                                               partitionManager,
                                               transport,
                                               DEFAULT_MAX_READ_RESPONSE_BYTES,
                                               StreamReadForwardMetrics.NOOP,
                                               Option.none());
    }

    static StreamForwardHandler streamForwardHandler(NodeId selfNodeId,
                                                     StreamPartitionManager partitionManager,
                                                     StreamForwardTransport transport,
                                                     long maxReadResponseBytes,
                                                     StreamReadForwardMetrics metrics) {
        return new DefaultStreamForwardHandler(selfNodeId,
                                               partitionManager,
                                               transport,
                                               maxReadResponseBytes,
                                               metrics,
                                               Option.none());
    }

    /// #345 item 1e-a overload: wires the shared owner-side serve pipeline so a `LINEARIZABLE`-class
    /// forwarded read (`ReadForward.linearizable()`) is re-guarded here (committed-owner check + epoch
    /// fence + no-op round + catch-up gate) instead of served by an unguarded local read — the same
    /// pipeline the local read path runs, so the two entry points cannot diverge.
    static StreamForwardHandler streamForwardHandler(NodeId selfNodeId,
                                                     StreamPartitionManager partitionManager,
                                                     StreamForwardTransport transport,
                                                     long maxReadResponseBytes,
                                                     StreamReadForwardMetrics metrics,
                                                     Option<LinearizableOwnerServe<OffHeapRingBuffer.RawEvent>> ownerServe) {
        return new DefaultStreamForwardHandler(selfNodeId,
                                               partitionManager,
                                               transport,
                                               maxReadResponseBytes,
                                               metrics,
                                               ownerServe);
    }

    StreamForwardHandler NOOP = new StreamForwardHandler() {
        @Contract
        @Override
        public void onPublishForward(PublishForward request) {}

        @Contract
        @Override
        public void onReadForward(ReadForward request) {}
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
    private final Option<LinearizableOwnerServe<OffHeapRingBuffer.RawEvent>> ownerServe;

    DefaultStreamForwardHandler(NodeId selfNodeId,
                                StreamPartitionManager partitionManager,
                                StreamForwardTransport transport,
                                long maxReadResponseBytes,
                                StreamReadForwardMetrics metrics,
                                Option<LinearizableOwnerServe<OffHeapRingBuffer.RawEvent>> ownerServe) {
        this.selfNodeId = selfNodeId;
        this.partitionManager = partitionManager;
        this.transport = transport;
        this.maxReadResponseBytes = maxReadResponseBytes;
        this.metrics = metrics;
        this.ownerServe = ownerServe;
    }

    @Contract
    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onPublishForward(PublishForward request) {
        partitionManager.publishLocal(request.streamName(), request.partition(), request.payload(), request.timestamp()).onSuccess(offset -> sendSuccessResponse(request,
                                                                                                                                                                 offset)).onFailure(cause -> sendFailureResponse(request,
                                                                                                                                                                                                                 cause.message()));
    }

    @Contract
    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onReadForward(ReadForward request) {
        serveRead(request).onSuccess(events -> sendReadSuccess(request, events)).onFailure(cause -> sendReadFailure(request,
                                                                                                                    cause.message()));
    }

    /// A `LINEARIZABLE`-class forwarded read re-runs the shared owner-side serve pipeline
    /// ({@link LinearizableOwnerServe#serveForwarded}) — the SAME committed-owner check + epoch fence +
    /// no-op round + catch-up gate the local read path runs — so a forwarded linearizable read to a
    /// deposed / not-caught-up owner is rejected (`StaleEpochRead` / `OwnerCatchupPending`) rather than
    /// served stale. Every other forward is a replica-class read served by a plain local read. When no
    /// owner-serve pipeline is wired (base handler / NOOP) even a linearizable forward degrades to the
    /// local read.
    private Promise<List<OffHeapRingBuffer.RawEvent>> serveRead(ReadForward request) {
        return request.linearizable()
               ? serveLinearizable(request)
               : readLocal(request);
    }

    private Promise<List<OffHeapRingBuffer.RawEvent>> serveLinearizable(ReadForward request) {
        return ownerServe.fold(() -> readLocal(request),
                               serve -> serve.serveForwarded(request.streamName(),
                                                             request.partition(),
                                                             request.fromOffset(),
                                                             request.maxEvents()));
    }

    private Promise<List<OffHeapRingBuffer.RawEvent>> readLocal(ReadForward request) {
        return partitionManager.readLocal(request.streamName(),
                                          request.partition(),
                                          request.fromOffset(),
                                          request.maxEvents())
                               .async();
    }

    @Contract
    private void sendSuccessResponse(PublishForward request, long offset) {
        var response = PublishForwardResponse.successResponse(selfNodeId, request.correlationId(), offset);

        transport.send(request.sender(), response);
        log.trace("Forwarded publish succeeded for {}[{}] correlationId={} offset={}",
                  request.streamName(),
                  request.partition(),
                  request.correlationId(),
                  offset);
    }

    @Contract
    private void sendFailureResponse(PublishForward request, String errorMessage) {
        var response = PublishForwardResponse.failureResponse(selfNodeId, request.correlationId(), errorMessage);

        transport.send(request.sender(), response);
        log.warn("Forwarded publish failed for {}[{}] correlationId={}: {}",
                 request.streamName(),
                 request.partition(),
                 request.correlationId(),
                 errorMessage);
    }

    @Contract
    private void sendReadSuccess(ReadForward request, List<OffHeapRingBuffer.RawEvent> events) {
        var capped = applyCap(events);
        var response = capped.truncated()
                       ? ReadForwardResponse.truncatedResponse(selfNodeId, request.correlationId(), capped.events())
                       : ReadForwardResponse.successResponse(selfNodeId, request.correlationId(), capped.events());

        if (capped.truncated()) {
            metrics.recordTruncated();
        }

        transport.send(request.sender(), response);
        log.trace("Forwarded read succeeded for {}[{}] fromOffset={} correlationId={} events={} truncated={}",
                  request.streamName(),
                  request.partition(),
                  request.fromOffset(),
                  request.correlationId(),
                  capped.events().size(),
                  capped.truncated());
    }

    @Contract
    private void sendReadFailure(ReadForward request, String errorMessage) {
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

            if (next > maxReadResponseBytes) {
                break;
            }

            capped.add(RawEventDto.fromRawEvent(event));
            total = next;
        }

        var truncated = capped.size() < events.size();

        return new CappedEvents(List.copyOf(capped), truncated);
    }

    private record CappedEvents(List<RawEventDto> events, boolean truncated) {}
}
