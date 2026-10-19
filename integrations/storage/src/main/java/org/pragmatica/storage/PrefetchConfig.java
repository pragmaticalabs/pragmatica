package org.pragmatica.storage;

/// Configuration for cross-node prefetch hints.
///
/// @param accessThreshold minimum access count before a block generates a hint
/// @param maxHintsPerGossip maximum number of hints to piggyback per gossip round
/// @param cooldownMs minimum interval (ms) between prefetch attempts for the same block
/// @param observationWindowMs how long (ms) an observation counter may sit idle before it is reclaimed; a
///        block must reach `accessThreshold` with no gap longer than this
/// @param maxTrackedBlocks soft cap on blocks with a live observation counter; past it new blocks are
///        not observed until idle counters are reclaimed
public record PrefetchConfig(int accessThreshold,
                             int maxHintsPerGossip,
                             long cooldownMs,
                             long observationWindowMs,
                             int maxTrackedBlocks) {
    private static final long DEFAULT_OBSERVATION_WINDOW_MS = 300_000L;
    private static final int DEFAULT_MAX_TRACKED_BLOCKS = 10_000;

    /// Clamp parameters to valid ranges.
    public PrefetchConfig {
        accessThreshold = Math.max(accessThreshold, 1);
        maxHintsPerGossip = Math.max(maxHintsPerGossip, 1);
        cooldownMs = Math.max(cooldownMs, 0);
        observationWindowMs = Math.max(observationWindowMs, 1);
        maxTrackedBlocks = Math.max(maxTrackedBlocks, 1);
    }

    /// Default configuration: threshold=10, maxHints=5, cooldown=30s, observation window=5min, 10,000 tracked blocks.
    public static PrefetchConfig prefetchConfig() {
        return new PrefetchConfig(10, 5, 30_000L, DEFAULT_OBSERVATION_WINDOW_MS, DEFAULT_MAX_TRACKED_BLOCKS);
    }

    /// Custom threshold, hint count and cooldown, with the default observation window and tracked-block cap.
    public static PrefetchConfig prefetchConfig(int accessThreshold, int maxHintsPerGossip, long cooldownMs) {
        return new PrefetchConfig(accessThreshold,
                                  maxHintsPerGossip,
                                  cooldownMs,
                                  DEFAULT_OBSERVATION_WINDOW_MS,
                                  DEFAULT_MAX_TRACKED_BLOCKS);
    }

    /// Full factory with custom parameters.
    public static PrefetchConfig prefetchConfig(int accessThreshold,
                                                int maxHintsPerGossip,
                                                long cooldownMs,
                                                long observationWindowMs,
                                                int maxTrackedBlocks) {
        return new PrefetchConfig(accessThreshold, maxHintsPerGossip, cooldownMs, observationWindowMs, maxTrackedBlocks);
    }
}
