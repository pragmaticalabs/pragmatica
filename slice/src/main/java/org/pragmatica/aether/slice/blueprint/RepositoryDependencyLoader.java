package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceClassLoader;
import org.pragmatica.aether.slice.SliceManifest;
import org.pragmatica.aether.slice.dependency.ArtifactMapper;
import org.pragmatica.aether.slice.dependency.DependencyDescriptor;
import org.pragmatica.aether.slice.dependency.SliceDependencies;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.net.URL;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Default DependencyLoader implementation using Repository.
 */
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
            return ExpanderError.ArtifactMismatch.cause(expected,
                                                        manifest.artifact())
                                .result();
        }
        return Result.success(manifest);
    }

    private static Result<Set<Artifact>> loadDependencies(SliceManifest.SliceManifestInfo manifest, URL jarUrl) {
        var classLoader = new SliceClassLoader(new URL[]{jarUrl}, RepositoryDependencyLoader.class.getClassLoader());
        return SliceDependencies.load(manifest.sliceClassName(),
                                      classLoader)
                                .flatMap(RepositoryDependencyLoader::convertToArtifacts);
    }

    private static Result<Set<Artifact>> convertToArtifacts(List<DependencyDescriptor> descriptors) {
        return Result.allOf(toArtifacts(descriptors))
                     .map(Set::copyOf);
    }

    private static Stream<Result<Artifact>> toArtifacts(List<DependencyDescriptor> descriptors) {
        return descriptors.stream()
                          .map(ArtifactMapper::toArtifact);
    }
}
