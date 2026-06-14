// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;


/// #287 — defense-in-depth for files that hold cluster secrets at rest (`aether.toml` carries
/// `cluster_secret`; the persisted CLI `api-key` file carries an admin credential). Writes such files
/// with owner-only `0600` permissions and never world-readable `0644`.
///
/// POSIX-aware: on a non-POSIX filesystem (e.g. Windows) the chmod is a best-effort no-op rather than a
/// failure, so the CLI keeps working while still hardening on Linux/macOS where the cluster runs.
public sealed interface SecureFiles {
    Set<PosixFilePermission> OWNER_ONLY = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /// Write `content` to `path`, then restrict it to owner read/write (`0600`).
    static Result<Unit> writeSecure(Path path, String content) {
        return Result.lift(Causes::fromThrowable,
                           () -> {
                               Files.writeString(path, content);
                               if (FileSystems.getDefault()
                                              .supportedFileAttributeViews()
                                              .contains("posix")) {
                               Files.setPosixFilePermissions(path, OWNER_ONLY);
                           }

                               return Unit.unit();
                           });
    }

    /// Restrict an existing file to owner read/write (`0600`). Best-effort no-op on non-POSIX systems.
    static Result<Unit> restrictToOwner(Path path) {
        return Result.lift(Causes::fromThrowable,
                           () -> {
                               if (FileSystems.getDefault()
                                              .supportedFileAttributeViews()
                                              .contains("posix")) {
                               Files.setPosixFilePermissions(path, OWNER_ONLY);
                           }

                               return Unit.unit();
                           });
    }

    record unused() implements SecureFiles {}
}
