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
}
