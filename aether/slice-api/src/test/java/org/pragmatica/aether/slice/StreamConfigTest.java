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

        @Test
        void consistencyMode_defaultsToEventual() {
            assertThat(streamConfig("orders").consistencyMode()).isEqualTo(ConsistencyMode.EVENTUAL);
        }
    }

    @Nested
    class BackwardCompatibility {

        @Test
        void fourFieldFactory_usesDefaults_forMaxEventSize() {
            var retention = RetentionPolicy.retentionPolicy(50, 1024L, 5000L);
            var config = streamConfig("compat", 8, retention, "earliest");

            assertThat(config.name()).isEqualTo("compat");
            assertThat(config.partitions()).isEqualTo(8);
            assertThat(config.retention()).isSameAs(retention);
            assertThat(config.autoOffsetReset()).isEqualTo("earliest");
            assertThat(config.maxEventSizeBytes()).isEqualTo(1_048_576L);
            assertThat(config.consistencyMode()).isEqualTo(ConsistencyMode.EVENTUAL);
        }

        @Test
        void fiveFieldFactory_usesDefaults_forConsistencyMode() {
            var retention = RetentionPolicy.retentionPolicy(50, 1024L, 5000L);
            var config = streamConfig("compat", 8, retention, "earliest", 2_000_000L);

            assertThat(config.consistencyMode()).isEqualTo(ConsistencyMode.EVENTUAL);
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

        @Test
        void strongConsistency_isPreserved() {
            var retention = RetentionPolicy.retentionPolicy(50, 1024L, 5000L);
            var config = streamConfig("events", 8, retention, "earliest", 1_048_576L, ConsistencyMode.STRONG);

            assertThat(config.consistencyMode()).isEqualTo(ConsistencyMode.STRONG);
        }
    }
}
