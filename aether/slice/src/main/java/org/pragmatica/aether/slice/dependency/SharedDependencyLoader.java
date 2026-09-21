// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.dependency;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.SharedLibraryClassLoader;
import org.pragmatica.aether.slice.SliceClassLoader;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02", "JBCT-ZONE-02"})
public interface SharedDependencyLoader {
    Logger log = LoggerFactory.getLogger(SharedDependencyLoader.class);

    /// `requester` is the slice whose dependency file is being processed; it is recorded against
    /// every artifact it puts into the shared loader so a later conflict can name both sides (#1184).
    static Promise<Unit> processInfraDependencies(List<ArtifactDependency> dependencies,
                                                  SharedLibraryClassLoader sharedLibraryLoader,
                                                  Repository repository,
                                                  String requester) {
        if (dependencies.isEmpty()) {
            return Promise.success(unit());
        }

        return processInfraSequentially(dependencies, sharedLibraryLoader, repository, requester);
    }

    private static Promise<Unit> processInfraSequentially(List<ArtifactDependency> dependencies,
                                                          SharedLibraryClassLoader sharedLibraryLoader,
                                                          Repository repository,
                                                          String requester) {
        if (dependencies.isEmpty()) {
            return Promise.success(unit());
        }

        var dependency = dependencies.getFirst();
        var remaining = dependencies.subList(1, dependencies.size());

        return loadInfraIntoShared(dependency, sharedLibraryLoader, repository, requester).flatMap(_ -> processInfraSequentially(remaining,
                                                                                                                                 sharedLibraryLoader,
                                                                                                                                 repository,
                                                                                                                                 requester));
    }

    /// #1184 — `[infra]` has no per-slice fallback (unlike `[shared]`, which loads a conflicting
    /// version into the slice's own loader), so the compatibility verdict decides the slice load:
    /// `Compatible` (the loaded version satisfies this slice's pattern) reuses it; `Conflict` fails
    /// the load, naming both versions and both requesters. Before this the verdict was discarded
    /// and both cases were a DEBUG "already loaded" no-op.
    private static Promise<Unit> loadInfraIntoShared(ArtifactDependency dependency,
                                                     SharedLibraryClassLoader sharedLibraryLoader,
                                                     Repository repository,
                                                     String requester) {
        return sharedLibraryLoader.checkCompatibility(dependency)
                                  .fold(() -> loadInfraArtifact(dependency, sharedLibraryLoader, repository, requester),
                                        result -> handleInfraCompatibilityResult(dependency,
                                                                                 result,
                                                                                 sharedLibraryLoader,
                                                                                 requester));
    }

    private static Promise<Unit> handleInfraCompatibilityResult(ArtifactDependency dependency,
                                                                CompatibilityResult result,
                                                                SharedLibraryClassLoader sharedLibraryLoader,
                                                                String requester) {
        return switch (result) {
            case CompatibilityResult.Compatible(var loadedVersion) -> logInfraCompatible(dependency, loadedVersion);
            case CompatibilityResult.Conflict(var loadedVersion, _) -> refuseInfraConflict(dependency,
                                                                                           loadedVersion,
                                                                                           sharedLibraryLoader,
                                                                                           requester);
            case CompatibilityResult.unused() -> Promise.success(unit());
        };
    }

    private static Promise<Unit> loadInfraArtifact(ArtifactDependency dependency,
                                                   SharedLibraryClassLoader sharedLibraryLoader,
                                                   Repository repository,
                                                   String requester) {
        return toArtifact(dependency).async()
                         .flatMap(repository::locate)
                         .flatMap(location -> addInfraToSharedLoader(dependency,
                                                                     sharedLibraryLoader,
                                                                     location.url(),
                                                                     requester).fold(refusal -> reevaluateInfraWindow(dependency,
                                                                                                                      sharedLibraryLoader,
                                                                                                                      requester,
                                                                                                                      refusal),
                                                                                     _ -> Promise.success(unit())));
    }

    /// The add was refused because another slice's version landed between `checkCompatibility` and
    /// `addArtifact`. The guard compares exact versions; the rule that decides `[infra]` loads is
    /// `pattern.matches(loaded)`. Re-running the rule against what is now held routes a
    /// rule-Compatible request (`^1.0.0` against a landed 1.2.0) to reuse, exactly as it would have
    /// been had the competitor landed first, and a genuine conflict to the same refusal (#1184 rev
    /// M1). `checkCompatibility` cannot be empty after a refusal; if it were, the refusal stands.
    private static Promise<Unit> reevaluateInfraWindow(ArtifactDependency dependency,
                                                       SharedLibraryClassLoader sharedLibraryLoader,
                                                       String requester,
                                                       Cause refusal) {
        return sharedLibraryLoader.checkCompatibility(dependency)
                                  .fold(refusal::promise,
                                        result -> handleInfraCompatibilityResult(dependency,
                                                                                 result,
                                                                                 sharedLibraryLoader,
                                                                                 requester));
    }

