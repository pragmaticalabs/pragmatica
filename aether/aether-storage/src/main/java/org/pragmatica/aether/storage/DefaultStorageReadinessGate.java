package org.pragmatica.aether.storage;

import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.unitResult;

/// Default implementation of storage readiness gate.
/// Uses AtomicReference for thread-safe state transitions and
/// two unresolved promises as barriers for read and write readiness.
final class DefaultStorageReadinessGate implements StorageReadinessGate {
    private static final Logger log = LoggerFactory.getLogger(DefaultStorageReadinessGate.class);

    private final AtomicReference<ReadinessState> state = new AtomicReference<>(ReadinessState.LOADING_SNAPSHOT);
    private final Promise<Unit> readReady = Promise.promise();
    private final Promise<Unit> writeReady = Promise.promise();

    DefaultStorageReadinessGate() {
        log.info("Storage readiness gate initialized in {} state", state.get());
    }

    @Override
    public boolean isReadReady() {
        return state.get() != ReadinessState.LOADING_SNAPSHOT;
    }

    @Override
    public boolean isWriteReady() {
        return state.get() == ReadinessState.READY;
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
        return state.get();
    }

    @Override
    public void snapshotLoaded() {
        if (!state.compareAndSet(ReadinessState.LOADING_SNAPSHOT, ReadinessState.SNAPSHOT_LOADED)) {
            log.warn("snapshotLoaded() called in unexpected state: {}", state.get());
            return;
        }

        readReady.resolve(unitResult());
        log.info("Snapshot loaded -- reads enabled");
    }

    @Override
    public void consensusSynced() {
        if (!state.compareAndSet(ReadinessState.SNAPSHOT_LOADED, ReadinessState.READY)) {
            log.warn("consensusSynced() called in unexpected state: {}", state.get());
            return;
        }

        writeReady.resolve(unitResult());
        log.info("Consensus synced -- writes enabled");
    }
}
