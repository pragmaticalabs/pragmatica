package org.pragmatica.storage;

/// A hint about block access frequency, piggybacked on SWIM gossip messages.
/// Remote nodes use these hints to decide whether to prefetch blocks they don't have locally.
///
/// @param blockIdHex hex-encoded block ID (portable across nodes)
/// @param accessCount number of accesses since last hint collection
/// @param tierLevel the tier where the block resides on the originating node
public record PrefetchHint(String blockIdHex, int accessCount, String tierLevel) {

    /// Factory method.
    public static PrefetchHint prefetchHint(String blockIdHex, int accessCount, String tierLevel) {
        return new PrefetchHint(blockIdHex, accessCount, tierLevel);
    }

    /// Construct a hint from domain types.
    public static PrefetchHint prefetchHint(BlockId blockId, int accessCount, TierLevel tier) {
        return new PrefetchHint(blockId.hexString(), accessCount, tier.name());
    }
}
