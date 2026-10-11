// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The archive piped to `docker cp -` must be a real tar the daemon can extract: proved by extracting it with the system `tar`
/// (the same ustar reader family) and reading back content and mode, not by re-parsing it with the code that wrote it.
class SingleFileTarTest {
    @Test
    void archive_extractsWithSystemTar_contentIntact_andModeIs0400() throws Exception {
        var content = "sentinel-secret-" + "x".repeat(700);
        var archive = SingleFileTar.singleFileTar("aether-cluster-secret", content.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0400, 1000, 1000);
        var dir = Files.createTempDirectory("single-file-tar-");

        try {
            var process = new ProcessBuilder("tar", "-xf", "-", "-C", dir.toString()).redirectErrorStream(true).start();

            try (var in = process.getOutputStream()) {
                in.write(archive);
            }

            var output = new String(process.getInputStream().readAllBytes());

            assertThat(process.waitFor()).as(output).isZero();
            var extracted = dir.resolve("aether-cluster-secret");

            assertThat(Files.readString(extracted)).isEqualTo(content);
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(extracted))).isEqualTo("r--------");
            assertThat(archive.length % SingleFileTar.BLOCK).as("a tar is whole blocks").isZero();
        } finally {
            try (var files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }
}
