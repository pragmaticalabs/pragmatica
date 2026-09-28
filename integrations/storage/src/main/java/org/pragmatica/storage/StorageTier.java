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
    /// durable tier it holds (#1567); a non-durable tier is a cache. Defaults to false; in rc4 only
    /// [LocalDiskTier] (and a wrapper delegating to it) returns true. An in-memory DHT tier is NOT
    /// durable however many peers hold a copy (#1544), and [RemoteTier] reports false until it has
    /// a production construction path (#249).
    default boolean isDurable() {
        return false;
    }
}
