// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.stream.replication.ReplicationError;
import org.pragmatica.aether.slice.PublishOutcomeUnknown;
import org.pragmatica.aether.slice.ResourceCapacityExhausted;
import org.pragmatica.aether.stream.LinearizableOwnerServe;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.VisibleBounds;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.segment.TieredStreamReader;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;

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
                                               Option.none(),
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
                                               Option.none(),
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
                                               ownerServe,
                                               Option.none());
    }

    /// #1383 overload: wires this node's tiered reader so a replica catch-up read falls through to the
    /// owner's tier for a prefix the ring has evicted but the tier retains (see [DefaultStreamForwardHandler#readAppended]).
    /// Without it (base handler / NOOP) the catch-up read stays ring-only and answers the evicted prefix
    /// `CursorExpired`, exactly as before.
    static StreamForwardHandler streamForwardHandler(NodeId selfNodeId,
                                                     StreamPartitionManager partitionManager,
                                                     StreamForwardTransport transport,
                                                     long maxReadResponseBytes,
                                                     StreamReadForwardMetrics metrics,
                                                     Option<LinearizableOwnerServe<OffHeapRingBuffer.RawEvent>> ownerServe,
                                                     Option<TieredStreamReader> tieredReader) {
        return new DefaultStreamForwardHandler(selfNodeId,
                                               partitionManager,
                                               transport,
                                               maxReadResponseBytes,
                                               metrics,
                                               ownerServe,
                                               tieredReader);
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
    private final Option<TieredStreamReader> tieredReader;

    DefaultStreamForwardHandler(NodeId selfNodeId,
                                StreamPartitionManager partitionManager,
                                StreamForwardTransport transport,
                                long maxReadResponseBytes,
                                StreamReadForwardMetrics metrics,
                                Option<LinearizableOwnerServe<OffHeapRingBuffer.RawEvent>> ownerServe,
                                Option<TieredStreamReader> tieredReader) {
        this.selfNodeId = selfNodeId;
        this.partitionManager = partitionManager;
        this.transport = transport;
        this.maxReadResponseBytes = maxReadResponseBytes;
        this.metrics = metrics;
        this.ownerServe = ownerServe;
        this.tieredReader = tieredReader;
    }

    /// #1236: the replica floor (`CF - 1` peers) is checked BEFORE the owner appends, so a forwarded
    /// publish refused with `NOT_ENOUGH_REPLICAS` is genuinely not in the log — and AFTER the #1230 owner
    /// admission, so a forward that lands on a non-owner is answered retryable ([StreamError.NotOwnerAppend])
    /// rather than with a floor verdict this node does not own. A stream this owner has not yet materialized
    /// reports a confirmation factor of 0 here; [StreamPartitionManager#publishForwarded] then materializes it from the
    /// committed config and checks THAT config's floor before appending (#1290 review M1), so the refusal
    /// is clean on that path too.
    @Contract
    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onPublishForward(PublishForward request) {
        partitionManager.publishForwarded(request.streamName(),
                                          request.partition(),
                                          request.payload(),
                                          request.timestamp(),
                                          partitionManager.confirmationFactorFor(request.streamName()) - 1)
                        .async()
                        .flatMap(offset -> awaitMinSync(request, offset))
                        .onSuccess(offset -> sendSuccessResponse(request, offset))
                        .onFailure(cause -> sendPublishFailure(request, cause));
    }

    /// The confirmation barrier belongs HERE, on the owner, because this is where the ack for a forwarded
    /// publish is produced. The sender's write path ([org.pragmatica.aether.stream.StreamWriteRouter], which
    /// every entry point delegates to since #1263) awaits replication only on its local-append arm — so before
    /// this, every publish that was forwarded to the owner acked on the owner's local fsync ALONE, silently
    /// dropping `confirmation_factor` to 1. Measured 2026-08-16 (02y-stream-crash, remote cluster B): 80/80 events ACKED, then a SIGKILL of
    /// the node owning partitions 0 and 2 lost BOTH partitions whole — 41 acked events gone, with the
    /// designated replica still `SYNCING` and never having acked a single one. Gating here fixes both
    /// writer paths at once and makes a forwarded ack mean exactly what a local ack means.
    ///
    /// #1236: this barrier runs AFTER the append, so a failure here is an unknown outcome
    /// ([PublishOutcomeUnknown]), never a clean failure — the clean refusal is the pre-append floor in [#onPublishForward].
    private Promise<Long> awaitMinSync(PublishForward request, long offset) {
        var confirmationFactor = partitionManager.confirmationFactorFor(request.streamName());

        return confirmationFactor > 1
               ? partitionManager.awaitReplication(request.streamName(),
                                                   request.partition(),
                                                   offset,
                                                   confirmationFactor - 1)
                                 .mapError(PublishOutcomeUnknown.FACTORY)
                                 .map(_ -> offset)
               : Promise.success(offset);
    }

    @Contract
    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onReadForward(ReadForward request) {
        if (isConsumerRead(request)) {
            serveConsumerRead(request);

            return;
        }

        serveRead(request).onSuccess(events -> sendReadSuccess(request, events, Epoch.ZERO))
                 .onFailure(cause -> sendReadFailure(request,
                                                     cause.message()));
    }

    /// A plain consumer read: not a replica's catch-up, not a linearizable one. It is the read that carries the owner
    /// epoch the consumer last read under (#1730 phase 2 / #1873).
    private boolean isConsumerRead(ReadForward request) {
        return ! isReplicaCatchup(request)
               && !request.catchup()
               && !request.linearizable();
    }

    /// Validated by [StreamPartitionManager#readServing(String, int, long, int, Epoch)]: the cursor is checked against the
    /// committed epoch starts before a single event is served, and the answer carries the epoch it was served under.
    /// A cursor that belongs to a replaced lineage is answered with the typed divergence.
    private void serveConsumerRead(ReadForward request) {
        partitionManager.readServing(request.streamName(),
                                     request.partition(),
                                     request.fromOffset(),
                                     request.maxEvents(),
                                     request.consumerEpoch())
                        .async()
                        .onSuccess(read -> sendReadSuccess(request,
                                                           read.events(),
                                                           read.ownerEpoch()))
                        .onFailure(cause -> sendReadFailure(request, cause));
    }

    /// A `LINEARIZABLE`-class forwarded read re-runs the shared owner-side serve pipeline
    /// ({@link LinearizableOwnerServe#serveForwarded}) — the SAME committed-owner check + epoch fence +
    /// no-op round + catch-up gate the local read path runs — so a forwarded linearizable read to a
    /// deposed / not-caught-up owner is rejected (`StaleEpochRead` / `OwnerCatchupPending`) rather than
    /// served stale. Every other forward is a replica-class read served by a plain local read. When no
    /// owner-serve pipeline is wired (base handler / NOOP) even a linearizable forward degrades to the
    /// local read. A `catchup` forward (#1235) from a registered replica of the partition is a replication
    /// read, answered up to the APPENDED head from the ring or, for an evicted prefix, this node's tier
    /// (#1383, [#readAppended]). Every other forward — including a `catchup` flag from a node
    /// outside the replica set — is a consumer read, answered up to the VISIBLE position: a bare flag must
    /// not let an arbitrary reader opt out of visibility (CTO ruling, #1235 Fork A).
    private Promise<List<OffHeapRingBuffer.RawEvent>> serveRead(ReadForward request) {
        if (isReplicaCatchup(request)) {
            return readAppended(request);
        }

        if (request.catchup()) {
            return readVisible(request);
        }

        return request.linearizable()
               ? serveLinearizable(request)
               : readServing(request);
    }

    private Promise<List<OffHeapRingBuffer.RawEvent>> serveLinearizable(ReadForward request) {
        return ownerServe.fold(() -> readServing(request),
                               serve -> serve.serveForwarded(request.streamName(),
                                                             request.partition(),
                                                             request.fromOffset(),
                                                             request.maxEvents()));
    }

    private boolean isReplicaCatchup(ReadForward request) {
        return request.catchup() && partitionManager.isRegisteredReplica(request.streamName(),
                                                                         request.partition(),
                                                                         request.sender());
    }

    /// #1383: a replica catch-up read — the ring up to the APPENDED head, and for a prefix the ring has evicted,
    /// this node's tier ([CatchupRead#readAppended]).
    private Promise<List<OffHeapRingBuffer.RawEvent>> readAppended(ReadForward request) {
        return CatchupRead.readAppended(partitionManager,
                                        tieredReader,
                                        request.streamName(),
                                        request.partition(),
                                        request.fromOffset(),
                                        request.maxEvents())
                          .mapError(cause -> nameHeldPartition(request, cause));
    }

    /// A replication-class refusal is translated, never absorbed: a partition this node holds but has not
    /// materialized (paced, budget-deferred) is REACHABLE, and says so with its durable watermark
    /// ([StreamError.PartitionHeldNotMaterialized]) instead of the `PARTITION_NOT_LOCAL` of a genuine non-holder,
    /// which a prober must read as no information. Only replication-class reads are refined: a consumer read
    /// keeps `PARTITION_NOT_LOCAL`, the identity the read routers forward on.
    private Cause nameHeldPartition(ReadForward request, Cause cause) {
        return cause == StreamError.General.PARTITION_NOT_LOCAL
               ? partitionManager.heldNotMaterializedWatermark(request.streamName(),
                                                               request.partition())
                                 .<Cause> map(watermark -> new StreamError.PartitionHeldNotMaterialized(request.streamName(),
                                                                                                        request.partition(),
                                                                                                        watermark,
                                                                                                        partitionManager.heldBudgetExhausted(request.streamName())))
                                 .or(cause)
               : cause;
    }

    /// A forwarded client read (#1555): an owner that has not completed promotion refuses rather than answering
    /// from a stale or short ring ([StreamPartitionManager#readServing]).
    private Promise<List<OffHeapRingBuffer.RawEvent>> readServing(ReadForward request) {
        return partitionManager.readServing(request.streamName(),
                                            request.partition(),
                                            request.fromOffset(),
                                            request.maxEvents())
                               .async();
    }

    /// A catch-up or watermark-probe read from a node outside the replica set (#1555): replication traffic, not
    /// an owner serve, so it is answered up to the visible position without the owner promotion gate — a new
    /// owner catching up from, or probing, a peer must not be blocked by that peer's own promotion state.
    private Promise<List<OffHeapRingBuffer.RawEvent>> readVisible(ReadForward request) {
        return partitionManager.readLocal(request.streamName(),
                                          request.partition(),
                                          request.fromOffset(),
                                          request.maxEvents())
                               .async()
                               .mapError(cause -> nameHeldPartition(request, cause));
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

    /// Owner-side forwarded-publish failure dispatch (write-forward race fix): a cause the owner deems
    /// transient — its committed config not yet visible ({@link StreamError.StreamConfigNotYetVisible}) or a
    /// capacity-deferred partition ({@link ResourceCapacityExhausted}), or a committed owner that is not yet
    /// this node ({@link StreamError.NotOwnerAppend}, #1230: the HRW-routed target during a reshuffle, before
    /// the leader commits the ownership change), or a replica floor not yet met (`NOT_ENOUGH_REPLICAS`, #1564:
    /// refused before the append, and it passes once the replica set registers) — is sent as a RETRYABLE response so the forwarder backs off
    /// and retries a bounded number of times. A [PublishOutcomeUnknown] (#1236) — the barrier failed AFTER
    /// this owner appended — is sent as outcome-unknown, so the sender does not report a clean failure for
    /// an event that may be in the log. Every other cause is permanent.
    @Contract
    private void sendPublishFailure(PublishForward request, Cause cause) {
        if (isRetryable(cause)) {
            sendRetryableResponse(request, cause.message());
        } else if (cause instanceof PublishOutcomeUnknown) {
            sendOutcomeUnknownResponse(request, cause.message());
        } else {
            sendFailureResponse(request, cause.message());
        }
    }

    @Contract
    private void sendOutcomeUnknownResponse(PublishForward request, String errorMessage) {
        var response = PublishForwardResponse.outcomeUnknownResponse(selfNodeId, request.correlationId(), errorMessage);

        transport.send(request.sender(), response);
        log.warn("Forwarded publish outcome unknown for {}[{}] correlationId={}: {}",
                 request.streamName(),
                 request.partition(),
                 request.correlationId(),
                 errorMessage);
    }

    private static boolean isRetryable(Cause cause) {
        return cause instanceof StreamError.StreamConfigNotYetVisible || cause instanceof StreamError.NotOwnerAppend || cause instanceof StreamError.OwnerNotActivated || cause == ReplicationError.General.NOT_ENOUGH_REPLICAS || ResourceCapacityExhausted.isTransientCapacity(cause);
    }

    @Contract
    private void sendRetryableResponse(PublishForward request, String errorMessage) {
        var response = PublishForwardResponse.retryableResponse(selfNodeId, request.correlationId(), errorMessage);

        transport.send(request.sender(), response);
        log.warn("Forwarded publish retryable for {}[{}] correlationId={}: {}",
                 request.streamName(),
                 request.partition(),
                 request.correlationId(),
                 errorMessage);
    }

    /// #1596: a replica catch-up answer carries this node's owner-epoch history, read AFTER the events so it covers
    /// every one of them; the replica installs it before applying them. A history this node cannot decode fails
    /// the read rather than shipping records without their provenance. Every other answer carries none.
    private ReadForwardResponse withCatchupHistory(ReadForward request, ReadForwardResponse answer) {
        return isReplicaCatchup(request)
               ? partitionManager.epochHistory(request.streamName(),
                                               request.partition())
                                 .fold(cause -> ReadForwardResponse.failureResponse(selfNodeId,
                                                                                    request.correlationId(),
                                                                                    cause.message()),
                                       answer::withHistory)
               : answer;
    }

    /// #1333: every successful answer carries this node's visible bounds of the partition, read AFTER
    /// the events so the head is never behind the last event served.
    @Contract
    private void sendReadSuccess(ReadForward request, List<OffHeapRingBuffer.RawEvent> events, Epoch ownerEpoch) {
        var capped = applyCap(events);
        var bounds = partitionManager.visibleBounds(request.streamName(),
                                                    request.partition())
                                     .or(VisibleBounds::absent);
        var answer = capped.truncated()
                     ? ReadForwardResponse.truncatedResponse(selfNodeId,
                                                             request.correlationId(),
                                                             capped.events(),
                                                             bounds)
                     : ReadForwardResponse.successResponse(selfNodeId, request.correlationId(), capped.events(), bounds);
        var response = withCatchupHistory(request, answer.withOwnerEpoch(ownerEpoch));

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

    /// The typed divergence of a validated read goes out as itself (#1730 phase 2 / #1873); every other failure as its
    /// message.
    @Contract
    private void sendReadFailure(ReadForward request, Cause cause) {
        if (cause instanceof StreamError.EpochDiverged diverged) {
            transport.send(request.sender(),
                           ReadForwardResponse.epochDivergedResponse(selfNodeId,
                                                                     request.correlationId(),
                                                                     diverged.ownerEpoch(),
                                                                     diverged.resumeAt(),
                                                                     diverged.provenLossFrom(),
                                                                     diverged.message()));
            log.info("Forwarded read diverged for {}[{}] fromOffset={} correlationId={}: {}",
                     request.streamName(),
                     request.partition(),
                     request.fromOffset(),
                     request.correlationId(),
                     diverged.message());

            return;
        }

        sendReadFailure(request, cause.message());
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

    /// #1431: the FIRST event of a page is always admitted, even when it alone exceeds the cap — the rule the
    /// replication manager already follows ("a single event larger than the budget is still sent alone"). A page cut
    /// before its first event can never advance a reader past that event: a catch-up, a promotion probe or a consumer
    /// read would stall on it. So a capped page is never empty, and only the transport frame limit bounds one event.
    private CappedEvents applyCap(List<OffHeapRingBuffer.RawEvent> events) {
        var capped = new ArrayList<RawEventDto>();
        var total = ENVELOPE_OVERHEAD_BYTES;

        for (var event : events) {
            var next = total + event.data().length + PER_EVENT_OVERHEAD_BYTES;

            if (next > maxReadResponseBytes && !capped.isEmpty()) {
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
