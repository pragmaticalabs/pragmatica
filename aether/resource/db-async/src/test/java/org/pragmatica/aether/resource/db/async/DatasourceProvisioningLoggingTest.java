// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.db.async;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.db.DatasourceConnectionProvider;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;

/**
 * #769: provisioning must log the resolved effective connection target (post override
 * precedence), not the raw discrete config — otherwise "the log claims the override was
 * applied" is not actually checkable from the log.
 */
class DatasourceProvisioningLoggingTest {

    private static final String PROVIDER_LOGGER = "org.pragmatica.aether.resource.db.DatasourceConnectionProviderInstance";

    @Test
    void connector_withAsyncUrlOverride_logsTheOverrideHostNotTheDiscreteHost() {
        var config = databaseConnectorConfigBuilder()
                .withName("db")
                .withHost("discrete-host")
                .withPort(5432)
                .withDatabase("discrete-db")
                .withAsyncUrl("postgresql://override-host:6543/override-db")
                .build()
                .unwrap();

        var provider = DatasourceConnectionProvider.datasourceConnectionProvider((section, configClass) -> Result.success(config));

        var lines = captureProviderLogLines(() -> {
            var outcome = provider.connector("db").await();
            assertThat(outcome.isSuccess()).isTrue();
        });

        assertThat(lines).anySatisfy(line -> assertThat(line).contains("override-host")
                                                              .contains("6543")
                                                              .contains("override-db")
                                                              .doesNotContain("discrete-host"));
    }

    private static List<String> captureProviderLogLines(Runnable action) {
        var attachment = attachAppender();
        try {
            action.run();
            return List.copyOf(attachment.appender().messages());
        } finally {
            detachAppender(attachment);
        }
    }

    private static Attachment attachAppender() {
        var ctx = (LoggerContext) LogManager.getContext(false);
        var config = ctx.getConfiguration();
        var appender = new CapturingAppender();
        appender.start();
        config.addAppender(appender);
        var loggerConfig = config.getLoggerConfig(PROVIDER_LOGGER);
        var previousLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.ALL, null);
        loggerConfig.setLevel(Level.TRACE);
        ctx.updateLoggers();
        return new Attachment(ctx, appender, loggerConfig, previousLevel);
    }

    private static void detachAppender(Attachment attachment) {
        attachment.loggerConfig().removeAppender(attachment.appender().getName());
        attachment.loggerConfig().setLevel(attachment.previousLevel());
        attachment.ctx().updateLoggers();
        attachment.appender().stop();
    }

    private record Attachment(LoggerContext ctx, CapturingAppender appender, LoggerConfig loggerConfig, Level previousLevel) {}

    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private CapturingAppender() {
            super("db-async-provisioning-test-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }

        List<String> messages() {
            return messages;
        }
    }
}
