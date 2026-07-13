// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.jbct.slice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static com.google.common.truth.Truth.assertThat;

/// Unit coverage for the #403 staleness guard: it must fire a remedy warning when the installed
/// processor jar predates its source tree, and stay silent in every other case (fresh install,
/// source tree absent for an external consumer, or an unresolved build stamp).
class StalenessGuardTest {
    private static final String VERSION = "1.0.0-rc2";
    private static final String STAMP_EARLY = "2026-07-01T00:00:00Z";
    private static final String STAMP_LATE = "2026-07-10T00:00:00Z";
    private static final Instant MTIME_LATE = Instant.parse("2026-07-10T00:00:00Z");
    private static final Instant MTIME_EARLY = Instant.parse("2026-07-01T00:00:00Z");

    @Test
    void staleWarning_fires_whenSourceNewerThanInstalledJar(@TempDir Path repoRoot) throws IOException {
        writeMarker(repoRoot, MTIME_LATE);

        var warning = StalenessGuard.staleWarning(repoRoot.toString(), VERSION, STAMP_EARLY)
                                    .or("");

        assertThat(warning).contains("predates the source tree");
        assertThat(warning).contains("run `mvn install` in jbct/");
        assertThat(warning).contains(VERSION);
        assertThat(warning).contains("SliceProcessor.java");
    }

    @Test
    void staleWarning_silent_whenInstalledJarNewerThanSource(@TempDir Path repoRoot) throws IOException {
        writeMarker(repoRoot, MTIME_EARLY);

        var warning = StalenessGuard.staleWarning(repoRoot.toString(), VERSION, STAMP_LATE);

        assertThat(warning.or("SILENT")).isEqualTo("SILENT");
    }

    @Test
    void staleWarning_silent_whenSourceTreeAbsent(@TempDir Path unrelated) {
        // No jbct/slice-processor marker below the working dir - any consumer built outside this monorepo.
        var warning = StalenessGuard.staleWarning(unrelated.toString(), VERSION, STAMP_EARLY);

        assertThat(warning.or("SILENT")).isEqualTo("SILENT");
    }

    @Test
    void staleWarning_silent_whenBuildStampUnresolved(@TempDir Path repoRoot) throws IOException {
        writeMarker(repoRoot, Instant.now());

        var warning = StalenessGuard.staleWarning(repoRoot.toString(), VERSION, BuildInfo.UNKNOWN);

        assertThat(warning.or("SILENT")).isEqualTo("SILENT");
    }

    private static void writeMarker(Path repoRoot, Instant mtime) throws IOException {
        var marker = repoRoot.resolve(Path.of("jbct", "slice-processor", "src", "main", "java",
                                              "org", "pragmatica", "jbct", "slice", "SliceProcessor.java"));
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "// fixture\n");
        Files.setLastModifiedTime(marker, FileTime.from(mtime));
    }
}
