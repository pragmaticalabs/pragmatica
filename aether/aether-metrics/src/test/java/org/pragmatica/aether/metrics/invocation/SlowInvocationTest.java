package org.pragmatica.aether.metrics.invocation;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.MethodName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SlowInvocationTest {

    private final MethodName method = MethodName.methodName("testMethod").unwrap();

    @Nested
    class SuccessfulFactoryTests {
        @Test
        void successful_setsSuccessTrue() {
            var invocation = SlowInvocation.slowInvocation(method, 1000L, 5_000_000L, 256, 512);

            assertThat(invocation.success()).isTrue();
        }

        @Test
        void successful_setsErrorTypeEmpty() {
            var invocation = SlowInvocation.slowInvocation(method, 1000L, 5_000_000L, 256, 512);

            assertThat(invocation.errorType().isEmpty()).isTrue();
        }

        @Test
        void successful_preservesAllFields() {
            var invocation = SlowInvocation.slowInvocation(method, 1000L, 5_000_000L, 256, 512);

            assertThat(invocation.methodName()).isEqualTo(method);
            assertThat(invocation.timestampNs()).isEqualTo(1000L);
            assertThat(invocation.durationNs()).isEqualTo(5_000_000L);
            assertThat(invocation.requestBytes()).isEqualTo(256);
            assertThat(invocation.responseBytes()).isEqualTo(512);
        }
    }

    @Nested
    class FailedFactoryTests {
        @Test
        void failed_setsSuccessFalse() {
            var invocation = SlowInvocation.slowInvocation(method, 1000L, 5_000_000L, 256, "TimeoutError");

            assertThat(invocation.success()).isFalse();
        }

        @Test
        void failed_setsErrorTypePresent() {
            var invocation = SlowInvocation.slowInvocation(method, 1000L, 5_000_000L, 256, "TimeoutError");

            assertThat(invocation.errorType().isPresent()).isTrue();
            invocation.errorType().onPresent(err -> assertThat(err).isEqualTo("TimeoutError"));
        }

        @Test
        void failed_setsResponseBytesToZero() {
            var invocation = SlowInvocation.slowInvocation(method, 1000L, 5_000_000L, 256, "NetworkError");

            assertThat(invocation.responseBytes()).isEqualTo(0);
        }
    }

    @Nested
    class DurationMsTests {
        @Test
        void durationMs_convertsNanosToMillis() {
            var invocation = SlowInvocation.slowInvocation(method, 1000L, 5_000_000L, 256, 512);

            assertThat(invocation.durationMs()).isCloseTo(5.0, within(0.001));
        }

        @Test
        void durationMs_subMillisecond_returnsFraction() {
            var invocation = SlowInvocation.slowInvocation(method, 1000L, 500_000L, 256, 512);

            assertThat(invocation.durationMs()).isCloseTo(0.5, within(0.001));
        }

        @Test
        void durationMs_exactlyOneSecond_returns1000() {
            var invocation = SlowInvocation.slowInvocation(method, 1000L, 1_000_000_000L, 256, 512);

            assertThat(invocation.durationMs()).isCloseTo(1000.0, within(0.001));
        }
    }
}
