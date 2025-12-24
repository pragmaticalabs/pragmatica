package org.pragmatica.aether.slice.dependency;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.SharedLibraryClassLoader;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceClassLoader;
import org.pragmatica.aether.slice.SliceManifest;
import org.pragmatica.aether.slice.SliceManifest.SliceManifestInfo;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.*;

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
     * This is the primary method that should be used for slice resolution.
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
        return registry.lookup(artifact).fold(
                () -> resolveWithSharedLoader(artifact, repository, registry, sharedLibraryLoader, new HashSet<>()),
                Promise::success
        );
    }

    /**
     * @deprecated Use {@link #resolve(Artifact, Repository, SliceRegistry, SharedLibraryClassLoader)} instead
     */
    @Deprecated
    static Promise<Slice> resolve(Artifact artifact, Repository repository, SliceRegistry registry) {
        return registry.lookup(artifact).fold(() -> resolveNew(artifact, repository, registry, new HashSet<>()),
                                              Promise::success);
    }

    static Result<Slice> resolveFromClassLoader(Artifact artifact, ClassLoader classLoader, SliceRegistry registry) {
        return registry.lookup(artifact).fold(() -> resolveNewSync(artifact, classLoader, registry, new HashSet<>()),
                                              Result::success);
    }

    // === New resolution path using SharedLibraryClassLoader ===

    private static Promise<Slice> resolveWithSharedLoader(Artifact artifact,
                                                          Repository repository,
                                                          SliceRegistry registry,
                                                          SharedLibraryClassLoader sharedLibraryLoader,
                                                          Set<String> resolutionPath) {
        var artifactKey = artifact.asString();

        if (resolutionPath.contains(artifactKey)) {
            return circularDependencyDetected(artifactKey).promise();
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
                            .onFailure(cause -> log.error("Invalid slice JAR {}: {}", artifact, cause.message()))
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
        if (!manifest.artifact().equals(artifact)) {
            log.error("Artifact mismatch: requested {} but JAR declares {}", artifact, manifest.artifact());
            return artifactMismatch(artifact, manifest.artifact()).promise();
        }

        // Load dependency file to get shared and slice dependencies
        return DependencyFile.load(manifest.sliceClassName(), createTempLoader(location.url(), sharedLibraryLoader))
                             .async()
                             .flatMap(depFile -> processSharedAndLoadSlice(
                                     manifest,
                                     location,
                                     depFile,
                                     repository,
                                     registry,
                                     sharedLibraryLoader,
                                     resolutionPath
                             ));
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
        // Process shared dependencies first
        return SharedDependencyLoader.processSharedDependencies(
                depFile.shared(),
                sharedLibraryLoader,
                repository,
                location.url()
        ).flatMap(sharedResult -> {
            // Now load the slice class and resolve slice dependencies
            return loadClass(manifest.sliceClassName(), sharedResult.sliceClassLoader())
                    .flatMap(sliceClass -> resolveSliceDependencies(
                            manifest.artifact(),
                            sliceClass,
                            depFile.slices(),
                            sharedResult.sliceClassLoader(),
                            repository,
                            registry,
                            sharedLibraryLoader,
                            resolutionPath
                    ));
        });
    }

    private static Promise<Slice> resolveSliceDependencies(Artifact artifact,
                                                            Class<?> sliceClass,
                                                            List<ArtifactDependency> sliceDeps,
                                                            ClassLoader classLoader,
                                                            Repository repository,
                                                            SliceRegistry registry,
                                                            SharedLibraryClassLoader sharedLibraryLoader,
                                                            Set<String> resolutionPath) {
        if (sliceDeps.isEmpty()) {
            return createSliceFromClass(sliceClass, List.of(), List.of())
                    .async()
                    .flatMap(slice -> registerSlice(artifact, slice, registry));
        }

        return resolveArtifactDependenciesSequentially(sliceDeps, repository, registry, sharedLibraryLoader, resolutionPath, List.of())
                .flatMap(resolvedSlices -> createSliceFromClass(sliceClass, resolvedSlices, sliceDeps).async())
                .flatMap(slice -> registerSlice(artifact, slice, registry));
    }

    private static Promise<List<Slice>> resolveArtifactDependenciesSequentially(
            List<ArtifactDependency> dependencies,
            Repository repository,
            SliceRegistry registry,
            SharedLibraryClassLoader sharedLibraryLoader,
            Set<String> resolutionPath,
            List<Slice> accumulated
    ) {
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
                        appendToList(accumulated, slice)
                ));
    }

    private static Promise<Slice> resolveArtifactDependency(ArtifactDependency dependency,
                                                             Repository repository,
                                                             SliceRegistry registry,
                                                             SharedLibraryClassLoader sharedLibraryLoader,
                                                             Set<String> resolutionPath) {
        // First check registry for compatible version
        return registry.findByArtifactKey(dependency.groupId(), dependency.artifactId(), dependency.versionPattern())
                       .fold(
                               () -> resolveArtifactFromRepository(dependency, repository, registry, sharedLibraryLoader, resolutionPath),
                               Promise::success
                       );
    }

    private static Promise<Slice> resolveArtifactFromRepository(ArtifactDependency dependency,
                                                                 Repository repository,
                                                                 SliceRegistry registry,
                                                                 SharedLibraryClassLoader sharedLibraryLoader,
                                                                 Set<String> resolutionPath) {
        return toArtifact(dependency)
                .async()
                .flatMap(artifact -> resolveWithSharedLoader(artifact, repository, registry, sharedLibraryLoader, resolutionPath));
    }

    private static Result<Artifact> toArtifact(ArtifactDependency dependency) {
        var versionStr = extractVersion(dependency.versionPattern()).withQualifier();
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

    private static Result<Slice> createSliceFromClass(Class<?> sliceClass,
                                                       List<Slice> dependencies,
                                                       List<ArtifactDependency> descriptors) {
        // Convert ArtifactDependency to DependencyDescriptor for SliceFactory compatibility
        var legacyDescriptors = descriptors.stream()
                .map(dep -> new DependencyDescriptor(
                        ArtifactMapper.toClassName(dep.groupId(), dep.artifactId()),
                        dep.versionPattern(),
                        Option.none()
                ))
                .toList();

        return SliceFactory.createSlice(sliceClass, dependencies, legacyDescriptors);
    }

    // === End of new resolution path ===

    private static Promise<Slice> resolveNew(Artifact artifact,
                                             Repository repository,
                                             SliceRegistry registry,
                                             Set<String> resolutionPath) {
        var artifactKey = artifact.asString();

        if (resolutionPath.contains(artifactKey)) {
            return circularDependencyDetected(artifactKey).promise();
        }

        resolutionPath.add(artifactKey);

        return repository.locate(artifact)
                         .flatMap(location -> loadFromLocation(artifact,
                                                               location,
                                                               repository,
                                                               registry,
                                                               resolutionPath))
                         .onSuccess(_ -> resolutionPath.remove(artifactKey))
                         .onFailure(_ -> resolutionPath.remove(artifactKey));
    }

    private static Promise<Slice> loadFromLocation(Artifact artifact,
                                                   Location location,
                                                   Repository repository,
                                                   SliceRegistry registry,
                                                   Set<String> resolutionPath) {
        // Read manifest first to get slice class name
        return SliceManifest.read(location.url()).onFailure(cause -> log.error("Invalid slice JAR {}: {}",
                                                                               artifact,
                                                                               cause.message())).async().flatMap(
                manifest -> validateAndLoad(artifact, location, manifest, repository, registry, resolutionPath));
    }

    private static Promise<Slice> validateAndLoad(Artifact artifact,
                                                  Location location,
                                                  SliceManifestInfo manifest,
                                                  Repository repository,
                                                  SliceRegistry registry,
                                                  Set<String> resolutionPath) {
        // Verify manifest artifact matches requested artifact
        if (!manifest.artifact().equals(artifact)) {
            log.error("Artifact mismatch: requested {} but JAR declares {}", artifact, manifest.artifact());
            return artifactMismatch(artifact, manifest.artifact()).promise();
        }

        var classLoader = new SliceClassLoader(new URL[]{location.url()}, DependencyResolver.class.getClassLoader());
        return resolveWithManifest(manifest, classLoader, repository, registry, resolutionPath);
    }

    private static Promise<Slice> resolveWithManifest(SliceManifestInfo manifest,
                                                      ClassLoader classLoader,
                                                      Repository repository,
                                                      SliceRegistry registry,
                                                      Set<String> resolutionPath) {
        return loadClass(manifest.sliceClassName(),
                         classLoader).flatMap(sliceClass -> resolveWithClass(manifest.artifact(),
                                                                             sliceClass,
                                                                             classLoader,
                                                                             repository,
                                                                             registry,
                                                                             resolutionPath));
    }

    private static Promise<Slice> resolveWithClass(Artifact artifact,
                                                   Class<?> sliceClass,
                                                   ClassLoader classLoader,
                                                   Repository repository,
                                                   SliceRegistry registry,
                                                   Set<String> resolutionPath) {
        return SliceDependencies.load(sliceClass.getName(), classLoader)
                                .async()
                                .flatMap(descriptors -> resolveDescriptors(sliceClass,
                                                                           descriptors,
                                                                           classLoader,
                                                                           repository,
                                                                           registry,
                                                                           resolutionPath))
                                .flatMap(slice -> registerSlice(artifact, slice, registry));
    }

    private static Promise<Slice> resolveDescriptors(Class<?> sliceClass,
                                                     List<DependencyDescriptor> descriptors,
                                                     ClassLoader classLoader,
                                                     Repository repository,
                                                     SliceRegistry registry,
                                                     Set<String> resolutionPath) {
        if (descriptors.isEmpty()) {
            return SliceFactory.createSlice(sliceClass, List.of(), List.of()).async();
        }

        return buildDependencyGraph(descriptors, classLoader)
                .flatMap(depGraph -> DependencyCycleDetector.checkForCycles(depGraph))
                .async()
                .flatMap(_ -> resolveDependencies(descriptors, repository, registry, resolutionPath))
                .flatMap(resolvedDeps -> SliceFactory.createSlice(sliceClass, resolvedDeps, descriptors).async());
    }

    private static Promise<Slice> registerSlice(Artifact artifact, Slice slice, SliceRegistry registry) {
        return registry.register(artifact, slice).map(_ -> slice).async();
    }

    private static Promise<List<Slice>> resolveDependencies(List<DependencyDescriptor> descriptors,
                                                            Repository repository,
                                                            SliceRegistry registry,
                                                            Set<String> resolutionPath) {
        return resolveDependenciesSequentially(descriptors, repository, registry, resolutionPath, List.of());
    }

    private static Promise<List<Slice>> resolveDependenciesSequentially(List<DependencyDescriptor> descriptors,
                                                                        Repository repository,
                                                                        SliceRegistry registry,
                                                                        Set<String> resolutionPath,
                                                                        List<Slice> accumulated) {
        if (descriptors.isEmpty()) {
            return Promise.success(accumulated);
        }

        var descriptor = descriptors.getFirst();
        var remaining = descriptors.subList(1, descriptors.size());

        return resolveDependency(descriptor, repository, registry, resolutionPath)
                .flatMap(slice -> resolveDependenciesSequentially(remaining,
                                                                  repository,
                                                                  registry,
                                                                  resolutionPath,
                                                                  appendToList(accumulated, slice)));
    }

    private static <T> List<T> appendToList(List<T> list, T element) {
        var newList = new ArrayList<>(list);
        newList.add(element);
        return List.copyOf(newList);
    }

    private static Promise<Slice> resolveDependency(DependencyDescriptor descriptor,
                                                    Repository repository,
                                                    SliceRegistry registry,
                                                    Set<String> resolutionPath) {
        return registry.find(descriptor.sliceClassName(), descriptor.versionPattern())
                       .fold(() -> resolveFromRepository(descriptor, repository, registry, resolutionPath),
                             Promise::success);
    }

    private static Promise<Slice> resolveFromRepository(DependencyDescriptor descriptor,
                                                        Repository repository,
                                                        SliceRegistry registry,
                                                        Set<String> resolutionPath) {
        return ArtifactMapper.toArtifact(descriptor.sliceClassName(), descriptor.versionPattern())
                             .async()
                             .flatMap(artifact -> resolve(artifact, repository, registry));
    }

    // === Synchronous resolution (for testing/pre-loaded scenarios) ===

    private static Result<Slice> resolveNewSync(Artifact artifact,
                                                ClassLoader classLoader,
                                                SliceRegistry registry,
                                                Set<String> resolutionPath) {
        var artifactKey = artifact.asString();

        if (resolutionPath.contains(artifactKey)) {
            return circularDependencyDetected(artifactKey).result();
        }

        resolutionPath.add(artifactKey);

        // For sync resolution from ClassLoader, read manifest to get class name
        return SliceManifest.readFromClassLoader(classLoader)
                            .onFailure(cause -> log.error("Invalid slice ClassLoader for {}: {}",
                                                          artifact,
                                                          cause.message()))
                            .flatMap(manifest -> validateManifestSync(artifact, manifest))
                            .flatMap(manifest -> loadClassSync(manifest.sliceClassName(), classLoader).flatMap(
                                    sliceClass -> resolveWithClassSync(manifest.artifact(),
                                                                       sliceClass,
                                                                       classLoader,
                                                                       registry,
                                                                       resolutionPath)))
                            .onSuccess(_ -> resolutionPath.remove(artifactKey))
                            .onFailure(_ -> resolutionPath.remove(artifactKey));
    }

    private static Result<SliceManifestInfo> validateManifestSync(Artifact artifact, SliceManifestInfo manifest) {
        if (!manifest.artifact().equals(artifact)) {
            log.error("Artifact mismatch: requested {} but ClassLoader declares {}", artifact, manifest.artifact());
            return artifactMismatch(artifact, manifest.artifact()).result();
        }
        return Result.success(manifest);
    }

    private static Result<Slice> resolveWithClassSync(Artifact artifact,
                                                      Class<?> sliceClass,
                                                      ClassLoader classLoader,
                                                      SliceRegistry registry,
                                                      Set<String> resolutionPath) {
        return SliceDependencies.load(sliceClass.getName(), classLoader).flatMap(descriptors -> resolveDescriptorsSync(
                sliceClass,
                descriptors,
                classLoader,
                registry,
                resolutionPath)).flatMap(slice -> registry.register(artifact, slice).map(_ -> slice));
    }

    private static Result<Slice> resolveDescriptorsSync(Class<?> sliceClass,
                                                        List<DependencyDescriptor> descriptors,
                                                        ClassLoader classLoader,
                                                        SliceRegistry registry,
                                                        Set<String> resolutionPath) {
        if (descriptors.isEmpty()) {
            return SliceFactory.createSlice(sliceClass, List.of(), List.of());
        }

        return buildDependencyGraph(descriptors,
                                    classLoader).flatMap(depGraph -> DependencyCycleDetector.checkForCycles(depGraph)
                                                                                            .flatMap(_ -> resolveDependenciesSync(
                                                                                                    descriptors,
                                                                                                    classLoader,
                                                                                                    registry,
                                                                                                    resolutionPath))
                                                                                            .flatMap(resolvedDeps -> SliceFactory.createSlice(
                                                                                                    sliceClass,
                                                                                                    resolvedDeps,
                                                                                                    descriptors)));
    }

    private static Result<List<Slice>> resolveDependenciesSync(List<DependencyDescriptor> descriptors,
                                                               ClassLoader classLoader,
                                                               SliceRegistry registry,
                                                               Set<String> resolutionPath) {
        return resolveDependenciesSyncSequentially(descriptors, classLoader, registry, resolutionPath, List.of());
    }

    private static Result<List<Slice>> resolveDependenciesSyncSequentially(List<DependencyDescriptor> descriptors,
                                                                           ClassLoader classLoader,
                                                                           SliceRegistry registry,
                                                                           Set<String> resolutionPath,
                                                                           List<Slice> accumulated) {
        if (descriptors.isEmpty()) {
            return Result.success(accumulated);
        }

        var descriptor = descriptors.getFirst();
        var remaining = descriptors.subList(1, descriptors.size());

        return resolveDependencySync(descriptor, classLoader, registry, resolutionPath)
                .flatMap(slice -> resolveDependenciesSyncSequentially(remaining,
                                                                      classLoader,
                                                                      registry,
                                                                      resolutionPath,
                                                                      appendToList(accumulated, slice)));
    }

    private static Result<Slice> resolveDependencySync(DependencyDescriptor descriptor,
                                                       ClassLoader classLoader,
                                                       SliceRegistry registry,
                                                       Set<String> resolutionPath) {
        return registry.find(descriptor.sliceClassName(), descriptor.versionPattern())
                       .fold(() -> resolveFromClassLoaderSync(descriptor, classLoader, registry, resolutionPath),
                             Result::success);
    }

    private static Result<Slice> resolveFromClassLoaderSync(DependencyDescriptor descriptor,
                                                            ClassLoader classLoader,
                                                            SliceRegistry registry,
                                                            Set<String> resolutionPath) {
        return ArtifactMapper.toArtifact(descriptor.sliceClassName(), descriptor.versionPattern())
                             .flatMap(artifact -> resolveNewSync(artifact, classLoader, registry, resolutionPath));
    }

    // === Utility Methods ===

    private static Result<Map<String, List<String>>> buildDependencyGraph(List<DependencyDescriptor> descriptors,
                                                                          ClassLoader classLoader) {
        return buildDependencyGraphSequentially(descriptors, classLoader, Map.of());
    }

    private static Result<Map<String, List<String>>> buildDependencyGraphSequentially(List<DependencyDescriptor> descriptors,
                                                                                      ClassLoader classLoader,
                                                                                      Map<String, List<String>> accumulated) {
        if (descriptors.isEmpty()) {
            return Result.success(accumulated);
        }

        var descriptor = descriptors.getFirst();
        var remaining = descriptors.subList(1, descriptors.size());

        return loadDescriptorDependencies(descriptor, classLoader)
                .flatMap(entry -> buildDependencyGraphSequentially(remaining,
                                                                   classLoader,
                                                                   addToMap(accumulated, entry)));
    }

    private static Result<Map.Entry<String, List<String>>> loadDescriptorDependencies(DependencyDescriptor descriptor,
                                                                                      ClassLoader classLoader) {
        return SliceDependencies.load(descriptor.sliceClassName(), classLoader)
                                .map(deps -> Map.entry(descriptor.sliceClassName(),
                                                       deps.stream()
                                                           .map(DependencyDescriptor::sliceClassName)
                                                           .toList()));
    }

    private static <K, V> Map<K, V> addToMap(Map<K, V> map, Map.Entry<K, V> entry) {
        var newMap = new HashMap<>(map);
        newMap.put(entry.getKey(), entry.getValue());
        return Map.copyOf(newMap);
    }

    private static Promise<Class<?>> loadClass(String className, ClassLoader classLoader) {
        return Promise.lift(Causes::fromThrowable, () -> classLoader.loadClass(className));
    }

    private static Result<Class<?>> loadClassSync(String className, ClassLoader classLoader) {
        return Result.lift(Causes::fromThrowable, () -> classLoader.loadClass(className));
    }

    private static Cause circularDependencyDetected(String artifactKey) {
        return Causes.cause("Circular dependency detected during resolution: " + artifactKey);
    }

    private static Cause artifactMismatch(Artifact requested, Artifact declared) {
        return Causes.cause("Artifact mismatch: requested "
                            + requested.asString()
                            + " but JAR manifest declares "
                            + declared.asString());
    }
}
