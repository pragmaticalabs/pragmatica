package org.pragmatica.storage;

/// Storage tier levels ordered by access latency.
public enum TierLevel {
    MEMORY,
    LOCAL_DISK,
    REMOTE
}
