package org.pragmatica.storage;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Number;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.FileOps.createDirectories;
import static org.pragmatica.lang.io.FileOps.deleteIfExists;
import static org.pragmatica.lang.io.FileOps.list;
import static org.pragmatica.lang.io.FileOps.moveAtomic;
import static org.pragmatica.lang.io.FileOps.readString;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.io.FileOps.writeString;


/// Default snapshot manager that writes text-format snapshots to local disk.
/// Dual-condition trigger: snapshot when mutation count exceeds threshold OR time exceeds interval.
final class DefaultSnapshotManager implements SnapshotManager {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultSnapshotManager.class);
    private static final String SNAPSHOT_PREFIX = "snapshot-";
    private static final String SNAPSHOT_SUFFIX = ".dat";
    private static final String LATEST_POINTER = "LATEST";
    /// #1353: every snapshot and every `LATEST` update is written HERE first, fsynced, and renamed
    /// over its target in ONE rename (`FileOps.moveAtomic`; the partial is a sibling of its target,
    /// so the rename never crosses a filesystem). Writes are serialised by [#writeLock], so exactly
    /// one writer owns these names at a time; under that lock the target only ever holds a complete
    /// file: an interrupted write, a crash during the rename or a failed rename leave the previous
    /// snapshot and the previous `LATEST` in place. Fixed names, not per-epoch ones, so a partial
    /// orphaned by a crash is overwritten by the next write instead of accumulating; neither name
    /// ends in `.dat`, so [#isSnapshotFile] never lists them and pruning never counts them.
    /// Without the lock two concurrent writers truncate each other's partial and rename each
    /// other's bytes under their own epoch name (rev1365 measured 3 torn + 26 misnamed files from
    /// 4 x 25 concurrent `forceSnapshot()` calls) -- the ticket's own defect, made by the fix.
    private static final String SNAPSHOT_PARTIAL = "snapshot.partial";
    private static final String LATEST_PARTIAL = "LATEST.partial";
    private static final HexFormat HEX = HexFormat.of();

    private final MetadataStore metadataStore;
    private final SnapshotConfig config;
    private final Function<Path, Result<Unit>> fsync;
    private final AtomicLong lastSnapshotEpoch = new AtomicLong();
    private final AtomicBoolean snapshotInProgress = new AtomicBoolean();
    /// Serialises [#forceSnapshot]. The `maybeSnapshot` CAS only coalesces TICKS; `forceSnapshot`
    /// is also called from the HTTP route (`StorageRoutes.triggerSnapshot`) on another thread. A
    /// loser WAITS rather than coalescing: a forced snapshot must reflect state at or after the
    /// call (the route reports `lastSnapshotEpoch` right after it), and an in-flight snapshot may
    /// have captured state before the caller's mutation.
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile long lastSnapshotTimestamp;

    DefaultSnapshotManager(MetadataStore metadataStore, SnapshotConfig config) {
        this(metadataStore, config, DefaultSnapshotManager::fsync);
    }

    /// Test seam: the sync step only, called once per written partial with its path, after
    /// `FileOps.writeString` has written it in full. A fixture can tear the file it is handed (a
    /// disk that fills or a process that dies mid-snapshot), replace it, count calls or measure
    /// concurrent depth. The write itself, the rename and the cleanup are the production path.
    DefaultSnapshotManager(MetadataStore metadataStore, SnapshotConfig config, Function<Path, Result<Unit>> fsync) {
        this.metadataStore = metadataStore;
        this.config = config;
        this.fsync = fsync;
        this.lastSnapshotTimestamp = System.currentTimeMillis();
    }

    @Override
    @Contract
    public void maybeSnapshot() {
        if (shouldSnapshot() && snapshotInProgress.compareAndSet(false, true)) {
            try {
                forceSnapshot();
            } finally {
                snapshotInProgress.set(false);
            }
        }
    }

    @Override
    @Contract
    public void forceSnapshot() {
        writeLock.lock();
        try {
            takeSnapshot();
        } finally {
            writeLock.unlock();
        }
    }

    private void takeSnapshot() {
        var snapshot = captureSnapshot();

        writeSnapshotToDisk(snapshot).onSuccess(_ -> recordSnapshotTaken(snapshot))
                           .onFailure(cause -> LOG.warn("Snapshot write failed: {}",
                                                        cause.message()));
    }

    /// #1353: the file `LATEST` names is tried first; when it is missing, torn or fails its hash
    /// check, the retained snapshots are tried newest-first instead, and the fallback is reported
    /// at WARN naming both files. A torn snapshot is never restored -- [#readAndValidateSnapshot]
    /// refuses it -- so the choice is between another complete snapshot (usually older) and none
    /// at all, and an older one is strictly more of the acked state than none.
    @Override
    public Option<MetadataSnapshot> restoreFromLatest() {
        var latest = readLatestSnapshotPath();

        return latest.flatMap(this::readAndValidateSnapshot)
                     .orElse(() -> restoreFromPreviousRetained(latest));
    }

    @Override
    public long lastSnapshotEpoch() {
        return lastSnapshotEpoch.get();
    }

    @Override
    public long lastSnapshotTimestamp() {
        return lastSnapshotTimestamp;
    }

    // --- Trigger logic ---
    private boolean shouldSnapshot() {
        var currentEpoch = metadataStore.currentEpoch();
        var epochDelta = currentEpoch - lastSnapshotEpoch.get();
        var timeDelta = System.currentTimeMillis() - lastSnapshotTimestamp;

        return epochDelta >= config.mutationThreshold() || timeDelta >= config.maxIntervalMillis();
    }

    // --- Snapshot capture ---
    private MetadataSnapshot captureSnapshot() {
        return MetadataSnapshot.metadataSnapshot(metadataStore.currentEpoch(),
                                                 config.nodeId(),
                                                 metadataStore.listAllLifecycles(),
                                                 metadataStore.listAllRefs());
    }

    private void recordSnapshotTaken(MetadataSnapshot snapshot) {
        lastSnapshotEpoch.set(snapshot.epoch());
        lastSnapshotTimestamp = snapshot.timestamp();
        pruneOldSnapshots();
        LOG.info("Snapshot taken: epoch={}, lifecycles={}, refs={}",
                 snapshot.epoch(),
                 snapshot.lifecycles().size(),
                 snapshot.refs().size());
    }

    // --- Disk write ---
    private Result<Path> writeSnapshotToDisk(MetadataSnapshot snapshot) {
        return ensureDirectory().flatMap(_ -> serializeAndWrite(snapshot));
    }

    private Result<Path> ensureDirectory() {
        return createDirectories(config.snapshotPath()).mapError(e -> new SnapshotError.DirectoryCreateFailed(new RuntimeException(e.message())));
    }

    private Result<Path> serializeAndWrite(MetadataSnapshot snapshot) {
        var fileName = snapshotFileName(snapshot.epoch());
        var filePath = config.snapshotPath().resolve(fileName);
        var content = serializeSnapshot(snapshot);

        return writeAtomically(SNAPSHOT_PARTIAL, filePath, content).flatMap(_ -> replaceLatestPointer(filePath))
                              .map(_ -> filePath);
    }

    /// `LATEST` is a regular one-line file naming the current snapshot, replaced by rename -- not a
    /// symlink, whatever the older name `updateLatestLink` suggested.
    private Result<Unit> replaceLatestPointer(Path snapshotFile) {
        var latestPath = config.snapshotPath().resolve(LATEST_POINTER);

        return writeAtomically(LATEST_PARTIAL,
                               latestPath,
                               snapshotFile.getFileName().toString());
    }

    /// Write to the named partial in the snapshot directory, then rename it over `target` as one
    /// rename. A failure at either step removes the partial, so the directory never holds a torn
    /// file under any name a later boot or prune could pick up.
    private Result<Unit> writeAtomically(String partialName, Path target, String content) {
        var partial = config.snapshotPath().resolve(partialName);

        return writeDurably(partial, content).flatMap(_ -> moveAtomic(partial, target))
                           .onFailure(_ -> deleteIfExists(partial))
                           .mapToUnit()
                           .mapError(e -> new SnapshotError.WriteFailed(new RuntimeException(e.message())));
    }

    private Result<Unit> writeDurably(Path path, String content) {
        return writeString(path, content).flatMap(_ -> fsync.apply(path));
    }

    private static Result<Unit> fsync(Path path) {
        return Result.lift(e -> new SnapshotError.WriteFailed(new RuntimeException(e.getMessage())),
                           () -> forceToDisk(path));
    }

    /// `force(true)` on the just-written partial: the bytes reach the device before the rename
    /// publishes them, so the rename can never make a torn file the current one. [unverified: power
    /// loss -- the rename's own directory entry is not fsynced, the same bound as #676's
    /// `GitBackedPersistence`; the pinned property is torn-file behaviour, not platter state.]
    @Contract
    private static Unit forceToDisk(Path path) throws Exception {
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }

        return Unit.unit();
    }

    // --- Disk read ---
    private Option<Path> readLatestSnapshotPath() {
        var latestPath = config.snapshotPath().resolve(LATEST_POINTER);

        return readString(latestPath).mapError(e -> new SnapshotError.ReadFailed(new RuntimeException(e.message())))
                         .map(String::trim)
                         .map(name -> config.snapshotPath()
                                            .resolve(name))
                         .onFailure(cause -> LOG.debug("No LATEST snapshot file: {}",
                                                       cause.message()))
                         .option();
    }

    private Option<MetadataSnapshot> readAndValidateSnapshot(Path path) {
        return readString(path).mapError(e -> new SnapshotError.ReadFailed(new RuntimeException(e.message())))
                         .flatMap(DefaultSnapshotManager::parseSnapshot)
                         .filter(SnapshotError.INTEGRITY_CHECK_FAILED, MetadataSnapshot::isValid)
                         .onFailure(cause -> LOG.warn("Snapshot restore failed: {}",
                                                      cause.message()))
                         .option();
    }

    /// #1353: the retained snapshots other than the one `LATEST` names, newest epoch first, until
    /// one restores. `LATEST` is deliberately NOT rewritten here: restore is a read path, and the
    /// next snapshot write repoints it anyway. The unreadable file is left on disk as evidence for
    /// the operator; it sorts newest, so ordinary retention removes it once enough snapshots follow.
    private Option<MetadataSnapshot> restoreFromPreviousRetained(Option<Path> unreadableLatest) {
        var candidates = previousRetained(unreadableLatest);

        return candidates.stream()
                         .map(candidate -> restoreCandidate(unreadableLatest, candidate))
                         .filter(Option::isPresent)
                         .findFirst()
                         .orElseGet(() -> reportNothingRestorable(unreadableLatest, candidates));
    }

    private Option<MetadataSnapshot> restoreCandidate(Option<Path> unreadableLatest, Path candidate) {
        return readAndValidateSnapshot(candidate).onPresent(snapshot -> reportFallback(unreadableLatest,
                                                                                       candidate,
                                                                                       snapshot));
    }

    private List<Path> previousRetained(Option<Path> unreadableLatest) {
        return listSnapshotFiles().map(files -> excludingUnreadable(files, unreadableLatest))
                                .map(List::reversed)
                                .onFailure(cause -> LOG.debug("No retained snapshots to fall back to: {}",
                                                              cause.message()))
                                .or(List.of());
    }

    private static List<Path> excludingUnreadable(List<Path> files, Option<Path> unreadableLatest) {
        return unreadableLatest.map(latest -> excludingLatest(files, latest))
                               .or(files);
    }

    private static void reportFallback(Option<Path> unreadableLatest, Path candidate, MetadataSnapshot snapshot) {
        LOG.warn("{}; restored another retained snapshot {} (epoch={}) instead. "
                + "Metadata recorded only in the unreadable file is lost unless a WAL replays it. "
                + "See docs/operators/runbooks/backup-recovery.md",
                 describeLatest(unreadableLatest),
                 candidate.getFileName(),
                 snapshot.epoch());
    }

    private static Option<MetadataSnapshot> reportNothingRestorable(Option<Path> unreadableLatest,
                                                                    List<Path> candidates) {
        unreadableLatest.onPresent(latest -> LOG.warn("{} and none of the {} other retained snapshot(s) restores; "
                                                     + "metadata starts EMPTY. See docs/operators/runbooks/backup-recovery.md",
                                                      describeLatest(unreadableLatest),
                                                      candidates.size()));

        return none();
    }

    private static String describeLatest(Option<Path> unreadableLatest) {
        return unreadableLatest.map(latest -> "Snapshot " + latest + " named by LATEST is unreadable")
                               .or("LATEST is missing or unreadable");
    }

    // --- Pruning ---
    private void pruneOldSnapshots() {
        performPrune().onFailure(cause -> LOG.warn("Snapshot pruning failed: {}", cause.message()));
    }

    private Result<Unit> performPrune() {
        return listSnapshotFiles().flatMap(this::deleteExcessSnapshots);
    }

    private Result<Unit> deleteExcessSnapshots(List<Path> snapshots) {
        if (snapshots.size() <= config.retentionCount()) {
            return Result.unitResult();
        }

        return Result.allOf(prunableVictims(snapshots).stream().map(DefaultSnapshotManager::deleteSnapshotFile)).mapToUnit();
    }

    /// #1012: the oldest files beyond the retention count, MINUS whatever `LATEST` currently names.
    /// The live pointer's target is off limits wherever its epoch sorts. A restart resets the
    /// metadata epoch, so a freshly written snapshot can carry a LOWER epoch than every retained
    /// predecessor and land in this very prefix; deleting it leaves `LATEST` dangling, which no
    /// later boot can restore from -- self-perpetuating durable-state corruption rather than the
    /// loss of one file. Retention is unaffected in the ordinary case, where `LATEST` names the
    /// newest file and is therefore never inside the prefix. An unreadable `LATEST` yields the
    /// unfiltered prefix: with no live pointer there is nothing to protect, and refusing to prune
    /// would let the directory grow without bound. [#readLatestSnapshotPath] logs that absence.
    private List<Path> prunableVictims(List<Path> snapshots) {
        var victims = snapshots.subList(0,
                                        snapshots.size() - config.retentionCount());

        return readLatestSnapshotPath().map(latest -> excludingLatest(victims, latest))
                                     .or(victims);
    }

    /// Compared by file name rather than by whole path: both sides are resolved against
    /// [SnapshotConfig#snapshotPath], so the names identify the same file exactly, without depending
    /// on how the directory listing spelled its entries.
    private static List<Path> excludingLatest(List<Path> victims, Path latest) {
        return victims.stream()
                      .filter(path -> !path.getFileName()
                                           .equals(latest.getFileName()))
                      .toList();
    }

    private static Result<Boolean> deleteSnapshotFile(Path file) {
        return deleteIfExists(file).mapError(e -> new SnapshotError.PruneFailed(new RuntimeException(e.message())));
    }

    private Result<List<Path>> listSnapshotFiles() {
        return list(config.snapshotPath()).mapError(e -> new SnapshotError.PruneFailed(new RuntimeException(e.message())))
                   .map(DefaultSnapshotManager::sortedSnapshotFiles);
    }

    /// #1012: order by the epoch the name ENCODES, never by the name itself. [#snapshotFileName]
    /// zero-pads to six digits, so lexicographic order agrees with numeric order only while the
    /// epoch stays below 1_000_000; past that boundary `snapshot-1000000.dat` sorts BEFORE
    /// `snapshot-999999.dat` and pruning starts deleting the newest snapshots first.
    private static List<Path> sortedSnapshotFiles(List<Path> entries) {
        return entries.stream()
                      .filter(DefaultSnapshotManager::isSnapshotFile)
                      .sorted(Comparator.comparingLong(DefaultSnapshotManager::snapshotEpochOf))
                      .toList();
    }

    /// The epoch encoded between [#SNAPSHOT_PREFIX] and [#SNAPSHOT_SUFFIX]. A name carrying no
    /// parsable epoch sorts oldest, so foreign files in the snapshot directory are pruned ahead of
    /// any real snapshot -- and are still never deleted while `LATEST` names one, because
    /// [#prunableVictims] excludes the live target regardless of where it sorts.
    private static long snapshotEpochOf(Path path) {
        var name = path.getFileName().toString();
        var digits = name.substring(SNAPSHOT_PREFIX.length(),
                                    name.length() - SNAPSHOT_SUFFIX.length());

        return Number.parseLong(digits).or(Long.MIN_VALUE);
    }

    private static boolean isSnapshotFile(Path path) {
        var name = path.getFileName().toString();

        return name.startsWith(SNAPSHOT_PREFIX) && name.endsWith(SNAPSHOT_SUFFIX);
    }

    // --- Serialization ---
    private static String serializeSnapshot(MetadataSnapshot snapshot) {
        var sb = new StringBuilder();

        sb.append("epoch=").append(snapshot.epoch()).append('\n');
        sb.append("timestamp=").append(snapshot.timestamp()).append('\n');
        sb.append("nodeId=").append(snapshot.nodeId()).append('\n');
        sb.append("contentHash=").append(HEX.formatHex(snapshot.contentHash())).append('\n');
        sb.append('\n');
        sb.append("# Lifecycles\n");
        snapshot.lifecycles().forEach(lc -> appendLifecycleLine(sb, lc));
        sb.append('\n');
        sb.append("# Refs\n");
        snapshot.refs().forEach((name, id) -> appendRefLine(sb, name, id));

        return sb.toString();
    }

    private static void appendLifecycleLine(StringBuilder sb, BlockLifecycle lc) {
        var tiers = lc.presentIn().stream().map(TierLevel::name).collect(Collectors.joining(","));

        sb.append(lc.blockId().hexString())
          .append('|')
          .append(tiers)
          .append('|')
          .append(lc.refCount())
          .append('|')
          .append(lc.lastAccessedAt())
          .append('|')
          .append(lc.createdAt())
          .append('|')
          .append(lc.accessCount())
          .append('|')
          .append(lc.orphanedAt())
          .append('\n');
    }

    private static void appendRefLine(StringBuilder sb, String name, BlockId id) {
        sb.append(name).append('|').append(id.hexString()).append('\n');
    }

    // --- Deserialization ---
    private static Result<MetadataSnapshot> parseSnapshot(String content) {
        return Result.lift(SnapshotError.ParseFailed::new, () -> parseSnapshotContent(content)).flatMap(parsed -> parsed);
    }

    private static Result<MetadataSnapshot> parseSnapshotContent(String content) {
        var lines = content.split("\n");
        var headers = parseHeaders(lines);
        var epoch = Long.parseLong(headers.get("epoch"));
        var timestamp = Long.parseLong(headers.get("timestamp"));
        var nodeId = headers.get("nodeId");
        var contentHash = HEX.parseHex(headers.get("contentHash"));
        var refs = new LinkedHashMap<String, BlockId>();

        return parseLifecycles(lines, refs).map(lifecycles -> new MetadataSnapshot(epoch,
                                                                                   timestamp,
                                                                                   nodeId,
                                                                                   lifecycles,
                                                                                   refs,
                                                                                   contentHash));
    }

    /// Parse the lifecycle and ref data lines, propagating any malformed-line failure rather than
    /// silently dropping it. Ref lines mutate `refs` in place (a `String -> BlockId` accumulator);
    /// lifecycle lines are collected via [`Result.allOf`] so a single corrupt line fails the whole
    /// snapshot restore (a partially-parsed snapshot is worse than a clean failure).
    private static Result<List<BlockLifecycle>> parseLifecycles(String[] lines, Map<String, BlockId> refs) {
        var lifecycleResults = new ArrayList<Result<BlockLifecycle>>();
        var section = "";

        for (var line : lines) {
            if (line.equals("# Lifecycles")) {
                section = "lifecycles";
            } else if (line.equals("# Refs")) {
                section = "refs";
            } else if (!line.isEmpty() && !line.startsWith("#") && !line.contains("=")) {
                collectDataLine(section, line, lifecycleResults, refs);
            }
        }

        return Result.allOf(lifecycleResults);
    }

    private static void collectDataLine(String section,
                                        String line,
                                        List<Result<BlockLifecycle>> lifecycleResults,
                                        Map<String, BlockId> refs) {
        if ("lifecycles".equals(section)) {
            lifecycleResults.add(parseLifecycleLine(line));
        } else if ("refs".equals(section)) {
            parseRefLine(line, refs);
        }
    }

    private static Map<String, String> parseHeaders(String[] lines) {
        var headers = new LinkedHashMap<String, String>();

        for (var line : lines) {
            if (line.isEmpty() || line.startsWith("#")) {
                break;
            }

            var eqIndex = line.indexOf('=');

            if (eqIndex > 0) {
                headers.put(line.substring(0, eqIndex), line.substring(eqIndex + 1));
            }
        }

        return headers;
    }

    private static Result<BlockLifecycle> parseLifecycleLine(String line) {
        var parts = line.split("\\|");

        return BlockId.fromHex(parts[0]).map(id -> buildLifecycle(id, parts));
    }

    private static BlockLifecycle buildLifecycle(BlockId id, String[] parts) {
        var tiers = parseTierSet(parts[1]);
        var refCount = Integer.parseInt(parts[2]);
        var lastAccessed = Long.parseLong(parts[3]);
        var created = Long.parseLong(parts[4]);
        var accessCount = parts.length > 5
                          ? Integer.parseInt(parts[5])
                          : 0;
        // #737 fix round 2: a pre-round-2 snapshot carries no orphaning instant. Fall back to the
        // same rule as the 6-arg blockLifecycle() factory -- lastAccessedAt for an orphaned entry,
        // 0 (unorphaned) for a live one -- rather than duplicating it here.
        if (parts.length > 6) {
            var orphanedAt = Long.parseLong(parts[6]);

            return BlockLifecycle.blockLifecycle(id, tiers, refCount, lastAccessed, created, accessCount, orphanedAt);
        }

        return BlockLifecycle.blockLifecycle(id, tiers, refCount, lastAccessed, created, accessCount);
    }

    private static Set<TierLevel> parseTierSet(String tierString) {
        if (tierString.isEmpty()) {
            return EnumSet.noneOf(TierLevel.class);
        }

        var tiers = EnumSet.noneOf(TierLevel.class);

        for (var name : tierString.split(",")) {
            tiers.add(TierLevel.valueOf(name));
        }

        return tiers;
    }

    private static void parseRefLine(String line, Map<String, BlockId> refs) {
        var pipeIndex = line.indexOf('|');

        if (pipeIndex > 0) {
            var name = line.substring(0, pipeIndex);

            BlockId.fromHex(line.substring(pipeIndex + 1)).onSuccess(id -> refs.put(name, id));
        }
    }

    // --- File naming ---
    private static String snapshotFileName(long epoch) {
        return SNAPSHOT_PREFIX + String.format("%06d", epoch) + SNAPSHOT_SUFFIX;
    }
}
