package org.pragmatica.storage;

import java.nio.file.Path;

import org.pragmatica.lang.io.FileOps;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;

/// Filesystem-backed storage tier with two-level directory sharding.
/// Files stored at: {basePath}/{hex[0:2]}/{hex[2:4]}/{fullHex}
/// Uses Promise.lift for non-blocking I/O on virtual threads.
public final class LocalDiskTier implements StorageTier {
    private static final Logger log = LoggerFactory.getLogger(LocalDiskTier.class);
    private static final Fn1<Cause, Throwable> READ_ERROR = t -> StorageError.ReadError.readError(t.getMessage());
    private static final Fn1<Cause, Throwable> WRITE_ERROR = t -> StorageError.WriteError.writeError(t.getMessage());

    private final Path basePath;
    private final AtomicLong usedBytes = new AtomicLong(0);
    private final long maxBytes;

    private LocalDiskTier(Path basePath, long maxBytes) {
        this.basePath = basePath;
        this.maxBytes = maxBytes;
    }

    public static Result<LocalDiskTier> localDiskTier(Path basePath, long maxBytes) {
        return FileOps.createDirectories(basePath)
                     .map(_ -> new LocalDiskTier(basePath, maxBytes))
                     .onSuccess(LocalDiskTier::calculateUsedBytes);
    }

    @Override
    public Promise<Option<byte[]>> get(BlockId id) {
        return Promise.lift(READ_ERROR, () -> readBlock(id));
    }

    @Override
    public Promise<Unit> put(BlockId id, byte[] content) {
        if (!reserveCapacity(content.length)) {
            return StorageError.TierFull.tierFull(TierLevel.LOCAL_DISK, usedBytes.get(), maxBytes).promise();
        }

        return Promise.lift(WRITE_ERROR, () -> writeBlock(id, content));
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

    @Override
    public Promise<Unit> delete(BlockId id) {
        return Promise.lift(WRITE_ERROR, () -> deleteBlock(id));
    }

    @Override
    public Promise<Boolean> exists(BlockId id) {
        return Promise.success(FileOps.exists(blockPath(id)));
    }

    @Override
    public TierLevel level() {
        return TierLevel.LOCAL_DISK;
    }

    @Override
    public long usedBytes() {
        return usedBytes.get();
    }

    @Override
    public long maxBytes() {
        return maxBytes;
    }

    private Option<byte[]> readBlock(BlockId id) {
        var path = blockPath(id);

        return FileOps.exists(path)
               ? some(FileOps.readBytes(path).unwrap())
               : none();
    }

    private Unit writeBlock(BlockId id, byte[] content) {
        var path = blockPath(id);
        FileOps.createDirectories(path.getParent()).unwrap();

        var previousSize = FileOps.exists(path) ? FileOps.size(path).unwrap() : 0L;
        FileOps.writeBytes(path, content).unwrap();

        // Capacity was pre-reserved for content.length. Correct for overwrites.
        if (previousSize > 0) {
            usedBytes.addAndGet(-previousSize);
        }

        return unit();
    }

    private Unit deleteBlock(BlockId id) {
        var path = blockPath(id);

        if (FileOps.exists(path)) {
            var fileSize = FileOps.size(path).unwrap();
            FileOps.delete(path).unwrap();
            usedBytes.addAndGet(-fileSize);
        }

        return unit();
    }

    private Path blockPath(BlockId id) {
        var hex = id.hexString();

        return basePath.resolve(hex.substring(0, 2))
                       .resolve(hex.substring(2, 4))
                       .resolve(hex);
    }

    private void calculateUsedBytes() {
        FileOps.walk(basePath, FileOps::isRegularFile)
                            .map(paths -> paths.stream().mapToLong(LocalDiskTier::fileSizeOrZero).sum())
                            .onSuccess(this::recordUsedBytes)
                            .onFailure(cause -> log.warn("Failed to calculate used bytes at {}: {}", basePath, cause.message()));
    }

    private void recordUsedBytes(long total) {
        usedBytes.set(total);
        log.info("LocalDiskTier at {} initialized: {} bytes in use", basePath, total);
    }

    private static long fileSizeOrZero(Path path) {
        return FileOps.size(path).or(0L);
    }
}
