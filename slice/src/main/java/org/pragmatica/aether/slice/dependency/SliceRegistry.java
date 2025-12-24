package org.pragmatica.aether.slice.dependency;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe registry for tracking loaded slice instances.
 * <p>
 * Maps artifacts to their loaded slice instances. Supports:
 * - Registration of loaded slices
 * - Lookup by exact artifact
 * - Lookup by class name with version pattern matching
 * - Thread-safe concurrent access
 */
public interface SliceRegistry {

    /**
     * Create a new empty registry.
     */
    static SliceRegistry create() {
        return new SliceRegistryImpl(new ConcurrentHashMap<>());
    }

    /**
     * Register a loaded slice.
     *
     * @param artifact The artifact identifier
     * @param slice    The loaded slice instance
     *
     * @return Success with Unit, or failure if artifact already registered
     */
    Result<Unit> register(Artifact artifact, Slice slice);

    /**
     * Unregister a slice.
     *
     * @param artifact The artifact identifier
     *
     * @return Success with Unit, or failure if artifact not found
     */
    Result<Unit> unregister(Artifact artifact);

    /**
     * Lookup slice by exact artifact.
     *
     * @param artifact The artifact identifier
     *
     * @return Option containing the slice if found
     */
    Option<Slice> lookup(Artifact artifact);

    /**
     * Find slice by class name and version pattern.
     * <p>
     * Searches all registered slices for matching class name,
     * then filters by version pattern.
     *
     * @param className      Fully qualified class name
     * @param versionPattern Version pattern to match
     *
     * @return Option containing the first matching slice
     */
    Option<Slice> find(String className, VersionPattern versionPattern);

    /**
     * Get all registered artifacts.
     */
    List<Artifact> allArtifacts();

    /**
     * Find slice by groupId, artifactId, and version pattern.
     * <p>
     * Searches all registered slices for matching groupId and artifactId,
     * then filters by version pattern.
     *
     * @param groupId        Group ID (e.g., "org.example")
     * @param artifactId     Artifact ID (e.g., "my-lib")
     * @param versionPattern Version pattern to match
     *
     * @return Option containing the first matching slice
     */
    Option<Slice> findByArtifactKey(String groupId, String artifactId, VersionPattern versionPattern);

    record SliceRegistryImpl(ConcurrentMap<Artifact, Slice> registry) implements SliceRegistry {

        @Override
        public Result<Unit> register(Artifact artifact, Slice slice) {
            var existing = registry.putIfAbsent(artifact, slice);
            if (existing != null) {
                return ALREADY_REGISTERED.apply(artifact.asString()).result();
            }
            return Result.unitResult();
        }

        @Override
        public Result<Unit> unregister(Artifact artifact) {
            var removed = registry.remove(artifact);
            if (removed == null) {
                return NOT_FOUND.apply(artifact.asString()).result();
            }
            return Result.unitResult();
        }

        @Override
        public Option<Slice> lookup(Artifact artifact) {
            return Option.option(registry.get(artifact));
        }

        @Override
        public Option<Slice> find(String className, VersionPattern versionPattern) {
            return registry.entrySet()
                           .stream()
                           .filter(entry -> matchesClassName(entry.getKey(), className))
                           .filter(entry -> versionPattern.matches(entry.getKey().version()))
                           .map(entry -> entry.getValue())
                           .findFirst()
                           .map(Option::option)
                           .orElse(Option.none());
        }

        @Override
        public List<Artifact> allArtifacts() {
            return List.copyOf(registry.keySet());
        }

        @Override
        public Option<Slice> findByArtifactKey(String groupId, String artifactId, VersionPattern versionPattern) {
            return registry.entrySet()
                           .stream()
                           .filter(entry -> entry.getKey().groupId().id().equals(groupId))
                           .filter(entry -> entry.getKey().artifactId().id().equals(artifactId))
                           .filter(entry -> versionPattern.matches(entry.getKey().version()))
                           .map(entry -> entry.getValue())
                           .findFirst()
                           .map(Option::option)
                           .orElse(Option.none());
        }

        private boolean matchesClassName(Artifact artifact, String className) {
            // Extract class name from artifact ID
            // Convention: artifact ID is typically the class name or last part of package
            // For exact matching, we'd need to load the class, but that's expensive
            // So we use a simple string match on the artifact ID
            return artifact.artifactId().id().equals(extractSimpleName(className));
        }

        private String extractSimpleName(String className) {
            var lastDot = className.lastIndexOf('.');
            return lastDot >= 0
                   ? className.substring(lastDot + 1)
                   : className;
        }

        private static final Fn1<Cause, String> ALREADY_REGISTERED = Causes.forValue("Artifact already registered: %s");

        private static final Fn1<Cause, String> NOT_FOUND = Causes.forValue("Artifact not found in registry: %s");
    }
}
