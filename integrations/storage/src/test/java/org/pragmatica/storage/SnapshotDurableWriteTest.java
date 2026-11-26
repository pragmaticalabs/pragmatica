package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileError;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.testlogging.LogCapture;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.storage.InMemoryMetadataStore.inMemoryMetadataStore;
import static org.pragmatica.storage.SnapshotConfig.snapshotConfig;
import static org.assertj.core.api.Assertions.assertThat;


/// #1353: a snapshot and its `LATEST` pointer must reach disk complete or not at all, and a torn
/// newest snapshot must fall back to the previous retained one rather than restore nothing.
///
/// The seam models the write stopping part-way (ENOSPC, a process killed mid-write). What it
/// CANNOT model is power loss after a completed `write(2)` -- that is `[unverified: power loss]`
/// on the ticket and stays so here; what these tests pin is the torn-file behaviour.
class SnapshotDurableWriteTest {
    private static final String LOGGER = DefaultSnapshotManager.class.getName();
    private static final String NODE_ID = "node-durable";
    private static final Function<Path, Result<Unit>> NO_SYNC = _ -> Result.unitResult();

    @TempDir
    Path tempDir;

    private InMemoryMetadataStore store;
    private SnapshotConfig config;
    private LogCapture capture;

    @BeforeEach
    void setUp() {
        store = inMemoryMetadataStore("durable");
        config = snapshotConfig(tempDir, 100, 600_000, 5, NODE_ID);
        capture = LogCapture.capture(LOGGER, Level.ALL, true);
    }

    @AfterEach
    void tearDown() {
        capture.close();
    }

    // --- Write path: interrupted writes ---
    /// The `LATEST` write stops half-way. Before #1353 `LATEST` was truncated in place, so the
    /// half-written name pointed nowhere and the boot restored NOTHING although a complete
    /// snapshot sat beside it. Now the torn bytes land in `LATEST.partial`, the rename never
    /// happens, and `LATEST` still names the previous complete snapshot.
    @Test
    void forceSnapshot_latestWriteInterrupted_previousSnapshotStillRestores() {
        var disk = new TearingSync();
        var manager = new DefaultSnapshotManager(store, config, disk::sync);

        mutate("first");
        manager.forceSnapshot();
        var epochOnDisk = manager.lastSnapshotEpoch();

        disk.tearOnCall(2);
        mutate("second");
        manager.forceSnapshot();
        var restored = manager.restoreFromLatest().unwrap();

        assertThat(restored.isPresent()).as("the previous complete snapshot still restores").isTrue();
        assertThat(restored.unwrap().epoch()).isEqualTo(epochOnDisk);
        assertThat(warnsMentioning("Snapshot restore failed")).isEmpty();
        assertThat(manager.lastSnapshotEpoch()).as("the interrupted snapshot was not recorded as taken")
                  .isEqualTo(epochOnDisk);
        assertThat(disk.tornPath.get()).as("the fixture tore the LATEST write").hasFileName("LATEST.partial");
        assertThat(tempDir.resolve("LATEST.partial")).as("the torn partial is removed").doesNotExist();
    }

    /// The snapshot write itself stops half-way. Before #1353 the torn bytes sat under the final
    /// `snapshot-NNNNNN.dat` name -- newest by epoch, so retained longest, and one `LATEST` repoint
    /// away from being restored. Now they land in `snapshot.partial` and are removed with the
    /// failure, so no name a later boot or prune could pick up ever holds a torn file.
    @Test
    void forceSnapshot_snapshotWriteInterrupted_leavesNoTornFileUnderAnyName() {
        var disk = new TearingSync();
        var manager = new DefaultSnapshotManager(store, config, disk::sync);

        mutate("first");
        manager.forceSnapshot();
        var epochOnDisk = manager.lastSnapshotEpoch();

        disk.tearOnCall(1);
        mutate("second");
        manager.forceSnapshot();
        assertThat(snapshotFiles()).as("only the complete snapshot remains").containsExactly("snapshot-000001.dat");
        assertThat(tempDir.resolve("snapshot.partial")).as("the torn partial is removed").doesNotExist();
        assertThat(manager.lastSnapshotEpoch()).isEqualTo(epochOnDisk);
        assertThat(disk.tornPath.get()).as("the fixture tore the snapshot write").hasFileName("snapshot.partial");
        assertThat(manager.restoreFromLatest().unwrap().unwrap().epoch()).isEqualTo(epochOnDisk);
    }

