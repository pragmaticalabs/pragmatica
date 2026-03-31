package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsumerConfig.ErrorStrategy;
import org.pragmatica.aether.slice.ConsumerConfig.ProcessingMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.ConsumerConfig.consumerConfig;

class ConsumerConfigTest {

    @Nested
    class DefaultFactory {

        @Test
        void groupId_isPreserved() {
            assertThat(consumerConfig("analytics").groupId()).isEqualTo("analytics");
        }

        @Test
        void maxBatchSize_defaultsTo1() {
            assertThat(consumerConfig("analytics").maxBatchSize()).isEqualTo(1);
        }

        @Test
        void processingMode_defaultsToOrdered() {
            assertThat(consumerConfig("analytics").processingMode()).isEqualTo(ProcessingMode.ORDERED);
        }

        @Test
        void errorStrategy_defaultsToRetry() {
            assertThat(consumerConfig("analytics").errorStrategy()).isEqualTo(ErrorStrategy.RETRY);
        }

        @Test
        void readPreference_defaultsToLeader() {
            assertThat(consumerConfig("analytics").readPreference()).isEqualTo(ReadPreference.LEADER);
        }
    }

    @Nested
    class CustomFactory {

        @Test
        void customValues_arePreserved() {
            var config = consumerConfig("audit", 100, ProcessingMode.PARALLEL, ErrorStrategy.STALL);

            assertThat(config.groupId()).isEqualTo("audit");
            assertThat(config.maxBatchSize()).isEqualTo(100);
            assertThat(config.processingMode()).isEqualTo(ProcessingMode.PARALLEL);
            assertThat(config.errorStrategy()).isEqualTo(ErrorStrategy.STALL);
        }
    }

    @Nested
    class BackwardCompatibility {

        @Test
        void fourFieldFactory_usesDefaults_forCheckpointRetryDlqReadPreference() {
            var config = consumerConfig("compat", 50, ProcessingMode.PARALLEL, ErrorStrategy.SKIP);

            assertThat(config.groupId()).isEqualTo("compat");
            assertThat(config.maxBatchSize()).isEqualTo(50);
            assertThat(config.processingMode()).isEqualTo(ProcessingMode.PARALLEL);
            assertThat(config.errorStrategy()).isEqualTo(ErrorStrategy.SKIP);
            assertThat(config.checkpointIntervalMs()).isEqualTo(1000L);
            assertThat(config.maxRetries()).isEqualTo(3);
            assertThat(config.deadLetterStream()).isEmpty();
            assertThat(config.readPreference()).isEqualTo(ReadPreference.LEADER);
        }

        @Test
        void sevenFieldFactory_defaultsToLeader_forReadPreference() {
            var config = consumerConfig("compat", 50, ProcessingMode.PARALLEL, ErrorStrategy.SKIP,
                                        2000L, 5, "dead-letters");

            assertThat(config.readPreference()).isEqualTo(ReadPreference.LEADER);
        }
    }

    @Nested
    class ReadPreferenceTests {

        @Test
        void readPreference_nearest_isPreserved() {
            var config = consumerConfig("group", 10, ProcessingMode.ORDERED, ErrorStrategy.RETRY,
                                        1000L, 3, "", ReadPreference.NEAREST);

            assertThat(config.readPreference()).isEqualTo(ReadPreference.NEAREST);
        }

        @Test
        void readPreference_followerOnly_isPreserved() {
            var config = consumerConfig("group", 10, ProcessingMode.ORDERED, ErrorStrategy.RETRY,
                                        1000L, 3, "", ReadPreference.FOLLOWER_ONLY);

            assertThat(config.readPreference()).isEqualTo(ReadPreference.FOLLOWER_ONLY);
        }
    }

    @Nested
    class EnumValues {

        @Test
        void processingMode_hasTwoValues() {
            assertThat(ProcessingMode.values()).containsExactly(ProcessingMode.ORDERED, ProcessingMode.PARALLEL);
        }

        @Test
        void errorStrategy_hasThreeValues() {
            assertThat(ErrorStrategy.values()).containsExactly(ErrorStrategy.RETRY, ErrorStrategy.SKIP, ErrorStrategy.STALL);
        }

        @Test
        void readPreference_hasThreeValues() {
            assertThat(ReadPreference.values()).containsExactly(
                ReadPreference.LEADER, ReadPreference.NEAREST, ReadPreference.FOLLOWER_ONLY);
        }
    }
}
