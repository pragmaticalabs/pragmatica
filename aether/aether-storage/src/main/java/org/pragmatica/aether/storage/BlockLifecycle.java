package org.pragmatica.aether.storage;

import java.util.EnumSet;
import java.util.Set;

/// Tracks which tiers hold a block and its lifecycle state.
///
/// @param blockId content-addressed block identifier
/// @param presentIn set of tiers that currently hold this block
/// @param refCount number of named references pointing to this block
/// @param lastAccessedAt timestamp of last read access
/// @param createdAt timestamp when first stored
public record BlockLifecycle(BlockId blockId,
                             Set<TierLevel> presentIn,
                             int refCount,
                             long lastAccessedAt,
                             long createdAt) {

    public static BlockLifecycle blockLifecycle(BlockId blockId, TierLevel initialTier) {
        var now = System.currentTimeMillis();
        return new BlockLifecycle(blockId, EnumSet.of(initialTier), 1, now, now);
    }

    public BlockLifecycle withTierAdded(TierLevel tier) {
        var tiers = EnumSet.copyOf(presentIn);
        tiers.add(tier);
        return new BlockLifecycle(blockId, tiers, refCount, lastAccessedAt, createdAt);
    }

    public BlockLifecycle withTierRemoved(TierLevel tier) {
        var tiers = EnumSet.copyOf(presentIn);
        tiers.remove(tier);
        return new BlockLifecycle(blockId, tiers, refCount, lastAccessedAt, createdAt);
    }

    public BlockLifecycle withRefCountIncremented() {
        return new BlockLifecycle(blockId, presentIn, refCount + 1, lastAccessedAt, createdAt);
    }

    public BlockLifecycle withRefCountDecremented() {
        return new BlockLifecycle(blockId, presentIn, Math.max(0, refCount - 1), lastAccessedAt, createdAt);
    }

    public BlockLifecycle withAccessTimestamp() {
        return new BlockLifecycle(blockId, presentIn, refCount, System.currentTimeMillis(), createdAt);
    }

    public boolean isOrphaned() {
        return refCount <= 0;
    }
}
