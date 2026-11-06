// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.stream.control.ControlLogError;
import org.pragmatica.aether.stream.control.ControlOutcome;
import org.pragmatica.aether.stream.control.ControlRecord;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ControlAppendForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ControlAppendForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.slice.PublishOutcomeUnknown;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.VisibleBounds;
import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Deadline;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageReceiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.stream.forward.StreamForwardError.General.FORWARD_TIMEOUT;
import static org.pragmatica.aether.stream.forward.StreamForwardError.General.READ_FORWARD_TIMEOUT;
import static org.pragmatica.aether.stream.forward.StreamForwardError.General.STREAM_FORWARD_UNAVAILABLE;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward.publishForward;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward.readForward;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward.validatedReadForward;
import static org.pragmatica.lang.Option.option;


public interface StreamForwardClient {
    Promise<Long> publishRemote(NodeId governorId, String streamName, int partition, byte[] payload, long timestamp);

    /// `systemWriter` marks a publish forwarded by this node's own system publisher (#1338); the owner refuses a forwarded
    /// write to a `system:` stream that does not carry it. The default drops the mark, so only a client that sends it can
    /// forward a system write.
    default Promise<Long> publishRemote(NodeId governorId,
                                        String streamName,
                                        int partition,
                                        byte[] payload,
                                        long timestamp,
                                        boolean systemWriter) {
        return publishRemote(governorId, streamName, partition, payload, timestamp);
    }

    Promise<ReadForwardResult> readRemote(NodeId replicaId,
                                          String streamName,
                                          int partition,
                                          long fromOffset,
                                          int maxEvents);

    /// #345 item 1e-a: forward a read tagged with its routing preference, so a `LINEARIZABLE` forward is
    /// re-guarded at the committed owner (the owner-side serve pipeline) rather than served by an
    /// unguarded local read. The default drops the tag and forwards plainly — it matters only on the
    /// production transport ({@link DefaultStreamForwardClient}), which stamps it into the
    /// {@link ReadForward} message; test fakes and {@link #NOOP} inherit the plain forward.
    default Promise<ReadForwardResult> readRemote(NodeId replicaId,
                                                  String streamName,
                                                  int partition,
                                                  long fromOffset,
                                                  int maxEvents,
                                                  ReadPreference preference) {
        return readRemote(replicaId, streamName, partition, fromOffset, maxEvents);
    }

    /// #1730 phase 2 / #1873 (KIP-320): forward a CONSUMER read carrying the owner epoch the consumer last read under. The
    /// owner checks the cursor against its committed epoch starts and answers either the events and the epoch they were
    /// served under, or a typed [StreamError.EpochDiverged]. The default forwards plainly and reports no epoch; only the
    /// production transport ([DefaultStreamForwardClient]) stamps the epoch into the [ReadForward].
    default Promise<ReadForwardResult> readRemoteValidated(NodeId ownerId,
                                                           String streamName,
                                                           int partition,
                                                           long fromOffset,
                                                           int maxEvents,
                                                           Epoch consumerEpoch) {
        return readRemote(ownerId, streamName, partition, fromOffset, maxEvents);
    }

    /// #1235: forward a REPLICATION read — a replica catching up, or a new owner pulling from a survivor —
    /// which the serving node answers up to its APPENDED head instead of its visible position. The default
    /// forwards plainly; it matters only on the production transport ({@link DefaultStreamForwardClient}),
    /// which stamps `catchup` into the {@link ReadForward} message. Test fakes and {@link #NOOP} inherit the
    /// plain forward.
    default Promise<ReadForwardResult> readRemoteCatchup(NodeId sourceId,
                                                         String streamName,
                                                         int partition,
                                                         long fromOffset,
                                                         int maxEvents) {
        return readRemote(sourceId, streamName, partition, fromOffset, maxEvents);
    }

    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onPublishForwardResponse(PublishForwardResponse response);

    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    void onReadForwardResponse(ReadForwardResponse response);

