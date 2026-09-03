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
}
