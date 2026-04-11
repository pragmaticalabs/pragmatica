package org.pragmatica.aether.stream.forward;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;

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
            result.onFailure(cause -> assertThat(cause.message()).contains("stream not found"));
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
    }

    @Nested
    class NoOpTests {

        @Test
        void noopClient_publishRemote_failsImmediately() {
            var result = StreamForwardClient.NOOP.publishRemote(GOVERNOR, STREAM, PARTITION, PAYLOAD, TIMESTAMP).await();

            assertThat(result.isSuccess()).isFalse();
            result.onFailure(cause -> assertThat(cause.message()).contains("governor"));
        }
    }

    record SentMessage(NodeId target, StreamForwardMessage message) {}
}
