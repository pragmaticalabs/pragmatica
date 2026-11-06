// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// Delta 7 (cluster assessment; CTO ruling 9f854db8b): the deployment engine reads boot tokens and membership incarnations through ONE
/// seam, so swapping the identity authority is a change in `AdmissionSeam` only. A source scan: no main source of the wiring package names
/// the boot-token registry or membership's incarnations outside `AdmissionSeam`, and `AetherNode` reads each exactly once, where it builds
/// the seam. Comment lines are not code and are skipped. A planted reader is the positive control.
class AdmissionSeamReadersTest {
    private static final Path MAIN = Path.of("src", "main", "java", "org", "pragmatica", "aether", "node");
    private static final Path WIRING = MAIN.resolve("deployment");
    private static final Path NODE = MAIN.resolve("AetherNode.java");
    /// The registry (its type or a variable holding it) and membership's incarnations: what only the seam may read.
    private static final List<String> RAW_READS = List.of("BootTokens", "bootTokens", "memberIncarnations(");

    private static List<String> codeLines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8)
                    .stream()
                    .map(String::trim)
                    .filter(line -> !line.startsWith("//"))
                    .toList();
    }

    /// The files of `dir` other than the seam that read the registry or membership's incarnations directly.
    private static List<String> rawReaders(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> file.toString().endsWith(".java"))
                        .filter(file -> !file.getFileName().toString().equals("AdmissionSeam.java"))
                        .filter(AdmissionSeamReadersTest::readsRaw)
                        .map(file -> file.getFileName().toString())
                        .toList();
        }
    }

    private static boolean readsRaw(Path file) {
        try {
            return codeLines(file).stream()
                                  .anyMatch(line -> RAW_READS.stream()
                                                             .anyMatch(line::contains));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long count(Path file, String text) throws IOException {
        return codeLines(file).stream()
                              .filter(line -> line.contains(text))
                              .count();
    }

    @Test
    void theWiringPackage_readsTokensAndIncarnations_onlyThroughTheSeam() throws IOException {
        try (Stream<Path> files = Files.list(WIRING)) {
            assertThat(files.map(file -> file.getFileName().toString())).as("the scan covers the former raw readers")
                                                                         .contains("DeploymentWiring.java", "Fsm2Routes.java", "Fsm2System.java", "SeamPoller.java");
        }
        assertThat(rawReaders(WIRING)).isEmpty();
    }

    @Test
    void aetherNode_readsTheRegistryAndMembershipIncarnations_onceEach_toBuildTheSeam() throws IOException {
        assertThat(count(NODE, "bootTokens::tokenOf")).as("the seam's token source").isEqualTo(1);
        assertThat(count(NODE, "memberIncarnations(")).as("the seam's incarnation source").isEqualTo(1);
        assertThat(count(NODE, "AdmissionSeam.boundLate(")).as("one seam").isEqualTo(1);
        assertThat(count(NODE, "AdmissionSeam.admissionSeam(")).as("no second seam").isZero();
    }

    @Test
    void aPlantedRawReader_isFlagged(@TempDir Path temp) throws IOException {
        Files.writeString(temp.resolve("Planted.java"), "class Planted {\n    long t = bootTokens.tokenOf(node);\n}\n");
        Files.writeString(temp.resolve("Fine.java"), "class Fine {\n    // bootTokens.tokenOf(node) in a comment\n    long t = seam.admissions().tokenOf(node);\n}\n");

        assertThat(rawReaders(temp)).containsExactly("Planted.java");
    }

    /// The late binding: before the incarnation source is bound a read gives `0` and is counted; after, it reads the source and is not.
    @Test
    void aReadBeforeTheSourceIsBound_isCounted_andReadsNoEvidence() {
        var node = NodeId.nodeId("node-a").unwrap();
        var source = new AtomicReference<Option<ToLongFunction<NodeId>>>(Option.none());
        var seam = AdmissionSeam.boundLate(_ -> 7L, source::get);

        assertThat(seam.admission(node).process().bootIncarnation()).isZero();
        assertThat(seam.unboundReads()).isEqualTo(1);
        source.set(Option.some(id -> Map.of(node, 42L).getOrDefault(id, 0L)));
        assertThat(seam.admission(node).process().bootIncarnation()).isEqualTo(42L);
        assertThat(seam.admission(node).process().bootToken()).isEqualTo(7L);
        assertThat(seam.unboundReads()).as("bound reads are not counted").isEqualTo(1);
    }
}
