// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    static Result<Unit> writeSecure(Path path, String content) {
        return Result.lift(Causes::fromThrowable, () -> writeOwnerOnly(path, content));
    }

    /// `throws IOException` is deliberate and lifted exactly once, by [#writeSecure]'s `Result.lift`
    /// — the sanctioned adapter boundary. Threading `Result` through the two creation paths here would
    /// obscure the ordering property the whole method exists to establish.
    @SuppressWarnings({"JBCT-SEQ-01", "JBCT-EX-01"})
    private static Unit writeOwnerOnly(Path path, String content) throws IOException {
        if (!posixSupported()) {
            Files.writeString(path, content);

            return Unit.unit();
        }

        if (Files.exists(path)) {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        }

        try (var channel = Files.newByteChannel(path,
                                                Set.of(StandardOpenOption.CREATE,
                                                       StandardOpenOption.WRITE,
                                                       StandardOpenOption.TRUNCATE_EXISTING),
                                                PosixFilePermissions.asFileAttribute(OWNER_ONLY))) {
            channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
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
