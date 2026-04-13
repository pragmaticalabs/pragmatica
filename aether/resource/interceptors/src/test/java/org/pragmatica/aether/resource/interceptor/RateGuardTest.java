package org.pragmatica.aether.resource.interceptor;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RateGuardError;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

class RateGuardTest {

    @Test
    void allows_requests_within_limit() {
        var guard = DefaultRateGuard.defaultRateGuard(new RateGuardConfig(10, 0, "local"));
        var result = guard.guard(() -> Promise.success("ok")).await();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.unwrap()).isEqualTo("ok");
    }

    @Test
    void rejects_when_limit_exceeded() {
        var guard = DefaultRateGuard.defaultRateGuard(new RateGuardConfig(1, 0, "local"));
        // First request succeeds
        guard.guard(() -> Promise.success("ok")).await();
        // Second request should be rate limited
        var result = guard.guard(() -> Promise.success("should fail")).await();
        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void limit_exceeded_error_has_metadata() {
        var guard = DefaultRateGuard.defaultRateGuard(new RateGuardConfig(1, 0, "local"));
        guard.guard(() -> Promise.success("ok")).await();
        var result = guard.guard(() -> Promise.success("fail")).await();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(RateGuardError.LimitExceeded.class);
            var exceeded = (RateGuardError.LimitExceeded) cause;
            assertThat(exceeded.limit()).isEqualTo(1);
            assertThat(exceeded.remaining()).isEqualTo(0);
            assertThat(exceeded.retryAfterMs()).isGreaterThan(0);
            assertThat(exceeded.resetAtEpochMs()).isGreaterThan(System.currentTimeMillis() - 1000);
            assertThat(exceeded.retryAfterSeconds()).isGreaterThanOrEqualTo(0);
        });
    }

    @Test
    void burst_allows_extra_requests() {
        var guard = DefaultRateGuard.defaultRateGuard(new RateGuardConfig(1, 2, "local"));
        // Rate=1 + burst=2 = 3 total permits
        assertThat(guard.guard(() -> Promise.success(1)).await().isSuccess()).isTrue();
        assertThat(guard.guard(() -> Promise.success(2)).await().isSuccess()).isTrue();
        assertThat(guard.guard(() -> Promise.success(3)).await().isSuccess()).isTrue();
        // 4th should fail
        assertThat(guard.guard(() -> Promise.success(4)).await().isFailure()).isTrue();
    }

    @Test
    void factory_provisions_from_config() {
        var factory = new RateGuardFactory();
        assertThat(factory.resourceType()).isEqualTo(org.pragmatica.aether.slice.RateGuard.class);
        assertThat(factory.configType()).isEqualTo(RateGuardConfig.class);

        var result = factory.provision(new RateGuardConfig(100, 20, "local")).await();
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void config_validation_rejects_zero_rate() {
        var result = RateGuardConfig.rateGuardConfig(0, 10);
        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void config_validation_rejects_negative_burst() {
        var result = RateGuardConfig.rateGuardConfig(100, -1);
        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void config_defaults_are_reasonable() {
        var result = RateGuardConfig.rateGuardConfig();
        assertThat(result.isSuccess()).isTrue();
        var config = result.unwrap();
        assertThat(config.requestsPerSecond()).isEqualTo(100);
        assertThat(config.burst()).isEqualTo(20);
        assertThat(config.type()).isEqualTo("local");
    }

    @Test
    void limit_exceeded_message_is_descriptive() {
        var error = RateGuardError.LimitExceeded.limitExceeded(5000, 100, 0, System.currentTimeMillis() + 5000);
        assertThat(error.message()).contains("Rate limit exceeded");
        assertThat(error.message()).contains("5000ms");
    }
}
