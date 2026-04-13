package org.pragmatica.aether.resource.interceptor;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RateGuardError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class RateGuardTest {

    @Nested
    class Guard {
        @Test
        void guard_succeeds_withinLimit() {
            var guard = DefaultRateGuard.defaultRateGuard(RateGuardConfig.rateGuardConfig(10, 0).unwrap());
            var result = guard.guard(() -> Promise.success("ok")).await();
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap()).isEqualTo("ok");
        }

        @Test
        void guard_fails_whenLimitExceeded() {
            var guard = DefaultRateGuard.defaultRateGuard(RateGuardConfig.rateGuardConfig(1, 0).unwrap());
            guard.guard(() -> Promise.success("ok")).await()
                 .onFailure(_ -> fail("First request should succeed"));
            var result = guard.guard(() -> Promise.success("should fail")).await();
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void guard_returnsMetadata_whenLimitExceeded() {
            var guard = DefaultRateGuard.defaultRateGuard(RateGuardConfig.rateGuardConfig(1, 0).unwrap());
            guard.guard(() -> Promise.success("ok")).await();
            var result = guard.guard(() -> Promise.success("fail")).await();
            result.onSuccessRun(() -> fail("Expected failure"))
                  .onFailure(RateGuardTest::assertLimitExceededMetadata);
        }

        @Test
        void guard_succeeds_withBurstCapacity() {
            var guard = DefaultRateGuard.defaultRateGuard(RateGuardConfig.rateGuardConfig(1, 2).unwrap());
            assertThat(guard.guard(() -> Promise.success(1)).await().isSuccess()).isTrue();
            assertThat(guard.guard(() -> Promise.success(2)).await().isSuccess()).isTrue();
            assertThat(guard.guard(() -> Promise.success(3)).await().isSuccess()).isTrue();
            assertThat(guard.guard(() -> Promise.success(4)).await().isFailure()).isTrue();
        }
    }

    @Nested
    class Config {
        @Test
        void rateGuardConfig_fails_forZeroRate() {
            assertThat(RateGuardConfig.rateGuardConfig(0, 10).isFailure()).isTrue();
        }

        @Test
        void rateGuardConfig_fails_forNegativeBurst() {
            assertThat(RateGuardConfig.rateGuardConfig(100, -1).isFailure()).isTrue();
        }

        @Test
        void rateGuardConfig_succeeds_withDefaults() {
            var result = RateGuardConfig.rateGuardConfig();
            assertThat(result.isSuccess()).isTrue();
            var config = result.unwrap();
            assertThat(config.requestsPerSecond()).isEqualTo(100);
            assertThat(config.burst()).isEqualTo(20);
            assertThat(config.type()).isEqualTo("local");
        }
    }

    @Nested
    class Factory {
        @Test
        void provision_succeeds_fromConfig() {
            var factory = new RateGuardFactory();
            assertThat(factory.resourceType()).isEqualTo(org.pragmatica.aether.slice.RateGuard.class);
            assertThat(factory.configType()).isEqualTo(RateGuardConfig.class);
            var result = factory.provision(RateGuardConfig.rateGuardConfig().unwrap()).await();
            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Nested
    class LimitExceededError {
        @Test
        void limitExceeded_hasDescriptiveMessage() {
            var error = RateGuardError.LimitExceeded.limitExceeded(5000, 100, 0, System.currentTimeMillis() + 5000);
            assertThat(error.message()).contains("Rate limit exceeded");
            assertThat(error.message()).contains("5000ms");
        }
    }

    private static void assertLimitExceededMetadata(Cause cause) {
        assertThat(cause).isInstanceOf(RateGuardError.LimitExceeded.class);
        var exceeded = (RateGuardError.LimitExceeded) cause;
        assertThat(exceeded.limit()).isEqualTo(1);
        assertThat(exceeded.remaining()).isEqualTo(0);
        assertThat(exceeded.retryAfterMs()).isGreaterThan(0);
        assertThat(exceeded.resetAtEpochMs()).isGreaterThan(System.currentTimeMillis() - 1000);
        assertThat(exceeded.retryAfterSeconds()).isGreaterThanOrEqualTo(0);
    }
}
