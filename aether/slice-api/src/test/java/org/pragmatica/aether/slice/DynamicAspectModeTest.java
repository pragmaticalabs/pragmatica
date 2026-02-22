package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicAspectModeTest {

    @Nested
    class EnumValuesTests {

        @Test
        void values_containsAllFourModes() {
            assertThat(DynamicAspectMode.values()).containsExactly(
                DynamicAspectMode.NONE,
                DynamicAspectMode.LOG,
                DynamicAspectMode.METRICS,
                DynamicAspectMode.LOG_AND_METRICS
            );
        }

        @Test
        void valueOf_none_returnsNone() {
            assertThat(DynamicAspectMode.valueOf("NONE")).isEqualTo(DynamicAspectMode.NONE);
        }

        @Test
        void valueOf_logAndMetrics_returnsLogAndMetrics() {
            assertThat(DynamicAspectMode.valueOf("LOG_AND_METRICS")).isEqualTo(DynamicAspectMode.LOG_AND_METRICS);
        }
    }

    @Nested
    class LoggingEnabledTests {

        @Test
        void isLoggingEnabled_none_returnsFalse() {
            assertThat(DynamicAspectMode.NONE.isLoggingEnabled()).isFalse();
        }

        @Test
        void isLoggingEnabled_log_returnsTrue() {
            assertThat(DynamicAspectMode.LOG.isLoggingEnabled()).isTrue();
        }

        @Test
        void isLoggingEnabled_metrics_returnsFalse() {
            assertThat(DynamicAspectMode.METRICS.isLoggingEnabled()).isFalse();
        }

        @Test
        void isLoggingEnabled_logAndMetrics_returnsTrue() {
            assertThat(DynamicAspectMode.LOG_AND_METRICS.isLoggingEnabled()).isTrue();
        }
    }

    @Nested
    class MetricsEnabledTests {

        @Test
        void isMetricsEnabled_none_returnsFalse() {
            assertThat(DynamicAspectMode.NONE.isMetricsEnabled()).isFalse();
        }

        @Test
        void isMetricsEnabled_log_returnsFalse() {
            assertThat(DynamicAspectMode.LOG.isMetricsEnabled()).isFalse();
        }

        @Test
        void isMetricsEnabled_metrics_returnsTrue() {
            assertThat(DynamicAspectMode.METRICS.isMetricsEnabled()).isTrue();
        }

        @Test
        void isMetricsEnabled_logAndMetrics_returnsTrue() {
            assertThat(DynamicAspectMode.LOG_AND_METRICS.isMetricsEnabled()).isTrue();
        }
    }

    @Nested
    class CombinationTests {

        @Test
        void none_neitherLoggingNorMetrics() {
            var mode = DynamicAspectMode.NONE;

            assertThat(mode.isLoggingEnabled()).isFalse();
            assertThat(mode.isMetricsEnabled()).isFalse();
        }

        @Test
        void logAndMetrics_bothEnabled() {
            var mode = DynamicAspectMode.LOG_AND_METRICS;

            assertThat(mode.isLoggingEnabled()).isTrue();
            assertThat(mode.isMetricsEnabled()).isTrue();
        }
    }
}
