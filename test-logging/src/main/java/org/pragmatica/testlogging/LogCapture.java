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
import java.util.function.Consumer;

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

import java.util.concurrent.CopyOnWriteArrayList;

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
/// Use in try-with-resources, or hold it in a field and close it in `@AfterEach`.
public final class LogCapture implements AutoCloseable {
    /// One captured event: level and formatted message.
    public record Entry(Level level, String message) {}

    private final CapturingAppender appender;
    private final Runnable restore;

    private LogCapture(CapturingAppender appender, Runnable restore) {
        this.appender = appender;
        this.restore = restore;
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
        return capture(loggerName, threshold, additive, _ -> {});
    }

    /// As [#capture(String, Level, boolean)], and `observer` is also called synchronously, on the logging thread, for
    /// every captured event (arrival order is preserved).
    public static LogCapture capture(String loggerName, Level threshold, boolean additive, Consumer<LogEvent> observer) {
        var context = (LoggerContext) LogManager.getContext(false);
        var configuration = context.getConfiguration();
        var displaced = configuration.getLoggers().get(loggerName);
        var appender = new CapturingAppender("LogCapture-" + loggerName, observer);
        var installed = new LoggerConfig(loggerName, threshold, additive);

        appender.start();
        installed.addAppender(appender, threshold, null);
        configuration.removeLogger(loggerName);
        configuration.addLogger(loggerName, installed);
        context.updateLoggers();
        return new LogCapture(appender, () -> restoreLogger(context, loggerName, displaced));
    }

    /// Captures every event reaching the root logger of `configuration`: for a test that installs a whole replacement
    /// configuration (the one under test) rather than capturing one logger of the shared test configuration. [#close]
    /// detaches the capture from that root.
    public static LogCapture captureRoot(Configuration configuration) {
        var root = configuration.getRootLogger();
        var appender = new CapturingAppender("LogCapture-root", _ -> {});
        var context = (LoggerContext) LogManager.getContext(false);

        appender.start();
        root.addAppender(appender, null, null);
        context.updateLoggers();
        return new LogCapture(appender, () -> detachRoot(context, root, appender));
    }

    /// Messages of every captured event.
    public List<String> messages() {
        return entries().stream().map(Entry::message).toList();
    }

    /// Messages of the captured events at exactly `level`.
    public List<String> messages(Level level) {
        return entries().stream().filter(entry -> entry.level() == level).map(Entry::message).toList();
    }

    /// Level and message of every captured event, in arrival order.
    public List<Entry> entries() {
        return events().stream().map(event -> new Entry(event.getLevel(), event.getMessage().getFormattedMessage())).toList();
    }

    /// Immutable snapshots of every captured event, in arrival order: for assertions on more than the message (the
    /// throwable, the logger name, the thread).
    public List<LogEvent> events() {
        return List.copyOf(appender.events);
    }

    /// Undoes the capture: removes its config and re-registers the one it displaced (or detaches from the root), and
    /// stops the appender. Idempotent.
    @Override
    public void close() {
        restore.run();
        appender.stop();
    }

    private static void restoreLogger(LoggerContext context, String loggerName, LoggerConfig displaced) {
        var configuration = context.getConfiguration();

        configuration.removeLogger(loggerName);
        if (displaced != null) {
            configuration.addLogger(loggerName, displaced);
        }
        context.updateLoggers();
    }

    private static void detachRoot(LoggerContext context, LoggerConfig root, CapturingAppender appender) {
        root.removeAppender(appender.getName());
        context.updateLoggers();
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();
        private final Consumer<LogEvent> observer;

        private CapturingAppender(String name, Consumer<LogEvent> observer) {
            super(name, (Filter) null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
            this.observer = observer;
        }

        @Override
        public void append(LogEvent event) {
            var snapshot = event.toImmutable();

            observer.accept(snapshot);
            events.add(snapshot);
        }
    }
}