    /// The rename itself fails: the seam replaces the `LATEST` partial with a DIRECTORY, so
    /// `rename(2)` over the existing `LATEST` fails with ENOTDIR under `ATOMIC_MOVE` and the
    /// previous pointer survives -- while a non-atomic replace unlinks `LATEST` FIRST and the rename
    /// then succeeds, leaving a directory where the pointer was (shape from #676 / verify-1118).
    /// Only the second sync of the round is affected, so the snapshot file itself is complete and
    /// this pins the rename alone. Not a base reproducer: the pre-#1353 path has no partial.
    @Test
    void forceSnapshot_renameFails_previousLatestSurvives() {
        var disk = new DirectorySync();
        var manager = new DefaultSnapshotManager(store, config, disk::sync);

        mutate("first");
        manager.forceSnapshot();
        var epochOnDisk = manager.lastSnapshotEpoch();

        disk.directoryOnCall(2);
        mutate("second");
        manager.forceSnapshot();
        assertThat(tempDir.resolve("LATEST")).as("LATEST is still the previous pointer file").isRegularFile();
        assertThat(manager.lastSnapshotEpoch()).isEqualTo(epochOnDisk);
        assertThat(tempDir.resolve("snapshot.partial")).doesNotExist();
        assertThat(tempDir.resolve("LATEST.partial")).doesNotExist();
        assertThat(manager.restoreFromLatest().unwrap().unwrap().epoch()).isEqualTo(epochOnDisk);
    }

    /// The sync step runs exactly once per partial -- `snapshot.partial` and `LATEST.partial` --
    /// per snapshot. Dropping `force(true)` from the write path drops these calls to zero. What the
    /// production sync step does inside (`force(true)`, not `force(false)`) is not observable from
    /// here: rev1365 measured it with strace, 48 `fsync(2)` at head and 0 with the step removed.
    @Test
    void forceSnapshot_syncsEachPartialExactlyOnce() {
        var counting = new CountingSync();
        var manager = new DefaultSnapshotManager(store, config, counting::sync);

        mutate("first");
        manager.forceSnapshot();
        assertThat(counting.callsPerFile).containsOnly(Map.entry("snapshot.partial", 1), Map.entry("LATEST.partial", 1));
        assertThat(manager.restoreFromLatest().unwrap().isPresent()).as("the counted write is a real one").isTrue();
    }

    /// #1567: each rename is followed by a force of the snapshot directory, so the entry the rename
    /// installed survives a power loss -- the partial first, then its directory, for the snapshot and
    /// then for `LATEST`. Read from the JDK's own `jdk.FileForce` events, not from an injected seam.
    @Test
    void forceSnapshot_forcesTheSnapshotDirectory_afterEachRename() {
        var manager = new DefaultSnapshotManager(store, config);

        mutate("first");
        var forced = FileForceRecording.forcedFilesDuring(manager::forceSnapshot)
                                       .stream()
                                       .map(FileForceRecording.ForcedFile::path)
                                       .map(path -> path.getFileName().toString())
                                       .toList();
        var dirName = tempDir.toAbsolutePath().getFileName().toString();

        assertThat(forced).containsSubsequence("snapshot.partial", dirName, "LATEST.partial", dirName);
    }

    /// rev1365 B1: `forceSnapshot()` is called from the scheduler tick (via `maybeSnapshot`) and
    /// from the HTTP route on different threads. Both write through the same fixed partial names,
    /// so two writers at once tear each other's files. The sync step is inside the write, so its
    /// concurrent depth is the write's concurrent depth; with the lock it never exceeds one. The
    /// sleep widens the window so the unlocked shape reddens reliably rather than by luck.
    @Test
    void forceSnapshot_concurrentCallers_writeOneAtATime() {
        var depth = new DepthMeasuringSync();
        var manager = new DefaultSnapshotManager(store, config, depth::sync);

        mutate("seed");
        runConcurrently(4, 10, manager);
        assertThat(depth.maxDepth.get()).as("max concurrent write depth").isEqualTo(1);
        assertThat(depth.calls.get()).as("every round synced both partials").isEqualTo(4 * 10 * 2);
    }

    /// rev1365's probe, adopted: 4 threads x 25 `forceSnapshot()` on a 20k-lifecycle store (~2 MB a
    /// file) with the PRODUCTION writer. Every surviving `snapshot-*.dat` is validated alone, in a
    /// fresh directory with a `LATEST` naming it, through `restoreFromLatest()`: none => torn; epoch
    /// differing from the name => one writer's rename published another writer's bytes. Unlocked
    /// head measured 3 torn + 26 misnamed of 51 files (two runs); the pre-#1353 per-epoch in-place
    /// write measured 0/0 of 100.
    @Test
    void forceSnapshot_concurrentCallers_leaveOnlyCompleteCorrectlyNamedFiles() {
        var raceDir = tempDir.resolve("snapshots");
        var raceConfig = snapshotConfig(raceDir, 1, 600_000, 10_000, NODE_ID);
        var manager = SnapshotManager.snapshotManager(store, raceConfig);

        IntStream.range(0, 20_000).forEach(i -> mutate("seed-" + i));
        runConcurrently(4, 25, manager);
        var files = snapshotFiles(raceDir);
        var validated = files.stream().collect(Collectors.toMap(name -> name, name -> validatedAlone(raceDir, name)));
        var torn = files.stream().filter(name -> validated.get(name)
                                                          .isEmpty()).toList();
        var misnamed = files.stream()
                            .filter(name -> validated.get(name)
                                                     .map(snapshot -> snapshot.epoch() != epochInName(name))
                                                     .or(false))
                            .toList();
        // Fewer than 100 files is expected: the name comes from the epoch at capture time, so two
        // callers that both mutated before either captured write the SAME NAME -- and not the same
        // bytes (the header carries the capture's timestamp, and `createLifecycle` puts before it
        // increments, so two same-epoch captures can differ). Under the lock the later complete
        // write atomically replaces the earlier; neither torn nor misnamed is possible. The file
        // count is the number of distinct epochs captured (90 of 100 measured).
        assertThat(files).as("the writers wrote something").isNotEmpty();
        assertThat(torn).as("torn snapshot files under a final name").isEmpty();
        assertThat(misnamed).as("files whose content epoch differs from the name").isEmpty();
        assertThat(raceDir.resolve(FileOps.readString(raceDir.resolve("LATEST")).unwrap().trim())).as("LATEST names an existing file")
                  .exists();
        assertThat(warnsMentioning("Snapshot write failed")).isEmpty();
    }

