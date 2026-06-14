// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// #287 — proves secret files at rest are written owner-only (0600), never world-readable.
class SecureFilesTest {
    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    @Test
    void writeSecure_writesContentAndRestrictsToOwnerOnly(@TempDir Path dir) {
        var file = dir.resolve("aether.toml");

        var result = SecureFiles.writeSecure(file, "cluster_secret = \"top-secret\"\n");

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.exists(file)).isTrue();

        assumeTrue(posix(), "POSIX permissions only assertable on a POSIX filesystem");
        assertThat(readPerms(file)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                                                              PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void restrictToOwner_tightensExistingWorldReadableFile(@TempDir Path dir) {
        assumeTrue(posix(), "POSIX permissions only assertable on a POSIX filesystem");
        var file = writeWorldReadable(dir.resolve("api-key"));

        var result = SecureFiles.restrictToOwner(file);

        assertThat(result.isSuccess()).isTrue();
        assertThat(readPerms(file)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                                                              PosixFilePermission.OWNER_WRITE);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Path writeWorldReadable(Path file) {
        try {
            Files.writeString(file, "the-key");
            Files.setPosixFilePermissions(file,
                                          java.util.Set.of(PosixFilePermission.OWNER_READ,
                                                           PosixFilePermission.OWNER_WRITE,
                                                           PosixFilePermission.GROUP_READ,
                                                           PosixFilePermission.OTHERS_READ));

            return file;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    private static java.util.Set<PosixFilePermission> readPerms(Path file) {
        try {
            return Files.getPosixFilePermissions(file);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
