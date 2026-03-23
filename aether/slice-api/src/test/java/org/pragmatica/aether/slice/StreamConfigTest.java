package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.StreamConfig.streamConfig;

class StreamConfigTest {

    @Nested
    class DefaultFactory {

        @Test
        void name_isPreserved() {
            assertThat(streamConfig("orders").name()).isEqualTo("orders");
        }

        @Test
        void partitions_defaultsTo4() {
            assertThat(streamConfig("orders").partitions()).isEqualTo(4);
        }

        @Test
        void retention_isDefault() {
            var config = streamConfig("orders");

            assertThat(config.retention().maxCount()).isEqualTo(100_000);
        }

        @Test
        void autoOffsetReset_defaultsToLatest() {
            assertThat(streamConfig("orders").autoOffsetReset()).isEqualTo("latest");
        }
    }

    @Nested
    class CustomFactory {

        @Test
        void customValues_arePreserved() {
            var retention = RetentionPolicy.retentionPolicy(50, 1024L, 5000L);
            var config = streamConfig("events", 8, retention, "earliest");

            assertThat(config.name()).isEqualTo("events");
            assertThat(config.partitions()).isEqualTo(8);
            assertThat(config.retention()).isSameAs(retention);
            assertThat(config.autoOffsetReset()).isEqualTo("earliest");
        }
    }
}
