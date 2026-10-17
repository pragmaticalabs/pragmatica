// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #1612: a node's stop runs every step even when an HTTP listener fails to stop. A listener stop can now
/// fail (a timed-out close or termination); before the fix, the `flatMap` chain would have skipped the slice
/// invoker, the storage drain and the cluster-node stop behind it.
class NodeStopSequenceTest {
    private static final String LOGGER_NAME = NodeStopSequence.class.getName();
    private static final Cause STOP_TIMED_OUT = Causes.cause("listener termination timed out");

    private final List<String> ran = new CopyOnWriteArrayList<>();
    private final List<LogEvent> logged = new CopyOnWriteArrayList<>();
    private LoggerContext context;
    private CapturingAppender appender;

    @BeforeEach
    void captureLog() {
        context = (LoggerContext) LogManager.getContext(false);
        appender = new CapturingAppender(logged);
        appender.start();

        var loggerConfig = new LoggerConfig(LOGGER_NAME, Level.TRACE, false);

        loggerConfig.addAppender(appender, Level.TRACE, null);
        context.getConfiguration().addLogger(LOGGER_NAME, loggerConfig);
        context.updateLoggers();
    }

    @AfterEach
    void releaseLog() {
        context.getConfiguration().removeLogger(LOGGER_NAME);
        context.updateLoggers();
        appender.stop();
    }

    @Test
    void run_managementServerStopFails_runsEveryLaterStep_andLogsTheFailure() {
        var outcome = NodeStopSequence.run(steps(STOP_TIMED_OUT.promise(), Promise.unitPromise()))
                                      .await();

        assertThat(outcome.isSuccess()).as("the node stop completes").isTrue();
        assertThat(ran).containsExactly("deactivate", "management", "appHttp", "sliceInvoker", "sharedResources", "storage", "clusterNode");
        assertThat(logged).anyMatch(event -> event.getLevel() == Level.WARN
                                             && event.getMessage()
                                                     .getFormattedMessage()
                                                     .equals("Management server did not stop cleanly; continuing node shutdown: "
                                                             + STOP_TIMED_OUT.message()));
    }

    @Test
    void run_appHttpServerStopFails_runsEveryLaterStep_andLogsTheFailure() {
        var outcome = NodeStopSequence.run(steps(Promise.unitPromise(), STOP_TIMED_OUT.promise()))
                                      .await();

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(ran).containsExactly("deactivate", "management", "appHttp", "sliceInvoker", "sharedResources", "storage", "clusterNode");
        assertThat(logged).anyMatch(event -> event.getMessage()
                                                  .getFormattedMessage()
                                                  .startsWith("App HTTP server did not stop cleanly"));
    }

    /// A failure after the listeners (here the cluster node) is still the node stop's outcome: only listener
    /// failures are recovered past.
    @Test
    void run_clusterNodeStopFails_reportsTheFailure() {
        var steps = NodeStopSequence.Steps.steps(record("deactivate", Promise.unitPromise()),
                                                 record("management", Promise.unitPromise()),
                                                 record("appHttp", Promise.unitPromise()),
                                                 record("sliceInvoker", Promise.unitPromise()),
                                                 record("sharedResources", Promise.unitPromise()),
                                                 storage(),
                                                 record("clusterNode", STOP_TIMED_OUT.promise()));

        assertThat(NodeStopSequence.run(steps).await().isFailure()).isTrue();
    }

    /// #903: the shared resource scope closes after the slice invoker (every consumer is gone) and before
    /// storage and the cluster node, and its failure does not skip them.
    @Test
    void run_sharedResourceCloseFails_runsEveryLaterStep_andLogsTheFailure() {
        var steps = NodeStopSequence.Steps.steps(record("deactivate", Promise.unitPromise()),
                                                 record("management", Promise.unitPromise()),
                                                 record("appHttp", Promise.unitPromise()),
                                                 record("sliceInvoker", Promise.unitPromise()),
                                                 record("sharedResources", STOP_TIMED_OUT.promise()),
                                                 storage(),
                                                 record("clusterNode", Promise.unitPromise()));

        assertThat(NodeStopSequence.run(steps).await().isSuccess()).isTrue();
        assertThat(ran).containsExactly("deactivate", "management", "appHttp", "sliceInvoker", "sharedResources", "storage", "clusterNode");
        assertThat(logged).anyMatch(event -> event.getMessage()
                                                  .getFormattedMessage()
                                                  .startsWith("Shared resource scope did not stop cleanly"));
    }

    private NodeStopSequence.Steps steps(Promise<Unit> managementStop, Promise<Unit> appHttpStop) {
        return NodeStopSequence.Steps.steps(record("deactivate", Promise.unitPromise()),
                                            record("management", managementStop),
                                            record("appHttp", appHttpStop),
                                            record("sliceInvoker", Promise.unitPromise()),
                                            record("sharedResources", Promise.unitPromise()),
                                            storage(),
                                            record("clusterNode", Promise.unitPromise()));
    }

    private Supplier<Promise<Unit>> record(String step, Promise<Unit> outcome) {
        return () -> {
            ran.add(step);
            return outcome;
        };
    }

    private Supplier<Unit> storage() {
        return () -> {
            ran.add("storage");
            return Unit.unit();
        };
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events;

        private CapturingAppender(List<LogEvent> events) {
            super("NodeStopSequenceCapture", null, null, true, Property.EMPTY_ARRAY);
            this.events = events;
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
