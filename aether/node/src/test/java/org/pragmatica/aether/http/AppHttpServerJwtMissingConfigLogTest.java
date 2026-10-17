// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

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
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.JwtConfig;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// #888 / #902 review ruling: a node with `security_mode = "jwt"` and no `[app-http] jwks_url` is
/// MISCONFIGURED, not degraded — it will answer `401` on every non-public route — and the operator
/// must see that at the top of the log. This pins the level (ERROR, ruled over WARN), the missing
/// key, and the consequence, through the real constructor path (`AppHttpServer.appHttpServer`), where
/// `buildSecurityValidator` runs. The server is never started: the validator is built at
/// construction, so no port is bound.
///
/// The appender captures EVERY level on `AppHttpServerAdapter`'s own logger, so a demotion to WARN
/// fails the level assertion by name rather than by silent absence. Message fragments are literal
/// here, not read back from production, so a reworded message that drops the key or the consequence
/// is a change to this file.
///
/// Controls: with `jwks_url` set nothing is logged (the fallback is never built — `Option.or` is
/// lazy), and with the server disabled nothing is logged either: a server that never starts refuses
/// nothing, so the ERROR would be about a consequence that cannot occur (#902 review, section 4).
class AppHttpServerJwtMissingConfigLogTest {
    private static final String LOGGER_NAME = AppHttpServerAdapter.class.getName();
    private static final NodeId SELF_NODE = NodeId.nodeId("test-node-jwt-log").unwrap();
    private static final String UNREACHABLE_JWKS = "http://127.0.0.1:1/.well-known/jwks.json";
    private static final int PORT = 19095;

    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        appender = CapturingAppender.create("AppHttpServerJwtMissingConfigCapture");
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

    @Test
    void jwtModeWithoutJwksUrl_logsErrorNamingTheKeyAndTheConsequence_atConstruction() {
        construct(appHttp(true, SecurityMode.JWT, Option.empty()));

        var events = appender.eventsMentioning("jwks_url");

        assertThat(events).as("exactly one startup message about the missing jwks_url").hasSize(1);

        var event = events.getFirst();

        assertThat(event.level()).as("ruled ERROR, not WARN: the node is misconfigured, not degraded").isEqualTo(Level.ERROR);
        assertThat(event.message()).contains("security_mode = \"jwt\"")
                                   .contains("[app-http] jwks_url is missing")
                                   .contains("every non-public app route")
                                   .contains("REFUSED with 401")
                                   .contains("restart")
                                   .contains("#888");
    }

    @Test
    void jwtModeWithJwksUrl_logsNothingAboutMissingConfig() {
        construct(appHttp(true, SecurityMode.JWT, Option.some(JwtConfig.jwtConfig(UNREACHABLE_JWKS).unwrap())));

        assertThat(appender.eventsMentioning("jwks_url")).as("with jwks_url set the fallback is never built").isEmpty();
    }

    @Test
    void jwtModeWithoutJwksUrl_onDisabledServer_logsNothing() {
        construct(appHttp(false, SecurityMode.JWT, Option.empty()));

        assertThat(appender.eventsMentioning("jwks_url")).as("a server that never starts refuses nothing").isEmpty();
    }

    @Test
    void apiKeyAndNoneModes_logNothingAboutJwt() {
        construct(appHttp(true, SecurityMode.API_KEY, Option.empty()));
        construct(appHttp(true, SecurityMode.NONE, Option.empty()));

        assertThat(appender.eventsMentioning("jwks_url")).isEmpty();
    }

    private static void construct(AppHttpConfig config) {
        AppHttpServer.appHttpServer(config,
                                    ForwardingTimeouts.forwardingTimeouts(),
                                    SELF_NODE,
                                    HttpRouteRegistry.httpRouteRegistry(),
                                    Option.none(),
                                    Option.none(),
                                    Option.none(),
                                    Option.none(),
                                    Option.none(),
                                    Option.none(),
                                    Option.none(),
                                    Option.none(),
                                    Option.none());
    }

    private static AppHttpConfig appHttp(boolean enabled, SecurityMode mode, Option<JwtConfig> jwtConfig) {
        return AppHttpConfig.appHttpConfig(enabled,
                                           PORT,
                                           Map.of(),
                                           AppHttpConfig.DEFAULT_MAX_REQUEST_SIZE,
                                           mode,
                                           jwtConfig,
                                           HttpProtocol.H1)
                            .unwrap();
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

    record Captured(Level level, String message) {}

    /// In-memory log4j2 appender capturing every event with its level, so a level change is
    /// observable rather than filtered away.
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
