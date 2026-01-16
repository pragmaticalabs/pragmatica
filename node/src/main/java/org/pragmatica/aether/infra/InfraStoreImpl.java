package org.pragmatica.aether.infra;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe implementation of InfraStore for managing infrastructure service instances.
 * <p>
 * Uses ConcurrentHashMap for atomic operations and version-based instance storage.
 * Each artifact key maps to a list of versioned instances.
 * <p>
 * Note: Internal implementation uses null for the double-checked locking pattern,
 * which is appropriate for this performance-sensitive synchronization context.
 * The public API never exposes null values.
 */
public final class InfraStoreImpl implements InfraStore {
    private static final Logger log = LoggerFactory.getLogger(InfraStoreImpl.class);

    // Map: artifactKey -> list of versioned instances
    private final ConcurrentHashMap<String, List<VersionedInstance< ? >>> store = new ConcurrentHashMap<>();

    // Lock for atomic getOrCreate operations
    private final Object createLock = new Object();

    private InfraStoreImpl() {}

    /**
     * Create a new InfraStoreImpl instance.
     */
    public static InfraStoreImpl infraStoreImpl() {
        return new InfraStoreImpl();
    }

    @Override
    public <T> List<VersionedInstance<T>> get(String artifactKey, Class<T> type) {
        var instances = store.getOrDefault(artifactKey, List.of());
        // Type-safe cast with filtering
        return instances.stream()
                        .filter(vi -> type.isInstance(vi.instance()))
                        .map(vi -> new VersionedInstance<>(vi.version(),
                                                           type.cast(vi.instance())))
                        .toList();
    }

    @Override
    public <T> T getOrCreate(String artifactKey, String version, Class<T> type, Supplier<T> factory) {
        // Fast path: check if exact version exists (null indicates absence)
        var existing = findExactVersion(artifactKey, version, type);
        if (existing != null) {
            log.debug("Returning existing instance for {}:{}", artifactKey, version);
            return existing;
        }
        // Slow path: synchronized creation with double-check
        synchronized (createLock) {
            existing = findExactVersion(artifactKey, version, type);
            if (existing != null) {
                log.debug("Returning existing instance for {}:{} (after lock)", artifactKey, version);
                return existing;
            }
            // Create new instance
            log.info("Creating new instance for {}:{}", artifactKey, version);
            var instance = factory.get();
            var versionedInstance = new VersionedInstance<>(version, instance);
            // Add to store atomically
            store.compute(artifactKey,
                          (_, existingList) -> {
                              var list = existingList == null
                                         ? new ArrayList<VersionedInstance< ?>>()
                                         : new ArrayList<>(existingList);
                              list.add(versionedInstance);
                              return list;
                          });
            return instance;
        }
    }

    /**
     * Find an instance matching the exact version (ignoring qualifiers).
     * <p>
     * Returns null if not found. This internal method uses null for the
     * double-checked locking pattern which requires separate check/extract phases.
     */
    private <T> T findExactVersion(String artifactKey, String version, Class<T> type) {
        var instances = store.get(artifactKey);
        if (instances == null) {
            return null;
        }
        var strippedVersion = stripQualifier(version);
        for (var vi : instances) {
            if (stripQualifier(vi.version())
                              .equals(strippedVersion) && type.isInstance(vi.instance())) {
                return type.cast(vi.instance());
            }
        }
        return null;
    }

    private static String stripQualifier(String version) {
        var dashIndex = version.indexOf('-');
        return dashIndex > 0
               ? version.substring(0, dashIndex)
               : version;
    }

    /**
     * Clear all stored instances.
     * <p>
     * Used for testing and shutdown.
     */
    public void clear() {
        store.clear();
        log.info("InfraStore cleared");
    }

    /**
     * Get the number of artifact keys stored.
     */
    public int size() {
        return store.size();
    }
}
