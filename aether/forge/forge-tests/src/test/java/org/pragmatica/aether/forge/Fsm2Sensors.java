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
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.testlogging.LogCapture;

/// What a FSM2 cluster run is checked against (phase-3 design 9.5 O6, 12 item 6): the log lines the deployment FSM loggers wrote while it ran, and
/// the checked-in expected-WARN list of the scenario (`fsm2/expected-warns-*.txt`). Shared by T and E-01, so both read the same sensors the same way.
///
/// An entry is `warn` (a regex on a WARN-or-worse line, an occurrence range), `line` (an INFO sensor line naming a node) or `metric` (a
/// `submissions_total` outcome arm of a node). The log capture is additive; each logger runs at its inherited level with an INFO floor (see `capture()`).
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
    private final List<LogCapture> captures = new ArrayList<>();

    private Fsm2Sensors() {}

    /// Starts capturing the FSM loggers; `close()` stops it. Each logger's config is installed at its inherited level with an INFO floor: one
    /// that inherits INFO or coarser (all four do under forge-core's log4j2.xml) logs exactly as before, one configured finer would be raised to
    /// INFO while captured. Its events go on to the console, and all of them land in one list in arrival order.
    static Fsm2Sensors capture() {
        var sensors = new Fsm2Sensors();

        for (var name : LOGGERS) {
            sensors.captures.add(LogCapture.capture(name,
                                                    thresholdOf(name),
                                                    true,
                                                    event -> sensors.logged.add(new Logged(event.getLevel(),
                                                                                           event.getMessage().getFormattedMessage()))));
        }

        return sensors;
    }

    private static Level thresholdOf(String loggerName) {
        var inherited = LogManager.getLogger(loggerName).getLevel();

        return inherited.isMoreSpecificThan(Level.INFO)
               ? inherited
               : Level.INFO;
    }

    @Override
    public void close() {
        captures.forEach(LogCapture::close);
        captures.clear();
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

    /// Total of a `submissions_total` outcome arm of one FSM (`cluster` or `node`) of one node.
    static double armCount(AetherNode node, String fsm, String outcome) {
        return node.deploymentWiring()
                   .meters()
                   .find("aether_deployment_fsm_submissions_total")
                   .tags("fsm", fsm, "outcome", outcome)
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
}
