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
/// A `.aether-sha256` sidecar holding the verified digest is published next to the jar. It is this node's
/// OWNERSHIP mark as well as its checksum, and deliberately not Maven's `.sha256` name, which Maven itself may write.
/// A cache hit is checked on load ([#check]):
/// - a jar carrying this node's sidecar that no longer matches it is fetched again and replaced — the node wrote it;
/// - a jar that fails a Maven `.sha1`/`.sha256` sidecar but carries no mark of ours is NOT the node's to delete (the
///   local repository is the operator's `~/.m2` in dev and Forge flows, possibly holding a locally built, never
///   published artifact). It is refused, loudly and with a typed cause, and every file is left as it was (v1617, M1);
/// - a jar with no sidecar (installed by Maven itself) cannot be checked and is used, as before.
final class ArtifactCache {
    private static final Logger log = LoggerFactory.getLogger(ArtifactCache.class);
    /// This node's sidecar: its checksum AND its mark that the node wrote the jar.
    private static final Sidecar OWN_SIDECAR = new Sidecar(".aether-sha256", "SHA-256");

    /// Maven's sidecars: checked, never grounds for deleting anything.
    private static final List<Sidecar> MAVEN_SIDECARS = List.of(new Sidecar(".sha256", "SHA-256"),
                                                                new Sidecar(".sha1", "SHA-1"));

    /// What a cache hit may do with the jar it found.
    enum CacheState {
        /// Load it: it matches its sidecar, or it has none to check against.
        USABLE,
        /// It was this node's and does not match: do not load it, fetch it again; the fetch replaces it atomically.
        STALE,
        /// It fails a Maven checksum and is not this node's: refuse, and leave every file untouched.
        FOREIGN_MISMATCH
    }

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
                      .flatMap(sha256 -> publish(OWN_SIDECAR.of(target),
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

    static CacheState check(Path jar) {
        if (FileOps.exists(OWN_SIDECAR.of(jar))) {
            return checkOwn(jar);
        }

        return MAVEN_SIDECARS.stream()
                             .filter(sidecar -> FileOps.exists(sidecar.of(jar)))
                             .findFirst()
                             .map(sidecar -> checkForeign(jar, sidecar))
                             .orElse(CacheState.USABLE);
    }

    /// A jar of ours that no longer matches is NOT deleted (CodeRabbit on #1725): it is reported STALE, never loaded,
    /// and [#store]'s atomic rename replaces it once a refetch succeeds. If the refetch fails, the stale jar and its
    /// sidecar are still there, still mismatched, and the next resolve tries again — nothing is lost before a good
    /// copy exists.
    private static CacheState checkOwn(Path jar) {
        if (matches(jar, OWN_SIDECAR)) {
            return CacheState.USABLE;
        }

        log.warn("Cached artifact {} no longer matches the checksum this node recorded when it wrote it; fetching it again "
                + "(the stale copy is replaced only once the fetch succeeds)",
                 jar);

        return CacheState.STALE;
    }

    private static CacheState checkForeign(Path jar, Sidecar sidecar) {
        if (matches(jar, sidecar)) {
            return CacheState.USABLE;
        }

        log.error("Artifact {} in the local Maven repository does not match its {} checksum ({}). This node did not write it, so it is "
                 + "left untouched and NOT loaded; rebuild or reinstall it, or remove it so the node can fetch it",
                  jar,
                  sidecar.algorithm(),
                  sidecar.of(jar).getFileName());

        return CacheState.FOREIGN_MISMATCH;
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
