package org.pragmatica.aether.storage;

import org.pragmatica.dht.DHTClient;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.StorageTier;
import org.pragmatica.storage.TierLevel;

import java.nio.charset.StandardCharsets;

/// DHT-backed storage tier for distributed block storage.
/// Uses DHTClient for cluster-wide replication of content-addressed blocks.
/// Intended as the durable (last) tier in a StorageInstance, with MemoryTier as the fast cache tier.
public final class DhtStorageTier implements StorageTier {
    private final DHTClient dhtClient;
    private final byte[] keyPrefixBytes;

    private DhtStorageTier(DHTClient dhtClient, String keyPrefix) {
        this.dhtClient = dhtClient;
        this.keyPrefixBytes = (keyPrefix + "/").getBytes(StandardCharsets.UTF_8);
    }

    /// Create a DHT-backed storage tier with the given key prefix.
    /// The prefix avoids collision with other DHT data (e.g., metadata keys).
    public static DhtStorageTier dhtStorageTier(DHTClient dhtClient, String keyPrefix) {
        return new DhtStorageTier(dhtClient, keyPrefix);
    }

    @Override
    public Promise<Option<byte[]>> get(BlockId id) {
        return dhtClient.get(buildKey(id));
    }

    @Override
    public Promise<Unit> put(BlockId id, byte[] content) {
        return dhtClient.put(buildKey(id), content);
    }

    @Override
    public Promise<Unit> delete(BlockId id) {
        return dhtClient.remove(buildKey(id)).mapToUnit();
    }

    @Override
    public Promise<Boolean> exists(BlockId id) {
        return dhtClient.exists(buildKey(id));
    }

    @Override
    public TierLevel level() {
        return TierLevel.REMOTE;
    }

    @Override
    public long usedBytes() {
        return 0;
    }

    @Override
    public long maxBytes() {
        return Long.MAX_VALUE;
    }

    private byte[] buildKey(BlockId id) {
        var hex = id.hexString().getBytes(StandardCharsets.UTF_8);
        var key = new byte[keyPrefixBytes.length + hex.length];

        System.arraycopy(keyPrefixBytes, 0, key, 0, keyPrefixBytes.length);
        System.arraycopy(hex, 0, key, keyPrefixBytes.length, hex.length);

        return key;
    }
}
