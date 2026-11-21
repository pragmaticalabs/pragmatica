// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.Collection;
import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.RepositoryType;
import org.pragmatica.aether.repository.RepositoryFactory;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// The early, readable refusal before any port is claimed (#3021): every coordinate the app needs is located through the SAME
/// router the node's loader uses ([RepositoryFactory#stagedRepository]), so the path rule cannot disagree with the runtime.
///
/// [#bypassed] is the probe-1 seam: it lets a test skip this refusal to prove that the node's own load still fails closed
/// (`ArtifactNotFound` from the router) when the pre-check is not there.
public interface StagedPreCheck {
    /// Succeeds when every coordinate locates through the router built from `staged`; otherwise fails with
    /// [EmberAppNotPackaged] for the first one that does not, naming `stagedSliceArtifacts`.
    Promise<Unit> check(RepositoryType.Staged staged,
                        Collection<Artifact> coordinates,
                        List<String> stagedSliceArtifacts);

    static StagedPreCheck stagedPreCheck() {
        return (staged, coordinates, found) -> allLocated(RepositoryFactory.stagedRepository(staged), coordinates, found);
    }

    /// TEST SEAM (probe 1): accepts everything.
    static StagedPreCheck bypassed() {
        return (_, _, _) -> Promise.unitPromise();
    }

    private static Promise<Unit> allLocated(Repository router, Collection<Artifact> coordinates, List<String> found) {
        return Promise.allOf(coordinates.stream().map(coordinate -> located(router, coordinate, found)).toList()).flatMap(StagedPreCheck::firstFailure);
    }

    private static Promise<Unit> located(Repository router, Artifact coordinate, List<String> found) {
        return router.locate(coordinate)
                     .<Unit> map(_ -> Unit.unit())
                     .mapError(_ -> EmberAppNotPackaged.emberAppNotPackaged(coordinate.asString(),
                                                                            found));
    }

    private static Promise<Unit> firstFailure(List<Result<Unit>> results) {
        return results.stream()
                      .filter(Result::isFailure)
                      .findFirst()
                      .orElseGet(Result::unitResult)
                      .async();
    }
}
