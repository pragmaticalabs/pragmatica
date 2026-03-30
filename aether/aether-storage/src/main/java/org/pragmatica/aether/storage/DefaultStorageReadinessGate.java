package org.pragmatica.aether.storage;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.unitResult;

/// Default implementation of storage readiness gate.
/// Uses two unresolved promises as barriers for read and write readiness.
final class DefaultStorageReadinessGate implements StorageReadinessGate {
    private static final Logger log = LoggerFactory.getLogger(DefaultStorageReadinessGate.class);

    private volatile ReadinessState state = ReadinessState.LOADING_SNAPSHOT;
    private final Promise<Unit> readReady = Promise.promise();
    private final Promise<Unit> writeReady = Promise.promise();

    DefaultStorageReadinessGate() {
        log.info("Storage readiness gate initialized in {} state", state);
    }

    @Override
    public boolean isReadReady() {
        return state.ordinal() >= ReadinessState.SNAPSHOT_LOADED.ordinal();
    }

    @Override
    public boolean isWriteReady() {
        return state == ReadinessState.READY;
    }

    @Override
    public Promise<Unit> awaitReadReady() {
        return readReady;
    }

    @Override
    public Promise<Unit> awaitWriteReady() {
        return writeReady;
    }

    @Override
    public ReadinessState state() {
        return state;
    }

    @Override
    public void snapshotLoaded() {
        if (state != ReadinessState.LOADING_SNAPSHOT) {
            log.warn("snapshotLoaded() called in unexpected state: {}", state);
            return;
        }

        state = ReadinessState.SNAPSHOT_LOADED;
        readReady.resolve(unitResult());
        log.info("Snapshot loaded -- reads enabled, transitioning to {}", state);
    }

    @Override
    public void consensusSynced() {
        if (state != ReadinessState.SNAPSHOT_LOADED && state != ReadinessState.JOINING_CONSENSUS) {
            log.warn("consensusSynced() called in unexpected state: {}", state);
            return;
        }

        state = ReadinessState.READY;
        writeReady.resolve(unitResult());
        log.info("Consensus synced -- writes enabled, transitioning to {}", state);
    }
}
