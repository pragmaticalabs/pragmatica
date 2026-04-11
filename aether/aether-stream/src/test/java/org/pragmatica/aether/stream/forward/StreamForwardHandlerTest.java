package org.pragmatica.aether.stream.forward;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.StreamConfig.streamConfig;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.forward.StreamForwardHandler.streamForwardHandler;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward.publishForward;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward.readForward;


class StreamForwardHandlerTest {

    private static final NodeId GOVERNOR = NodeId.randomNodeId();
    private static final NodeId REQUESTER = NodeId.randomNodeId();
    private static final String STREAM = "test-stream";
    private static final int PARTITION = 0;
    private static final byte[] PAYLOAD = "hello".getBytes();
    private static final long TIMESTAMP = 42L;
    private static final String CORRELATION_ID = "corr-001";

    private StreamPartitionManager partitionManager;
    private List<SentMessage> sentMessages;
    private StreamForwardHandler handler;

    @BeforeEach
    void setUp() {
        partitionManager = streamPartitionManager(Long.MAX_VALUE);
        sentMessages = new ArrayList<>();
        StreamForwardTransport capturingTransport = (target, message) -> sentMessages.add(new SentMessage(target, message));
        handler = streamForwardHandler(GOVERNOR, partitionManager, capturingTransport);
    }

    @Nested
    class SuccessTests {

        @Test
        void onPublishForward_publishesLocallyAndRespondsWithOffset() {
            partitionManager.createStream(streamConfig(STREAM));
            var request = publishForward(REQUESTER, CORRELATION_ID, STREAM, PARTITION, PAYLOAD, TIMESTAMP);

            handler.onPublishForward(request);

            assertThat(sentMessages).hasSize(1);
            var sent = sentMessages.getFirst();
            assertThat(sent.target()).isEqualTo(REQUESTER);
            var response = (PublishForwardResponse) sent.message();
            assertThat(response.success()).isTrue();
            assertThat(response.offset()).isGreaterThanOrEqualTo(0L);
            assertThat(response.correlationId()).isEqualTo(CORRELATION_ID);
            assertThat(response.sender()).isEqualTo(GOVERNOR);
        }

        @Test
        void onPublishForward_eventIsStoredLocally() {
            partitionManager.createStream(streamConfig(STREAM));
            var request = publishForward(REQUESTER, CORRELATION_ID, STREAM, PARTITION, PAYLOAD, TIMESTAMP);

            handler.onPublishForward(request);

            var events = partitionManager.readLocal(STREAM, PARTITION, 0L, 10);
            assertThat(events.isSuccess()).isTrue();
            events.onSuccess(list -> {
                assertThat(list).hasSize(1);
                assertThat(list.getFirst().data()).isEqualTo(PAYLOAD);
            });
        }
    }

    @Nested
    class FailureTests {

        @Test
        void onPublishForward_streamNotFound_respondsWithError() {
            var request = publishForward(REQUESTER, CORRELATION_ID, "nonexistent", PARTITION, PAYLOAD, TIMESTAMP);

            handler.onPublishForward(request);

            assertThat(sentMessages).hasSize(1);
            var response = (PublishForwardResponse) sentMessages.getFirst().message();
            assertThat(response.success()).isFalse();
            assertThat(response.errorMessage()).contains("not found");
            assertThat(response.correlationId()).isEqualTo(CORRELATION_ID);
        }

        @Test
        void onPublishForward_partitionOutOfRange_respondsWithError() {
            partitionManager.createStream(streamConfig(STREAM));
            var request = publishForward(REQUESTER, CORRELATION_ID, STREAM, 99, PAYLOAD, TIMESTAMP);

            handler.onPublishForward(request);

            assertThat(sentMessages).hasSize(1);
            var response = (PublishForwardResponse) sentMessages.getFirst().message();
            assertThat(response.success()).isFalse();
            assertThat(response.errorMessage()).contains("out of range");
        }
    }

    // SPEC: §11.2 ReadForward handler tests
    @Nested
    class ReadForwardTests {
        private static final long FROM_OFFSET = 0L;
        private static final int MAX_EVENTS = 10;

        @Test
        void onReadForward_success_sendsResponseWithEvents() {
            partitionManager.createStream(streamConfig(STREAM));
            partitionManager.publishLocal(STREAM, PARTITION, "first".getBytes(), 100L);
            partitionManager.publishLocal(STREAM, PARTITION, "second".getBytes(), 101L);

            var request = readForward(REQUESTER, CORRELATION_ID, STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS);
            handler.onReadForward(request);

            assertThat(sentMessages).hasSize(1);
            var response = (ReadForwardResponse) sentMessages.getFirst().message();
            assertThat(response.success()).isTrue();
            assertThat(response.truncated()).isFalse();
            assertThat(response.events()).hasSize(2);
            assertThat(response.events().get(0).data()).isEqualTo("first".getBytes());
            assertThat(response.events().get(1).data()).isEqualTo("second".getBytes());
            assertThat(response.correlationId()).isEqualTo(CORRELATION_ID);
            assertThat(response.sender()).isEqualTo(GOVERNOR);
        }

        @Test
        void onReadForward_partitionNotLocal_sendsFailureResponse() {
            // No stream exists on this node; readLocal returns a StreamNotFound failure.
            var request = readForward(REQUESTER, CORRELATION_ID, "nonexistent", PARTITION, FROM_OFFSET, MAX_EVENTS);
            handler.onReadForward(request);

            assertThat(sentMessages).hasSize(1);
            var response = (ReadForwardResponse) sentMessages.getFirst().message();
            assertThat(response.success()).isFalse();
            assertThat(response.errorMessage()).contains("not found");
        }

        @Test
        void onReadForward_readLocalFailure_sendsFailureResponse() {
            partitionManager.createStream(streamConfig(STREAM));
            var request = readForward(REQUESTER, CORRELATION_ID, STREAM, 99, FROM_OFFSET, MAX_EVENTS);
            handler.onReadForward(request);

            assertThat(sentMessages).hasSize(1);
            var response = (ReadForwardResponse) sentMessages.getFirst().message();
            assertThat(response.success()).isFalse();
            assertThat(response.errorMessage()).contains("out of range");
        }

        // SPEC: §10.5 defensive cap → truncated flag
        @Test
        void onReadForward_oversizedResponse_truncatesAndSetsFlag() {
            partitionManager.createStream(streamConfig(STREAM));
            var bigPayload = new byte[256];
            for (int i = 0; i < 10; i++) {
                partitionManager.publishLocal(STREAM, PARTITION, bigPayload, 100L + i);
            }
            // Handler with tiny cap (500 bytes). Each event is ~256 + 24 = 280 bytes; envelope 64.
            // So only 1 event fits (64 + 280 = 344), second would hit 624 > 500.
            var tinyHandler = streamForwardHandler(GOVERNOR,
                                                   partitionManager,
                                                   (target, message) -> sentMessages.add(new SentMessage(target, message)),
                                                   500L,
                                                   StreamReadForwardMetrics.NOOP);
            var request = readForward(REQUESTER, CORRELATION_ID, STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS);
            tinyHandler.onReadForward(request);

            assertThat(sentMessages).hasSize(1);
            var response = (ReadForwardResponse) sentMessages.getFirst().message();
            assertThat(response.success()).isTrue();
            assertThat(response.truncated()).isTrue();
            assertThat(response.events()).hasSizeLessThan(10);
        }
    }

    record SentMessage(NodeId target, StreamForwardMessage message) {}
}
