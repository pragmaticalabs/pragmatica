package org.pragmatica.aether.infra.aspect;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsAspectFactoryTest {
    private SimpleMeterRegistry registry;
    private MetricsAspectFactory factory;

    interface Calculator {
        int add(int a, int b);
        Promise<Integer> asyncMultiply(int a, int b);
    }

    static class SimpleCalculator implements Calculator {
        @Override
        public int add(int a, int b) {
            return a + b;
        }

        @Override
        public Promise<Integer> asyncMultiply(int a, int b) {
            return Promise.success(a * b);
        }
    }

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        factory = MetricsAspectFactory.metricsAspectFactory(registry);
    }

    @Test
    void create_wraps_instance_with_metrics() {
        var calculator = new SimpleCalculator();
        var config = MetricsConfig.metricsConfig("Calculator").unwrap();

        var wrapped = factory.<Calculator>create(config).apply(calculator);

        assertThat(wrapped.add(2, 3)).isEqualTo(5);
    }

    @Test
    void sync_method_records_timer() {
        var calculator = new SimpleCalculator();
        var config = MetricsConfig.metricsConfig("Calculator").unwrap();
        var wrapped = factory.<Calculator>create(config).apply(calculator);

        wrapped.add(2, 3);

        // Verify at least one timer was registered
        var timers = registry.getMeters().stream()
                             .filter(m -> m.getId().getName().contains("Calculator"))
                             .toList();
        assertThat(timers).isNotEmpty();
    }

    @Test
    void async_method_records_metrics_on_completion() {
        var calculator = new SimpleCalculator();
        var config = MetricsConfig.metricsConfig("Calculator").unwrap();
        var wrapped = factory.<Calculator>create(config).apply(calculator);

        wrapped.asyncMultiply(3, 4)
               .await()
               .onFailure(c -> Assertions.fail())
               .onSuccess(result -> assertThat(result).isEqualTo(12));
    }

    @Test
    void disabled_factory_returns_original_instance() {
        factory.setEnabled(false);
        var calculator = new SimpleCalculator();
        var config = MetricsConfig.metricsConfig("Calculator").unwrap();

        var wrapped = factory.<Calculator>create(config).apply(calculator);

        assertThat(wrapped).isSameAs(calculator);
    }

    @Test
    void factory_method_creates_instance() {
        assertThat(factory).isNotNull();
        assertThat(factory.isEnabled()).isTrue();
        assertThat(factory.registry()).isSameAs(registry);
    }

    @Test
    void metricsConfig_factory_creates_with_defaults() {
        MetricsConfig.metricsConfig("Test")
                     .onFailureRun(Assertions::fail)
                     .onSuccess(config -> {
                         assertThat(config.name()).isEqualTo("Test");
                         assertThat(config.recordTiming()).isTrue();
                         assertThat(config.recordCounts()).isTrue();
                     });
    }

    @Test
    void metricsConfig_rejects_blank_name() {
        MetricsConfig.metricsConfig("")
                     .onSuccessRun(Assertions::fail);

        MetricsConfig.metricsConfig("   ")
                     .onSuccessRun(Assertions::fail);
    }

    @Test
    void metricsConfig_withTags_creates_copy_with_tags() {
        var config = MetricsConfig.metricsConfig("Test").unwrap()
                                  .withTags("service", "orders", "region", "us-west");

        assertThat(config.tags()).containsExactly("service", "orders", "region", "us-west");
    }

    @Test
    void metricsConfig_custom_settings() {
        MetricsConfig.metricsConfig("Test", false, true)
                     .onFailureRun(Assertions::fail)
                     .onSuccess(config -> {
                         assertThat(config.name()).isEqualTo("Test");
                         assertThat(config.recordTiming()).isFalse();
                         assertThat(config.recordCounts()).isTrue();
                     });
    }
}
