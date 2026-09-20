package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
        var disk = new InterruptibleDisk();
        var manager = new DefaultSnapshotManager(store, config, disk::write);

        mutate("first");
        manager.forceSnapshot();
        var epochOnDisk = manager.lastSnapshotEpoch();

        disk.failOnCall(2);
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
        var disk = new InterruptibleDisk();
        var manager = new DefaultSnapshotManager(store, config, disk::write);

        mutate("first");
        manager.forceSnapshot();
        var epochOnDisk = manager.lastSnapshotEpoch();

        disk.failOnCall(1);
        mutate("second");
        manager.forceSnapshot();
        assertThat(snapshotFiles()).as("only the complete snapshot remains").containsExactly("snapshot-000001.dat");
        assertThat(tempDir.resolve("snapshot.partial")).as("the torn partial is removed").doesNotExist();
        assertThat(manager.lastSnapshotEpoch()).isEqualTo(epochOnDisk);
        assertThat(disk.tornPath.get()).as("the fixture tore the snapshot write").hasFileName("snapshot.partial");
        assertThat(manager.restoreFromLatest().unwrap().epoch()).isEqualTo(epochOnDisk);
    }

    /// The rename itself fails: the seam makes the `LATEST` partial a DIRECTORY, so `rename(2)`
    /// over the existing `LATEST` fails with ENOTDIR under `ATOMIC_MOVE` and the previous pointer
    /// survives -- while a non-atomic replace unlinks `LATEST` FIRST and the rename then succeeds,
    /// leaving a directory where the pointer was (shape from #676 / verify-1118). Only the second
    /// write of the round is a directory, so the snapshot file itself is complete and this pins
    /// the rename alone. Not a base reproducer: without a partial the fixture's directory collides
    /// with the existing `LATEST` file and the in-place write fails before it can do damage.
    @Test
    void forceSnapshot_renameFails_previousLatestSurvives() {
        var disk = new DirectoryPartialDisk();
        var manager = new DefaultSnapshotManager(store, config, disk::write);

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
        assertThat(appender.warnsMentioning("restored previous retained snapshot")).hasSize(1)
                  .first()
                  .satisfies(warn -> assertThat(warn.message()).contains(newest.getFileName().toString())
                                               .contains("snapshot-000001.dat"));
        assertThat(readLatest()).as("restore does not rewrite LATEST").isEqualTo(latestBefore);
        assertThat(newest).as("the torn file is left as evidence").exists();
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
        assertThat(appender.warnsMentioning("restored previous retained snapshot")).hasSize(1);
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
        var names = FileOps.list(tempDir).unwrap().stream().map(p -> p.getFileName().toString());

        return names.filter(name -> name.startsWith("snapshot-") && name.endsWith(".dat"))
                    .sorted()
                    .toList();
    }

    /// Writes the first half of the content to the path it is given on call N, then fails.
    private static final class InterruptibleDisk {
        private final AtomicInteger failOnCall = new AtomicInteger(Integer.MAX_VALUE);
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<Path> tornPath = new AtomicReference<>();

        void failOnCall(int call) {
            calls.set(0);
            failOnCall.set(call);
        }

        Result<Unit> write(Path path, String content) {
            if (calls.incrementAndGet() != failOnCall.get()) {
                return FileOps.writeString(path, content);
            }

            tornPath.set(path);

            return FileOps.writeString(path,
                                       content.substring(0,
                                                         content.length() / 2))
                          .flatMap(_ -> new FileError.WriteFailed(path, "No space left on device").result());
        }
    }

    /// Creates a directory at the path it is given on call N instead of a file, so that rename
    /// must fail.
    private static final class DirectoryPartialDisk {
        private final AtomicInteger directoryOnCall = new AtomicInteger(Integer.MAX_VALUE);
        private final AtomicInteger calls = new AtomicInteger();

        void directoryOnCall(int call) {
            calls.set(0);
            directoryOnCall.set(call);
        }

        Result<Unit> write(Path path, String content) {
            return calls.incrementAndGet() == directoryOnCall.get()
                   ? FileOps.createDirectories(path).mapToUnit()
                   : FileOps.writeString(path, content);
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
