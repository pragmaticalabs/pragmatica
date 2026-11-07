package org.pragmatica.storage;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.warning.OperatorWarningCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Filesystem-backed storage tier with two-level directory sharding.
/// Files stored at: {basePath}/{hex[0:2]}/{hex[2:4]}/{fullHex}
/// Uses Promise.lift for non-blocking I/O on virtual threads.
///
/// **Every block is durable before [#put] resolves (#1567).** The partial file is written and forced
/// with its metadata, renamed over the block path, and then the block's directory is forced so the
/// renamed entry survives a power loss; a shard directory the write had to create is made durable in
/// its parent the same way. Durable for EVERY block, not only for blocks some ref names: blocks are
/// content-addressed and deduplicated, so a later put of the same content -- a sealed segment, a cursor,
/// an entity checkpoint whose id is committed to KV -- returns the existing id without rewriting it,
/// and whether a block will ever be reachable from a published ref cannot be known when it is written.
/// Cost: two fsyncs per block written (file, directory), plus one per shard directory created.
///
/// **Anchors** ([#putAnchor]) live under `{basePath}/anchors/`, laid out and framed by [AnchorFrame], and are written
/// with the same partial, rename and directory force as a block. They are not blocks: [#usedBytes] does not count them.
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
    /// A block file's name: the full lowercase hex of its id ([BlockId#hexString]).
    private static final Pattern BLOCK_NAME = Pattern.compile("[0-9a-f]{64}");
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
    private static final long NO_REFUSAL = Long.MAX_VALUE;

    private final Path basePath;
    private final AtomicLong usedBytes = new AtomicLong(0);
    private final AtomicLong writeSequence = new AtomicLong(0);
    private final long maxBytes;
    private final TimeSpan readTimeout;
    private final Option<Fn1<Result<Option<byte[]>>, BlockId>> readerOverride;
    private final Fn2<Result<Unit>, Path, byte[]> writer;
    private final Fn1<Result<Unit>, Path> directoryForcer;
    /// Sees every path a [#scan] lists, after the listing and before any stat of it; a no-op outside tests.
    private final Fn1<Unit, Path> listingObserver;
    private final AtomicReference<Option<MountClaim>> claim = new AtomicReference<>(none());
    /// The smallest put refused for want of space in the current full episode; [#NO_REFUSAL] when none is open.
    private final AtomicLong smallestRefused = new AtomicLong(NO_REFUSAL);

    private final AtomicReference<DegradeReporter> degrade = new AtomicReference<>(DegradeReporter.degradeReporter("disk-tier"));

    private LocalDiskTier(Path basePath,
                          long maxBytes,
                          TimeSpan readTimeout,
                          Option<Fn1<Result<Option<byte[]>>, BlockId>> readerOverride,
                          Option<Fn2<Result<Unit>, Path, byte[]>> writerOverride,
                          Option<Fn1<Result<Unit>, Path>> directoryForcerOverride,
                          Fn1<Unit, Path> listingObserver) {
        this.basePath = basePath;
        this.maxBytes = maxBytes;
        this.readTimeout = readTimeout;
        this.readerOverride = readerOverride;
        this.writer = writerOverride.or(FileOps::writeBytesForced);
        this.directoryForcer = directoryForcerOverride.or(FileOps::forceDirectory);
        this.listingObserver = listingObserver;
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
        return localDiskTier(basePath, maxBytes, readTimeout, readerOverride, writerOverride, none());
    }

    /// Variant that also injects the directory force issued after the rename, for tests that record
    /// the order of forces against the steps that follow a write (#1567). The writer override
    /// replaces the partial write INCLUDING its file force.
    static Result<LocalDiskTier> localDiskTier(Path basePath,
                                               long maxBytes,
                                               TimeSpan readTimeout,
                                               Option<Fn1<Result<Option<byte[]>>, BlockId>> readerOverride,
                                               Option<Fn2<Result<Unit>, Path, byte[]>> writerOverride,
                                               Option<Fn1<Result<Unit>, Path>> directoryForcerOverride) {
        return localDiskTier(basePath,
                             maxBytes,
                             readTimeout,
                             readerOverride,
                             writerOverride,
                             directoryForcerOverride,
                             _ -> unit());
    }

    /// Variant that also hands every path a [#scan] lists to `listingObserver`, after the listing and before any stat of
    /// it, for tests that remove files at that point (a block deleted or a partial renamed while the shard is listed).
    static Result<LocalDiskTier> localDiskTier(Path basePath,
                                               long maxBytes,
                                               TimeSpan readTimeout,
                                               Option<Fn1<Result<Option<byte[]>>, BlockId>> readerOverride,
                                               Option<Fn2<Result<Unit>, Path, byte[]>> writerOverride,
                                               Option<Fn1<Result<Unit>, Path>> directoryForcerOverride,
                                               Fn1<Unit, Path> listingObserver) {
        return Result.success(new LocalDiskTier(basePath,
                                                maxBytes,
                                                readTimeout,
                                                readerOverride,
                                                writerOverride,
                                                directoryForcerOverride,
                                                listingObserver)).onSuccess(LocalDiskTier::countUsedBytes);
    }

    /// Take the tier's directory into use: create the base directory durably if it is missing, remove the
    /// partial files a write the process did not survive left behind, and recount the bytes in use.
    ///
    /// The only step that changes the volume (#1569 A3): construction reads it and nothing more, so a node
    /// that builds a tier for a volume and then loses its claim on it leaves the volume byte-identical. The
    /// node that owns the volume opens the tier before using it. A put creates the directories it needs on
    /// its own, so an unopened tier still writes correctly; it only keeps any stale partials.
    public Result<LocalDiskTier> open() {
        return FileOps.createDirectoriesDurable(basePath)
                      .flatMap(_ -> FileOps.walk(basePath, FileOps::isRegularFile))
                      .onSuccess(LocalDiskTier::removeLeftoverPartials)
                      .map(_ -> countUsedBytes());
    }

    /// [#open] after `nodeId` has claimed the mount exclusively ([MountClaim]): the claim is taken before the open
    /// removes anything, so a node refused here has touched nothing on the mount. A claim held by another live node
    /// fails with [MountClaim.Held]. The claim is given up by [#release], and at once when the open itself fails.
    public Result<LocalDiskTier> openClaiming(String nodeId) {
        return MountClaim.claim(basePath, nodeId)
                         .onSuccess(held -> claim.set(some(held)))
                         .flatMap(_ -> open())
                         .onFailure(_ -> release());
    }

    @Override
    public Unit release() {
        claim.getAndSet(none()).onPresent(MountClaim::release);

        return unit();
    }

    @Override
    public Unit reportTo(DegradeReporter reporter) {
        degrade.set(reporter);

        return unit();
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
            return refuseFull(content.length);
        }

        reportWritable(content.length);
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

    /// The put needs this tier and it is full: one operator warning per episode (the first refusal), then the typed error to
    /// the caller. The episode ends when a write at least as large as the smallest one refused in it fits
    /// ([#reportWritable]): a small write fitting while a larger one is still refused does not end it, so a full tier that
    /// alternates large and small writes raises once, not on every alternation.
    private Promise<Unit> refuseFull(int refusedLength) {
        smallestRefused.accumulateAndGet(refusedLength, Math::min);
        degrade.get()
               .degraded(OperatorWarningCode.STORAGE_TIER_FULL,
                         "Disk tier {} is full ({} of {} bytes): writes that need it fail with TierFull until space is freed; "
                        + "raise [storage] disk_max_bytes, free disk space, or let demotion and garbage collection catch up",
                         basePath,
                         usedBytes.get(),
                         maxBytes);

        return StorageError.TierFull.tierFull(TierLevel.LOCAL_DISK,
                                              usedBytes.get(),
                                              maxBytes)
                                    .promise();
    }

    private void reportWritable(int fittedLength) {
        if (fittedLength >= smallestRefused.get()) {
            smallestRefused.set(NO_REFUSAL);
            degrade.get()
                   .recovered(OperatorWarningCode.STORAGE_TIER_FULL, "Disk tier {} accepts writes again", basePath);
        }
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

    /// The block file's size and modification time.
    @Override
    public Promise<Option<BlockStat>> stat(BlockId id) {
        return Promise.lift(READ_ERROR, () -> statFile(id, blockPath(id))).flatMap(Promise::resolved);
    }

    /// Walks the shard's first-level directories in ascending order, skipping those wholly before the cursor; each
    /// directory visited is listed whole by name and sorted, and only the files a page returns are stat'ed. A file
    /// removed while the shard is listed never fails the scan, and one deleted between the listing and its stat is
    /// skipped, never counted against the page.
    @Override
    public Promise<Page<BlockStat>> scan(KeyShard shard, Option<Cursor> after, int limit) {
        return Promise.lift(READ_ERROR, () -> scanShard(shard, after, limit)).flatMap(Promise::resolved);
    }

    /// Sets the block file's modification time to now and forces the file with its metadata, so the refresh survives a
    /// power loss as a [#put] does. The bytes are not rewritten.
    @Override
    public Promise<Boolean> touch(BlockId id) {
        return Promise.lift(WRITE_ERROR, () -> touchFile(blockPath(id))).flatMap(Promise::resolved);
    }

    private Result<Page<BlockStat>> scanShard(KeyShard shard, Option<Cursor> after, int limit) {
        return KeyShard.pageLimit(limit)
                       .flatMap(_ -> KeyShard.resumeAfter(after))
                       .flatMap(resume -> collect(shard.prefixes(),
                                                  0,
                                                  resume,
                                                  limit,
                                                  List.of()))
                       .map(found -> KeyShard.pageOf(found, limit));
    }

    private Result<List<BlockStat>> collect(List<String> prefixes,
                                            int index,
                                            Option<String> resume,
                                            int limit,
                                            List<BlockStat> found) {
        if (index >= prefixes.size() || found.size() >= limit) {
            return Result.success(found);
        }

        return blocksUnder(prefixes.get(index), resume, limit - found.size()).flatMap(more -> collect(prefixes,
                                                                                                      index + 1,
                                                                                                      resume,
                                                                                                      limit,
                                                                                                      Stream.concat(found.stream(),
                                                                                                                    more.stream())
                                                                                                            .toList()));
    }

    private Result<List<BlockStat>> blocksUnder(String prefix, Option<String> resume, int wanted) {
        var directory = basePath.resolve(prefix);

        return FileOps.isDirectory(directory) && resume.filter(hex -> prefix.compareTo(hex.substring(0, 2)) < 0)
                                                       .isEmpty()
               ? blockFilesUnder(directory).flatMap(files -> statFirst(files, directory, resume, wanted))
               : Result.success(List.of());
    }

    /// The files in `directory`'s subdirectories, by name only. A listing reads no file's attributes, so a file removed
    /// while the shard is listed (a block deleted, a partial renamed over its block path) never fails it; [#statFirst]
    /// skips a listed block gone before its stat, and never stats a name that is not a block's. A subdirectory removed
    /// before it is listed lists as empty.
    private Result<List<Path>> blockFilesUnder(Path directory) {
        return listed(directory).flatMap(this::listedAll);
    }

    private Result<List<Path>> listedAll(List<Path> subdirectories) {
        return Result.allOf(subdirectories.stream().map(this::listed).toList()).map(listings -> listings.stream()
                                                                                                        .flatMap(List::stream)
                                                                                                        .toList());
    }

    /// The entries of `directory`, each shown to the listing observer; none when it is gone or is not a directory.
    private Result<List<Path>> listed(Path directory) {
        return FileOps.list(directory)
                      .fold(cause -> goneOr(directory, cause),
                            Result::success)
                      .onSuccess(entries -> entries.forEach(listingObserver::apply));
    }

    private static Result<List<Path>> goneOr(Path directory, Cause cause) {
        return FileOps.isDirectory(directory)
               ? cause.result()
               : Result.success(List.of());
    }

    private static Result<List<BlockStat>> statFirst(List<Path> files,
                                                     Path directory,
                                                     Option<String> resume,
                                                     int wanted) {
        var stats = files.stream()
                         .filter(file -> isBlockFileUnder(directory, file))
                         .filter(file -> resume.fold(() -> true,
                                                     hex -> file.getFileName()
                                                                .toString()
                                                                .compareTo(hex) > 0))
                         .sorted()
                         .map(LocalDiskTier::statBlockFile)
                         .filter(stat -> stat.fold(_ -> true, Option::isPresent))
                         .limit(wanted)
                         .toList();

        return Result.allOf(stats).map(found -> found.stream()
                                                     .flatMap(Option::stream)
                                                     .toList());
    }

    /// `{dir}/{hex[2:4]}/{hex}` with `{dir}` named `hex[0:2]`: a block, never a partial, a claim or an anchor.
    private static boolean isBlockFileUnder(Path directory, Path file) {
        var name = file.getFileName().toString();
        var shardDirectory = file.getParent();
        var expectedStart = directory.getFileName().toString() + shardDirectory.getFileName().toString();

        return BLOCK_NAME.matcher(name).matches()
               && shardDirectory.getParent()
                                .equals(directory)
               && name.startsWith(expectedStart);
    }

    private static Result<Option<BlockStat>> statBlockFile(Path file) {
        return BlockId.fromHex(file.getFileName().toString()).flatMap(id -> statFile(id, file));
    }

    /// None when no regular file is at `path`, including one deleted while it was being read.
    private static Result<Option<BlockStat>> statFile(BlockId id, Path path) {
        return Result.lift(READ_ERROR,
                           () -> Files.readAttributes(path, BasicFileAttributes.class))
                     .fold(cause -> absentOr(path, cause),
                           attributes -> Result.success(statOf(id, attributes)));
    }

    private static Option<BlockStat> statOf(BlockId id, BasicFileAttributes attributes) {
        return attributes.isRegularFile()
               ? some(BlockStat.blockStat(id,
                                          attributes.size(),
                                          attributes.lastModifiedTime().toMillis()))
               : none();
    }

    private static <T> Result<Option<T>> absentOr(Path path, Cause cause) {
        return FileOps.isRegularFile(path)
               ? cause.result()
               : Result.success(none());
    }

    /// False when no regular file is at `path`, including one deleted while it was being touched.
    private static Result<Boolean> touchFile(Path path) {
        return FileOps.isRegularFile(path)
               ? Result.lift(WRITE_ERROR,
                             () -> Files.setLastModifiedTime(path,
                                                             FileTime.fromMillis(System.currentTimeMillis())))
                       .flatMap(_ -> forceWithMetadata(path))
                       .fold(cause -> absentOr(path, cause).map(Option::isPresent),
                             _ -> Result.success(true))
               : Result.success(false);
    }

    private static Result<Unit> forceWithMetadata(Path path) {
        return Result.lift(WRITE_ERROR,
                           () -> FileChannel.open(path, StandardOpenOption.READ))
                     .flatMap(LocalDiskTier::forceThenClose);
    }

    /// The channel is closed whatever the force's outcome; a failed force is the failure reported.
    private static Result<Unit> forceThenClose(FileChannel channel) {
        var forced = Result.lift(WRITE_ERROR, () -> channel.force(true));
        var closed = Result.lift(WRITE_ERROR, channel::close);

        return forced.flatMap(_ -> closed);
    }

    @Override
    public TierLevel level() {
        return TierLevel.LOCAL_DISK;
    }

    /// See the class doc: a resolved [#put] has forced the block and the entry naming it.
    @Override
    public boolean isDurable() {
        return true;
    }

    @Override
    public long usedBytes() {
        return usedBytes.get();
    }

    @Override
    public long maxBytes() {
        return maxBytes;
    }

    /// Durable before it resolves, as [#put]: the frame is written to a partial file and forced, renamed over the
    /// version's key, and the anchor's directory is forced. A failure leaves the previous newest version in place.
    @Override
    public Promise<Unit> putAnchor(AnchorName name, long version, byte[] value) {
        return version < 0
               ? AnchorError.NegativeVersion.negativeVersion(name, version).promise()
               : Promise.lift(WRITE_ERROR, () -> writeAnchor(name, version, value)).flatMap(Promise::resolved);
    }

    @Override
    public Promise<Option<Anchor>> latestAnchor(AnchorName name) {
        return Promise.lift(READ_ERROR,
                            () -> readLatestAnchor(name))
                      .timeout(readTimeout)
                      .flatMap(Promise::resolved);
    }

    /// Lists every version of the anchor, so `scanLimit` does not apply.
    @Override
    public Promise<Unit> pruneAnchors(AnchorName name, long below, int scanLimit) {
        return Promise.lift(WRITE_ERROR, () -> deleteAnchorsBelow(name, below)).flatMap(Promise::resolved);
    }

    private Result<Unit> writeAnchor(AnchorName name, long version, byte[] value) {
        var path = anchorDirectory(name).resolve(AnchorFrame.key(version));
        var partial = partialPath(path);

        return FileOps.createDirectoriesDurable(path.getParent())
                      .flatMap(_ -> writer.apply(partial,
                                                 AnchorFrame.encode(version, value)))
                      .flatMap(_ -> FileOps.moveAtomic(partial, path))
                      .flatMap(_ -> directoryForcer.apply(path.getParent()))
                      .onFailure(_ -> discardFailedWrite(partial))
                      .mapToUnit();
    }

    private Result<Option<Anchor>> readLatestAnchor(AnchorName name) {
        var directory = anchorDirectory(name);

        return FileOps.isDirectory(directory)
               ? FileOps.list(directory).flatMap(entries -> newestVersion(entries).fold(() -> Result.success(none()),
                                                                                        version -> readAnchor(directory,
                                                                                                              name,
                                                                                                              version).map(Option::some)))
               : Result.success(none());
    }

    private static Result<Anchor> readAnchor(Path directory, AnchorName name, long version) {
        return FileOps.readBytes(directory.resolve(AnchorFrame.key(version))).flatMap(frame -> AnchorFrame.decode(TierLevel.LOCAL_DISK,
                                                                                                                  name,
                                                                                                                  version,
                                                                                                                  frame));
    }

    private Result<Unit> deleteAnchorsBelow(AnchorName name, long below) {
        var directory = anchorDirectory(name);

        return FileOps.isDirectory(directory)
               ? FileOps.list(directory)
                        .flatMap(entries -> Result.allOf(versionsOf(entries).filter(version -> version < below)
                                                                   .map(version -> FileOps.deleteIfExists(directory.resolve(AnchorFrame.key(version))))))
                        .mapToUnit()
               : Result.success(unit());
    }

    private static Option<Long> newestVersion(List<Path> entries) {
        return Option.from(versionsOf(entries).max(Long::compare));
    }

    private static Stream<Long> versionsOf(List<Path> entries) {
        return entries.stream()
                      .map(entry -> AnchorFrame.versionOf(entry.getFileName().toString()))
                      .flatMap(Option::stream);
    }

    private Path anchorDirectory(AnchorName name) {
        return basePath.resolve(AnchorFrame.DIRECTORY)
                       .resolve(name.storageSegment());
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

        return FileOps.createDirectoriesDurable(path.getParent())
                      .flatMap(_ -> existingSize(path))
                      .flatMap(previousSize -> writeThenRename(partial, path, content, previousSize));
    }

    /// The directory force comes AFTER the rename: forcing it before would sync the partial's entry,
    /// which the rename replaces. A failed directory force fails the put although the block file is in
    /// place -- its survival across a power loss is unknown, and the caller must not publish a ref to
    /// it. The file stays on disk as an unreferenced copy that the next successful write of the same
    /// content replaces; it is counted only when it replaced a previous copy (same id, same size).
    private Result<Unit> writeThenRename(Path partial, Path path, byte[] content, long previousSize) {
        return writer.apply(partial, content)
                     .flatMap(_ -> FileOps.moveAtomic(partial, path))
                     .flatMap(_ -> directoryForcer.apply(path.getParent()))
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

    /// Read-only: a partial file left by a write the process did not survive is never counted -- it is never
    /// served (reads use the block path) -- and it is left where it is; [#open] removes it, since nothing else
    /// would ever delete it.
    private LocalDiskTier countUsedBytes() {
        if (FileOps.exists(basePath)) {
            recount();
        } else {
            recordUsedBytes(0);
        }

        return this;
    }

    private Unit recount() {
        FileOps.walk(basePath, FileOps::isRegularFile)
               .map(paths -> blockBytes(paths,
                                        basePath.resolve(AnchorFrame.DIRECTORY)))
               .onSuccess(this::recordUsedBytes)
               .onFailure(cause -> log.warn("Failed to calculate used bytes at {}: {}",
                                            basePath,
                                            cause.message()));

        return unit();
    }

    private Unit recordUsedBytes(long total) {
        usedBytes.set(total);
        log.info("LocalDiskTier at {} initialized: {} bytes in use", basePath, total);

        return unit();
    }

    private static long fileSizeOrZero(Path path) {
        return FileOps.size(path).or(0L);
    }

    private static void removeLeftoverPartials(List<Path> paths) {
        paths.stream().filter(LocalDiskTier::isPartial).forEach(LocalDiskTier::removeLeftoverPartial);
    }

    private static long blockBytes(List<Path> paths, Path anchors) {
        return paths.stream()
                    .filter(path -> !isPartial(path)
                                    && !isClaim(path)
                                    && !path.startsWith(anchors))
                    .mapToLong(LocalDiskTier::fileSizeOrZero)
                    .sum();
    }

    private static boolean isClaim(Path path) {
        return path.getFileName()
                   .toString()
                   .equals(MountClaim.CLAIM_FILE);
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
