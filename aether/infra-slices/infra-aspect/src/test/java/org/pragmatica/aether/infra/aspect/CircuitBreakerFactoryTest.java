package org.pragmatica.aether.infra.aspect;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.CircuitBreaker.CircuitBreakerErrors.CircuitBreakerOpenError;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class CircuitBreakerFactoryTest {
    private CircuitBreakerFactory factory;

    interface Service {
        Promise<String> doWork();
        String syncWork();
    }

    @BeforeEach
    void setUp() {
        factory = CircuitBreakerFactory.circuitBreakerFactory();
    }

    @Test
    void successful_call_does_not_trip_circuit() {
        var counter = new AtomicInteger(0);
        Service service = createSuccessfulService(counter);
        var config = CircuitBreakerConfig.circuitBreakerConfig(3).unwrap();
        var wrapped = factory.<Service>create(config).apply(service);

        wrapped.doWork()
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(result -> assertThat(result).isEqualTo("success"));

        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void circuit_opens_after_threshold_failures() {
        var counter = new AtomicInteger(0);
        Service service = createFailingService(counter);
        var config = CircuitBreakerConfig.circuitBreakerConfig(3, timeSpan(10).seconds(), 1).unwrap();
        var wrapped = factory.<Service>create(config).apply(service);

        // Cause 3 failures to open the circuit
        for (int i = 0; i < 3; i++) {
            wrapped.doWork()
                   .await()
                   .onSuccessRun(Assertions::fail);
        }

        assertThat(counter.get()).isEqualTo(3);

        // Next call should fail immediately with circuit open error
        wrapped.doWork()
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(CircuitBreakerOpenError.class));
    }

    @Test
    void sync_methods_are_not_protected_by_circuit_breaker() {
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
        var config = CircuitBreakerConfig.circuitBreakerConfig(3).unwrap();
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
        var config = CircuitBreakerConfig.circuitBreakerConfig(3).unwrap();

        var wrapped = factory.<Service>create(config).apply(service);

        assertThat(wrapped).isSameAs(service);
    }

    @Test
    void factory_method_creates_instance() {
        assertThat(factory).isNotNull();
        assertThat(factory.isEnabled()).isTrue();
    }

    @Test
    void circuitBreakerConfig_rejects_non_positive_threshold() {
        CircuitBreakerConfig.circuitBreakerConfig(0)
                            .onSuccessRun(Assertions::fail);

        CircuitBreakerConfig.circuitBreakerConfig(-1)
                            .onSuccessRun(Assertions::fail);
    }

    @Test
    void circuitBreakerConfig_accepts_custom_parameters() {
        CircuitBreakerConfig.circuitBreakerConfig(10, timeSpan(60).seconds(), 5)
                            .onFailureRun(Assertions::fail)
                            .onSuccess(config -> {
                                assertThat(config.failureThreshold()).isEqualTo(10);
                                assertThat(config.resetTimeout()).isEqualTo(timeSpan(60).seconds());
                                assertThat(config.testAttempts()).isEqualTo(5);
                            });
    }

    @Test
    void default_config_has_sensible_defaults() {
        CircuitBreakerConfig.circuitBreakerConfig()
                            .onFailureRun(Assertions::fail)
                            .onSuccess(config -> {
                                assertThat(config.failureThreshold()).isEqualTo(5);
                                assertThat(config.resetTimeout()).isEqualTo(timeSpan(30).seconds());
                                assertThat(config.testAttempts()).isEqualTo(3);
                            });
    }

    private Service createSuccessfulService(AtomicInteger counter) {
        return new Service() {
            @Override
            public Promise<String> doWork() {
                counter.incrementAndGet();
                return Promise.success("success");
            }

            @Override
            public String syncWork() {
                return "sync";
            }
        };
    }

    private Service createFailingService(AtomicInteger counter) {
        return new Service() {
            @Override
            public Promise<String> doWork() {
                counter.incrementAndGet();
                return new TestFailure("test failure").promise();
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
