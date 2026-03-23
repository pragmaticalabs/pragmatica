package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamAccess.PartitionInfo;
import org.pragmatica.aether.slice.StreamAccess.StreamEvent;
import org.pragmatica.aether.slice.StreamAccess.StreamMetadata;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamAccessTest {

    @Nested
    class StreamEventTests {

        @Test
        void recordFields_areAccessible() {
            var event = new StreamEvent<>(42L, 1000L, 3, "payload");

            assertThat(event.offset()).isEqualTo(42L);
            assertThat(event.timestamp()).isEqualTo(1000L);
            assertThat(event.partition()).isEqualTo(3);
            assertThat(event.payload()).isEqualTo("payload");
        }
    }

    @Nested
    class StreamMetadataTests {

        @Test
        void recordFields_areAccessible() {
            var partitions = List.of(new PartitionInfo(0, 0L, 100L, 100L));
            var metadata = new StreamMetadata("test-stream", 1, partitions);

            assertThat(metadata.streamName()).isEqualTo("test-stream");
            assertThat(metadata.partitionCount()).isEqualTo(1);
            assertThat(metadata.partitions()).hasSize(1);
        }
    }

    @Nested
    class PartitionInfoTests {

        @Test
        void recordFields_areAccessible() {
            var info = new PartitionInfo(2, 10L, 500L, 490L);

            assertThat(info.partition()).isEqualTo(2);
            assertThat(info.headOffset()).isEqualTo(10L);
            assertThat(info.tailOffset()).isEqualTo(500L);
            assertThat(info.eventCount()).isEqualTo(490L);
        }
    }

    @Nested
    class InterfaceTests {

        @Test
        void isInterface() {
            assertThat(StreamAccess.class.isInterface()).isTrue();
        }

        @Test
        void declaresExpectedMethods() {
            var methodNames = java.util.Arrays.stream(StreamAccess.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .toList();

            assertThat(methodNames).contains("publish", "fetch", "commit", "committedOffset", "metadata");
        }
    }
}
