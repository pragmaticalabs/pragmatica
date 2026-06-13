// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.repository.maven;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.io.TimeSpan;

import java.nio.file.Path;

import static org.pragmatica.lang.io.FileOps.exists;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.aether.slice.repository.Location.location;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-ZONE-02"})
public interface LocalRepository extends Repository {
    static LocalRepository localRepository() {
        return localRepository(Path.of(MavenLocalRepoLocator.findLocalRepository()));
    }

    static LocalRepository localRepository(Path localRepo) {
        return localRepository(localRepo, timeSpan(30).seconds());
    }

    static LocalRepository localRepository(Path localRepo, TimeSpan locateTimeout) {
        record repository(Path localRepo, TimeSpan locateTimeout) implements LocalRepository {
            @Override
            public Promise<Location> locate(Artifact artifact) {
                return resolveLocation(artifact, "").async()
                                      .timeout(locateTimeout);
            }

            @Override
            public Promise<Location> locate(Artifact artifact, String classifier) {
                var suffix = classifier.isEmpty()
                             ? ""
                             : "-" + classifier;

                return resolveLocation(artifact, suffix).async()
                                      .timeout(locateTimeout);
            }

            private Result<Location> resolveLocation(Artifact artifact, String classifier) {
                var jarPath = resolvePath(artifact, classifier);

                if (!exists(jarPath)) {
                    return ARTIFACT_NOT_FOUND.apply(artifact.asString() + " at " + jarPath).result();
                }

                return Result.lift(Causes::fromThrowable,
                                   () -> jarPath.toUri()
                                                .toURL())
                             .flatMap(url -> location(artifact, url));
            }

            private Path resolvePath(Artifact artifact, String classifier) {
                var version = artifact.version();
                var artifactId = artifact.artifactId().id();

                return localRepo.resolve(artifact.groupId().id().replace('.', '/'))
                                .resolve(artifactId)
                                .resolve(version.withQualifier())
                                .resolve(artifactId + "-" + version.withQualifier() + classifier + ".jar");
            }

            private static final Fn1<Cause, String> ARTIFACT_NOT_FOUND = Causes.forOneValue("Artifact not found in local repository: %s");
        }

        return new repository(localRepo, locateTimeout);
    }
}
