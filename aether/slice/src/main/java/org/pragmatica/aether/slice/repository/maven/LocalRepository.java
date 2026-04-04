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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.aether.slice.repository.Location.location;


/// Repository implementation for Maven local repository (~/.m2/repository).
///
/// Resolves artifacts to their JAR locations following Maven conventions:
/// ```
/// {localRepo}/{groupId as path}/{artifactId}/{version}/{artifactId}-{version}.jar
/// Example: ~/.m2/repository/org/example/my-slice/1.0.0/my-slice-1.0.0.jar
/// ```
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-ZONE-02"}) public interface LocalRepository extends Repository {
    static LocalRepository localRepository() {
        return localRepository(Path.of(MavenLocalRepoLocator.findLocalRepository()));
    }

    static LocalRepository localRepository(Path localRepo) {
        return localRepository(localRepo, timeSpan(30).seconds());
    }

    static LocalRepository localRepository(Path localRepo, TimeSpan locateTimeout) {
        record repository(Path localRepo, TimeSpan locateTimeout) implements LocalRepository {
            @Override public Promise<Location> locate(Artifact artifact) {
                return resolveLocation(artifact, "").async().timeout(locateTimeout);
            }

            @Override public Promise<Location> locate(Artifact artifact, String classifier) {
                var suffix = classifier.isEmpty()
                            ? ""
                            : "-" + classifier;
                return resolveLocation(artifact, suffix).async().timeout(locateTimeout);
            }

            private Result<Location> resolveLocation(Artifact artifact, String classifier) {
                var jarPath = resolvePath(artifact, classifier);
                if (!Files.exists(jarPath)) {return ARTIFACT_NOT_FOUND.apply(artifact.asString() + " at " + jarPath)
                                                                            .result();}
                return Result.lift(Causes::fromThrowable,
                                   () -> jarPath.toUri().toURL())
                .flatMap(url -> location(artifact, url));
            }

            private Path resolvePath(Artifact artifact, String classifier) {
                var version = artifact.version();
                var artifactId = artifact.artifactId().id();
                return localRepo.resolve(artifact.groupId().id()
                                                         .replace('.', '/')).resolve(artifactId)
                                        .resolve(version.withQualifier())
                                        .resolve(artifactId + "-" + version.withQualifier() + classifier + ".jar");
            }

            private static final Fn1<Cause, String> ARTIFACT_NOT_FOUND = Causes.forOneValue("Artifact not found in local repository: %s");
        }
        return new repository(localRepo, locateTimeout);
    }
}
