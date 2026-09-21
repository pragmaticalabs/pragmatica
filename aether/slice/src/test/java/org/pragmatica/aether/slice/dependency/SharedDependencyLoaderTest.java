// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice.dependency;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SharedLibraryClassLoader;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

/// #1184 — driven through `SharedDependencyLoader.processInfraDependencies` and
/// `processSharedDependencies`, the production entry points, with a repository that resolves every
/// artifact to a URL carrying its version. `addURL` never opens the jar, so no real jar is needed
/// to observe which version the shared loader holds and serves.
class SharedDependencyLoaderTest {
    private static final Cause NOT_FOUND = new SliceLoadingFailure.Intermittent.ArtifactNotFound("org.example:lib");

    private final SharedLibraryClassLoader sharedLoader = new SharedLibraryClassLoader(SharedDependencyLoaderTest.class.getClassLoader());
    private final Repository repository = artifact -> Promise.success(new Location(artifact, jarUrlFor(artifact)));

    @AfterEach
    void closeLoader() throws Exception {
        sharedLoader.close();
    }

    /// RED on the unmodified base: the second load succeeded, the loader kept 1.0.0 and served only
    /// the 1.0.0 jar, and slice-b silently ran against a version it did not declare.
    @Test
    void infraVersionConflict_failsTheSecondSliceLoad_namingBothVersionsAndBothRequesters() {
        loadInfra("slice-a", "org.example:lib:1.0.0").await().onFailureRun(Assertions::fail);

        var second = loadInfra("slice-b", "org.example:lib:2.0.0").await();

        assertConflict(second,
                       "slice slice-b requires org.example:lib:2.0.0 but org.example:lib:1.0.0 is already loaded by slice-a");
        assertLoaderHoldsOnly("1.0.0", "slice-a");
    }

    /// The rule is `required.matches(loaded)`, and `Exact` requires equality — an OLDER request
    /// against a newer loaded version is a conflict too; there is no "newer wins" policy.
    @Test
    void infraVersionConflict_olderExactRequest_alsoFails() {
        loadInfra("slice-a", "org.example:lib:2.0.0").await().onFailureRun(Assertions::fail);

        var second = loadInfra("slice-b", "org.example:lib:1.0.0").await();

        assertConflict(second,
                       "slice slice-b requires org.example:lib:1.0.0 but org.example:lib:2.0.0 is already loaded by slice-a");
        assertLoaderHoldsOnly("2.0.0", "slice-a");
    }

    /// The conflict is Fatal: a retry cannot change what the shared loader holds, so the cluster
    /// must not spend retry budget on it (#930's typing obligation).
    @Test
    void infraVersionConflict_isTypedFatal() {
        loadInfra("slice-a", "org.example:lib:1.0.0").await().onFailureRun(Assertions::fail);

        loadInfra("slice-b", "org.example:lib:2.0.0").await().onSuccessRun(Assertions::fail).onFailure(cause -> {
            assertThat(cause).isInstanceOf(SliceLoadingFailure.Fatal.SharedLoaderVersionConflict.class);
            assertThat(SliceLoadingFailure.classify(cause, SliceLoadingFailure.Unrecognised.RETRY).isFatal()).isTrue();
        });
    }

    /// `Compatible`: the loaded version satisfies the second slice's pattern (`^1.0.0` accepts
    /// 1.2.0), so the slice reuses it — nothing is added and the load succeeds, as before.
    @Test
    void infraCompatibleRequest_reusesTheLoadedVersion() {
        loadInfra("slice-a", "org.example:lib:1.2.0").await().onFailureRun(Assertions::fail);

        loadInfra("slice-b", "org.example:lib:^1.0.0").await().onFailureRun(Assertions::fail);

        assertLoaderHoldsOnly("1.2.0", "slice-a");
    }

    /// The same artifact at the SAME exact version from two slices is not a conflict.
    @Test
    void infraSameVersionTwice_isANoOp() {
        loadInfra("slice-a", "org.example:lib:1.0.0").await().onFailureRun(Assertions::fail);

        loadInfra("slice-b", "org.example:lib:1.0.0").await().onFailureRun(Assertions::fail);

        assertLoaderHoldsOnly("1.0.0", "slice-a");
    }

    /// The first requester is recorded by the `[shared]` path too, so an `[infra]` conflict against
    /// an artifact a `[shared]` declaration put there names that slice.
    @Test
    void infraConflictAgainstASharedLoad_namesTheSharedRequester() throws Exception {
        loadShared("slice-a", "org.example:lib:1.0.0").await().onFailureRun(Assertions::fail);

        var second = loadInfra("slice-b", "org.example:lib:2.0.0").await();

        assertConflict(second,
                       "slice slice-b requires org.example:lib:2.0.0 but org.example:lib:1.0.0 is already loaded by slice-a");
    }

    /// The `[shared]` rule is unchanged by #1184: a conflicting version goes into the slice's own
    /// loader (`conflictingJarUrls`), the shared loader keeps the first, and the load succeeds.
    @Test
    void sharedVersionConflict_stillLoadsIntoTheSliceLoader() throws Exception {
        loadShared("slice-a", "org.example:lib:1.0.0").await().onFailureRun(Assertions::fail);

        loadShared("slice-b", "org.example:lib:2.0.0").await().onFailureRun(Assertions::fail).onSuccess(result -> {
            assertThat(result.conflictingJarUrls()).containsExactly(jarUrl("org.example:lib:2.0.0"));
        });
        assertLoaderHoldsOnly("1.0.0", "slice-a");
    }

