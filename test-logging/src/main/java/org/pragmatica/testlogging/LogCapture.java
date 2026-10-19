/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.testlogging;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

/// Captures the events of one named logger for the duration of a test and ALWAYS puts the log4j2
/// configuration back as it found it.
///
/// Why this exists: `Configuration.addLogger` is a `putIfAbsent`. A test that registers a
/// `LoggerConfig` for a name and never removes it silently disables the capture of every later test in
/// the same JVM that registers the same name (its own config is ignored, its appender never consulted,
/// the capture reads `[]`), and which tests share a JVM depends on surefire's run order. So the capture
/// does not depend on the name being free: it REPLACES whatever config is registered under the exact
/// name, and [#close] removes its own and re-registers the one it displaced.
///
/// Use in try-with-resources, or hold in a field and close in `@AfterEach`.
public final class LogCapture implements AutoCloseable {
    /// One captured event.
    public record Entry(Level level, String message) {}

    private final LoggerContext context;
    private final String loggerName;
    private final LoggerConfig displaced;
    private final CapturingAppender appender;

    private LogCapture(LoggerContext context, String loggerName, LoggerConfig displaced, CapturingAppender appender) {
        this.context = context;
        this.loggerName = loggerName;
        this.displaced = displaced;
        this.appender = appender;
    }

    /// Captures events of `logger` at `threshold` and more severe.
    ///
    /// @param additive whether events also continue to the parent loggers' appenders (the console)
    public static LogCapture capture(Class<?> logger, Level threshold, boolean additive) {
        return capture(logger.getName(), threshold, additive);
    }

    /// Captures events of the logger named `loggerName` at `threshold` and more severe.
    ///
    /// @param additive whether events also continue to the parent loggers' appenders (the console)
    public static LogCapture capture(String loggerName, Level threshold, boolean additive) {
        var context = (LoggerContext) LogManager.getContext(false);
        var configuration = context.getConfiguration();
        var displaced = exactConfig(configuration, loggerName);
        var appender = new CapturingAppender("LogCapture-" + loggerName);
        var installed = new LoggerConfig(loggerName, threshold, additive);

        appender.start();
        installed.addAppender(appender, threshold, null);
        configuration.removeLogger(loggerName);
        configuration.addLogger(loggerName, installed);
        context.updateLoggers();
        return new LogCapture(context, loggerName, displaced, appender);
    }

    /// Messages of every captured event.
    public List<String> messages() {
        return appender.entries.stream().map(Entry::message).toList();
    }

    /// Messages of the captured events at exactly `level`.
    public List<String> messages(Level level) {
        return appender.entries.stream().filter(entry -> entry.level() == level).map(Entry::message).toList();
    }

    /// Every captured event, in arrival order.
    public List<Entry> entries() {
        return List.copyOf(appender.entries);
    }

    /// Removes the capture's config, re-registers the one it displaced and stops the appender.
    /// Idempotent.
    @Override
    public void close() {
        var configuration = context.getConfiguration();

        configuration.removeLogger(loggerName);
        if (displaced != null) {
            configuration.addLogger(loggerName, displaced);
        }
        context.updateLoggers();
        appender.stop();
    }

    /// `getLoggerConfig` answers the nearest ancestor when the exact name is unregistered; only a config
    /// registered under the exact name is ours to displace and restore.
    private static LoggerConfig exactConfig(Configuration configuration, String loggerName) {
        Map<String, LoggerConfig> registered = configuration.getLoggers();

        return registered.get(loggerName);
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<Entry> entries = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name) {
            super(name, (Filter) null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            entries.add(new Entry(event.getLevel(), event.getMessage().getFormattedMessage()));
        }
    }
}