    // --- Write-time read-back (#2257) ---
    /// A snapshot is read back through the restore path before its file is committed. The node id is
    /// written as-is, so one holding a newline encodes to a file that restores under a different id.
    @Test
    void snapshotNow_nodeIdThatDoesNotReadBack_isRefusedAndPreviousSnapshotKept() {
        var good = new DefaultSnapshotManager(store, config);
        var badConfig = snapshotConfig(tempDir, 100, 600_000, 5, "node\nwith-newline");

        mutate("kept");
        good.snapshotNow().unwrap();
        var latestBefore = readLatest();
        mutate("refused");

        var refused = new DefaultSnapshotManager(store, badConfig).snapshotNow();

        assertRefusedAndPreviousIntact(refused, latestBefore);
    }

    /// An encoder defect of the SA-01 kind -- a line lost on the way out -- is caught by the integrity
    /// check the restore would apply, at write time, not at the next boot.
    @Test
    void snapshotNow_serializerDropsRefLine_isRefusedAtWrite() {
        var good = new DefaultSnapshotManager(store, config);

        mutate("kept");
        store.putRef("kept", store.listAllLifecycles().getFirst().blockId());
        good.snapshotNow().unwrap();
        var latestBefore = readLatest();
        store.putRef("lost", store.listAllLifecycles().getFirst().blockId());

        var refused = new DefaultSnapshotManager(store, config, NO_SYNC, SnapshotDurableWriteTest::withoutLastLine).snapshotNow();

        assertRefusedAndPreviousIntact(refused, latestBefore);
    }

    /// A separator mangled by the encoder is a parse failure on the read-back, refused at write time.
    @Test
    void snapshotNow_serializerMangledSeparator_isRefusedAtWrite() {
        var good = new DefaultSnapshotManager(store, config);

        mutate("kept");
        store.putRef("kept", store.listAllLifecycles().getFirst().blockId());
        good.snapshotNow().unwrap();
        var latestBefore = readLatest();
        store.putRef("mangled", store.listAllLifecycles().getFirst().blockId());

        var refused = new DefaultSnapshotManager(store, config, NO_SYNC, SnapshotDurableWriteTest::withMangledSeparator).snapshotNow();

        assertRefusedAndPreviousIntact(refused, latestBefore);
    }

    /// The encoder itself loses information. The file is written from a snapshot that was rebuilt with the
    /// loss, so its hash is consistent with what it holds: only a comparison with the in-memory source
    /// by value can see it.
    @Test
    void snapshotNow_encoderLosesRefNameCharacter_isRefusedAtWrite() {
        var latestBefore = keptSnapshotThenChange();

        store.putRef("a\0b", store.listAllLifecycles().getFirst().blockId());

        var refused = new DefaultSnapshotManager(store, config, NO_SYNC, lossy(SnapshotDurableWriteTest::withoutNul)).snapshotNow();

        assertRefusedAndPreviousIntact(refused, latestBefore);
    }

    @Test
    void snapshotNow_encoderLosesRefCount_isRefusedAtWrite() {
        assertLifecycleLossRefused(lc -> new BlockLifecycle(lc.blockId(), lc.presentIn(), lc.refCount() + 1, lc.lastAccessedAt(), lc.createdAt(), lc.accessCount(), lc.orphanedAt()));
    }

    @Test
    void snapshotNow_encoderLosesLastAccessedAt_isRefusedAtWrite() {
        assertLifecycleLossRefused(lc -> new BlockLifecycle(lc.blockId(), lc.presentIn(), lc.refCount(), lc.lastAccessedAt() + 1, lc.createdAt(), lc.accessCount(), lc.orphanedAt()));
    }

    @Test
    void snapshotNow_encoderLosesCreatedAt_isRefusedAtWrite() {
        assertLifecycleLossRefused(lc -> new BlockLifecycle(lc.blockId(), lc.presentIn(), lc.refCount(), lc.lastAccessedAt(), lc.createdAt() + 1, lc.accessCount(), lc.orphanedAt()));
    }

    @Test
    void snapshotNow_encoderLosesAccessCount_isRefusedAtWrite() {
        assertLifecycleLossRefused(lc -> new BlockLifecycle(lc.blockId(), lc.presentIn(), lc.refCount(), lc.lastAccessedAt(), lc.createdAt(), lc.accessCount() + 1, lc.orphanedAt()));
    }