    /// #2590 P1: forward a control-log batch to the partition's owner as ONE message; one outcome per record, in order.
    /// The default sends nothing and refuses every record ([ControlLogError.General#FORWARD_UNSUPPORTED]): nothing was
    /// appended. Only the production transport ([DefaultStreamForwardClient]) forwards.
    default Promise<List<ControlOutcome>> appendControlRemote(NodeId owner,
                                                              String streamName,
                                                              int partition,
                                                              List<ControlRecord> records) {
        return Promise.success(Collections.nCopies(records.size(),
                                                   new ControlOutcome.Refused(ControlLogError.General.FORWARD_UNSUPPORTED)));
    }

    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    @Contract
    default void onControlAppendForwardResponse(ControlAppendForwardResponse response) {}

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

    /// `bounds` (#1333): the serving node's visible span of the partition at answer time; none when it held
    /// no ring, or from a client that does not carry it.
    /// `history` (#1596): the serving node's owner-epoch history on a replica catch-up read, empty otherwise.
    /// `historyVouched`: the source answered as a replica catch-up, so an empty `history` means it keeps none
    /// ([ReadForwardResponse#historyVouched]). The shorter constructors model a source that does.
    /// `ownerEpoch` (#1730 phase 2 / #1873): the owner epoch a validated consumer read was served under, [Epoch#ZERO] on any
    /// other answer.
    record ReadForwardResult(List<RawEventDto> events,
                             boolean truncated,
                             Option<VisibleBounds> bounds,
                             List<ProvenanceEntry> history,
                             boolean historyVouched,
                             Epoch ownerEpoch) {
        public ReadForwardResult {
            events = List.copyOf(events);
            history = List.copyOf(history);
        }

        public ReadForwardResult(List<RawEventDto> events,
                                 boolean truncated,
                                 Option<VisibleBounds> bounds,
                                 List<ProvenanceEntry> history) {
            this(events, truncated, bounds, history, true, Epoch.ZERO);
        }

        public ReadForwardResult(List<RawEventDto> events,
                                 boolean truncated,
                                 Option<VisibleBounds> bounds,
                                 List<ProvenanceEntry> history,
                                 boolean historyVouched) {
            this(events, truncated, bounds, history, historyVouched, Epoch.ZERO);
        }

        public ReadForwardResult(List<RawEventDto> events, boolean truncated) {
            this(events, truncated, Option.none(), List.of(), true, Epoch.ZERO);
        }

        public static ReadForwardResult readForwardResult(List<RawEventDto> events, boolean truncated) {
            return new ReadForwardResult(events, truncated);
        }
    }

    /// #1333: the owner's visible bounds of a partition this node holds no ring for — a `ReadForward` that
    /// asks for no events (`maxEvents = 0`, from beyond any head so retention can never refuse it) and keeps
    /// only the bounds the response carries. Fails when the serving node holds no ring either.
    default Promise<VisibleBounds> boundsRemote(NodeId ownerId, String streamName, int partition) {
        return readRemote(ownerId, streamName, partition, Long.MAX_VALUE, 0).flatMap(result -> result.bounds()
                                                                                                     .toResult(new StreamForwardError.ReadForwardFailed("Node " + ownerId.id()
                                                                                                                                                       + " holds no ring for " + streamName
                                                                                                                                                       + "[" + partition
                                                                                                                                                       + "]"))
                                                                                                     .async());
    }

    /// Where a peer's RING begins, over the CATCH-UP read class (#1730 phase 2): a read from beyond any head asking for no events, so
    /// it reveals no data and is not subject to the consumer-read verification gate (a peer that has not been compared with the
    /// committed owner still answers it). None when the peer holds no ring or reports none.
    default Promise<Option<Long>> ringTailRemote(NodeId peerId, String streamName, int partition) {
        return readRemoteCatchup(peerId, streamName, partition, Long.MAX_VALUE, 0).map(result -> result.bounds()
                                                                                                       .filter(known -> known.earliestRetained() >= 0L)
                                                                                                       .map(VisibleBounds::earliestRetained));
    }

