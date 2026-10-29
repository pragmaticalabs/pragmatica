package org.pragmatica.storage;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Storage tier interface -- implementations provide get/put/delete for content-addressed blocks.
public interface StorageTier {
    Promise<Option<byte[]>> get(BlockId id);
    Promise<Unit> put(BlockId id, byte[] content);
    Promise<Unit> delete(BlockId id);
    Promise<Boolean> exists(BlockId id);
    TierLevel level();
    long usedBytes();
    long maxBytes();

    /// True when this tier is a cluster-wide shared store (e.g. DHT-backed) rather than
    /// node-private. Node-local garbage collection must never delete a block from a shared
    /// tier on the strength of this node's own refcount belief -- another node may still hold
    /// a live reference. Defaults to false; only a shared-tier implementation overrides it.
    default boolean isShared() {
        return false;
    }

    /// True when a successful [#put] means the block survives a power loss or a kernel crash on
    /// this node -- the bytes and the entries naming them are on the device, not only in the page
    /// cache or in memory. Every write a storage instance acknowledges must have landed on every
    /// durable tier it holds (#1567); a non-durable tier is a cache. Defaults to false. True for
    /// [LocalDiskTier], for the [RemoteTier] a node builds from `[storage.<instance>.shared] kind = "s3"`
    /// ([RemoteTier#durableRemoteTier], #249: the put resolved on the provider's 200, so "survives" means
    /// the provider's durability, not Aether's), and for a wrapper delegating to either. An in-memory DHT
    /// tier is NOT durable however many peers hold a copy (#1544), and a [RemoteTier] built by its test or
    /// pre-#249 factories reports false.
    default boolean isDurable() {
        return false;
    }

    /// Writes `value` as version `version` (at least 0) of anchor `name`, and resolves once that version would survive
    /// what [#put] survives on this tier: on a durable tier, the device (file and directory forced) or the provider's
    /// acknowledgement. Writing a version again replaces it. Every write of one version must carry the same value: that is
    /// the caller's duty, not checked here, so a different value replaces the old one silently; only a read across
    /// tiers notices ([AnchorError.AnchorDiverged]).
    ///
    /// The default stores nothing on a non-durable tier, which was never a cold-boot source, and REFUSES on a durable one
    /// with [AnchorError.AnchorsUnsupported]: a durable tier that answered "nothing stored" would lose every name
    /// anchored on it at the next cold boot.
    default Promise<Unit> putAnchor(AnchorName name, long version, byte[] value) {
        return isDurable()
               ? AnchorError.AnchorsUnsupported.anchorsUnsupported(level()).promise()
               : Promise.unitPromise();
    }

    /// The highest version of anchor `name` this tier holds, or none. A newest version that cannot be read back as
    /// written fails with [AnchorError.AnchorCorrupt]; an older version is never returned in its place. The default is
    /// none on a non-durable tier and [AnchorError.AnchorsUnsupported] on a durable one, as for [#putAnchor].
    default Promise<Option<Anchor>> latestAnchor(AnchorName name) {
        return isDurable()
               ? AnchorError.AnchorsUnsupported.anchorsUnsupported(level()).promise()
               : Promise.success(Option.none());
    }

    /// Removes versions of anchor `name` below `below`, examining at most `scanLimit` versions from the newest end (a
    /// tier that lists cheaply may examine all of them). Never removes a version at or above `below`. Best effort: a
    /// version missed now is removed by a later call. The default removes nothing.
    default Promise<Unit> pruneAnchors(AnchorName name, long below, int scanLimit) {
        return Promise.unitPromise();
    }

    /// Gives up whatever the tier holds exclusively: the mount claim of a [LocalDiskTier]. Called once, when the
    /// storage instance shuts down. Defaults to nothing.
    default Unit release() {
        return Unit.unit();
    }

    /// Hands the tier the degrade reporter of the instance it belongs to, so a condition only the tier can see (a durable disk
    /// tier that is full) reaches the operator. Called once, when the instance is assembled. Defaults to nothing.
    default Unit reportTo(DegradeReporter reporter) {
        return Unit.unit();
    }
}
