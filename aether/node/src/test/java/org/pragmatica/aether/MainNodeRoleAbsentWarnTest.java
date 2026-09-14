// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #689, node side: a node started without `AETHER_ROLE` advertises no role label and is
/// classified CORE by every peer (`MemberDescriptor.isCoreRole`: blank counts as core). That
/// default is deliberate and unchanged; what was missing is the node saying so at boot. The pair
/// below shares one capture: absent role must WARN naming the default, present role must not.
class MainNodeRoleAbsentWarnTest {
    private static final String LOGGER_NAME = "org.pragmatica.aether.Main";
    private static final String MARKER = "AETHER_ROLE";

    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        appender = CapturingAppender.create("MainRoleAbsentCapture");
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig = getOrCreateLoggerConfig(ctx.getConfiguration());
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
    void absentRole_warnsAtBoot_namingTheCoreDefault() {
        var labels = Main.collectNodeLabels("host-1", _ -> Option.none());

        assertThat(labels).as("control: the label is genuinely absent").doesNotContainKey(NodeInfo.LABEL_ROLE);
        var warns = appender.capturedWarns().stream().filter(msg -> msg.contains(MARKER)).toList();
        assertThat(warns).as("#689: an absent role must be stated at boot, not discovered from a suppressed fence")
                         .hasSize(1);
        assertThat(warns.getFirst()).contains("no role label")
                                    .contains("CORE");
    }

    @Test
    void presentRole_doesNotWarn() {
        var env = Map.of("AETHER_ROLE", "worker");
        var labels = Main.collectNodeLabels("host-1", name -> Option.option(env.get(name)));

        assertThat(labels.get(NodeInfo.LABEL_ROLE)).isEqualTo("worker");
        assertThat(appender.capturedWarns()).noneMatch(msg -> msg.contains(MARKER));
    }

    private static LoggerConfig getOrCreateLoggerConfig(Configuration configuration) {
        var existing = configuration.getLoggerConfig(LOGGER_NAME);
        if (LOGGER_NAME.equals(existing.getName())) {return existing;}
        var fresh = new LoggerConfig(LOGGER_NAME, Level.WARN, false);
        configuration.addLogger(LOGGER_NAME, fresh);
        return fresh;
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Layout<?> layout) {
            super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            return new CapturingAppender(name, PatternLayout.createDefaultLayout());
        }

        @Override public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        }

        List<String> capturedWarns() {
            return List.copyOf(messages);
        }
    }
}
