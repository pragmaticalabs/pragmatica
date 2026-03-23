package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

class StreamPartitionManagerTest {

    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Nested
    class CreateStream {

        @Test
        void createStream_success_defaultConfig() {
            var config = StreamConfig.streamConfig("orders");

            manager.createStream(config)
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));

            var info = manager.streamInfo("orders");
            assertThat(info.isPresent()).isTrue();
            info.onPresent(si -> {
                assertThat(si.name()).isEqualTo("orders");
                assertThat(si.partitions()).isEqualTo(4);
                assertThat(si.totalEvents()).isEqualTo(0L);
            });
        }

        @Test
        void createStream_success_customPartitions() {
            var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);
            var config = StreamConfig.streamConfig("events", 8, retention, "earliest");

            manager.createStream(config)
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));

            manager.streamInfo("events")
                   .onPresent(si -> assertThat(si.partitions()).isEqualTo(8));
        }

        @Test
        void createStream_failure_duplicateName() {
            var config = StreamConfig.streamConfig("orders");
            manager.createStream(config);

            manager.createStream(config)
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_ALREADY_EXISTS));
        }
    }

    @Nested
    class DestroyStream {

        @Test
        void destroyStream_success_existingStream() {
            manager.createStream(StreamConfig.streamConfig("orders"));

            manager.destroyStream("orders")
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));

            assertThat(manager.streamInfo("orders").isEmpty()).isTrue();
        }

        @Test
        void destroyStream_failure_nonExistentStream() {
            manager.destroyStream("nonexistent")
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.StreamNotFound.class));
        }

        @Test
        void destroyStream_closesBuffers() {
            var retention = RetentionPolicy.retentionPolicy(100, 4096, 60_000);
            var config = StreamConfig.streamConfig("orders", 2, retention, "latest");
            manager.createStream(config);

            // Publish some data first
            manager.publishLocal("orders", 0, "test".getBytes(), System.currentTimeMillis());

            manager.destroyStream("orders")
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));

            // Stream should no longer be accessible
            manager.publishLocal("orders", 0, "test".getBytes(), System.currentTimeMillis())
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.StreamNotFound.class));
        }
    }

    @Nested
    class PublishAndRead {

        @Test
        void publishLocal_success_returnsOffset() {
            var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);
            var config = StreamConfig.streamConfig("orders", 4, retention, "latest");
            manager.createStream(config);

            manager.publishLocal("orders", 0, "event-1".getBytes(), 1000L)
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                   .onSuccess(offset -> assertThat(offset).isEqualTo(0L));
        }

        @Test
        void readLocal_success_afterPublish() {
            var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);
            var config = StreamConfig.streamConfig("orders", 4, retention, "latest");
            manager.createStream(config);

            manager.publishLocal("orders", 0, "event-1".getBytes(), 1000L);
            manager.publishLocal("orders", 0, "event-2".getBytes(), 2000L);

            manager.readLocal("orders", 0, 0, 10)
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                   .onSuccess(events -> {
                       assertThat(events).hasSize(2);
                       assertThat(events.get(0).data()).isEqualTo("event-1".getBytes());
                       assertThat(events.get(1).data()).isEqualTo("event-2".getBytes());
                   });
        }

        @Test
        void publishLocal_preservesOrdering_acrossEvents() {
            var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);
            var config = StreamConfig.streamConfig("orders", 2, retention, "latest");
            manager.createStream(config);

            for (int i = 0; i < 10; i++) {
                manager.publishLocal("orders", 1, ("msg-" + i).getBytes(), 1000L + i);
            }

            manager.readLocal("orders", 1, 0, 10)
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                   .onSuccess(events -> {
                       assertThat(events).hasSize(10);
                       for (int i = 0; i < 10; i++) {
                           assertThat(events.get(i).data()).isEqualTo(("msg-" + i).getBytes());
                           assertThat(events.get(i).offset()).isEqualTo(i);
                       }
                   });
        }

        @Test
        void readLocal_withOffset_returnsSubsequentEvents() {
            var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);
            var config = StreamConfig.streamConfig("orders", 2, retention, "latest");
            manager.createStream(config);

            manager.publishLocal("orders", 0, "a".getBytes(), 1000L);
            manager.publishLocal("orders", 0, "b".getBytes(), 2000L);
            manager.publishLocal("orders", 0, "c".getBytes(), 3000L);

            manager.readLocal("orders", 0, 1, 10)
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                   .onSuccess(events -> {
                       assertThat(events).hasSize(2);
                       assertThat(events.get(0).data()).isEqualTo("b".getBytes());
                       assertThat(events.get(1).data()).isEqualTo("c".getBytes());
                   });
        }

        @Test
        void publishLocal_isolatesPartitions() {
            var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);
            var config = StreamConfig.streamConfig("orders", 3, retention, "latest");
            manager.createStream(config);

            manager.publishLocal("orders", 0, "p0-event".getBytes(), 1000L);
            manager.publishLocal("orders", 1, "p1-event".getBytes(), 2000L);
            manager.publishLocal("orders", 2, "p2-event".getBytes(), 3000L);

            manager.readLocal("orders", 0, 0, 10)
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                   .onSuccess(events -> {
                       assertThat(events).hasSize(1);
                       assertThat(events.getFirst().data()).isEqualTo("p0-event".getBytes());
                   });

            manager.readLocal("orders", 1, 0, 10)
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                   .onSuccess(events -> {
                       assertThat(events).hasSize(1);
                       assertThat(events.getFirst().data()).isEqualTo("p1-event".getBytes());
                   });
        }
    }

    @Nested
    class ErrorCases {

        @Test
        void publishLocal_failure_nonExistentStream() {
            manager.publishLocal("missing", 0, "data".getBytes(), 1000L)
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.StreamNotFound.class));
        }

        @Test
        void readLocal_failure_nonExistentStream() {
            manager.readLocal("missing", 0, 0, 10)
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.StreamNotFound.class));
        }

        @Test
        void publishLocal_failure_partitionOutOfRange() {
            manager.createStream(StreamConfig.streamConfig("orders"));

            manager.publishLocal("orders", 99, "data".getBytes(), 1000L)
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.PartitionOutOfRange.class));
        }

        @Test
        void readLocal_failure_partitionOutOfRange() {
            manager.createStream(StreamConfig.streamConfig("orders"));

            manager.readLocal("orders", -1, 0, 10)
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.PartitionOutOfRange.class));
        }

        @Test
        void publishLocal_failure_negativePartition() {
            manager.createStream(StreamConfig.streamConfig("orders"));

            manager.publishLocal("orders", -1, "data".getBytes(), 1000L)
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.PartitionOutOfRange.class));
        }
    }

    @Nested
    class ListStreams {

        @Test
        void listStreams_empty_noStreams() {
            assertThat(manager.listStreams()).isEmpty();
        }

        @Test
        void listStreams_returnsAll_multipleStreams() {
            manager.createStream(StreamConfig.streamConfig("orders"));
            manager.createStream(StreamConfig.streamConfig("events"));
            manager.createStream(StreamConfig.streamConfig("logs"));

            var streams = manager.listStreams();
            assertThat(streams).hasSize(3);
            assertThat(streams.stream().map(StreamPartitionManager.StreamInfo::name).toList())
                .containsExactlyInAnyOrder("orders", "events", "logs");
        }

        @Test
        void listStreams_reflectsEventCounts() {
            var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);
            var config = StreamConfig.streamConfig("orders", 2, retention, "latest");
            manager.createStream(config);

            manager.publishLocal("orders", 0, "a".getBytes(), 1000L);
            manager.publishLocal("orders", 0, "b".getBytes(), 2000L);
            manager.publishLocal("orders", 1, "c".getBytes(), 3000L);

            var streams = manager.listStreams();
            assertThat(streams).hasSize(1);
            assertThat(streams.getFirst().totalEvents()).isEqualTo(3L);
        }
    }

    @Nested
    class StreamInfoTests {

        @Test
        void streamInfo_none_nonExistentStream() {
            assertThat(manager.streamInfo("missing").isEmpty()).isTrue();
        }

        @Test
        void streamInfo_present_existingStream() {
            manager.createStream(StreamConfig.streamConfig("orders"));

            manager.streamInfo("orders")
                   .onPresent(si -> {
                       assertThat(si.name()).isEqualTo("orders");
                       assertThat(si.partitions()).isEqualTo(4);
                       assertThat(si.totalBytes()).isGreaterThan(0L);
                   });
        }
    }
}
