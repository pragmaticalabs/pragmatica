// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.storage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.DemotionManager;
import org.pragmatica.storage.StorageGarbageCollector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.storage.DelegatedStorageAdapter.delegatedStorageAdapter;
import static org.pragmatica.lang.Unit.unit;

/// #250 review — `activate()`/`deactivate()` on the delegated managers are real operations that can
/// fail since #250 (they were no-ops before). Pins that a failing `Result` from either manager is
/// observed (logged at WARN with the cause) rather than silently discarded.
///
/// Log-capture strategy follows `ClusterTopologyManagerCasLossLoggingTest`: attach a log4j2
/// programmatic appender to the adapter's own logger and assert on captured WARN messages.
class DelegatedStorageAdapterTest {
    private static final String LOGGER_NAME = DelegatedStorageAdapter.class.getName();

    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        appender = CapturingAppender.create("DelegatedStorageAdapterCapture");
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);
        var configuration = ctx.getConfiguration();
        loggerConfig = getOrCreateLoggerConfig(configuration);
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.WARN, null);
        loggerConfig.setLevel(Level.WARN);
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

    @Test
    void activate_demotionManagerFails_logsWarningWithCause() {
        var adapter = delegatedStorageAdapter(failingDemotionManager("demotion boom"), succeedingGarbageCollector());

        adapter.activate().await();

        assertThat(appender.capturedWarns())
            .as("a failing demotion-manager activate() must be logged, not discarded")
            .anyMatch(msg -> msg.contains("demotion boom"));
    }

    @Test
    void activate_demotionManagerFails_adapterReportsInactive() {
        var adapter = delegatedStorageAdapter(failingDemotionManager("demotion boom"), succeedingGarbageCollector());

        adapter.activate().await();

        assertThat(adapter.isActive())
            .as("a failed manager activation must leave the adapter reporting inactive, not flip active=true regardless")
            .isFalse();
    }

    @Test
    void deactivate_garbageCollectorFails_logsWarningWithCause() {
        var adapter = delegatedStorageAdapter(succeedingDemotionManager(), failingGarbageCollector("gc boom"));

        adapter.activate().await();
        appender.clear();
        adapter.deactivate().await();

        assertThat(appender.capturedWarns())
            .as("a failing garbage-collector deactivate() must be logged, not discarded")
            .anyMatch(msg -> msg.contains("gc boom"));
    }

    // --- Fakes ---

    private static DemotionManager succeedingDemotionManager() {
        return demotionManagerReturning(Result.success(unit()), Result.success(unit()));
    }

    private static DemotionManager failingDemotionManager(String failureMessage) {
        return demotionManagerReturning(Causes.cause(failureMessage).result(), Result.success(unit()));
    }

    private static DemotionManager demotionManagerReturning(Result<Unit> onActivate, Result<Unit> onDeactivate) {
        return new DemotionManager() {
            @Override
            public int demote() {
                return 0;
            }

            @Override
            public DemotionStats stats() {
                return new DemotionStats(0, 0, 0);
            }

            @Override
            public Result<Unit> activate() {
                return onActivate;
            }

            @Override
            public Result<Unit> deactivate() {
                return onDeactivate;
            }

            @Override
            public boolean isActive() {
                return false;
            }
        };
    }

    private static StorageGarbageCollector succeedingGarbageCollector() {
        return garbageCollectorReturning(Result.success(unit()), Result.success(unit()));
    }

    private static StorageGarbageCollector failingGarbageCollector(String failureMessage) {
        return garbageCollectorReturning(Result.success(unit()), Causes.cause(failureMessage).result());
    }

    private static StorageGarbageCollector garbageCollectorReturning(Result<Unit> onActivate, Result<Unit> onDeactivate) {
        return new StorageGarbageCollector() {
            @Override
            public int collectGarbage() {
                return 0;
            }

            @Override
            public GCStats stats() {
                return new GCStats(0, 0);
            }

            @Override
            public Result<Unit> activate() {
                return onActivate;
            }

            @Override
            public Result<Unit> deactivate() {
                return onDeactivate;
            }

            @Override
            public boolean isActive() {
                return false;
            }
        };
    }

    private static LoggerConfig getOrCreateLoggerConfig(Configuration configuration) {
        var existing = configuration.getLoggerConfig(LOGGER_NAME);
        if (LOGGER_NAME.equals(existing.getName())) {
            return existing;
        }
        var fresh = new LoggerConfig(LOGGER_NAME, Level.WARN, false);
        configuration.addLogger(LOGGER_NAME, fresh);
        return fresh;
    }

    /// In-memory log4j2 appender capturing WARN-and-above messages for assertions.
    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Layout<?> layout) {
            super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            var layout = PatternLayout.createDefaultLayout();
            return new CapturingAppender(name, layout);
        }

        @Override
        public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        }

        List<String> capturedWarns() {
            return messages.stream().collect(Collectors.toUnmodifiableList());
        }

        void clear() {
            messages.clear();
        }
    }
}
