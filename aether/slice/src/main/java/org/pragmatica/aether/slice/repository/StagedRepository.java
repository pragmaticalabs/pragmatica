// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.repository;

import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Promise;


/// A router over two repositories, fail-closed for the module's own coordinates (#3021).
///
/// A coordinate in `moduleSet` is located through `staged` ALONE: a miss there is the answer, never a reason to ask
/// `other`, so a stale or foreign jar under the same coordinate in a Maven repository can never stand in for the
/// module's packaged slice. Every other coordinate goes to `other`. Both `locate` forms route the same way: a
/// classifier lookup that bypassed the router would reopen the fall-through for exactly the artifacts it protects.
public interface StagedRepository extends Repository {
    static StagedRepository stagedRepository(Set<Artifact> moduleSet, Repository staged, Repository other) {
        record router(Set<Artifact> moduleSet, Repository staged, Repository other) implements StagedRepository {
            @Override
            public Promise<Location> locate(Artifact artifact) {
                return route(artifact).locate(artifact);
            }

            @Override
            public Promise<Location> locate(Artifact artifact, String classifier) {
                return route(artifact).locate(artifact, classifier);
            }

            private Repository route(Artifact artifact) {
                return moduleSet.contains(artifact)
                       ? staged
                       : other;
            }
        }

        return new router(Set.copyOf(moduleSet), staged, other);
    }
}
