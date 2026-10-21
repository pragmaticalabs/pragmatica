package org.pragmatica.storage;

/// A hint about block access frequency, gossiped over SWIM between nodes. A node that receives one decides
/// whether to prefetch the block it names; the instance the block belongs to travels beside the hints, not in
/// each of them.
///
/// @param blockIdHex hex-encoded block ID (portable across nodes)
/// @param accessCount number of accesses since last hint collection
public record PrefetchHint(String blockIdHex, int accessCount) {
    /// Factory method.
    public static PrefetchHint prefetchHint(String blockIdHex, int accessCount) {
        return new PrefetchHint(blockIdHex, accessCount);
    }
}
