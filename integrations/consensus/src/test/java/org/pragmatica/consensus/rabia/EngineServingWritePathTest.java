// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.consensus.rabia;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1516 — the announce of an unannounced restore rides on the engine's ONE write path for the serving states
/// (`enterServing` / `enterServingIf`), so a transition into `Idle`, `InPhase` or `Observing` cannot forget it. This
/// scans `RabiaEngine.java` (comments removed, whitespace stripped) and fails if a serving state is written any other
/// way, if the announce is called from anywhere but the two helpers, or if a call site of the helpers is added or
/// removed without this test being revisited. It is a source pin: it says the structure is present, not that a path ran;
/// the behaviour of the paths is pinned by `RabiaNoReplayWhileInactiveTest`.
class EngineServingWritePathTest {
    private static final Pattern STATE_WRITE = Pattern.compile("engineState\\.(set|getAndSet|compareAndSet|updateAndGet|accumulateAndGet)\\(([^;]*)");
    private static final Pattern SERVING_CONSTRUCTION = Pattern.compile("newEngineState\\.(Idle|InPhase|Observing)\\(");

    @Test
    void everyEngineStateWrite_isAHelperOrANonServingState() {
        var code = engineCode();
        var matcher = STATE_WRITE.matcher(code);
        var writes = 0;

        while (matcher.find()) {
            writes++;
            var argument = matcher.group(2);

            assertThat(argument).as("write %s%s", matcher.group(1), argument)
                                .matches(arg -> arg.startsWith("serving)")
                                                || arg.startsWith("expected,serving)")
                                                || arg.startsWith("newEngineState.Syncing(")
                                                || arg.startsWith("newEngineState.Paused(")
                                                || arg.startsWith("newEngineState.Stopped("));
        }

        assertThat(writes).as("the scan reached the writes (2 helper bodies, 2 Syncing, 1 Paused, 1 Stopped)").isEqualTo(6);
    }

    @Test
    void everyServingStateConstruction_isAnArgumentOfAHelperCall() {
        var code = engineCode();
        var matcher = SERVING_CONSTRUCTION.matcher(code);
        var constructions = 0;

        while (matcher.find()) {
            constructions++;
            var prefix = code.substring(0, matcher.start());
            var lastCall = Math.max(prefix.lastIndexOf("enterServing("), prefix.lastIndexOf("enterServingIf("));

            assertThat(lastCall).as("a helper call opens before the construction at %d", matcher.start()).isGreaterThanOrEqualTo(0);
            assertThat(prefix.substring(lastCall)).as("the construction is inside the helper call's own statement")
                                                  .doesNotContain(";")
                                                  .doesNotContain("{")
                                                  .doesNotContain("}");
        }

        assertThat(constructions).as("Idle and Observing in resume (2), plus 8 single-state sites").isEqualTo(10);
    }

    @Test
    void theAnnounceIsCalledOnlyAtTheTailOfAServingTransition_afterTheEventIsPublished() {
        var code = engineCode();

        assertThat(count(code, "enterServing(")).as("declaration plus seven call sites").isEqualTo(8);
        assertThat(count(code, "enterServingIf(")).as("declaration plus two call sites").isEqualTo(3);
        assertThat(between(code, "privateEngineStateenterServing(", "privatebooleanenterServingIf(")).as("the write path does not announce")
                                                                                                    .doesNotContain("announceRestoreIfPending");
        assertThat(between(code, "privatebooleanenterServingIf(", "privatevoidannounceRestoreIfPending(")).as("nor does the compare-and-set").doesNotContain("announceRestoreIfPending");

        var announces = 0;
        var from = code.indexOf("announceRestoreIfPending();");

        while (from >= 0) {
            announces++;
            var prefix = code.substring(0, from);
            var write = prefix.lastIndexOf("enterServing(");

            assertThat(write).as("a serving write precedes the announce at %d", from).isGreaterThanOrEqualTo(0);
            assertThat(prefix.substring(write)).as("the serving event is published before the announce at %d", from)
                                               .contains("notifyConsensusStateTransition();");
            from = code.indexOf("announceRestoreIfPending();", from + 1);
        }

        assertThat(announces).as("promotion, resume, activation, observer activation, added voter, observer advance").isEqualTo(6);
        assertThat(count(code, "announceRestoreIfPending()")).as("declaration plus the six tail calls").isEqualTo(7);
        var activation = "varoldState=enterServing(newEngineState.Idle());exitState(oldState);armCleanupTask();";

        assertThat(between(code, activation, "announceRestoreIfPending();")).as("on activation the start promise is resolved before the announce")
                                                                            .contains("startPromise.get().succeed(Unit.unit());");
    }

    private static String between(String code, String from, String to) {
        var start = code.indexOf(from);
        var end = code.indexOf(to, start);

        assertThat(start).as("%s present", from).isGreaterThanOrEqualTo(0);
        assertThat(end).as("%s follows", to).isGreaterThan(start);

        return code.substring(start, end);
    }

    private static int count(String text, String needle) {
        var count = 0;
        var from = text.indexOf(needle);

        while (from >= 0) {
            count++;
            from = text.indexOf(needle, from + needle.length());
        }

        return count;
    }

    /// `RabiaEngine.java` with line comments removed and all whitespace stripped. An unreadable file fails loudly,
    /// because a pin that scans nothing always passes.
    private static String engineCode() {
        var file = sourceRoot().resolve("org/pragmatica/consensus/rabia/RabiaEngine.java");

        assertThat(file).exists();

        return readFile(file).lines()
                             .map(line -> line.replaceFirst("//.*$", ""))
                             .collect(Collectors.joining())
                             .replaceAll("\\s+", "");
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("Cannot read " + path, e);
        }
    }

    private static Path sourceRoot() {
        try {
            var testClasses = Path.of(EngineServingWritePathTest.class.getProtectionDomain()
                                                                      .getCodeSource()
                                                                      .getLocation()
                                                                      .toURI());

            return testClasses.getParent()
                              .getParent()
                              .resolve("src/main/java");
        } catch (URISyntaxException e) {
            throw new AssertionError("Cannot locate module source root", e);
        }
    }
}