    @Test
    void snapshotNow_encoderLosesOrphanedAt_isRefusedAtWrite() {
        assertLifecycleLossRefused(lc -> new BlockLifecycle(lc.blockId(), lc.presentIn(), lc.refCount(), lc.lastAccessedAt(), lc.createdAt(), lc.accessCount(), 0L));
    }

    @Test
    void snapshotNow_encoderLosesTiers_isRefusedAtWrite() {
        assertLifecycleLossRefused(lc -> new BlockLifecycle(lc.blockId(), EnumSet.of(TierLevel.MEMORY, TierLevel.LOCAL_DISK), lc.refCount(), lc.lastAccessedAt(), lc.createdAt(), lc.accessCount(), lc.orphanedAt()));
    }

    @Test
    void snapshotNow_encoderLosesBlockId_isRefusedAtWrite() {
        assertLifecycleLossRefused(lc -> new BlockLifecycle(BlockId.blockId(new byte[]{1}).unwrap(), lc.presentIn(), lc.refCount(), lc.lastAccessedAt(), lc.createdAt(), lc.accessCount(), lc.orphanedAt()));
    }

    @Test
    void snapshotNow_encoderLosesRefTarget_isRefusedAtWrite() {
        var latestBefore = keptSnapshotThenChange();

        store.putRef("target", store.listAllLifecycles().getFirst().blockId());

        var refused = new DefaultSnapshotManager(store, config, NO_SYNC, lossy(SnapshotDurableWriteTest::withOtherTargets)).snapshotNow();

        assertRefusedAndPreviousIntact(refused, latestBefore);
    }

    /// A file with an extra trailing line, or an extra ref line, does not read back as captured.
    @Test
    void snapshotNow_fileWithTrailingLine_isRefusedAtWrite() {
        var latestBefore = keptSnapshotThenChange();

        mutate("changed");

        var refused = new DefaultSnapshotManager(store, config, NO_SYNC, snapshot -> DefaultSnapshotManager.serializeSnapshot(snapshot) + "extra-line\n").snapshotNow();

        assertRefusedAndPreviousIntact(refused, latestBefore);
    }

    /// A raw character that is not part of the encoded alphabet is a malformed token even when it equals the name.
    @Test
    void snapshotNow_nameWrittenRaw_isRefusedAtWrite() {
        var latestBefore = keptSnapshotThenChange();

        store.putRef("a b", store.listAllLifecycles().getFirst().blockId());

        var refused = new DefaultSnapshotManager(store, config, NO_SYNC, snapshot -> DefaultSnapshotManager.serializeSnapshot(snapshot).replace("a%20b|", "a b|")).snapshotNow();

        assertRefusedAndPreviousIntact(refused, latestBefore);
    }

    private void assertLifecycleLossRefused(UnaryOperator<BlockLifecycle> loss) {
        var latestBefore = keptSnapshotThenChange();

        store.createLifecycle(new BlockLifecycle(BlockId.blockId("distinct".getBytes(StandardCharsets.UTF_8)).unwrap(), EnumSet.of(TierLevel.MEMORY), 2, 11L, 12L, 13, 14L));

        var refused = new DefaultSnapshotManager(store, config, NO_SYNC, lossy(snapshot -> withLifecycles(snapshot, loss))).snapshotNow();

        assertRefusedAndPreviousIntact(refused, latestBefore);
    }

    private static MetadataSnapshot withLifecycles(MetadataSnapshot snapshot, UnaryOperator<BlockLifecycle> loss) {
        return MetadataSnapshot.metadataSnapshot(snapshot.epoch(), snapshot.nodeId(), snapshot.lifecycles().stream().map(loss).toList(), snapshot.refs());
    }

    private static MetadataSnapshot withOtherTargets(MetadataSnapshot snapshot) {
        var other = BlockId.blockId(new byte[]{9}).unwrap();
        var refs = snapshot.refs().keySet().stream().collect(Collectors.toMap(name -> name, _ -> other, (a, b) -> a, LinkedHashMap::new));

        return MetadataSnapshot.metadataSnapshot(snapshot.epoch(), snapshot.nodeId(), snapshot.lifecycles(), refs);
    }

    @Test
    void snapshotNow_encoderShiftsEpoch_isRefusedAtWrite() {
        var latestBefore = keptSnapshotThenChange();

        mutate("changed");

        var refused = new DefaultSnapshotManager(store, config, NO_SYNC, lossy(SnapshotDurableWriteTest::withNextEpoch)).snapshotNow();

        assertRefusedAndPreviousIntact(refused, latestBefore);
    }

    /// The hash is computed once, from the captured snapshot, and the read-back checks the file carries that
    /// value: a file whose header hash is not the captured one is refused although its content is right.
    @Test
    void snapshotNow_headerHashIsNotTheCapturedOne_isRefusedAtWrite() {
        var latestBefore = keptSnapshotThenChange();

        mutate("changed");

        var refused = new DefaultSnapshotManager(store, config, NO_SYNC, SnapshotDurableWriteTest::withZeroHash).snapshotNow();

        assertRefusedAndPreviousIntact(refused, latestBefore);
    }