    private static StreamForwardClient noOpClient() {
        return new StreamForwardClient() {
            @Override
            public Promise<Long> publishRemote(NodeId governorId,
                                               String streamName,
                                               int partition,
                                               byte[] payload,
                                               long timestamp) {
                return StreamForwardError.General.GOVERNOR_UNAVAILABLE.promise();
            }

            @Override
            public Promise<ReadForwardResult> readRemote(NodeId replicaId,
                                                         String streamName,
                                                         int partition,
                                                         long fromOffset,
                                                         int maxEvents) {
                return STREAM_FORWARD_UNAVAILABLE.promise();
            }

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onPublishForwardResponse(PublishForwardResponse response) {}

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onReadForwardResponse(ReadForwardResponse response) {}
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

    private final ConcurrentHashMap<String, PendingControl> pendingControl = new ConcurrentHashMap<>();

    /// A forwarded control batch awaiting its answer, and how many records it carried.
    private record PendingControl(Promise<List<ControlOutcome>> promise, int count) {}

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

    @Override
    public Promise<Long> publishRemote(NodeId governorId,
                                       String streamName,
                                       int partition,
                                       byte[] payload,
                                       long timestamp) {
        return publishRemote(governorId, streamName, partition, payload, timestamp, false);
    }

    @Override
    public Promise<Long> publishRemote(NodeId governorId,
                                       String streamName,
                                       int partition,
                                       byte[] payload,
                                       long timestamp,
                                       boolean systemWriter) {
        var correlationId = UUID.randomUUID().toString();
        Promise<Long> promise = Promise.promise();

        pendingRequests.put(correlationId, promise);
        // The wait for the ack — not the write itself — is capped by the ambient request budget:
        // a client-driven publish stops waiting when its caller stops waiting, while background
        // callers (no bound scope) keep the configured timeout unchanged.
        var publishWait = Deadline.current().bounded(publishTimeout);

        SharedScheduler.schedule(() -> timeoutRequest(correlationId), publishWait);
        var message = publishForward(selfNodeId, correlationId, streamName, partition, payload, timestamp, systemWriter);
        // The frame must not outlive the wait that just started (#1996): delivered later it would publish an event
        // whose caller was already told the outcome is unknown.
        transport.send(governorId, message, publishWait);
        log.trace("Sent PublishForward to {} for {}[{}] correlationId={}",
                  governorId,
                  streamName,
                  partition,
                  correlationId);

        return promise;
    }

    @Override
    public Promise<ReadForwardResult> readRemote(NodeId replicaId,
                                                 String streamName,
                                                 int partition,
                                                 long fromOffset,
                                                 int maxEvents) {
        return readRemote(replicaId, streamName, partition, fromOffset, maxEvents, ReadPreference.GOVERNOR);
    }

    @Override
    public Promise<ReadForwardResult> readRemote(NodeId replicaId,
                                                 String streamName,
                                                 int partition,
                                                 long fromOffset,
                                                 int maxEvents,
                                                 ReadPreference preference) {
        return sendRead(replicaId,
                        readForward(selfNodeId,
                                    UUID.randomUUID().toString(),
                                    streamName,
                                    partition,
                                    fromOffset,
                                    maxEvents,
                                    preference == ReadPreference.LINEARIZABLE),
                        preference.name());
    }

    @Override
    public Promise<ReadForwardResult> readRemoteValidated(NodeId ownerId,
                                                          String streamName,
                                                          int partition,
                                                          long fromOffset,
                                                          int maxEvents,
                                                          Epoch consumerEpoch) {
        return sendRead(ownerId,
                        validatedReadForward(selfNodeId,
                                             UUID.randomUUID().toString(),
                                             streamName,
                                             partition,
                                             fromOffset,
                                             maxEvents,
                                             consumerEpoch),
                        "VALIDATED");
    }

    @Override
    public Promise<ReadForwardResult> readRemoteCatchup(NodeId sourceId,
                                                        String streamName,
                                                        int partition,
                                                        long fromOffset,
                                                        int maxEvents) {
        return sendRead(sourceId,
                        readForward(selfNodeId,
                                    UUID.randomUUID().toString(),
                                    streamName,
                                    partition,
                                    fromOffset,
                                    maxEvents,
                                    false,
                                    true),
                        "CATCHUP");
    }

    private Promise<ReadForwardResult> sendRead(NodeId target, ReadForward message, String readClass) {
        metrics.recordAttempt();
        Promise<ReadForwardResult> promise = Promise.promise();

        pendingReads.put(message.correlationId(), promise);
        var readWait = Deadline.current().bounded(readTimeout);

        SharedScheduler.schedule(() -> timeoutRead(message.correlationId()), readWait);
        // #2574: a read the transport did not send fails at once; its scheduled timeout then finds no pending entry.
        // detached: nothing chains on the outcome; the failure resolves the caller's promise through the pending-entry
        // removal, which races the response and the timeout safely (whichever removes the entry resolves it).
        transport.send(target, message, readWait)
                 .onSuccess(outcome -> failReadIfNotSent(outcome,
                                                         message.correlationId()));
        log.trace("Sent ReadForward to {} for {}[{}] fromOffset={} maxEvents={} correlationId={} preference={}",
                  target,
                  message.streamName(),
                  message.partition(),
                  message.fromOffset(),
                  message.maxEvents(),
                  message.correlationId(),
                  readClass);

        return promise;
    }

    /// #2590 P1: one [ControlAppendForward] for the whole batch. The wait is bounded like a publish's (#1996: the frame
    /// does not outlive it); a batch not answered in time, or answered with the wrong number of outcomes, is an unknown
    /// outcome for every record, never a refusal: the owner may have appended it.
    @Override
    public Promise<List<ControlOutcome>> appendControlRemote(NodeId owner,
                                                             String streamName,
                                                             int partition,
                                                             List<ControlRecord> records) {
        var correlationId = UUID.randomUUID().toString();
        Promise<List<ControlOutcome>> promise = Promise.promise();

        pendingControl.put(correlationId, new PendingControl(promise, records.size()));
        var wait = Deadline.current().bounded(publishTimeout);

        SharedScheduler.schedule(() -> timeoutControl(correlationId), wait);
        transport.send(owner,
                       ControlAppendForward.controlAppendForward(selfNodeId,
                                                                 correlationId,
                                                                 streamName,
                                                                 partition,
                                                                 records.stream()
                                                                        .map(ControlRecordDto::fromRecord)
                                                                        .toList()),
                       wait);

        return promise;
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    @Contract
    public void onControlAppendForwardResponse(ControlAppendForwardResponse response) {
        option(pendingControl.remove(response.correlationId())).onEmpty(() -> log.debug("Received ControlAppendForwardResponse for unknown correlationId: {}",
                                                                                        response.correlationId()))
              .onPresent(pending -> pending.promise()
                                           .succeed(controlOutcomes(pending, response)));
    }

    private static List<ControlOutcome> controlOutcomes(PendingControl pending, ControlAppendForwardResponse response) {
        return response.outcomes()
                       .size() == pending.count()
               ? response.outcomes()
                         .stream()
                         .map(ControlOutcomeDto::toOutcome)
                         .toList()
               : unknownControlOutcomes(pending.count(),
                                        new StreamForwardError.RemotePublishFailed("answered " + response.outcomes()
                                                                                                         .size()
                                                                                  + " outcomes for " + pending.count()
                                                                                  + " records"));
    }

    private static List<ControlOutcome> unknownControlOutcomes(int count, Cause cause) {
        return Collections.nCopies(count, new ControlOutcome.OutcomeUnknown(cause));
    }

    private void timeoutControl(String correlationId) {
        option(pendingControl.remove(correlationId)).onPresent(pending -> pending.promise()
                                                                                 .succeed(unknownControlOutcomes(pending.count(),
                                                                                                                 ControlLogError.General.FORWARD_TIMEOUT)));
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onPublishForwardResponse(PublishForwardResponse response) {
        option(pendingRequests.remove(response.correlationId())).onEmpty(() -> logOrphanedPublishResponse(response))
              .onPresent(promise -> resolveFromResponse(promise, response));
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onReadForwardResponse(ReadForwardResponse response) {
        option(pendingReads.remove(response.correlationId())).onEmpty(() -> logOrphanedReadResponse(response))
              .onPresent(promise -> resolveFromReadResponse(promise, response));
    }

    private void resolveFromResponse(Promise<Long> promise, PublishForwardResponse response) {
        if (response.success()) {
            promise.succeed(response.offset());
        } else {
            promise.resolve(publishFailureCause(response).result());
        }
    }

    /// Rebuild the typed forward-publish cause from the wire response, preserving the owner's
    /// retryable/permanent classification (write-forward race fix): a `retryable` response becomes
    /// {@link StreamForwardError.RemotePublishRetryable} so the forwarder bounded-retries, an
    /// `outcomeUnknown` response (#1236 — the owner appended, its barrier did not confirm) becomes
    /// [PublishOutcomeUnknown], otherwise a permanent {@link StreamForwardError.RemotePublishFailed}.
    private static Cause publishFailureCause(PublishForwardResponse response) {
        if (response.retryable()) {
            return new StreamForwardError.RemotePublishRetryable(response.errorMessage());
        }

        return response.outcomeUnknown()
               ? PublishOutcomeUnknown.FACTORY.apply(new StreamForwardError.RemotePublishFailed(response.errorMessage()))
               : new StreamForwardError.RemotePublishFailed(response.errorMessage());
    }

    /// The typed divergence of a validated read survives the wire as itself (#1730 phase 2 / #1873): the consumer re-seeks
    /// on it, so it is never rebuilt from a message. So does every other refusal a read is known to raise (#1967): it is
    /// rebuilt as the cause a local read of the same partition gives, so the route that maps that cause maps this one the
    /// same way. Only a failure outside that set is a [StreamForwardError.ReadForwardFailed] carrying the owner's text.
    private static Cause readFailureCause(ReadForwardResponse response) {
        return response.epochDiverged()
               ? new StreamError.EpochDiverged(response.ownerEpoch(),
                                               response.divergenceResumeAt(),
                                               response.divergenceLossFrom())
               : response.refusal()
                         .<Cause> map(refusal -> refusal.toCause(response.errorMessage()))
                         .or(() -> new StreamForwardError.ReadForwardFailed(response.errorMessage()));
    }

    private void resolveFromReadResponse(Promise<ReadForwardResult> promise, ReadForwardResponse response) {
        if (response.success()) {
            metrics.recordSuccess();
            promise.succeed(new ReadForwardResult(response.events(),
                                                  response.truncated(),
                                                  response.bounds(),
                                                  response.history(),
                                                  response.historyVouched(),
                                                  response.ownerEpoch()));
        } else {
            promise.resolve(readFailureCause(response).result());
        }
    }

    /// #1236: the forward was SENT, so the owner may have appended it before the response was lost or
    /// late — a publish-forward timeout is an unknown outcome, never a clean failure.
    private void timeoutRequest(String correlationId) {
        option(pendingRequests.remove(correlationId)).onPresent(promise -> promise.resolve(PublishOutcomeUnknown.FACTORY.apply(FORWARD_TIMEOUT).result()));
    }

    private void timeoutRead(String correlationId) {
        option(pendingReads.remove(correlationId)).onPresent(promise -> {
            metrics.recordTimeout();
            promise.resolve(READ_FORWARD_TIMEOUT.result());
        });
    }

    /// #2574 (CTO, know de7d2783de): a read whose frame was not sent fails at once. Refused under backpressure, a dead
    /// connection or no peer state is the transient [StreamForwardError.ForwardNotSent] every re-reading caller backs off
    /// on; an encode failure is the hard [StreamForwardError.ForwardNotEncoded], which no retry can change. `Sent` keeps the
    /// read waiting for its answer. (A read is safe to fail on `ConnectionDead` even if the offline buffer later delivers
    /// the frame: its late answer is orphaned.)
    private static Option<Cause> notSentCause(WriteOutcome outcome) {
        return switch (outcome) {
            case WriteOutcome.Sent _ -> Option.none();
            case WriteOutcome.BackpressureRefused _, WriteOutcome.ConnectionDead _, WriteOutcome.NoPeerState _ -> Option.some(StreamForwardError.ForwardNotSent.FACTORY.apply(outcome));
            case WriteOutcome.EncodeFailed _ -> Option.some(StreamForwardError.ForwardNotEncoded.FACTORY.apply(outcome));
        };
    }

    private void failReadIfNotSent(WriteOutcome outcome, String correlationId) {
        notSentCause(outcome).onPresent(cause -> failRead(correlationId, cause));
    }

    private void failRead(String correlationId, Cause cause) {
        option(pendingReads.remove(correlationId)).onPresent(promise -> notSent(promise, cause));
    }

    private static void notSent(Promise<ReadForwardResult> promise, Cause cause) {
        log.debug("Stream forward read not sent: {}", cause.message());
        promise.resolve(cause.result());
    }

    private static void logOrphanedPublishResponse(PublishForwardResponse response) {
        log.debug("Received PublishForwardResponse for unknown correlationId: {}", response.correlationId());
    }

    private static void logOrphanedReadResponse(ReadForwardResponse response) {
        log.debug("Received ReadForwardResponse for unknown correlationId: {}", response.correlationId());
    }
}
