package org.pragmatica.aether.slice.dependency;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.SharedLibraryClassLoader;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.SliceBridgeImpl;
import org.pragmatica.aether.slice.SliceClassLoader;
import org.pragmatica.aether.slice.SliceManifest;
import org.pragmatica.aether.slice.SliceManifest.SliceManifestInfo;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.aether.slice.serialization.FurySerializerFactoryProvider;
import org.pragmatica.aether.slice.serialization.SerializerFactory;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates dependency resolution for slices.
 * <p>
 * Resolution process:
 * 1. Check registry for already-loaded slice
 * 2. Locate JAR via repository
 * 3. Read manifest to get slice class name and verify artifact
 * 4. Create ClassLoader with JAR URL
 * 5. Load dependencies from META-INF/dependencies/ file
 * 6. Build dependency graph and check for cycles
 * 7. Recursively resolve dependencies (depth-first)
 * 8. Instantiate slice via factory method
 * 9. Register in registry
 * <p>
 * Thread-safe: Uses SliceRegistry for synchronization.
 */
public interface DependencyResolver {
    Logger log = LoggerFactory.getLogger(DependencyResolver.class);

    /**
     * Resolve a slice using SharedLibraryClassLoader for shared dependencies.
     *
     * @param artifact            The slice artifact to resolve
     * @param repository          Repository to locate artifacts
     * @param registry            Registry to track loaded slices
     * @param sharedLibraryLoader ClassLoader for shared dependencies
     * @return Promise of resolved slice
     */
    static Promise<Slice> resolve(Artifact artifact,
                                  Repository repository,
                                  SliceRegistry registry,
                                  SharedLibraryClassLoader sharedLibraryLoader) {
        return registry.lookup(artifact)
                       .fold(() -> resolveWithSharedLoader(artifact,
                                                           repository,
                                                           registry,
                                                           sharedLibraryLoader,
                                                           new HashSet<>()),
                             Promise::success);
    }

    /**
     * Resolve a slice and return a SliceBridge for isolated communication.
     * <p>
     * This method resolves the slice and wraps it in a SliceBridge that handles
     * serialization/deserialization at the boundary using byte arrays.
     *
     * @param artifact            The slice artifact to resolve
     * @param repository          Repository to locate artifacts
     * @param registry            Registry to track loaded slices
     * @param sharedLibraryLoader ClassLoader for shared dependencies
     * @param serializerFactory   Factory for serialization
     * @return Promise of resolved SliceBridge
     */
    static Promise<SliceBridge> resolveBridge(Artifact artifact,
                                              Repository repository,
                                              SliceRegistry registry,
                                              SharedLibraryClassLoader sharedLibraryLoader,
                                              SerializerFactory serializerFactory) {
        return resolve(artifact, repository, registry, sharedLibraryLoader)
               .map(slice -> SliceBridgeImpl.sliceBridge(artifact, slice, serializerFactory));
    }

    /**
     * Resolve a slice and return a SliceBridge using default Fury serialization.
     *
     * @param artifact            The slice artifact to resolve
     * @param repository          Repository to locate artifacts
     * @param registry            Registry to track loaded slices
     * @param sharedLibraryLoader ClassLoader for shared dependencies
     * @return Promise of resolved SliceBridge
     */
    static Promise<SliceBridge> resolveBridge(Artifact artifact,
                                              Repository repository,
                                              SliceRegistry registry,
                                              SharedLibraryClassLoader sharedLibraryLoader) {
        var serializerFactory = FurySerializerFactoryProvider.furySerializerFactoryProvider()
                                                             .createFactory(List.of());
        return resolveBridge(artifact, repository, registry, sharedLibraryLoader, serializerFactory);
    }

    private static Promise<Slice> resolveWithSharedLoader(Artifact artifact,
                                                          Repository repository,
                                                          SliceRegistry registry,
                                                          SharedLibraryClassLoader sharedLibraryLoader,
                                                          Set<String> resolutionPath) {
        var artifactKey = artifact.asString();
        if (resolutionPath.contains(artifactKey)) {
            return circularDependencyDetected(artifactKey)
                   .promise();
        }
        resolutionPath.add(artifactKey);
        return repository.locate(artifact)
                         .flatMap(location -> loadFromLocationWithShared(artifact,
                                                                         location,
                                                                         repository,
                                                                         registry,
                                                                         sharedLibraryLoader,
                                                                         resolutionPath))
                         .onSuccess(_ -> resolutionPath.remove(artifactKey))
                         .onFailure(_ -> resolutionPath.remove(artifactKey));
    }

