package org.pragmatica.aether.resource.aspect;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.aspect.RetryConfig.retryConfig;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class RetryConfigTest {

    @Nested
    class ExponentialBackoffFactory {

        @Test
        void retryConfig_succeeds_withPositiveAttempts() {
            var config = retryConfig(3).unwrap();

            assertThat(config.maxAttempts()).isEqualTo(3);
            assertThat(config.backoffStrategy()).isNotNull();
        }

        @Test
        void retryConfig_fails_withZeroAttempts() {
            var result = retryConfig(0);

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void retryConfig_fails_withNegativeAttempts() {
            var result = retryConfig(-5);

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void retryConfig_createsExponentialStrategy_withDefaultParams() {
            var config = retryConfig(2).unwrap();

            var delay = config.backoffStrategy().nextTimeout(1);
            assertThat(delay).isNotNull();
        }
    }

    @Nested
    class FixedBackoffFactory {

        @Test
        void retryConfig_succeeds_withPositiveAttemptsAndInterval() {
            var interval = timeSpan(500).millis();
            var config = retryConfig(5, interval).unwrap();

            assertThat(config.maxAttempts()).isEqualTo(5);
            assertThat(config.backoffStrategy()).isNotNull();
        }

        @Test
        void retryConfig_fails_withZeroAttemptsAndFixedInterval() {
            var result = retryConfig(0, timeSpan(100).millis());

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void retryConfig_returnsConstantDelay_withFixedBackoff() {
            var interval = timeSpan(200).millis();
            var config = retryConfig(3, interval).unwrap();

            assertThat(config.backoffStrategy().nextTimeout(1)).isEqualTo(interval);
            assertThat(config.backoffStrategy().nextTimeout(2)).isEqualTo(interval);
            assertThat(config.backoffStrategy().nextTimeout(3)).isEqualTo(interval);
        }
    }

    @Nested
    class CustomStrategyFactory {

        @Test
        void retryConfig_succeeds_withValidAttemptsAndStrategy() {
            BackoffStrategy strategy = attempt -> timeSpan(attempt * 100L).millis();
            var config = retryConfig(4, strategy).unwrap();

            assertThat(config.maxAttempts()).isEqualTo(4);
            assertThat(config.backoffStrategy()).isSameAs(strategy);
        }

        @Test
        void retryConfig_fails_withZeroAttemptsAndCustomStrategy() {
            BackoffStrategy strategy = attempt -> timeSpan(100).millis();
            var result = retryConfig(0, strategy);

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void retryConfig_fails_withNullStrategy() {
            var result = retryConfig(3, (BackoffStrategy) null);

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void retryConfig_collectsAllErrors_withInvalidAttemptsAndNullStrategy() {
            var result = retryConfig(-1, (BackoffStrategy) null);

            assertThat(result.isFailure()).isTrue();
        }
    }
}
