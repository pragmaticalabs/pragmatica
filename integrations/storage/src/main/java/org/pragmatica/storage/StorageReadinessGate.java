package org.pragmatica.storage;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// Storage readiness gate. Blocks write operations until consensus is available.
/// Allows reads after local snapshot is loaded.
public interface StorageReadinessGate {

    /// True after local snapshot is loaded. Reads are safe.
    boolean isReadReady();

    /// True after consensus sync is complete. Writes are safe.
    boolean isWriteReady();

    /// Wait for read readiness.
    Promise<Unit> awaitReadReady();

    /// Wait for write readiness.
    Promise<Unit> awaitWriteReady();

    /// Current readiness state.
    ReadinessState state();

    /// Signal that snapshot loading is complete.
    void snapshotLoaded();

    /// Signal that consensus sync is complete.
    void consensusSynced();

    /// Factory.
    static StorageReadinessGate storageReadinessGate() {
        return new DefaultStorageReadinessGate();
    }
}
