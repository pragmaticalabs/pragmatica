package org.pragmatica.storage;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.FileOps.createDirectories;
import static org.pragmatica.lang.io.FileOps.deleteIfExists;
import static org.pragmatica.lang.io.FileOps.exists;
import static org.pragmatica.lang.io.FileOps.forceDirectory;
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
    /// #1473: the volume-identity file INSIDE the snapshot directory; its twin, the sentinel, lives
    /// outside the volume ([SnapshotConfig#volumeSentinel]). The id is a random UUID, minted at the
    /// first successful snapshot write.
    private static final String VOLUME_MARKER = ".volume-id";
    /// #2257: version of the snapshot text layout this class WRITES. 1 is the unversioned layout
    /// (no `format` header, raw ref names) that is still READ; see [#parseSnapshotContent].
    static final String FORMAT_VERSION = "2";
    private static final String LEGACY_FORMAT_VERSION = "1";
    private static final Cause MALFORMED_REF_LINE = Causes.cause("Malformed ref line in snapshot");
    private static final Cause DUPLICATE_REF_NAME = Causes.cause("Duplicate ref name in snapshot");

    private final MetadataStore metadataStore;
    private final SnapshotConfig config;
    private final Function<Path, Result<Unit>> fsync;
    private final BiFunction<MetadataSnapshot, MetadataSnapshot.Body, String> serializer;
    private final AtomicLong lastSnapshotEpoch = new AtomicLong();
    private final AtomicBoolean snapshotInProgress = new AtomicBoolean();
    private final AtomicBoolean volumeStampWarned = new AtomicBoolean();
    private final DegradeReporter degrade;
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

    /// Test seam: the sync step and the reporter. A fixture that fails the sync drives the write-failure transition.
    DefaultSnapshotManager(MetadataStore metadataStore,
                           SnapshotConfig config,
                           Function<Path, Result<Unit>> fsync,
                           DegradeReporter degrade) {
        this(metadataStore, config, fsync, DefaultSnapshotManager::serializeBody, degrade);
    }

    /// The manager of a storage instance whose snapshot failures and fallbacks are reported through `degrade`.
    DefaultSnapshotManager(MetadataStore metadataStore, SnapshotConfig config, DegradeReporter degrade) {
        this(metadataStore, config, DefaultSnapshotManager::fsync, DefaultSnapshotManager::serializeBody, degrade);
    }

    /// Test seam: the sync step only, called once per written partial with its path, after
    /// `FileOps.writeString` has written it in full. A fixture can tear the file it is handed (a
    /// disk that fills or a process that dies mid-snapshot), replace it, count calls or measure
    /// concurrent depth. The write itself, the rename and the cleanup are the production path.
    DefaultSnapshotManager(MetadataStore metadataStore, SnapshotConfig config, Function<Path, Result<Unit>> fsync) {
        this(metadataStore, config, fsync, DefaultSnapshotManager::serializeBody);
    }

    /// Test seam (#2257): the encoder only. A fixture can hand the write path a serializer with a defect
    /// -- a dropped line, a mangled separator -- and observe that the write-time read-back refuses the
    /// result. Production passes [#serializeBody].
    DefaultSnapshotManager(MetadataStore metadataStore,
                           SnapshotConfig config,
                           Function<Path, Result<Unit>> fsync,
                           Function<MetadataSnapshot, String> serializer) {
        this(metadataStore, config, fsync, (snapshot, _) -> serializer.apply(snapshot));
    }

    private DefaultSnapshotManager(MetadataStore metadataStore,
                                   SnapshotConfig config,
                                   Function<Path, Result<Unit>> fsync,
                                   BiFunction<MetadataSnapshot, MetadataSnapshot.Body, String> serializer) {
        this(metadataStore, config, fsync, serializer, DegradeReporter.degradeReporter("snapshot"));
    }

    private DefaultSnapshotManager(MetadataStore metadataStore,
                                   SnapshotConfig config,
                                   Function<Path, Result<Unit>> fsync,
                                   BiFunction<MetadataSnapshot, MetadataSnapshot.Body, String> serializer,
                                   DegradeReporter degrade) {
        this.degrade = degrade;
        this.metadataStore = metadataStore;
        this.config = config;
        this.fsync = fsync;
        this.serializer = serializer;
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

    /// Under the same lock as [#forceSnapshot], so it is ordered with every other snapshot write.
    @Override
    public Result<MetadataSnapshot> snapshotNow() {
        writeLock.lock();
        try {
            return takeSnapshot();
        } finally {
            writeLock.unlock();
        }
    }

    private Result<MetadataSnapshot> takeSnapshot() {
        var capture = captureSnapshot();
        var snapshot = capture.snapshot();

        return writeSnapshotToDisk(capture).onSuccess(_ -> recordSnapshotTaken(snapshot))
                                  .onSuccess(_ -> stampVolumeIdentity())
                                  .onSuccess(_ -> degrade.recovered(OperatorWarningCode.STORAGE_SNAPSHOT_WRITE_FAILED,
                                                                    "Snapshot written again at epoch {}",
                                                                    snapshot.epoch()))
                                  .onFailure(cause -> reportWriteFailed(cause))
                                  .map(_ -> snapshot);
    }

    private void reportWriteFailed(Cause cause) {
        LOG.warn("Snapshot write failed: {}", cause.message());
        degrade.degraded(OperatorWarningCode.STORAGE_SNAPSHOT_WRITE_FAILED,
                         "Metadata snapshot cannot be written, so the references it holds are unprotected across a restart: {}",
                         cause.message());
    }

    /// #1353: the file `LATEST` names is tried first; when it is missing, torn or fails its hash
    /// check, the retained snapshots are tried newest-first instead, and the fallback is reported
    /// at WARN naming both files. A torn snapshot is never restored -- [#readAndValidateSnapshot]
    /// refuses it -- so the choice is between another complete snapshot (usually older) and none
    /// at all, and an older one is strictly more of the acked state than none.
    ///
    /// #1013: "none at all" is itself two outcomes -- see [#nothingRestored].
    @Override
    public Result<Option<MetadataSnapshot>> restoreFromLatest() {
        var latest = readLatestSnapshotPath();

        return latest.flatMap(this::readAndValidateSnapshot)
                     .map(snapshot -> Result.success(Option.some(snapshot)))
                     .or(() -> restoreFromPreviousRetained(latest));
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
    /// The snapshot and its canonical body, rendered once: the hash, the file and the write-time read-back
    /// all use these lines.
    private record Capture(MetadataSnapshot snapshot, MetadataSnapshot.Body body) {}

    private Capture captureSnapshot() {
        var epoch = metadataStore.currentEpoch();
        var lifecycles = metadataStore.listAllLifecycles();
        var refs = metadataStore.listAllRefs();
        var body = MetadataSnapshot.render(epoch, lifecycles, refs);

        return new Capture(new MetadataSnapshot(epoch,
                                                System.currentTimeMillis(),
                                                config.nodeId(),
                                                lifecycles,
                                                refs,
                                                body.hash()),
                           body);
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
    private Result<Path> writeSnapshotToDisk(Capture capture) {
        return ensureDirectory().flatMap(_ -> serializeAndWrite(capture));
    }

    private Result<Path> ensureDirectory() {
        return createDirectories(config.snapshotPath()).mapError(e -> new SnapshotError.DirectoryCreateFailed(new RuntimeException(e.message())));
    }

    private Result<Path> serializeAndWrite(Capture capture) {
        var snapshot = capture.snapshot();
        var fileName = snapshotFileName(snapshot.epoch());
        var filePath = config.snapshotPath().resolve(fileName);
        var content = serializer.apply(snapshot, capture.body());

        return verifyReadsBack(snapshot, content).flatMap(_ -> writeAtomically(SNAPSHOT_PARTIAL, filePath, content))
                              .flatMap(_ -> replaceLatestPointer(filePath))
                              .map(_ -> filePath);
    }

    /// #2257: the encoded text is read back in ONE pass ([SnapshotReadBack]) and compared by value with the
    /// snapshot that was captured in memory: header (format, epoch, node id, hash), then every lifecycle
    /// field and every ref name and target, in the order they were written. Each line is DECODED and the
    /// result compared with the value it was rendered from, so no collection of parsed values is built. The
    /// hash alone cannot do this job: it is computed over the same encoder output that was written, so an
    /// encoder that loses information hashes identically on both sides. The hash is computed ONCE, while
    /// the body is rendered; the file is checked to carry that value, which together with equal content is
    /// the integrity check the restore applies. A snapshot that would not restore as captured is refused
    /// here, loudly, while the previous snapshot and `LATEST` are still in place.
    private Result<Unit> verifyReadsBack(MetadataSnapshot captured, String content) {
        return Result.lift(SnapshotError.ParseFailed::new,
                           () -> SnapshotReadBack.matches(captured, content))
                     .filter(SnapshotError.General.ROUND_TRIP_MISMATCH, matches -> matches)
                     .mapError(SnapshotError.RoundTripFailed::new)
                     .onFailure(cause -> LOG.error("Snapshot epoch={} refused: {}",
                                                   captured.epoch(),
                                                   cause.message()))
                     .mapToUnit();
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
    ///
    /// #1567: the snapshot directory is forced after the rename, so the renamed entry survives a power
    /// loss. The stream WAL is truncated up to the sealed bound this snapshot's refs carry; without the
    /// directory force, a power loss could resurface the PREVIOUS snapshot at boot -- a lower bound than
    /// the one truncation already used, with the records in between gone from the WAL.
    private Result<Unit> writeAtomically(String partialName, Path target, String content) {
        return writeAtomically(config.snapshotPath().resolve(partialName),
                               target,
                               content);
    }

    /// The partial is a sibling of `target` (#1473 extends this to the sentinel, which lives outside the
    /// snapshot directory), so the rename never crosses a filesystem, and the directory forced is the
    /// one holding the renamed entry.
    private Result<Unit> writeAtomically(Path partial, Path target, String content) {
        return createDirectories(target.getParent()).flatMap(_ -> writeDurably(partial, content))
                                .flatMap(_ -> moveAtomic(partial, target))
                                .flatMap(_ -> forceDirectory(target.getParent()))
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
    /// publishes them, so the rename can never make a torn file the current one. The rename's own
    /// directory entry is forced by [#writeAtomically] after it (#1567).
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
    private Result<Option<MetadataSnapshot>> restoreFromPreviousRetained(Option<Path> unreadableLatest) {
        return previousRetained(unreadableLatest).flatMap(candidates -> restoreFirstComplete(unreadableLatest,
                                                                                             candidates));
    }

    private Result<Option<MetadataSnapshot>> restoreFirstComplete(Option<Path> unreadableLatest,
                                                                  List<Path> candidates) {
        return candidates.stream()
                         .map(candidate -> restoreCandidate(unreadableLatest, candidate))
                         .filter(Option::isPresent)
                         .findFirst()
                         .map(Result::success)
                         .orElseGet(() -> nothingRestored(unreadableLatest, candidates));
    }

    private Option<MetadataSnapshot> restoreCandidate(Option<Path> unreadableLatest, Path candidate) {
        return readAndValidateSnapshot(candidate).onPresent(snapshot -> reportFallback(unreadableLatest,
                                                                                       candidate,
                                                                                       snapshot));
    }

    /// #1013: a snapshot directory that does not exist holds nothing, by inspection. One that exists
    /// and cannot be listed is a failure, not an empty list -- absence is established by looking,
    /// never inferred from a read that failed.
    ///
    /// #1013 round 2 -- a KNOWN, DELIBERATELY UNCLOSED conflation, recorded here rather than fixed:
    /// an absent snapshot directory reads as a first boot even when it is absent because the VOLUME
    /// is not there. `StorageFactory.defaultStreamStorage`'s javadoc promises boot "never fails on an
    /// unmountable data dir", so on that boot the node comes up read-ready on empty metadata.
    ///
    /// Refusing HERE was implemented and REVERTED, because the discriminator it needs does not exist
    /// at this layer. "Data root absent" is not evidence of a vanished volume: it is the ORDINARY
    /// state wherever `/data` is not writable -- CI and every developer laptop -- where the disk tier
    /// degrades to memory+DHT by design (`handleDiskTierUnavailable`, #1276's `HermeticStorage`).
    /// Refusing on it fails 52 node boot tests across 18 classes that pin exactly that guarantee.
    ///
    /// What WOULD close it is one layer up, where the intent is known: `StorageFactory` sees whether
    /// the disk tier ARMED or degraded. An absent snapshot directory under a disk tier that armed is a
    /// genuine first boot; under a tier that degraded, durable metadata was never possible and empty
    /// is expected. Threading that single bit into [SnapshotConfig] would let the manager tell them
    /// apart without guessing from the filesystem. Out of #1013's scope; needs its own ticket.
    private Result<List<Path>> previousRetained(Option<Path> unreadableLatest) {
        if (!exists(config.snapshotPath())) {
            return Result.success(List.of());
        }

        return listSnapshotFiles(SnapshotError.ReadFailed::new).map(files -> excludingUnreadable(files, unreadableLatest))
                                .map(List::reversed);
    }

    /// #1013 round 2: "absent, established by looking" needs something to have looked AT. An absent
    /// snapshot directory under a data root that is present and listable is a first boot -- nothing
    /// was ever written there. Under a data root that is itself absent, or that refuses a listing,
    /// the same absence means the volume never mounted, and reading it as a first boot is exactly
    /// the defect #1013 closes on the torn-snapshot route: a node coming up read-ready on empty
    /// metadata. The two are indistinguishable from the snapshot directory alone, so this
    /// establishes the root before concluding anything about the directory under it.
    ///
    /// BOUND, stated because it is NOT closed: a volume whose MOUNT POINT exists but is unmounted
    /// presents an empty, listable directory, and is still read here as a first boot. Closing that
    /// needs evidence this layer does not hold -- a provisioning-time sentinel written onto the
    /// volume itself, or a mount check at the layer that owns the data-dir configuration.
    private static List<Path> excludingUnreadable(List<Path> files, Option<Path> unreadableLatest) {
        return unreadableLatest.map(latest -> excludingLatest(files, latest))
                               .or(files);
    }

    private void reportFallback(Option<Path> unreadableLatest, Path candidate, MetadataSnapshot snapshot) {
        degrade.degraded(OperatorWarningCode.STORAGE_SNAPSHOT_FELL_BACK,
                         "{}; restored another retained snapshot {} (epoch={}) instead",
                         describeLatest(unreadableLatest),
                         candidate.getFileName(),
                         snapshot.epoch());
        LOG.warn("{}; restored another retained snapshot {} (epoch={}) instead. "
                + "Metadata recorded only in the unreadable file is lost unless a WAL replays it. "
                + "See docs/operators/runbooks/backup-recovery.md",
                 describeLatest(unreadableLatest),
                 candidate.getFileName(),
                 snapshot.epoch());
    }

    /// #1013: nothing restored is two outcomes the caller has to keep apart. No `LATEST` file and
    /// no retained snapshot is a first boot: success with none, nothing to warn about. Anything
    /// else on disk -- a `LATEST` that is torn or dangles, snapshot files that are all unreadable
    /// -- is metadata that existed and is now lost; that is a failure, so the caller refuses to
    /// start rather than signal readiness over an empty store. Before #1013 both were `none()`,
    /// and the second was not even WARNed when `LATEST` was the missing part.
    private Result<Option<MetadataSnapshot>> nothingRestored(Option<Path> unreadableLatest, List<Path> candidates) {
        if (candidates.isEmpty() && !exists(config.snapshotPath().resolve(LATEST_POINTER))) {
            return verifyVolumeIdentity().map(_ -> none());
        }

        return new SnapshotError.NothingRestorable(describeLatest(unreadableLatest), candidates.size()).<Option<MetadataSnapshot>> result()
                                                                                                       .onFailure(DefaultSnapshotManager::reportNothingRestorable);
    }

    /// #1013 round 2: the AGGREGATE verdict, restored after the fix that made this state detectable
    /// removed the only signal for it. [#readAndValidateSnapshot]'s per-file WARN says one file did
    /// not read; this says every one of them failed and the metadata is gone. On the boot path the
    /// caller turns the failure into a named boot refusal, which is louder -- but on the truncation
    /// tick (`DurableSealedOffsetSource.onDisk`) there is no boot left to refuse, and a node whose
    /// snapshots tear AFTER a good boot would otherwise fail every tick in silence.
    private static void reportNothingRestorable(Cause cause) {
        LOG.warn("{}", cause.message());
    }

    /// #1473: "nothing on disk" is a first boot only when no sentinel says this node wrote a volume
    /// before. The sentinel lives outside the volume, so an unmounted mount point (an empty, listable
    /// directory that is NOT the volume) cannot erase it; the matching `.volume-id` inside the
    /// directory is what a remount of the right volume brings back. No sentinel configured means the
    /// disk tier is degraded (or the node keeps no state directory): there is no durable volume to
    /// lose, so the old first-boot reading stands.
    private Result<Unit> verifyVolumeIdentity() {
        return config.volumeSentinel()
                     .map(this::checkAgainstSentinel)
                     .or(Result.success(Unit.unit()));
    }

    private Result<Unit> checkAgainstSentinel(Path sentinel) {
        if (!exists(sentinel)) {
            return Result.success(Unit.unit());
        }

        return readString(sentinel).mapError(e -> new SnapshotError.ReadFailed(new RuntimeException(e.message())))
                         .map(String::trim)
                         .filter(new SnapshotError.VolumeNotPreviouslyWritten(config.snapshotPath(),
                                                                              sentinel),
                                 this::volumeCarriesId)
                         .mapToUnit();
    }

    private boolean volumeCarriesId(String id) {
        return readString(config.snapshotPath().resolve(VOLUME_MARKER)).map(String::trim)
                         .map(id::equals)
                         .or(false);
    }

    /// #1473: once a snapshot has been written, the volume gets its identity: the marker inside the
    /// snapshot directory first, the sentinel outside it second, so a crash between the two leaves a
    /// marker with no sentinel, which the next write adopts. A sentinel that already exists is never
    /// rewritten here: if it disagrees with the marker, restore is what reports it.
    ///
    /// FER: a failed stamp is logged and the snapshot stands. The snapshot is already durable; what
    /// is lost is only the guard against an unmounted volume until a later write succeeds. Warned
    /// once per manager, not every tick.
    private void stampVolumeIdentity() {
        config.volumeSentinel()
              .map(this::ensureSentinel)
              .onPresent(result -> result.onFailure(this::warnVolumeStampFailed));
    }

    private Result<Unit> ensureSentinel(Path sentinel) {
        if (exists(sentinel)) {
            return Result.success(Unit.unit());
        }

        return existingOrNewVolumeId().flatMap(id -> writeAtomically(sentinel.resolveSibling(sentinel.getFileName()
                                                                                            + ".partial"),
                                                                     sentinel,
                                                                     id));
    }

    private Result<String> existingOrNewVolumeId() {
        var markerPath = config.snapshotPath().resolve(VOLUME_MARKER);

        return readString(markerPath).map(String::trim)
                         .filter(SnapshotError.INTEGRITY_CHECK_FAILED,
                                 id -> !id.isEmpty())
                         .orElse(() -> mintVolumeId(markerPath));
    }

    private Result<String> mintVolumeId(Path markerPath) {
        var id = UUID.randomUUID().toString();

        return writeAtomically(config.snapshotPath().resolve(VOLUME_MARKER + ".partial"),
                               markerPath,
                               id).map(_ -> id);
    }

    private void warnVolumeStampFailed(Cause cause) {
        if (volumeStampWarned.compareAndSet(false, true)) {
            LOG.warn("Volume identity could not be recorded for {} ({}); an unmounted volume will not be detected "
                    + "until a later snapshot records it",
                     config.snapshotPath(),
                     cause.message());
        }
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
        return listSnapshotFiles(SnapshotError.PruneFailed::new).flatMap(this::deleteExcessSnapshots);
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

    private Result<List<Path>> listSnapshotFiles(Function<Throwable, SnapshotError> onFailure) {
        return list(config.snapshotPath()).mapError(e -> onFailure.apply(new RuntimeException(e.message())))
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
    /// #2257: `format=2` is the first header. Ref names are percent-encoded ([SnapshotNameCodec]); a file
    /// with no `format` header is the pre-#2257 layout, which [#parseLegacyContent] still reads.
    static String serializeSnapshot(MetadataSnapshot snapshot) {
        return serializeBody(snapshot,
                             MetadataSnapshot.render(snapshot.epoch(), snapshot.lifecycles(), snapshot.refs()));
    }

    /// Header, a blank line, then the body exactly as it was hashed.
    static String serializeBody(MetadataSnapshot snapshot, MetadataSnapshot.Body body) {
        return "format=" + FORMAT_VERSION + '\n'
             + "epoch=" + snapshot.epoch() + '\n'
             + "timestamp=" + snapshot.timestamp() + '\n'
             + "nodeId=" + snapshot.nodeId() + '\n'
             + "contentHash=" + HEX.formatHex(snapshot.contentHash())
             + "\n\n" + body.text();
    }

    // --- Deserialization ---
    private static Result<MetadataSnapshot> parseSnapshot(String content) {
        return Result.lift(SnapshotError.ParseFailed::new, () -> parseSnapshotContent(content)).flatMap(parsed -> parsed);
    }

    /// #2257: dispatch on the `format` header. Absent means the unversioned pre-#2257 layout, which keeps
    /// its known limits (a ref name with `=`, `|`, a leading `#`, a newline, or the EMPTY name never reads back; the empty one is dropped without failing the hash, so the next format=2 write makes the loss permanent) but is
    /// still restored, so a node holding only such files is not bricked by the upgrade. A version this
    /// code does not know is refused, never guessed at.
    private static Result<MetadataSnapshot> parseSnapshotContent(String content) {
        var lines = content.split("\n");
        var headers = parseHeaders(lines);
        var version = headers.getOrDefault("format", LEGACY_FORMAT_VERSION);

        return switch (version) {
            case FORMAT_VERSION -> parseCurrentContent(lines, headers);
            case LEGACY_FORMAT_VERSION -> parseLegacyContent(lines, headers);
            default -> new SnapshotError.UnsupportedFormat(version).result();
        };
    }

    private static MetadataSnapshot snapshotOf(Map<String, String> headers,
                                               List<BlockLifecycle> lifecycles,
                                               Map<String, BlockId> refs) {
        return new MetadataSnapshot(Long.parseLong(headers.get("epoch")),
                                    Long.parseLong(headers.get("timestamp")),
                                    headers.get("nodeId"),
                                    lifecycles,
                                    refs,
                                    HEX.parseHex(headers.get("contentHash")));
    }

    // --- Current format (format=2) ---
    private static Result<MetadataSnapshot> parseCurrentContent(String[] lines, Map<String, String> headers) {
        var sections = splitSections(lines);

        return Result.all(Result.allOf(sections.getOrDefault("lifecycles",
                                                             List.of())
                                               .stream()
                                               .map(DefaultSnapshotManager::parseLifecycleLine)
                                               .toList()),
                          parseRefs(sections.getOrDefault("refs",
                                                          List.of())))
                     .map((lifecycles, refs) -> snapshotOf(headers, lifecycles, refs));
    }

    /// The data lines of each `# ...` section, none dropped: in this format a name is percent-encoded, so
    /// no data line can look like a header or a comment.
    private static Map<String, List<String>> splitSections(String[] lines) {
        var sections = new HashMap<String, List<String>>();
        var section = "";

        for (var line : lines) {
            if (line.equals("# Lifecycles")) {
                section = "lifecycles";
            } else if (line.equals("# Refs")) {
                section = "refs";
            } else if (!line.isEmpty() && !section.isEmpty()) {
                sections.computeIfAbsent(section, _ -> new ArrayList<>()).add(line);
            }
        }

        return sections;
    }

    /// A duplicate name or a malformed line fails the snapshot; nothing is skipped (a partially parsed
    /// snapshot is worse than a clean failure).
    private static Result<Map<String, BlockId>> parseRefs(List<String> lines) {
        return Result.allOf(lines.stream().map(DefaultSnapshotManager::parseRefEntry).toList()).flatMap(DefaultSnapshotManager::toDistinctMap);
    }

    private static Result<Map<String, BlockId>> toDistinctMap(List<Map.Entry<String, BlockId>> entries) {
        Map<String, BlockId> refs = entries.stream()
                                           .collect(Collectors.toMap(Map.Entry::getKey,
                                                                     Map.Entry::getValue,
                                                                     (_, last) -> last,
                                                                     LinkedHashMap::new));

        return Result.success(refs).filter(DUPLICATE_REF_NAME,
                                           distinct -> distinct.size() == entries.size());
    }

    private static Result<Map.Entry<String, BlockId>> parseRefEntry(String line) {
        var pipeIndex = line.indexOf('|');

        if (pipeIndex < 0) {
            return MALFORMED_REF_LINE.result();
        }

        return Result.all(SnapshotNameCodec.decode(line.substring(0, pipeIndex)),
                          BlockId.fromHex(line.substring(pipeIndex + 1)))
                     .map(Map::entry);
    }

    // --- Legacy format (no format header) ---
    private static Result<MetadataSnapshot> parseLegacyContent(String[] lines, Map<String, String> headers) {
        var refs = new LinkedHashMap<String, BlockId>();

        return parseLifecycles(lines, refs).map(lifecycles -> snapshotOf(headers, lifecycles, refs))
                              .filter(SnapshotError.INTEGRITY_CHECK_FAILED, DefaultSnapshotManager::matchesLegacyHash)
                              .map(MetadataSnapshot::resealed);
    }

    private static boolean matchesLegacyHash(MetadataSnapshot snapshot) {
        return Arrays.equals(snapshot.contentHash(),
                             MetadataSnapshot.legacyContentHash(snapshot.lifecycles(), snapshot.refs()));
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

    static Map<String, String> parseHeaders(String[] lines) {
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

            return BlockLifecycle.blockLifecycle(id,
                                                 tiers,
                                                 refCount,
                                                 lastAccessed,
                                                 created,
                                                 accessCount,
                                                 orphanedAt,
                                                 hasCacheOnlyFlag(parts));
        }

        return BlockLifecycle.blockLifecycle(id, tiers, refCount, lastAccessed, created, accessCount);
    }

    /// The optional 8th field. Absent for every line an earlier encoder wrote; present means the record is a
    /// cache-only marker. An unknown flag fails the line (and with it the snapshot) rather than being
    /// dropped: a flag this code cannot read could be one whose absence changes what the record means.
    private static boolean hasCacheOnlyFlag(String[] parts) {
        return parts.length > 7 && MetadataSnapshot.LineFlag.valueOf(parts[7]) == MetadataSnapshot.LineFlag.CACHE_ONLY;
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
