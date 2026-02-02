package org.pragmatica.aether.infra;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.function.Supplier;

/**
 * Per-node key-value store for infrastructure service instances.
 * <p>
 * Enables infra services to share instances across slices. Each infra service
 * determines its own sharing strategy by using this store in its factory method.
 * <p>
 * Thread-safe: All operations are atomic and safe for concurrent access.
 * <p>
 * Usage in infra service factory:
 * <pre>{@code
 * static CacheService cacheService() {
 *     return InfraStore.instance()
 *         .flatMap(store -> store.getOrCreate(
 *             "org.pragmatica-lite.aether:infra-cache",
 *             "0.7.0",
 *             CacheService.class,
 *             InMemoryCacheService::inMemoryCacheService))
 *         .unwrap();
 * }
 * }</pre>
 */
public interface InfraStore {
    /**
     * Get all registered versions for an artifact.
     * <p>
     * Infra services use this to find compatible versions via
     * {@link VersionedInstance} API.
     *
     * @param artifactKey Maven coordinates (groupId:artifactId)
     * @param type        Expected instance type
     * @param <T>         Instance type
     * @return List of versioned instances (empty if none registered)
     */
    <T> List<VersionedInstance<T>> get(String artifactKey, Class<T> type);

    /**
     * Atomic get-or-create operation.
     * <p>
     * If an instance with the exact version exists, returns it.
     * Otherwise, calls factory to create new instance and registers it.
     * <p>
     * Thread-safe: Only one instance per (artifactKey, version) pair
     * will be created even under concurrent access.
     *
     * @param artifactKey Maven coordinates (groupId:artifactId)
     * @param version     Semantic version string
     * @param type        Expected instance type
     * @param factory     Creates instance if not found
     * @param <T>         Instance type
     * @return Existing or newly created instance
     */
    <T> T getOrCreate(String artifactKey, String version, Class<T> type, Supplier<T> factory);

    // Static accessor pattern (like SliceRuntime)
    /**
     * Get the global InfraStore instance.
     *
     * @return InfraStore if configured, empty otherwise
     */
    static Option<InfraStore> instance() {
        return InfraStoreHolder.instance();
    }

    /**
     * Set the global InfraStore instance.
     * <p>
     * Called by AetherNode during startup.
     *
     * @param store InfraStore implementation
     */
    static void setInstance(InfraStore store) {
        InfraStoreHolder.setInstance(store);
    }

    /**
     * Clear the global InfraStore instance.
     * <p>
     * Called during shutdown or in tests.
     */
    static void clear() {
        InfraStoreHolder.clear();
    }
}
