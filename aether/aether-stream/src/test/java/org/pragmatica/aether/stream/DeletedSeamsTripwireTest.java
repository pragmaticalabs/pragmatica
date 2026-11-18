// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// T-02 (#3333, S0): the four seams deleted by S0-1 stay gone. No tracked file names them, in source or in docs.
///
/// Scanned: the output of `git ls-files` run at the repository root (C-15), never a directory walk: a stale `target/`
/// class or a `.m2-local` copy would match a walk. Comments are NOT stripped: a doc line or a code comment that names a
/// deleted seam is exactly what this test exists to catch.
///
/// Excluded (R-2): `CHANGELOG.md`, `changelog.d/`, `aether/docs/.internal/progress/`, `aether/docs/.internal/audits/`
/// (records of what was true then), and this test with its fixture directory (they must name the seams).
///
/// Positive control: the same scanner over the fixture directory (one file per name) must find exactly four hits, so an
/// empty or misplaced scan cannot pass the main test vacuously.
class DeletedSeamsTripwireTest {
    private static final Pattern SEAMS = Pattern.compile("WatermarkStore|ReplicaAssignmentStore|PgSegmentSink|PgCursorStore");

    private static final List<String> EXCLUDED_PREFIXES = List.of("CHANGELOG.md",
                                                                  "changelog.d/",
                                                                  "aether/docs/.internal/progress/",
                                                                  "aether/docs/.internal/audits/",
                                                                  "aether/aether-stream/src/test/java/org/pragmatica/aether/stream/DeletedSeamsTripwireTest.java",
                                                                  "aether/aether-stream/src/test/resources/deleted-seams-fixture/");

    private static final String FIXTURE = "aether/aether-stream/src/test/resources/deleted-seams-fixture";

    @Test
    void deletedSeamsHaveNoReference() throws IOException {
        var root = repositoryRoot();
        var hits = scan(root, trackedFiles(root).filter(DeletedSeamsTripwireTest::isScanned));

        assertThat(hits).as("tracked files naming a deleted seam (WatermarkStore, ReplicaAssignmentStore, PgSegmentSink, "
                            + "PgCursorStore). Remove the reference; do not add the file to the exclusions.")
                        .isEmpty();
    }

    @Test
    void scanner_findsExactlyFourHits_inTheFixtureDirectory() throws IOException {
        var root = repositoryRoot();
        var hits = scan(root, trackedFiles(root).filter(path -> path.startsWith(FIXTURE)));

        assertThat(hits).as("positive control: one fixture file per deleted name")
                        .hasSize(4);
    }

    @Test
    void exclusions_coverOnlyTheRecordedPaths() {
        assertThat(isScanned("aether/aether-stream/src/main/java/org/pragmatica/aether/stream/StreamPartitionManager.java")).isTrue();
        assertThat(isScanned("aether/docs/reference/feature-catalog.md")).isTrue();
        assertThat(isScanned("aether/docs/.internal/control-plane-delegation-investigation.md")).isTrue();
        assertThat(isScanned("CHANGELOG.md")).isFalse();
        assertThat(isScanned("changelog.d/0-3333-s0-1.md")).isFalse();
        assertThat(isScanned("aether/docs/.internal/audits/x.md")).isFalse();
    }

    private static boolean isScanned(String path) {
        return EXCLUDED_PREFIXES.stream()
                                .noneMatch(path::startsWith);
    }

    private static List<String> scan(Path root, Stream<String> files) {
        var hits = new ArrayList<String>();
        files.forEach(file -> collectHits(root, file, hits));
        return hits;
    }

    private static void collectHits(Path root, String file, List<String> hits) {
        var path = root.resolve(file);
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            var lines = Files.readAllLines(path, StandardCharsets.ISO_8859_1);
            for (int i = 0; i < lines.size(); i++) {
                if (SEAMS.matcher(lines.get(i)).find()) {
                    hits.add(file + ":" + (i + 1));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    private static Stream<String> trackedFiles(Path root) throws IOException {
        var process = new ProcessBuilder("git", "-C", root.toString(), "ls-files", "-z").redirectErrorStream(false)
                                                                                      .start();
        try (var in = process.getInputStream()) {
            var output = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertThat(waitFor(process)).as("git ls-files exit code")
                                        .isZero();
            return Stream.of(output.split("\0"))
                         .filter(name -> !name.isEmpty());
        }
    }

    private static int waitFor(Process process) {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
            throw new IllegalStateException("interrupted waiting for git", e);
        }
    }

    private static Path repositoryRoot() {
        var dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(".git"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root (a directory holding .git) above the working directory")
                       .isNotNull();
        return dir;
    }
}
