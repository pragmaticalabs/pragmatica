package org.pragmatica.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
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
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Filesystem-backed storage tier with two-level directory sharding.
/// Files stored at: {basePath}/{hex[0:2]}/{hex[2:4]}/{fullHex}
/// Uses Promise.lift for non-blocking I/O on virtual threads.
public final class LocalDiskTier implements StorageTier {
    private static final Logger log = LoggerFactory.getLogger(LocalDiskTier.class);

    private static final Fn1<Cause, Throwable> READ_ERROR = t -> StorageError.ReadError.readError(t.getMessage());

    private static final Fn1<Cause, Throwable> WRITE_ERROR = t -> StorageError.WriteError.writeError(t.getMessage());

    /// Per-read deadline (defense in depth under the artifact-store resolve budget). The
    /// blocking `readBlock` runs on the async executor; without a deadline a read that wedges
    /// at the filesystem layer (a stuck network mount, a kernel-blocked syscall) leaves the
    /// lifted promise unresolved forever and stalls every chain awaiting it. The deadline
    /// forcibly resolves the read with `CoreError.Timeout`. Generous: a healthy local-disk
    /// block read completes in milliseconds.
    private static final TimeSpan DEFAULT_READ_TIMEOUT = timeSpan(30).seconds();

    private final Path basePath;
    private final AtomicLong usedBytes = new AtomicLong(0);
    private final long maxBytes;
    private final TimeSpan readTimeout;
    private final Option<Fn1<Result<Option<byte[]>>, BlockId>> readerOverride;
    private final Fn2<Result<Unit>, Path, byte[]> writer;

    private LocalDiskTier(Path basePath,
                          long maxBytes,
                          TimeSpan readTimeout,
                          Option<Fn1<Result<Option<byte[]>>, BlockId>> readerOverride,
                          Option<Fn2<Result<Unit>, Path, byte[]>> writerOverride) {
        this.basePath = basePath;
        this.maxBytes = maxBytes;
        this.readTimeout = readTimeout;
        this.readerOverride = readerOverride;
        this.writer = writerOverride.or(FileOps::writeBytes);
    }

    public static Result<LocalDiskTier> localDiskTier(Path basePath, long maxBytes) {
        return localDiskTier(basePath, maxBytes, DEFAULT_READ_TIMEOUT, none());
    }

    /// Variant with an explicit per-read deadline and an injectable read operation. Used by
    /// tests that drive the read-deadline firing with a short `TimeSpan` against a blocking
    /// read. Production callers use the two-arg factory (default disk reader, default deadline).
    public static Result<LocalDiskTier> localDiskTier(Path basePath,
                                                      long maxBytes,
                                                      TimeSpan readTimeout,
                                                      Option<Fn1<Result<Option<byte[]>>, BlockId>> readerOverride) {
        return localDiskTier(basePath, maxBytes, readTimeout, readerOverride, none());
    }

    /// Variant with an injectable file write, for tests that need a write to fail after N bytes
    /// (a disk that fills mid-block) without a real ENOSPC.
    static Result<LocalDiskTier> localDiskTier(Path basePath,
                                               long maxBytes,
                                               TimeSpan readTimeout,
                                               Option<Fn1<Result<Option<byte[]>>, BlockId>> readerOverride,
                                               Option<Fn2<Result<Unit>, Path, byte[]>> writerOverride) {
        return FileOps.createDirectories(basePath)
                      .map(_ -> new LocalDiskTier(basePath, maxBytes, readTimeout, readerOverride, writerOverride))
                      .onSuccess(LocalDiskTier::calculateUsedBytes);
    }

    @Override
    public Promise<Option<byte[]>> get(BlockId id) {
        // Per-read deadline placed immediately after the lift (per Promise.timeout's contract)
        // so a wedged blocking read is cancelled rather than a downstream transformation.
        return Promise.lift(READ_ERROR,
                            () -> readBlock(id))
                      .timeout(readTimeout)
                      .flatMap(Promise::resolved);
    }

