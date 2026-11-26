// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #3799: the fence behind `ReplacementSignal`. A replacement's `DONE` is waited for where the next `begin` reads it, the leader's index,
/// and the Ember test tree has exactly one reader of a node's replacement record (`ReplacementSignal`). This test fails when any other
/// Ember test file (1) reads `nodeReplacementService()` for anything but `begin`/`beginExternal`/an `instanceof` wiring check, which is
/// how a per-node `status` read is written, or (2) mentions `NodeReplacementPhase.DONE` inside a lambda, which is how a wait on it is
/// written. Rule 1 is the structural fence; rule 2 is a heuristic net over the same shape and says so. The fence reads source text, so it is
/// proven against snippets, and against the real tree having files to read.
class ReplacementSignalFenceTest {
    private static final Path EMBER_TESTS = Path.of("src/test/java/org/pragmatica/aether/ember");
    private static final Pattern SERVICE_READ = Pattern.compile("nodeReplacementService\\(\\)(?!\\s*\\.begin(External)?\\()(?!\\s+instanceof\\b)");
    private static final Pattern DONE_IN_LAMBDA = Pattern.compile("->[^;{}]*NodeReplacementPhase\\.DONE");
    private static final List<String> EXEMPT = List.of("ReplacementSignal.java", "ReplacementSignalFenceTest.java");

    @Test
    void noEmberTestReadsAReplacementRecordOutsideReplacementSignal() throws IOException {
        var files = emberTestSources();

        assertThat(files).as("the fence read the real tree: a scan of nothing proves nothing")
                         .extracting(path -> path.getFileName().toString())
                         .contains("ReplacementSignal.java", "EmberWorkerVoterRosterTest.java", "EmberNodeReplacementTest.java", "M1MeasureTest.java");
        assertThat(files.stream()
                        .filter(path -> !EXEMPT.contains(path.getFileName().toString()))
                        .flatMap(path -> violations(path.getFileName().toString(), read(path)).stream())
                        .toList())
            .as("read a replacement record only through ReplacementSignal: settledOnLeader to wait for DONE before a begin, newestRecordOnAnyNode for a "
                + "terminal-state observer that cannot read the leader")
            .isEmpty();
    }

    @Test
    void fence_flagsAPerNodeStatusRead() {
        var anyNodeDone = """
            private boolean replacementDone(NodeId victim) {
                return cluster.allNodes().stream().map(node -> node.nodeReplacementService().status(victim).or((NodeReplacementValue) null)).anyMatch(r -> r != null);
            }
            """;

        assertThat(violations("Probe.java", anyNodeDone)).hasSize(1).first().asString().contains("nodeReplacementService()");
    }

    @Test
    void fence_flagsAStatusReadSplitAcrossLines_andAServiceHeldInAVariable() {
        assertThat(violations("Probe.java", "node.nodeReplacementService()\n    .status(victim)")).hasSize(1);
        assertThat(violations("Probe.java", "var service = node.nodeReplacementService();")).hasSize(1);
    }

    @Test
    void fence_flagsADoneWaitInALambda() {
        var wait = "awaitCondition(\"done\", 1L, () -> recordOf(victim).phase() == NodeReplacementPhase.DONE);";

        assertThat(violations("Probe.java", wait)).hasSize(1).first().asString().contains("NodeReplacementPhase.DONE");
    }

    @Test
    void fence_passesTheShapesTheTreeLegitimatelyUses() {
        var legitimate = """
            leader.nodeReplacementService().begin(victim, "").await(START_BOUND);
            leader.nodeReplacementService()
                  .beginExternal(victim, chosen, "");
            if (!(leader.nodeReplacementService() instanceof Wired wired)) { return; }
            assertThat(recordOf(victim).phase()).isEqualTo(NodeReplacementPhase.DONE);
            awaitCondition("settled", 1L, () -> ReplacementSignal.settledOnLeader(cluster.allNodes(), victim));
            """;

        assertThat(violations("Probe.java", legitimate)).isEmpty();
    }

    private static List<String> violations(String file, String source) {
        return Stream.concat(SERVICE_READ.matcher(source).results().map(hit -> file + ": a replacement record read outside ReplacementSignal: " + hit.group()),
                             DONE_IN_LAMBDA.matcher(source).results().map(hit -> file + ": a DONE wait outside ReplacementSignal: " + hit.group().strip()))
                     .toList();
    }

    private static List<Path> emberTestSources() throws IOException {
        try (var walk = Files.walk(EMBER_TESTS)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
