// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.repository.maven;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// #1599 — the local artifact cache a later boot loads jars from.
///
/// A jar is PUBLISHED, never written in place: its bytes go to a sibling temp file in the target's own
/// directory and are forced to the device, the temp file is renamed over the target in one atomic
/// rename, and the directory is forced so the entry survives a crash. A crash at any point leaves
/// either no file or the complete previous one at the final path, never a torn jar. (The previous code
/// wrote an unforced temp file in the SYSTEM temp directory and then `moveReplace`d it, which the JDK
/// does as copy-and-delete across filesystems — a non-atomic write at the final path.)
///
/// A `.sha256` sidecar holding the verified digest is published next to the jar, so a cache hit can be
/// checked on load: a jar that no longer matches its sidecar (or a Maven `.sha1` one) is evicted and
/// fetched again instead of being loaded. A jar with no sidecar — installed by Maven itself — cannot be
/// checked and is trusted, as before.
final class ArtifactCache {
    private static final Logger log = LoggerFactory.getLogger(ArtifactCache.class);
    private static final Sidecar SHA256_SIDECAR = new Sidecar(".sha256", "SHA-256");
    private static final List<Sidecar> SIDECARS = List.of(SHA256_SIDECAR, new Sidecar(".sha1", "SHA-1"));

    private record Sidecar(String suffix, String algorithm) {
        Path of(Path jar) {
            return jar.resolveSibling(jar.getFileName() + suffix);
        }
    }

    private ArtifactCache() {}

    static Result<Path> store(Path target, byte[] content) {
        return store(target, content, FileOps::writeBytesForced);
    }

    /// `writer` writes and forces one file; the seam lets a test fail it mid-write.
    static Result<Path> store(Path target, byte[] content, Fn2<Result<Unit>, Path, byte[]> writer) {
        var directory = target.toAbsolutePath().getParent();

        return FileOps.createDirectoriesDurable(directory)
                      .flatMap(_ -> publish(target, content, writer))
                      .flatMap(_ -> digest(content, "SHA-256"))
                      .map(ArtifactCache::ascii)
                      .flatMap(sha256 -> publish(SHA256_SIDECAR.of(target),
                                                 sha256,
                                                 writer))
                      .flatMap(_ -> FileOps.forceDirectory(directory))
                      .map(_ -> target);
    }

    private static byte[] ascii(String hex) {
        return hex.getBytes(StandardCharsets.US_ASCII);
    }

    private static Result<Path> publish(Path target, byte[] content, Fn2<Result<Unit>, Path, byte[]> writer) {
        var temp = target.resolveSibling(target.getFileName() + ".download-" + UUID.randomUUID() + ".tmp");

        return writer.apply(temp, content)
                     .flatMap(_ -> FileOps.moveAtomic(temp, target))
                     .onFailure(_ -> FileOps.deleteIfExists(temp));
    }

    /// True when the cached jar may be loaded: it matches the first sidecar present, or has none. A jar
    /// that does not match, or that cannot be read, is evicted together with its sidecars and reported
    /// false, so the caller fetches it again.
    static boolean usable(Path jar) {
        var sidecar = SIDECARS.stream().filter(candidate -> FileOps.exists(candidate.of(jar))).findFirst();

        if (sidecar.isEmpty()) {
            return true;
        }

        var intact = matches(jar, sidecar.get());

        if (!intact) {
            log.warn("Cached artifact {} does not match its {} sidecar; evicting it to fetch it again",
                     jar,
                     sidecar.get().algorithm());
            FileOps.deleteIfExists(jar);
            SIDECARS.forEach(candidate -> FileOps.deleteIfExists(candidate.of(jar)));
        }

        return intact;
    }

    private static boolean matches(Path jar, Sidecar sidecar) {
        return FileOps.readString(sidecar.of(jar))
                      .map(body -> body.trim()
                                       .split("\\s") [0])
                      .flatMap(expected -> FileOps.readBytes(jar)
                                                  .flatMap(bytes -> digest(bytes,
                                                                           sidecar.algorithm()))
                                                  .map(expected::equalsIgnoreCase))
                      .or(false);
    }

    static Result<String> digest(byte[] content, String algorithm) {
        return Result.lift(Causes::fromThrowable,
                           () -> HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(content)));
    }
}
