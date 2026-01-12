package org.pragmatica.aether.infra.ratelimit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class RateLimiterTest {
    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        var config = RateLimitConfig.rateLimitConfig(5, timeSpan(1).seconds()).unwrap();
        rateLimiter = RateLimiter.inMemory(config);
    }

    @Test
    void acquire_allows_within_limit() {
        rateLimiter.acquire("test-key")
                   .await()
                   .onFailureRun(Assertions::fail)
                   .onSuccess(result -> {
                       assertThat(result.allowed()).isTrue();
                       assertThat(result.remaining()).isEqualTo(4);
                   });
    }

    @Test
    void acquire_denies_when_limit_exceeded() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.acquire("test-key").await();
        }

        rateLimiter.acquire("test-key")
                   .await()
                   .onFailureRun(Assertions::fail)
                   .onSuccess(result -> {
                       assertThat(result.allowed()).isFalse();
                       assertThat(result.remaining()).isEqualTo(0);
                       assertThat(result.retryAfter().nanos()).isGreaterThan(0);
                   });
    }

    @Test
    void acquire_multiple_permits_at_once() {
        rateLimiter.acquire("test-key", 3)
                   .await()
                   .onFailureRun(Assertions::fail)
                   .onSuccess(result -> {
                       assertThat(result.allowed()).isTrue();
                       assertThat(result.remaining()).isEqualTo(2);
                   });
    }

    @Test
    void acquire_denies_when_not_enough_permits() {
        rateLimiter.acquire("test-key", 3).await();

        rateLimiter.acquire("test-key", 3)
                   .await()
                   .onFailureRun(Assertions::fail)
                   .onSuccess(result -> assertThat(result.allowed()).isFalse());
    }

    @Test
    void check_does_not_consume_permits() {
        rateLimiter.check("test-key")
                   .await()
                   .onFailureRun(Assertions::fail)
                   .onSuccess(result -> assertThat(result.remaining()).isEqualTo(5));

        rateLimiter.check("test-key")
                   .await()
                   .onFailureRun(Assertions::fail)
                   .onSuccess(result -> assertThat(result.remaining()).isEqualTo(5));
    }

    @Test
    void different_keys_have_separate_limits() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.acquire("key1").await();
        }

        rateLimiter.acquire("key2")
                   .await()
                   .onFailureRun(Assertions::fail)
                   .onSuccess(result -> {
                       assertThat(result.allowed()).isTrue();
                       assertThat(result.remaining()).isEqualTo(4);
                   });
    }

    @Test
    void configure_overrides_default_config() {
        var customConfig = RateLimitConfig.rateLimitConfig(2, timeSpan(1).seconds()).unwrap();
        rateLimiter.configure("custom-key", customConfig).await();

        rateLimiter.acquire("custom-key").await();
        rateLimiter.acquire("custom-key").await();

        rateLimiter.acquire("custom-key")
                   .await()
                   .onFailureRun(Assertions::fail)
                   .onSuccess(result -> assertThat(result.allowed()).isFalse());
    }

    @Test
    void rateLimitConfig_rejects_non_positive_max_requests() {
        RateLimitConfig.rateLimitConfig(0, timeSpan(1).seconds())
                       .onSuccessRun(Assertions::fail);

        RateLimitConfig.rateLimitConfig(-1, timeSpan(1).seconds())
                       .onSuccessRun(Assertions::fail);
    }

    @Test
    void rateLimitConfig_accepts_custom_strategy() {
        RateLimitConfig.rateLimitConfig(100, timeSpan(1).minutes(), RateLimitStrategy.TOKEN_BUCKET)
                       .onFailureRun(Assertions::fail)
                       .onSuccess(config -> {
                           assertThat(config.maxRequests()).isEqualTo(100);
                           assertThat(config.strategy()).isEqualTo(RateLimitStrategy.TOKEN_BUCKET);
                       });
    }

    @Test
    void default_config_has_sensible_values() {
        RateLimitConfig.defaultConfig()
                       .onFailureRun(Assertions::fail)
                       .onSuccess(config -> {
                           assertThat(config.maxRequests()).isEqualTo(100);
                           assertThat(config.window()).isEqualTo(timeSpan(1).minutes());
                           assertThat(config.strategy()).isEqualTo(RateLimitStrategy.FIXED_WINDOW);
                       });
    }

    @Test
    void window_resets_after_expiry() throws InterruptedException {
        var shortConfig = RateLimitConfig.rateLimitConfig(2, timeSpan(100).millis()).unwrap();
        var limiter = RateLimiter.inMemory(shortConfig);

        limiter.acquire("key").await();
        limiter.acquire("key").await();

        limiter.acquire("key")
               .await()
               .onSuccess(result -> assertThat(result.allowed()).isFalse());

        Thread.sleep(150);

        limiter.acquire("key")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(result -> {
                   assertThat(result.allowed()).isTrue();
                   assertThat(result.remaining()).isEqualTo(1);
               });
    }
}
