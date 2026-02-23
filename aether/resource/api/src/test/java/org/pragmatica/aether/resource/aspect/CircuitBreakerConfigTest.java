package org.pragmatica.aether.resource.aspect;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.aspect.CircuitBreakerConfig.circuitBreakerConfig;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class CircuitBreakerConfigTest {

    @Nested
    class DefaultFactory {

        @Test
        void circuitBreakerConfig_returnsDefaults_withNoArgs() {
            var config = circuitBreakerConfig();

            assertThat(config.failureThreshold()).isEqualTo(5);
            assertThat(config.resetTimeout()).isEqualTo(timeSpan(30).seconds());
            assertThat(config.testAttempts()).isEqualTo(3);
        }
    }

    @Nested
    class SingleParamFactory {

        @Test
        void circuitBreakerConfig_succeeds_withPositiveThreshold() {
            var config = circuitBreakerConfig(10).unwrap();

            assertThat(config.failureThreshold()).isEqualTo(10);
            assertThat(config.resetTimeout()).isEqualTo(timeSpan(30).seconds());
            assertThat(config.testAttempts()).isEqualTo(3);
        }

        @Test
        void circuitBreakerConfig_fails_withZeroThreshold() {
            var result = circuitBreakerConfig(0);

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void circuitBreakerConfig_fails_withNegativeThreshold() {
            var result = circuitBreakerConfig(-1);

            assertThat(result.isFailure()).isTrue();
        }
    }

    @Nested
    class FullParamFactory {

        @Test
        void circuitBreakerConfig_succeeds_withAllValidParams() {
            var config = circuitBreakerConfig(8, timeSpan(60).seconds(), 5).unwrap();

            assertThat(config.failureThreshold()).isEqualTo(8);
            assertThat(config.resetTimeout()).isEqualTo(timeSpan(60).seconds());
            assertThat(config.testAttempts()).isEqualTo(5);
        }

        @Test
        void circuitBreakerConfig_fails_withZeroFailureThreshold() {
            var result = circuitBreakerConfig(0, timeSpan(30).seconds(), 3);

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void circuitBreakerConfig_fails_withNullTimeout() {
            var result = circuitBreakerConfig(5, null, 3);

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void circuitBreakerConfig_fails_withZeroTestAttempts() {
            var result = circuitBreakerConfig(5, timeSpan(30).seconds(), 0);

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void circuitBreakerConfig_collectsAllErrors_withMultipleInvalidParams() {
            var result = circuitBreakerConfig(0, null, -1);

            assertThat(result.isFailure()).isTrue();
        }
    }
}
