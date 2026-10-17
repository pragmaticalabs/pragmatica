package org.pragmatica.storage;

import java.util.EnumSet;
import java.util.Set;


/// Tracks which tiers hold a block and its lifecycle state.
///
/// @param blockId content-addressed block identifier
/// @param presentIn set of tiers that currently hold this block
/// @param refCount number of named references pointing to this block
/// @param lastAccessedAt timestamp of last read access
/// @param createdAt timestamp when first stored
/// @param accessCount total number of read accesses (for frequency-based eviction)
/// @param orphanedAt timestamp of the instant refCount last transitioned from >0 to <=0; 0 while
/// referenced. [StorageGarbageCollector]'s grace period runs from here (#737 fix round 2) --
/// lastAccessedAt is set at creation and refreshed only on a successful read, so using it for the
/// grace filter made a long-idle-but-still-referenced block immediately collectible the instant it
/// was orphaned, with no grace at all.
public record BlockLifecycle(BlockId blockId,
                             Set<TierLevel> presentIn,
                             int refCount,
                             long lastAccessedAt,
                             long createdAt,
                             int accessCount,
                             long orphanedAt) {
    /// Defensive copy — ensure immutability of the tier set.
    public BlockLifecycle {
        presentIn = presentIn.isEmpty()
                    ? EnumSet.noneOf(TierLevel.class)
                    : EnumSet.copyOf(presentIn);
    }

    public static BlockLifecycle blockLifecycle(BlockId blockId, TierLevel initialTier) {
        var now = System.currentTimeMillis();

        return new BlockLifecycle(blockId, EnumSet.of(initialTier), 1, now, now, 0, 0L);
    }

    /// Reconstruction factory for deserialization from a pre-#737-round-2 KV-Store/snapshot
    /// representation that carries no orphaning instant. A refCount<=0 entry falls back to
    /// lastAccessedAt for orphanedAt, reproducing the old (grace-less) behavior for that one
    /// legacy entry until it is next re-orphaned or collected with an accurate timestamp; a live
    /// entry gets 0 (unorphaned). Prefer the 7-arg overload wherever the orphaning instant is
    /// actually known.
    public static BlockLifecycle blockLifecycle(BlockId blockId,
                                                Set<TierLevel> presentIn,
                                                int refCount,
                                                long lastAccessedAt,
                                                long createdAt,
                                                int accessCount) {
        return new BlockLifecycle(blockId,
                                  presentIn,
                                  refCount,
                                  lastAccessedAt,
                                  createdAt,
                                  accessCount,
                                  refCount <= 0
                                  ? lastAccessedAt
                                  : 0L);
    }

    /// Reconstruction factory carrying an explicit orphaning instant (current snapshot/KV-Store format).
    public static BlockLifecycle blockLifecycle(BlockId blockId,
                                                Set<TierLevel> presentIn,
                                                int refCount,
                                                long lastAccessedAt,
                                                long createdAt,
                                                int accessCount,
                                                long orphanedAt) {
        return new BlockLifecycle(blockId, presentIn, refCount, lastAccessedAt, createdAt, accessCount, orphanedAt);
    }

    public BlockLifecycle withTierAdded(TierLevel tier) {
        var tiers = EnumSet.copyOf(presentIn);

        tiers.add(tier);

        return new BlockLifecycle(blockId, tiers, refCount, lastAccessedAt, createdAt, accessCount, orphanedAt);
    }

    public BlockLifecycle withTierRemoved(TierLevel tier) {
        var tiers = EnumSet.copyOf(presentIn);

        tiers.remove(tier);

        return new BlockLifecycle(blockId, tiers, refCount, lastAccessedAt, createdAt, accessCount, orphanedAt);
    }

    /// Increments refCount. Resurrecting an orphaned block (refCount was already <=0) clears
    /// orphanedAt -- it is referenced again and must drop out of GC's orphan-grace filter.
    public BlockLifecycle withRefCountIncremented() {
        var resurrected = refCount <= 0
                          ? 0L
                          : orphanedAt;

        return new BlockLifecycle(blockId, presentIn, refCount + 1, lastAccessedAt, createdAt, accessCount, resurrected);
    }

    /// Decrements refCount (floored at 0). Stamps orphanedAt with the current instant only on the
    /// transition from referenced to orphaned; a further decrement while already at the floor
    /// leaves orphanedAt where it was -- it does not get a later, more-generous grace start every
    /// time something redundantly decrements an already-dead block.
    public BlockLifecycle withRefCountDecremented() {
        var newRefCount = Math.max(0, refCount - 1);
        var newOrphanedAt = newRefCount <= 0 && refCount > 0
                            ? System.currentTimeMillis()
                            : orphanedAt;

        return new BlockLifecycle(blockId, presentIn, newRefCount, lastAccessedAt, createdAt, accessCount, newOrphanedAt);
    }

    public BlockLifecycle withAccessTimestamp() {
        return new BlockLifecycle(blockId,
                                  presentIn,
                                  refCount,
                                  System.currentTimeMillis(),
                                  createdAt,
                                  accessCount + 1,
                                  orphanedAt);
    }

    public boolean isOrphaned() {
        return refCount <= 0;
    }
}