    /// A `[shared]` artifact found in no repository is registered as runtime-provided, and that
    /// registration records the requester, so a later `[infra]` conflict against it is attributable.
    @Test
    void runtimeProvidedRegistration_recordsTheRequester() throws Exception {
        Repository empty = _ -> NOT_FOUND.promise();

        SharedDependencyLoader.processSharedDependencies(List.of(dependency("org.example:lib:1.0.0")),
                                                         sharedLoader,
                                                         empty,
                                                         jarUrl("org.example:slice-a:1.0.0"),
                                                         "slice-a")
                              .await()
                              .onFailureRun(Assertions::fail);

        assertThat(sharedLoader.loadedBy("org.example", "lib").unwrap()).isEqualTo("slice-a");
        assertThat(sharedLoader.getURLs()).isEmpty();
    }

    /// The check-to-add window: `checkCompatibility` sees nothing, the asynchronous locate runs, and
    /// by the time `addArtifact` is reached another slice has put a different version there. This
    /// repository performs that interleaving deterministically. Before #1184 the add logged a WARN,
    /// returned success and slice-b ran against 1.0.0 — the same silent downgrade by a second route.
    @Test
    void infraConflictInsideTheCheckToAddWindow_stillFailsLoudly() {
        var second = SharedDependencyLoader.processInfraDependencies(List.of(dependency("org.example:lib:2.0.0")),
                                                                     sharedLoader,
                                                                     racingRepository("org.example:lib:1.0.0", "slice-a"),
                                                                     "slice-b")
                                           .await();

        assertConflict(second,
                       "slice slice-b requires org.example:lib:2.0.0 but org.example:lib:1.0.0 is already loaded by slice-a");
        assertLoaderHoldsOnly("1.0.0", "slice-a");
    }

    /// Same window on the `[shared]` path. The designed `[shared]` fallback (load the conflicting
    /// version into the slice's loader) is decided by `checkCompatibility`, which ran before the race;
    /// inside the window the add's refusal is now propagated as a loud failure. Before #1184 it was
    /// swallowed twice: once by the discarded `Result`, and once more by the `orElse` after the add,
    /// which would have turned any refusal into a runtime-provided registration — a no-op success.
    @Test
    void sharedConflictInsideTheCheckToAddWindow_failsLoudly_ratherThanRegisteringRuntimeProvided() {
        var second = SharedDependencyLoader.processSharedDependencies(List.of(dependency("org.example:lib:2.0.0")),
                                                                      sharedLoader,
                                                                      racingRepository("org.example:lib:1.0.0", "slice-a"),
                                                                      jarUrl("org.example:slice-b:1.0.0"),
                                                                      "slice-b")
                                           .await();

        second.onSuccessRun(() -> Assertions.fail("accepted although a different version won the window; loader holds "
                                                  + sharedLoader.getLoadedArtifacts()))
              .onFailure(cause -> assertThat(cause.message()).contains("slice slice-b requires org.example:lib:2.0.0 but org.example:lib:1.0.0 is already loaded by slice-a"));
        assertLoaderHoldsOnly("1.0.0", "slice-a");
    }

    /// Resolves the located artifact normally, but first lets `competitor` win the shared loader
    /// with `competing` — the interleaving a concurrent slice load produces.
    private Repository racingRepository(String competing, String competitor) {
        return artifact -> {
            var dep = dependency(competing);
            var version = ((VersionPattern.Exact) dep.versionPattern()).version();

            sharedLoader.addArtifact(dep.groupId(), dep.artifactId(), version, jarUrl(competing), competitor)
                        .onFailureRun(Assertions::fail);

            return repository.locate(artifact);
        };
    }

    private void assertConflict(Result<Unit> outcome, String expectedNaming) {
        outcome.onSuccessRun(() -> Assertions.fail("accepted although a different version is loaded; loader holds "
                                                   + sharedLoader.getLoadedArtifacts()
                                                   + " and serves " + List.of(sharedLoader.getURLs())))
               .onFailure(cause -> assertThat(cause.message()).contains(expectedNaming));
    }

    private void assertLoaderHoldsOnly(String version, String requester) {
        assertThat(sharedLoader.getLoadedVersion("org.example", "lib").unwrap().withQualifier()).isEqualTo(version);
        assertThat(sharedLoader.loadedBy("org.example", "lib").unwrap()).isEqualTo(requester);
        assertThat(sharedLoader.getURLs()).containsExactly(jarUrl("org.example:lib:" + version));
    }

    private Promise<Unit> loadInfra(String requester, String dependency) {
        return SharedDependencyLoader.processInfraDependencies(List.of(dependency(dependency)), sharedLoader, repository, requester);
    }

    private Promise<SharedDependencyLoader.SharedDependencyResult> loadShared(String requester, String dependency) {
        return SharedDependencyLoader.processSharedDependencies(List.of(dependency(dependency)),
                                                                sharedLoader,
                                                                repository,
                                                                jarUrl("org.example:" + requester + ":1.0.0"),
                                                                requester);
    }

    private static ArtifactDependency dependency(String line) {
        return ArtifactDependency.artifactDependency(line).unwrap();
    }

    private static URL jarUrlFor(Artifact artifact) {
        return jarUrl(artifact.asString());
    }

    private static URL jarUrl(String coordinates) {
        try {
            return new URL("file:///repo/" + coordinates.replace(":", "-") + ".jar");
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }
}
