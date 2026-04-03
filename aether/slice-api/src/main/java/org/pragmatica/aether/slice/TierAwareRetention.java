package org.pragmatica.aether.slice;

public record TierAwareRetention(long postSealBufferMs, long postSealMaxCount) {
    private static final long DEFAULT_POST_SEAL_BUFFER_MS = 60_000L;

    private static final long DEFAULT_POST_SEAL_MAX_COUNT = 10_000L;

    public static TierAwareRetention tierAwareRetention(long postSealBufferMs, long postSealMaxCount) {
        return new TierAwareRetention(postSealBufferMs, postSealMaxCount);
    }

    public static TierAwareRetention tierAwareRetention() {
        return new TierAwareRetention(DEFAULT_POST_SEAL_BUFFER_MS, DEFAULT_POST_SEAL_MAX_COUNT);
    }
}
