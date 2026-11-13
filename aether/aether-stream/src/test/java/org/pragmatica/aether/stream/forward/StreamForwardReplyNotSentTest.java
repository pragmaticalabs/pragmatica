// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.forward;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.StreamConfig.streamConfig;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.forward.StreamForwardHandler.streamForwardHandler;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.ControlAppendForward.controlAppendForward;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward.publishForward;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward.readForward;

/// #2583 P2: a reply the handler sends to a forwarding requester reports its [WriteOutcome], and a reply the transport did not
/// accept moves [StreamReadForwardMetrics#recordReplyNotSent] instead of vanishing into the requester's timeout. Every
/// reply shape the handler sends is driven here; an accepted reply moves nothing. Red before P2: the handler discarded the
/// outcome, so the counter stayed at zero for a refused reply.
class StreamForwardReplyNotSentTest {
    private static final NodeId OWNER = NodeId.randomNodeId();
    private static final NodeId REQUESTER = NodeId.randomNodeId();
    private static final String STREAM = "events";
    private static final String CORRELATION_ID = "corr-p2";
    private static final byte[] PAYLOAD = "p".getBytes();

    private StreamPartitionManager partitionManager;
    private InMemoryStreamReadForwardMetrics metrics;
    private List<StreamForwardMessage> replies;

    @BeforeEach
    void setUp() {
        partitionManager = streamPartitionManager(Long.MAX_VALUE);
        metrics = (InMemoryStreamReadForwardMetrics) StreamReadForwardMetrics.inMemory();
        replies = new CopyOnWriteArrayList<>();
        partitionManager.createStream(streamConfig(STREAM));
    }

    private StreamForwardHandler handlerAnswering(WriteOutcome outcome) {
        StreamForwardTransport transport = (target, message) -> answer(message, outcome);

        return streamForwardHandler(OWNER, partitionManager, transport, 1_048_576L, metrics);
    }

    private Promise<WriteOutcome> answer(StreamForwardMessage message, WriteOutcome outcome) {
        replies.add(message);

        return Promise.success(outcome);
    }

    private void assertRefusedReplyCounted(Consumer<StreamForwardHandler> trigger) {
        trigger.accept(handlerAnswering(new WriteOutcome.BackpressureRefused(REQUESTER)));

        assertThat(replies).as("the reply was handed to the transport").hasSize(1);
        assertThat(metrics.replyNotSent()).as("refused reply counted").isEqualTo(1L);
    }

    @Test
    void publishSuccessReply_refused_isCounted() {
        assertRefusedReplyCounted(handler -> handler.onPublishForward(publishForward(REQUESTER, CORRELATION_ID, STREAM, 0, PAYLOAD, 1L)));
    }

    @Test
    void publishRetryableReply_refused_isCounted() {
        assertRefusedReplyCounted(handler -> handler.onPublishForward(publishForward(REQUESTER, CORRELATION_ID, "absent", 0, PAYLOAD, 1L)));
    }

    @Test
    void publishFailureReply_refused_isCounted() {
        assertRefusedReplyCounted(handler -> handler.onPublishForward(publishForward(REQUESTER, CORRELATION_ID, STREAM, 99, PAYLOAD, 1L)));
    }

    @Test
    void readSuccessReply_refused_isCounted() {
        assertRefusedReplyCounted(handler -> handler.onReadForward(readForward(REQUESTER, CORRELATION_ID, STREAM, 0, 0L, 10)));
    }

    @Test
    void readFailureReply_refused_isCounted() {
        assertRefusedReplyCounted(handler -> handler.onReadForward(readForward(REQUESTER, CORRELATION_ID, STREAM, 99, 0L, 10)));
    }

    @Test
    void controlAppendReply_refused_isCounted() {
        assertRefusedReplyCounted(handler -> handler.onControlAppendForward(controlAppendForward(REQUESTER, CORRELATION_ID, STREAM, 0, List.of())));
    }

    @Test
    void everyNonSentOutcome_isCounted() {
        var outcomes = List.of(new WriteOutcome.BackpressureRefused(REQUESTER),
                               new WriteOutcome.ConnectionDead(REQUESTER),
                               new WriteOutcome.NoPeerState(REQUESTER),
                               new WriteOutcome.EncodeFailed(REQUESTER, "ReadForwardResponse"));

        outcomes.forEach(outcome -> handlerAnswering(outcome).onReadForward(readForward(REQUESTER, CORRELATION_ID, STREAM, 0, 0L, 10)));

        assertThat(metrics.replyNotSent()).isEqualTo(outcomes.size());
    }

    @Test
    void acceptedReply_isNotCounted() {
        handlerAnswering(new WriteOutcome.Sent(REQUESTER)).onReadForward(readForward(REQUESTER, CORRELATION_ID, STREAM, 0, 0L, 10));

        assertThat(replies).hasSize(1);
        assertThat(metrics.replyNotSent()).isZero();
    }
}
