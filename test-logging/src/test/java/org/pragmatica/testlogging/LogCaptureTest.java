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
}