    private static Promise<Slice> loadFromLocationWithShared(Artifact artifact,
                                                             Location location,
                                                             Repository repository,
                                                             SliceRegistry registry,
                                                             SharedLibraryClassLoader sharedLibraryLoader,
                                                             Set<String> resolutionPath) {
        return SliceManifest.read(location.url())
                            .onFailure(cause -> log.error("Invalid slice JAR {}: {}",
                                                          artifact,
                                                          cause.message()))
                            .async()
                            .flatMap(manifest -> validateAndLoadWithShared(artifact,
                                                                           location,
                                                                           manifest,
                                                                           repository,
                                                                           registry,
                                                                           sharedLibraryLoader,
                                                                           resolutionPath));
    }

    private static Promise<Slice> validateAndLoadWithShared(Artifact artifact,
                                                            Location location,
                                                            SliceManifestInfo manifest,
                                                            Repository repository,
                                                            SliceRegistry registry,
                                                            SharedLibraryClassLoader sharedLibraryLoader,
                                                            Set<String> resolutionPath) {
        if (!manifest.artifact()
                     .equals(artifact)) {
            log.error("Artifact mismatch: requested {} but JAR declares {}", artifact, manifest.artifact());
            return artifactMismatch(artifact,
                                    manifest.artifact())
                   .promise();
        }
        // Load dependency file to get shared and slice dependencies
        return DependencyFile.load(manifest.sliceClassName(),
                                   createTempLoader(location.url(),
                                                    sharedLibraryLoader))
                             .async()
                             .flatMap(depFile -> processSharedAndLoadSlice(
        manifest, location, depFile, repository, registry, sharedLibraryLoader, resolutionPath));
    }

    private static SliceClassLoader createTempLoader(URL jarUrl, SharedLibraryClassLoader parent) {
        return new SliceClassLoader(new URL[]{jarUrl}, parent);
    }

    private static Promise<Slice> processSharedAndLoadSlice(SliceManifestInfo manifest,
                                                            Location location,
                                                            DependencyFile depFile,
                                                            Repository repository,
                                                            SliceRegistry registry,
                                                            SharedLibraryClassLoader sharedLibraryLoader,
                                                            Set<String> resolutionPath) {
        // Process API dependencies first (loaded into SharedLibraryClassLoader like [shared])
        // API JARs contain typed interfaces used by generated proxies
        return SharedDependencyLoader.processApiDependencies(depFile.api(),
                                                             sharedLibraryLoader,
                                                             repository)
                                     .flatMap(_ -> SharedDependencyLoader.processSharedDependencies(
        depFile.shared(),
        sharedLibraryLoader,
        repository,
        location.url()))
                                     .flatMap(sharedResult -> {
                                                  // Now load the slice class and resolve slice dependencies
        return loadClass(manifest.sliceClassName(),
                         sharedResult.sliceClassLoader())
               .flatMap(sliceClass -> resolveSliceDependencies(
        manifest.artifact(),
        sliceClass,
        depFile.slices(),
        sharedResult.sliceClassLoader(),
        repository,
        registry,
        sharedLibraryLoader,
        resolutionPath));
                                              });
    }

    private static Promise<Slice> resolveSliceDependencies(Artifact artifact,
                                                           Class< ? > sliceClass,
                                                           List<ArtifactDependency> sliceDeps,
                                                           ClassLoader classLoader,
                                                           Repository repository,
                                                           SliceRegistry registry,
                                                           SharedLibraryClassLoader sharedLibraryLoader,
                                                           Set<String> resolutionPath) {
        if (sliceDeps.isEmpty()) {
            return createSliceFromClass(sliceClass,
                                        List.of(),
                                        List.of())
                   .async()
                   .flatMap(slice -> registerSlice(artifact, slice, registry));
        }
        return resolveArtifactDependenciesSequentially(sliceDeps,
                                                       repository,
                                                       registry,
                                                       sharedLibraryLoader,
                                                       resolutionPath,
                                                       List.of())
               .flatMap(resolvedSlices -> createSliceFromClass(sliceClass, resolvedSlices, sliceDeps)
                                          .async())
               .flatMap(slice -> registerSlice(artifact, slice, registry));
    }