    private static String withZeroHash(MetadataSnapshot snapshot) {
        return DefaultSnapshotManager.serializeSnapshot(snapshot).replaceFirst("contentHash=[0-9a-f]+", "contentHash=" + "00".repeat(32));
    }

    private String keptSnapshotThenChange() {
        mutate("kept");
        store.putRef("kept", store.listAllLifecycles().getFirst().blockId());
        new DefaultSnapshotManager(store, config).snapshotNow().unwrap();

        return readLatest();
    }

    /// A lossy encoder as it would be seen in production: the header carries the hash of the CAPTURED
    /// snapshot (computed while rendering), so only the body lines can disagree with the in-memory source.
    private static Function<MetadataSnapshot, String> lossy(Function<MetadataSnapshot, MetadataSnapshot> loss) {
        return snapshot -> withCapturedHash(snapshot, loss.apply(snapshot));
    }

    private static String withCapturedHash(MetadataSnapshot captured, MetadataSnapshot lossy) {
        return DefaultSnapshotManager.serializeSnapshot(lossy)
                                     .replace("contentHash=" + HexFormat.of().formatHex(lossy.contentHash()),
                                              "contentHash=" + HexFormat.of().formatHex(captured.contentHash()));
    }

    @Test
    void snapshotNow_formatHeaderDisagrees_isRefusedAtWrite() {
        assertTextDefectRefused(content -> content.replace("format=2\n", "format=7\n"));
    }

    @Test
    void snapshotNow_lifecycleLineWithExtraField_isRefusedAtWrite() {
        assertTextDefectRefused(content -> content.replaceAll("(?m)^([0-9a-f]{64}\\|.*)$", "$1|9"));
    }

    @Test
    void snapshotNow_refTargetWithTrailingCharacters_isRefusedAtWrite() {
        assertTextDefectRefused(content -> content.replaceAll("(?m)^([^\\n#=|]+\\|[0-9a-f]{64})$", "$1ab"));
    }

    private void assertTextDefectRefused(UnaryOperator<String> defect) {
        var latestBefore = keptSnapshotThenChange();

        mutate("changed");

        var refused = new DefaultSnapshotManager(store, config, NO_SYNC, snapshot -> defect.apply(DefaultSnapshotManager.serializeSnapshot(snapshot))).snapshotNow();

        assertRefusedAndPreviousIntact(refused, latestBefore);
    }

    private static MetadataSnapshot withoutNul(MetadataSnapshot snapshot) {
        var refs = snapshot.refs().entrySet().stream().collect(Collectors.toMap(e -> e.getKey().replace("\0", ""), Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));

        return MetadataSnapshot.metadataSnapshot(snapshot.epoch(), snapshot.nodeId(), snapshot.lifecycles(), refs);
    }

    private static MetadataSnapshot withNextEpoch(MetadataSnapshot snapshot) {
        return MetadataSnapshot.metadataSnapshot(snapshot.epoch() + 1, snapshot.nodeId(), snapshot.lifecycles(), snapshot.refs());
    }

    private static String withoutLastLine(MetadataSnapshot snapshot) {
        var lines = DefaultSnapshotManager.serializeSnapshot(snapshot).split("\n");

        return String.join("\n", List.of(lines).subList(0, lines.length - 1)) + "\n";
    }

    private static String withMangledSeparator(MetadataSnapshot snapshot) {
        return DefaultSnapshotManager.serializeSnapshot(snapshot).replace("mangled|", "mangled ");
    }

    private void assertRefusedAndPreviousIntact(Result<MetadataSnapshot> refused, String latestBefore) {
        refused.onSuccess(_ -> Assertions.fail("a snapshot that does not read back must not be written"))
               .onFailure(cause -> assertThat(cause).isInstanceOf(SnapshotError.RoundTripFailed.class));
        assertThat(readLatest()).as("LATEST still names the previous snapshot").isEqualTo(latestBefore);
        assertThat(snapshotFiles()).as("no new snapshot file").hasSize(1);
        assertThat(errorsMentioning("NOT written")).as("the refusal is logged at ERROR").hasSize(1);
        assertThat(new DefaultSnapshotManager(inMemoryMetadataStore("boot"), config).restoreFromLatest().unwrap().isPresent())
            .as("the previous snapshot still restores")
            .isTrue();
    }

    // --- Read path: fallback ---
    /// The file `LATEST` names is torn on disk (the power-loss shape the write path cannot rule
    /// out, or an operator's half-copied file). Before #1353 the boot restored nothing. Now the
    /// previous retained snapshot restores, the WARN names both files, and the read path leaves
    /// `LATEST` and the torn file exactly as it found them.
    @Test
    void restoreFromLatest_newestSnapshotTorn_fallsBackToPreviousRetained() {
        var manager = SnapshotManager.snapshotManager(store, config);

        mutate("first");
        manager.forceSnapshot();
        var previousEpoch = manager.lastSnapshotEpoch();

        mutate("second");
        manager.forceSnapshot();
        var newest = latestTarget();
        var latestBefore = readLatest();

        truncateToHalf(newest);
        var restored = manager.restoreFromLatest().unwrap();

        assertThat(restored.isPresent()).as("the previous retained snapshot restores").isTrue();
        assertThat(restored.unwrap().epoch()).isEqualTo(previousEpoch);
        assertThat(restored.unwrap().lifecycles()).hasSize(1);
        assertThat(warnsMentioning("restored another retained snapshot")).hasSize(1)
                  .first()
                  .satisfies(warn -> assertThat(warn).contains(newest.getFileName().toString())
                                               .contains("snapshot-000001.dat"));
        assertThat(readLatest()).as("restore does not rewrite LATEST").isEqualTo(latestBefore);
        assertThat(newest).as("the torn file is left as evidence").exists();
        assertThat(warnsMentioning("Snapshot restore failed")).as("the torn target is tried once, via LATEST, and not again as a candidate")
                  .hasSize(1);
    }