    private static Result<Unit> addInfraToSharedLoader(ArtifactDependency dependency,
                                                       SharedLibraryClassLoader sharedLibraryLoader,
                                                       URL url,
                                                       String requester) {
        var version = extractVersion(dependency.versionPattern());

        return sharedLibraryLoader.addArtifact(dependency.groupId(),
                                               dependency.artifactId(),
                                               version,
                                               url,
                                               requester)
                                  .onSuccessRun(() -> log.debug("Loaded infra dependency {} into SharedLibraryClassLoader for {}",
                                                                dependency.asString(),
                                                                requester));
    }

    private static Promise<Unit> logInfraCompatible(ArtifactDependency dependency, Version loadedVersion) {
        log.debug("Infra dependency {} compatible with loaded version {}",
                  dependency.asString(),
                  loadedVersion.withQualifier());

        return Promise.success(unit());
    }

    private static Promise<Unit> refuseInfraConflict(ArtifactDependency dependency,
                                                     Version loadedVersion,
                                                     SharedLibraryClassLoader sharedLibraryLoader,
                                                     String requester) {
        var key = dependency.groupId() + ":" + dependency.artifactId();
        var conflict = new SliceLoadingFailure.Fatal.SharedLoaderVersionConflict(requester,
                                                                                 dependency.asString(),
                                                                                 key
                                                                                + ":" + loadedVersion.withQualifier(),
                                                                                 sharedLibraryLoader.loadedBy(dependency.groupId(),
                                                                                                              dependency.artifactId())
                                                                                                    .or("<unrecorded>"));

        log.error(conflict.message());

        return conflict.promise();
    }

    record SharedDependencyResult(SliceClassLoader sliceClassLoader, List<URL> conflictingJarUrls) {}

    static Promise<SharedDependencyResult> processSharedDependencies(List<ArtifactDependency> dependencies,
                                                                     SharedLibraryClassLoader sharedLibraryLoader,
                                                                     Repository repository,
                                                                     URL sliceJarUrl,
                                                                     String requester) {
        var conflictUrls = new ArrayList<URL>();

        return processSequentially(dependencies, sharedLibraryLoader, repository, conflictUrls, requester).map(_ -> createSliceClassLoader(sharedLibraryLoader,
                                                                                                                                           sliceJarUrl,
                                                                                                                                           conflictUrls));
    }

    private static SharedDependencyResult createSliceClassLoader(SharedLibraryClassLoader sharedLibraryLoader,
                                                                 URL sliceJarUrl,
                                                                 List<URL> conflictUrls) {
        var urls = new ArrayList<URL>();

        urls.add(sliceJarUrl);
        urls.addAll(conflictUrls);
        var sliceLoader = new SliceClassLoader(urls.toArray(URL[]::new), sharedLibraryLoader);

        return new SharedDependencyResult(sliceLoader, List.copyOf(conflictUrls));
    }

    private static Promise<Unit> processSequentially(List<ArtifactDependency> dependencies,
                                                     SharedLibraryClassLoader sharedLibraryLoader,
                                                     Repository repository,
                                                     List<URL> conflictUrls,
                                                     String requester) {
        if (dependencies.isEmpty()) {
            return Promise.success(unit());
        }

        var dependency = dependencies.getFirst();
        var remaining = dependencies.subList(1, dependencies.size());

        return processSingleDependency(dependency, sharedLibraryLoader, repository, conflictUrls, requester).flatMap(_ -> processSequentially(remaining,
                                                                                                                                              sharedLibraryLoader,
                                                                                                                                              repository,
                                                                                                                                              conflictUrls,
                                                                                                                                              requester));
    }

    private static Promise<Unit> processSingleDependency(ArtifactDependency dependency,
                                                         SharedLibraryClassLoader sharedLibraryLoader,
                                                         Repository repository,
                                                         List<URL> conflictUrls,
                                                         String requester) {
        return sharedLibraryLoader.checkCompatibility(dependency)
                                  .fold(() -> loadIntoShared(dependency,
                                                             sharedLibraryLoader,
                                                             repository,
                                                             conflictUrls,
                                                             requester),
                                        result -> handleCompatibilityResult(dependency, result, repository, conflictUrls));
    }

    private static Promise<Unit> handleCompatibilityResult(ArtifactDependency dependency,
                                                           CompatibilityResult result,
                                                           Repository repository,
                                                           List<URL> conflictUrls) {
        return switch (result) {
            case CompatibilityResult.Compatible(var loadedVersion) -> logCompatibleDependency(dependency, loadedVersion);
            case CompatibilityResult.Conflict(var loadedVersion, _) -> handleConflictingDependency(dependency,
                                                                                                   loadedVersion,
                                                                                                   repository,
                                                                                                   conflictUrls);
            case CompatibilityResult.unused() -> Promise.success(unit());
        };
    }

    private static Promise<Unit> logCompatibleDependency(ArtifactDependency dependency, Version loadedVersion) {
        log.debug("Shared dependency {} compatible with loaded version {}",
                  dependency.asString(),
                  loadedVersion.withQualifier());

        return Promise.success(unit());
    }

