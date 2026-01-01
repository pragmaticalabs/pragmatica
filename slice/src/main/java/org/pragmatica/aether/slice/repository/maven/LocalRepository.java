package org.pragmatica.aether.slice.repository.maven;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.pragmatica.aether.slice.repository.Location.location;

/**
 * Repository implementation for Maven local repository (~/.m2/repository).
 * <p>
 * Resolves artifacts to their JAR locations following Maven conventions:
 * <pre>
 * {localRepo}/{groupId as path}/{artifactId}/{version}/{artifactId}-{version}.jar
 * Example: ~/.m2/repository/org/example/my-slice/1.0.0/my-slice-1.0.0.jar
 * </pre>
 */
public interface LocalRepository extends Repository {
    /**
     * Create a LocalRepository using the detected Maven local repository location.
     * Detection order: maven.repo.local property → user settings.xml → global settings.xml → default ~/.m2/repository
     */
    static LocalRepository localRepository() {
        return localRepository(Path.of(MavenLocalRepoLocator.findLocalRepository()));
    }

    /**
     * Create a LocalRepository at a specific path (useful for testing).
     */
    static LocalRepository localRepository(Path localRepo) {
        record repository(Path localRepo) implements LocalRepository {
            @Override
            public Promise<Location> locate(Artifact artifact) {
                return resolveLocation(artifact)
                       .async();
            }

            private Result<Location> resolveLocation(Artifact artifact) {
                var jarPath = resolvePath(artifact, "jar");
                if (!Files.exists(jarPath)) {
                    return ARTIFACT_NOT_FOUND.apply(artifact.asString() + " at " + jarPath)
                                             .result();
                }
                return Result.lift(Causes::fromThrowable,
                                   () -> jarPath.toUri()
                                                .toURL())
                             .map(url -> location(artifact, url));
            }

            private Path resolvePath(Artifact artifact, String packaging) {
                var version = artifact.version();
                var artifactId = artifact.artifactId()
                                         .id();
                return localRepo.resolve(artifact.groupId()
                                                 .id()
                                                 .replace('.', '/'))
                                .resolve(artifactId)
                                .resolve(version.bareVersion())
                                .resolve(artifactId + "-" + version.withQualifier() + "." + packaging);
            }

            private static final Fn1<Cause, String>ARTIFACT_NOT_FOUND = Causes.forOneValue(
            "Artifact not found in local repository: %s");
        }
        return new repository(localRepo);
    }
}
