package org.pragmatica.aether.stream.consensus;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.consensus.ConsensusPublishPath.consensusPublishPath;
import static org.pragmatica.aether.stream.consensus.StreamConsensusCommand.streamConsensusCommand;

class ConsensusPublishPathTest {
    private final StreamConsensusCommand[] captured = new StreamConsensusCommand[1];

    private Promise<Long> capturingPropose(StreamConsensusCommand command) {
        captured[0] = command;
        return Promise.success(42L);
    }

    @Nested
    class PublishConsensus {

        @Test
        void publish_callsProposer_withCorrectCommand() {
            var path = consensusPublishPath(ConsensusPublishPathTest.this::capturingPropose);
            var payload = "test-event".getBytes();

            path.publish("orders", 2, payload, 1000L)
                .await()
                .onFailure(_ -> fail("Expected success"))
                .onSuccess(offset -> assertThat(offset).isEqualTo(42L));

            assertThat(captured[0]).isNotNull();
            assertThat(captured[0].streamName()).isEqualTo("orders");
            assertThat(captured[0].partition()).isEqualTo(2);
            assertThat(captured[0].timestamp()).isEqualTo(1000L);
        }

        @Test
        void publish_returnsAssignedOffset_fromProposer() {
            ConsensusProposer proposer = _ -> Promise.success(99L);
            var path = consensusPublishPath(proposer);

            path.publish("events", 0, "data".getBytes(), 500L)
                .await()
                .onFailure(_ -> fail("Expected success"))
                .onSuccess(offset -> assertThat(offset).isEqualTo(99L));
        }

        @Test
        void publish_propagatesFailure_fromProposer() {
            var error = new TestConsensusError("proposal rejected");
            ConsensusProposer failingProposer = _ -> error.promise();
            var path = consensusPublishPath(failingProposer);

            path.publish("orders", 0, "data".getBytes(), 1000L)
                .await()
                .onSuccess(_ -> fail("Expected failure"))
                .onFailure(cause -> assertThat(cause.message()).isEqualTo("proposal rejected"));
        }
    }

    @Nested
    class NoopProposer {

        @Test
        void noopProposer_returnsMinusOne() {
            ConsensusProposer.NOOP.propose(streamConsensusCommand("test", 0, "data".getBytes(), 0L))
                .await()
                .onFailure(_ -> fail("Expected success"))
                .onSuccess(offset -> assertThat(offset).isEqualTo(-1L));
        }
    }

    @Nested
    class StreamConsensusCommandTests {

        @Test
        void defensiveCopy_onConstruction() {
            var original = "hello".getBytes();
            var command = streamConsensusCommand("stream", 0, original, 1000L);

            original[0] = 'X';

            assertThat(command.payload()).isEqualTo("hello".getBytes());
        }

        @Test
        void defensiveCopy_onAccess() {
            var command = streamConsensusCommand("stream", 0, "hello".getBytes(), 1000L);

            var accessed = command.payload();
            accessed[0] = 'X';

            assertThat(command.payload()).isEqualTo("hello".getBytes());
        }

        @Test
        void factoryMethod_preservesAllFields() {
            var command = streamConsensusCommand("my-stream", 3, "data".getBytes(), 12345L);

            assertThat(command.streamName()).isEqualTo("my-stream");
            assertThat(command.partition()).isEqualTo(3);
            assertThat(command.payload()).isEqualTo("data".getBytes());
            assertThat(command.timestamp()).isEqualTo(12345L);
        }
    }

    /// Test error type for verifying failure propagation.
    record TestConsensusError(String message) implements org.pragmatica.lang.Cause {}
}
