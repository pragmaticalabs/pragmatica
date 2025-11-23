package org.pragmatica.aether.slice.dependency;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.*;

/**
 * Orchestrates dependency resolution for slices.
 * <p>
 * Resolution process:
 * 1. Load dependencies from META-INF/dependencies/ file
 * 2. Build dependency graph from descriptors
 * 3. Check for circular dependencies
 * 4. Recursively resolve dependencies (depth-first)
 * 5. Instantiate slice via factory method
 * 6. Register in registry
 * <p>
 * Thread-safe: Uses SliceRegistry for synchronization.
 */
public interface DependencyResolver {

    /**
     * Resolve and instantiate a slice with all its dependencies.
     * <p>
     * If the slice is already in the registry, returns it immediately.
     * Otherwise, recursively resolves dependencies, instantiates, and registers.
     *
     * @param artifact    The slice artifact to resolve
     * @param classLoader ClassLoader to load slice class and dependencies
     * @param registry    Registry to track loaded slices
     * @return Resolved and registered slice instance
     */
    static Result<Slice> resolve(Artifact artifact, ClassLoader classLoader, SliceRegistry registry) {
        // Check if already loaded
        var existing = registry.lookup(artifact);

        // Need to check if Option is present and return early if so
        // If not present, proceed with resolution
        // Use a holder pattern to extract value from Option
        final Slice[] holder = new Slice[1];
        existing.onPresent(slice -> holder[0] = slice);

        if (holder[0] != null) {
            return Result.success(holder[0]);
        }

        return resolveNew(artifact, classLoader, registry, new HashSet<>());
    }

    private static Result<Slice> resolveNew(
        Artifact artifact,
        ClassLoader classLoader,
        SliceRegistry registry,
        Set<String> resolutionPath
    ) {
        var className = artifactToClassName(artifact);

        // Detect cycles
        if (resolutionPath.contains(className)) {
            return circularDependencyDetected(className).result();
        }

        resolutionPath.add(className);

        return loadClass(className, classLoader)
            .flatMap(sliceClass -> resolveWithClass(artifact, sliceClass, classLoader, registry, resolutionPath))
            .onSuccess(slice -> resolutionPath.remove(className));
    }

    private static Result<Slice> resolveWithClass(
        Artifact artifact,
        Class<?> sliceClass,
        ClassLoader classLoader,
        SliceRegistry registry,
        Set<String> resolutionPath
    ) {
        var className = sliceClass.getName();

        return SliceDependencies.load(className, classLoader)
            .flatMap(descriptors -> {
                if (descriptors.isEmpty()) {
                    // No dependencies - instantiate directly
                    return SliceFactory.createSlice(sliceClass, List.of(), List.of());
                }

                // Build dependency graph and check for cycles
                return buildDependencyGraph(descriptors, classLoader)
                    .flatMap(depGraph -> DependencyCycleDetector.checkForCycles(depGraph)
                        .flatMap(_ -> resolveDependencies(descriptors, classLoader, registry, resolutionPath))
                        .flatMap(resolvedDeps -> SliceFactory.createSlice(sliceClass, resolvedDeps, descriptors))
                    );
            })
            .flatMap(slice -> registry.register(artifact, slice)
                .map(_ -> slice));
    }

    private static Result<List<Slice>> resolveDependencies(
        List<DependencyDescriptor> descriptors,
        ClassLoader classLoader,
        SliceRegistry registry,
        Set<String> resolutionPath
    ) {
        var resolvedDeps = new ArrayList<Slice>();

        for (var descriptor : descriptors) {
            var depResult = resolveDependency(descriptor, classLoader, registry, resolutionPath);
            if (depResult.isFailure()) {
                return depResult.flatMap(_ -> Result.success(List.<Slice>of())); // Propagate failure
            }
            depResult.onSuccess(resolvedDeps::add);
        }

        return Result.success(resolvedDeps);
    }

    private static Result<Slice> resolveDependency(
        DependencyDescriptor descriptor,
        ClassLoader classLoader,
        SliceRegistry registry,
        Set<String> resolutionPath
    ) {
        // Try to find in registry first
        // If not found, return error (limitation: we don't have artifact resolution yet)
        return registry.find(descriptor.sliceClassName(), descriptor.versionPattern())
            .toResult(dependencyNotFound(
                descriptor.sliceClassName(),
                descriptor.versionPattern().asString()
            ));
    }

    private static Result<Map<String, List<String>>> buildDependencyGraph(
        List<DependencyDescriptor> descriptors,
        ClassLoader classLoader
    ) {
        var graph = new HashMap<String, List<String>>();

        for (var descriptor : descriptors) {
            var className = descriptor.sliceClassName();

            // Load dependencies for this descriptor
            var depsResult = SliceDependencies.load(className, classLoader);
            if (depsResult.isFailure()) {
                return depsResult.flatMap(_ -> Result.success(Map.<String, List<String>>of()));
            }

            depsResult.onSuccess(deps -> {
                var depClassNames = deps.stream()
                    .map(DependencyDescriptor::sliceClassName)
                    .toList();
                graph.put(className, depClassNames);
            });
        }

        return Result.success(graph);
    }

    private static Result<Class<?>> loadClass(String className, ClassLoader classLoader) {
        return Result.lift(
            Causes::fromThrowable,
            () -> classLoader.loadClass(className)
        );
    }

    private static String artifactToClassName(Artifact artifact) {
        // Simple conversion: use artifact ID as class name
        // In real implementation, this would be more sophisticated
        return artifact.groupId().id() + "." + artifact.artifactId().id();
    }

    // Error causes
    private static Cause circularDependencyDetected(String className) {
        return Causes.cause("Circular dependency detected during resolution: " + className);
    }

    private static Cause dependencyNotFound(String className, String version) {
        return Causes.cause("Dependency not found: " + className + " matching version " + version);
    }
}
