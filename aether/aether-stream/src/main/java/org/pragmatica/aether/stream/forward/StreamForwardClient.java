// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.slice.PublishOutcomeUnknown;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.stream.VisibleBounds;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
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
import static org.pragmatica.lang.Option.option;


public interface StreamForwardClient {
    Promise<Long> publishRemote(NodeId governorId, String streamName, int partition, byte[] payload, long timestamp);

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
    record ReadForwardResult(List<RawEventDto> events, boolean truncated, Option<VisibleBounds> bounds) {
        public ReadForwardResult {
            events = List.copyOf(events);
        }

        public ReadForwardResult(List<RawEventDto> events, boolean truncated) {
            this(events, truncated, Option.none());
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
        var correlationId = UUID.randomUUID().toString();
        Promise<Long> promise = Promise.promise();

        pendingRequests.put(correlationId, promise);
        // The wait for the ack — not the write itself — is capped by the ambient request budget:
        // a client-driven publish stops waiting when its caller stops waiting, while background
        // callers (no bound scope) keep the configured timeout unchanged.
        SharedScheduler.schedule(() -> timeoutRequest(correlationId),
                                 Deadline.current().bounded(publishTimeout));
        var message = publishForward(selfNodeId, correlationId, streamName, partition, payload, timestamp);

        transport.send(governorId, message);
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
        SharedScheduler.schedule(() -> timeoutRead(message.correlationId()),
                                 Deadline.current().bounded(readTimeout));
        transport.send(target, message);
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

    private void resolveFromReadResponse(Promise<ReadForwardResult> promise, ReadForwardResponse response) {
        if (response.success()) {
            metrics.recordSuccess();
            promise.succeed(new ReadForwardResult(response.events(), response.truncated(), response.bounds()));
        } else {
            promise.resolve(new StreamForwardError.ReadForwardFailed(response.errorMessage()).result());
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

    private static void logOrphanedPublishResponse(PublishForwardResponse response) {
        log.debug("Received PublishForwardResponse for unknown correlationId: {}", response.correlationId());
    }

    private static void logOrphanedReadResponse(ReadForwardResponse response) {
        log.debug("Received ReadForwardResponse for unknown correlationId: {}", response.correlationId());
    }
}