    /// `LATEST` itself is torn (a pre-#1353 in-place write cut by power loss: half a file name).
    /// The newest retained snapshot restores instead of nothing.
    @Test
    void restoreFromLatest_latestPointerTorn_fallsBackToNewestRetained() {
        var manager = SnapshotManager.snapshotManager(store, config);

        mutate("first");
        manager.forceSnapshot();
        mutate("second");
        manager.forceSnapshot();
        var newestEpoch = manager.lastSnapshotEpoch();

        FileOps.writeString(tempDir.resolve("LATEST"), "snapshot-0").unwrap();
        var restored = manager.restoreFromLatest().unwrap();

        assertThat(restored.isPresent()).isTrue();
        assertThat(restored.unwrap().epoch()).as("newest complete snapshot, not the oldest").isEqualTo(newestEpoch);
        assertThat(warnsMentioning("restored another retained snapshot")).hasSize(1);
    }

    /// Every candidate is tried newest-first and a torn one is skipped, never restored: two torn
    /// files on top, the third restores.
    @Test
    void restoreFromLatest_twoNewestTorn_fallsBackToThird() {
        var manager = SnapshotManager.snapshotManager(store, config);

        mutate("first");
        manager.forceSnapshot();
        var thirdNewestEpoch = manager.lastSnapshotEpoch();

        mutate("second");
        manager.forceSnapshot();
        var secondNewest = latestTarget();

        mutate("third");
        manager.forceSnapshot();
        var newest = latestTarget();

        truncateToHalf(newest);
        truncateToHalf(secondNewest);
        var restored = manager.restoreFromLatest().unwrap();

        assertThat(restored.unwrap().epoch()).isEqualTo(thirdNewestEpoch);
    }

    /// Nothing complete remains: a FAILURE naming the file, never a torn restore and (#1013) never
    /// `none` -- before #1013 this returned the same `none` as a first boot, with only a WARN to
    /// tell them apart, and the caller signalled readiness on both.
    @Test
    void restoreFromLatest_onlySnapshotTorn_failsNothingRestorable() {
        var manager = SnapshotManager.snapshotManager(store, config);

        mutate("first");
        manager.forceSnapshot();
        var torn = latestTarget();

        truncateToHalf(torn);

        assertNothingRestorable(manager.restoreFromLatest(), "Snapshot " + torn + " named by LATEST is unreadable", 0);
    }

    /// #1013: `LATEST` names a file that is not on disk (the shape #1012's prune produced), and no
    /// other snapshot is retained.
    @Test
    void restoreFromLatest_latestDangles_failsNothingRestorable() {
        var manager = SnapshotManager.snapshotManager(store, config);

        mutate("first");
        manager.forceSnapshot();
        var target = latestTarget();

        FileOps.delete(target).unwrap();

        assertNothingRestorable(manager.restoreFromLatest(), "Snapshot " + target + " named by LATEST is unreadable", 0);
    }

    /// #1013: no `LATEST`, but a snapshot file is on disk and it is torn. Before #1013 this was the
    /// quietest shape: the fallback found nothing, did not WARN (the WARN was keyed on `LATEST` being
    /// present) and returned the first-boot `none`. A snapshot file is evidence of metadata that
    /// existed; it must not read as a first boot.
    @Test
    void restoreFromLatest_noLatestAndOnlyTornSnapshot_failsNothingRestorable() {
        var manager = SnapshotManager.snapshotManager(store, config);

        mutate("first");
        manager.forceSnapshot();
        var target = latestTarget();

        FileOps.delete(tempDir.resolve("LATEST")).unwrap();
        truncateToHalf(target);

        assertNothingRestorable(manager.restoreFromLatest(), "LATEST is missing or unreadable", 1);
    }

    /// #1013: the snapshot directory EXISTS but cannot be listed (here: the path is a regular file,
    /// which `exists` but is no directory). Absence cannot be established, so this is a failure --
    /// not the empty list the old fallback turned every listing failure into.
    @Test
    void restoreFromLatest_snapshotDirectoryUnlistable_fails() {
        var notADirectory = tempDir.resolve("snapshots-as-a-file");

        FileOps.writeString(notADirectory, "not a directory").unwrap();
        var manager = SnapshotManager.snapshotManager(store, snapshotConfig(notADirectory, 100, 600_000, 5, NODE_ID));

        // Fixture control: the path must exist and must refuse a listing, or the test passes for the
        // wrong reason (a missing directory is a legitimate first boot).
        assertThat(FileOps.exists(notADirectory)).isTrue();
        assertThat(FileOps.list(notADirectory).isFailure()).isTrue();

        var restored = manager.restoreFromLatest();

        assertThat(restored.isFailure()).as("an unlistable snapshot directory is not an absent one").isTrue();
        restored.onFailure(cause -> assertThat(cause).isInstanceOf(SnapshotError.ReadFailed.class));
    }

