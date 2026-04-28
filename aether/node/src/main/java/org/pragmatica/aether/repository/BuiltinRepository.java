// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.repository;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.resource.artifact.ArtifactStore.ResolvedArtifact;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.FileOps.createTempFile;
import static org.pragmatica.lang.io.FileOps.writeBytes;
import static org.pragmatica.aether.slice.repository.Location.location;


public interface BuiltinRepository extends Repository {
    Logger log = LoggerFactory.getLogger(BuiltinRepository.class);

    static BuiltinRepository builtinRepository(ArtifactStore store) {
        return artifact -> store.resolveWithMetadata(artifact).flatMap(resolved -> writeToTempFile(artifact, resolved));
    }

    private static Promise<Location> writeToTempFile(Artifact artifact, ResolvedArtifact resolved) {
        return createTempFile("aether-" + artifact.artifactId().id() + "-",
                              ".jar").flatMap(tempFile -> writeBytes(tempFile,
                                                                     resolved.content()).map(_ -> tempFile))
                             .mapError(e -> new RepositoryError.WriteFailed(artifact,
                                                                            new RuntimeException(e.message())))
                             .async()
                             .onSuccess(_ -> log.info("Resolved artifact {} (SHA1={}, {} bytes)",
                                                      artifact.asString(),
                                                      resolved.metadata().sha1(),
                                                      resolved.metadata().size()))
                             .flatMap(tempFile -> Promise.lift(cause -> new RepositoryError.WriteFailed(artifact, cause),
                                                               () -> tempFile.toUri().toURL()))
                             .flatMap(url -> location(artifact, url).async());
    }

    sealed interface RepositoryError extends Cause {
        record WriteFailed(Artifact artifact, Throwable cause) implements RepositoryError {
            @Override public String message() {
                return "Failed to write artifact " + artifact.asString() + " to temp file: " + cause.getMessage();
            }
        }
    }
}
