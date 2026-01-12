package org.pragmatica.aether.infra.aspect;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class RetryAspectFactoryTest {
    private RetryAspectFactory factory;

    interface Service {
        Promise<String> doWork();
        String syncWork();
    }

    @BeforeEach
    void setUp() {
        factory = RetryAspectFactory.retryAspectFactory();
    }

    @Test
    void successful_call_does_not_retry() {
        var counter = new AtomicInteger(0);
        Service service = createService(counter, 0);
        var config = RetryConfig.retryConfig(3).unwrap();
        var wrapped = factory.<Service>create(config).apply(service);

        wrapped.doWork()
               .await()
               .onFailure(c -> Assertions.fail())
               .onSuccess(result -> assertThat(result).isEqualTo("success"));

        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void retries_on_failure_until_success() {
        var counter = new AtomicInteger(0);
        Service service = createService(counter, 2); // Fail twice then succeed
        var config = RetryConfig.retryConfig(5, timeSpan(10).millis()).unwrap();
        var wrapped = factory.<Service>create(config).apply(service);

        wrapped.doWork()
               .await()
               .onFailure(c -> Assertions.fail())
               .onSuccess(result -> assertThat(result).isEqualTo("success"));

        assertThat(counter.get()).isEqualTo(3); // 2 failures + 1 success
    }

    @Test
    void fails_after_max_retries() {
        var counter = new AtomicInteger(0);
        Service service = createService(counter, 10); // Always fail
        var config = RetryConfig.retryConfig(3, timeSpan(10).millis()).unwrap();
        var wrapped = factory.<Service>create(config).apply(service);

        wrapped.doWork()
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause.message()).contains("test failure"));

        assertThat(counter.get()).isEqualTo(3);
    }

    @Test
    void sync_methods_are_not_retried() {
        var counter = new AtomicInteger(0);
        Service service = new Service() {
            @Override
            public Promise<String> doWork() {
                return Promise.success("async");
            }

            @Override
            public String syncWork() {
                counter.incrementAndGet();
                return "sync";
            }
        };
        var config = RetryConfig.retryConfig(3).unwrap();
        var wrapped = factory.<Service>create(config).apply(service);

        assertThat(wrapped.syncWork()).isEqualTo("sync");
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void disabled_factory_returns_original_instance() {
        factory.setEnabled(false);
        Service service = new Service() {
            @Override
            public Promise<String> doWork() {
                return Promise.success("test");
            }

            @Override
            public String syncWork() {
                return "sync";
            }
        };
        var config = RetryConfig.retryConfig(3).unwrap();

        var wrapped = factory.<Service>create(config).apply(service);

        assertThat(wrapped).isSameAs(service);
    }

    @Test
    void factory_method_creates_instance() {
        assertThat(factory).isNotNull();
        assertThat(factory.isEnabled()).isTrue();
    }

    @Test
    void retryConfig_rejects_non_positive_attempts() {
        RetryConfig.retryConfig(0)
                   .onSuccessRun(Assertions::fail);

        RetryConfig.retryConfig(-1)
                   .onSuccessRun(Assertions::fail);
    }

    @Test
    void retryConfig_accepts_custom_backoff_strategy() {
        RetryConfig.retryConfig(5, BackoffStrategy.fixed().interval(timeSpan(50).millis()))
                   .onFailureRun(Assertions::fail)
                   .onSuccess(config -> {
                       assertThat(config.maxAttempts()).isEqualTo(5);
                       assertThat(config.backoffStrategy()).isNotNull();
                   });
    }

    private Service createService(AtomicInteger counter, int failuresBeforeSuccess) {
        return new Service() {
            @Override
            public Promise<String> doWork() {
                int attempt = counter.incrementAndGet();
                if (attempt <= failuresBeforeSuccess) {
                    return new TestFailure("test failure attempt " + attempt).promise();
                }
                return Promise.success("success");
            }

            @Override
            public String syncWork() {
                return "sync";
            }
        };
    }

    private record TestFailure(String detail) implements Cause {
        @Override
        public String message() {
            return "test failure: " + detail;
        }
    }
}
