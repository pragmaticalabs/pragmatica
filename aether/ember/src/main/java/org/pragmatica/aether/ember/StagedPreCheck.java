// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.RepositoryType;
import org.pragmatica.aether.repository.RepositoryFactory;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.option;


/// The early, readable refusal before any port is claimed (#3021): every coordinate the app needs is located through the SAME
/// router the node's loader uses ([RepositoryFactory#stagedRepository]), so the path rule cannot disagree with the runtime.
///
/// What it refuses, and what it does not claim. A coordinate is refused when it does not locate, or when the jar it locates to
/// is empty, not a regular file, unreadable, or has no readable manifest. STALENESS IS NOT CHECKED: a jar that is there and
/// readable passes even when the module's sources changed after it was packaged. Staleness needs the build's knowledge of what
/// changed, which the extension (#3021 step 3) has and this check does not; the refusal text says so, so no message or
/// document claims "stale, therefore refused".
///
/// [#bypassed] is the probe-1 seam: it lets a test skip this refusal to prove that the node's own load still fails closed
/// (`ArtifactNotFound` from the router) when the pre-check is not there. It has NO main caller (pinned by
/// `StagedSeamCallerCensusTest`).
public interface StagedPreCheck {
    /// Succeeds when every coordinate locates through the router built from `staged` to a usable jar; otherwise fails with
    /// [EmberAppNotPackaged] for the first one that does not, carrying the locate or jar cause and naming
    /// `stagedSliceArtifacts` (the caller's report; this check does not read it).
    Promise<Unit> check(RepositoryType.Staged staged,
                        Collection<Artifact> coordinates,
                        List<String> stagedSliceArtifacts);

    static StagedPreCheck stagedPreCheck() {
        return StagedPreCheck::allLocated;
    }

    /// TEST SEAM (probe 1): accepts everything.
    static StagedPreCheck bypassed() {
        return (_, _, _) -> Promise.unitPromise();
    }

    private static Promise<Unit> allLocated(RepositoryType.Staged staged,
                                            Collection<Artifact> coordinates,
                                            List<String> found) {
        var router = RepositoryFactory.stagedRepository(staged);

        return Promise.allOf(coordinates.stream()
                                        .map(coordinate -> located(router,
                                                                   coordinate,
                                                                   staged.moduleSet().contains(coordinate),
                                                                   found))
                                        .toList()).flatMap(StagedPreCheck::firstFailure);
    }

    private static Promise<Unit> located(Repository router, Artifact coordinate, boolean module, List<String> found) {
        return router.locate(coordinate)
                     .flatMap(location -> usableJar(location.url()).async())
                     .mapError(cause -> EmberAppNotPackaged.emberAppNotPackaged(coordinate.asString(),
                                                                                module,
                                                                                cause.message(),
                                                                                found));
    }

    private static Result<Unit> usableJar(URL url) {
        return Result.lift(Causes::fromThrowable,
                           () -> Path.of(url.toURI()))
                     .flatMap(StagedPreCheck::regularReadableNonEmptyFile)
                     .flatMap(StagedPreCheck::manifestOf)
                     .mapToUnit();
    }

    private static Result<Path> regularReadableNonEmptyFile(Path jar) {
        return Verify.ensure(jar,
                             StagedPreCheck::isRegular,
                             Causes.cause("not a regular file: " + jar))
                     .flatMap(StagedPreCheck::readable)
                     .flatMap(StagedPreCheck::nonEmpty);
    }

    private static Result<Path> readable(Path jar) {
        return Verify.ensure(jar, StagedPreCheck::isReadable, Causes.cause("not readable: " + jar));
    }

    private static Result<Path> nonEmpty(Path jar) {
        return Verify.ensure(jar, StagedPreCheck::hasContent, Causes.cause("empty file: " + jar));
    }

    private static boolean isRegular(Path jar) {
        return Files.isRegularFile(jar);
    }

    private static boolean isReadable(Path jar) {
        return Files.isReadable(jar);
    }

    private static boolean hasContent(Path jar) {
        return jar.toFile()
                  .length() > 0;
    }

    private static Result<Manifest> manifestOf(Path jar) {
        return Result.lift(Causes::fromThrowable, () -> readManifest(jar)).flatMap(manifest -> option(manifest).toResult(Causes.cause("no manifest in " + jar)));
    }

    // Adapter leaf: JarFile throws IOException; the caller lifts it into a Cause.
    @SuppressWarnings("JBCT-EX-01")
    private static Manifest readManifest(Path jar) throws IOException {
        try (var jarFile = new JarFile(jar.toFile())) {
            return jarFile.getManifest();
        }
    }

    private static Promise<Unit> firstFailure(List<Result<Unit>> results) {
        return results.stream()
                      .filter(Result::isFailure)
                      .findFirst()
                      .orElseGet(Result::unitResult)
                      .async();
    }
}