    /// A first boot has no `LATEST` and no snapshots; the fallback must not turn that into a WARN,
    /// and (#1013) it is a SUCCESS with none, distinct from the failures above.
    @Test
    void restoreFromLatest_firstBoot_noSnapshotAndNoWarn() {
        var manager = SnapshotManager.snapshotManager(store, config);

        assertThat(manager.restoreFromLatest().unwrap().isEmpty()).isTrue();
        assertThat(capture.messages(Level.WARN)).isEmpty();
    }

    /// #1013: the snapshot directory does not exist yet either (the first boot of a node whose data
    /// dir was just created). Absent, by inspection: success with none.
    ///
    /// `[unverified: unmounted volume]` #1013 round 2 -- what this test does NOT distinguish, stated
    /// so the gap is not mistaken for coverage. An absent snapshot directory reads as a first boot
    /// whether nothing was ever written OR the volume never mounted. Refusing on an absent DATA ROOT
    /// was implemented here and reverted: "data root absent" is the ORDINARY state wherever `/data`
    /// is not writable (CI, every laptop), where the disk tier degrades to memory+DHT by design --
    /// `HermeticStorage` (#1276) builds exactly that state on purpose, and refusing on it failed 52
    /// aether/node boot tests across 18 classes that pin "boot never fails on an unmountable data dir".
    ///
    /// The discriminator lives one layer up, in `StorageFactory`, which knows whether the disk tier
    /// ARMED or degraded. See the note on `DefaultSnapshotManager.previousRetained`; it needs its own
    /// ticket and is out of #1013's scope.
    @Test
    void restoreFromLatest_snapshotDirectoryMissing_isAbsent() {
        var missing = tempDir.resolve("never-created");
        var manager = SnapshotManager.snapshotManager(store, snapshotConfig(missing, 100, 600_000, 5, NODE_ID));

        assertThat(FileOps.exists(missing)).isFalse();
        assertThat(manager.restoreFromLatest().unwrap().isEmpty()).isTrue();
        assertThat(capture.messages(Level.WARN)).isEmpty();
    }

    /// #1013 round 2: the AGGREGATE verdict is WARNed, not only returned. The PR that made this state
    /// detectable deleted the base's `reportNothingRestorable` WARN; on the boot path the caller turns
    /// the failure into a named refusal, but on a RUNNING node's truncation tick there is no boot left
    /// to refuse and the state went silent.
    ///
    /// Two controls, because a log assertion is an instrument: `restoreFromLatest_firstBoot_noSnapshotAndNoWarn`
    /// drives the same appender over the opposite arm and asserts NO warn at all; and the per-file
    /// `Snapshot restore failed` line is asserted to be a DIFFERENT line that does NOT carry the
    /// aggregate text, so this cannot be satisfied by the per-file WARN that was never removed.
    @Test
    void restoreFromLatest_onlySnapshotTorn_warnsTheAggregateVerdict() {
        var manager = SnapshotManager.snapshotManager(store, config);

        mutate("first");
        manager.forceSnapshot();

        var torn = latestTarget();

        truncateToHalf(torn);
        manager.restoreFromLatest();

        assertThat(warnsMentioning("refusing to start with EMPTY metadata")).as("the aggregate verdict is WARNed, not only returned")
                                                                                     .hasSize(1);
        assertThat(warnsMentioning("Snapshot restore failed")).as("control: the per-file WARN is a separate line and still fires")
                                                                       .hasSize(1);
        assertThat(warnsMentioning("Snapshot restore failed")
                           .getFirst()).as("control: the per-file line does NOT carry the aggregate text, so the assertion above needs the restored WARN")
                                      .doesNotContain("refusing to start with EMPTY metadata");
    }

    private static void assertNothingRestorable(Result<Option<MetadataSnapshot>> restored, String expectedLatest, int expectedRetained) {
        assertThat(restored.isFailure()).as("something is on disk and nothing restores: a failure, not none").isTrue();
        restored.onFailure(cause -> assertNothingRestorableCause(cause, expectedLatest, expectedRetained));
    }

    private static void assertNothingRestorableCause(Cause cause, String expectedLatest, int expectedRetained) {
        assertThat(cause).isInstanceOf(SnapshotError.NothingRestorable.class);
        assertThat(cause.message()).contains(expectedLatest)
                                   .contains("none of the " + expectedRetained + " other retained snapshot(s) restores")
                                   .contains("refusing to start with EMPTY metadata");
    }

