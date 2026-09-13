// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.logging;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1077: nine production classes log through `System.Logger` (JDK Platform Logging). Without
/// `log4j-jpl` on the node's classpath that binds to `java.util.logging`, so their DEBUG lines are
/// dropped by JUL's INFO root and their WARNINGs bypass the node's JSON layout — a diagnostic
/// nobody can read. This pins, on the node's own classpath, that a `System.Logger` DEBUG line
/// from one of those classes — `ResourceFactory`'s "no close convention" outcome (#891/#893) —
/// arrives in log4j: 1 captured event with the bridge, 0 without.
class SystemLoggerBridgeTest {
    private static final String LOGGER_NAME = ResourceFactory.class.getName();

    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        appender = CapturingAppender.create("SystemLoggerBridgeCapture");
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig = getOrCreateLoggerConfig(ctx.getConfiguration());
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.ALL, null);
        loggerConfig.setLevel(Level.ALL);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        var ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
    }

    /// The producer is the real default dispatch on a resource that implements no close
    /// convention — the #893 path — through the real `System.Logger`.
    @Test
    void systemLoggerDebugLine_fromResourceFactory_reachesLog4j() {
        new NothingToCloseFactory().close(new Object())
                                   .await()
                                   .onFailure(cause -> fail("close should succeed: " + cause.message()));

        var events = appender.eventsMentioning("No close convention");

        assertThat(events).as("System.Logger must be bridged into log4j on the node classpath (log4j-jpl)").hasSize(1);
        assertThat(events.getFirst().level()).isEqualTo(Level.DEBUG);
    }

    /// Control: the bridge is not the only path to log4j — a log4j logger of the same name is
    /// captured too, which proves the appender is wired before the bridge claim is read.
    @Test
    void log4jLoggerOfTheSameName_isCaptured() {
        LogManager.getLogger(LOGGER_NAME).debug("control line through log4j directly");

        assertThat(appender.eventsMentioning("control line")).hasSize(1);
    }

    private static LoggerConfig getOrCreateLoggerConfig(Configuration configuration) {
        var existing = configuration.getLoggerConfig(LOGGER_NAME);

        if (LOGGER_NAME.equals(existing.getName())) {
            return existing;
        }

        var fresh = new LoggerConfig(LOGGER_NAME, Level.ALL, false);

        configuration.addLogger(LOGGER_NAME, fresh);

        return fresh;
    }

    /// Provisions nothing; exists to reach the interface's default `close` with a plain object.
    private static final class NothingToCloseFactory implements ResourceFactory<Object, Object> {
        @Override
        public Class<Object> resourceType() {
            return Object.class;
        }

        @Override
        public Class<Object> configType() {
            return Object.class;
        }

        @Override
        public Promise<Object> provision(Object config) {
            return Promise.success(new Object());
        }
    }

    record Captured(Level level, String message) {}

    private static final class CapturingAppender extends AbstractAppender {
        private final List<Captured> events = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Layout<?> layout) {
            super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            return new CapturingAppender(name, PatternLayout.createDefaultLayout());
        }

        @Override
        public void append(LogEvent event) {
            events.add(new Captured(event.getLevel(), event.getMessage().getFormattedMessage()));
        }

        List<Captured> eventsMentioning(String fragment) {
            return events.stream().filter(captured -> captured.message().contains(fragment)).toList();
        }
    }
}
