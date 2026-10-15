// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.pragmatica.aether.node.AetherNode;

/// What a FSM2 cluster run is checked against (phase-3 design 9.5 O6, 12 item 6): the log lines the deployment FSM loggers wrote while it ran, and
/// the checked-in expected-WARN list of the scenario (`fsm2/expected-warns-*.txt`). Shared by T and E-01, so both read the same sensors the same way.
///
/// An entry is `warn` (a regex on a WARN-or-worse line, an occurrence range), `line` (an INFO sensor line naming a node) or `metric` (a
/// `submissions_total` outcome arm of a node). The log capture is additive: it keeps the level each logger inherits.
final class Fsm2Sensors implements AutoCloseable {
    private static final List<String> LOGGERS = List.of("org.pragmatica.aether.deployment.fsm.node",
                                                        "org.pragmatica.aether.deployment.fsm.cluster",
                                                        "org.pragmatica.utility.warning.OperatorWarnings",
                                                        "org.pragmatica.aether.node.deployment.EffectTranslator");

    /// One captured log line.
    record Logged(Level level, String message) {}

    /// One entry of an expected-WARN list.
    record Expectation(String kind, String name, int min, int max) {
        boolean admits(long count) {
            return count >= min && count <= max;
        }

        boolean matches(String line) {
            return Pattern.compile(name).matcher(line).find();
        }
    }

    private final List<Logged> logged = new CopyOnWriteArrayList<>();
    private final List<CapturingAppender> appenders = new ArrayList<>();

    private Fsm2Sensors() {}

    /// Starts capturing the FSM loggers; `close()` stops it.
    static Fsm2Sensors capture() {
        var sensors = new Fsm2Sensors();
        var ctx = (LoggerContext) LogManager.getContext(false);
        var configuration = ctx.getConfiguration();

        for (var name : LOGGERS) {
            var existing = configuration.getLoggerConfig(name);
            var config = name.equals(existing.getName())
                         ? existing
                         : new LoggerConfig(name, existing.getLevel(), true);

            if (!name.equals(existing.getName())) {
                configuration.addLogger(name, config);
            }
            var appender = new CapturingAppender(name, sensors.logged);

            appender.start();
            config.addAppender(appender, Level.INFO, null);
            sensors.appenders.add(appender);
        }
        ctx.updateLoggers();

        return sensors;
    }

    @Override
    public void close() {
        if (appenders.isEmpty()) {
            return;
        }

        var ctx = (LoggerContext) LogManager.getContext(false);

        for (var appender : appenders) {
            ctx.getConfiguration().getLoggerConfig(appender.logger()).removeAppender(appender.getName());
            appender.stop();
        }
        ctx.updateLoggers();
        appenders.clear();
    }

    /// Every WARN-or-worse line captured so far.
    List<String> warnLines() {
        return logged.stream()
                     .filter(line -> line.level().isMoreSpecificThan(Level.WARN))
                     .map(Logged::message)
                     .toList();
    }

    /// The captured lines that contain `name` and name `nodeId` (an INFO sensor line, e.g. `write-probe-resubmit`, of that node).
    List<String> sensorLines(String nodeId, String name) {
        return logged.stream()
                     .map(Logged::message)
                     .filter(message -> message.contains(name) && message.contains("node " + nodeId))
                     .toList();
    }

    /// Total of a `submissions_total` outcome arm of one node's FSMs.
    static double armCount(AetherNode node, String outcome) {
        return node.deploymentWiring()
                   .meters()
                   .find("aether_deployment_fsm_submissions_total")
                   .tags("outcome", outcome)
                   .counters()
                   .stream()
                   .mapToDouble(counter -> counter.count())
                   .sum();
    }

    /// The checked-in list at `resource`.
    static List<Expectation> expectations(String resource) {
        try (InputStream in = Fsm2Sensors.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new AssertionError("SETUP: the checked-in expected-WARN list " + resource + " is missing");
            }

            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                                                                         .filter(line -> !line.startsWith("#") && !line.isBlank())
                                                                         .map(Fsm2Sensors::expectation)
                                                                         .toList();
        } catch (IOException e) {
            throw new AssertionError("SETUP: cannot read " + resource, e);
        }
    }

    private static Expectation expectation(String line) {
        var match = Pattern.compile("^(warn|line|metric)\\s+(\\S+)\\s+(\\d+)\\.\\.(\\d+)").matcher(line);

        if (!match.find()) {
            throw new AssertionError("SETUP: unreadable expected-WARN entry: " + line);
        }

        return new Expectation(match.group(1), match.group(2), Integer.parseInt(match.group(3)), Integer.parseInt(match.group(4)));
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final String logger;
        private final List<Logged> sink;

        private CapturingAppender(String logger, List<Logged> sink) {
            super("Fsm2Sensors-" + logger, (Filter) null, null, true, Property.EMPTY_ARRAY);
            this.logger = logger;
            this.sink = sink;
        }

        String logger() {
            return logger;
        }

        @Override
        public void append(LogEvent event) {
            sink.add(new Logged(event.getLevel(), event.getMessage().getFormattedMessage()));
        }
    }
}
