package org.pragmatica.aether.stream.forward;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.StreamConfig.streamConfig;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.forward.StreamForwardHandler.streamForwardHandler;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward.publishForward;


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

    record SentMessage(NodeId target, StreamForwardMessage message) {}
}
