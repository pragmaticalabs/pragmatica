// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

/// Captures the events of named loggers, with their level, in memory. Closing it restores the logger configuration.
final class LogCapture implements AutoCloseable {
    record Line(String logger, Level level, String message) {}

    private final List<Line> lines = new CopyOnWriteArrayList<>();
    private final List<LoggerConfig> configs = new java.util.ArrayList<>();
    private final List<Level> levels = new java.util.ArrayList<>();
    private final Appender appender;

    LogCapture(String... loggerNames) {
        appender = new Appender(lines);
        appender.start();
        var context = (LoggerContext) LogManager.getContext(false);

        for (var name : loggerNames) {
            var existing = context.getConfiguration().getLoggerConfig(name);
            var config = name.equals(existing.getName())
                         ? existing
                         : fresh(context, name);

            configs.add(config);
            levels.add(config.getLevel());
            config.addAppender(appender, Level.ALL, null);
            config.setLevel(Level.ALL);
        }

        context.updateLoggers();
    }

    private static LoggerConfig fresh(LoggerContext context, String name) {
        var config = new LoggerConfig(name, Level.ALL, false);

        context.getConfiguration().addLogger(name, config);

        return config;
    }

    List<Line> lines() {
        return List.copyOf(lines);
    }

    List<Line> on(String logger) {
        return lines.stream().filter(line -> line.logger().equals(logger)).toList();
    }

    @Override
    public void close() {
        for (var i = 0; i < configs.size(); i++) {
            configs.get(i).removeAppender(appender.getName());
            configs.get(i).setLevel(levels.get(i));
        }

        ((LoggerContext) LogManager.getContext(false)).updateLoggers();
        appender.stop();
    }

    private static final class Appender extends AbstractAppender {
        private final List<Line> sink;

        Appender(List<Line> sink) {
            super("deployment-fsm-log-capture-" + System.nanoTime(), (Filter) null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
            this.sink = sink;
        }

        @Override
        public void append(LogEvent event) {
            sink.add(new Line(event.getLoggerName(), event.getLevel(), event.getMessage().getFormattedMessage()));
        }
    }
}
