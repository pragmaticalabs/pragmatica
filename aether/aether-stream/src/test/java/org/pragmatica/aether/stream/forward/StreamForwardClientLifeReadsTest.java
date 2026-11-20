// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.forward;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.VisibleBounds;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.testlogging.LogCapture;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.forward.StreamForwardClient.streamForwardClient;

/// M-9a0 (design stream-mem §9 L1389): the two additive forward-client reads. A linearizable read that carries the
/// consumer's epoch and answers the epoch it was served under, and a bounds read that answers the life with the bounds.
/// The client half only: what a real owner answers to the linearizable read is pinned in
/// [StreamForwardLifeReadsHandlerTest].
class StreamForwardClientLifeReadsTest {
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId OWNER = NodeId.randomNodeId();
    private static final String STREAM = "events";
    private static final int PARTITION = 3;
    private static final long FROM_OFFSET = 7L;
    private static final int MAX_EVENTS = 11;
    private static final Epoch LIFE = Epoch.epoch(4L, 5L, 6L);
    private static final String READ_CLASS_LABEL = "preference=LINEARIZABLE_VALIDATED";

    private record Sent(NodeId target, StreamForwardMessage message) {}

    private List<Sent> sent;
    private StreamForwardClient client;
    private LogCapture clientLog;

    @BeforeEach
    void setUp() {
        sent = new ArrayList<>();
        clientLog = LogCapture.capture(StreamForwardClient.class, Level.TRACE, false);
        client = streamForwardClient(SELF,
                                     AcceptingStreamForwardTransport.accepting((target, message) -> sent.add(new Sent(target,
                                                                                                                        message))),
                                     TimeSpan.timeSpan(5).seconds());
    }

    @AfterEach
    void tearDown() {
        clientLog.close();
    }

    @Test
    void linearizableLifeRead_sendsOneLinearizableForwardCarryingTheEpoch() {
        client.readRemoteLinearizable(OWNER, STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS, LIFE);

        assertThat(sent).hasSize(1);
        var forward = forwardAt(0);
        assertThat(forward.linearizable()).isTrue();
        assertThat(forward.catchup()).isFalse();
        assertThat(forward.consumerEpoch()).isEqualTo(LIFE);
        assertThat(forward.fromOffset()).isEqualTo(FROM_OFFSET);
        assertThat(forward.maxEvents()).isEqualTo(MAX_EVENTS);
        assertThat(forward.streamName()).isEqualTo(STREAM);
        assertThat(forward.partition()).isEqualTo(PARTITION);
    }

    /// Survivors 1 and 2 of v3462b: the forward names this node as its sender, goes to the owner, and carries a fresh
    /// random correlation id (a constant id would let a second read overwrite the first one's pending entry).
    @Test
    void linearizableLifeRead_isSentFromSelfToTheOwner_underAFreshRandomCorrelationId() {
        client.readRemoteLinearizable(OWNER, STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS, LIFE);
        client.readRemoteLinearizable(OWNER, STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS, LIFE);

        assertThat(sent).hasSize(2);
        assertThat(sent).allSatisfy(each -> assertThat(each.target()).isEqualTo(OWNER));
        assertThat(sent).allSatisfy(each -> assertThat(((ReadForward) each.message()).sender()).isEqualTo(SELF));
        assertThat(UUID.fromString(forwardAt(0).correlationId())).isNotNull();
        assertThat(UUID.fromString(forwardAt(1).correlationId())).isNotNull();
        assertThat(forwardAt(0).correlationId()).isNotEqualTo(forwardAt(1).correlationId());
    }

    /// Survivor 3 of v3462b: the read class the client logs for the new read is its own, not another read's.
    @Test
    void linearizableLifeRead_logsItsOwnReadClass() {
        client.readRemoteLinearizable(OWNER, STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS, LIFE);

        assertThat(clientLog.messages()).anyMatch(message -> message.contains(READ_CLASS_LABEL));
    }

    @Test
    void linearizableLifeRead_resolvesWithTheServedEpoch() {
        var promise = client.readRemoteLinearizable(OWNER, STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS, LIFE);
        var events = List.of(new RawEventDto(FROM_OFFSET, 100L, "x".getBytes()));

        client.onReadForwardResponse(ReadForwardResponse.successResponse(OWNER, correlationId(), events)
                                                        .withOwnerEpoch(LIFE));

        var result = promise.await();
        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(served -> {
            assertThat(served.events()).hasSize(1);
            assertThat(served.events().getFirst().offset()).isEqualTo(FROM_OFFSET);
            assertThat(served.ownerEpoch()).isEqualTo(LIFE);
        });
    }

    @Test
    void boundsWithLife_sendsTheZeroEventProbe_andReturnsBoundsAndLife() {
        var promise = client.boundsWithLifeRemote(OWNER, STREAM, PARTITION);

        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().target()).isEqualTo(OWNER);
        var forward = forwardAt(0);
        assertThat(forward.streamName()).isEqualTo(STREAM);
        assertThat(forward.partition()).isEqualTo(PARTITION);
        assertThat(forward.fromOffset()).isEqualTo(Long.MAX_VALUE);
        assertThat(forward.maxEvents()).isZero();
        assertThat(forward.linearizable()).isFalse();
        assertThat(forward.catchup()).isFalse();
        assertThat(forward.consumerEpoch()).isEqualTo(Epoch.ZERO);

