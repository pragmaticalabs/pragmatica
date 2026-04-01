package org.pragmatica.storage;

/// Strategy for selecting blocks to demote from a tier.
public enum DemotionStrategy {
    /// Oldest first (by createdAt).
    AGE,
    /// Least frequently used (by accessCount).
    LFU,
    /// Least recently used (by lastAccessedAt).
    LRU,
    /// Largest blocks first (by block data size in the tier).
    SIZE_PRESSURE
}
