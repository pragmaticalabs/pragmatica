package org.pragmatica.aether.infra.aspect;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingAspectFactoryTest {

    interface Calculator {
        int add(int a, int b);
        String concat(String a, String b);
    }

    static class SimpleCalculator implements Calculator {
        @Override
        public int add(int a, int b) {
            return a + b;
        }

        @Override
        public String concat(String a, String b) {
            return a + b;
        }
    }

    @Test
    void create_wraps_instance_with_logging() {
        var factory = LoggingAspectFactory.loggingAspectFactory();
        var calculator = new SimpleCalculator();
        var config = LogConfig.logConfig("Calculator").unwrap();

        var wrapped = factory.<Calculator>create(config).apply(calculator);

        assertThat(wrapped.add(2, 3)).isEqualTo(5);
        assertThat(wrapped.concat("Hello", "World")).isEqualTo("HelloWorld");
    }

    @Test
    void disabled_factory_returns_original_instance() {
        var factory = LoggingAspectFactory.loggingAspectFactory();
        factory.setEnabled(false);
        var calculator = new SimpleCalculator();
        var config = LogConfig.logConfig("Calculator").unwrap();

        var wrapped = factory.<Calculator>create(config).apply(calculator);

        // When disabled, should return the original instance
        assertThat(wrapped).isSameAs(calculator);
    }

    @Test
    void factory_method_creates_instance() {
        var factory = LoggingAspectFactory.loggingAspectFactory();
        assertThat(factory).isNotNull();
        assertThat(factory.isEnabled()).isTrue();
    }

    @Test
    void logConfig_factory_creates_with_defaults() {
        LogConfig.logConfig("Test")
            .onFailureRun(Assertions::fail)
            .onSuccess(config -> {
                assertThat(config.name()).isEqualTo("Test");
                assertThat(config.level()).isEqualTo(LogLevel.INFO);
                assertThat(config.logArgs()).isTrue();
                assertThat(config.logResult()).isTrue();
                assertThat(config.logDuration()).isTrue();
            });
    }

    @Test
    void logConfig_rejects_blank_name() {
        LogConfig.logConfig("")
            .onSuccessRun(Assertions::fail);

        LogConfig.logConfig("   ")
            .onSuccessRun(Assertions::fail);
    }

    @Test
    void logConfig_with_methods_create_copies() {
        var config = LogConfig.logConfig("Test").unwrap()
            .withLevel(LogLevel.DEBUG)
            .withLogArgs(false)
            .withLogResult(false)
            .withLogDuration(false);

        assertThat(config.level()).isEqualTo(LogLevel.DEBUG);
        assertThat(config.logArgs()).isFalse();
        assertThat(config.logResult()).isFalse();
        assertThat(config.logDuration()).isFalse();
    }
}