        var bounds = VisibleBounds.visibleBounds(2L, 9L);
        client.onReadForwardResponse(ReadForwardResponse.successResponse(OWNER, correlationId(), List.of(), bounds)
                                                        .withOwnerEpoch(LIFE));

        var result = promise.await();
        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(under -> {
            assertThat(under.bounds()).isEqualTo(bounds);
            assertThat(under.life()).isEqualTo(LIFE);
        });
    }

    /// Survivor 5 of v3462b: the failure names the node, the stream and the partition it asked about.
    @Test
    void boundsWithLife_failsAsBoundsRemoteDoes_whenNoBoundsCame() {
        var promise = client.boundsWithLifeRemote(OWNER, STREAM, PARTITION);

        client.onReadForwardResponse(ReadForwardResponse.successResponse(OWNER, correlationId(), List.of())
                                                        .withOwnerEpoch(LIFE));

        var result = promise.await();
        assertThat(result.isSuccess()).isFalse();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(StreamForwardError.ReadForwardFailed.class);
            assertThat(cause.message()).isEqualTo("Remote read failed: Node " + OWNER.id() + " holds no ring for "
                                                  + STREAM + "[" + PARTITION + "]");
        });
    }

    /// Survivor 4 of v3462b: the documented default, through an implementation that overrides neither
    /// `readRemoteLinearizable` nor the preference-tagged `readRemote`. It falls back to the `LINEARIZABLE`-tagged
    /// forward, which in turn falls back to the plain forward; `life` is dropped silently (no parameter carries it).
    @Test
    void defaultLinearizableLifeRead_fallsBackToThePlainForward_andDropsLife() {
        var plain = new PlainOnlyClient();

        var result = plain.readRemoteLinearizable(OWNER, STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS, LIFE);

        assertThat(plain.calls).containsExactly(new PlainCall(OWNER, STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS));
        result.await()
              .onSuccess(_ -> fail("the plain read's own result must come back"))
              .onFailure(cause -> assertThat(cause.message()).isEqualTo(PlainOnlyClient.MARK.message()));
    }

    /// Survivor 4 of v3462b, the other layer: the default asks `readRemote` for the `LINEARIZABLE` class, with the
    /// stream, partition, offset and limit it was given.
    @Test
    void defaultLinearizableLifeRead_asksForTheLinearizableReadClass() {
        var tagging = new TaggingClient();

        tagging.readRemoteLinearizable(OWNER, STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS, LIFE);

        assertThat(tagging.taggedCalls).containsExactly(new TaggedCall(OWNER,
                                                                 STREAM,
                                                                 PARTITION,
                                                                 FROM_OFFSET,
                                                                 MAX_EVENTS,
                                                                 ReadPreference.LINEARIZABLE));
    }

    private ReadForward forwardAt(int index) {
        return (ReadForward) sent.get(index).message();
    }

    private String correlationId() {
        return forwardAt(0).correlationId();
    }

    private record PlainCall(NodeId target, String stream, int partition, long fromOffset, int maxEvents) {}

    private record TaggedCall(NodeId target,
                              String stream,
                              int partition,
                              long fromOffset,
                              int maxEvents,
                              ReadPreference preference) {}

    /// Overrides only the abstract plain `readRemote`: every other read is the interface default.
    private static class PlainOnlyClient implements StreamForwardClient {
        static final Cause MARK = Causes.cause("plain-read-result");
        final List<PlainCall> calls = new ArrayList<>();

        @Override
        public Promise<Long> publishRemote(NodeId governorId, String streamName, int partition, byte[] payload, long timestamp) {
            return MARK.promise();
        }

        @Override
        public Promise<ReadForwardResult> readRemote(NodeId replicaId,
                                                     String streamName,
                                                     int partition,
                                                     long fromOffset,
                                                     int maxEvents) {
            calls.add(new PlainCall(replicaId, streamName, partition, fromOffset, maxEvents));
            return MARK.promise();
        }

        @Override
        @SuppressWarnings("JBCT-RET-01")
        public void onPublishForwardResponse(PublishForwardResponse response) {}

        @Override
        @SuppressWarnings("JBCT-RET-01")
        public void onReadForwardResponse(ReadForwardResponse response) {}
    }

    /// Also overrides the preference-tagged `readRemote`, to see which class the default asks for.
    private static final class TaggingClient extends PlainOnlyClient {
        final List<TaggedCall> taggedCalls = new ArrayList<>();

        @Override
        public Promise<ReadForwardResult> readRemote(NodeId replicaId,
                                                     String streamName,
                                                     int partition,
                                                     long fromOffset,
                                                     int maxEvents,
                                                     ReadPreference preference) {
            taggedCalls.add(new TaggedCall(replicaId, streamName, partition, fromOffset, maxEvents, preference));
            return MARK.promise();
        }
    }
}
