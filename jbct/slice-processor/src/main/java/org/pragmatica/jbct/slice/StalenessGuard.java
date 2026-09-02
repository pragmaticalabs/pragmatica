// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// Staleness guard for a locally-installed slice-processor jar (#403).
///
/// A stale processor jar in `~/.m2` silently reintroduces an already-fixed codegen bug: consumers
/// reference the processor through `annotationProcessorPaths`, so `-am` never rebuilds it and it can
/// drift arbitrarily far behind the source tree (green -> red with no source change, nothing pointing
/// at the cause). This guard turns that silent drift into a loud, actionable warning.
///
/// Signal: the processor's own embedded [BuildInfo#BUILD_TIMESTAMP] versus the newest modification
/// time of the `jbct/slice-processor/src/main` tree (plus the module `pom.xml`), located by walking
/// up from the build's working directory. When the source outpaces the installed jar, [#staleWarning]
/// yields the remedy message; the processor emits it as a compile warning.
///
/// Degrades to silence in every uncertain case - unresolvable build stamp, source tree not present
/// (any consumer built outside this monorepo), or an unreadable filesystem - so it never fails a
/// build and never warns an external consumer who has no `jbct/` source tree to rebuild.
final class StalenessGuard {
    /// Format of the embedded build stamp; mirrors `maven.build.timestamp.format` in the module pom
    /// (UTC, literal `Z`). Parsed as a local date-time and pinned to UTC to recover the [Instant].
    private static final DateTimeFormatter STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    /// Relative marker proving an ancestor directory is this monorepo's root; also the file whose
    /// staleness the guard exists to catch.
    private static final Path MARKER = Path.of("jbct",
                                               "slice-processor",
                                               "src",
                                               "main",
                                               "java",
                                               "org",
                                               "pragmatica",
                                               "jbct",
                                               "slice",
                                               "SliceProcessor.java");

    /// Source subtree whose newest mtime represents "the codegen source" for the comparison.
    private static final Path SCAN_ROOT = Path.of("jbct", "slice-processor", "src", "main");
    /// Module descriptor - a version/dependency bump here also warrants a rebuild.
    private static final Path MODULE_POM = Path.of("jbct", "slice-processor", "pom.xml");
    /// Bound on the walk from the working directory toward the filesystem root.
    private static final int MAX_ASCENT = 16;

    private StalenessGuard() {}

    /// Warning to emit when the installed processor predates its source tree, or none when fresh /
    /// indeterminate. `workingDir` is the build's working directory (`user.dir`); `version` and
    /// `buildTimestamp` are the running processor's embedded [BuildInfo] stamp.
    static Option<String> staleWarning(String workingDir, String version, String buildTimestamp) {
        return parseBuildInstant(buildTimestamp).flatMap(buildInstant -> outpacingSource(workingDir, buildInstant))
                                .map(newest -> warningMessage(version, buildTimestamp, newest));
    }

    /// The newest source entry, but only when it is newer than the installed jar - otherwise none.
    private static Option<NewestSource> outpacingSource(String workingDir, Instant buildInstant) {
        return locateRepoRoot(workingDir).flatMap(StalenessGuard::newestSource)
                             .filter(newest -> newest.instant()
                                                     .isAfter(buildInstant));
    }

    private static Option<Instant> parseBuildInstant(String buildTimestamp) {
        return Result.lift(() -> LocalDateTime.parse(buildTimestamp, STAMP_FORMAT).toInstant(ZoneOffset.UTC)).option();
    }

    /// First ancestor of `workingDir` (inclusive) that contains the source [#MARKER], or none when the
    /// monorepo source tree is not present - i.e. any consumer build outside this repository.
    private static Option<Path> locateRepoRoot(String workingDir) {
        return Option.from(Stream.iterate(Path.of(workingDir).toAbsolutePath(),
                                          Objects::nonNull,
                                          Path::getParent)
                                 .limit(MAX_ASCENT)
                                 .filter(candidate -> Files.exists(candidate.resolve(MARKER)))
                                 .findFirst());
    }

    private static Option<NewestSource> newestSource(Path repoRoot) {
        return Result.lift(() -> scanNewest(repoRoot)).or(Option.none());
    }

    private static Option<NewestSource> scanNewest(Path repoRoot) throws IOException {
        try (var tree = Files.walk(repoRoot.resolve(SCAN_ROOT))) {
            return Option.from(Stream.concat(tree,
                                             Stream.of(repoRoot.resolve(MODULE_POM)))
                                     .filter(Files::isRegularFile)
                                     .map(StalenessGuard::stamp)
                                     .max(Comparator.comparing(NewestSource::instant)));
        }
    }

    private static NewestSource stamp(Path file) {
        return new NewestSource(mtime(file), file);
    }

    private static Instant mtime(Path file) {
        return Result.lift(() -> Files.getLastModifiedTime(file).toInstant()).or(Instant.EPOCH);
    }

    private static String warningMessage(String version, String buildTimestamp, NewestSource newest) {
        return "[slice-processor STALE] installed slice-processor " + version
             + " (built " + buildTimestamp
             + ") predates the source tree - run `mvn install` in jbct/. Newest source: " + newest.file()
             + " (" + newest.instant()
             + "). Regenerated code may reintroduce an already-fixed codegen bug (#403).";
    }

    /// A source file paired with its last-modified instant; unreadable files stamp as [Instant#EPOCH]
    /// so they never win the newest-mtime comparison.
    record NewestSource(Instant instant, Path file) {}
}
