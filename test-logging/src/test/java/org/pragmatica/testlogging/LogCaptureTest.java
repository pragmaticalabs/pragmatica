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

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogCaptureTest {
    private static final String NAME = "org.pragmatica.testlogging.LogCaptureTest.subject";

    private static LoggerContext context() {
        return (LoggerContext) LogManager.getContext(false);
    }

    private static void register(LoggerConfig config) {
        context().getConfiguration().addLogger(NAME, config);
        context().updateLoggers();
    }

    private static LoggerConfig registered() {
        return context().getConfiguration().getLoggers().get(NAME);
    }

    /// The leak this class exists to survive: a stale WARN config under the same name makes `addLogger`
    /// (a putIfAbsent) ignore a newer INFO config, so INFO events are dropped and the capture reads [].
    @Test
    void capture_seesInfoEvents_whenAStaleWarnConfigIsRegisteredUnderTheSameName() {
        var stale = new LoggerConfig(NAME, Level.WARN, true);

        register(stale);
        try (var capture = LogCapture.capture(NAME, Level.INFO, true)) {
            LogManager.getLogger(NAME).info("hello");

            assertThat(capture.messages(Level.INFO)).containsExactly("hello");
        } finally {
            context().getConfiguration().removeLogger(NAME);
            context().updateLoggers();
        }
    }

    @Test
    void close_reRegistersTheDisplacedConfig() {
        var stale = new LoggerConfig(NAME, Level.WARN, true);

        register(stale);
        try {
            LogCapture.capture(NAME, Level.INFO, true).close();

            assertThat(registered()).isSameAs(stale);
        } finally {
            context().getConfiguration().removeLogger(NAME);
            context().updateLoggers();
        }
    }

    @Test
    void close_leavesNoConfigBehind_whenNoneWasRegistered() {
        LogCapture.capture(NAME, Level.INFO, true).close();

        assertThat(registered()).isNull();
    }

    @Test
    void events_carryTheThrowableAndTheLoggerName() {
        try (var capture = LogCapture.capture(NAME, Level.INFO, true)) {
            LogManager.getLogger(NAME).error("boom", new IllegalStateException("cause"));

            var event = capture.events().getFirst();

            assertThat(event.getThrown()).isInstanceOf(IllegalStateException.class);
            assertThat(event.getLoggerName()).isEqualTo(NAME);
            assertThat(capture.entries()).containsExactly(new LogCapture.Entry(Level.ERROR, "boom"));
        }
    }

    @Test
    void observer_seesEveryEventSynchronouslyInArrivalOrder() {
        var seen = new java.util.ArrayList<String>();

        try (var capture = LogCapture.capture(NAME, Level.INFO, true, event -> seen.add(event.getMessage().getFormattedMessage()))) {
            LogManager.getLogger(NAME).info("one");
            LogManager.getLogger(NAME).warn("two");

            assertThat(seen).containsExactly("one", "two");
            assertThat(capture.messages()).containsExactly("one", "two");
        }
    }

    @Test
    void captureRoot_seesEventsOfAReplacementConfiguration_andDetachesOnClose() {
        var configuration = context().getConfiguration();
        var before = configuration.getRootLogger().getAppenders().size();

        try (var capture = LogCapture.captureRoot(configuration)) {
            LogManager.getLogger(NAME).warn("rooted");

            assertThat(capture.messages(Level.WARN)).contains("rooted");
        }

        assertThat(configuration.getRootLogger().getAppenders()).hasSize(before);
    }
}
