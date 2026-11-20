// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.forward;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.VisibleBounds;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.forward.StreamForwardClient.streamForwardClient;

/// M-9a0 (design stream-mem §9 L1389): the two additive forward-client reads. A linearizable read that carries the
/// consumer's epoch and answers the epoch it was served under, and a bounds read that answers the life with the bounds.
class StreamForwardClientLifeReadsTest {
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId OWNER = NodeId.randomNodeId();
    private static final String STREAM = "events";
    private static final int PARTITION = 3;
    private static final long FROM_OFFSET = 7L;
    private static final int MAX_EVENTS = 11;
    private static final Epoch LIFE = Epoch.epoch(4L, 5L, 6L);

    private List<StreamForwardMessage> sent;
    private StreamForwardClient client;

    @BeforeEach
    void setUp() {
        sent = new ArrayList<>();
        client = streamForwardClient(SELF,
                                     AcceptingStreamForwardTransport.accepting((_, message) -> sent.add(message)),
                                     TimeSpan.timeSpan(5).seconds());
    }

    @Test
    void linearizableLifeRead_sendsOneLinearizableForwardCarryingTheEpoch() {
        client.readRemoteLinearizable(OWNER, STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS, LIFE);

        assertThat(sent).hasSize(1);
        var forward = (ReadForward) sent.getFirst();
        assertThat(forward.linearizable()).isTrue();
        assertThat(forward.catchup()).isFalse();
        assertThat(forward.consumerEpoch()).isEqualTo(LIFE);
        assertThat(forward.fromOffset()).isEqualTo(FROM_OFFSET);
        assertThat(forward.maxEvents()).isEqualTo(MAX_EVENTS);
        assertThat(forward.streamName()).isEqualTo(STREAM);
        assertThat(forward.partition()).isEqualTo(PARTITION);
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
        var forward = (ReadForward) sent.getFirst();
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

    @Test
    void boundsWithLife_failsAsBoundsRemoteDoes_whenNoBoundsCame() {
        var promise = client.boundsWithLifeRemote(OWNER, STREAM, PARTITION);

        client.onReadForwardResponse(ReadForwardResponse.successResponse(OWNER, correlationId(), List.of())
                                                        .withOwnerEpoch(LIFE));

        var result = promise.await();
        assertThat(result.isSuccess()).isFalse();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(StreamForwardError.ReadForwardFailed.class));
    }

    private String correlationId() {
        return ((ReadForward) sent.getFirst()).correlationId();
    }
}