    // --- Helpers ---
    private void mutate(String content) {
        var id = BlockId.blockId(content.getBytes(StandardCharsets.UTF_8)).unwrap();

        store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));
    }

    private Path latestTarget() {
        return tempDir.resolve(readLatest().trim());
    }

    private String readLatest() {
        return FileOps.readString(tempDir.resolve("LATEST")).unwrap();
    }

    private static void truncateToHalf(Path file) {
        var bytes = FileOps.readBytes(file).unwrap();

        FileOps.writeBytes(file, Arrays.copyOf(bytes, bytes.length / 2)).unwrap();
    }

    private List<String> snapshotFiles() {
        return snapshotFiles(tempDir);
    }

    private static List<String> snapshotFiles(Path dir) {
        var names = FileOps.list(dir).unwrap().stream().map(p -> p.getFileName()
                                                                  .toString());

        return names.filter(name -> name.startsWith("snapshot-") && name.endsWith(".dat"))
                    .sorted()
                    .toList();
    }

    /// Each of `threads` workers mutates the store and calls `forceSnapshot()` `rounds` times,
    /// released together by a latch.
    private void runConcurrently(int threads, int rounds, SnapshotManager manager) {
        var start = new CountDownLatch(1);
        var workers = IntStream.range(0, threads)
                               .mapToObj(id -> Thread.ofPlatform().start(() -> worker(start, id, rounds, manager)))
                               .toList();

        start.countDown();
        workers.forEach(SnapshotDurableWriteTest::join);
    }

    private static void join(Thread worker) {
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void worker(CountDownLatch start, int id, int rounds, SnapshotManager manager) {
        awaitStart(start);
        for (var round = 0; round < rounds; round++) {
            mutate("t" + id + "-r" + round);
            manager.forceSnapshot();
        }
    }

    private static void awaitStart(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// Restores `name` the way production would: alone, in a fresh directory, with a `LATEST`
    /// naming it. Empty means the file does not restore (torn or unparsable).
    private Option<MetadataSnapshot> validatedAlone(Path dir, String name) {
        var probeDir = tempDir.resolve("probe-" + name);

        FileOps.createDirectories(probeDir).unwrap();
        FileOps.copy(dir.resolve(name), probeDir.resolve(name)).unwrap();
        FileOps.writeString(probeDir.resolve("LATEST"), name).unwrap();

        return SnapshotManager.snapshotManager(inMemoryMetadataStore("validate"),
                                               snapshotConfig(probeDir, 1, 600_000, 10_000, "validate")).restoreFromLatest()
                              .fold(_ -> none(), restored -> restored);
    }

    private static long epochInName(String name) {
        return Long.parseLong(name.substring("snapshot-".length(),
                                             name.length() - ".dat".length()));
    }

    /// Sync step that, on call N, cuts the file it is handed to half its length and reports a
    /// failure: the bytes past the cut never reached the disk.
    private static final class TearingSync {
        private final AtomicInteger tearOnCall = new AtomicInteger(Integer.MAX_VALUE);
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<Path> tornPath = new AtomicReference<>();

        void tearOnCall(int call) {
            calls.set(0);
            tearOnCall.set(call);
        }

        Result<Unit> sync(Path path) {
            if (calls.incrementAndGet() != tearOnCall.get()) {
                return Result.unitResult();
            }

            tornPath.set(path);
            truncateToHalf(path);

            return new FileError.WriteFailed(path, "No space left on device").result();
        }
    }

    /// Sync step that, on call N, replaces the file it is handed with a DIRECTORY, so the rename
    /// over the existing target must fail.
    private static final class DirectorySync {
        private final AtomicInteger directoryOnCall = new AtomicInteger(Integer.MAX_VALUE);
        private final AtomicInteger calls = new AtomicInteger();

        void directoryOnCall(int call) {
            calls.set(0);
            directoryOnCall.set(call);
        }

        Result<Unit> sync(Path path) {
            return calls.incrementAndGet() == directoryOnCall.get()
                   ? FileOps.delete(path)
                            .flatMap(_ -> FileOps.createDirectories(path))
                            .mapToUnit()
                   : Result.unitResult();
        }
    }

    /// Sync step that counts calls per file name.
    private static final class CountingSync {
        private final Map<String, Integer> callsPerFile = new ConcurrentHashMap<>();

        Result<Unit> sync(Path path) {
            callsPerFile.merge(path.getFileName().toString(),
                               1,
                               Integer::sum);

            return Result.unitResult();
        }
    }

    /// Sync step that measures how many writers are inside the write at once, holding each for a
    /// few milliseconds so an unserialised pair overlaps.
    private static final class DepthMeasuringSync {
        private final AtomicInteger depth = new AtomicInteger();
        private final AtomicInteger maxDepth = new AtomicInteger();
        private final AtomicInteger calls = new AtomicInteger();

        Result<Unit> sync(Path path) {
            maxDepth.accumulateAndGet(depth.incrementAndGet(), Math::max);
            calls.incrementAndGet();
            pause();
            depth.decrementAndGet();

            return Result.unitResult();
        }

        private static void pause() {
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private List<String> warnsMentioning(String fragment) {
        return capture.messages(Level.WARN)
                      .stream()
                      .filter(message -> message.contains(fragment))
                      .toList();
    }

    private List<String> errorsMentioning(String fragment) {
        return capture.messages(Level.ERROR)
                      .stream()
                      .filter(message -> message.contains(fragment))
                      .toList();
    }
}
