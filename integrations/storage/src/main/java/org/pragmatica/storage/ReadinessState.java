package org.pragmatica.storage;

/// Storage readiness states during startup.
public enum ReadinessState {
    LOADING_SNAPSHOT,
    SNAPSHOT_LOADED,
    READY
}
