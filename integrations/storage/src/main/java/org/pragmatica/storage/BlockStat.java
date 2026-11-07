package org.pragmatica.storage;

/// What a tier knows about one block it holds (A2b): its stored size in bytes -- what the tier holds, so ciphertext under
/// an encrypting tier -- and when the tier last wrote or refreshed it ([StorageTier#touch]), in epoch milliseconds by
/// the clock of whatever keeps the copy: this node's for memory and disk, the provider's for an S3 tier.
public record BlockStat(BlockId id, long size, long writtenAtMillis) {
    public static BlockStat blockStat(BlockId id, long size, long writtenAtMillis) {
        return new BlockStat(id, size, writtenAtMillis);
    }
}
