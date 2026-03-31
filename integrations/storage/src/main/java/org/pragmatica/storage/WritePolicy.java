package org.pragmatica.storage;

/// Controls how writes propagate across storage tiers.
///
/// WRITE_THROUGH: writes to ALL tiers synchronously (durable tier first, then cache tiers).
/// WRITE_BEHIND: writes to fast (first) tier synchronously, queues slow tier writes for async background flush.
public enum WritePolicy {
    WRITE_THROUGH,
    WRITE_BEHIND
}
