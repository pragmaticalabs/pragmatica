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

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    /// #994 verification finding SF-1 — **a concurrent reader must never observe a PARTIAL file.** The
    /// previous implementation opened the target itself with `TRUNCATE_EXISTING` and wrote in place, so a
    /// reader (or a process that died) could see a zero-byte or half-written file. For `bootstrap-state.json`
    /// that is not a cosmetic window: `Files.exists` is true, `fromJson` fails, and the ledger of paid VMs
    /// reads as EMPTY — indistinguishable from "nothing was ever created".
    ///
    /// Same sampler shape as the permissions pin above, different property: every observation must be EXACTLY
    /// the old content or EXACTLY the new one. The sampler's own read count is asserted non-zero, because an
    /// assertion about what was never seen is vacuous if nothing was seen at all; and the observed set is
    /// asserted to contain the old content, which proves the sampler was running BEFORE the write landed
    /// rather than only after it.
    ///
    /// The 1 MiB payload exists only to widen the write past sampling granularity.
    @Test
    void writeSecure_neverExposesAPartialFile_toAConcurrentReader(@TempDir Path dir) throws Exception {
        var file = dir.resolve("bootstrap-state.json");
        var oldContent = "{\"createdResources\":[\"vm-1\",\"vm-2\"]}";
        var newContent = "n".repeat(1024 * 1024);

        Files.writeString(file, oldContent);
        var observed = new HashSet<String>();
        var reads = new AtomicInteger();
        var stop = new AtomicBoolean(false);
        // Daemon + try/finally: `awaitFirstRead` can fail, and a live non-daemon sampler spinning past a
        // failed assertion would wedge the surefire fork instead of reporting the failure. An instrument
        // that hangs on its own failure is worse than one that reports it.
        var sampler = Thread.ofPlatform().daemon().start(() -> sampleContentUntilStopped(file, observed, reads, stop));
        Result<Unit> result;

        try {
            // The sampler is required to have read the PRE-write file before the write starts. Without this
            // barrier the test is racy in the direction that HIDES the defect: a sampler that first runs
            // after the write completed observes only the new content and passes vacuously.
            awaitFirstRead(reads);
            result = SecureFiles.writeSecure(file, newContent);
        } finally {
            stop.set(true);
            sampler.join();
        }

        assertThat(result.isSuccess()).isTrue();
        assertThat(reads.get()).as("the sampler must have actually read the file, or every assertion below is vacuous")
                  .isGreaterThan(0);
        assertThat(snapshotOf(observed)).as("the pre-write content, guaranteed by the barrier above — this is what "
                                            + "proves the sampler was running ACROSS the write")
                  .contains(oldContent);
        assertThat(snapshotOf(observed)).as("every observation must be one WHOLE version or the other; a prefix of "
                                            + "the new content, or an empty file, is the torn state that makes a "
                                            + "ledger of paid VMs read as empty")
                  .isSubsetOf(oldContent, newContent);
        assertThat(Files.readString(file)).isEqualTo(newContent);

        try (var entries = Files.list(dir)) {
            assertThat(entries.filter(entry -> entry.getFileName().toString().endsWith(".tmp")).toList())
                      .as("the temp file must not survive a successful write")
                      .isEmpty();
        }
    }

    private static Set<String> snapshotOf(Set<String> observed) {
        synchronized (observed) {
            return Set.copyOf(observed);
        }
    }

    /// Bounded: a sampler that never reads is a broken instrument, and hanging here would hide that.
    @SuppressWarnings("JBCT-EX-01")
    private static void awaitFirstRead(AtomicInteger reads) {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (reads.get() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(reads.get()).as("the sampler thread must read the file before the write begins")
                  .isGreaterThan(0);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void sampleContentUntilStopped(Path file, Set<String> observed, AtomicInteger reads, AtomicBoolean stop) {
        while (!stop.get()) {
            try {
                var content = Files.readString(file);

                reads.incrementAndGet();
                synchronized (observed) {
                    observed.add(content);
                }
            } catch (Exception e) {
                // A read that races the rename can fail outright; that is not an observation of torn content.
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
