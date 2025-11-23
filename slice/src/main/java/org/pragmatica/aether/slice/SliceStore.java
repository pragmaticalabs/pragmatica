package org.pragmatica.aether.slice;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;

public interface SliceStore {
    interface LoadedSlice {
        Artifact artifact();

        Result<Slice> slice();
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
