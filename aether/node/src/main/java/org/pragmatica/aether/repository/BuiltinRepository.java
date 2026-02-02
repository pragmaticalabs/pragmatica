package org.pragmatica.aether.repository;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.infra.artifact.ArtifactStore;
import org.pragmatica.aether.infra.artifact.ArtifactStore.ResolvedArtifact;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;

import java.nio.file.Files;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.slice.repository.Location.location;

/**
 * Repository implementation that uses ArtifactStore for in-process artifact resolution.
 * Resolved artifacts are written to temporary files for class loading.
 * Integrity verification is performed by ArtifactStore during resolution.
 */
public interface BuiltinRepository extends Repository {
    Logger log = LoggerFactory.getLogger(BuiltinRepository.class);

    static BuiltinRepository builtinRepository(ArtifactStore store) {
        return artifact -> store.resolveWithMetadata(artifact)
                                .flatMap(resolved -> writeToTempFile(artifact, resolved));
    }

    private static Promise<Location> writeToTempFile(Artifact artifact, ResolvedArtifact resolved) {
        return Promise.lift(cause -> new RepositoryError.WriteFailed(artifact, cause),
                            () -> {
                                var tempFile = Files.createTempFile("aether-" + artifact.artifactId()
                                                                                       .id() + "-",
                                                                    ".jar");
                                Files.write(tempFile,
                                            resolved.content());
                                log.info("Resolved artifact {} (SHA1={}, {} bytes)",
                                         artifact.asString(),
                                         resolved.metadata()
                                                 .sha1(),
                                         resolved.metadata()
                                                 .size());
                                return tempFile.toUri()
                                               .toURL();
                            })
                      .flatMap(url -> location(artifact, url).async());
    }

    sealed interface RepositoryError extends Cause {
        record WriteFailed(Artifact artifact, Throwable cause) implements RepositoryError {
            @Override
            public String message() {
                return "Failed to write artifact " + artifact.asString() + " to temp file: " + cause.getMessage();
            }
        }
    }
}