    @Override
    public Promise<Unit> put(BlockId id, byte[] content) {
        if (!reserveCapacity(content.length)) {
            return StorageError.TierFull.tierFull(TierLevel.LOCAL_DISK,
                                                  usedBytes.get(),
                                                  maxBytes)
                                        .promise();
        }
        // A write that failed after reserveCapacity keeps no reservation: the tier would otherwise
        // over-count by the whole block until restart (review of #1095, SF-2 — reproduced under a
        // real ENOSPC). Released here, once, whatever stage failed; the partial file is writeBlock's.
        return Promise.lift(WRITE_ERROR,
                            () -> writeBlock(id, content))
                      .flatMap(Promise::resolved)
                      .onFailure(_ -> usedBytes.addAndGet(-content.length));
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
        return Promise.lift(WRITE_ERROR, () -> deleteBlock(id)).flatMap(Promise::resolved);
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

    private Result<Option<byte[]>> readBlock(BlockId id) {
        return readerOverride.fold(() -> readBlockDefault(id), reader -> reader.apply(id));
    }

    private Result<Option<byte[]>> readBlockDefault(BlockId id) {
        var path = blockPath(id);

        return FileOps.exists(path)
               ? FileOps.readBytes(path).map(Option::some)
               : Result.success(none());
    }

    private Result<Unit> writeBlock(BlockId id, byte[] content) {
        var path = blockPath(id);

        return FileOps.createDirectories(path.getParent())
                      .flatMap(_ -> existingSize(path))
                      .flatMap(previousSize -> writer.apply(path, content)
                                                      .onSuccess(_ -> correctUsedBytes(previousSize))
                                                      .onFailure(_ -> discardFailedWrite(path, previousSize)));
    }

    /// `Files.write` is TRUNCATE_EXISTING with no temp-and-rename, so a write that fails mid-way
    /// leaves a partial file that the read waterfall would serve and then fail on its integrity
    /// check (review of #1095, B-1). Whatever regular file is at the path after the failure is
    /// removed — it is either the truncated new content (never counted, only reserved) or what is
    /// left of the previous copy (counted, and now gone either way), so `previousSize` leaves the
    /// count exactly when a regular file was there to overwrite.
    private void discardFailedWrite(Path path, long previousSize) {
        if (!Files.isRegularFile(path)) {
            return;
        }

        FileOps.delete(path).onFailure(cause -> log.warn("Partial block at {} could not be removed after a failed write: {}",
                                                         path,
                                                         cause.message()));
        correctUsedBytes(previousSize);
    }

    private Result<Long> existingSize(Path path) {
        return FileOps.exists(path)
               ? FileOps.size(path)
               : Result.success(0L);
    }

    private void correctUsedBytes(long previousSize) {
        // Capacity was pre-reserved for content.length. Correct for overwrites.
        if (previousSize > 0) {
            usedBytes.addAndGet(-previousSize);
        }
    }

    private Result<Unit> deleteBlock(BlockId id) {
        var path = blockPath(id);

        if (!FileOps.exists(path)) {
            return Result.success(unit());
        }

        return FileOps.size(path).flatMap(fileSize -> FileOps.delete(path).onSuccess(_ -> usedBytes.addAndGet(-fileSize)));
    }

    private Path blockPath(BlockId id) {
        var hex = id.hexString();

        return basePath.resolve(hex.substring(0, 2))
                       .resolve(hex.substring(2, 4))
                       .resolve(hex);
    }

    private void calculateUsedBytes() {
        FileOps.walk(basePath, FileOps::isRegularFile)
               .map(paths -> paths.stream()
                                  .mapToLong(LocalDiskTier::fileSizeOrZero)
                                  .sum())
               .onSuccess(this::recordUsedBytes)
               .onFailure(cause -> log.warn("Failed to calculate used bytes at {}: {}",
                                            basePath,
                                            cause.message()));
    }

    private void recordUsedBytes(long total) {
        usedBytes.set(total);
        log.info("LocalDiskTier at {} initialized: {} bytes in use", basePath, total);
    }

    private static long fileSizeOrZero(Path path) {
        return FileOps.size(path).or(0L);
    }
}
