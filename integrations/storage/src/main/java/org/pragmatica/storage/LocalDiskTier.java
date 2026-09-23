package org.pragmatica.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    /// A block is written to a sibling `<hex>.<n>.partial` file and renamed over the block path
    /// once complete, so the block path only ever holds a whole copy — the previous one until the
    /// rename, the new one after it. Without this the TRUNCATE_EXISTING in-place write destroyed
    /// the previous copy at open and left a truncated file that the read waterfall would serve and
    /// then fail on its integrity check (review of #1095, B-1, reproduced under a real ENOSPC).
    /// The rename is `FileOps.moveAtomic` (ONE `rename(2)`; the partial is a sibling, so it never
    /// crosses a filesystem): `Files.move(REPLACE_EXISTING)` alone unlinks the block first, and a
    /// reader or a crash in that window found nothing at the block path (#1169, correcting the
    /// #910 ruling -- `know: 812d4de2c`).
    private static final String PARTIAL_SUFFIX = ".partial";

    private final Path basePath;
    private final AtomicLong usedBytes = new AtomicLong(0);
    private final AtomicLong writeSequence = new AtomicLong(0);
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

    /// Variant with an injectable partial-file write, for tests that need a write to fail after
    /// N bytes (a disk that fills mid-block) without a real ENOSPC. The override receives the
    /// partial path, never the block path; everything after the failure — discard, rename,
    /// accounting — is the production path.
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
        // real ENOSPC). Released here, exactly once, whatever stage failed; the on-disk state is
        // writeBlock's, and it is either the previous copy or nothing — so a later `delete` of the
        // id subtracts only what it finds there and the count never goes negative (r3, b).
        // `withFailure`, not `onFailure`: a dependent action runs before the returned promise
        // resolves, so the reservation is gone by the time the caller sees the failure. As an
        // `onFailure` handler it ran on an executor thread after the caller's `await` returned,
        // and `usedBytes` over-reported by the block for that window (#1144).
        return Promise.lift(WRITE_ERROR,
                            () -> writeBlock(id, content))
                      .flatMap(Promise::resolved)
                      .withFailure(_ -> usedBytes.addAndGet(-content.length));
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
        var partial = partialPath(path);

        return FileOps.createDirectories(path.getParent())
                      .flatMap(_ -> existingSize(path))
                      .flatMap(previousSize -> writeThenRename(partial, path, content, previousSize));
    }

    private Result<Unit> writeThenRename(Path partial, Path path, byte[] content, long previousSize) {
        return writer.apply(partial, content)
                     .flatMap(_ -> FileOps.moveAtomic(partial, path))
                     .onSuccess(_ -> correctUsedBytes(previousSize))
                     .onFailure(_ -> discardFailedWrite(partial))
                     .mapToUnit();
    }

    /// Only what THIS write created is discarded: the partial file, whether it holds nothing (the
    /// open failed), N bytes (the disk filled mid-block) or the whole block (the rename failed).
    /// The previous copy at the block path was never touched -- the atomic rename never unlinks
    /// it -- and stays counted (r3, c).
    private void discardFailedWrite(Path partial) {
        FileOps.deleteIfExists(partial).onFailure(cause -> log.warn("Partial block at {} could not be removed after a failed write: {}",
                                                                    partial,
                                                                    cause.message()));
    }

    private Path partialPath(Path path) {
        return path.resolveSibling(path.getFileName() + "." + writeSequence.incrementAndGet() + PARTIAL_SUFFIX);
    }

    /// Only a regular file at the block path is a previous copy; a directory squatting there is
    /// not counted, so the failed write that follows leaves nothing to correct for.
    private Result<Long> existingSize(Path path) {
        return Files.isRegularFile(path)
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

        if (!Files.isRegularFile(path)) {
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

    /// A partial file left by a write the process did not survive is removed here rather than
    /// counted: it is never served (reads use the block path) and nothing else would ever delete it.
    private void calculateUsedBytes() {
        FileOps.walk(basePath, FileOps::isRegularFile)
               .onSuccess(LocalDiskTier::removeLeftoverPartials)
               .map(LocalDiskTier::blockBytes)
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

    private static void removeLeftoverPartials(List<Path> paths) {
        paths.stream().filter(LocalDiskTier::isPartial).forEach(LocalDiskTier::removeLeftoverPartial);
    }

    private static long blockBytes(List<Path> paths) {
        return paths.stream()
                    .filter(path -> !isPartial(path))
                    .mapToLong(LocalDiskTier::fileSizeOrZero)
                    .sum();
    }

    private static boolean isPartial(Path path) {
        return path.getFileName()
                   .toString()
                   .endsWith(PARTIAL_SUFFIX);
    }

    private static void removeLeftoverPartial(Path partial) {
        FileOps.deleteIfExists(partial)
               .onSuccess(_ -> log.info("Removed leftover partial block {}", partial))
               .onFailure(cause -> log.warn("Leftover partial block {} could not be removed: {}",
                                            partial,
                                            cause.message()));
    }
}
