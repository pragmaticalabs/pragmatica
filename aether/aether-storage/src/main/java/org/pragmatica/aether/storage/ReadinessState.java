package org.pragmatica.aether.storage;

/// Storage readiness states during startup.
public enum ReadinessState {
    LOADING_SNAPSHOT,
    SNAPSHOT_LOADED,
    JOINING_CONSENSUS,
    READY
}
