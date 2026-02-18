package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceClassLoader;
import org.pragmatica.aether.slice.SliceManifest;
import org.pragmatica.aether.slice.dependency.ArtifactDependency;
import org.pragmatica.aether.slice.dependency.ArtifactMapper;
import org.pragmatica.aether.slice.dependency.DependencyFile;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.net.URL;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.pragmatica.lang.Result.success;

/// Default DependencyLoader implementation using Repository.
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-NEST-01", "JBCT-ZONE-02", "JBCT-ZONE-03"})
public interface RepositoryDependencyLoader {
    static DependencyLoader repositoryDependencyLoader(Repository repository) {
        return artifact -> repository.locate(artifact)
                                     .flatMap(location -> loadDependenciesFromJar(artifact, location));
    }

    private static Promise<Set<Artifact>> loadDependenciesFromJar(Artifact artifact, Location location) {
        return SliceManifest.read(location.url())
                            .flatMap(manifest -> validateManifest(artifact, manifest))
                            .flatMap(manifest -> loadDependencies(manifest,
                                                                  location.url()))
                            .async();
    }

    private static Result<SliceManifest.SliceManifestInfo> validateManifest(Artifact expected,
                                                                            SliceManifest.SliceManifestInfo manifest) {
        if (!manifest.artifact()
                     .equals(expected)) {
            return ExpanderError.ArtifactMismatch.artifactMismatch(expected,
                                                                   manifest.artifact())
                                .result();
        }
        return success(manifest);
    }

    private static Result<Set<Artifact>> loadDependencies(SliceManifest.SliceManifestInfo manifest, URL jarUrl) {
        var classLoader = new SliceClassLoader(new URL[]{jarUrl}, RepositoryDependencyLoader.class.getClassLoader());
        return DependencyFile.load(manifest.sliceClassName(),
                                   classLoader)
                             .flatMap(RepositoryDependencyLoader::convertToArtifacts);
    }

    private static Result<Set<Artifact>> convertToArtifacts(DependencyFile dependencyFile) {
        return Result.allOf(toArtifacts(dependencyFile.slices()))
                     .map(Set::copyOf);
    }

    private static Stream<Result<Artifact>> toArtifacts(List<ArtifactDependency> dependencies) {
        return dependencies.stream()
                           .map(ArtifactMapper::toArtifact);
    }
}