    private static Promise<Unit> handleConflictingDependency(ArtifactDependency dependency,
                                                             Version loadedVersion,
                                                             Repository repository,
                                                             List<URL> conflictUrls) {
        log.info("Shared dependency {} conflicts with loaded version {}, will load into slice",
                 dependency.asString(),
                 loadedVersion.withQualifier());

        return loadConflictIntoSlice(dependency, repository, conflictUrls);
    }

    /// The runtime-provided fallback covers a FAILED LOCATE only. Before #1184 the `orElse` sat
    /// after the add as well, so a refusal from `addArtifact` would have been re-routed into a
    /// runtime-provided registration — a no-op success on a key that is already held. The locate
    /// is resolved to an `Option` first so the add's own verdict propagates.
    private static Promise<Unit> loadIntoShared(ArtifactDependency dependency,
                                                SharedLibraryClassLoader sharedLibraryLoader,
                                                Repository repository,
                                                List<URL> conflictUrls,
                                                String requester) {
        return locateOptional(dependency, repository).flatMap(located -> located.fold(() -> registerAsRuntimeProvided(dependency,
                                                                                                                      sharedLibraryLoader,
                                                                                                                      requester),
                                                                                      location -> addOrReevaluate(dependency,
                                                                                                                  sharedLibraryLoader,
                                                                                                                  repository,
                                                                                                                  conflictUrls,
                                                                                                                  location.url(),
                                                                                                                  requester)));
    }

    /// Same window as [#reevaluateInfraWindow], routed through the `[shared]` rule: a refused add is
    /// re-checked against what is now held, so a rule-Compatible request reuses it and a conflict
    /// takes the designed per-slice fallback instead of a failure (#1184 rev M1).
    private static Promise<Unit> addOrReevaluate(ArtifactDependency dependency,
                                                 SharedLibraryClassLoader sharedLibraryLoader,
                                                 Repository repository,
                                                 List<URL> conflictUrls,
                                                 URL url,
                                                 String requester) {
        return addToSharedLoader(dependency, sharedLibraryLoader, url, requester).fold(refusal -> sharedLibraryLoader.checkCompatibility(dependency)
                                                                                                                     .fold(refusal::promise,
                                                                                                                           result -> handleCompatibilityResult(dependency,
                                                                                                                                                               result,
                                                                                                                                                               repository,
                                                                                                                                                               conflictUrls)),
                                                                                       _ -> Promise.success(unit()));
    }

    private static Promise<Option<Location>> locateOptional(ArtifactDependency dependency, Repository repository) {
        return toArtifact(dependency).async()
                         .flatMap(repository::locate)
                         .map(Option::<Location> some)
                         .orElse(() -> Promise.success(Option.none()));
    }

    private static Promise<Unit> registerAsRuntimeProvided(ArtifactDependency dependency,
                                                           SharedLibraryClassLoader sharedLibraryLoader,
                                                           String requester) {
        var version = extractVersion(dependency.versionPattern());

        return sharedLibraryLoader.registerRuntimeProvided(dependency.groupId(),
                                                           dependency.artifactId(),
                                                           version,
                                                           requester)
                                  .onSuccessRun(() -> log.info("Shared dependency {} not found in repositories, registered as runtime-provided for {}",
                                                               dependency.asString(),
                                                               requester))
                                  .async();
    }

    private static Result<Unit> addToSharedLoader(ArtifactDependency dependency,
                                                  SharedLibraryClassLoader sharedLibraryLoader,
                                                  URL url,
                                                  String requester) {
        var version = extractVersion(dependency.versionPattern());

        return sharedLibraryLoader.addArtifact(dependency.groupId(),
                                               dependency.artifactId(),
                                               version,
                                               url,
                                               requester)
                                  .onSuccessRun(() -> log.debug("Loaded shared dependency {} into SharedLibraryClassLoader for {}",
                                                                dependency.asString(),
                                                                requester));
    }

    private static Promise<Unit> loadConflictIntoSlice(ArtifactDependency dependency,
                                                       Repository repository,
                                                       List<URL> conflictUrls) {
        return toArtifact(dependency).async()
                         .flatMap(repository::locate)
                         .map(location -> addConflictUrl(dependency,
                                                         conflictUrls,
                                                         location.url()));
    }

    private static Unit addConflictUrl(ArtifactDependency dependency, List<URL> conflictUrls, URL url) {
        conflictUrls.add(url);
        log.debug("Added conflicting dependency {} to slice classloader", dependency.asString());

        return unit();
    }

    private static Result<Artifact> toArtifact(ArtifactDependency dependency) {
        var versionStr = extractVersion(dependency.versionPattern()).withQualifier();

        return Artifact.artifact(dependency.groupId() + ":" + dependency.artifactId() + ":" + versionStr);
    }

    private static Version extractVersion(VersionPattern pattern) {
        return switch (pattern) {
            case VersionPattern.Exact(Version version) -> version;
            case VersionPattern.Range(Version from, _, _, _) -> from;
            case VersionPattern.Comparison(_, Version version) -> version;
            case VersionPattern.Tilde(Version version) -> version;
            case VersionPattern.Caret(Version version) -> version;
            case VersionPattern.unused _ -> Version.version("0.0.0").unwrap();
        };
    }
}
