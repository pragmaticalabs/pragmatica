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
    void store_publishesTheJarAndTheNodesOwnSidecar() throws IOException {
        var target = dir.resolve("a-1.0.jar");

        assertThat(ArtifactCache.store(target, JAR).isSuccess()).isTrue();

        assertThat(Files.readAllBytes(target)).isEqualTo(JAR);
        assertThat(Files.readString(dir.resolve("a-1.0.jar.aether-sha256"))).isEqualTo(ArtifactCache.digest(JAR, "SHA-256").unwrap());
        assertThat(ArtifactCache.check(target)).as("a freshly stored jar verifies").isEqualTo(ArtifactCache.CacheState.USABLE);
    }

    /// A jar THIS NODE wrote that no longer matches its sidecar — torn before this fix, or corrupted since — is reported
    /// STALE so it is fetched again instead of being loaded. It is NOT deleted: the refetch replaces it atomically, so a
    /// failed refetch loses nothing (CodeRabbit on #1725). Mutation that reddens it: make `check` skip the comparison.
    @Test
    void check_ownJarNotMatchingItsSidecar_isStaleAndKeptUntilReplaced() throws IOException {
        var target = dir.resolve("a-1.0.jar");

        assertThat(ArtifactCache.store(target, JAR).isSuccess()).isTrue();
        Files.write(target, Arrays.copyOf(JAR, JAR.length / 2));

        assertThat(ArtifactCache.check(target)).as("#1599: a torn jar is not loaded").isEqualTo(ArtifactCache.CacheState.STALE);
        assertThat(target).as("it is kept until a refetch replaces it").exists();
        assertThat(dir.resolve("a-1.0.jar.aether-sha256")).exists();
    }

    /// v1617 M1 — a jar in the local Maven repository that fails MAVEN's `.sha1` and carries no mark of this node (the
    /// operator's `~/.m2` in dev and Forge flows, possibly a local never-published build) is refused and LEFT AS IT WAS:
    /// the node never deletes a file it did not write. Mutation that reddens it: delete the jar on a foreign mismatch.
    @Test
    void check_foreignJarFailingAMavenSha1_isRefusedAndLeftUntouched() throws IOException {
        var target = dir.resolve("a-1.0.jar");
        var sha1 = dir.resolve("a-1.0.jar.sha1");

        Files.write(target, JAR);
        Files.writeString(sha1, "0000000000000000000000000000000000000000  a-1.0.jar");

        assertThat(ArtifactCache.check(target)).isEqualTo(ArtifactCache.CacheState.FOREIGN_MISMATCH);
        assertThat(target).as("M1: a jar the node did not write survives").exists();
        assertThat(Files.readAllBytes(target)).isEqualTo(JAR);
        assertThat(sha1).as("and so does Maven's sidecar").exists();
    }

    /// Maven may write `.sha256` too; that name is Maven's, not the node's mark, so a mismatch against it is refused the
    /// same way. The node's own sidecar is `.aether-sha256` precisely so the two cannot be confused.
    @Test
    void check_foreignJarFailingAMavenSha256_isRefusedAndLeftUntouched() throws IOException {
        var target = dir.resolve("a-1.0.jar");

        Files.write(target, JAR);
        Files.writeString(dir.resolve("a-1.0.jar.sha256"), "00".repeat(32));

        assertThat(ArtifactCache.check(target)).isEqualTo(ArtifactCache.CacheState.FOREIGN_MISMATCH);
        assertThat(target).exists();
    }

    /// CONTROL — a jar installed by Maven without a sidecar cannot be checked and stays usable, as before.
    @Test
    void check_jarWithoutSidecar_isTrusted() throws IOException {
        var target = dir.resolve("a-1.0.jar");

        Files.write(target, JAR);

        assertThat(ArtifactCache.check(target)).isEqualTo(ArtifactCache.CacheState.USABLE);
        assertThat(target).exists();
    }

    private static Result<Unit> tornWrite(Path path, byte[] content) {
        return FileOps.writeBytes(path, Arrays.copyOf(content, content.length / 2))
                      .flatMap(_ -> Causes.cause("simulated crash after " + content.length / 2 + " bytes").result());
    }
}
