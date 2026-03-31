package org.pragmatica.aether.slice;

/// Post-seal retention: how long to keep events in ring buffer after they have been sealed to storage.
///
/// Once events are safely persisted in AHSE, the ring buffer can evict them more aggressively.
/// The post-seal limits replace the normal retention limits for events at or below the sealed offset.
///
/// @param postSealBufferMs maximum time (ms) to keep sealed events in the ring buffer
/// @param postSealMaxCount maximum number of sealed events to keep in the ring buffer
public record TierAwareRetention(long postSealBufferMs, long postSealMaxCount) {

    private static final long DEFAULT_POST_SEAL_BUFFER_MS = 60_000L;
    private static final long DEFAULT_POST_SEAL_MAX_COUNT = 10_000L;

    /// Create a tier-aware retention policy with custom limits.
    public static TierAwareRetention tierAwareRetention(long postSealBufferMs, long postSealMaxCount) {
        return new TierAwareRetention(postSealBufferMs, postSealMaxCount);
    }

    /// Create a tier-aware retention policy with defaults: 60s after seal, max 10,000 events.
    public static TierAwareRetention tierAwareRetention() {
        return new TierAwareRetention(DEFAULT_POST_SEAL_BUFFER_MS, DEFAULT_POST_SEAL_MAX_COUNT);
    }
}
