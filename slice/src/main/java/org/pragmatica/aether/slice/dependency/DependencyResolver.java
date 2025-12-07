package org.pragmatica.aether.slice.dependency;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceClassLoader;
import org.pragmatica.aether.slice.SliceManifest;
import org.pragmatica.aether.slice.SliceManifest.SliceManifestInfo;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Cause;
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

    static Promise<Slice> resolve(Artifact artifact, Repository repository, SliceRegistry registry) {
        return registry.lookup(artifact)
            .fold(
                () -> resolveNew(artifact, repository, registry, new HashSet<>()),
                Promise::success
            );
    }

    static Result<Slice> resolveFromClassLoader(Artifact artifact, ClassLoader classLoader, SliceRegistry registry) {
        return registry.lookup(artifact)
            .fold(
                () -> resolveNewSync(artifact, classLoader, registry, new HashSet<>()),
                Result::success
            );
    }

    private static Promise<Slice> resolveNew(
        Artifact artifact,
        Repository repository,
        SliceRegistry registry,
        Set<String> resolutionPath
    ) {
        var artifactKey = artifact.asString();

        if (resolutionPath.contains(artifactKey)) {
            return circularDependencyDetected(artifactKey).promise();
        }

        resolutionPath.add(artifactKey);

        return repository.locate(artifact)
            .flatMap(location -> loadFromLocation(artifact, location, repository, registry, resolutionPath))
            .onSuccess(_ -> resolutionPath.remove(artifactKey))
            .onFailure(_ -> resolutionPath.remove(artifactKey));
    }

    private static Promise<Slice> loadFromLocation(
        Artifact artifact,
        Location location,
        Repository repository,
        SliceRegistry registry,
        Set<String> resolutionPath
    ) {
        // Read manifest first to get slice class name
        return SliceManifest.read(location.url())
            .onFailure(cause -> log.error("Invalid slice JAR {}: {}", artifact, cause.message()))
            .async()
            .flatMap(manifest -> validateAndLoad(artifact, location, manifest, repository, registry, resolutionPath));
    }

    private static Promise<Slice> validateAndLoad(
        Artifact artifact,
        Location location,
        SliceManifestInfo manifest,
        Repository repository,
        SliceRegistry registry,
        Set<String> resolutionPath
    ) {
        // Verify manifest artifact matches requested artifact
        if (!manifest.artifact().equals(artifact)) {
            log.error("Artifact mismatch: requested {} but JAR declares {}", artifact, manifest.artifact());
            return artifactMismatch(artifact, manifest.artifact()).promise();
        }

        var classLoader = new SliceClassLoader(new URL[]{location.url()}, DependencyResolver.class.getClassLoader());
        return resolveWithManifest(manifest, classLoader, repository, registry, resolutionPath);
    }

    private static Promise<Slice> resolveWithManifest(
        SliceManifestInfo manifest,
        ClassLoader classLoader,
        Repository repository,
        SliceRegistry registry,
        Set<String> resolutionPath
    ) {
        return loadClass(manifest.sliceClassName(), classLoader)
            .flatMap(sliceClass -> resolveWithClass(manifest.artifact(), sliceClass, classLoader, repository, registry, resolutionPath));
    }

    private static Promise<Slice> resolveWithClass(
        Artifact artifact,
        Class<?> sliceClass,
        ClassLoader classLoader,
        Repository repository,
        SliceRegistry registry,
        Set<String> resolutionPath
    ) {
        return SliceDependencies.load(sliceClass.getName(), classLoader)
            .async()
            .flatMap(descriptors -> resolveDescriptors(sliceClass, descriptors, classLoader, repository, registry, resolutionPath))
            .flatMap(slice -> registerSlice(artifact, slice, registry));
    }

    private static Promise<Slice> resolveDescriptors(
        Class<?> sliceClass,
        List<DependencyDescriptor> descriptors,
        ClassLoader classLoader,
        Repository repository,
        SliceRegistry registry,
        Set<String> resolutionPath
    ) {
        if (descriptors.isEmpty()) {
            return SliceFactory.createSlice(sliceClass, List.of(), List.of()).async();
        }

        return buildDependencyGraph(descriptors, classLoader)
            .flatMap(depGraph -> DependencyCycleDetector.checkForCycles(depGraph)
                .flatMap(_ -> resolveDependencies(descriptors, repository, registry, resolutionPath))
                .flatMap(resolvedDeps -> SliceFactory.createSlice(sliceClass, resolvedDeps, descriptors))
            ).async();
    }

    private static Promise<Slice> registerSlice(Artifact artifact, Slice slice, SliceRegistry registry) {
        return registry.register(artifact, slice)
            .map(_ -> slice)
            .async();
    }

    private static Result<List<Slice>> resolveDependencies(
        List<DependencyDescriptor> descriptors,
        Repository repository,
        SliceRegistry registry,
        Set<String> resolutionPath
    ) {
        Result<List<Slice>> acc = Result.success(List.of());

        for (var descriptor : descriptors) {
            var depResult = resolveDependency(descriptor, repository, registry, resolutionPath);
            acc = acc.flatMap(list -> depResult.map(dep -> appendToList(list, dep)));
            if (acc.isFailure()) {
                return acc;
            }
        }

        return acc;
    }

    private static <T> List<T> appendToList(List<T> list, T element) {
        var newList = new ArrayList<>(list);
        newList.add(element);
        return List.copyOf(newList);
    }

    private static Result<Slice> resolveDependency(
        DependencyDescriptor descriptor,
        Repository repository,
        SliceRegistry registry,
        Set<String> resolutionPath
    ) {
        return registry.find(descriptor.sliceClassName(), descriptor.versionPattern())
            .fold(
                () -> resolveFromRepository(descriptor, repository, registry, resolutionPath),
                Result::success
            );
    }

    private static Result<Slice> resolveFromRepository(
        DependencyDescriptor descriptor,
        Repository repository,
        SliceRegistry registry,
        Set<String> resolutionPath
    ) {
        return ArtifactMapper.toArtifact(descriptor.sliceClassName(), descriptor.versionPattern())
            .flatMap(artifact -> resolve(artifact, repository, registry).await());
    }

    // === Synchronous resolution (for testing/pre-loaded scenarios) ===

    private static Result<Slice> resolveNewSync(
        Artifact artifact,
        ClassLoader classLoader,
        SliceRegistry registry,
        Set<String> resolutionPath
    ) {
        var artifactKey = artifact.asString();

        if (resolutionPath.contains(artifactKey)) {
            return circularDependencyDetected(artifactKey).result();
        }

        resolutionPath.add(artifactKey);

        // For sync resolution from ClassLoader, read manifest to get class name
        return SliceManifest.readFromClassLoader(classLoader)
            .onFailure(cause -> log.error("Invalid slice ClassLoader for {}: {}", artifact, cause.message()))
            .flatMap(manifest -> validateManifestSync(artifact, manifest))
            .flatMap(manifest -> loadClassSync(manifest.sliceClassName(), classLoader)
                .flatMap(sliceClass -> resolveWithClassSync(manifest.artifact(), sliceClass, classLoader, registry, resolutionPath)))
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

    private static Result<Slice> resolveWithClassSync(
        Artifact artifact,
        Class<?> sliceClass,
        ClassLoader classLoader,
        SliceRegistry registry,
        Set<String> resolutionPath
    ) {
        return SliceDependencies.load(sliceClass.getName(), classLoader)
            .flatMap(descriptors -> resolveDescriptorsSync(sliceClass, descriptors, classLoader, registry, resolutionPath))
            .flatMap(slice -> registry.register(artifact, slice).map(_ -> slice));
    }

    private static Result<Slice> resolveDescriptorsSync(
        Class<?> sliceClass,
        List<DependencyDescriptor> descriptors,
        ClassLoader classLoader,
        SliceRegistry registry,
        Set<String> resolutionPath
    ) {
        if (descriptors.isEmpty()) {
            return SliceFactory.createSlice(sliceClass, List.of(), List.of());
        }

        return buildDependencyGraph(descriptors, classLoader)
            .flatMap(depGraph -> DependencyCycleDetector.checkForCycles(depGraph)
                .flatMap(_ -> resolveDependenciesSync(descriptors, classLoader, registry, resolutionPath))
                .flatMap(resolvedDeps -> SliceFactory.createSlice(sliceClass, resolvedDeps, descriptors))
            );
    }

    private static Result<List<Slice>> resolveDependenciesSync(
        List<DependencyDescriptor> descriptors,
        ClassLoader classLoader,
        SliceRegistry registry,
        Set<String> resolutionPath
    ) {
        Result<List<Slice>> acc = Result.success(List.of());

        for (var descriptor : descriptors) {
            var depResult = resolveDependencySync(descriptor, classLoader, registry, resolutionPath);
            acc = acc.flatMap(list -> depResult.map(dep -> appendToList(list, dep)));
            if (acc.isFailure()) {
                return acc;
            }
        }

        return acc;
    }

    private static Result<Slice> resolveDependencySync(
        DependencyDescriptor descriptor,
        ClassLoader classLoader,
        SliceRegistry registry,
        Set<String> resolutionPath
    ) {
        return registry.find(descriptor.sliceClassName(), descriptor.versionPattern())
            .fold(
                () -> resolveFromClassLoaderSync(descriptor, classLoader, registry, resolutionPath),
                Result::success
            );
    }

    private static Result<Slice> resolveFromClassLoaderSync(
        DependencyDescriptor descriptor,
        ClassLoader classLoader,
        SliceRegistry registry,
        Set<String> resolutionPath
    ) {
        return ArtifactMapper.toArtifact(descriptor.sliceClassName(), descriptor.versionPattern())
            .flatMap(artifact -> resolveNewSync(artifact, classLoader, registry, resolutionPath));
    }

    // === Utility Methods ===

    private static Result<Map<String, List<String>>> buildDependencyGraph(
        List<DependencyDescriptor> descriptors,
        ClassLoader classLoader
    ) {
        Result<Map<String, List<String>>> acc = Result.success(Map.of());

        for (var descriptor : descriptors) {
            var entryResult = loadDescriptorDependencies(descriptor, classLoader);
            acc = acc.flatMap(map -> entryResult.map(entry -> addToMap(map, entry)));
            if (acc.isFailure()) {
                return acc;
            }
        }

        return acc;
    }

    private static Result<Map.Entry<String, List<String>>> loadDescriptorDependencies(
        DependencyDescriptor descriptor,
        ClassLoader classLoader
    ) {
        return SliceDependencies.load(descriptor.sliceClassName(), classLoader)
            .map(deps -> Map.entry(
                descriptor.sliceClassName(),
                deps.stream().map(DependencyDescriptor::sliceClassName).toList()
            ));
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
        return Causes.cause("Artifact mismatch: requested " + requested.asString() +
            " but JAR manifest declares " + declared.asString());
    }
}
