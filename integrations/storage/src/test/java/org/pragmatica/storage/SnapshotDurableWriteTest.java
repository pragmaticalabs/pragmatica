package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileError;
import org.pragmatica.lang.io.FileOps;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @TempDir
    Path tempDir;

    private InMemoryMetadataStore store;
    private SnapshotConfig config;
    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        store = inMemoryMetadataStore("durable");
        config = snapshotConfig(tempDir, 100, 600_000, 5, NODE_ID);
        appender = CapturingAppender.create("SnapshotDurableWriteCapture");
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);

        loggerConfig = getOrCreateLoggerConfig(ctx.getConfiguration());
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.ALL, null);
        loggerConfig.setLevel(Level.ALL);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        var ctx = (LoggerContext) LogManager.getContext(false);

        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
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
        var restored = manager.restoreFromLatest();

        assertThat(restored.isPresent()).as("the previous complete snapshot still restores").isTrue();
        assertThat(restored.unwrap().epoch()).isEqualTo(epochOnDisk);
        assertThat(appender.warnsMentioning("Snapshot restore failed")).isEmpty();
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
        assertThat(manager.restoreFromLatest().unwrap().epoch()).isEqualTo(epochOnDisk);
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
        assertThat(manager.restoreFromLatest().unwrap().epoch()).isEqualTo(epochOnDisk);
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
        assertThat(manager.restoreFromLatest().isPresent()).as("the counted write is a real one").isTrue();
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
        // callers that both mutated before either captured write the same name with the same bytes.
        assertThat(files).as("the writers wrote something").isNotEmpty();
        assertThat(torn).as("torn snapshot files under a final name").isEmpty();
        assertThat(misnamed).as("files whose content epoch differs from the name").isEmpty();
        assertThat(raceDir.resolve(FileOps.readString(raceDir.resolve("LATEST")).unwrap().trim())).as("LATEST names an existing file")
                  .exists();
        assertThat(appender.warnsMentioning("Snapshot write failed")).isEmpty();
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
        var restored = manager.restoreFromLatest();

        assertThat(restored.isPresent()).as("the previous retained snapshot restores").isTrue();
        assertThat(restored.unwrap().epoch()).isEqualTo(previousEpoch);
        assertThat(restored.unwrap().lifecycles()).hasSize(1);
        assertThat(appender.warnsMentioning("restored another retained snapshot")).hasSize(1)
                  .first()
                  .satisfies(warn -> assertThat(warn.message()).contains(newest.getFileName().toString())
                                               .contains("snapshot-000001.dat"));
        assertThat(readLatest()).as("restore does not rewrite LATEST").isEqualTo(latestBefore);
        assertThat(newest).as("the torn file is left as evidence").exists();
        assertThat(appender.warnsMentioning("Snapshot restore failed")).as("the torn target is tried once, via LATEST, and not again as a candidate")
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
        var restored = manager.restoreFromLatest();

        assertThat(restored.isPresent()).isTrue();
        assertThat(restored.unwrap().epoch()).as("newest complete snapshot, not the oldest").isEqualTo(newestEpoch);
        assertThat(appender.warnsMentioning("restored another retained snapshot")).hasSize(1);
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
        var restored = manager.restoreFromLatest();

        assertThat(restored.unwrap().epoch()).isEqualTo(thirdNewestEpoch);
    }

    /// Nothing complete remains: refuse, at WARN, rather than restore a torn snapshot.
    @Test
    void restoreFromLatest_onlySnapshotTorn_returnsNoneAndWarns() {
        var manager = SnapshotManager.snapshotManager(store, config);

        mutate("first");
        manager.forceSnapshot();
        truncateToHalf(latestTarget());
        var restored = manager.restoreFromLatest();

        assertThat(restored.isEmpty()).isTrue();
        assertThat(appender.warnsMentioning("metadata starts EMPTY")).hasSize(1);
    }

    /// A first boot has no `LATEST` and no snapshots; the fallback must not turn that into a WARN.
    @Test
    void restoreFromLatest_firstBoot_noSnapshotAndNoWarn() {
        var manager = SnapshotManager.snapshotManager(store, config);

        assertThat(manager.restoreFromLatest().isEmpty()).isTrue();
        assertThat(appender.warns()).isEmpty();
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
                                               snapshotConfig(probeDir, 1, 600_000, 10_000, "validate")).restoreFromLatest();
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

    private static LoggerConfig getOrCreateLoggerConfig(Configuration configuration) {
        var existing = configuration.getLoggerConfig(LOGGER);

        if (existing.getName().equals(LOGGER)) {
            return existing;
        }

        var created = new LoggerConfig(LOGGER, Level.ALL, true);

        configuration.addLogger(LOGGER, created);

        return created;
    }

    record Captured(Level level, String message) {}

    private static final class CapturingAppender extends AbstractAppender {
        private final List<Captured> events = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Layout<?> layout) {
            super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            return new CapturingAppender(name, PatternLayout.createDefaultLayout());
        }

        @Override
        public void append(LogEvent event) {
            events.add(new Captured(event.getLevel(),
                                    event.getMessage().getFormattedMessage()));
        }

        List<Captured> warns() {
            return events.stream()
                         .filter(e -> e.level() == Level.WARN)
                         .toList();
        }

        List<Captured> warnsMentioning(String fragment) {
            return warns().stream()
                        .filter(e -> e.message()
                                      .contains(fragment))
                        .toList();
        }
    }
}
