package org.pragmatica.storage;

/// Strategy for selecting blocks to demote from a tier.
public enum DemotionStrategy {
    /// Oldest first (by createdAt).
    AGE,
    /// Least frequently used (by accessCount).
    LFU,
    /// Least recently used (by lastAccessedAt).
    LRU,
    /// Meant to take the largest blocks first, but a block carries no size yet, so it behaves as [#LRU]: least recently
    /// read first (by the reads the access tracker saw). The size key arrives with the snapshot format change (#2443).
    SIZE_PRESSURE
}
