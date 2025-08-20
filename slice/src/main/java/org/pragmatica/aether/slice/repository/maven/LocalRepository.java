package org.pragmatica.aether.slice.repository.maven;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Path;

import static org.pragmatica.aether.slice.repository.Location.location;

public interface LocalRepository extends Repository {
    static LocalRepository localRepository() {
        record repository(Path localRepo) implements LocalRepository {

            @Override
            public Promise<Location> locate(Artifact artifact) {
                return Promise.promise(() -> resolveLocation(artifact));
            }

            private Result<Location> resolveLocation(Artifact artifact) {
                return Result.lift(Causes::fromThrowable,
                                   () -> resolvePath(artifact, "jar")
                                           .toUri()
                                           .toURL())
                             .map(url -> location(artifact, url));
            }

            private Path resolvePath(Artifact artifact, String packaging) {
                var version = artifact.version();
                var artifactId = artifact.artifactId().id();
                return localRepo
                        .resolve(artifact.groupId().id().replace('.', '/'))
                        .resolve(artifactId)
                        .resolve(version.bareVersion())
                        .resolve(artifactId + "-" + version.withQualifier() + "." + packaging);
            }
        }
        return new repository(Path.of(MavenLocalRepoLocator.findLocalRepository()));
    }

    Promise<Location> locate(Artifact artifact);
}
