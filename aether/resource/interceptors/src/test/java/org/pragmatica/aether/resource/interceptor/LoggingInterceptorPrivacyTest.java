// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.lang.reflect.Field;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #280 R28: `LogConfig`'s programmatic defaults logged full request and result content
/// (`logArgs = true`, `logResult = true`), and every injection point shared one logger named after
/// the interceptor class, so no per-method level could be set. Content logging must be OPT-IN,
/// and each injection point must log through a logger carrying its own configured name.
class LoggingInterceptorPrivacyTest {
    @Test
    void logConfig_defaults_doNotLogArgsOrResults() {
        var config = LogConfig.logConfig("payment.flow").fold(cause -> fail("valid name: " + cause.message()),
                                                              c -> c);

        assertThat(config.logArgs()).as("request content is PII until proven otherwise; logging it is opt-in").isFalse();
        assertThat(config.logResult()).as("result content is PII until proven otherwise; logging it is opt-in")
                  .isFalse();
        assertThat(config.logDuration()).isTrue();
    }

    @Test
    void logConfig_defaultsWithLevel_doNotLogArgsOrResults() {
        var config = LogConfig.logConfig("payment.flow", LogLevel.DEBUG).fold(cause -> fail("valid name: " + cause.message()),
                                                                              c -> c);

        assertThat(config.logArgs()).isFalse();
        assertThat(config.logResult()).isFalse();
    }

    /// Read through reflection so the probe compiles against both shapes: the old static
    /// class-named logger and the per-instance one.
    @Test
    void interceptor_logsThroughALoggerNamedForItsInjectionPoint() {
        var config = LogConfig.logConfig("payment.flow").fold(cause -> fail("valid name: " + cause.message()),
                                                              c -> c);
        var interceptor = new LoggingMethodInterceptor(config);

        assertThat(loggerOf(interceptor).getName()).as("per-injection-point logger, so one method's level can be tuned without the others")
                  .isEqualTo("payment.flow");
    }

    private static Logger loggerOf(LoggingMethodInterceptor interceptor) {
        return Arrays.stream(LoggingMethodInterceptor.class.getDeclaredFields())
                     .filter(field -> Logger.class.isAssignableFrom(field.getType()))
                     .findFirst()
                     .map(field -> readLogger(field, interceptor))
                     .orElseGet(() -> fail("LoggingMethodInterceptor declares no Logger field"));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Logger readLogger(Field field, LoggingMethodInterceptor interceptor) {
        try {
            field.setAccessible(true);

            return (Logger) field.get(interceptor);
        } catch (IllegalAccessException e) {
            return fail("logger field unreadable: " + e);
        }
    }
}