    private static Promise<List<Slice>> resolveArtifactDependenciesSequentially(List<ArtifactDependency> dependencies,
                                                                                Repository repository,
                                                                                SliceRegistry registry,
                                                                                SharedLibraryClassLoader sharedLibraryLoader,
                                                                                Set<String> resolutionPath,
                                                                                List<Slice> accumulated) {
        if (dependencies.isEmpty()) {
            return Promise.success(accumulated);
        }
        var dep = dependencies.getFirst();
        var remaining = dependencies.subList(1, dependencies.size());
        return resolveArtifactDependency(dep, repository, registry, sharedLibraryLoader, resolutionPath)
               .flatMap(slice -> resolveArtifactDependenciesSequentially(
        remaining,
        repository,
        registry,
        sharedLibraryLoader,
        resolutionPath,
        appendToList(accumulated, slice)));
    }

    private static Promise<Slice> resolveArtifactDependency(ArtifactDependency dependency,
                                                            Repository repository,
                                                            SliceRegistry registry,
                                                            SharedLibraryClassLoader sharedLibraryLoader,
                                                            Set<String> resolutionPath) {
        // First check registry for compatible version
        return registry.findByArtifactKey(dependency.groupId(),
                                          dependency.artifactId(),
                                          dependency.versionPattern())
                       .fold(() -> resolveArtifactFromRepository(dependency,
                                                                 repository,
                                                                 registry,
                                                                 sharedLibraryLoader,
                                                                 resolutionPath),
                             Promise::success);
    }

    private static Promise<Slice> resolveArtifactFromRepository(ArtifactDependency dependency,
                                                                Repository repository,
                                                                SliceRegistry registry,
                                                                SharedLibraryClassLoader sharedLibraryLoader,
                                                                Set<String> resolutionPath) {
        return toArtifact(dependency)
               .async()
               .flatMap(artifact -> resolveWithSharedLoader(artifact,
                                                            repository,
                                                            registry,
                                                            sharedLibraryLoader,
                                                            resolutionPath));
    }

    private static Result<Artifact> toArtifact(ArtifactDependency dependency) {
        var versionStr = extractVersion(dependency.versionPattern())
                         .withQualifier();
        return Artifact.artifact(dependency.groupId() + ":" + dependency.artifactId() + ":" + versionStr);
    }

    private static Version extractVersion(VersionPattern pattern) {
        return switch (pattern) {
            case VersionPattern.Exact(Version version) -> version;
            case VersionPattern.Range(Version from, _, _, _) -> from;
            case VersionPattern.Comparison(_, Version version) -> version;
            case VersionPattern.Tilde(Version version) -> version;
            case VersionPattern.Caret(Version version) -> version;
        };
    }

    private static Result<Slice> createSliceFromClass(Class< ? > sliceClass,
                                                      List<Slice> dependencies,
                                                      List<ArtifactDependency> descriptors) {
        // Convert ArtifactDependency to DependencyDescriptor for SliceFactory compatibility
        var legacyDescriptors = descriptors.stream()
                                           .map(dep -> new DependencyDescriptor(
        ArtifactMapper.toClassName(dep.groupId(),
                                   dep.artifactId()),
        dep.versionPattern(),
        Option.none()))
                                           .toList();
        return SliceFactory.createSlice(sliceClass, dependencies, legacyDescriptors);
    }

    private static Promise<Slice> registerSlice(Artifact artifact, Slice slice, SliceRegistry registry) {
        return registry.register(artifact, slice)
                       .map(_ -> slice)
                       .async();
    }

    private static <T> List<T> appendToList(List<T> list, T element) {
        var newList = new ArrayList<>(list);
        newList.add(element);
        return List.copyOf(newList);
    }

    private static Promise<Class< ? >> loadClass(String className, ClassLoader classLoader) {
        return Promise.lift(Causes::fromThrowable, () -> classLoader.loadClass(className));
    }

    private static Cause circularDependencyDetected(String artifactKey) {
        return Causes.cause("Circular dependency detected during resolution: " + artifactKey);
    }

    private static Cause artifactMismatch(Artifact requested, Artifact declared) {
        return Causes.cause("Artifact mismatch: requested " + requested.asString() + " but JAR manifest declares " + declared.asString());
    }
}
