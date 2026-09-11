// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// #287 — defense-in-depth for files that hold cluster secrets at rest (`aether.toml` carries
/// `cluster_secret`; the persisted CLI `api-key` file carries an admin credential). Writes such files
/// with owner-only `0600` permissions and never world-readable `0644`.
///
/// POSIX-aware: on a non-POSIX filesystem (e.g. Windows) the chmod is a best-effort no-op rather than a
/// failure, so the CLI keeps working while still hardening on Linux/macOS where the cluster runs.
public sealed interface SecureFiles {
    Set<PosixFilePermission> OWNER_ONLY = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /// Write `content` to `path` so that it is owner-only (`0600`) **at every instant it holds that
    /// content** — never written first and tightened after.
    ///
    /// #980 verification finding SF3. The previous implementation was `Files.writeString` followed by
    /// `setPosixFilePermissions`, which under the ambient `umask 022` leaves the file `rw-r--r--`
    /// **with the secret already on disk** until the chmod lands. Measured, not theorised. That window
    /// is on files this helper exists to protect — `aether.toml`, the persisted `api-key`, and (since
    /// #980) `bootstrap-state.json`, which carries the cluster secret and is therefore
    /// admin-equivalent.
    ///
    /// Two paths, because a file attribute is honoured only at CREATION:
    ///   - **new file** — created with the mode as a creation attribute, so it never exists loose;
    ///   - **existing file** — tightened BEFORE the new content is written, so a file left `0644` by
    ///     an older release never holds the *new* secret at `0644`.
    ///
    /// Non-POSIX filesystems (Windows) keep the plain write: best-effort, as before.
    ///
    /// #994 verification finding SF-1 — **the write is also all-or-nothing.** It used to open `path`
    /// itself with `TRUNCATE_EXISTING`, so a process that died part-way through left a zero-byte or
    /// half-written file: `Files.exists` true, `fromJson` failing, and `BootstrapStatePersistence.load`
    /// reporting EMPTY — indistinguishable from "nothing was ever persisted". That mattered because
    /// `bootstrap-state.json` is the only record of money-bearing resources and **both the #994 and #995
    /// incidents ended with the operator KILLING the process mid-run.** Content now lands in a sibling
    /// temp file and arrives at `path` by [StandardCopyOption#ATOMIC_MOVE], so a reader sees either the
    /// whole previous file or the whole new one.
    ///
    /// Precise guarantee, because "atomic" alone overclaims: the rename is atomic **with respect to
    /// process death** — `rename(2)` within one directory either completed or did not, and the page
    /// cache survives the process. The temp file's bytes are `force`d before the rename, so the content
    /// a completed rename publishes is on the device. The DIRECTORY entry is not fsync'd, so a host
    /// power loss in the window can still lose the last write; process death, the observed failure mode,
    /// cannot.
    static Result<Unit> writeSecure(Path path, String content) {
        return Result.lift(Causes::fromThrowable, () -> writeOwnerOnly(path, content));
    }

    /// `throws IOException` is deliberate and lifted exactly once, by [#writeSecure]'s `Result.lift`
    /// — the sanctioned adapter boundary. Threading `Result` through the two creation paths here would
    /// obscure the ordering property the whole method exists to establish.
    ///
    /// The pre-tightening of an EXISTING file is kept even though the atomic move replaces the inode
    /// (and with it the mode) on its own: without it, a file an older release left `0644` keeps holding
    /// its OLD secret world-readable for the duration of the temp write. That window is pre-existing
    /// rather than new, but it is one syscall to close and #980's property is stated over instants.
    @SuppressWarnings({"JBCT-SEQ-01", "JBCT-EX-01"})
    private static Unit writeOwnerOnly(Path path, String content) throws IOException {
        if (!posixSupported()) {
            return writeViaTempFile(path, content);
        }

        if (Files.exists(path)) {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        }

        return writeViaTempFile(path, content, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
    }

    /// The temp file is created in `path`'s OWN directory, which is what makes the move a same-filesystem
    /// rename and therefore atomic; a temp in `/tmp` would be a copy-and-delete and would reintroduce the
    /// torn-file window this method exists to remove. Created with the same mode attribute as the target,
    /// so the new secret is never loose even while it is still called something else. A failed write
    /// removes the temp rather than leaving litter beside the real file.
    @SuppressWarnings({"JBCT-SEQ-01", "JBCT-EX-01"})
    private static Unit writeViaTempFile(Path path, String content, FileAttribute<?>... attributes) throws IOException {
        var directory = path.toAbsolutePath().getParent();
        var temp = Files.createTempFile(directory, path.getFileName() + ".", ".tmp", attributes);

        try {
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
            }

            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);

            throw e;
        }

        return Unit.unit();
    }

    private static boolean posixSupported() {
        return FileSystems.getDefault()
                          .supportedFileAttributeViews()
                          .contains("posix");
    }

    /// Restrict an existing file to owner read/write (`0600`). Best-effort no-op on non-POSIX systems.
    static Result<Unit> restrictToOwner(Path path) {
        return Result.lift(Causes::fromThrowable,
                           () -> {
                               if (posixSupported()) {
                               Files.setPosixFilePermissions(path, OWNER_ONLY);
                           }

                               return Unit.unit();
                           });
    }

    record unused() implements SecureFiles {}
}
