// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.forward;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Deadline;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.forward.StreamForwardClient.streamForwardClient;


class StreamForwardClientTest {

    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId GOVERNOR = NodeId.randomNodeId();
    private static final String STREAM = "events";
    private static final int PARTITION = 0;
    private static final byte[] PAYLOAD = "data".getBytes();
    private static final long TIMESTAMP = 100L;

    private List<SentMessage> sentMessages;
    private StreamForwardClient client;

    @BeforeEach
    void setUp() {
        sentMessages = new ArrayList<>();
        StreamForwardTransport capturingTransport = (target, message) -> sentMessages.add(new SentMessage(target, message));
        client = streamForwardClient(SELF, capturingTransport, TimeSpan.timeSpan(5).seconds());
    }

    @Nested
    class PublishRemoteTests {

        @Test
        void publishRemote_sendsForwardMessageToGovernor() {
            client.publishRemote(GOVERNOR, STREAM, PARTITION, PAYLOAD, TIMESTAMP);

            assertThat(sentMessages).hasSize(1);
            var sent = sentMessages.getFirst();
            assertThat(sent.target()).isEqualTo(GOVERNOR);
            var forward = (PublishForward) sent.message();
            assertThat(forward.sender()).isEqualTo(SELF);
            assertThat(forward.streamName()).isEqualTo(STREAM);
            assertThat(forward.partition()).isEqualTo(PARTITION);
            assertThat(forward.payload()).isEqualTo(PAYLOAD);
            assertThat(forward.timestamp()).isEqualTo(TIMESTAMP);
        }

        @Test
        void publishRemote_generatesUniqueCorrelationIds() {
            client.publishRemote(GOVERNOR, STREAM, PARTITION, PAYLOAD, TIMESTAMP);
            client.publishRemote(GOVERNOR, STREAM, PARTITION, PAYLOAD, TIMESTAMP);

            assertThat(sentMessages).hasSize(2);
            var id1 = ((PublishForward) sentMessages.get(0).message()).correlationId();
            var id2 = ((PublishForward) sentMessages.get(1).message()).correlationId();
            assertThat(id1).isNotEqualTo(id2);
        }
    }

    @Nested
    class ResponseHandlingTests {

        @Test
        void onPublishForwardResponse_success_resolvesPromiseWithOffset() {
            var promise = client.publishRemote(GOVERNOR, STREAM, PARTITION, PAYLOAD, TIMESTAMP);
            var correlationId = ((PublishForward) sentMessages.getFirst().message()).correlationId();

            client.onPublishForwardResponse(PublishForwardResponse.successResponse(GOVERNOR, correlationId, 42L));

            var result = promise.await();
            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(offset -> assertThat(offset).isEqualTo(42L));
        }

        @Test
        void onPublishForwardResponse_failure_resolvesPromiseWithError() {
            var promise = client.publishRemote(GOVERNOR, STREAM, PARTITION, PAYLOAD, TIMESTAMP);
            var correlationId = ((PublishForward) sentMessages.getFirst().message()).correlationId();

            client.onPublishForwardResponse(PublishForwardResponse.failureResponse(GOVERNOR, correlationId, "stream not found"));

            var result = promise.await();
            assertThat(result.isSuccess()).isFalse();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(StreamForwardError.RemotePublishFailed.class));
        }

