package org.pragmatica.aether.slice;
public record TierAwareRetention( long postSealBufferMs, long postSealMaxCount) {
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
