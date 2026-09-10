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
import java.util.concurrent.atomic.AtomicBoolean;

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

    /// #980 verification finding SF3 — the END-STATE assertions above cannot see the defect this
    /// pins. `Files.writeString` followed by `setPosixFilePermissions` also ends at `0600`; what it
    /// adds is an interval in which the secret is already on disk at `rw-r--r--`. Reverting to that
    /// order leaves both tests above green.
    ///
    /// So this samples permissions from a second thread WHILE the write is in flight and requires that
    /// group/other-readable is never observed. Calibrated before being committed: against the
    /// write-then-chmod order the sampler detected the window **10 runs out of 10** at this payload
    /// size, and against the current create-with-attributes implementation **0 out of 10** — so it is
    /// a real red-on-revert pin, not a probabilistic one, and it does not false-alarm.
    ///
    /// The payload is 1 MiB purely to widen the write beyond sampling granularity; nothing about the
    /// content matters.
    @Test
    void writeSecure_neverExposesContentWorldReadable_evenMidWrite(@TempDir Path dir) throws Exception {
        assumeTrue(posix(), "POSIX permissions only assertable on a POSIX filesystem");
        var file = dir.resolve("bootstrap-state.json");
        var payload = "s".repeat(1024 * 1024);
        var sawLoose = new AtomicBoolean(false);
        var stop = new AtomicBoolean(false);
        var sampler = Thread.ofPlatform().start(() -> sampleUntilStopped(file, sawLoose, stop));

        var result = SecureFiles.writeSecure(file, payload);

        stop.set(true);
        sampler.join();

        assertThat(result.isSuccess()).isTrue();
        assertThat(sawLoose.get())
            .as("the cluster secret must never be group/other-readable, not even for the interval "
                + "between writing it and chmod-ing it")
            .isFalse();
        assertThat(readPerms(file)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                                                              PosixFilePermission.OWNER_WRITE);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void sampleUntilStopped(Path file, AtomicBoolean sawLoose, AtomicBoolean stop) {
        while (!stop.get()) {
            try {
                var perms = Files.getPosixFilePermissions(file);

                if (perms.contains(PosixFilePermission.GROUP_READ)
                    || perms.contains(PosixFilePermission.OTHERS_READ)) {
                    sawLoose.set(true);
                }
            } catch (Exception e) {
                // The file does not exist yet — not an observation either way; keep sampling.
            }
        }
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
