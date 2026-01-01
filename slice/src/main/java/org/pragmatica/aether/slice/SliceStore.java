package org.pragmatica.aether.slice;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

public interface SliceStore {
    /**
     * Create a new SliceStore instance with shared library classloader.
     *
     * @param registry            Registry for tracking loaded slices
     * @param repositories        Repositories to search for slice JARs
     * @param sharedLibraryLoader ClassLoader for shared dependencies across slices
     *
     * @return SliceStore implementation
     */
    static SliceStore sliceStore(SliceRegistry registry,
                                 List<Repository> repositories,
                                 SharedLibraryClassLoader sharedLibraryLoader) {
        return SliceStoreImpl.sliceStore(registry, repositories, sharedLibraryLoader);
    }

    interface LoadedSlice {
        Artifact artifact();

        Slice slice();
    }

    Promise<Unit> unload(Artifact artifact);

    List<LoadedSlice> loaded();

    /**
     * Load a slice into memory but do not activate it.
     * This corresponds to the LOADING → LOADED state transition.
     */
    Promise<LoadedSlice> loadSlice(Artifact artifact);

    /**
     * Activate a previously loaded slice, making it ready to serve requests.
     * This corresponds to the ACTIVATING → ACTIVE state transition.
     */
    Promise<LoadedSlice> activateSlice(Artifact artifact);

    /**
     * Deactivate an active slice, but keep it loaded in memory.
     * This corresponds to the DEACTIVATING → LOADED state transition.
     */
    Promise<LoadedSlice> deactivateSlice(Artifact artifact);

    /**
     * Unload a slice from memory completely.
     * This corresponds to the UNLOADING → (removed) state transition.
     */
    Promise<Unit> unloadSlice(Artifact artifact);
}
