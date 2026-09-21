// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;


/// Test-only: capture the formatted WARN messages one class's logger emits. The returned runnable
/// detaches the appender; call it in a `finally`. Filtering on the logger NAME keeps other loggers'
/// WARNs out. Same shape as the deployment module's capture helpers.
@SuppressWarnings("JBCT-RET-01")  // `Appender.append` is log4j's void override
final class LogCapture {
    private LogCapture() {}

    static Runnable warningsOf(Class<?> loggerOwner, List<String> sink) {
        var loggerName = loggerOwner.getName();
        var context = (LoggerContext) LogManager.getContext(false);
        var config = context.getConfiguration();
        var loggerConfig = config.getLoggerConfig(loggerName);
        var appender = new AbstractAppender("warn-capture-" + loggerOwner.getSimpleName(),
                                            null,
                                            PatternLayout.createDefaultLayout(),
                                            true,
                                            Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (event.getLevel() == Level.WARN && loggerName.equals(event.getLoggerName())) {
                    sink.add(event.getMessage().getFormattedMessage());
                }
            }
        };

        appender.start();
        loggerConfig.addAppender(appender, Level.WARN, null);
        context.updateLoggers();

        return () -> {
            loggerConfig.removeAppender(appender.getName());
            context.updateLoggers();
            appender.stop();
        };
    }
}
