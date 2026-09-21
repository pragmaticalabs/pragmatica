// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.PublishOutcomeUnknown;
import org.pragmatica.aether.slice.ResourceCapacityExhausted;
import org.pragmatica.aether.stream.LinearizableOwnerServe;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.VisibleBounds;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.segment.SegmentError;
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

    /// #1236: the replica floor (`min-sync - 1` peers) is checked BEFORE the owner appends, so a forwarded
    /// publish refused with `NOT_ENOUGH_REPLICAS` is genuinely not in the log — and AFTER the #1230 owner
    /// admission, so a forward that lands on a non-owner is answered retryable ([StreamError.NotOwnerAppend])
    /// rather than with a floor verdict this node does not own. A stream this owner has not yet materialized
    /// reports `min-sync` 0 here; [StreamPartitionManager#publishForwarded] then materializes it from the
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
                                          partitionManager.minSyncReplicasFor(request.streamName()) - 1)
                        .async()
                        .flatMap(offset -> awaitMinSync(request, offset))
                        .onSuccess(offset -> sendSuccessResponse(request, offset))
                        .onFailure(cause -> sendPublishFailure(request, cause));
    }

    /// The min-sync barrier belongs HERE, on the owner, because this is where the ack for a forwarded
    /// publish is produced. The sender's write path ([org.pragmatica.aether.stream.StreamWriteRouter], which
    /// every entry point delegates to since #1263) awaits replication only on its local-append arm — so before
    /// this, every publish that was forwarded to the owner acked on the owner's local fsync ALONE, silently
    /// dropping `min-sync-replicas` to 1. Measured 2026-08-16 (02y-stream-crash, remote cluster B): 80/80 events ACKED, then a SIGKILL of
    /// the node owning partitions 0 and 2 lost BOTH partitions whole — 41 acked events gone, with the
    /// designated replica still `SYNCING` and never having acked a single one. Gating here fixes both
    /// writer paths at once and makes a forwarded ack mean exactly what a local ack means.
    ///
    /// #1236: this barrier runs AFTER the append, so a failure here is an unknown outcome
    /// ([PublishOutcomeUnknown]), never a clean failure — the clean refusal is the pre-append floor in [#onPublishForward].
    private Promise<Long> awaitMinSync(PublishForward request, long offset) {
        var minSyncReplicas = partitionManager.minSyncReplicasFor(request.streamName());

        return minSyncReplicas > 1
               ? partitionManager.awaitReplication(request.streamName(),
                                                   request.partition(),
                                                   offset,
                                                   minSyncReplicas - 1)
                                 .mapError(PublishOutcomeUnknown.FACTORY)
                                 .map(_ -> offset)
               : Promise.success(offset);
    }

    @Contract
    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onReadForward(ReadForward request) {
        serveRead(request).onSuccess(events -> sendReadSuccess(request, events))
                 .onFailure(cause -> sendReadFailure(request,
                                                     cause.message()));
    }

    /// A `LINEARIZABLE`-class forwarded read re-runs the shared owner-side serve pipeline
    /// ({@link LinearizableOwnerServe#serveForwarded}) — the SAME committed-owner check + epoch fence +
    /// no-op round + catch-up gate the local read path runs — so a forwarded linearizable read to a
    /// deposed / not-caught-up owner is rejected (`StaleEpochRead` / `OwnerCatchupPending`) rather than
    /// served stale. Every other forward is a replica-class read served by a plain local read. When no
    /// owner-serve pipeline is wired (base handler / NOOP) even a linearizable forward degrades to the
    /// local read. A `catchup` forward (#1235) from a registered replica of the partition is a replication
    /// read, answered up to the APPENDED head. Every other forward — including a `catchup` flag from a node
    /// outside the replica set — is a consumer read, answered up to the VISIBLE position: a bare flag must
    /// not let an arbitrary reader opt out of visibility (CTO ruling, #1235 Fork A).
    private Promise<List<OffHeapRingBuffer.RawEvent>> serveRead(ReadForward request) {
        if (isReplicaCatchup(request)) {
            return readAppended(request);
        }

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

    private boolean isReplicaCatchup(ReadForward request) {
        return request.catchup() && partitionManager.isRegisteredReplica(request.streamName(),
                                                                         request.partition(),
                                                                         request.sender());
    }

    /// #1383: a replica catch-up read is served from the ring up to the APPENDED head, or — for a prefix the
    /// ring has evicted but this node's tier retains — from the tier, then the ring for the rest of the page.
    /// Before this the read was ring-only, so a replacement replica whose catch-up started below the ring tail
    /// was answered `CursorExpired` on every redrive and never left SYNCING; with `min-sync` 2 and the original
    /// peer gone, the partition's visible position never advanced again. The tier read is bounded by the
    /// appended head — the replication-read class (#1235), never the consumer's visible bound (#1352) — so the
    /// replica holds every offset it acks and nothing this owner has not appended. A prefix retention has
    /// reclaimed is still `CursorExpired` (from the tier, [TieredStreamReader#read]) and still stalls: #1407.
    private Promise<List<OffHeapRingBuffer.RawEvent>> readAppended(ReadForward request) {
        return partitionManager.readAppended(request.streamName(),
                                             request.partition(),
                                             request.fromOffset(),
                                             request.maxEvents())
                               .fold(cause -> recoverEvicted(request, cause),
                                     Promise::success);
    }

    private Promise<List<OffHeapRingBuffer.RawEvent>> recoverEvicted(ReadForward request, Cause cause) {
        return cause instanceof StreamError.CursorExpired
               ? readEvictedPrefix(request, cause)
               : cause.promise();
    }

    /// An evicted offset the sealer still retains is IN FLIGHT: its seal is not indexed yet, so it is in neither
    /// place, and the read fails transient ([SegmentError.SealInFlight]) for the backfill to redrive — never
    /// `CursorExpired`, which names an offset nobody holds. Asked BEFORE the tier read, as the consumer path
    /// does: the sink indexes a segment before the sealer releases its copy, so an offset not retained here is
    /// already findable in the index. Without a tier wired the ring's own refusal stands.
    private Promise<List<OffHeapRingBuffer.RawEvent>> readEvictedPrefix(ReadForward request, Cause expired) {
        if (partitionManager.sealInFlight(request.streamName(), request.partition(), request.fromOffset())) {
            return new SegmentError.SealInFlight(request.streamName(), request.partition(), request.fromOffset()).promise();
        }

        return tieredReader.fold(expired::promise, reader -> readTierThenRing(request, reader, expired));
    }

    /// The tier is asked for no more than `[fromOffset, appended head]`; nothing sealed at `fromOffset` means the
    /// ring's refusal was right (a seal that failed for good, a ring released under the read) and it is returned
    /// as-is — an empty success would let the backfill take the no-source path off a partition that has history.
    private Promise<List<OffHeapRingBuffer.RawEvent>> readTierThenRing(ReadForward request,
                                                                       TieredStreamReader reader,
                                                                       Cause expired) {
        var head = appendedHead(request);

        if (request.fromOffset() > head) {
            return expired.promise();
        }

        return reader.read(request.streamName(),
                           request.partition(),
                           request.fromOffset(),
                           (int) Math.min(request.maxEvents(),
                                          head - request.fromOffset() + 1))
                     .flatMap(sealed -> serveSealedPrefix(request, sealed, expired));
    }

    private Promise<List<OffHeapRingBuffer.RawEvent>> serveSealedPrefix(ReadForward request,
                                                                        List<OffHeapRingBuffer.RawEvent> sealed,
                                                                        Cause expired) {
        return sealed.isEmpty()
               ? expired.promise()
               : appendRingTail(request, sealed);
    }

    private long appendedHead(ReadForward request) {
        return partitionManager.partitionBuffer(request.streamName(),
                                                request.partition())
                               .map(OffHeapRingBuffer::headOffset)
                               .or(-1L);
    }

    /// FER (degrade forward) for the ring's share of the page, which starts right after the sealed prefix: a
    /// ring failure there returns the prefix alone, because under sustained eviction the ring can wrap past that
    /// offset between the tier read and this one, and failing the whole page would redrive the backfill from the
    /// same cursor into the same race. A short page is applied, stays SYNCING (`INCOMPLETE_BACKFILL`) and redrives
    /// from the advanced watermark, where the failure — in-flight seal, wrapped ring, corrupted ring — surfaces at
    /// the exact offset it occurs.
    private Promise<List<OffHeapRingBuffer.RawEvent>> appendRingTail(ReadForward request,
                                                                     List<OffHeapRingBuffer.RawEvent> sealed) {
        var remaining = request.maxEvents() - sealed.size();

        if (remaining <= 0) {
            return Promise.success(sealed);
        }

        return partitionManager.readAppended(request.streamName(),
                                             request.partition(),
                                             sealed.getLast().offset() + 1,
                                             remaining)
                               .map(ring -> List.copyOf(Stream.concat(sealed.stream(),
                                                                      ring.stream()).toList()))
                               .recover(_ -> sealed)
                               .async();
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

    /// Owner-side forwarded-publish failure dispatch (write-forward race fix): a cause the owner deems
    /// transient — its committed config not yet visible ({@link StreamError.StreamConfigNotYetVisible}) or a
    /// capacity-deferred partition ({@link ResourceCapacityExhausted}), or a committed owner that is not yet
    /// this node ({@link StreamError.NotOwnerAppend}, #1230: the HRW-routed target during a reshuffle, before
    /// the leader commits the ownership change) — is sent as a RETRYABLE response so the forwarder backs off
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
        return cause instanceof StreamError.StreamConfigNotYetVisible || cause instanceof StreamError.NotOwnerAppend || ResourceCapacityExhausted.isTransientCapacity(cause);
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

    /// #1333: every successful answer carries this node's visible bounds of the partition, read AFTER
    /// the events so the head is never behind the last event served.
    @Contract
    private void sendReadSuccess(ReadForward request, List<OffHeapRingBuffer.RawEvent> events) {
        var capped = applyCap(events);
        var bounds = partitionManager.visibleBounds(request.streamName(),
                                                    request.partition())
                                     .or(VisibleBounds::absent);
        var response = capped.truncated()
                       ? ReadForwardResponse.truncatedResponse(selfNodeId,
                                                               request.correlationId(),
                                                               capped.events(),
                                                               bounds)
                       : ReadForwardResponse.successResponse(selfNodeId,
                                                             request.correlationId(),
                                                             capped.events(),
                                                             bounds);

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