        @Test
        void onPublishForwardResponse_retryable_resolvesPromiseWithRetryableError() {
            var promise = client.publishRemote(GOVERNOR, STREAM, PARTITION, PAYLOAD, TIMESTAMP);
            var correlationId = ((PublishForward) sentMessages.getFirst().message()).correlationId();

            client.onPublishForwardResponse(PublishForwardResponse.retryableResponse(GOVERNOR, correlationId, "config not yet visible"));

            var result = promise.await();
            assertThat(result.isSuccess()).isFalse();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(StreamForwardError.RemotePublishRetryable.class));
        }

        @Test
        void onPublishForwardResponse_unknownCorrelationId_ignored() {
            client.onPublishForwardResponse(PublishForwardResponse.successResponse(GOVERNOR, "unknown-id", 99L));

            // No exception, no pending promises affected
        }
    }

    @Nested
    class TimeoutTests {

        @Test
        void publishRemote_timeout_resolvesPromiseWithTimeoutError() {
            var shortTimeoutTransport = (StreamForwardTransport) (_, _) -> {};
            var shortClient = streamForwardClient(SELF, shortTimeoutTransport, TimeSpan.timeSpan(100).millis());

            var promise = shortClient.publishRemote(GOVERNOR, STREAM, PARTITION, PAYLOAD, TIMESTAMP);

            var result = promise.await();
            assertThat(result.isSuccess()).isFalse();
            result.onFailure(cause -> assertThat(cause.message()).contains("timed out"));
        }

        /// Deadline budget: the ack wait is min(configured, remaining). A 60s-configured client under
        /// ~150ms of ambient budget must resolve its unanswered publish in well under the configured
        /// minute — pre-fix (full configured wait regardless of caller budget) turns this red.
        @Test
        void publishRemote_underSmallAmbientBudget_waitsTheBudgetNotTheConfiguredTimeout() {
            var silentTransport = (StreamForwardTransport) (_, _) -> {};
            var longClient = streamForwardClient(SELF, silentTransport, TimeSpan.timeSpan(60).seconds());
            var startedAt = System.nanoTime();

            var result = Deadline.runWith(Deadline.fromWireMillis(150),
                                          () -> longClient.publishRemote(GOVERNOR, STREAM, PARTITION, PAYLOAD, TIMESTAMP))
                                 .await();
            var elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(result.isSuccess()).isFalse();
            assertThat(elapsedMillis)
                .as("ack wait capped by remaining budget, not the 60s configured timeout")
                .isLessThan(10_000);
        }

        @Test
        void readRemote_underSmallAmbientBudget_waitsTheBudgetNotTheConfiguredTimeout() {
            var silentTransport = (StreamForwardTransport) (_, _) -> {};
            var longClient = streamForwardClient(SELF, silentTransport, TimeSpan.timeSpan(60).seconds());
            var startedAt = System.nanoTime();

            var result = Deadline.runWith(Deadline.fromWireMillis(150),
                                          () -> longClient.readRemote(GOVERNOR, STREAM, PARTITION, 0L, 10))
                                 .await();
            var elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(result.isSuccess()).isFalse();
            assertThat(elapsedMillis)
                .as("read wait capped by remaining budget, not the 60s configured timeout")
                .isLessThan(10_000);
        }
    }

    @Nested
    class NoOpTests {

        @Test
        void noopClient_publishRemote_failsImmediately() {
            var result = StreamForwardClient.NOOP.publishRemote(GOVERNOR, STREAM, PARTITION, PAYLOAD, TIMESTAMP).await();

            assertThat(result.isSuccess()).isFalse();
            result.onFailure(cause -> assertThat(cause.message()).contains("governor"));
        }

        // SPEC: §4.5 NOOP readRemote returns STREAM_FORWARD_UNAVAILABLE
        @Test
        void noopClient_readRemote_failsWithUnavailable() {
            var result = StreamForwardClient.NOOP.readRemote(GOVERNOR, STREAM, PARTITION, 0L, 10).await();

            assertThat(result.isSuccess()).isFalse();
            result.onFailure(cause -> assertThat(cause.message()).contains("not available"));
        }
    }

    // SPEC: §11.1 ReadRemoteTests — readRemote_success / timeout / failureResponse / orphanedResponse / multiplePending / separateTimeout
    @Nested
    class ReadRemoteTests {
        private static final long FROM_OFFSET = 0L;
        private static final int MAX_EVENTS = 10;

        @Test
        void readRemote_success_resolvesPromise() {
            var promise = client.readRemote(GOVERNOR, STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS);
            var correlationId = ((ReadForward) sentMessages.getFirst().message()).correlationId();
            var events = List.of(new RawEventDto(5L, 100L, "hello".getBytes()));

            client.onReadForwardResponse(ReadForwardResponse.successResponse(GOVERNOR, correlationId, events));

            var result = promise.await();
            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(r -> {
                assertThat(r.events()).hasSize(1);
                assertThat(r.truncated()).isFalse();
                assertThat(r.events().getFirst().offset()).isEqualTo(5L);
            });
        }

        @Test
        void readRemote_timeout_returnsReadForwardTimeout() {
            var shortTimeoutTransport = (StreamForwardTransport) (_, _) -> {};
            var shortClient = streamForwardClient(SELF,
                                                  shortTimeoutTransport,
                                                  TimeSpan.timeSpan(5).seconds(),
                                                  TimeSpan.timeSpan(100).millis());

            var promise = shortClient.readRemote(GOVERNOR, STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS);

            var result = promise.await();
            assertThat(result.isSuccess()).isFalse();
            result.onFailure(cause -> assertThat(cause.message()).contains("read forward timed out"));
        }

        @Test
        void readRemote_failureResponse_returnsReadForwardFailed() {
            var promise = client.readRemote(GOVERNOR, STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS);
            var correlationId = ((ReadForward) sentMessages.getFirst().message()).correlationId();

            client.onReadForwardResponse(ReadForwardResponse.failureResponse(GOVERNOR,
                                                                              correlationId,
                                                                              "partition not local"));

            var result = promise.await();
            assertThat(result.isSuccess()).isFalse();
            result.onFailure(cause -> assertThat(cause.message()).contains("partition not local"));
        }

        @Test
        void readRemote_orphanedResponse_isDropped() {
            // Arrives for an unknown correlationId — no crash, pending reads unaffected
            client.onReadForwardResponse(ReadForwardResponse.successResponse(GOVERNOR, "unknown-id", List.of()));
        }

        @Test
        void readRemote_multiplePending_correlatedByCorrelationId() {
            var p1 = client.readRemote(GOVERNOR, STREAM, 0, 0L, 5);
            var p2 = client.readRemote(GOVERNOR, STREAM, 1, 0L, 5);
            assertThat(sentMessages).hasSize(2);
            var id1 = ((ReadForward) sentMessages.get(0).message()).correlationId();
            var id2 = ((ReadForward) sentMessages.get(1).message()).correlationId();
            assertThat(id1).isNotEqualTo(id2);

            var e1 = List.of(new RawEventDto(0L, 100L, "a".getBytes()));
            var e2 = List.of(new RawEventDto(0L, 200L, "b".getBytes()));
            // Resolve p2 first to prove independence
            client.onReadForwardResponse(ReadForwardResponse.successResponse(GOVERNOR, id2, e2));
            client.onReadForwardResponse(ReadForwardResponse.successResponse(GOVERNOR, id1, e1));

            var r1 = p1.await();
            var r2 = p2.await();
            r1.onSuccess(r -> assertThat(r.events().getFirst().data()).isEqualTo("a".getBytes()));
            r2.onSuccess(r -> assertThat(r.events().getFirst().data()).isEqualTo("b".getBytes()));
        }

        @Test
        void readRemote_separateTimeoutFromPublish_honored() {
            var timeoutTransport = (StreamForwardTransport) (_, _) -> {};
            // Publish timeout long, read timeout short — prove they're independent
            var splitClient = streamForwardClient(SELF,
                                                  timeoutTransport,
                                                  TimeSpan.timeSpan(30).seconds(),
                                                  TimeSpan.timeSpan(150).millis());

            var readPromise = splitClient.readRemote(GOVERNOR, STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS);

            var result = readPromise.await();
            assertThat(result.isSuccess()).isFalse();
            result.onFailure(cause -> assertThat(cause.message()).contains("read forward timed out"));
        }
    }

    record SentMessage(NodeId target, StreamForwardMessage message) {}
}
