// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// v1617 on #1725 — `[storage.streams]` only carries `wal_path`; other keys there have no effect. They were dropped
/// silently, so an operator setting `disk_path` there believed it applied. The loader now WARNs, naming them.
/// Mutation that reddens it: drop the `warnIgnoredStreamsKeys` call.
class ConfigLoaderStreamsSectionTest {
    private static final String LOGGER = ConfigLoader.class.getName();

    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final AbstractAppender appender = new AbstractAppender("streams-section", (Filter) null, null, true, Property.EMPTY_ARRAY) {
        @Override
        public void append(LogEvent event) {
            if (event.getLevel() == Level.WARN) {
                warnings.add(event.getMessage().getFormattedMessage());
            }
        }
    };
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);
        var configuration = ctx.getConfiguration();
        var existing = configuration.getLoggerConfig(LOGGER);
        if (!LOGGER.equals(existing.getName())) {
            existing = new LoggerConfig(LOGGER, Level.WARN, false);
            configuration.addLogger(LOGGER, existing);
        }
        loggerConfig = existing;
        originalLevel = existing.getLevel();
        loggerConfig.addAppender(appender, Level.WARN, null);
        loggerConfig.setLevel(Level.WARN);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(originalLevel);
        ((LoggerContext) LogManager.getContext(false)).updateLoggers();
        appender.stop();
    }

    @Test
    void streamsSectionWithOtherKeys_warnsNamingTheIgnoredKeys() {
        ConfigLoader.loadFromString("""
            [storage.streams]
            wal_path = "/tmp/wal"
            disk_path = "/tmp/streams"
            encrypted = true
            """);

        assertThat(warnings).as("the keys that have no effect are named")
                            .anyMatch(message -> message.contains("[disk_path, encrypted]"));
    }

    /// CONTROL — wal_path alone, and other storage sections, produce no such WARN.
    @Test
    void walPathAlone_andOtherSections_doNotWarn() {
        ConfigLoader.loadFromString("""
            [storage.streams]
            wal_path = "/tmp/wal"

            [storage.vault]
            disk_path = "/tmp/vault"
            """);

        assertThat(warnings).noneMatch(message -> message.contains("[storage.streams]"));
    }
}
