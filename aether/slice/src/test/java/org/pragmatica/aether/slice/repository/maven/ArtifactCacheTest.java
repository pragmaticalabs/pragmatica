// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.repository.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #1599 — `RemoteRepository` caches downloaded jars that a later boot loads. A crash mid-write used to be
/// able to leave a torn jar at the final path; a torn jar already there was loaded as if intact.
class ArtifactCacheTest {
    private static final byte[] JAR = "PK\u0003\u0004 a jar's bytes, long enough to tear in half".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    /// The writer fails after writing half the bytes — a crash or a full disk mid-write. Nothing may appear
    /// at the final path, and no temp file may be left behind. Mutation that reddens it: write straight to
    /// the final path instead of a sibling that is renamed over it.
    @Test
    void store_writerFailsMidWrite_leavesNothingAtTheFinalPath() throws IOException {
        var target = dir.resolve("g/a/1.0/a-1.0.jar");

        var result = ArtifactCache.store(target, JAR, ArtifactCacheTest::tornWrite);

        assertThat(result.isFailure()).as("the failed write is reported: %s", result).isTrue();
        assertThat(target).as("#1599: no torn jar at the final path").doesNotExist();
        try (var left = Files.list(target.getParent())) {
            assertThat(left.toList()).as("no temp file left behind").isEmpty();
        }
    }

    /// A failed re-store over an existing jar keeps the previous complete jar: the rename is the only step
    /// that touches the final path.
    @Test
    void store_writerFailsOverAnExistingJar_keepsThePreviousOne() throws IOException {
        var target = dir.resolve("a-1.0.jar");

        assertThat(ArtifactCache.store(target, JAR).isSuccess()).isTrue();
        assertThat(ArtifactCache.store(target, "replacement".getBytes(StandardCharsets.UTF_8), ArtifactCacheTest::tornWrite)
                                .isFailure()).isTrue();

        assertThat(Files.readAllBytes(target)).isEqualTo(JAR);
    }

    @Test
    void store_publishesTheJarAndItsSha256Sidecar() throws IOException {
        var target = dir.resolve("a-1.0.jar");

        assertThat(ArtifactCache.store(target, JAR).isSuccess()).isTrue();

        assertThat(Files.readAllBytes(target)).isEqualTo(JAR);
        assertThat(Files.readString(dir.resolve("a-1.0.jar.sha256"))).isEqualTo(ArtifactCache.digest(JAR, "SHA-256").unwrap());
        assertThat(ArtifactCache.usable(target)).as("a freshly stored jar verifies").isTrue();
    }

    /// A cached jar that no longer matches its sidecar — torn before this fix, or corrupted since — is evicted
    /// so it is fetched again, instead of being loaded. Mutation that reddens it: make `usable` skip the check.
    @Test
    void usable_jarNotMatchingItsSidecar_isEvicted() throws IOException {
        var target = dir.resolve("a-1.0.jar");

        assertThat(ArtifactCache.store(target, JAR).isSuccess()).isTrue();
        Files.write(target, Arrays.copyOf(JAR, JAR.length / 2));

        assertThat(ArtifactCache.usable(target)).as("#1599: a torn jar is not loaded").isFalse();
        assertThat(target).as("it is evicted so the next resolve fetches it again").doesNotExist();
        assertThat(dir.resolve("a-1.0.jar.sha256")).doesNotExist();
    }

    /// Maven's own `.sha1` sidecar is honoured too.
    @Test
    void usable_jarNotMatchingAMavenSha1Sidecar_isEvicted() throws IOException {
        var target = dir.resolve("a-1.0.jar");

        Files.write(target, JAR);
        Files.writeString(dir.resolve("a-1.0.jar.sha1"), "0000000000000000000000000000000000000000  a-1.0.jar");

        assertThat(ArtifactCache.usable(target)).isFalse();
        assertThat(target).doesNotExist();
    }

    /// CONTROL — a jar installed by Maven without a sidecar cannot be checked and stays usable, as before.
    @Test
    void usable_jarWithoutSidecar_isTrusted() throws IOException {
        var target = dir.resolve("a-1.0.jar");

        Files.write(target, JAR);

        assertThat(ArtifactCache.usable(target)).isTrue();
        assertThat(target).exists();
    }

    private static Result<Unit> tornWrite(Path path, byte[] content) {
        return FileOps.writeBytes(path, Arrays.copyOf(content, content.length / 2))
                      .flatMap(_ -> Causes.cause("simulated crash after " + content.length / 2 + " bytes").result());
    }
}
