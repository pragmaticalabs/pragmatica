package org.pragmatica.storage;

import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.cloud.aws.s3.S3Client;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// S3-backed storage tier for cold/archive data.
/// Uses sharded S3 keys: {prefix}/{hex[0:2]}/{hex[2:4]}/{fullHex}
public final class RemoteTier implements StorageTier {
    private static final String CONTENT_TYPE = "application/octet-stream";

    private final S3Client s3Client;
    private final String prefix;
    private final long maxBytes;
    private final AtomicLong usedBytes = new AtomicLong(0);

    private RemoteTier(S3Client s3Client, String prefix, long maxBytes) {
        this.s3Client = s3Client;
        this.prefix = prefix;
        this.maxBytes = maxBytes;
    }

    /// Creates a RemoteTier from configuration, using default S3 client.
    public static RemoteTier remoteTier(RemoteTierConfig config) {
        return new RemoteTier(S3Client.s3Client(config.s3Config()), config.prefix(), config.maxBytes());
    }

    /// Creates a RemoteTier with an externally provided S3 client (for testing).
    public static RemoteTier remoteTier(S3Client s3Client, String prefix, long maxBytes) {
        return new RemoteTier(s3Client, prefix, maxBytes);
    }

    @Override
    public Promise<Option<byte[]>> get(BlockId id) {
        return s3Client.getObject(s3Key(id));
    }

    @Override
    public Promise<Unit> put(BlockId id, byte[] content) {
        if (!reserveCapacity(content.length)) {
            return StorageError.TierFull.tierFull(TierLevel.REMOTE, usedBytes.get(), maxBytes).promise();
        }

        return s3Client.putObject(s3Key(id), content, CONTENT_TYPE);
    }

    @Override
    public Promise<Unit> delete(BlockId id) {
        return s3Client.deleteObject(s3Key(id));
    }

    @Override
    public Promise<Boolean> exists(BlockId id) {
        return s3Client.headObject(s3Key(id));
    }

    @Override
    public TierLevel level() {
        return TierLevel.REMOTE;
    }

    @Override
    public long usedBytes() {
        return usedBytes.get();
    }

    @Override
    public long maxBytes() {
        return maxBytes;
    }

    /// Atomic CAS capacity reservation — prevents TOCTOU race.
    private boolean reserveCapacity(int contentLength) {
        long current;
        long updated;

        do {
            current = usedBytes.get();
            updated = current + contentLength;

            if (updated > maxBytes) {
                return false;
            }
        } while (!usedBytes.compareAndSet(current, updated));

        return true;
    }

    private String s3Key(BlockId id) {
        return prefix + "/" + id.shardPrefix() + "/" + id.hexString();
    }
}
