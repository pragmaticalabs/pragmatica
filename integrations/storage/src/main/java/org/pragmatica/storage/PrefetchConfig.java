package org.pragmatica.storage;

/// Configuration for cross-node prefetch hints.
///
/// @param accessThreshold minimum access count before a block generates a hint
/// @param maxHintsPerGossip maximum number of hints to piggyback per gossip round
/// @param cooldownMs minimum interval (ms) between prefetch attempts for the same block
public record PrefetchConfig(int accessThreshold, int maxHintsPerGossip, long cooldownMs) {

    /// Clamp parameters to valid ranges.
    public PrefetchConfig {
        accessThreshold = Math.max(accessThreshold, 1);
        maxHintsPerGossip = Math.max(maxHintsPerGossip, 1);
        cooldownMs = Math.max(cooldownMs, 0);
    }

    /// Default configuration: threshold=10, maxHints=5, cooldown=30s.
    public static PrefetchConfig prefetchConfig() {
        return new PrefetchConfig(10, 5, 30_000L);
    }

    /// Full factory with custom parameters.
    public static PrefetchConfig prefetchConfig(int accessThreshold, int maxHintsPerGossip, long cooldownMs) {
        return new PrefetchConfig(accessThreshold, maxHintsPerGossip, cooldownMs);
    }
}
