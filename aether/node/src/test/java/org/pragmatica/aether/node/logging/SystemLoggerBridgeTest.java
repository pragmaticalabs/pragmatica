// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.logging;

import java.util.List;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.lang.Promise;
import org.pragmatica.testlogging.LogCapture;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private LogCapture capture;

    @BeforeEach
    void setUp() {
        capture = LogCapture.capture(LOGGER_NAME, Level.ALL, false);
    }

    @AfterEach
    void tearDown() {
        capture.close();
    }

    /// The producer is the real default dispatch on a resource that implements no close
    /// convention — the #893 path — through the real `System.Logger`.
    @Test
    void systemLoggerDebugLine_fromResourceFactory_reachesLog4j() {
        new NothingToCloseFactory().close(new Object())
                                   .await()
                                   .onFailure(cause -> fail("close should succeed: " + cause.message()));
        var events = eventsMentioning("No close convention");

        assertThat(events).as("System.Logger must be bridged into log4j on the node classpath (log4j-jpl)").hasSize(1);
        assertThat(events.getFirst().level()).isEqualTo(Level.DEBUG);
    }

    /// Control: the bridge is not the only path to log4j — a log4j logger of the same name is
    /// captured too, which proves the appender is wired before the bridge claim is read.
    @Test
    void log4jLoggerOfTheSameName_isCaptured() {
        LogManager.getLogger(LOGGER_NAME).debug("control line through log4j directly");
        assertThat(eventsMentioning("control line")).hasSize(1);
    }

    private List<LogCapture.Entry> eventsMentioning(String fragment) {
        return capture.entries()
                      .stream()
                      .filter(entry -> entry.message()
                                            .contains(fragment))
                      .toList();
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
}
