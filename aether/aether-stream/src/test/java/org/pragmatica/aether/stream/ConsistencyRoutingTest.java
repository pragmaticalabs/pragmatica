package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.consensus.ConsensusPublishPath;
import org.pragmatica.aether.stream.consensus.ConsensusProposer;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.serialization.Serializer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.StreamPublisherImpl.streamPublisher;
import static org.pragmatica.aether.stream.consensus.ConsensusPublishPath.consensusPublishPath;

class ConsistencyRoutingTest {

    private static final String STREAM = "events";
    private static final int PARTITION_COUNT = 1;

    private StreamPartitionManager partitionManager;

    @BeforeEach
    void setUp() {
        partitionManager = streamPartitionManager(Long.MAX_VALUE);
        var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);
        partitionManager.createStream(StreamConfig.streamConfig(STREAM, PARTITION_COUNT, retention, "latest"));
    }

    @AfterEach
    void tearDown() {
        partitionManager.close();
    }

    @Nested
    class EventualMode {

        @Test
        void publish_eventualMode_usesLocalPath() {
            Serializer serializer = identitySerializer();
            var publisher = streamPublisher(partitionManager, serializer, STREAM, PARTITION_COUNT, Option.none());

            publisher.publish("hello".getBytes()).await()
                     .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));

            partitionManager.readLocal(STREAM, 0, 0, 10)
                            .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                            .onSuccess(events -> {
                                assertThat(events).hasSize(1);
                                assertThat(events.getFirst().data()).isEqualTo("hello".getBytes());
                            });
        }
    }

    @Nested
    class StrongMode {

        @Test
        void publish_strongMode_usesConsensusPath() {
            Serializer serializer = identitySerializer();
            var proposalCount = new AtomicInteger(0);
            ConsensusProposer countingProposer = command -> {
                proposalCount.incrementAndGet();
                return Promise.success(42L);
            };
            var consensusPath = consensusPublishPath(countingProposer);

            var publisher = StreamPublisherImpl.streamPublisher(
                partitionManager, serializer, STREAM, PARTITION_COUNT,
                Option.<Function<byte[], Object>>none(),
                ConsistencyMode.STRONG, Option.some(consensusPath));

            publisher.publish("strong-event".getBytes()).await()
                     .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));

            assertThat(proposalCount.get()).isEqualTo(1);

            // STRONG mode goes through consensus, not local buffer directly
            partitionManager.readLocal(STREAM, 0, 0, 10)
                            .onSuccess(events -> assertThat(events).isEmpty());
        }
    }

    @Nested
    class DefaultMode {

        @Test
        void publish_defaultMode_isEventual() {
            Serializer serializer = identitySerializer();
            // The single-arg factory defaults to EVENTUAL
            var publisher = streamPublisher(partitionManager, serializer, STREAM, PARTITION_COUNT, Option.none());

            publisher.publish("default-event".getBytes()).await()
                     .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));

            // EVENTUAL writes directly to local ring buffer
            partitionManager.readLocal(STREAM, 0, 0, 10)
                            .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                            .onSuccess(events -> assertThat(events).hasSize(1));
        }
    }

    private static Serializer identitySerializer() {
        return new Serializer() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> byte[] encode(T object) {
                return (byte[]) object;
            }

            @Override
            public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {
                byteBuf.writeBytes((byte[]) object);
            }
        };
    }
}
